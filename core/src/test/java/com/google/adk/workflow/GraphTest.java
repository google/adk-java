/*
 * Copyright 2026 Google LLC
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

package com.google.adk.workflow;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.testing.TestNode;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GraphTest {

  private static final Route YES = new Route.Tag("yes");
  private static final Route NO = new Route.Tag("no");

  @Test
  public void of_listsEachNodeOnceInTheOrderTheEdgesFirstMentionIt() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Node c = TestNode.of("c");
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(Start.INSTANCE, a), new Edge(a, c), new Edge(a, b), new Edge(b, c));

    Graph graph = Graph.of(edges);

    assertThat(graph.nodes()).containsExactly(Start.INSTANCE, a, c, b).inOrder();
    assertThat(graph.edges()).containsExactlyElementsIn(edges).inOrder();
  }

  @Test
  public void terminalNodeNames_areTheNodesWithNoOutgoingEdge() {
    Node a = TestNode.of("a");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, TestNode.of("b")),
                new Edge(a, TestNode.of("c"))));

    assertThat(graph.terminalNodeNames()).containsExactly("b", "c");
  }

  @Test
  public void node_returnsTheNodeWithThatName() {
    Node a = TestNode.of("a");
    Graph graph = Graph.of(ImmutableList.of(new Edge(Start.INSTANCE, a)));

    assertThat(graph.node("a")).isSameInstanceAs(a);
  }

  @Test
  public void node_rejectsAnUnknownName() {
    Graph graph = Graph.of(ImmutableList.of(new Edge(Start.INSTANCE, TestNode.of("a"))));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> graph.node("missing"));

    assertThat(e).hasMessageThat().contains("missing");
  }

  @Test
  public void of_rejectsANodeNameContainingASlash() {
    // '/' separates the segments of a node path.
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(Start.INSTANCE, TestNode.of("a/b")));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("but 'a/b' does");
  }

  @Test
  public void of_rejectsANodeNameContainingAnAtSign() {
    // '@' separates a node's name from its run ID in a node path.
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(Start.INSTANCE, TestNode.of("a@1")));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("but 'a@1' does");
  }

  @Test
  public void of_rejectsANodeNameContainingADot() {
    // '.' separates branch segments; an agent name may contain one, a node name may not.
    Node a = TestNode.of("a");
    ImmutableList<Edge> edges =
        ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, TestNode.of("a.b")));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("but 'a.b' does");
  }

  @Test
  public void of_rejectsAnEmptyNodeName() {
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(Start.INSTANCE, TestNode.of("")));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().isEqualTo("A node name must not be empty.");
  }

  @Test
  public void nodesTriggeredBy_followsOnlyTheEdgeWithTheEmittedRoute() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("yes_node"), YES),
                new Edge(router, TestNode.of("no_node"), NO)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(YES))).containsExactly("yes_node");
  }

  @Test
  public void nodesTriggeredBy_followsTheEdgeOfEachEmittedRoute() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("yes_node"), YES),
                new Edge(router, TestNode.of("no_node"), NO)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO, YES)))
        .containsExactly("yes_node", "no_node")
        .inOrder();
  }

  @Test
  public void nodesTriggeredBy_followsAnEdgeOnAnyOfItsRoutes() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("either"), ImmutableList.of(YES, NO))));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO))).containsExactly("either");
  }

  @Test
  public void nodesTriggeredBy_followsTheDefaultEdgeWhenNoRouteMatches() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("yes_node"), YES),
                new Edge(router, TestNode.of("fallback"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(new Route.Tag("other"))))
        .containsExactly("fallback");
  }

  @Test
  public void nodesTriggeredBy_followsTheDefaultEdgeWhenNoRouteIsEmitted() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("yes_node"), YES),
                new Edge(router, TestNode.of("fallback"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of())).containsExactly("fallback");
  }

  /** Mirrors adk-python's test_get_next_pending_nodes_with_default_route_fan_out. */
  @Test
  public void nodesTriggeredBy_followsEveryDefaultEdgeWhenNoRouteMatches() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("named"), YES),
                new Edge(router, TestNode.of("first"), Route.Default.INSTANCE),
                new Edge(router, TestNode.of("second"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO)))
        .containsExactly("first", "second")
        .inOrder();
  }

  @Test
  public void nodesTriggeredBy_skipsEveryDefaultEdgeWhenARouteMatches() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("named"), YES),
                new Edge(router, TestNode.of("first"), Route.Default.INSTANCE),
                new Edge(router, TestNode.of("second"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(YES))).containsExactly("named");
  }

  @Test
  public void nodesTriggeredBy_followsUnconditionalEdgesAlongsideTheDefaultEdge() {
    Node a = TestNode.of("a");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, TestNode.of("b")),
                new Edge(a, TestNode.of("c"), YES),
                new Edge(a, TestNode.of("d"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("a", ImmutableList.of())).containsExactly("b", "d").inOrder();
  }

  @Test
  public void nodesTriggeredBy_followsUnconditionalEdgesAlongsideAMatchedRoute() {
    Node a = TestNode.of("a");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, TestNode.of("b")),
                new Edge(a, TestNode.of("c"), YES),
                new Edge(a, TestNode.of("d"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("a", ImmutableList.of(YES)))
        .containsExactly("b", "c")
        .inOrder();
  }

  @Test
  public void nodesTriggeredBy_followsARoutedEdgeBackToAnEarlierNode() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Graph graph =
        Graph.of(
            ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(b, a, YES)));

    assertThat(graph.nodesTriggeredBy("b", ImmutableList.of(YES))).containsExactly("a");
  }

  @Test
  public void nodesTriggeredBy_returnsNothingWhenNoRoutedEdgeMatches() {
    Node router = TestNode.of("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router), new Edge(router, TestNode.of("yes_node"), YES)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO))).isEmpty();
  }

  @Test
  public void predecessorsOf_returnsTheSourcesOfTheIncomingEdges() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Node join = TestNode.of("join");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(Start.INSTANCE, b),
                new Edge(a, join),
                new Edge(b, join)));

    assertThat(graph.predecessorsOf("join")).containsExactly("a", "b");
  }
}
