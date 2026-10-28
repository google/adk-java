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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.testing.TestNode;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Graph validation, rule by rule, matching ADK Python's {@code utils/_graph_validation.py}. */
@RunWith(JUnit4.class)
public final class GraphValidationTest {

  private static final Route YES = new Route.Tag("yes");

  @Test
  public void of_rejectsAGraphWithoutStart() {
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(TestNode.of("a"), TestNode.of("b")));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Graph validation failed. START node (name: '__START__') not found in graph nodes.");
  }

  @Test
  public void of_rejectsARouteOnAStartEdge() {
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(Start.INSTANCE, TestNode.of("a"), YES));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    // The route prints as its value, as ADK Python prints it.
    assertThat(e)
        .hasMessageThat()
        .contains("Edges from START must not have routes (edge to a has route yes).");
  }

  @Test
  public void of_namesADefaultRouteOnAStartEdgeByItsSentinel() {
    ImmutableList<Edge> edges =
        ImmutableList.of(new Edge(Start.INSTANCE, TestNode.of("a"), Route.Default.INSTANCE));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("(edge to a has route __DEFAULT__).");
  }

  @Test
  public void of_namesEachRouteOnAStartEdgeByItsValue() {
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(
                Start.INSTANCE,
                TestNode.of("a"),
                ImmutableList.of(new Route.Num(1), new Route.Flag(true))));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("(edge to a has route 1, true).");
  }

  @Test
  public void of_keepsItsOwnCopyOfTheEdges() {
    Node a = TestNode.of("a");
    List<Edge> edges = new ArrayList<>();
    edges.add(new Edge(Start.INSTANCE, a));
    Graph graph = Graph.of(edges);

    // An edge into START added afterwards would have failed validation.
    edges.add(new Edge(a, Start.INSTANCE));

    assertThat(graph.edges()).containsExactly(new Edge(Start.INSTANCE, a));
  }

  @Test
  public void of_rejectsUnreachableNodesAndListsThemInOrder() {
    Node a = TestNode.of("a");
    Node orphan = TestNode.of("orphan");
    Node sink = TestNode.of("sink");
    ImmutableList<Edge> edges =
        ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(sink, a), new Edge(orphan, sink));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e)
        .hasMessageThat()
        .contains("The following nodes are unreachable from START: [orphan, sink]");
  }

  @Test
  public void of_treatsADisconnectedRoutedSubgraphAsUnreachable() {
    // b and c only route to each other, so START reaches neither.
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Node c = TestNode.of("c");
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(Start.INSTANCE, a),
            new Edge(b, c, new Route.Tag("x")),
            new Edge(c, b, new Route.Tag("y")));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("unreachable from START: [b, c]");
  }

  @Test
  public void of_rejectsAnEdgeIntoStart() {
    Node a = TestNode.of("a");
    ImmutableList<Edge> edges =
        ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, Start.INSTANCE));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Graph validation failed. START node must not have incoming edges.");
  }

  @Test
  public void of_rejectsOverlappingEdgesBetweenTheSameTwoNodes() {
    // An empty route list is unconditional, which overlaps every other edge.
    ImmutableList<Map.Entry<ImmutableList<Route>, ImmutableList<Route>>> overlapping =
        ImmutableList.of(
            Map.entry(routes(), routes()),
            Map.entry(routes("x"), routes("x")),
            Map.entry(routes("x"), routes()),
            Map.entry(routes(), routes("x")),
            Map.entry(routes("x", "y"), routes("y")),
            Map.entry(routes("x", "y"), routes("y", "z")));

    for (Map.Entry<ImmutableList<Route>, ImmutableList<Route>> pair : overlapping) {
      Node a = TestNode.of("a");
      Node b = TestNode.of("b");
      ImmutableList<Edge> edges =
          ImmutableList.of(
              new Edge(Start.INSTANCE, a),
              new Edge(a, b, pair.getKey()),
              new Edge(a, b, pair.getValue()));

      GraphValidationException e =
          assertThrows(pair.toString(), GraphValidationException.class, () -> Graph.of(edges));

      assertThat(e)
          .hasMessageThat()
          .isEqualTo("Graph validation failed. Duplicate edge found: from=a, to=b");
    }
  }

  @Test
  public void of_acceptsRoutedEdgesBetweenTheSameTwoNodesWithDisjointRoutes() {
    ImmutableList<Map.Entry<ImmutableList<Route>, ImmutableList<Route>>> disjoint =
        ImmutableList.of(
            Map.entry(routes("w"), routes("x")),
            Map.entry(routes("w", "x"), routes("y")),
            Map.entry(routes("w", "x"), routes("y", "z")));

    for (Map.Entry<ImmutableList<Route>, ImmutableList<Route>> pair : disjoint) {
      Node a = TestNode.of("a");
      Node b = TestNode.of("b");

      Graph graph =
          Graph.of(
              ImmutableList.of(
                  new Edge(Start.INSTANCE, a),
                  new Edge(a, b, pair.getKey()),
                  new Edge(a, b, pair.getValue())));

      assertThat(graph.nodes()).containsExactly(Start.INSTANCE, a, b).inOrder();
    }
  }

  @Test
  public void nodesTriggeredBy_triggersATargetOnceForEachMatchedDisjointEdge() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, b, new Route.Tag("x")),
                new Edge(a, b, new Route.Tag("y"))));

    // One trigger per matched edge, as ADK Python's get_next_pending_nodes appends.
    assertThat(graph.nodesTriggeredBy("a", routes("x", "y"))).containsExactly("b", "b");
  }

  @Test
  public void of_acceptsANamedRouteAndTheDefaultRouteToOneTarget() {
    // The routing-map shape {x: target, DEFAULT: target}.
    Node router = TestNode.of("router");
    Node target = TestNode.of("target");
    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, target, new Route.Tag("x")),
                new Edge(router, target, Route.Default.INSTANCE)));

    assertThat(graph.nodesTriggeredBy("router", routes("x"))).containsExactly("target");
    assertThat(graph.nodesTriggeredBy("router", routes("other"))).containsExactly("target");
  }

  @Test
  public void of_acceptsAnEdgeListingTheSameRouteTwice() {
    // Routes on one edge form a set, so a repeat is redundant rather than a conflict.
    Node router = TestNode.of("router");
    Node target = TestNode.of("target");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, target, ImmutableList.of(YES, YES))));

    assertThat(graph.nodesTriggeredBy("router", ImmutableList.of(YES))).containsExactly("target");
  }

  @Test
  public void of_acceptsSeveralDefaultRouteEdgesFromOneNode() {
    Node router = TestNode.of("router");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("first"), Route.Default.INSTANCE),
                new Edge(router, TestNode.of("second"), Route.Default.INSTANCE)));

    assertThat(graph.terminalNodeNames()).containsExactly("first", "second");
  }

  @Test
  public void of_acceptsADefaultRouteBesideANamedRoute() {
    Node router = TestNode.of("router");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, router),
                new Edge(router, TestNode.of("fallback"), Route.Default.INSTANCE),
                new Edge(router, TestNode.of("named"), new Route.Tag("another_route"))));

    assertThat(graph.terminalNodeNames()).containsExactly("fallback", "named");
  }

  @Test
  public void of_rejectsADefaultRouteSharingAnEdgeWithAnotherRoute() {
    Node router = TestNode.of("router");
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(Start.INSTANCE, router),
            new Edge(
                router,
                TestNode.of("target"),
                ImmutableList.of(new Route.Tag("x"), Route.Default.INSTANCE)));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e)
        .hasMessageThat()
        .contains(
            "DEFAULT_ROUTE cannot be combined with other routes in a list (edge from=router,"
                + " to=target)");
  }

  @Test
  public void of_rejectsTwoDistinctNodesSharingAName() {
    Node a = TestNode.of("a");
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(Start.INSTANCE, a),
            new Edge(a, TestNode.of("dup")),
            new Edge(a, TestNode.of("dup")));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("Duplicate node names found: [dup].");
  }

  @Test
  public void of_acceptsOneNodeInstanceReusedAcrossBranches() {
    // A diamond, where both branches name the same join instance.
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

    assertThat(graph.terminalNodeNames()).containsExactly("join");
  }

  @Test
  public void of_rejectsAnUnconditionalCycle() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    ImmutableList<Edge> edges =
        ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(b, a));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("Unconditional cycle detected: a -> b -> a.");
  }

  @Test
  public void of_rejectsALongerUnconditionalCycle() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Node c = TestNode.of("c");
    ImmutableList<Edge> edges =
        ImmutableList.of(
            new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(b, c), new Edge(c, a));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("Unconditional cycle detected: a -> b -> c -> a.");
  }

  @Test
  public void of_rejectsAnUnconditionalSelfLoop() {
    Node a = TestNode.of("a");
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, a));

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> Graph.of(edges));

    assertThat(e).hasMessageThat().contains("Unconditional cycle detected: a -> a.");
  }

  @Test
  public void of_acceptsACycleThroughARoutedBackEdge() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, b),
                new Edge(b, a, new Route.Tag("retry"))));

    assertThat(graph.nodes()).containsExactly(Start.INSTANCE, a, b).inOrder();
  }

  @Test
  public void of_acceptsARoutedSelfLoop() {
    Node a = TestNode.of("a");

    Graph graph =
        Graph.of(
            ImmutableList.of(
                new Edge(Start.INSTANCE, a),
                new Edge(a, a, new Route.Tag("continue")),
                new Edge(a, TestNode.of("b"), new Route.Tag("done"))));

    assertThat(graph.terminalNodeNames()).containsExactly("b");
  }

  @Test
  public void of_throwsAnExceptionThatCallersCanCatchAsIllegalArgumentException() {
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(TestNode.of("a"), TestNode.of("b")));

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Graph.of(edges));

    assertThat(e).isInstanceOf(GraphValidationException.class);
  }

  private static ImmutableList<Route> routes(String... tags) {
    return Arrays.stream(tags).<Route>map(Route.Tag::new).collect(toImmutableList());
  }
}
