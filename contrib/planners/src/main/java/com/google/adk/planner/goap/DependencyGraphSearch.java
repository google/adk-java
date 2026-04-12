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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

  /**
   * Groups agents into parallelizable execution levels.
   *
   * <p>Each group contains agents whose dependencies are all satisfied by agents in earlier groups
   * or by initial preconditions. Agents within the same group are independent and can run in
   * parallel.
   *
   * @param graph the dependency graph
   * @param metadata agent metadata used to compute dependency levels
   * @param preconditions state keys already available
   * @param goal the target output key
   * @return ordered list of agent groups; agents within each group can run in parallel
   * @throws IllegalStateException if a dependency cannot be resolved or a cycle is detected
   */
  public static ImmutableList<ImmutableList<String>> searchGrouped(
      GoalOrientedSearchGraph graph,
      List<AgentMetadata> metadata,
      Collection<String> preconditions,
      String goal) {

    ImmutableList<String> flatOrder = search(graph, preconditions, goal);
    return assignParallelLevels(flatOrder, metadata, preconditions, graph);
  }

  /**
   * Assigns agents from a flat execution order into parallelizable groups based on dependency
   * depth.
   *
   * <p>Each agent's level is {@code 1 + max(level of its dependency agents)}. Agents at the same
   * level have no mutual dependencies and can run in parallel.
   *
   * @param flatOrder ordered list of agent names (topological order)
   * @param metadata agent metadata for dependency lookup
   * @param preconditions state keys already available
   * @param graph the dependency graph
   * @return ordered list of agent groups for parallel execution
   */
  static ImmutableList<ImmutableList<String>> assignParallelLevels(
      ImmutableList<String> flatOrder,
      List<AgentMetadata> metadata,
      Collection<String> preconditions,
      GoalOrientedSearchGraph graph) {

    if (flatOrder.isEmpty()) {
      return ImmutableList.of();
    }

    Map<String, AgentMetadata> agentToMeta = new HashMap<>();
    for (AgentMetadata m : metadata) {
      agentToMeta.put(m.agentName(), m);
    }

    // Assign execution levels: level = 1 + max(level of dependency agents).
    // Agents at the same level have no mutual dependencies and can run in parallel.
    Set<String> preconSet = new HashSet<>(preconditions);
    Map<String, Integer> agentLevel = new LinkedHashMap<>();

    for (String agentName : flatOrder) {
      AgentMetadata meta = agentToMeta.get(agentName);
      int maxDepLevel = -1;

      for (String inputKey : meta.inputKeys()) {
        if (preconSet.contains(inputKey)) {
          continue;
        }
        String producerAgent = graph.getProducerAgent(inputKey);
        if (producerAgent != null && agentLevel.containsKey(producerAgent)) {
          maxDepLevel = Math.max(maxDepLevel, agentLevel.get(producerAgent));
        }
      }

      agentLevel.put(agentName, maxDepLevel + 1);
    }

    int maxLevel = agentLevel.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    ImmutableList.Builder<ImmutableList<String>> groups = ImmutableList.builder();
    for (int level = 0; level <= maxLevel; level++) {
      final int l = level;
      ImmutableList<String> group =
          flatOrder.stream()
              .filter(name -> agentLevel.get(name) == l)
              .collect(ImmutableList.toImmutableList());
      if (!group.isEmpty()) {
        groups.add(group);
      }
    }

    return groups.build();
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
