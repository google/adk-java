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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A workflow node that runs a Java function on each input, with a type per port. Jackson reads each
 * input into the input type and writes each output in JSON form, so records and POJOs work as
 * ports, and a successor reads the same value whether it comes live or from a stored session. Build
 * one with {@link FunctionNodes#of} or {@link FunctionNodes#builder}.
 */
@Experimental
public final class FunctionNode<I, O> extends BaseNode {

  private static final PortCodec<Object> ANY =
      PortCodec.output(PortCodec.javaType(Object.class), "an event output");

  private final PortCodec<I> inputCodec;
  private final PortCodec<O> outputCodec;
  private final NodeBody<I, O> body;

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
   * inferred from the input type. An {@code Object}, {@code Void} or other type with no fixed JSON
   * shape infers none.
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

  /** Whether {@link #outputSchema} was inferred rather than set on the builder. */
  boolean outputSchemaInferred() {
    return super.outputSchema().isEmpty() && outputCodec.schema() != null;
  }

  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    return Flowable.defer(
            () -> {
              I input = inputCodec.decode(nodeInput, "input of node '" + name() + "'");
              WorkerScope scope = new WorkerScope(context);
              O result;
              try {
                result = body.apply(scope, input);
              } finally {
                scope.close();
              }
              return Flowable.fromOptional(Optional.ofNullable(output(result)));
            })
        .subscribeOn(context.invocationContext().scheduler());
  }

  /** Returns the event or JSON value to send for {@code result}, or {@code null} for none. */
  private @Nullable Object output(@Nullable O result) {
    String what = "output of node '" + name() + "'";
    if (result instanceof Event event) {
      // A returned event's output is sent in JSON form too, as a stored session hands it back.
      return event.output().isEmpty()
          ? event
          : event.toBuilder().output(ANY.encode(event.output().get(), what)).build();
    }
    return outputCodec.encode(result, what);
  }

  /** The scope of one body run, closed once the body returns. */
  private static final class WorkerScope implements NodeScope {
    private final Context context;
    private volatile boolean open = true;

    WorkerScope(Context context) {
      this.context = context;
    }

    @Override
    public Context context() {
      checkState(open, "A NodeScope works only while its body runs.");
      return context;
    }

    void close() {
      open = false;
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
    private @Nullable NodeBody<I, O> body;

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

    /** Sets the schema that describes the node's input, in place of one inferred from its type. */
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
    public Builder<I, O> body(NodeBody<I, O> body) {
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
