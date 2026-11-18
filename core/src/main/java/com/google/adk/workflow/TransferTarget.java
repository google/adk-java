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
import static java.util.stream.Collectors.joining;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Where a {@code transfer_to_agent} hands off to.
 *
 * @param agent the agent that runs next as a workflow node
 * @param parent the context of the node it runs under, or null to run it at the top of the
 *     invocation, beside the root node
 */
record TransferTarget(BaseAgent agent, @Nullable Context parent) {

  /**
   * Follows the handoffs that {@code first}, a run of {@code node} under {@code caller}, requests,
   * and returns the context of the run that ends the loop: one that fails, waits on interrupts, or
   * finishes without a handoff. {@code runHop} runs each target and returns the context of its run.
   */
  static Single<Context> followHandoffs(
      Node node,
      Context first,
      @Nullable Context caller,
      Function<TransferTarget, Single<Context>> runHop) {
    return Single.defer(() -> {
      Optional<TransferTarget> handoff = handoffOf(node, first, caller);
      if (handoff.isEmpty()) {
        return Single.just(first);
      }
      TransferTarget target = handoff.get();
      return runHop
          .apply(target)
          .flatMap(next -> followHandoffs(target.agent(), next, target.parent(), runHop));
    });
  }

  /**
   * Returns whether {@code node} is an agent whose transfers the workflow follows by running each
   * target as a node. Outside an agent tree with modes, an agent runs the agent it transfers to
   * itself.
   */
  static boolean handsOff(Node node) {
    return node instanceof BaseAgent agent && setsMode(agent.rootAgent());
  }

  private static boolean setsMode(BaseAgent agent) {
    return (agent instanceof LlmAgent llmAgent && llmAgent.mode().isPresent())
        || agent.subAgents().stream().anyMatch(TransferTarget::setsMode);
  }

  /**
   * Returns where {@code run}, a run of {@code node} under {@code caller}, hands off to, or empty
   * if it does not.
   */
  private static Optional<TransferTarget> handoffOf(
      Node node, Context run, @Nullable Context caller) {
    NodeExecutionState state = run.requireNodeState();
    Optional<String> targetName = run.eventActions().transferToAgent();
    if (targetName.isEmpty()
        || state.failure().isPresent()
        || !state.interruptIds().isEmpty()
        || !handsOff(node)) {
      return Optional.empty();
    }
    return Optional.of(resolve(targetName.get(), (BaseAgent) node, run, caller));
  }

  /**
   * Resolves the agent {@code targetName} that {@code current} hands off to, where {@code current}
   * ran as {@code currentContext} under {@code caller}. A sub-agent runs under {@code
   * currentContext} and a peer under {@code caller}; as in Python ADK, the parent runs again where
   * its earlier run ran, or else at the top of the invocation.
   *
   * @throws IllegalArgumentException for a transfer to {@code current} itself, to a task or
   *     single-turn agent, to a parent or peer that {@code current}'s flags forbid, or to an agent
   *     that is not {@code current}'s sub-agent, parent or peer
   */
  private static TransferTarget resolve(
      String targetName, BaseAgent current, Context currentContext, @Nullable Context caller) {
    checkArgument(
        !targetName.equals(current.name()), "Agent '%s' cannot transfer to itself.", targetName);
    BaseAgent subAgent = findByName(current.subAgents(), targetName);
    if (subAgent != null) {
      requireTransferTarget(subAgent);
      return new TransferTarget(subAgent, currentContext);
    }
    BaseAgent parent = current.parentAgent();
    // Checked before the peers, so a parent with a sub-agent named after itself still wins.
    if (parent != null && parent.name().equals(targetName)) {
      checkArgument(
          !(current instanceof LlmAgent agent && agent.disallowTransferToParent()),
          "Cannot transfer from '%s' to parent agent '%s': disallowTransferToParent is set.",
          current.name(),
          targetName);
      return new TransferTarget(parent, parentOfEarlierRun(currentContext, targetName));
    }
    BaseAgent peer = parent == null ? null : findByName(parent.subAgents(), targetName);
    if (peer != null) {
      checkArgument(
          !(current instanceof LlmAgent agent && agent.disallowTransferToPeers()),
          "Cannot transfer from '%s' to peer agent '%s': disallowTransferToPeers is set.",
          current.name(),
          targetName);
      requireTransferTarget(peer);
      return new TransferTarget(peer, caller);
    }
    BaseAgent root = current.rootAgent();
    // The name comes from the model, so the message leaves it out.
    checkArgument(
        root.findAgent(targetName).isPresent(),
        "Agent '%s' requested a transfer to an agent that is not in the agent tree.",
        current.name());
    throw new IllegalArgumentException(
        "Cannot transfer from '"
            + current.name()
            + "' to unrelated agent '"
            + targetName
            + "'.\nAvailable agents: "
            + agentNames(root).collect(joining(", ")));
  }

  private static @Nullable BaseAgent findByName(List<? extends BaseAgent> agents, String name) {
    for (BaseAgent agent : agents) {
      if (agent.name().equals(name)) {
        return agent;
      }
    }
    return null;
  }

  /**
   * Throws {@link IllegalArgumentException} if {@code agent} is a task or single-turn agent, which
   * its parent calls as a tool rather than transfers to.
   */
  private static void requireTransferTarget(BaseAgent agent) {
    checkArgument(
        !LlmAgent.runsAsTool(agent),
        "Cannot transfer to '%s': a task or single-turn agent runs as a tool.",
        agent.name());
  }

  /**
   * Returns the context that the latest run named {@code name} at or above {@code context} ran
   * under, or null if there is no such run or it ran at the top of the invocation.
   */
  private static @Nullable Context parentOfEarlierRun(Context context, String name) {
    for (Context run = context; run != null; run = run.requireNodeState().parent().orElse(null)) {
      if (run.requireNodeState().node().name().equals(name)) {
        return run.requireNodeState().parent().orElse(null);
      }
    }
    return null;
  }

  /** Returns the names of {@code agent} and every agent below it, depth first. */
  private static Stream<String> agentNames(BaseAgent agent) {
    return Stream.concat(
        Stream.of(agent.name()), agent.subAgents().stream().flatMap(TransferTarget::agentNames));
  }
}
