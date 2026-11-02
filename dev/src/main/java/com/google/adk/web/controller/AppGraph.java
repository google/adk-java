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

package com.google.adk.web.controller;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.JoinNode;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An app's root and everything under it, as the Dev UI reads it from {@code
 * /dev/apps/{appName}/build_graph}.
 *
 * <p>Keys are snake_case, though the server's mapper writes camelCase, because that is how the Dev
 * UI reads this payload.
 */
record AppGraph(String name, @JsonProperty("root_agent") GraphNode rootAgent) {

  static final String AGENT_NODE_TYPE = "agent";
  static final String WORKFLOW_NODE_TYPE = "workflow";
  static final String TOOL_NODE_TYPE = "tool";
  static final String JOIN_NODE_TYPE = "join";

  /** The name a toolset without a class name, such as an anonymous class, is drawn under. */
  private static final String TOOLSET_NAME = "Toolset";

  /** Describes the app {@code appName}, rooted at {@code root}. */
  static AppGraph of(String appName, Node root) {
    return new AppGraph(appName, graphNodeOf(root));
  }

  /** Describes {@code node} and everything under it. */
  static GraphNode graphNodeOf(Node node) {
    if (node instanceof Workflow workflow) {
      return new GraphNode(
          node.name(),
          typeOf(node),
          /* subAgents= */ null,
          /* tools= */ null,
          workflowGraphOf(workflow));
    }
    if (node instanceof BaseAgent agent) {
      return new GraphNode(
          agent.name(),
          typeOf(agent),
          agent.subAgents().stream().map(AppGraph::graphNodeOf).collect(toImmutableList()),
          agent instanceof LlmAgent llmAgent ? toolsOf(llmAgent) : null,
          /* graph= */ null);
    }
    return new GraphNode(node.name(), typeOf(node));
  }

  /**
   * One node of an app: an agent with its sub-agents and tools, a workflow with its graph, or any
   * other node.
   *
   * @param type how the graph draws the node: {@code agent}, {@code workflow}, {@code join}, {@code
   *     start} or {@code node}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record GraphNode(
      String name,
      String type,
      @JsonProperty("sub_agents") @Nullable ImmutableList<GraphNode> subAgents,
      @Nullable ImmutableList<GraphTool> tools,
      @Nullable WorkflowGraph graph) {

    GraphNode(String name, String type) {
      this(name, type, /* subAgents= */ null, /* tools= */ null, /* graph= */ null);
    }
  }

  record GraphTool(String name, String type) {}

  record WorkflowGraph(ImmutableList<GraphNode> nodes, ImmutableList<GraphEdge> edges) {}

  /** An edge between two nodes; {@code route} is absent on an unconditional one. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record GraphEdge(
      @JsonProperty("from_node") GraphNodeRef from,
      @JsonProperty("to_node") GraphNodeRef to,
      @Nullable Object route) {}

  record GraphNodeRef(String name, String type) {}

  /** Returns the node type string used by the Dev UI graph renderer for {@code node}. */
  private static String typeOf(Node node) {
    if (node instanceof Start) {
      return "start";
    }
    if (node instanceof Workflow) {
      return WORKFLOW_NODE_TYPE;
    }
    if (node instanceof BaseAgent) {
      return AGENT_NODE_TYPE;
    }
    if (node instanceof JoinNode) {
      return JOIN_NODE_TYPE;
    }
    return "node";
  }

  private static @Nullable WorkflowGraph workflowGraphOf(Workflow workflow) {
    if (workflow.edges().isEmpty()) {
      return null;
    }
    // Preserve first-appearance order and deduplicate by node identity.
    Set<Node> seen = Sets.newIdentityHashSet();
    List<Node> nodes = new ArrayList<>();
    for (Edge edge : workflow.edges()) {
      for (Node node : List.of(edge.from(), edge.to())) {
        if (seen.add(node)) {
          nodes.add(node);
        }
      }
    }
    return new WorkflowGraph(
        nodes.stream().map(AppGraph::graphNodeOf).collect(toImmutableList()),
        workflow.edges().stream()
            .map(edge -> new GraphEdge(refOf(edge.from()), refOf(edge.to()), routeOrNull(edge)))
            .collect(toImmutableList()));
  }

  private static GraphNodeRef refOf(Node node) {
    return new GraphNodeRef(node.name(), typeOf(node));
  }

  /**
   * Returns {@code edge}'s routes as null when the edge is unconditional, a single route as itself,
   * which serializes as a scalar, and any other route list as a list.
   */
  private static @Nullable Object routeOrNull(Edge edge) {
    if (edge.isUnconditional()) {
      return null;
    }
    ImmutableList<Route> routes = edge.routes();
    return routes.size() == 1 ? routes.get(0) : routes;
  }

  /** The agent's tools and toolsets in declaration order, minus any named after a sub-agent. */
  private static ImmutableList<GraphTool> toolsOf(LlmAgent agent) {
    ImmutableSet<String> subAgentNames =
        agent.subAgents().stream().map(BaseAgent::name).collect(toImmutableSet());
    return agent.toolsUnion().stream()
        .map(AppGraph::toolName)
        .filter(name -> !subAgentNames.contains(name))
        .map(name -> new GraphTool(name, TOOL_NODE_TYPE))
        .collect(toImmutableList());
  }

  /** Names a tool by its name, and a toolset, which has none, by its class. */
  private static String toolName(Object toolOrToolset) {
    if (toolOrToolset instanceof BaseTool tool) {
      return tool.name();
    }
    String className = toolOrToolset.getClass().getSimpleName();
    return className.isEmpty() ? TOOLSET_NAME : className;
  }
}
