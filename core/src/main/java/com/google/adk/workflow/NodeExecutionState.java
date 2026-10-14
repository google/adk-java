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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The state of one workflow node run, held by the run's {@link Context}. Only the workflow runtime
 * creates it and updates the bookkeeping that records which results an event has carried.
 */
@Experimental
public final class NodeExecutionState {

  private final Node node;
  private final NodeRunner.EventSink eventSink;
  private final @Nullable Context parent;
  private final String runId;
  private final String nodePath;
  private String eventAuthor;
  private final Set<String> interruptIds = new LinkedHashSet<>();
  private @Nullable Object output;
  private boolean outputEmitted;
  private @Nullable ImmutableList<Route> selectedRoutes;
  private boolean routesEmitted;
  private @Nullable Exception failure;

  private NodeExecutionState(Builder builder) {
    checkState(builder.node != null, "A node state needs a node.");
    checkState(builder.eventSink != null, "A node state needs an event sink.");
    checkState(builder.runId != null, "A node state needs a run ID.");
    this.node = builder.node;
    this.eventSink = builder.eventSink;
    this.parent = builder.parent;
    this.runId = builder.runId;
    NodeExecutionState parentState = parent == null ? null : parent.requireNodeState();
    this.nodePath =
        NodeRunner.appendSegment(
            parentState == null ? null : parentState.nodePath,
            node.name(),
            runId,
            /* separator= */ '/');
    this.eventAuthor = parentState == null ? "" : parentState.eventAuthor;
  }

  /** Returns a builder for the state of one node run. */
  static Builder builder() {
    return new Builder();
  }

  /** Returns the node this run belongs to. */
  public Node node() {
    return node;
  }

  /**
   * Returns the path of this run: {@code name@runId} segments joined by {@code /}, starting at the
   * outermost node.
   */
  public String nodePath() {
    return nodePath;
  }

  /** Returns the ID of this run among the node's runs, counting from {@code "1"}. */
  public String runId() {
    return runId;
  }

  /** Returns the context of the node that ran this node, or empty if this is a root node. */
  public Optional<Context> parent() {
    return Optional.ofNullable(parent);
  }

  /**
   * Returns the author of the events this run emits, or empty to use the node's name. A run under a
   * parent starts with the parent's author.
   */
  public String eventAuthor() {
    return eventAuthor;
  }

  /** Sets the author of the events this run emits. */
  public void eventAuthor(String eventAuthor) {
    this.eventAuthor = checkNotNull(eventAuthor);
  }

  /** Returns the node's output, or empty if it has produced none. */
  public Optional<Object> output() {
    return Optional.ofNullable(output);
  }

  /**
   * Records the node's output, which an event carries when the node finishes unless an earlier
   * event already carried it. A node produces at most one output.
   *
   * @throws IllegalStateException if the node already has an output.
   */
  public void produceOutput(Object output) {
    checkNotNull(output);
    checkState(
        this.output == null,
        "Node '%s' produced a second output; a node produces at most one output.",
        node.name());
    this.output = output;
  }

  /**
   * Returns the routes the node selected, which decide the outgoing edges to follow, or empty if it
   * made no routing decision. An empty list is a decision that selects no route.
   */
  public Optional<ImmutableList<Route>> selectedRoutes() {
    return Optional.ofNullable(selectedRoutes);
  }

  /**
   * Selects the node's routes, replacing any earlier selection. An event carries them when the node
   * finishes unless an earlier event already carried them.
   */
  public void selectRoutes(List<? extends Route> routes) {
    this.selectedRoutes = ImmutableList.copyOf(routes);
    this.routesEmitted = false;
  }

  /** Returns the IDs of the long-running tool calls the node is waiting on. */
  public ImmutableSet<String> interruptIds() {
    return ImmutableSet.copyOf(interruptIds);
  }

  NodeRunner.EventSink eventSink() {
    return eventSink;
  }

  /** Returns whether an event has carried the node's output. */
  boolean outputEmitted() {
    return outputEmitted;
  }

  void markOutputEmitted() {
    outputEmitted = true;
  }

  /** Returns whether an event has carried the node's current route selection. */
  boolean routesEmitted() {
    return routesEmitted;
  }

  void markRoutesEmitted() {
    routesEmitted = true;
  }

  void addInterruptIds(Collection<String> ids) {
    interruptIds.addAll(ids);
  }

  /** Returns the exception the node failed with, if it failed. */
  Optional<Exception> failure() {
    return Optional.ofNullable(failure);
  }

  void failure(Exception failure) {
    this.failure = checkNotNull(failure);
  }

  /** Builder for {@link NodeExecutionState}. */
  @Experimental
  static final class Builder {
    private @Nullable Node node;
    private NodeRunner.@Nullable EventSink eventSink;
    private @Nullable Context parent;
    private @Nullable String runId;

    private Builder() {}

    /** Sets the node this run belongs to. */
    @CanIgnoreReturnValue
    Builder node(Node node) {
      this.node = checkNotNull(node);
      return this;
    }

    /** Sets the sink that receives the events of the run. */
    @CanIgnoreReturnValue
    Builder eventSink(NodeRunner.EventSink eventSink) {
      this.eventSink = checkNotNull(eventSink);
      return this;
    }

    /**
     * Sets the context of the node that ran this node, or null for a root node. A run under a
     * parent extends the parent's node path and starts with the parent's event author.
     */
    @CanIgnoreReturnValue
    Builder parent(@Nullable Context parent) {
      this.parent = parent;
      return this;
    }

    /** Sets the ID of this run among the node's runs. */
    @CanIgnoreReturnValue
    Builder runId(String runId) {
      this.runId = checkNotNull(runId);
      return this;
    }

    /**
     * Builds the state.
     *
     * @throws IllegalStateException if the node, the event sink, or the run ID is not set.
     */
    NodeExecutionState build() {
      return new NodeExecutionState(this);
    }
  }
}
