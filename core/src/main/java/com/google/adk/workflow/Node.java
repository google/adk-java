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

  /** Returns the schema the node's input must match. */
  @Experimental
  default Optional<Schema> inputSchema() {
    return Optional.empty();
  }

  /**
   * Returns the schema that the node's output value must match. The content of a message-as-output
   * event is not checked against this schema.
   */
  @Experimental
  default Optional<Schema> outputSchema() {
    return Optional.empty();
  }

  /**
   * Returns the schema declaring the state keys the node uses. Child nodes inherit it unless they
   * declare their own.
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
   * Runs the node on {@code nodeInput}, the output it receives from its predecessors.
   *
   * <p>An emitted {@link Event} passes through, and any other emitted value becomes the node's
   * output (at most one per run); emit nothing for no output, since RxJava forbids null. The
   * wildcard lets an implementation return a {@code Flowable} of its own type.
   */
  @Experimental
  Flowable<?> runNode(Context context, @Nullable Object nodeInput);
}
