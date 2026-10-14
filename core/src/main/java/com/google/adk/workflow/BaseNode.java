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
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Base class for nodes in a workflow graph. It is meant for the framework's own nodes; to write a
 * node, implement {@link Node}. Subclasses implement {@link #runNode}, and the framework turns each
 * emission into an {@link Event}.
 */
@Experimental
public abstract class BaseNode implements Node {

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
   * Runs the node and emits its events. Each emission of {@link #runNode} that is an {@link Event}
   * passes through, and any other value becomes the output of a new event.
   */
  final Flowable<Event> run(Context context, @Nullable Object nodeInput) {
    InvocationContext invocationContext = checkNotNull(context).invocationContext();
    // The node runner fills in the author, as only it knows the node's place in the graph.
    return Flowable.<Object>defer(() -> runNode(context, nodeInput))
        .map(
            item ->
                item instanceof Event event
                    ? event
                    : Event.builder()
                        .id(invocationContext.newUuid())
                        .timestamp(invocationContext.now().toEpochMilli())
                        .author("")
                        .output(item)
                        .build());
  }
}
