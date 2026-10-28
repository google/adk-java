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
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static java.util.stream.Collectors.joining;

import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Checks that a workflow graph can run, throwing {@link GraphValidationException} on the first
 * problem. Each rule rejects a graph that would otherwise fail confusingly at run time, or never
 * fail: a node nothing reaches never runs, and an unconditional cycle never terminates.
 */
@Experimental
final class GraphValidation {

  private GraphValidation() {}

  /** Validates the graph of {@code nodes}, each listed once, and the {@code edges} between them. */
  static void validate(List<Node> nodes, List<Edge> edges) {
    ImmutableSet<String> nodeNames = validateUniqueNames(nodes);
    validateStartPresent(nodeNames);
    validateStartEdges(edges);
    validateConnectivity(edges, nodeNames);
    validateNoDuplicateEdges(edges);
    validateDefaultRoutes(edges);
    validateNoUnconditionalCycles(edges, nodeNames);
  }

  private static GraphValidationException invalid(String message) {
    return new GraphValidationException("Graph validation failed. " + message);
  }

  /** Returns the node names in the order of {@code nodes}, throwing if two nodes share one. */
  private static ImmutableSet<String> validateUniqueNames(List<Node> nodes) {
    ImmutableListMultimap<String, Node> nodesByName = Multimaps.index(nodes, Node::name);
    ImmutableList<String> duplicates =
        nodesByName.keySet().stream()
            .filter(name -> nodesByName.get(name).size() > 1)
            .sorted()
            .collect(toImmutableList());
    if (!duplicates.isEmpty()) {
      throw invalid(
          "Duplicate node names found: "
              + duplicates
              + ". Distinct node objects share a name. To reuse a node, pass the same instance; to"
              + " keep them distinct, give them different names.");
    }
    return nodesByName.keySet();
  }

  private static void validateStartPresent(Set<String> nodeNames) {
    if (!nodeNames.contains(Start.NAME)) {
      throw invalid("START node (name: '" + Start.NAME + "') not found in graph nodes.");
    }
  }

  private static void validateStartEdges(List<Edge> edges) {
    for (Edge edge : edges) {
      if (edge.from().name().equals(Start.NAME) && !edge.isUnconditional()) {
        throw invalid(
            "Edges from START must not have routes (edge to "
                + edge.to().name()
                + " has route "
                + edge.routes().stream().map(GraphValidation::label).collect(joining(", "))
                + ").");
      }
    }
  }

  /** Returns {@code route} as error messages show it: its bare value, or the default sentinel. */
  private static String label(Route route) {
    if (route instanceof Route.Tag tag) {
      return tag.value();
    }
    if (route instanceof Route.Num num) {
      return Long.toString(num.value());
    }
    if (route instanceof Route.Flag flag) {
      return Boolean.toString(flag.value());
    }
    return Route.DEFAULT_ROUTE_SENTINEL;
  }

  private static void validateConnectivity(List<Edge> edges, Set<String> nodeNames) {
    ImmutableListMultimap<String, String> successors =
        edges.stream()
            .collect(toImmutableListMultimap(edge -> edge.from().name(), edge -> edge.to().name()));
    Set<String> reachable = new HashSet<>();
    Queue<String> pending = new ArrayDeque<>();
    pending.add(Start.NAME);
    while (!pending.isEmpty()) {
      String current = pending.remove();
      if (reachable.add(current)) {
        pending.addAll(successors.get(current));
      }
    }

    ImmutableList<String> unreachable =
        nodeNames.stream()
            .filter(name -> !reachable.contains(name))
            .sorted()
            .collect(toImmutableList());
    if (!unreachable.isEmpty()) {
      throw invalid("The following nodes are unreachable from START: " + unreachable);
    }
    if (edges.stream().anyMatch(edge -> edge.to().name().equals(Start.NAME))) {
      throw invalid("START node must not have incoming edges.");
    }
  }

  /**
   * Rejects a second edge between the same two nodes unless both are routed and share no route,
   * since an overlapping route would trigger the target twice for the same emitted route.
   */
  private static void validateNoDuplicateEdges(List<Edge> edges) {
    // An empty set marks an unconditional edge, which overlaps every other edge in its pair.
    Map<NodePair, Set<Route>> routesByPair = new HashMap<>();
    for (Edge edge : edges) {
      NodePair pair = new NodePair(edge.from().name(), edge.to().name());
      Set<Route> seen = routesByPair.get(pair);
      if (seen != null
          && (seen.isEmpty()
              || edge.isUnconditional()
              || !Collections.disjoint(seen, edge.routes()))) {
        throw invalid("Duplicate edge found: from=" + pair.from() + ", to=" + pair.to());
      }
      routesByPair.computeIfAbsent(pair, unused -> new HashSet<>()).addAll(edge.routes());
    }
  }

  private static void validateDefaultRoutes(List<Edge> edges) {
    for (Edge edge : edges) {
      if (edge.routes().contains(Route.Default.INSTANCE) && edge.routes().size() > 1) {
        throw invalid(
            "DEFAULT_ROUTE cannot be combined with other routes in a list (edge from="
                + edge.from().name()
                + ", to="
                + edge.to().name()
                + "). Use a separate edge for DEFAULT_ROUTE.");
      }
    }
  }

  private static void validateNoUnconditionalCycles(List<Edge> edges, Set<String> nodeNames) {
    ImmutableListMultimap<String, String> successors =
        edges.stream()
            .filter(Edge::isUnconditional)
            .collect(toImmutableListMultimap(edge -> edge.from().name(), edge -> edge.to().name()));
    Set<String> done = new HashSet<>();
    for (String name : nodeNames) {
      if (!done.contains(name)) {
        visitUnconditional(name, successors, new LinkedHashSet<>(), done);
      }
    }
  }

  /**
   * Follows the unconditional edges depth-first from {@code node}, throwing on an edge back to a
   * node on {@code path}, and adds each node it finishes exploring to {@code done}.
   */
  private static void visitUnconditional(
      String node,
      ImmutableListMultimap<String, String> successors,
      LinkedHashSet<String> path,
      Set<String> done) {
    path.add(node);
    for (String next : successors.get(node)) {
      if (path.contains(next)) {
        List<String> pathList = new ArrayList<>(path);
        List<String> cycle =
            new ArrayList<>(pathList.subList(pathList.indexOf(next), pathList.size()));
        cycle.add(next);
        throw invalid(
            "Unconditional cycle detected: "
                + String.join(" -> ", cycle)
                + ". A cycle must include at least one routed edge, or it never terminates.");
      }
      if (!done.contains(next)) {
        visitUnconditional(next, successors, path, done);
      }
    }
    path.remove(node);
    done.add(node);
  }

  private record NodePair(String from, String to) {}
}
