/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.telemetry.Tracing;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Phaser;
import org.jspecify.annotations.Nullable;

/**
 * A workflow node that runs a Java function on each input, with a type per port; build one with
 * {@link FunctionNodes#of} or {@link FunctionNodes#builder}. An input port reads its input by type:
 *
 * <ul>
 *   <li>{@code Void} ignores it; {@code Object}, or a type the input already has, takes it as is.
 *   <li>Any other type rejects an {@link Event} and takes a {@link Content} message as its
 *       non-thought text: the text itself if the port's schema is a string, as for {@code String}
 *       or an enum ({@code byte[]} reads it as base64), and otherwise the text's JSON, or the text
 *       if it is not JSON.
 *   <li>Jackson converts that value, or any other input, to the port's type.
 * </ul>
 *
 * <p>Every output, including the output of a {@code useAsOutput} child, is written as {@code O} in
 * JSON form, so a successor reads the same value live or from a stored session.
 *
 * <p>The body may send events before its output with {@link Context#emit(Event)}:
 *
 * <ul>
 *   <li>State: a write made before {@code emit} is sent with that event, or with an event another
 *       thread sent just before it; a later write is sent with a later event or the output, and
 *       reads on the calling thread after {@code emit} returns see the body's own writes.
 *   <li>Threads: any thread the body uses may call it, and a call waits even if the body returns; a
 *       call deadlocks in a {@code ctx.runNode(...)} callback such as {@code doOnSuccess}, which
 *       runs inside the dispatch. A body that calls it cannot run inline, so on a {@code Runner}
 *       that does, such as {@code Schedulers.trampoline()} or a direct executor, set a {@link
 *       Builder#scheduler} that does not, such as {@code Schedulers.io()}; ADK Kotlin runs bodies
 *       on {@code Dispatchers.IO}, where none is needed.
 *   <li>Events: a missing ID is filled in; long-running tool IDs, a route or a transfer act as on a
 *       returned event; a partial event is sent without waiting, not stored, and carries no state
 *       writes; the events precede the output unless a {@code useAsOutput} child already sent it.
 *   <li>Cancellation: a cancel of the node, or an interrupt of the waiting thread, ends the wait
 *       with a {@code CancellationException}, and the event may still be stored; after a cancel
 *       every call throws one and the output is dropped.
 *   <li>Resumes: a body rerun after a pause sends its events again. If the turn is canceled after
 *       an emitted event was stored, a later resume reruns the body only if the node sets {@code
 *       waitForOutput}, or runs through {@code ctx.runNode} or as the app's root node with {@code
 *       rerunOnResume} false.
 * </ul>
 *
 * <p>Only the workflow runner runs a {@code FunctionNode}; from another node's body, run it with
 * {@code ctx.runNode}, since calling {@link #runNode} directly fails with an {@code
 * IllegalStateException}.
 */
@Experimental
public final class FunctionNode<I, O> extends BaseNode {

  static final PortCodec<Object> ANY =
      PortCodec.output(PortCodec.javaType(Object.class), "an event output");

  private static final ConfirmationPredicate<Object> ALWAYS_CONFIRM = (context, input) -> true;

  /** The output of a run the user rejected. */
  private static final ImmutableMap<String, String> REJECTED_OUTPUT =
      ImmutableMap.of("error", "This tool call is rejected.");

  private final PortCodec<I> inputCodec;
  private final PortCodec<O> outputCodec;
  private final NodeBody<? super I, ? extends O> body;
  private final @Nullable ConfirmationPredicate<? super I> confirmationPredicate;
  private final @Nullable Boolean explicitRerunOnResume;
  private final @Nullable Scheduler scheduler;

  private FunctionNode(Builder<I, O> builder) {
    super(
        checkNotNull(builder.name),
        builder.description,
        builder.rerunOnResume != null
            ? builder.rerunOnResume
            : builder.confirmationPredicate != null,
        builder.waitForOutput,
        builder.config,
        builder.inputSchema,
        builder.outputSchema,
        builder.stateSchema);
    this.inputCodec = builder.inputCodec;
    this.outputCodec = builder.outputCodec;
    this.body = checkNotNull(builder.body);
    this.confirmationPredicate = builder.confirmationPredicate;
    this.explicitRerunOnResume = builder.rerunOnResume;
    this.scheduler = builder.scheduler;
  }

  /**
   * Returns a builder initialized with this node's properties. A {@code rerunOnResume} the node
   * derived from its confirmation is left unset, so the copy derives it from its own.
   */
  public Builder<I, O> toBuilder() {
    Builder<I, O> builder = new Builder<>(inputCodec, outputCodec);
    builder.rerunOnResume = explicitRerunOnResume;
    builder.confirmationPredicate = confirmationPredicate;
    return builder
        .name(name())
        .description(description())
        .waitForOutput(waitForOutput())
        .config(config())
        // Only explicit schemas carry over; the copy infers the others from its ports.
        .inputSchema(super.inputSchema().orElse(null))
        .outputSchema(super.outputSchema().orElse(null))
        .stateSchema(stateSchema().orElse(null))
        .scheduler(scheduler)
        .body(body);
  }

  /**
   * Returns the schema that describes the input port: the one set on the builder or, if none, one
   * inferred from the input type, where an {@code Object}, {@code Void} or other type with no fixed
   * JSON shape infers none. An edge into an inferred record or POJO fails at build if the
   * producer's output schema lists a key the type does not, while a builder schema checks only the
   * keys it lists and requires. On a {@code Content} port a builder schema describes the message
   * text, and an edge is checked against the {@code Content} schema instead. On an {@code Event} or
   * genai port, or a list, map or array of them, a builder schema does not let the port take keys
   * its type lacks.
   */
  @Override
  public Optional<Schema> inputSchema() {
    return super.inputSchema().or(() -> Optional.ofNullable(inputCodec.schema()));
  }

  /**
   * Returns the schema that describes the output port: the one set on the builder or, if none, one
   * inferred from the output type. A returned {@code Event} is sent as it is, so an {@code Event}
   * output infers none.
   */
  @Override
  public Optional<Schema> outputSchema() {
    return super.outputSchema().or(() -> Optional.ofNullable(outputCodec.schema()));
  }

  /** Whether {@link #inputSchema} was inferred rather than set on the builder. */
  boolean inputSchemaInferred() {
    return super.inputSchema().isEmpty() && inputCodec.schema() != null;
  }

  /** Returns the schema an edge into this node must fit, the inferred one on a Content port. */
  Optional<Schema> edgeInputSchema() {
    return inputCodec.takesContent() ? Optional.ofNullable(inputCodec.schema()) : inputSchema();
  }

  /** Whether a builder schema would let this port take keys its type does not list. */
  boolean acceptsExtraKeysWithASchema() {
    return inputSchemaInferred() && !inputCodec.rejectsUnknownKeys();
  }

  /** Whether {@link #edgeInputSchema} was inferred, so its objects with properties are closed. */
  boolean edgeInputSchemaInferred() {
    return inputCodec.takesContent() || inputSchemaInferred();
  }

  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    // defer, so that each subscription has its own body run and keeps its caller's trace context.
    return Flowable.defer(() -> runOnce(context, nodeInput));
  }

  private Flowable<Object> runOnce(Context context, @Nullable Object nodeInput) {
    io.opentelemetry.context.Context traceContext = io.opentelemetry.context.Context.current();
    Scheduler runnerScheduler = context.invocationContext().scheduler();
    Scheduler bodyScheduler = scheduler != null ? scheduler : runnerScheduler;
    BodyRun run = new BodyRun();
    NodeExecutionState state = context.requireNodeState();
    // Only the runner gives a run a state of its own; claimed on subscribe, before any user code.
    if (state.node() != this || !state.claimBodyRun(run)) {
      return Flowable.error(
          new IllegalStateException(
              "Node '"
                  + name()
                  + "' runs only through the workflow runner; to run it from a node's body, call"
                  + " ctx.runNode."));
    }
    // create rather than defer, so that canceling the node reaches the body while it runs.
    Flowable<Object> outcome =
        Flowable.<Object>create(
                emitter -> {
                  // The timeout waits for the body to return, so a retry cannot overlap it.
                  CompletableSubject returned = CompletableSubject.create();
                  // Not on the body's thread, which may be interrupted, or the busy Runner.
                  state.markBodyStarted(returned.observeOn(Schedulers.computation()));
                  try {
                    // Serialized, as the body's helper threads may emit at the same time.
                    runBody(context, nodeInput, run, emitter.serialize());
                  } finally {
                    returned.onComplete();
                  }
                },
                BackpressureStrategy.BUFFER)
            .compose(Tracing.withContext(traceContext));
    // Unlike subscribeOn, canceling this hop does not interrupt the handling of the body's output.
    Flowable<Object> events =
        // Via a Maybe, as andThen(Publisher) can interrupt the thread on a cancel.
        Completable.complete()
            .observeOn(bodyScheduler)
            .andThen(Maybe.just(outcome))
            .flatMapPublisher(f -> f);
    // Back on the Runner's scheduler, through an Executor so that a cancel cannot interrupt it.
    return (bodyScheduler.equals(runnerScheduler)
            ? events
            : events
                .observeOn(Schedulers.from(runnerScheduler::scheduleDirect), /* delayError= */ true)
                .compose(Tracing.withContext(traceContext)))
        // A cancel ends the waits; after completion, each wait ends when its event is stored.
        .doOnCancel(run::cancel);
  }

  /**
   * Runs the confirmation predicate and the body on the calling thread and sends the body's events,
   * then its outcome, to {@code events}. Canceling the node interrupts that thread.
   */
  private void runBody(
      Context context, @Nullable Object nodeInput, BodyRun run, FlowableEmitter<Object> events) {
    Worker<RunOutcome<O>> worker =
        new Worker<>(() -> {
          try {
            return runFunction(context, nodeInput, run, events);
          } finally {
            run.close();
          }
        });
    events.setCancellable(worker::cancel);
    Object output;
    try {
      RunOutcome<O> outcome = worker.run();
      if (outcome == null || events.isCancelled()) {
        return;
      }
      output = outcome.replacement() != null ? outcome.replacement() : finishRun(context, outcome);
    } catch (Exception | NodeInterruptedException e) {
      // tryOnError, as a canceled run can no longer take a failure or a pause.
      events.tryOnError(e);
      return;
    }
    if (output != null) {
      events.onNext(output);
    }
    events.onComplete();
  }

  /**
   * Asks for or reads the user's confirmation, then runs the body unless the rejection or the
   * request replaces it. Runs in the worker, as the predicate is user code like the body.
   */
  private @Nullable RunOutcome<O> runFunction(
      Context context, @Nullable Object nodeInput, BodyRun run, FlowableEmitter<Object> events)
      throws Exception {
    String what = "input of node '" + name() + "'";
    I input = inputCodec.decode(nodeInput, what);
    ToolConfirmation confirmation =
        confirmationPredicate != null ? ToolConfirmations.answer(context, name()) : null;
    if (confirmation != null) {
      context.toolConfirmation(confirmation);
      if (!confirmation.confirmed()) {
        return new RunOutcome<>(confirmation, REJECTED_OUTPUT, null);
      }
    } else if (confirmationPredicate != null
        && !events.isCancelled()
        && confirmationPredicate.test(context, input)) {
      RequestInput request =
          ToolConfirmations.request(
              context,
              name(),
              inputCodec.encode(input, what),
              /* hint= */ null,
              /* confirmationPayload= */ null);
      return new RunOutcome<>(null, request, null);
    }
    run.open(events);
    return events.isCancelled()
        ? null
        : new RunOutcome<>(confirmation, null, body.apply(context, input));
  }

  /**
   * Returns the event or JSON value to send for a run of the body, or {@code null} for none, after
   * writing a delegated output in this node's output form.
   */
  private @Nullable Object finishRun(Context context, RunOutcome<O> outcome) {
    NodeExecutionState state = context.requireNodeState();
    encodeDelegatedOutput(state);
    Object output = output(outcome.result());
    if (outcome.confirmation() != null && state.delegatedOutput().isEmpty()) {
      // An approved run always leaves an event, so a resume finds it finished.
      if (state.hasOutputDelegate()) {
        return output instanceof Event event
            ? event.toBuilder().output(null).build()
            : newEventBuilder(context.invocationContext()).build();
      }
      if (output == null) {
        return newEventBuilder(context.invocationContext()).build();
      }
    }
    // After a delegation, NodeRunner drops a returned output.
    return output;
  }

  /**
   * One run: the user's answer to its confirmation request, if any, and either what replaces the
   * body (a rejection or a confirmation request) or the body's result.
   */
  private record RunOutcome<O>(
      @Nullable ToolConfirmation confirmation, @Nullable Object replacement, @Nullable O result) {}

  /**
   * Writes the output of a child the body gave its output to with {@code useAsOutput} as if the
   * body had returned it, and has the node send it as its own output if that changes it.
   *
   * @throws IllegalArgumentException if the child's output is not an {@code O}
   */
  private void encodeDelegatedOutput(NodeExecutionState state) {
    Optional<Object> delegated = state.delegatedOutput();
    if (delegated.isEmpty()) {
      return;
    }
    Class<?> outputType = outputCodec.rawType();
    checkArgument(
        outputType != Void.class,
        "node '%s' declares no output (Void) but gives a child its output; declare its output"
            + " type.",
        name());
    String what = "output of node '" + name() + "'";
    Object encoded;
    if (outputType == Event.class) {
      // A value is not an Event, so it goes out in JSON form, as a returned event's output does.
      encoded = ANY.encode(delegated.get(), what);
    } else {
      try {
        encoded = outputCodec.encode(outputCodec.decode(delegated.get(), what), what);
      } catch (NodeInputValidationException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
    }
    if (encoded != null && !encoded.equals(delegated.get())) {
      state.replaceDelegatedOutput(encoded);
    }
  }

  /** Returns the event or JSON value to send for {@code result}, or {@code null} for none. */
  private @Nullable Object output(@Nullable O result) {
    String what = "output of node '" + name() + "'";
    if (result instanceof RequestInput) {
      throw new IllegalArgumentException(
          "node '"
              + name()
              + "' returned a RequestInput, which a NodeBody cannot send; call"
              + " FunctionNodes.requestInput(...) instead.");
    }
    if (result instanceof Event event) {
      // A returned event's output is sent in JSON form too, as a stored session hands it back.
      return event.output().isEmpty()
          ? event
          : event.toBuilder().output(ANY.encode(event.output().get(), what)).build();
    }
    return outputCodec.encode(result, what);
  }

  /**
   * Runs one body on the thread that calls {@link #run}, and {@link #cancel} interrupts that thread
   * only while the body runs. When a canceled body finishes, the worker clears the thread's
   * interrupt so its next task does not see it, unless the thread was interrupted before the body
   * ran. This also clears an interrupt from elsewhere, such as {@code shutdownNow()}, which a
   * {@code ThreadPoolExecutor} sets again before any task it starts once stopping.
   */
  private static final class Worker<T> {
    private final FutureTask<T> task;

    Worker(Callable<T> body) {
      this.task = new FutureTask<>(body);
    }

    @SuppressWarnings("Interruption") // Interrupting the body is how a cancel reaches it.
    void cancel() {
      task.cancel(/* mayInterruptIfRunning= */ true);
    }

    /** Runs the body and returns its result, or {@code null} if it was canceled. */
    @Nullable T run() throws Exception {
      boolean callerInterrupted = Thread.currentThread().isInterrupted();
      task.run();
      if (task.isCancelled()) {
        if (!callerInterrupted) {
          var unused = Thread.interrupted();
        }
        return null;
      }
      try {
        return task.get(); // Done, so get does not block.
      } catch (ExecutionException e) {
        Throwables.throwIfInstanceOf(e.getCause(), Exception.class);
        Throwables.throwIfUnchecked(e.getCause());
        throw e;
      }
    }
  }

  /** Delivers the events of one body run's {@code Context.emit} calls. */
  static final class BodyRun {
    // Counts the calls from admission to the end of their send; close waits for them.
    private final Phaser sending = new Phaser(1);

    @GuardedBy("this")
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();

    @GuardedBy("this")
    private @Nullable FlowableEmitter<Object> events;

    @GuardedBy("this")
    private boolean canceled;

    synchronized void open(FlowableEmitter<Object> events) {
      this.events = events;
    }

    /**
     * Sends {@code event} and waits for its store unless it is partial. Returns false once the body
     * has returned, and throws a CancellationException once the node is canceled.
     */
    boolean emit(Event event) {
      boolean partial = event.partial().orElse(false);
      CompletableFuture<Void> processed = new CompletableFuture<>();
      FlowableEmitter<Object> sink;
      synchronized (this) {
        if (canceled) {
          throw new CancellationException("The node was canceled.");
        }
        if (events == null) {
          return false;
        }
        checkArgument(
            event.output().isEmpty()
                && !event.nodeInfo().map(NodeInfo::messageAsOutput).orElse(false),
            "Context.emit takes an event without an output; return the value from the body.");
        checkArgument(
            partial || pending.putIfAbsent(event.id(), processed) == null,
            "Another Context.emit call still waits on an event with this ID.");
        sending.register();
        sink = events;
      }
      try {
        sink.onNext(event); // Outside the lock: the downstream may run on this thread.
      } finally {
        sending.arriveAndDeregister();
      }
      if (!partial) {
        awaitStore(processed);
      }
      return true;
    }

    /** Waits until the run stores the event; a cancel or an interrupt ends the wait. */
    private static void awaitStore(CompletableFuture<Void> processed) {
      try {
        processed.get(); // Throws CancellationException once the node is canceled.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Interrupted while waiting for the event's store.");
      } catch (ExecutionException e) {
        throw new IllegalStateException(e); // Unreachable: the signal never fails.
      }
    }

    /** Stops admitting events, then waits until each admitted call has handed its event on. */
    void close() {
      synchronized (this) {
        events = null;
      }
      // Afterwards serialize() has sent or queued every admitted event, so all precede the output.
      sending.arriveAndAwaitAdvance();
    }

    /** Ends each wait with a CancellationException, and makes every later call throw one. */
    synchronized void cancel() {
      canceled = true;
      pending.values().forEach(signal -> signal.cancel(/* mayInterruptIfRunning= */ false));
      pending.clear();
    }

    synchronized void markProcessed(@Nullable String eventId) {
      CompletableFuture<Void> signal = pending.remove(eventId);
      if (signal != null) {
        signal.complete(null);
      }
    }
  }

  /**
   * Fluent builder for {@link FunctionNode}. Any property left unset falls back to the default of
   * the matching {@link Node} property, except that {@code rerunOnResume} defaults to true for a
   * node that requires confirmation; name and body are required.
   */
  public static final class Builder<I, O> {
    private final PortCodec<I> inputCodec;
    private final PortCodec<O> outputCodec;
    private @Nullable String name;
    private String description = "";
    private @Nullable Boolean rerunOnResume;
    private @Nullable ConfirmationPredicate<? super I> confirmationPredicate;
    private boolean waitForOutput;
    private NodeConfig config = NodeConfig.builder().build();
    private @Nullable Schema inputSchema;
    private @Nullable Schema outputSchema;
    private @Nullable Schema stateSchema;
    private @Nullable NodeBody<? super I, ? extends O> body;
    private @Nullable Scheduler scheduler;

    Builder(PortCodec<I> inputCodec, PortCodec<O> outputCodec) {
      this.inputCodec = inputCodec;
      this.outputCodec = outputCodec;
    }

    /** Sets the node's name, unique within its graph. Required. */
    @CanIgnoreReturnValue
    public Builder<I, O> name(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    /** Sets what the node does, for humans and for a model that may call it. */
    @CanIgnoreReturnValue
    public Builder<I, O> description(String description) {
      this.description = checkNotNull(description);
      return this;
    }

    /**
     * Sets whether a resume runs the node again rather than using the resuming answer. Unset, it is
     * true for a node that requires confirmation and false otherwise. A body that runs child nodes
     * through {@link Context#runNode(Node, Object, RunNodeConfig)} requires {@code true}: on resume
     * the body runs again from the start, a child that asked resumes with its answer, and the other
     * children resume as {@link RunNodeConfig#runId} describes.
     */
    @CanIgnoreReturnValue
    public Builder<I, O> rerunOnResume(boolean rerunOnResume) {
      this.rerunOnResume = rerunOnResume;
      return this;
    }

    /** Sets whether the node stays re-triggerable until it produces an output or a route. */
    @CanIgnoreReturnValue
    public Builder<I, O> waitForOutput(boolean waitForOutput) {
      this.waitForOutput = waitForOutput;
      return this;
    }

    /** Sets the node's retry policy and execution timeout. */
    @CanIgnoreReturnValue
    public Builder<I, O> config(NodeConfig config) {
      this.config = checkNotNull(config);
      return this;
    }

    /**
     * Sets the schema that describes the node's input, in place of one inferred from its type. On a
     * {@code Content} port, the schema describes the message's text.
     */
    @CanIgnoreReturnValue
    public Builder<I, O> inputSchema(@Nullable Schema inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    /** Sets the schema that describes the node's output, in place of one inferred from its type. */
    @CanIgnoreReturnValue
    public Builder<I, O> outputSchema(@Nullable Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    /**
     * Sets the state keys the node uses, which child nodes inherit unless they declare their own.
     */
    @CanIgnoreReturnValue
    public Builder<I, O> stateSchema(@Nullable Schema stateSchema) {
      this.stateSchema = stateSchema;
      return this;
    }

    /**
     * Sets whether each run first asks the user to approve it; either overload replaces the other.
     * Such a node reruns on resume, so a body that asks for input reads its answer from {@link
     * Context#resumeInputs()} before asking. An approved run reads the user's decision from {@link
     * Context#toolConfirmation()}, and a rejected run skips the body and outputs {@code {"error":
     * "This tool call is rejected."}} whatever the output type, so a successor that must handle a
     * rejection takes {@code Object}.
     */
    @CanIgnoreReturnValue
    public Builder<I, O> requireConfirmation(boolean requireConfirmation) {
      this.confirmationPredicate = requireConfirmation ? ALWAYS_CONFIRM : null;
      return this;
    }

    /**
     * Sets a predicate that decides for each input whether a run asks the user to approve it, as
     * the {@code boolean} overload does for every input. Once the node has asked, the answer
     * decides.
     */
    @CanIgnoreReturnValue
    public Builder<I, O> requireConfirmation(ConfirmationPredicate<? super I> predicate) {
      this.confirmationPredicate = checkNotNull(predicate);
      return this;
    }

    /** Sets the function the node runs on each input. Required. */
    @CanIgnoreReturnValue
    public Builder<I, O> body(NodeBody<? super I, ? extends O> body) {
      this.body = checkNotNull(body);
      return this;
    }

    /**
     * Sets the scheduler that decodes the input and runs the confirmation predicate and the body,
     * or {@code null} (the default) for the {@code Runner}'s scheduler. A body that calls {@link
     * Context#emit} cannot run inline, and bodies that wait for one another need a thread each, so
     * a small fixed-size scheduler can hang the run; ADK Kotlin instead defaults to {@code
     * Dispatchers.IO}, which gives each waiting body a thread. Once that work ends, even when a
     * confirmation skips the body, the outcome is handed back on the {@code Runner}'s scheduler,
     * unless that scheduler equals this one (two {@code Schedulers.from} of one executor do not) or
     * runs work inline, in which case it stays on this thread. The scheduler must accept this work:
     * a rejected body leaves the run waiting, as a {@code Runner} scheduler that rejects work does.
     */
    @CanIgnoreReturnValue
    public Builder<I, O> scheduler(@Nullable Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /**
     * Builds the node.
     *
     * @throws IllegalStateException if the name or the body is not set
     * @throws WorkflowConfigurationException if the node requires confirmation but {@code
     *     rerunOnResume} is set to false
     */
    public FunctionNode<I, O> build() {
      checkState(name != null, "FunctionNode.Builder requires name to be set.");
      checkState(body != null, "FunctionNode.Builder for '%s' requires body to be set.", name);
      if (confirmationPredicate != null && rerunOnResume != null && !rerunOnResume) {
        throw new WorkflowConfigurationException(
            "node '"
                + name
                + "' requires confirmation, so it needs rerunOnResume(true) to run again once the"
                + " user answers.");
      }
      return new FunctionNode<>(this);
    }
  }
}
