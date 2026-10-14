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
    Node a = new StubNode("a");
    Node b = new StubNode("b");
    Node c = new StubNode("c");
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(Start.INSTANCE, a), new Edge(a, c), new Edge(a, b), new Edge(b, c));

    Graph graph = Graph.of(edges);

    assertThat(graph.nodes()).containsExactly(Start.INSTANCE, a, c, b).inOrder();
    assertThat(graph.edges()).containsExactlyElementsIn(edges).inOrder();
  }

  @Test
  public void terminalNodeNames_areTheNodesWithNoOutgoingEdge() {
    Node a = new StubNode("a");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, new StubNode("b")),
                new Edge(a, new StubNode("c"))));

    assertThat(graph.terminalNodeNames()).containsExactly("b", "c");
  }

  @Test
  public void node_returnsTheNodeWithThatName() {
    Node a = new StubNode("a");
    Graph graph = Graph.of(ImmutableList.of(new Edge(Start.INSTANCE, a)));

    assertThat(graph.node("a")).isSameInstanceAs(a);
  }

  @Test
  public void node_rejectsAnUnknownName() {
    Graph graph = Graph.of(ImmutableList.of(new Edge(Start.INSTANCE, new StubNode("a"))));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> graph.node("missing"));

    assertThat(e).hasMessageThat().contains("missing");
  }

  @Test
  public void nodesTriggeredBy_followsOnlyTheEdgeWithTheEmittedRoute() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("yes_node"), YES),
                new Edge(router, new StubNode("no_node"), NO)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(YES))).containsExactly("yes_node");
  }

  @Test
  public void nodesTriggeredBy_followsTheEdgeOfEachEmittedRoute() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("yes_node"), YES),
                new Edge(router, new StubNode("no_node"), NO)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO, YES)))
        .containsExactly("yes_node", "no_node")
        .inOrder();
  }

  @Test
  public void nodesTriggeredBy_followsAnEdgeOnAnyOfItsRoutes() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("either"), ImmutableList.of(YES, NO))));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO))).containsExactly("either");
  }

  @Test
  public void nodesTriggeredBy_followsTheDefaultEdgeWhenNoRouteMatches() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("yes_node"), YES),
                new Edge(router, new StubNode("fallback"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(new Route.Tag("other"))))
        .containsExactly("fallback");
  }

  @Test
  public void nodesTriggeredBy_followsTheDefaultEdgeWhenNoRouteIsEmitted() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("yes_node"), YES),
                new Edge(router, new StubNode("fallback"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of())).containsExactly("fallback");
  }

  /** Mirrors adk-python's test_get_next_pending_nodes_with_default_route_fan_out. */
  @Test
  public void nodesTriggeredBy_followsEveryDefaultEdgeWhenNoRouteMatches() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("named"), YES),
                new Edge(router, new StubNode("first"), Route.Default.INSTANCE),
                new Edge(router, new StubNode("second"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO)))
        .containsExactly("first", "second")
        .inOrder();
  }

  @Test
  public void nodesTriggeredBy_skipsEveryDefaultEdgeWhenARouteMatches() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, new StubNode("named"), YES),
                new Edge(router, new StubNode("first"), Route.Default.INSTANCE),
                new Edge(router, new StubNode("second"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(YES))).containsExactly("named");
  }

  @Test
  public void nodesTriggeredBy_followsUnconditionalEdgesAlongsideTheDefaultEdge() {
    Node a = new StubNode("a");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, new StubNode("b")),
                new Edge(a, new StubNode("c"), YES),
                new Edge(a, new StubNode("d"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("a", ImmutableList.of())).containsExactly("b", "d").inOrder();
  }

  @Test
  public void nodesTriggeredBy_followsUnconditionalEdgesAlongsideAMatchedRoute() {
    Node a = new StubNode("a");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, new StubNode("b")),
                new Edge(a, new StubNode("c"), YES),
                new Edge(a, new StubNode("d"), Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("a", ImmutableList.of(YES)))
        .containsExactly("b", "c")
        .inOrder();
  }

  @Test
  public void nodesTriggeredBy_followsARoutedEdgeBackToAnEarlierNode() {
    Node a = new StubNode("a");
    Node b = new StubNode("b");
    Graph graph =
        Graph.of(
            ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(b, a, YES)));

    assertThat(graph.nodesTriggeredBy("b", ImmutableList.of(YES))).containsExactly("a");
  }

  @Test
  public void nodesTriggeredBy_returnsNothingWhenNoRoutedEdgeMatches() {
    Node router = new StubNode("router");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router), new Edge(router, new StubNode("yes_node"), YES)));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(NO))).isEmpty();
  }

  @Test
  public void predecessorsOf_returnsTheSourcesOfTheIncomingEdges() {
    Node a = new StubNode("a");
    Node b = new StubNode("b");
    Node join = new StubNode("join");
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
