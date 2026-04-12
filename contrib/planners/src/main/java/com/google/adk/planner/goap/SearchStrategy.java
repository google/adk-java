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
import java.util.List;

/**
 * Strategy for searching a dependency graph to find ordered agent execution groups.
 *
 * <p>Given a graph, agent metadata, available preconditions, and a goal output key, produces an
 * ordered list of agent groups where agents within each group are independent and can run in
 * parallel.
 */
public interface SearchStrategy {

  /**
   * Searches for agent execution groups that produce the goal.
   *
   * @param graph the dependency graph
   * @param metadata agent metadata
   * @param preconditions state keys already available
   * @param goal the target output key
   * @return ordered list of agent groups for parallel execution
   * @throws IllegalStateException if the goal cannot be reached
   */
  ImmutableList<ImmutableList<String>> searchGrouped(
      GoalOrientedSearchGraph graph,
      List<AgentMetadata> metadata,
      Collection<String> preconditions,
      String goal);
}
