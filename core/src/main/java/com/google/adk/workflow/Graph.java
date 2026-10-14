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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A workflow graph. Its nodes are the ones its edges connect, each listed once, in the order the
 * edges first mention them, so scheduling is deterministic.
 */
@Experimental
final class Graph {

  private static final Logger logger = LoggerFactory.getLogger(Graph.class);

  private final ImmutableList<Node> nodes;
  private final ImmutableList<Edge> edges;
  private final ImmutableMap<String, Node> nodesByName;
  private final ImmutableSet<String> terminalNodeNames;

  private Graph(
      ImmutableList<Node> nodes,
      ImmutableList<Edge> edges,
      ImmutableMap<String, Node> nodesByName,
      ImmutableSet<String> terminalNodeNames) {
    this.nodes = nodes;
    this.edges = edges;
    this.nodesByName = nodesByName;
    this.terminalNodeNames = terminalNodeNames;
  }

  /** Builds the graph that {@code edges} describe. */
  static Graph of(List<Edge> edges) {
    ImmutableList<Edge> edgeList = ImmutableList.copyOf(edges);
    // Edges reuse a node by passing the same instance, so deduplicate by identity.
    Set<Node> seen = Sets.newIdentityHashSet();
    ImmutableList.Builder<Node> nodeList = ImmutableList.builder();
    for (Edge edge : edgeList) {
      for (Node node : ImmutableList.of(edge.from(), edge.to())) {
        if (seen.add(node)) {
          nodeList.add(node);
        }
      }
    }
    ImmutableList<Node> nodes = nodeList.build();
    ImmutableMap<String, Node> nodesByName =
        nodes.stream().collect(toImmutableMap(Node::name, node -> node, (first, last) -> last));
    ImmutableSet<String> sourceNames =
        edgeList.stream().map(edge -> edge.from().name()).collect(toImmutableSet());
    ImmutableSet<String> terminalNodeNames =
        nodes.stream()
            .map(Node::name)
            .filter(name -> !name.equals(Start.NAME) && !sourceNames.contains(name))
            .collect(toImmutableSet());
    return new Graph(nodes, edgeList, nodesByName, terminalNodeNames);
  }

  ImmutableList<Node> nodes() {
    return nodes;
  }

  ImmutableList<Edge> edges() {
    return edges;
  }

  /** Returns the names of the nodes with no outgoing edge, whose outputs are the graph's own. */
  ImmutableSet<String> terminalNodeNames() {
    return terminalNodeNames;
  }

  /** Returns the node called {@code name}, throwing if the graph has none. */
  Node node(String name) {
    Node node = nodesByName.get(name);
    if (node == null) {
      throw new IllegalArgumentException("No node named '" + name + "' in the graph.");
    }
    return node;
  }

  /**
   * Returns the names of the nodes that {@code nodeName} triggers when it completes and emits
   * {@code emittedRoutes}. {@code emittedRoutes} is empty if the node emitted no route.
   */
  ImmutableList<String> nodesTriggeredBy(String nodeName, List<Route> emittedRoutes) {
    ImmutableList.Builder<String> triggered = ImmutableList.builder();
    List<String> defaultTargets = new ArrayList<>();
    boolean hasRoutedEdge = false;
    boolean matchedSpecificRoute = false;
    for (Edge edge : edges) {
      if (!edge.from().name().equals(nodeName)) {
        continue;
      }
      if (edge.isUnconditional()) {
        triggered.add(edge.to().name());
        continue;
      }
      hasRoutedEdge = true;
      if (edge.routes().contains(Route.Default.INSTANCE)) {
        defaultTargets.add(edge.to().name());
      } else if (edge.routes().stream().anyMatch(emittedRoutes::contains)) {
        triggered.add(edge.to().name());
        matchedSpecificRoute = true;
      }
    }
    // Every default edge fires together, so a fallback can fan out like any other route.
    if (!matchedSpecificRoute) {
      triggered.addAll(defaultTargets);
    }
    ImmutableList<String> result = triggered.build();
    if (result.isEmpty() && hasRoutedEdge) {
      // Flags a likely mistyped route; log only the name, since routes may come from a model.
      logger.warn(
          "Node '{}' has routing edges, but none matched the routes it emitted; its branch ends.",
          nodeName);
    }
    return result;
  }

  /** Returns the names of the nodes with an edge into {@code nodeName}. */
  ImmutableSet<String> predecessorsOf(String nodeName) {
    return edges.stream()
        .filter(edge -> edge.to().name().equals(nodeName))
        .map(edge -> edge.from().name())
        .collect(toImmutableSet());
  }
}
