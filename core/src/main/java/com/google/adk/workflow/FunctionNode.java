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
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeEmitter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.jspecify.annotations.Nullable;

/**
 * A workflow node that runs a Java function on each input, with a type per port; build one with
 * {@link FunctionNodes#of} or {@link FunctionNodes#builder}. An {@code Object} input port takes its
 * input as it is, a {@code Content} or {@code Event} port reads its own type, a {@code Void} port
 * ignores its input, and Jackson reads any other type, so a user message reaches only a {@code
 * Content} or {@code Object} port. Every output is written in JSON form, so a successor reads the
 * same value whether it comes live or from a stored session. A body that gives its output to a
 * child with {@code useAsOutput} outputs the child's output written as {@code O}, as if it had
 * returned it.
 */
@Experimental
public final class FunctionNode<I, O> extends BaseNode {

  private static final PortCodec<Object> ANY =
      PortCodec.output(PortCodec.javaType(Object.class), "an event output");

  private static final String CONFIRMATION_INTERRUPT_PREFIX = "wf_tool_confirmation:";

  private static final ConfirmationPredicate<Object> ALWAYS_CONFIRM = (context, input) -> true;

  /** The output of a run the user rejected. */
  private static final ImmutableMap<String, String> REJECTED_OUTPUT =
      ImmutableMap.of("error", "This tool call is rejected.");

  /** The schema of an answer to a confirmation request. */
  private static final Schema TOOL_CONFIRMATION_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "hint", Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                  "confirmed", Schema.builder().type(Type.Known.BOOLEAN).nullable(true).build(),
                  "payload", Schema.builder().build()))
          .build();

  private final PortCodec<I> inputCodec;
  private final PortCodec<O> outputCodec;
  private final NodeBody<? super I, ? extends O> body;
  private final @Nullable ConfirmationPredicate<? super I> confirmationPredicate;
  private final @Nullable Boolean explicitRerunOnResume;

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
        .body(body);
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
    // Maybe.create rather than defer, so that canceling the node reaches the body while it runs.
    return Maybe.<Object>create(emitter -> runBody(context, nodeInput, emitter))
        .subscribeOn(context.invocationContext().scheduler())
        .toFlowable();
  }

  /**
   * Runs the confirmation predicate and the body on the calling thread and sends the output or
   * failure to {@code emitter}. Canceling the node interrupts that thread.
   */
  private void runBody(Context context, @Nullable Object nodeInput, MaybeEmitter<Object> emitter) {
    Worker<RunOutcome<O>> worker = new Worker<>(() -> runFunction(context, nodeInput, emitter));
    emitter.setCancellable(worker::cancel);
    Object output;
    try {
      RunOutcome<O> outcome = worker.run();
      if (outcome == null || emitter.isDisposed()) {
        return;
      }
      output = outcome.replacement() != null ? outcome.replacement() : finishRun(context, outcome);
    } catch (Exception e) {
      // tryOnError, as a canceled run can no longer take a failure.
      emitter.tryOnError(e);
      return;
    }
    if (output == null) {
      emitter.onComplete();
    } else {
      emitter.onSuccess(output);
    }
  }

  /**
   * Asks for or reads the user's confirmation, then runs the body unless the rejection or the
   * request replaces it. Runs in the worker, as the predicate is user code like the body.
   */
  private @Nullable RunOutcome<O> runFunction(
      Context context, @Nullable Object nodeInput, MaybeEmitter<Object> emitter) throws Exception {
    I input = inputCodec.decode(nodeInput, "input of node '" + name() + "'");
    ToolConfirmation confirmation = confirmationAnswer(context);
    if (confirmation != null) {
      context.toolConfirmation(confirmation);
      if (!confirmation.confirmed()) {
        return new RunOutcome<>(confirmation, REJECTED_OUTPUT, null);
      }
    } else if (confirmationPredicate != null
        && !emitter.isDisposed()
        && confirmationPredicate.test(context, input)) {
      return new RunOutcome<>(null, confirmationRequest(context, input), null);
    }
    return emitter.isDisposed()
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
            : finishedEvent(context);
      }
      if (output == null) {
        return finishedEvent(context);
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

  private static Event finishedEvent(Context context) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .timestamp(context.invocationContext().now().toEpochMilli())
        .author("")
        .build();
  }

  private static String confirmationInterruptId(Context context) {
    return CONFIRMATION_INTERRUPT_PREFIX + context.requireNodeState().nodePath();
  }

  /** Returns the request that asks the user to approve this run with {@code input}. */
  private RequestInput confirmationRequest(Context context, @Nullable I input) {
    String interruptId = confirmationInterruptId(context);
    Map<String, @Nullable Object> payload = new LinkedHashMap<>();
    payload.put("tool_name", name());
    payload.put("args", inputCodec.encode(input, "input of node '" + name() + "'"));
    return RequestInput.builder()
        .interruptId(interruptId)
        .message("Please approve or reject the tool call " + name() + "().")
        .payload(payload)
        .responseSchema(TOOL_CONFIRMATION_SCHEMA)
        .build();
  }

  /**
   * Returns the user's answer to this run's confirmation request, or null if the node requires no
   * confirmation or has no answer yet. A field left out or null takes its default.
   *
   * @throws WorkflowDataException if the answer is not a {@link ToolConfirmation}
   */
  private @Nullable ToolConfirmation confirmationAnswer(Context context) {
    if (confirmationPredicate == null) {
      return null;
    }
    Object answer = context.resumeInputs().get(confirmationInterruptId(context));
    if (answer == null) {
      return null;
    }
    if (!(answer instanceof Map<?, ?> map)
        || !TOOL_CONFIRMATION_SCHEMA.properties().orElseThrow().keySet().containsAll(map.keySet())
        || (map.get("confirmed") != null && !(map.get("confirmed") instanceof Boolean))
        || (map.get("hint") != null && !(map.get("hint") instanceof String))) {
      throw new WorkflowDataException(
          "The answer to the confirmation request of node '"
              + name()
              + "' is not a ToolConfirmation.");
    }
    ToolConfirmation.Builder confirmation = ToolConfirmation.builder();
    if (map.get("hint") instanceof String hint) {
      confirmation.hint(hint);
    }
    return confirmation
        .confirmed(map.get("confirmed") instanceof Boolean confirmed && confirmed)
        .payload(map.get("payload"))
        .build();
  }

  /** Returns the event or JSON value to send for {@code result}, or {@code null} for none. */
  private @Nullable Object output(@Nullable O result) {
    String what = "output of node '" + name() + "'";
    if (result instanceof RequestInput) {
      throw new IllegalArgumentException(
          "node '"
              + name()
              + "' returned a RequestInput, which a NodeBody cannot send; return the output"
              + " instead.");
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
     * true for a node that requires confirmation and false otherwise.
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
