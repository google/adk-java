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
 * Backward-chaining DFS search strategy with parallel grouping.
 *
 * <p>Delegates to {@link DependencyGraphSearch} for the actual algorithm.
 */
public final class DfsSearchStrategy implements SearchStrategy {

  @Override
  public ImmutableList<ImmutableList<String>> searchGrouped(
      GoalOrientedSearchGraph graph,
      List<AgentMetadata> metadata,
      Collection<String> preconditions,
      String goal) {
    return DependencyGraphSearch.searchGrouped(graph, metadata, preconditions, goal);
  }
}
