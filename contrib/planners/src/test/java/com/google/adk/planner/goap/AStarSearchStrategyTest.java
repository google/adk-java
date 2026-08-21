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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AStarSearchStrategy}. */
class AStarSearchStrategyTest {

  private final AStarSearchStrategy astar = new AStarSearchStrategy();

  // ── A. Graph topology tests (mirror DFS tests) ──────────────────────────

  @Test
  void linearChain_producesCorrectGroups() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputB"), "outputC"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "outputC");

    assertThat(groups).hasSize(3);
    assertThat(groups.get(0)).containsExactly("agentA");
    assertThat(groups.get(1)).containsExactly("agentB");
    assertThat(groups.get(2)).containsExactly("agentC");
  }

  @Test
  void multipleInputs_groupsIndependentAgents() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of(), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA", "outputB"), "outputC"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "outputC");

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0)).containsExactly("agentA", "agentB");
    assertThat(groups.get(1)).containsExactly("agentC");
  }

  @Test
  void diamondDependency_correctGrouping() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA"), "outputC"),
            new AgentMetadata("agentD", ImmutableList.of("outputB", "outputC"), "outputD"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "outputD");

    assertThat(groups).hasSize(3);
    assertThat(groups.get(0)).containsExactly("agentA");
    assertThat(groups.get(1)).containsExactly("agentB", "agentC");
    assertThat(groups.get(2)).containsExactly("agentD");
  }

  @Test
  void skipsSatisfiedPreconditions() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of(), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA", "outputB"), "outputC"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of("outputA"), "outputC");

    // agentA skipped; only agentB and agentC needed
    assertThat(groups).hasSize(2);
    assertThat(groups.get(0)).containsExactly("agentB");
    assertThat(groups.get(1)).containsExactly("agentC");
  }

  @Test
  void goalAlreadyInPreconditions_returnsEmpty() {
    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of(), "outputA"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of("outputA"), "outputA");

    assertThat(groups).isEmpty();
  }

  @Test
  void throwsOnUnresolvableDependency() {
    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentB", ImmutableList.of("missing"), "outputB"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> astar.searchGrouped(graph, metadata, Set.of(), "outputB"));
    assertThat(ex.getMessage()).contains("outputB");
  }

  @Test
  void detectsUnreachableGoal_cycle() {
    // A needs B's output, B needs A's output — neither can activate
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of("outputB"), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);

    assertThrows(
        IllegalStateException.class,
        () -> astar.searchGrouped(graph, metadata, Set.of(), "outputA"));
  }

  // ── B. A*-specific topology tests ───────────────────────────────────────

  @Test
  void singleAgentNoInputs() {
    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of(), "outputA"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "outputA");

    assertThat(groups).hasSize(1);
    assertThat(groups.get(0)).containsExactly("agentA");
  }

  @Test
  void wideGraph_allIndependent() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "a"),
            new AgentMetadata("agentB", ImmutableList.of(), "b"),
            new AgentMetadata("agentC", ImmutableList.of(), "c"),
            new AgentMetadata("agentD", ImmutableList.of(), "d"),
            new AgentMetadata("agentE", ImmutableList.of("a", "b", "c", "d"), "goal"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "goal");

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0)).containsExactly("agentA", "agentB", "agentC", "agentD");
    assertThat(groups.get(1)).containsExactly("agentE");
  }

  @Test
  void deepChain_fiveLinks() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("a1", ImmutableList.of(), "o1"),
            new AgentMetadata("a2", ImmutableList.of("o1"), "o2"),
            new AgentMetadata("a3", ImmutableList.of("o2"), "o3"),
            new AgentMetadata("a4", ImmutableList.of("o3"), "o4"),
            new AgentMetadata("a5", ImmutableList.of("o4"), "o5"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "o5");

    assertThat(groups).hasSize(5);
    assertThat(groups.get(0)).containsExactly("a1");
    assertThat(groups.get(1)).containsExactly("a2");
    assertThat(groups.get(2)).containsExactly("a3");
    assertThat(groups.get(3)).containsExactly("a4");
    assertThat(groups.get(4)).containsExactly("a5");
  }

  @Test
  void complexSixAgentGraph() {
    // A:[]→a, B:[]→b, C:[a]→c, D:[b]→d, E:[c,d]→e, F:[a,e]→goal
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of("a"), "c"),
            new AgentMetadata("D", ImmutableList.of("b"), "d"),
            new AgentMetadata("E", ImmutableList.of("c", "d"), "e"),
            new AgentMetadata("F", ImmutableList.of("a", "e"), "goal"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<ImmutableList<String>> groups =
        astar.searchGrouped(graph, metadata, Set.of(), "goal");

    assertThat(groups).hasSize(4);
    assertThat(groups.get(0)).containsExactly("A", "B");
    assertThat(groups.get(1)).containsExactly("C", "D");
    assertThat(groups.get(2)).containsExactly("E");
    assertThat(groups.get(3)).containsExactly("F");
  }

  // ── C. Cross-strategy equivalence tests ─────────────────────────────────

  @Test
  void equivalentToDfs_linearChain() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputB"), "outputC"));

    assertStrategiesEquivalent(metadata, Set.of(), "outputC");
  }

  @Test
  void equivalentToDfs_diamond() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA"), "outputC"),
            new AgentMetadata("agentD", ImmutableList.of("outputB", "outputC"), "outputD"));

    assertStrategiesEquivalent(metadata, Set.of(), "outputD");
  }

  @Test
  void equivalentToDfs_horoscope() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("personExtractor", ImmutableList.of("prompt"), "person"),
            new AgentMetadata("signExtractor", ImmutableList.of("prompt"), "sign"),
            new AgentMetadata(
                "horoscopeGenerator", ImmutableList.of("person", "sign"), "horoscope"),
            new AgentMetadata("writer", ImmutableList.of("person", "horoscope"), "writeup"));

    assertStrategiesEquivalent(metadata, Set.of("prompt"), "writeup");
  }

  @Test
  void equivalentToDfs_complexSixAgent() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of("a"), "c"),
            new AgentMetadata("D", ImmutableList.of("b"), "d"),
            new AgentMetadata("E", ImmutableList.of("c", "d"), "e"),
            new AgentMetadata("F", ImmutableList.of("a", "e"), "goal"));

    assertStrategiesEquivalent(metadata, Set.of(), "goal");
  }

  // ── Helper ──────────────────────────────────────────────────────────────

  private void assertStrategiesEquivalent(
      List<AgentMetadata> metadata, Set<String> preconditions, String goal) {
    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);

    DfsSearchStrategy dfs = new DfsSearchStrategy();
    ImmutableList<ImmutableList<String>> dfsGroups =
        dfs.searchGrouped(graph, metadata, preconditions, goal);
    ImmutableList<ImmutableList<String>> astarGroups =
        astar.searchGrouped(graph, metadata, preconditions, goal);

    assertThat(astarGroups).hasSize(dfsGroups.size());
    for (int i = 0; i < dfsGroups.size(); i++) {
      assertThat(astarGroups.get(i)).containsExactlyElementsIn(dfsGroups.get(i));
    }
  }
}
