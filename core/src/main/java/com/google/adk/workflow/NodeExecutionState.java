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
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The state of one workflow node run, held by the run's {@link Context}. Only the workflow runtime
 * creates it and updates the bookkeeping that records which results an event has carried. Its
 * output, interrupt IDs and child run IDs are safe to update from several threads, since children
 * that a node runs itself may start and finish on other threads.
 */
@Experimental
public final class NodeExecutionState {

  private static final OutputState NO_OUTPUT = new OutputState(OutputStatus.NONE, null);
  private static final OutputState DELEGATED_OUTPUT = new OutputState(OutputStatus.DELEGATED, null);

  private final Node node;
  private final NodeRunner.EventSink eventSink;
  private final @Nullable Context parent;
  // The parent's state when this run's output is also the parent's.
  private final @Nullable NodeExecutionState outputParent;
  private final String runId;
  private final String nodePath;
  private final ImmutableList<String> outputFor;
  private final Map<String, @Nullable Object> resumeInputs;
  private final ConcurrentMap<String, Integer> childRunCounts = new ConcurrentHashMap<>();
  // The paths of the node runs started but not finished in this turn, shared under one root.
  private final Set<String> unfinishedNodePaths;
  private final Set<String> interruptIds = new CopyOnWriteArraySet<>();
  private final AtomicReference<OutputState> outputState = new AtomicReference<>(NO_OUTPUT);
  private String eventAuthor;
  private @Nullable ImmutableList<Route> selectedRoutes;
  private boolean routesEmitted;
  private @Nullable Exception failure;
  private @Nullable String failureNodePath;
  private @Nullable ImmutableMap<String, ResumeScan.RecoveredNode> recoveredChildren;

  private NodeExecutionState(Builder builder) {
    checkState(builder.node != null, "A node state needs a node.");
    checkState(builder.eventSink != null, "A node state needs an event sink.");
    checkState(builder.runId != null, "A node state needs a run ID.");
    this.node = builder.node;
    this.eventSink = builder.eventSink;
    this.parent = builder.parent;
    this.runId = builder.runId;
    NodeExecutionState parentState = parent == null ? null : parent.requireNodeState();
    this.outputParent = builder.useAsOutput ? parentState : null;
    this.nodePath =
        NodeRunner.appendSegment(
            parentState == null ? null : parentState.nodePath,
            node.name(),
            runId,
            /* separator= */ '/');
    this.outputFor =
        outputParent != null
            ? ImmutableList.<String>builder().add(nodePath).addAll(outputParent.outputFor).build()
            : ImmutableList.of(nodePath);
    this.eventAuthor = parentState == null ? "" : parentState.eventAuthor;
    this.unfinishedNodePaths =
        parentState == null ? ConcurrentHashMap.newKeySet() : parentState.unfinishedNodePaths;
    this.resumeInputs = Collections.unmodifiableMap(new LinkedHashMap<>(builder.resumeInputs));
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

  /**
   * Returns the node's output, which is the output of its {@code useAsOutput} child once that child
   * emits one, or empty if it has none.
   */
  public Optional<Object> output() {
    return Optional.ofNullable(outputState.get().value());
  }

  /**
   * Records the node's output, which an event carries when the node finishes unless an earlier
   * event already carried it. A node produces at most one output.
   *
   * @throws IllegalStateException if the node already has an output, or if it delegated its output
   *     to a {@code useAsOutput} child.
   */
  public void produceOutput(Object output) {
    checkNotNull(output);
    outputState.updateAndGet(
        current -> {
          checkState(
              !current.status().isDelegated(),
              "Node '%s' delegated its output to a useAsOutput child and must not produce one of"
                  + " its own.",
              node.name());
          checkState(
              current.status() == OutputStatus.NONE,
              "Node '%s' produced a second output; a node produces at most one output.",
              node.name());
          return new OutputState(OutputStatus.PRODUCED, output);
        });
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

  /**
   * Returns the user's answers to the long-running calls that this node or its descendants raised
   * in an earlier turn, keyed by interrupt ID. A null answer has a null value in the map. The map
   * is empty unless the node reruns on resume.
   */
  public Map<String, @Nullable Object> resumeInputs() {
    return resumeInputs;
  }

  NodeRunner.EventSink eventSink() {
    return eventSink;
  }

  /**
   * Returns the paths of the nodes that use this run's output as their output, starting with this
   * run's path.
   */
  ImmutableList<String> outputFor() {
    return outputFor;
  }

  /** Returns whether this run's output is also its parent's output, as with {@code useAsOutput}. */
  boolean outputCountsForParent() {
    return outputParent != null;
  }

  /** Returns the next run ID for a child node named {@code name}, counting from {@code "1"}. */
  String nextChildRunId(String name) {
    return String.valueOf(childRunCounts.merge(name, 1, Integer::sum));
  }

  /** Records that this run started; it counts as canceled mid-run until it finishes. */
  void markStarted() {
    unfinishedNodePaths.add(nodePath);
  }

  /** Records that this run finished, with or without a result. */
  void markFinished() {
    unfinishedNodePaths.remove(nodePath);
  }

  /**
   * Returns whether this run's child {@code childKey}, a {@code name@runId} segment, started in
   * this turn and has not finished. When read before the child runs again, this means a failing
   * sibling or caller canceled it.
   */
  boolean childCanceledMidRun(String childKey) {
    return unfinishedNodePaths.contains(nodePath + "/" + childKey);
  }

  /**
   * Returns the states of this node's children from earlier turns, keyed by {@code name@runId},
   * which the first call reconstructs from the events of {@code invocationContext}.
   */
  synchronized ImmutableMap<String, ResumeScan.RecoveredNode> recoveredChildren(
      InvocationContext invocationContext) {
    if (recoveredChildren == null) {
      recoveredChildren = ResumeScan.scan(invocationContext, nodePath);
    }
    return recoveredChildren;
  }

  /** Returns whether an event has carried the node's own or delegated output. */
  boolean outputEmitted() {
    OutputStatus status = outputState.get().status();
    return status == OutputStatus.EMITTED || status == OutputStatus.DELEGATE_EMITTED;
  }

  /**
   * Records that an event carried the output the node produced, which also makes it the output of
   * each ancestor that delegated its output to this run.
   *
   * @throws IllegalStateException if the node has no unsent output.
   */
  void markOutputEmitted() {
    Object emitted =
        outputState
            .updateAndGet(
                current -> {
                  checkState(
                      current.status() == OutputStatus.PRODUCED,
                      "Node '%s' has no unsent output to mark as emitted.",
                      node.name());
                  return new OutputState(OutputStatus.EMITTED, current.value());
                })
            .value();
    for (NodeExecutionState ancestor = outputParent;
        ancestor != null;
        ancestor = ancestor.outputParent) {
      ancestor.recordDelegatedOutput(emitted);
    }
  }

  /**
   * Records {@code output}, emitted by a {@code useAsOutput} descendant, as the node's output if
   * the node delegated its output. A later output replaces an earlier one, as when a workflow
   * records its own output after its terminal node's.
   */
  private void recordDelegatedOutput(@Nullable Object output) {
    outputState.updateAndGet(
        current ->
            current.status().isDelegated()
                ? new OutputState(OutputStatus.DELEGATE_EMITTED, output)
                : current);
  }

  /**
   * Delegates the node's output to a {@code useAsOutput} child.
   *
   * @throws IllegalStateException if the node already has an output or a delegate.
   */
  void claimOutputDelegation() {
    outputState.updateAndGet(
        current -> {
          checkState(
              !current.status().isDelegated(),
              "Node '%s' already delegated its output to a useAsOutput child.",
              node.name());
          checkState(
              current.status() == OutputStatus.NONE,
              "Node '%s' already produced an output and cannot delegate it to a useAsOutput child.",
              node.name());
          return DELEGATED_OUTPUT;
        });
  }

  /** Withdraws the delegation of the node's output, unless the delegate has emitted an output. */
  void releaseOutputDelegation() {
    outputState.compareAndSet(DELEGATED_OUTPUT, NO_OUTPUT);
  }

  /**
   * Records {@code output} from a {@code useAsOutput} child as this node's own unsent output when
   * no event carried it for this run, as when the child replays an earlier turn's output.
   *
   * @throws IllegalStateException if the node has no pending delegation
   */
  void adoptReplayedOutput(Object output) {
    checkState(
        outputState.compareAndSet(
            DELEGATED_OUTPUT, new OutputState(OutputStatus.PRODUCED, checkNotNull(output))),
        "Node '%s' adopted a replayed output without a pending delegation.",
        node.name());
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

  /** Records that the node failed with {@code failure}, which it raised itself. */
  void failure(Exception failure) {
    failure(failure, nodePath);
  }

  /**
   * Records that the node failed with {@code failure}, which the node at {@code failureNodePath}
   * raised.
   */
  void failure(Exception failure, String failureNodePath) {
    this.failure = checkNotNull(failure);
    this.failureNodePath = checkNotNull(failureNodePath);
  }

  /**
   * Returns the path of the node that raised {@link #failure()}: this run's path, or the path of a
   * descendant whose failure the node carried up. Empty if the node has not failed.
   */
  Optional<String> failureNodePath() {
    return Optional.ofNullable(failureNodePath);
  }

  /** Status of a run's output. */
  private enum OutputStatus {
    /** The run has no output and no delegate. */
    NONE,
    /** A {@code useAsOutput} child owns the run's output and has emitted none yet. */
    DELEGATED,
    /** The run produced an output that no event has carried yet. */
    PRODUCED,
    /** An event carried the output the run produced. */
    EMITTED,
    /** An event of a {@code useAsOutput} descendant carried the run's output. */
    DELEGATE_EMITTED;

    boolean isDelegated() {
      return this == DELEGATED || this == DELEGATE_EMITTED;
    }
  }

  /** A run's output status and its output, held together so that each update is atomic. */
  private record OutputState(OutputStatus status, @Nullable Object value) {}

  /** Builder for {@link NodeExecutionState}. */
  @Experimental
  static final class Builder {
    private @Nullable Node node;
    private NodeRunner.@Nullable EventSink eventSink;
    private @Nullable Context parent;
    private @Nullable String runId;
    private boolean useAsOutput;
    private Map<String, @Nullable Object> resumeInputs = ImmutableMap.of();

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

    /** Sets whether this run's output is also its parent's output. */
    @CanIgnoreReturnValue
    Builder useAsOutput(boolean useAsOutput) {
      this.useAsOutput = useAsOutput;
      return this;
    }

    /** Sets the answers the node resumes with, keyed by interrupt ID. */
    @CanIgnoreReturnValue
    Builder resumeInputs(Map<String, @Nullable Object> resumeInputs) {
      this.resumeInputs = checkNotNull(resumeInputs);
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
