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

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.sessions.StateSchemaException;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A unit of work in a workflow graph. Node names must be unique within a graph. Every member except
 * {@link #name} and {@link #description} is experimental, so implementing this interface is too.
 */
public interface Node {

  /** Returns the name that identifies the node within its graph. */
  String name();

  /** Returns what the node does, for humans and for a model that may call it. */
  default String description() {
    return "";
  }

  /**
   * Returns {@code true} if a resumed node runs again from scratch, rather than completing with the
   * resuming answer as its output.
   */
  @Experimental
  default boolean rerunOnResume() {
    return false;
  }

  /**
   * Returns {@code true} if the node stays re-triggerable until it produces an output or a route,
   * instead of completing when its run completes.
   */
  @Experimental
  default boolean waitForOutput() {
    return false;
  }

  /** Returns the node's retry policy and execution timeout. */
  @Experimental
  default NodeConfig config() {
    return NodeConfig.builder().build();
  }

  /**
   * Returns the schema the node's input must match. An edge into the node fails at build when its
   * source's output schema lists a key or type this one rejects, so an open producer such as a map
   * builds, and a {@link FunctionNode} on a {@code Content} port checks the edge against the {@code
   * Content} schema. A node that requires all predecessors applies it to each predecessor's output,
   * not the joined map. A run checks a node's schemas only for type, {@code nullable}, {@code
   * enum}, {@code anyOf}, {@code properties}, {@code required} and {@code items}; it ignores {@code
   * minimum}, {@code maximum}, {@code pattern}, lengths and {@code format}.
   */
  @Experimental
  default Optional<Schema> inputSchema() {
    return Optional.empty();
  }

  /**
   * Returns the schema that the node's output value must match. The content of a message-as-output
   * event is not checked against this schema. A run checks a record, POJO or enum input or output
   * in its JSON form and hands on that form: a map for a record, the name for an enum.
   */
  @Experimental
  default Optional<Schema> outputSchema() {
    return Optional.empty();
  }

  /**
   * Returns the schema of the state keys that the node and its children may write; a child inherits
   * it unless it declares its own. The runtime converts writes through {@link Context#state()} in a
   * node or in a tool node's tool, emitted events' state deltas and tool nodes' deltas to the
   * declared type, throwing {@link StateSchemaException} on a mismatch or undeclared key; a {@link
   * com.google.adk.tools.FunctionTool} lets it through, so it fails the node. Keys with a colon and
   * a node's direct writes to its own {@link Context#eventActions()}'s state delta pass unchecked.
   * A stored value reads back in the form a stored session gives: lists and maps unmodifiable, a
   * record as a map, an enum by its name, a small whole number as an {@code Integer}.
   */
  @Experimental
  default Optional<Schema> stateSchema() {
    return Optional.empty();
  }

  /**
   * Returns {@code true} if the node runs only once every predecessor has completed, receiving all
   * their outputs keyed by node name, as a fan-in node does.
   */
  @Experimental
  default boolean requiresAllPredecessors() {
    return false;
  }

  /**
   * Runs the node on {@code nodeInput}, the output it receives from its predecessors. The workflow
   * runner calls this method; to run a node from another node's body, call {@link
   * Context#runNode(Node, Object)}.
   *
   * <p>An emitted {@link Event} passes through, an emitted {@link RequestInput} pauses the workflow
   * until the user answers it, and any other emitted value becomes the node's output (at most one
   * per run); emit nothing for no output, since RxJava forbids null. The wildcard lets an
   * implementation return a {@code Flowable} of its own type.
   */
  @Experimental
  Flowable<?> runNode(Context context, @Nullable Object nodeInput);
}
