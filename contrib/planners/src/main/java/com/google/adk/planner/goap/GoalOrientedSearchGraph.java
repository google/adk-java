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
import com.google.common.collect.ImmutableMap;
import java.util.List;

/**
 * Transforms {@link AgentMetadata} into a dependency graph where:
 *
 * <ul>
 *   <li>Each output key maps to the agent that produces it
 *   <li>Each output key maps to the input keys (dependencies) required to produce it
 * </ul>
 *
 * <p>Used by {@link DependencyGraphSearch} for backward-chaining dependency resolution.
 */
public final class GoalOrientedSearchGraph {

  private final ImmutableMap<String, String> outputKeyToAgent;
  private final ImmutableMap<String, ImmutableList<String>> outputKeyToDependencies;

  public GoalOrientedSearchGraph(List<AgentMetadata> metadata) {
    ImmutableMap.Builder<String, String> agentMap = ImmutableMap.builder();
    ImmutableMap.Builder<String, ImmutableList<String>> depMap = ImmutableMap.builder();

    for (AgentMetadata m : metadata) {
      agentMap.put(m.outputKey(), m.agentName());
      depMap.put(m.outputKey(), m.inputKeys());
    }

    this.outputKeyToAgent = agentMap.buildOrThrow();
    this.outputKeyToDependencies = depMap.buildOrThrow();
  }

  /** Returns the input keys (dependencies) needed to produce the given output key. */
  public ImmutableList<String> getDependencies(String outputKey) {
    ImmutableList<String> deps = outputKeyToDependencies.get(outputKey);
    if (deps == null) {
      return ImmutableList.of();
    }
    return deps;
  }

  /** Returns the agent name that produces the given output key. */
  public String getProducerAgent(String outputKey) {
    return outputKeyToAgent.get(outputKey);
  }

  /** Returns true if the given output key is known in this graph. */
  public boolean contains(String outputKey) {
    return outputKeyToAgent.containsKey(outputKey);
  }
}
