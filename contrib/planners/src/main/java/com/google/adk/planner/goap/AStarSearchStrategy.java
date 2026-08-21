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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * A* forward search strategy that explores from preconditions toward the goal, activating agents
 * whose inputs are all satisfied.
 *
 * <p>Uses a priority queue ordered by f-score (g + h) where:
 *
 * <ul>
 *   <li>g = number of agents activated so far (uniform cost)
 *   <li>h = admissible heuristic counting unsatisfied dependencies reachable backward from goal
 * </ul>
 *
 * <p>After finding the goal, reconstructs the agent path and delegates to {@link
 * DependencyGraphSearch#assignParallelLevels} for parallel grouping.
 */
public final class AStarSearchStrategy implements SearchStrategy {

  /** Immutable search state: the set of output keys that have been "activated" (produced). */
  private record SearchState(ImmutableSet<String> activatedKeys) {}

  /** Priority queue entry tracking cost, heuristic, and parent chain for path reconstruction. */
  private record StateScore(
      SearchState state, double gScore, double fScore, String lastActivatedAgent, StateScore parent)
      implements Comparable<StateScore> {

    @Override
    public int compareTo(StateScore other) {
      return Double.compare(this.fScore, other.fScore);
    }
  }

  @Override
  public ImmutableList<ImmutableList<String>> searchGrouped(
      GoalOrientedSearchGraph graph,
      List<AgentMetadata> metadata,
      Collection<String> preconditions,
      String goal) {

    ImmutableSet<String> initialActivated = ImmutableSet.copyOf(preconditions);

    // Goal already satisfied
    if (initialActivated.contains(goal)) {
      return ImmutableList.of();
    }

    PriorityQueue<StateScore> openSet = new PriorityQueue<>();
    Set<ImmutableSet<String>> visited = new HashSet<>();

    SearchState startState = new SearchState(initialActivated);
    double h0 = heuristic(graph, startState, goal);
    openSet.add(new StateScore(startState, 0.0, h0, null, null));

    while (!openSet.isEmpty()) {
      StateScore current = openSet.poll();

      if (current.state.activatedKeys.contains(goal)) {
        ImmutableList<String> agentPath = reconstructPath(current);
        return DependencyGraphSearch.assignParallelLevels(
            agentPath, metadata, preconditions, graph);
      }

      if (!visited.add(current.state.activatedKeys)) {
        continue;
      }

      // Find activatable agents: those whose ALL inputKeys are in activatedKeys
      for (AgentMetadata agent : metadata) {
        if (current.state.activatedKeys.contains(agent.outputKey())) {
          continue; // already activated
        }
        if (!current.state.activatedKeys.containsAll(agent.inputKeys())) {
          continue; // not all inputs satisfied
        }

        ImmutableSet<String> newActivated =
            ImmutableSet.<String>builder()
                .addAll(current.state.activatedKeys)
                .add(agent.outputKey())
                .build();

        if (visited.contains(newActivated)) {
          continue;
        }

        SearchState newState = new SearchState(newActivated);
        double newG = current.gScore + 1.0;
        double newH = heuristic(graph, newState, goal);
        double newF = newG + newH;

        openSet.add(new StateScore(newState, newG, newF, agent.agentName(), current));
      }
    }

    throw new IllegalStateException(
        "Cannot reach goal '"
            + goal
            + "': no sequence of agents can produce it from the given preconditions.");
  }

  /**
   * Admissible heuristic: counts unsatisfied output keys reachable backward from the goal.
   *
   * <p>Each unsatisfied key requires at least one agent to produce it, so this never overestimates.
   */
  private static double heuristic(GoalOrientedSearchGraph graph, SearchState state, String goal) {
    Queue<String> queue = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    int unsatisfied = 0;

    queue.add(goal);
    while (!queue.isEmpty()) {
      String key = queue.poll();
      if (!seen.add(key)) {
        continue;
      }
      if (!state.activatedKeys.contains(key)) {
        unsatisfied++;
        if (graph.contains(key)) {
          for (String dep : graph.getDependencies(key)) {
            queue.add(dep);
          }
        }
      }
    }
    return unsatisfied;
  }

  /** Reconstructs the ordered agent path by following the parent chain. */
  private static ImmutableList<String> reconstructPath(StateScore goalState) {
    List<String> path = new ArrayList<>();
    StateScore current = goalState;
    while (current != null && current.lastActivatedAgent != null) {
      path.add(current.lastActivatedAgent);
      current = current.parent;
    }
    Collections.reverse(path);
    return ImmutableList.copyOf(path);
  }
}
