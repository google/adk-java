/*
 * Copyright 2025 Google LLC
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

package com.google.adk.planner.p2p;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Planner;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.planner.goap.AgentMetadata;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A peer-to-peer planner where agents activate dynamically as their input dependencies become
 * available in session state.
 *
 * <p>Key behaviors:
 *
 * <ul>
 *   <li>Multiple agents can activate in parallel when their inputs are satisfied
 *   <li>When an agent produces output, other agents whose inputs are now satisfied activate
 *   <li>Agents can re-execute when their inputs change (iterative refinement)
 *   <li>Terminates on maxInvocations or a custom exit condition
 * </ul>
 *
 * <p>Example: Research collaboration where a critic's feedback causes hypothesis refinement:
 *
 * <pre>
 *   LiteratureAgent (needs: topic) → researchFindings
 *   HypothesisAgent (needs: topic, researchFindings) → hypothesis
 *   CriticAgent (needs: topic, hypothesis) → critique
 *   ScorerAgent (needs: topic, hypothesis, critique) → score
 *   Exit when: score >= 0.85
 * </pre>
 */
public final class P2PPlanner implements Planner {

  private static final Logger logger = LoggerFactory.getLogger(P2PPlanner.class);

  private final List<AgentMetadata> metadata;
  private final int maxInvocations;
  private final BiPredicate<ConcurrentMap<String, Object>, Integer> exitCondition;
  private Map<String, AgentActivator> activators;
  private final AtomicInteger invocationCount = new AtomicInteger(0);

  /**
   * Creates a P2P planner with a custom exit condition.
   *
   * @param metadata agent input/output declarations
   * @param maxInvocations maximum total agent invocations before termination
   * @param exitCondition predicate tested on (state, invocationCount); returns true to stop
   */
  public P2PPlanner(
      List<AgentMetadata> metadata,
      int maxInvocations,
      BiPredicate<ConcurrentMap<String, Object>, Integer> exitCondition) {
    this.metadata = metadata;
    this.maxInvocations = maxInvocations;
    this.exitCondition = exitCondition;
  }

  /** Creates a P2P planner that exits only on maxInvocations. */
  public P2PPlanner(List<AgentMetadata> metadata, int maxInvocations) {
    this(metadata, maxInvocations, (state, count) -> false);
  }

  @Override
  public void init(PlanningContext context) {
    activators = new LinkedHashMap<>();
    for (AgentMetadata m : metadata) {
      activators.put(m.agentName(), new AgentActivator(m));
    }
    invocationCount.set(0);
  }

  @Override
  public Single<PlannerAction> firstAction(PlanningContext context) {
    return findReadyAgents(context);
  }

  @Override
  public Single<PlannerAction> nextAction(PlanningContext context) {
    int count = invocationCount.get();

    // Check exit condition
    if (exitCondition.test(context.state(), count)) {
      logger.info("P2PPlanner exit condition met at invocation {}", count);
      return Single.just(new PlannerAction.Done());
    }

    // Mark previously executing agents as finished and notify state changes
    for (AgentActivator activator : activators.values()) {
      activator.finishExecution();
    }

    // Notify all activators about state changes from recently produced keys
    for (AgentMetadata m : metadata) {
      if (context.state().containsKey(m.outputKey())) {
        for (AgentActivator activator : activators.values()) {
          activator.onStateChanged(m.outputKey());
        }
      }
    }

    return findReadyAgents(context);
  }

  private Single<PlannerAction> findReadyAgents(PlanningContext context) {
    if (invocationCount.get() >= maxInvocations) {
      logger.info("P2PPlanner reached maxInvocations={}", maxInvocations);
      return Single.just(new PlannerAction.Done());
    }

    ImmutableList.Builder<BaseAgent> readyAgents = ImmutableList.builder();
    for (AgentActivator activator : activators.values()) {
      if (activator.canActivate(context.state())) {
        readyAgents.add(context.findAgent(activator.agentName()));
        activator.startExecution();
        invocationCount.incrementAndGet();
      }
    }

    ImmutableList<BaseAgent> agents = readyAgents.build();
    if (agents.isEmpty()) {
      logger.info("P2PPlanner: no agents can activate, done");
      return Single.just(new PlannerAction.Done());
    }

    logger.info(
        "P2PPlanner activating {} agent(s): {}",
        agents.size(),
        agents.stream().map(BaseAgent::name).toList());
    return Single.just(new PlannerAction.RunAgents(agents));
  }
}
