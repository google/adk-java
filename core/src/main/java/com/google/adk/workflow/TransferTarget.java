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

import static com.google.adk.agents.AgentMode.SINGLE_TURN;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where a {@code transfer_to_agent} hands off to.
 *
 * @param agent the agent that runs next as a workflow node
 * @param parent the context of the node it runs under
 */
record TransferTarget(BaseAgent agent, Context parent) {

  /**
   * Follows the handoffs that {@code first}, a run of {@code node}, requests, and returns the
   * context of the run that ends the loop: one that fails, waits on interrupts, or finishes without
   * a handoff. {@code runHop} runs each target and returns the context of its run.
   */
  static Single<Context> followHandoffs(
      Node node, Context first, Function<TransferTarget, Single<Context>> runHop) {
    return Single.defer(() -> {
      Optional<TransferTarget> target = handoffOf(node, first);
      if (target.isEmpty()) {
        return Single.just(first);
      }
      return runHop
          .apply(target.get())
          .flatMap(next -> followHandoffs(target.get().agent(), next, runHop));
    });
  }

  /**
   * Returns whether {@code node} is an agent whose transfers the workflow follows by running each
   * target as a node: one in an agent tree with modes, other than a single-turn agent, which runs
   * its target itself as in Python ADK. Outside such a tree an agent runs its target itself too, so
   * existing apps keep their behavior, unlike Python ADK, which treats such agents as chat agents.
   */
  static boolean handsOff(Node node) {
    return node instanceof BaseAgent agent
        && !runsTargetInline(agent)
        && setsMode(agent.rootAgent());
  }

  /**
   * Returns whether {@code agent} is a single-turn agent, which runs its transfer target itself.
   */
  static boolean runsTargetInline(BaseAgent agent) {
    return agent instanceof LlmAgent llmAgent
        && llmAgent.mode().filter(SINGLE_TURN::equals).isPresent();
  }

  private static boolean setsMode(BaseAgent agent) {
    return (agent instanceof LlmAgent llmAgent && llmAgent.mode().isPresent())
        || agent.subAgents().stream().anyMatch(TransferTarget::setsMode);
  }

  /** Returns where {@code run}, a run of {@code node}, hands off to, or empty if it does not. */
  private static Optional<TransferTarget> handoffOf(Node node, Context run) {
    NodeExecutionState state = run.requireNodeState();
    Optional<String> targetName = run.eventActions().transferToAgent();
    if (targetName.isEmpty()
        || state.failure().isPresent()
        || !state.interruptIds().isEmpty()
        || !handsOff(node)) {
      return Optional.empty();
    }
    return resolve(targetName.get(), (BaseAgent) node, run);
  }

  /**
   * Resolves the sub-agent {@code targetName} that {@code current} hands off to, which runs under
   * {@code currentContext}, the context of {@code current}'s run. Returns empty for any other
   * agent, since {@code current} runs a transfer to its parent or a peer itself.
   *
   * @throws IllegalArgumentException if the sub-agent is a task or single-turn agent, which its
   *     parent calls as a tool instead
   */
  private static Optional<TransferTarget> resolve(
      String targetName, BaseAgent current, Context currentContext) {
    for (BaseAgent subAgent : current.subAgents()) {
      if (subAgent.name().equals(targetName)) {
        checkArgument(
            !LlmAgent.runsAsTool(subAgent),
            "Cannot transfer to '%s': a task or single-turn agent runs as a tool.",
            targetName);
        return Optional.of(new TransferTarget(subAgent, currentContext));
      }
    }
    return Optional.empty();
  }
}
