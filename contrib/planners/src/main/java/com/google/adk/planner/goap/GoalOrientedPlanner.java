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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   Resolved groups: [A, B] → [C] → [D]
 *   (A and B are independent and run in parallel)
 * </pre>
 *
 * <p>Supports configurable failure handling via {@link ReplanPolicy}:
 *
 * <ul>
 *   <li>{@link ReplanPolicy.Ignore} — proceed regardless of missing outputs (default)
 *   <li>{@link ReplanPolicy.FailStop} — halt on first missing output
 *   <li>{@link ReplanPolicy.Replan} — recompute the remaining plan from current world state
 * </ul>
 *
 * <p>Supports pluggable search strategies via {@link SearchStrategy}: backward-chaining DFS ({@link
 * DfsSearchStrategy}) or forward A* ({@link AStarSearchStrategy}).
 */
public final class GoalOrientedPlanner implements Planner {

  private static final Logger logger = LoggerFactory.getLogger(GoalOrientedPlanner.class);

  private final String goal;
  private final List<AgentMetadata> metadata;
  private final SearchStrategy searchStrategy;
  private final ReplanPolicy replanPolicy;
  // Mutable state — planners are used within a single reactive pipeline and are not thread-safe.
  private ImmutableList<ImmutableList<BaseAgent>> executionGroups;
  private Map<String, String> agentNameToOutputKey;
  private int cursor;
  private int replanCount;

  public GoalOrientedPlanner(String goal, List<AgentMetadata> metadata) {
    this(goal, metadata, new DfsSearchStrategy(), new ReplanPolicy.Ignore());
  }

  public GoalOrientedPlanner(String goal, List<AgentMetadata> metadata, boolean validateOutputs) {
    this(
        goal,
        metadata,
        new DfsSearchStrategy(),
        validateOutputs ? new ReplanPolicy.FailStop() : new ReplanPolicy.Ignore());
  }

  public GoalOrientedPlanner(
      String goal,
      List<AgentMetadata> metadata,
      SearchStrategy searchStrategy,
      ReplanPolicy replanPolicy) {
    this.goal = goal;
    this.metadata = metadata;
    this.searchStrategy = searchStrategy;
    this.replanPolicy = replanPolicy;
  }

  @Override
  public void init(PlanningContext context) {
    buildPlan(context);
    replanCount = 0;
  }

  @Override
  public Single<PlannerAction> firstAction(PlanningContext context) {
    cursor = 0;
    return selectNext();
  }

  @Override
  public Single<PlannerAction> nextAction(PlanningContext context) {
    if (cursor > 0 && executionGroups != null) {
      List<String> missingOutputs = findMissingOutputs(executionGroups.get(cursor - 1), context);

      if (!missingOutputs.isEmpty()) {
        if (replanPolicy instanceof ReplanPolicy.FailStop) {
          String message =
              "Execution stopped: missing expected outputs from previous group: "
                  + String.join(", ", missingOutputs);
          logger.warn(message);
          return Single.just(new PlannerAction.DoneWithResult(message));
        } else if (replanPolicy instanceof ReplanPolicy.Replan replan) {
          if (replanCount >= replan.maxAttempts()) {
            String message =
                "Execution stopped: max replan attempts ("
                    + replan.maxAttempts()
                    + ") exhausted. Still missing: "
                    + String.join(", ", missingOutputs);
            logger.warn(message);
            return Single.just(new PlannerAction.DoneWithResult(message));
          }

          replanCount++;
          logger.info(
              "Replanning (attempt {}/{}). Current state keys: {}. Missing outputs: {}",
              replanCount,
              replan.maxAttempts(),
              context.state().keySet(),
              missingOutputs);

          try {
            buildPlan(context);
          } catch (IllegalStateException e) {
            String message = "Replanning failed: " + e.getMessage();
            logger.warn(message);
            return Single.just(new PlannerAction.DoneWithResult(message));
          }

          if (executionGroups.isEmpty()) {
            return Single.just(new PlannerAction.Done());
          }

          logger.info("Replanned execution groups: {}", executionGroupNames());
        }
        // ReplanPolicy.Ignore: proceed with current plan
      } else {
        // Previous group succeeded — reset consecutive replan counter
        replanCount = 0;
      }
    }
    return selectNext();
  }

  private void buildPlan(PlanningContext context) {
    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> agentGroups =
        searchStrategy.searchGrouped(graph, metadata, context.state().keySet(), goal);

    logger.info("GoalOrientedPlanner resolved execution groups: {}", agentGroups);

    executionGroups =
        agentGroups.stream()
            .map(
                group ->
                    group.stream().map(context::findAgent).collect(ImmutableList.toImmutableList()))
            .collect(ImmutableList.toImmutableList());
    cursor = 0;

    agentNameToOutputKey = new HashMap<>();
    for (AgentMetadata m : metadata) {
      agentNameToOutputKey.put(m.agentName(), m.outputKey());
    }
  }

  private List<String> findMissingOutputs(ImmutableList<BaseAgent> group, PlanningContext context) {
    List<String> missing = new ArrayList<>();
    for (BaseAgent agent : group) {
      String expectedOutput = agentNameToOutputKey.get(agent.name());
      if (expectedOutput != null && !context.state().containsKey(expectedOutput)) {
        missing.add(agent.name() + " -> " + expectedOutput);
        logger.warn(
            "GoalOrientedPlanner: agent '{}' did not produce expected output key '{}'",
            agent.name(),
            expectedOutput);
      }
    }
    return missing;
  }

  private List<List<String>> executionGroupNames() {
    return executionGroups.stream()
        .map(group -> group.stream().map(BaseAgent::name).toList())
        .toList();
  }

  private Single<PlannerAction> selectNext() {
    if (executionGroups == null || cursor >= executionGroups.size()) {
      return Single.just(new PlannerAction.Done());
    }
    ImmutableList<BaseAgent> group = executionGroups.get(cursor++);
    return Single.just(new PlannerAction.RunAgents(group));
  }
}
