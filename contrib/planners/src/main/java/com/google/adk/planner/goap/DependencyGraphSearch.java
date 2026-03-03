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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Performs a topological search on the dependency graph to find the ordered list of agents that
 * must execute to produce a goal output, given a set of initial preconditions (state keys already
 * available).
 *
 * <p>The search works backward from the goal: for each unsatisfied dependency, it finds the agent
 * that produces it and recursively resolves that agent's dependencies. Uses recursive DFS to ensure
 * correct topological ordering.
 */
public final class DependencyGraphSearch {

  private DependencyGraphSearch() {}

  /**
   * Finds the ordered list of agent names that must execute to produce the goal.
   *
   * @param graph the dependency graph built from agent metadata
   * @param preconditions state keys already available (no agent needed to produce them)
   * @param goal the target output key to produce
   * @return ordered list of agent names, from first to execute to last
   * @throws IllegalStateException if a dependency cannot be resolved or a cycle is detected
   */
  public static ImmutableList<String> search(
      GoalOrientedSearchGraph graph, Collection<String> preconditions, String goal) {

    Set<String> satisfied = new HashSet<>(preconditions);
    LinkedHashSet<String> executionOrder = new LinkedHashSet<>();
    Set<String> visiting = new HashSet<>();

    resolve(graph, goal, satisfied, visiting, executionOrder);

    return ImmutableList.copyOf(executionOrder);
  }

  private static void resolve(
      GoalOrientedSearchGraph graph,
      String outputKey,
      Set<String> satisfied,
      Set<String> visiting,
      LinkedHashSet<String> executionOrder) {

    if (satisfied.contains(outputKey)) {
      return;
    }

    if (!graph.contains(outputKey)) {
      throw new IllegalStateException(
          "Cannot resolve dependency '"
              + outputKey
              + "': no agent produces this output key. "
              + "Check that all required AgentMetadata entries are provided.");
    }

    if (!visiting.add(outputKey)) {
      throw new IllegalStateException(
          "Circular dependency detected involving output key: " + outputKey);
    }

    // Recursively resolve all dependencies first
    for (String dep : graph.getDependencies(outputKey)) {
      resolve(graph, dep, satisfied, visiting, executionOrder);
    }

    // All dependencies are now satisfied; add this agent
    String agentName = graph.getProducerAgent(outputKey);
    if (agentName != null) {
      executionOrder.add(agentName);
    }
    satisfied.add(outputKey);
    visiting.remove(outputKey);
  }
}
