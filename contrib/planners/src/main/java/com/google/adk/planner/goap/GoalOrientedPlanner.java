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

package com.google.adk.planner.goap;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Planner;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A planner that resolves agent execution order based on input/output dependencies and a target
 * goal (output key).
 *
 * <p>Given agent metadata declaring what each agent reads (inputKeys) and writes (outputKey), this
 * planner uses backward-chaining dependency resolution to compute the execution path from initial
 * preconditions to the goal.
 *
 * <p>Example:
 *
 * <pre>
 *   Agent A: inputs=[], output="person"
 *   Agent B: inputs=[], output="sign"
 *   Agent C: inputs=["person", "sign"], output="horoscope"
 *   Agent D: inputs=["person", "horoscope"], output="writeup"
 *   Goal: "writeup"
 *
 *   Resolved path: A → B → C → D
 * </pre>
 */
public final class GoalOrientedPlanner implements Planner {

  private static final Logger logger = LoggerFactory.getLogger(GoalOrientedPlanner.class);

  private final String goal;
  private final List<AgentMetadata> metadata;
  private ImmutableList<BaseAgent> executionPath;
  private final AtomicInteger cursor = new AtomicInteger(0);

  public GoalOrientedPlanner(String goal, List<AgentMetadata> metadata) {
    this.goal = goal;
    this.metadata = metadata;
  }

  @Override
  public void init(PlanningContext context) {
    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<String> agentOrder =
        DependencyGraphSearch.search(graph, context.state().keySet(), goal);

    logger.info("GoalOrientedPlanner resolved execution order: {}", agentOrder);

    executionPath =
        agentOrder.stream().map(context::findAgent).collect(ImmutableList.toImmutableList());
    cursor.set(0);
  }

  @Override
  public Single<PlannerAction> firstAction(PlanningContext context) {
    cursor.set(0);
    return selectNext();
  }

  @Override
  public Single<PlannerAction> nextAction(PlanningContext context) {
    return selectNext();
  }

  private Single<PlannerAction> selectNext() {
    int idx = cursor.getAndIncrement();
    if (executionPath == null || idx >= executionPath.size()) {
      return Single.just(new PlannerAction.Done());
    }
    return Single.just(new PlannerAction.RunAgents(executionPath.get(idx)));
  }
}
