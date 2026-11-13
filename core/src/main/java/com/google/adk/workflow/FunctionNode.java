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
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
import org.jspecify.annotations.Nullable;

/**
 * A workflow node that runs a Java function on each input, with a type per port; build one with
 * {@link FunctionNodes#of}, {@link FunctionNodes#ofFlowable} or {@link FunctionNodes#builder}. An
 * {@code Object} input port takes its input as it is, a {@code Content} or {@code Event} port reads
 * its own type, a {@code Void} port ignores its input, and Jackson reads any other type, so a user
 * message reaches only a {@code Content} or {@code Object} port. Every output is written in JSON
 * form, so a successor reads the same value whether it comes live or from a stored session. A body
 * that gives its output to a child with {@code useAsOutput} outputs the child's output written as
 * {@code O}, as if it had returned it.
 */
@Experimental
public final class FunctionNode<I, O> extends BaseNode {

  private static final PortCodec<Object> ANY =
      PortCodec.output(PortCodec.javaType(Object.class), "an event output");

  private final PortCodec<I> inputCodec;
  private final PortCodec<O> outputCodec;
  private final @Nullable NodeBody<? super I, ? extends O> body;
  private final @Nullable FlowableNodeBody<? super I> flowableBody;
  private final @Nullable Scheduler scheduler;

  private FunctionNode(Builder<I, O> builder) {
    super(
        checkNotNull(builder.name),
        builder.description,
        builder.rerunOnResume,
        builder.waitForOutput,
        builder.config,
        builder.inputSchema,
        builder.outputSchema,
        builder.stateSchema);
    this.inputCodec = builder.inputCodec;
    this.outputCodec = builder.outputCodec;
    this.body = builder.body;
    this.flowableBody = builder.flowableBody;
    this.scheduler = builder.scheduler;
  }

  /** Returns a builder initialized with this node's properties. */
  public Builder<I, O> toBuilder() {
    Builder<I, O> builder =
        new Builder<>(inputCodec, outputCodec)
            .name(name())
            .description(description())
            .rerunOnResume(rerunOnResume())
            .waitForOutput(waitForOutput())
            .config(config())
            // Only explicit schemas carry over; the copy infers the others from its ports.
            .inputSchema(super.inputSchema().orElse(null))
            .outputSchema(super.outputSchema().orElse(null))
            .stateSchema(stateSchema().orElse(null))
            .scheduler(scheduler);
    return body != null ? builder.body(body) : builder.flowableBody(checkNotNull(flowableBody));
  }

  /**
   * Returns the schema that describes the input port: the one set on the builder or, if none, one
   * inferred from the input type, where an {@code Object}, {@code Void} or other type with no fixed
   * JSON shape infers none. An edge into an inferred record or POJO fails at build if the
   * producer's output schema lists a key the type does not, while a builder schema checks only the
   * keys it lists and requires. On a {@code Content} port a builder schema describes the message
   * text, and an edge is checked against the {@code Content} schema instead.
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
    return inputSchemaInferred() && !inputCodec.takesContent();
  }

  /** Whether {@link #edgeInputSchema} was inferred, so its objects with properties are closed. */
  boolean edgeInputSchemaInferred() {
    return inputCodec.takesContent() || inputSchemaInferred();
  }

  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    FlowableNodeBody<? super I> rxBody = flowableBody;
    if (rxBody != null) {
      return Flowable.defer(() ->
              rxBody.apply(context, inputCodec.decode(nodeInput, "input of node '" + name() + "'")))
          .mapOptional(item ->
              item instanceof RequestInput
                  ? Optional.of(item)
                  : Optional.ofNullable(
                      item instanceof Event event ? emitted(event) : output(item)))
          // After a delegation, NodeRunner drops a returned output.
          .doOnComplete(() -> encodeDelegatedOutput(context.requireNodeState()));
    }
    NodeBody<? super I, ? extends O> plainBody = checkNotNull(body);
    // A fresh run per subscription, so a resubscribed node starts clean.
    return Flowable.defer(() -> runPlain(context, nodeInput, plainBody));
  }

  private Flowable<Object> runPlain(
      Context context, @Nullable Object nodeInput, NodeBody<? super I, ? extends O> plainBody) {
    BodyRun run = new BodyRun(context.invocationContext());
    return Flowable.<Object>create(
            emitter -> {
              if (emitter.isCancelled()) {
                return;
              }
              // Serialized, as the body's helper threads may emit at the same time.
              FlowableEmitter<Object> events = emitter.serialize();
              Worker worker = new Worker();
              emitter.setCancellable(worker::cancel);
              Object output;
              try {
                O result;
                try {
                  I input = inputCodec.decode(nodeInput, "input of node '" + name() + "'");
                  run.open(events);
                  context.requireNodeState().bodyRun(run);
                  result = emitter.isCancelled() ? null : plainBody.apply(context, input);
                } finally {
                  run.close();
                  worker.close();
                }
                if (emitter.isCancelled()) {
                  return;
                }
                encodeDelegatedOutput(context.requireNodeState());
                // After a delegation, NodeRunner drops a returned output.
                output = output(result);
              } catch (Exception e) {
                // tryOnError, as a canceled run can no longer take a failure.
                events.tryOnError(e);
                return;
              }
              if (output != null) {
                events.onNext(output);
              }
              events.onComplete();
            },
            BackpressureStrategy.BUFFER)
        // io() by default, as the Runner's scheduler may be inline or bounded. Unlike subscribeOn,
        // canceling this hop does not interrupt the thread once the body has returned.
        .compose(source ->
            Completable.complete()
                .observeOn(scheduler != null ? scheduler : Schedulers.io())
                // Via a Maybe, as andThen(Publisher) can interrupt the thread on a cancel.
                .andThen(Maybe.just(source))
                .flatMapPublisher(flowable -> flowable))
        // Only a cancel ends the waits; completion leaves them to their events.
        .doOnCancel(run::cancel);
  }

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
    Object encoded = writeAsOutput(delegated.get(), "output of node '" + name() + "'");
    if (encoded != null && !encoded.equals(delegated.get())) {
      state.replaceDelegatedOutput(encoded);
    }
  }

  /**
   * Returns the answer to the node's request {@code interruptIds} read as {@code O} and written in
   * JSON form, as a returned output is; a {@code Void} node outputs nothing.
   *
   * @throws WorkflowDataException if the answer is not an {@code O}
   */
  @Nullable Object answerAsOutput(@Nullable Object answer, Collection<String> interruptIds) {
    String what =
        String.format(
            "answer to node '%s' (interrupt '%s')", name(), String.join("', '", interruptIds));
    try {
      return answer == null ? null : writeAsOutput(answer, what);
    } catch (IllegalArgumentException e) {
      throw new WorkflowDataException(e.getMessage(), e);
    }
  }

  private @Nullable Object writeAsOutput(Object value, String what) {
    if (outputCodec.rawType() == Event.class) {
      // A value is not an Event, so it goes out in JSON form, as a returned event's output does.
      return ANY.encode(value, what);
    }
    try {
      return outputCodec.encode(outputCodec.decode(value, what), what);
    } catch (NodeInputValidationException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /**
   * Returns the event to send for an event a flowable body emitted. It writes the event's output as
   * the output type does, unless the port is an {@code Event}.
   *
   * @throws IllegalArgumentException if it is marked message-as-output, or its output does not fit
   *     the output type
   */
  private Event emitted(Event event) {
    checkArgument(
        !event.nodeInfo().map(NodeInfo::messageAsOutput).orElse(false),
        "node '%s' emitted an Event marked messageAsOutput; send the message as a plain event and"
            + " return the Content.",
        name());
    if (event.output().isEmpty()) {
      return event;
    }
    // An Event port gives an event's output no type, so it goes out as a returned event's does.
    PortCodec<?> codec = outputCodec.rawType() == Event.class ? ANY : outputCodec;
    String what = "output of node '" + name() + "'";
    return event.toBuilder().output(codec.encode(event.output().get(), what)).build();
  }

  /** Returns the event or JSON value to send for {@code result}, or {@code null} for none. */
  private @Nullable Object output(@Nullable Object result) {
    String what = "output of node '" + name() + "'";
    if (result instanceof RequestInput) {
      throw new IllegalArgumentException(
          "node '"
              + name()
              + "' returned a RequestInput, which a NodeBody cannot send; emit it from a"
              + " FlowableNodeBody instead.");
    }
    if (result instanceof Event event) {
      // A returned event's output is sent in JSON form too, as a stored session hands it back.
      return event.output().isEmpty()
          ? event
          : event.toBuilder().output(ANY.encode(event.output().get(), what)).build();
    }
    return outputCodec.encode(result, what);
  }

  /** The thread running one body; canceling the node interrupts it while the body runs. */
  private static final class Worker {
    private final Thread thread = Thread.currentThread();
    private final Object lock = new Object();
    // An interrupt the caller set before the body ran, which close leaves in place.
    private final boolean callerInterrupted = thread.isInterrupted();

    @GuardedBy("lock")
    private boolean running = true;

    @GuardedBy("lock")
    private boolean interrupted;

    @SuppressWarnings("Interruption") // Interrupting the body is how a cancel reaches it.
    void cancel() {
      synchronized (lock) {
        if (running) {
          interrupted = true;
          thread.interrupt();
        }
      }
    }

    void close() {
      synchronized (lock) {
        running = false;
        // Clears the interrupt cancel set, so it does not reach the thread's next task.
        if (interrupted && !callerInterrupted) {
          var unused = Thread.interrupted();
        }
      }
    }
  }

  /**
   * One run of a plain body: sends its {@code Context.emit} events and releases each call once its
   * event is processed, even after the body returned, or on a cancel.
   */
  static final class BodyRun {
    final InvocationContext invocationContext;
    // The calls between their open check and the end of their send, which close waits for.
    private final Phaser sending = new Phaser(1);

    @GuardedBy("this")
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();

    @GuardedBy("this")
    private @Nullable FlowableEmitter<Object> events;

    @GuardedBy("this")
    private boolean open;

    @GuardedBy("this")
    private boolean canceled;

    // Set once a call has thrown for the cancel, so later calls send nothing instead.
    @GuardedBy("this")
    private boolean cancelReported;

    BodyRun(InvocationContext invocationContext) {
      this.invocationContext = invocationContext;
    }

    synchronized void open(FlowableEmitter<Object> events) {
      this.events = events;
      this.open = true;
    }

    /** Sends {@code event} and waits for it, or returns false once the body has returned. */
    boolean emit(Event event) {
      boolean partial = event.partial().orElse(false);
      CompletableFuture<Void> processed = new CompletableFuture<>();
      FlowableEmitter<Object> sink;
      synchronized (this) {
        if (!open) {
          return false;
        }
        if (canceled) {
          if (cancelReported) {
            return true;
          }
          cancelReported = true;
          throw new CancellationException("The node was canceled.");
        }
        checkArgument(
            partial || pending.putIfAbsent(event.id(), processed) == null,
            "Another Context.emit call still waits on an event with this ID.");
        sending.register();
        sink = checkNotNull(events);
      }
      try {
        sink.onNext(event); // Outside the lock: the downstream may run on this thread.
      } finally {
        sending.arriveAndDeregister();
      }
      if (!partial) {
        try {
          processed.join(); // Throws CancellationException once the node is canceled.
        } finally {
          synchronized (this) {
            pending.remove(event.id(), processed);
          }
        }
      }
      return true;
    }

    /** Takes no more events once the body returns, after the ones being sent are handed on. */
    void close() {
      synchronized (this) {
        open = false;
      }
      // serialize() has sent or queued each admitted event by now, so all precede the output.
      sending.arriveAndAwaitAdvance();
    }

    /** Ends each wait with a CancellationException, or makes the next call throw one. */
    synchronized void cancel() {
      canceled = true;
      cancelReported |= !pending.isEmpty();
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
   * the matching {@link Node} property; name and either body or flowableBody are required.
   */
  public static final class Builder<I, O> {
    private final PortCodec<I> inputCodec;
    private final PortCodec<O> outputCodec;
    private @Nullable String name;
    private String description = "";
    private boolean rerunOnResume;
    private boolean waitForOutput;
    private NodeConfig config = NodeConfig.builder().build();
    private @Nullable Schema inputSchema;
    private @Nullable Schema outputSchema;
    private @Nullable Schema stateSchema;
    private @Nullable NodeBody<? super I, ? extends O> body;
    private @Nullable FlowableNodeBody<? super I> flowableBody;
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
     * Sets whether a resume runs the node again rather than using the resuming answer. A body that
     * runs child nodes through {@link Context#runNode} requires {@code true}: a resume runs the
     * body again, replays each child that recorded an output and resumes those that asked, as
     * {@link RunNodeConfig#runId} describes. The rerun sends the body's {@code ctx.emit} events
     * again, and a child's pause must propagate from {@code blockingGet()}, as {@link
     * Context#runNode} describes.
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

    /** Sets the function the node runs on each input, in place of any body set before. */
    @CanIgnoreReturnValue
    public Builder<I, O> body(NodeBody<? super I, ? extends O> body) {
      this.body = checkNotNull(body);
      this.flowableBody = null;
      return this;
    }

    /** Sets a body that returns a {@code Flowable}, in place of any body set before. */
    @CanIgnoreReturnValue
    public Builder<I, O> flowableBody(FlowableNodeBody<? super I> flowableBody) {
      this.flowableBody = checkNotNull(flowableBody);
      this.body = null;
      return this;
    }

    /**
     * Sets the scheduler a plain {@link NodeBody} runs on, or {@code null} (the default) for {@code
     * Schedulers.io()}; a {@link FlowableNodeBody} is subscribed on the calling thread instead. The
     * scheduler needs a free thread for every body that blocks or waits, so an inline or small
     * fixed-size scheduler can hang the run.
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
     */
    public FunctionNode<I, O> build() {
      checkState(name != null, "FunctionNode.Builder requires name to be set.");
      checkState(
          body != null || flowableBody != null,
          "FunctionNode.Builder for '%s' requires body or flowableBody to be set.",
          name);
      return new FunctionNode<>(this);
    }
  }
}
