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

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Base class for nodes in a workflow graph. It is meant for the framework's own nodes; to write a
 * node, implement {@link Node}. Subclasses implement {@link #runNode}, and the framework turns each
 * emission into an {@link Event}.
 */
@Experimental
public abstract class BaseNode implements Node {

  /** Writes any value in JSON form, as a {@link FunctionNode} port of type {@code Object} does. */
  private static final PortCodec<Object> OBJECT_PORT =
      PortCodec.output(PortCodec.javaType(Object.class), "an output of type Object");

  private final String name;
  private final String description;
  private final boolean rerunOnResume;
  private final boolean waitForOutput;
  private final NodeConfig config;
  private final @Nullable Schema inputSchema;
  private final @Nullable Schema outputSchema;
  private final @Nullable Schema stateSchema;

  /** Creates a node with every property other than its name and description at its default. */
  protected BaseNode(String name, String description) {
    this(
        name,
        description,
        /* rerunOnResume= */ false,
        /* waitForOutput= */ false,
        NodeConfig.builder().build(),
        /* inputSchema= */ null,
        /* outputSchema= */ null,
        /* stateSchema= */ null);
  }

  /** Creates a node; each parameter sets the {@link Node} property of the same name. */
  protected BaseNode(
      String name,
      String description,
      boolean rerunOnResume,
      boolean waitForOutput,
      NodeConfig config,
      @Nullable Schema inputSchema,
      @Nullable Schema outputSchema,
      @Nullable Schema stateSchema) {
    this.name = checkNotNull(name);
    this.description = checkNotNull(description);
    this.rerunOnResume = rerunOnResume;
    this.waitForOutput = waitForOutput;
    this.config = checkNotNull(config);
    this.inputSchema = inputSchema;
    this.outputSchema = outputSchema;
    this.stateSchema = stateSchema;
  }

  @Override
  public final String name() {
    return name;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public boolean rerunOnResume() {
    return rerunOnResume;
  }

  @Override
  public boolean waitForOutput() {
    return waitForOutput;
  }

  @Override
  public NodeConfig config() {
    return config;
  }

  @Override
  public Optional<Schema> inputSchema() {
    return Optional.ofNullable(inputSchema);
  }

  @Override
  public Optional<Schema> outputSchema() {
    return Optional.ofNullable(outputSchema);
  }

  @Override
  public Optional<Schema> stateSchema() {
    return Optional.ofNullable(stateSchema);
  }

  /**
   * Throws {@link GraphValidationException} if this node cannot serve the {@code edges} that leave
   * it, such as an edge for a route it never emits. The default accepts any edges. Building the
   * workflow graph calls it after its own checks.
   */
  protected void validateOutgoingEdges(Collection<Edge> edges) {}

  /**
   * Runs the node and emits its events. Each emission of {@link #runNode} that is an {@link Event}
   * passes through, a {@link RequestInput} becomes the event that asks for it, and any other value
   * becomes the output of a new event. Each output, emitted or carried by an event, and the input
   * when {@code checkInput} is set, must match the node's schemas; the node receives and hands on
   * the value converted to the schema's types.
   */
  final Flowable<Event> run(Context context, @Nullable Object nodeInput, boolean checkInput) {
    InvocationContext invocationContext = checkNotNull(context).invocationContext();
    // The node runner fills in the author, as only it knows the node's place in the graph.
    return Flowable.<Object>defer(
            () -> runNode(context, checkInput ? checkedInput(nodeInput) : nodeInput))
        .map(item -> {
          if (item instanceof Event event) {
            return event.output().isEmpty()
                ? event
                : event.toBuilder().output(checkedOutput(event.output().get())).build();
          }
          if (item instanceof RequestInput request) {
            return request.toEvent(invocationContext);
          }
          return newEventBuilder(invocationContext).output(checkedOutput(item)).build();
        });
  }

  /**
   * Returns a builder of an event with a new ID and the current time. The node runner fills in the
   * author, as only it knows the node's place in the graph.
   */
  static Event.Builder newEventBuilder(InvocationContext invocationContext) {
    return Event.builder()
        .id(invocationContext.newUuid())
        .timestamp(invocationContext.now().toEpochMilli())
        .author("");
  }

  /**
   * Returns {@code nodeInput} converted to the types of {@link #inputSchema}, which it must match.
   * A node that requires all predecessors receives their outputs keyed by name, so each output must
   * match on its own.
   *
   * @throws IllegalArgumentException if the input does not match
   */
  @Nullable Object checkedInput(@Nullable Object nodeInput) {
    Schema schema = inputSchema().orElse(null);
    if (schema == null || nodeInput == null) {
      return nodeInput;
    }
    if (requiresAllPredecessors() && nodeInput instanceof Map<?, ?> outputs) {
      Map<String, @Nullable Object> checked = new LinkedHashMap<>();
      outputs.forEach((predecessor, output) -> {
        String what = "output of '" + predecessor + "' into node '" + name() + "'";
        // A null output is no output, so there is nothing to check.
        checked.put(
            String.valueOf(predecessor), output == null ? null : coerce(output, schema, what));
      });
      return Collections.unmodifiableMap(checked);
    }
    return coerce(nodeInput, schema, "input of node '" + name() + "'");
  }

  /**
   * Returns {@code output} converted to the types of {@link #outputSchema}, which it must match.
   *
   * @throws IllegalArgumentException if the output does not match
   */
  @Nullable Object checkedOutput(Object output) {
    Schema schema = outputSchema().orElse(null);
    return schema == null ? output : coerce(output, schema, "output of node '" + name() + "'");
  }

  /**
   * Checks {@code value} in JSON form, a record or POJO as an object and an enum by its name, and
   * returns the converted value as a stored session reads it back.
   */
  private static @Nullable Object coerce(Object value, Schema schema, String what) {
    Object coerced = ValueCoercer.coerceValue(jsonForm(value, what), schema, what);
    // The conversion returns mutable collections and a Long for a converted integer.
    return coerced instanceof Event ? coerced : encode(coerced, what);
  }

  /**
   * Returns {@code value} in JSON form, as a {@link FunctionNode} port writes it. A message, an
   * event, or a value already in JSON form stays as it is.
   */
  private static @Nullable Object jsonForm(Object value, String what) {
    return value instanceof Event || isJsonForm(value) ? value : encode(value, what);
  }

  private static @Nullable Object encode(@Nullable Object value, String what) {
    try {
      return OBJECT_PORT.encode(value, what);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("validation error: " + e.getMessage(), e);
    }
  }

  /** Whether {@code value} is JSON-native, with any message in it read as its text or JSON. */
  private static boolean isJsonForm(@Nullable Object value) {
    return value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Content
        || (value instanceof Map<?, ?> map
            && map.entrySet().stream()
                .allMatch(
                    entry -> entry.getKey() instanceof String && isJsonForm(entry.getValue())))
        || (value instanceof Collection<?> items && items.stream().allMatch(BaseNode::isJsonForm));
  }
}
