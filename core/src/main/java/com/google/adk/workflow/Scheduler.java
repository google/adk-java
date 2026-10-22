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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

/** One run of one workflow's scheduling loop. Discarded when the run ends. */
@Experimental
final class Scheduler {

  private final Workflow workflow;
  private final Graph graph;
  private final Context context;
  private final NodeExecutionState workflowState;
  private final Map<String, NodeState> nodeStates = new HashMap<>();
  private final Map<String, @Nullable Object> nodeOutputs = new HashMap<>();
  // The branch each completed node ran on, so a join can re-merge parallel branches.
  private final Map<String, @Nullable String> nodeBranches = new HashMap<>();
  private final TriggerQueue triggerQueue = new TriggerQueue();
  private final Set<String> interruptIds = new LinkedHashSet<>();

  Scheduler(Workflow workflow, Graph graph, Context context) {
    this.workflow = workflow;
    this.graph = graph;
    this.context = context;
    this.workflowState = context.requireNodeState();
  }

  /** Runs the graph from its start node, which hands {@code nodeInput} to its successors. */
  Completable run(@Nullable Object nodeInput) {
    return Completable.defer(
        () -> {
          enqueueStartTriggers(nodeInput);
          return new Loop().drive();
        });
  }

  private void enqueueStartTriggers(@Nullable Object nodeInput) {
    ImmutableList<Edge> startEdges =
        graph.edges().stream()
            .filter(edge -> edge.from().name().equals(Start.NAME))
            .collect(toImmutableList());
    // A start node with more than one successor puts each on its own sub-branch.
    boolean useSubBranch = startEdges.size() > 1;
    for (Edge edge : startEdges) {
      if (edge.to().requiresAllPredecessors()) {
        // The node must still wait for its other predecessors, so record the start node's input.
        nodeOutputs.put(Start.NAME, nodeInput);
        nodeBranches.put(Start.NAME, context.invocationContext().branch().orElse(null));
        enqueueBarrierTrigger(edge.to().name());
        continue;
      }
      triggerQueue.enqueue(
          edge.to().name(), new Trigger(nodeInput, useSubBranch, /* branch= */ null));
    }
  }

  /**
   * The scheduling loop. Node runs report their completions through a serialized signal that the
   * loop handles one batch at a time, so its state needs no locks. It runs until no node is running
   * or can start, or until a node fails.
   */
  private final class Loop {
    private final Map<String, Disposable> running = new LinkedHashMap<>();
    private final CompositeDisposable childRuns = new CompositeDisposable();
    private final Queue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final FlowableProcessor<Boolean> wakeUps =
        UnicastProcessor.<Boolean>create().toSerialized();
    private boolean failed;

    Completable drive() {
      return Completable.defer(
              () -> {
                scheduleReady();
                stopIfIdle();
                return wakeUps.doOnNext(unused -> handleCompletions()).ignoreElements();
              })
          .andThen(Completable.defer(() -> failed ? Completable.complete() : finalizeRun()))
          .doFinally(childRuns::dispose);
    }

    /** Handles the node runs that finished since the last wake-up. */
    private void handleCompletions() {
      // A failure ends the run, so a completion queued behind it must not start successors.
      if (failed) {
        return;
      }
      List<Completion> batch = new ArrayList<>();
      for (Completion completion; (completion = completions.poll()) != null; ) {
        batch.add(completion);
        childRuns.delete(running.remove(completion.nodeName()));
      }
      if (batch.isEmpty()) {
        return;
      }
      // Act on a failure first, or the successors of a node that finished beside it would start.
      if (handleFailures(batch)) {
        return;
      }
      batch.forEach(completion -> handleCompletion(completion.nodeName(), completion.context()));
      scheduleReady();
      stopIfIdle();
    }

    /**
     * Fails the run with the first failed node in {@code batch}, canceling the nodes still running,
     * and returns whether a node failed.
     */
    private boolean handleFailures(List<Completion> batch) {
      for (Completion completion : batch) {
        NodeExecutionState childState = completion.context().requireNodeState();
        Optional<Exception> failure = childState.failure();
        if (failure.isPresent()) {
          workflowState.failure(failure.get(), childState.failureNodePath().orElseThrow());
          failed = true;
          childRuns.dispose();
          wakeUps.onComplete();
          return true;
        }
      }
      return false;
    }

    private void stopIfIdle() {
      if (running.isEmpty()) {
        wakeUps.onComplete();
      }
    }

    private void scheduleReady() {
      Optional<Integer> maxConcurrency = workflow.maxConcurrency();
      for (String nodeName : triggerQueue.queuedNodeNames()) {
        if (running.containsKey(nodeName)) {
          continue;
        }
        // A node waiting on a user's answers keeps its triggers until it is resumed.
        NodeState progress = nodeStates.get(nodeName);
        if (progress != null
            && progress.status == NodeStatus.WAITING
            && !progress.interrupts.isEmpty()) {
          continue;
        }
        if (maxConcurrency.isPresent() && running.size() >= maxConcurrency.get()) {
          break;
        }
        triggerQueue.poll(nodeName).ifPresent(trigger -> start(nodeName, trigger));
      }
    }

    private void start(String nodeName, Trigger trigger) {
      String runId = workflowState.nextChildRunId(nodeName);
      nodeStates.put(nodeName, new NodeState(NodeStatus.RUNNING));
      Disposable run =
          NodeRunner.builder()
              .node(graph.node(nodeName))
              .parent(context)
              .runId(runId)
              .useAsOutput(graph.terminalNodeNames().contains(nodeName))
              .useSubBranch(trigger.useSubBranch())
              .overrideBranch(trigger.branch())
              .build()
              .run(trigger.input())
              .subscribe(
                  childContext -> {
                    completions.add(new Completion(nodeName, childContext));
                    wakeUps.onNext(true);
                  },
                  // A node's exception fails only that node, so this is an Error.
                  wakeUps::onError);
      childRuns.add(run);
      running.put(nodeName, run);
    }
  }

  private void handleCompletion(String nodeName, Context childContext) {
    NodeState progress = nodeStates.get(nodeName);
    NodeExecutionState childState = childContext.requireNodeState();
    ImmutableSet<String> childInterruptIds = childState.interruptIds();
    if (!childInterruptIds.isEmpty()) {
      progress.status = NodeStatus.WAITING;
      progress.interrupts = childInterruptIds.asList();
      interruptIds.addAll(childInterruptIds);
      return;
    }
    if (graph.node(nodeName).waitForOutput()
        && childState.output().isEmpty()
        && childState.selectedRoutes().isEmpty()) {
      progress.status = NodeStatus.WAITING;
      return;
    }
    progress.status = NodeStatus.COMPLETED;
    nodeBranches.put(nodeName, childContext.invocationContext().branch().orElse(null));
    childState.output().ifPresent(output -> nodeOutputs.put(nodeName, output));
    enqueueSuccessorTriggers(nodeName, childContext);
  }

  private void enqueueSuccessorTriggers(String nodeName, Context childContext) {
    NodeExecutionState childState = childContext.requireNodeState();
    ImmutableList<String> successors =
        graph.nodesTriggeredBy(nodeName, childState.selectedRoutes().orElse(ImmutableList.of()));
    // Several successors each run on a sub-branch, while a single one keeps the node's branch.
    boolean useSubBranch = successors.size() > 1;
    String branch = childContext.invocationContext().branch().orElse(null);
    for (String successor : successors) {
      if (graph.node(successor).requiresAllPredecessors()) {
        enqueueBarrierTrigger(successor);
      } else {
        triggerQueue.enqueue(
            successor, new Trigger(childState.output().orElse(null), useSubBranch, branch));
      }
    }
  }

  /**
   * Queues a trigger for {@code target}, a node that waits for all its predecessors, once every
   * predecessor has completed. The trigger hands it their outputs keyed by node name.
   */
  private void enqueueBarrierTrigger(String target) {
    ImmutableSet<String> predecessors = graph.predecessorsOf(target);
    // A predecessor that a loop re-triggered is still marked completed, so check its queue as well.
    ImmutableSet<String> queued = ImmutableSet.copyOf(triggerQueue.queuedNodeNames());
    for (String predecessor : predecessors) {
      NodeState progress = nodeStates.get(predecessor);
      boolean done =
          predecessor.equals(Start.NAME)
              || (progress != null
                  && progress.status == NodeStatus.COMPLETED
                  && !queued.contains(predecessor));
      if (!done) {
        return;
      }
    }
    Map<String, @Nullable Object> outputs = new LinkedHashMap<>();
    List<@Nullable String> branches = new ArrayList<>();
    for (String predecessor : predecessors) {
      outputs.put(predecessor, nodeOutputs.get(predecessor));
      branches.add(nodeBranches.get(predecessor));
    }
    // The node re-merges onto the branch that its predecessors forked from.
    triggerQueue.enqueue(
        target,
        new Trigger(
            Collections.unmodifiableMap(outputs),
            /* useSubBranch= */ false,
            commonBranchPrefix(branches)));
  }

  /**
   * Surfaces the interrupts of the nodes still waiting on the workflow, or, when there are none,
   * records the output of the sole terminal node that produced one as the workflow's output.
   */
  private Completable finalizeRun() {
    // A child run from the workflow's own context records its interrupts on the workflow.
    if (!interruptIds.isEmpty() || !workflowState.interruptIds().isEmpty()) {
      workflowState.addInterruptIds(interruptIds);
      return Completable.complete();
    }
    ImmutableList<String> terminalOutputs =
        graph.terminalNodeNames().stream()
            .filter(nodeOutputs::containsKey)
            .collect(toImmutableList());
    if (terminalOutputs.size() > 1) {
      return Completable.error(
          new WorkflowConfigurationException(
              String.format(
                  "Workflow '%s': multiple terminal nodes produced output (%d). A workflow must"
                      + " have at most one terminal output.",
                  workflow.name(), terminalOutputs.size())));
    }
    if (terminalOutputs.size() == 1) {
      workflowState.produceOutput(nodeOutputs.get(terminalOutputs.get(0)));
      // The terminal node's event already carried the output for the workflow as well.
      workflowState.markOutputEmitted();
    }
    return Completable.complete();
  }

  /** Returns the longest segment prefix that the dot-separated {@code branches} share, if any. */
  private static @Nullable String commonBranchPrefix(List<@Nullable String> branches) {
    List<List<String>> segmented = new ArrayList<>();
    for (String branch : branches) {
      segmented.add(
          isNullOrEmpty(branch) ? ImmutableList.of() : Splitter.on('.').splitToList(branch));
    }
    int shortest = segmented.stream().mapToInt(List::size).min().getAsInt();
    List<String> common = new ArrayList<>();
    for (int i = 0; i < shortest; i++) {
      String segment = segmented.get(0).get(i);
      int index = i;
      if (segmented.stream().anyMatch(segments -> !segments.get(index).equals(segment))) {
        break;
      }
      common.add(segment);
    }
    return common.isEmpty() ? null : String.join(".", common);
  }

  /** A node run that finished, with its context. */
  private record Completion(String nodeName, Context context) {}
}
