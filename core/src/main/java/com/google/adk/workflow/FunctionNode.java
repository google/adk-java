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
import com.google.common.base.Throwables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeEmitter;
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

  private final PortCodec<I> inputCodec;
  private final PortCodec<O> outputCodec;
  private final NodeBody<? super I, ? extends O> body;

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
    this.body = checkNotNull(builder.body);
  }

  /** Returns a builder initialized with this node's properties. */
  public Builder<I, O> toBuilder() {
    return new Builder<>(inputCodec, outputCodec)
        .name(name())
        .description(description())
        .rerunOnResume(rerunOnResume())
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

  /** Runs the body on the calling thread and sends its output or failure to {@code emitter}. */
  private void runBody(Context context, @Nullable Object nodeInput, MaybeEmitter<Object> emitter) {
    Worker<O> worker =
        new Worker<>(() -> {
          I input = inputCodec.decode(nodeInput, "input of node '" + name() + "'");
          return emitter.isDisposed() ? null : body.apply(context, input);
        });
    emitter.setCancellable(worker::cancel);
    Object output;
    try {
      O result = worker.run();
      if (emitter.isDisposed()) {
        return;
      }
      encodeDelegatedOutput(context.requireNodeState());
      // After a delegation, NodeRunner drops a returned output.
      output = output(result);
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
   * the matching {@link Node} property; name and body are required.
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

    /** Sets whether a resume runs the node again rather than using the resuming answer. */
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
     */
    public FunctionNode<I, O> build() {
      checkState(name != null, "FunctionNode.Builder requires name to be set.");
      checkState(body != null, "FunctionNode.Builder for '%s' requires body to be set.", name);
      return new FunctionNode<>(this);
    }
  }
}
