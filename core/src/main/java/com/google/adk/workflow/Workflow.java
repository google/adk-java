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
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A graph of nodes that is itself a node, so a workflow can go wherever a node can, including in
 * another workflow. Building a workflow validates its graph and throws {@link
 * GraphValidationException} for a malformed one.
 */
@Experimental
public final class Workflow extends BaseNode {

  private final ImmutableList<Edge> edges;
  private final @Nullable Integer maxConcurrency;
  private final @Nullable Graph graph;

  private Workflow(Builder builder) {
    super(
        builder.name,
        builder.description,
        builder.rerunOnResume,
        builder.waitForOutput,
        builder.config,
        builder.inputSchema,
        builder.outputSchema,
        builder.stateSchema);
    NodeNames.validate(name());
    this.edges = builder.edges;
    this.maxConcurrency = builder.maxConcurrency;
    this.graph = edges.isEmpty() ? null : Graph.of(edges);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns the edges of the workflow's graph. */
  public ImmutableList<Edge> edges() {
    return edges;
  }

  /**
   * Returns the maximum number of nodes the workflow runs at once, or empty if there is no limit.
   */
  public Optional<Integer> maxConcurrency() {
    return Optional.ofNullable(maxConcurrency);
  }

  /** Returns the graph built from the edges, or empty when there are no edges. */
  Optional<Graph> graph() {
    return Optional.ofNullable(graph);
  }

  /**
   * Runs the graph from its start node, which hands {@code nodeInput} to its successors. The output
   * of the sole terminal node that produces one becomes the workflow's output. If more than one
   * terminal node produces an output, the returned {@code Flowable} fails with {@link
   * WorkflowConfigurationException}.
   */
  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    Graph runGraph = graph;
    if (runGraph == null) {
      return Flowable.empty();
    }
    return Completable.defer(
            () -> {
              // Inner nodes inherit the workflow as event author; agent nodes keep their own.
              context.requireNodeState().eventAuthor(name());
              return new Scheduler(this, runGraph, context).run(nodeInput);
            })
        .toFlowable();
  }

  /**
   * Builder for {@link Workflow}. Only the name is required. Unlike other nodes, a workflow reruns
   * from scratch when resumed unless {@link #rerunOnResume} turns that off.
   */
  public static final class Builder {
    private @Nullable String name;
    private ImmutableList<Edge> edges = ImmutableList.of();
    private String description = "";
    private @Nullable Integer maxConcurrency;
    private boolean rerunOnResume = true;
    private boolean waitForOutput = false;
    private NodeConfig config = NodeConfig.builder().build();
    private @Nullable Schema inputSchema;
    private @Nullable Schema outputSchema;
    private @Nullable Schema stateSchema;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder edges(List<Edge> edges) {
      this.edges = ImmutableList.copyOf(edges);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder edges(Edge... edges) {
      return edges(ImmutableList.copyOf(edges));
    }

    /**
     * Sets the edges that {@code graphDefinition} adds to a new {@link EdgesBuilder}.
     *
     * <p>For example: {@code .edges(g -> g.fromStart().chain(a, b))}.
     */
    @CanIgnoreReturnValue
    public Builder edges(Consumer<? super EdgesBuilder> graphDefinition) {
      EdgesBuilder builder = new EdgesBuilder();
      graphDefinition.accept(builder);
      return edges(builder.build());
    }

    @CanIgnoreReturnValue
    public Builder description(String description) {
      this.description = checkNotNull(description);
      return this;
    }

    /**
     * Sets the maximum number of nodes to run at once, which must be at least 1. A workflow without
     * one has no limit.
     */
    @CanIgnoreReturnValue
    public Builder maxConcurrency(int maxConcurrency) {
      checkArgument(
          maxConcurrency >= 1, "maxConcurrency must be at least 1, but was %s.", maxConcurrency);
      this.maxConcurrency = maxConcurrency;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder rerunOnResume(boolean rerunOnResume) {
      this.rerunOnResume = rerunOnResume;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder waitForOutput(boolean waitForOutput) {
      this.waitForOutput = waitForOutput;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder config(NodeConfig config) {
      this.config = checkNotNull(config);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder inputSchema(@Nullable Schema inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputSchema(@Nullable Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder stateSchema(@Nullable Schema stateSchema) {
      this.stateSchema = stateSchema;
      return this;
    }

    /**
     * Builds the workflow.
     *
     * @throws IllegalArgumentException if the workflow's name or the name of a node in its graph is
     *     empty or contains {@code /}, {@code @}, or {@code .}
     * @throws GraphValidationException if the graph is malformed, for example if {@link Start}
     *     cannot reach one of its nodes
     */
    public Workflow build() {
      checkState(name != null, "Workflow.Builder requires name to be set.");
      return new Workflow(this);
    }
  }
}
