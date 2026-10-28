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

import static com.google.adk.web.testing.GraphFixtures.assistantAgent;
import static com.google.adk.web.testing.GraphFixtures.llmAgent;
import static com.google.adk.web.testing.GraphFixtures.step;
import static com.google.adk.web.testing.GraphFixtures.supportWorkflow;
import static com.google.adk.web.testing.GraphFixtures.tool;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.web.controller.AppGraph.GraphNode;
import com.google.adk.web.controller.AppGraph.GraphNodeRef;
import com.google.adk.web.controller.AppGraph.GraphTool;
import com.google.adk.web.controller.AppGraph.WorkflowGraph;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.JoinNode;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class AppGraphTest {

  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  @Test
  void of_workflowRoot_describesStartRoutesAndNestedWorkflows() throws Exception {
    JsonNode json = objectMapper.valueToTree(AppGraph.of("support_app", supportWorkflow()));

    assertThat(json)
        .isEqualTo(
            objectMapper.readTree(
                """
                {"name": "support_app", "root_agent": {"name": "support", "type": "workflow",
                  "graph": {
                    "nodes": [
                      {"name": "__START__", "type": "start"},
                      {"name": "classify", "type": "node"},
                      {"name": "billing_agent", "type": "agent", "sub_agents": [],
                       "tools": [{"name": "lookup_invoice", "type": "tool"}]},
                      {"name": "tech_flow", "type": "workflow", "graph": {
                        "nodes": [
                          {"name": "__START__", "type": "start"},
                          {"name": "diagnose", "type": "node"},
                          {"name": "fix", "type": "node"}],
                        "edges": [
                          {"from_node": {"name": "__START__", "type": "start"},
                           "to_node": {"name": "diagnose", "type": "node"}},
                          {"from_node": {"name": "diagnose", "type": "node"},
                           "to_node": {"name": "fix", "type": "node"}}]}},
                      {"name": "fallback", "type": "node"},
                      {"name": "summarize", "type": "node"}],
                    "edges": [
                      {"from_node": {"name": "__START__", "type": "start"},
                       "to_node": {"name": "classify", "type": "node"}},
                      {"from_node": {"name": "classify", "type": "node"},
                       "to_node": {"name": "billing_agent", "type": "agent"}, "route": "billing"},
                      {"from_node": {"name": "classify", "type": "node"},
                       "to_node": {"name": "tech_flow", "type": "workflow"}, "route": "tech"},
                      {"from_node": {"name": "classify", "type": "node"},
                       "to_node": {"name": "fallback", "type": "node"}, "route": "__DEFAULT__"},
                      {"from_node": {"name": "billing_agent", "type": "agent"},
                       "to_node": {"name": "summarize", "type": "node"}},
                      {"from_node": {"name": "tech_flow", "type": "workflow"},
                       "to_node": {"name": "summarize", "type": "node"}},
                      {"from_node": {"name": "fallback", "type": "node"},
                       "to_node": {"name": "summarize", "type": "node"}}]}}}
                """));
  }

  @Test
  void of_agentRoot_describesSubAgentsAndTools() throws Exception {
    JsonNode json = objectMapper.valueToTree(AppGraph.of("assistant", assistantAgent()));

    assertThat(json)
        .isEqualTo(
            objectMapper.readTree(
                """
                {"name": "assistant", "root_agent": {"name": "assistant", "type": "agent",
                  "tools": [],
                  "sub_agents": [{"name": "helper", "type": "agent", "sub_agents": [],
                    "tools": [{"name": "lookup_invoice", "type": "tool"}]}]}}
                """));
  }

  @Test
  void graphNodeOf_nonLlmAgent_describesSubAgentsWithoutTools() {
    GraphNode node =
        AppGraph.graphNodeOf(
            SequentialAgent.builder()
                .name("pipeline")
                .subAgents(llmAgent("first", ImmutableList.of(), ImmutableList.of()))
                .build());

    JsonNode json = objectMapper.valueToTree(node);
    assertThat(json.get("sub_agents").get(0).get("name").asText()).isEqualTo("first");
    assertThat(json.has("tools")).isFalse();
  }

  @Test
  void graphNodeOf_toolNamedLikeASubAgent_skipsItAndNamesToolsetsByClass() {
    BaseToolset anonymousToolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(@Nullable ReadonlyContext readonlyContext) {
            return Flowable.empty();
          }

          @Override
          public void close() {}
        };
    GraphNode agent =
        AppGraph.graphNodeOf(
            llmAgent(
                "root",
                List.of(tool("helper"), new InvoiceToolset(), tool("search"), anonymousToolset),
                List.of(llmAgent("helper", List.of(), List.of()))));

    assertThat(agent.tools())
        .containsExactly(
            new GraphTool("InvoiceToolset", "tool"),
            new GraphTool("search", "tool"),
            new GraphTool("Toolset", "tool"))
        .inOrder();
  }

  @Test
  void graphNodeOf_routedEdges_describesRoutesAsScalarsOrAList() throws Exception {
    Node a = step("a");
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(
                new Edge(Start.INSTANCE, a),
                new Edge(a, step("b"), new Route.Num(1)),
                new Edge(a, step("c"), new Route.Flag(false)),
                new Edge(a, step("d"), ImmutableList.of(new Route.Tag("x"), new Route.Tag("y"))))
            .build();

    WorkflowGraph graph = AppGraph.graphNodeOf(workflow).graph();

    assertThat(graph.edges().stream().map(AppGraphTest::routeJson).toList())
        .containsExactly(null, "1", "false", "[\"x\",\"y\"]")
        .inOrder();
  }

  @Test
  void graphNodeOf_joinNode_describesJoinTypeInNodesAndEdges() {
    Node a = step("a");
    JoinNode join = JoinNode.builder().name("merge").build();
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(new Edge(Start.INSTANCE, a), new Edge(a, join))
            .build();

    WorkflowGraph graph = AppGraph.graphNodeOf(workflow).graph();

    assertThat(graph.nodes()).contains(new GraphNode("merge", "join"));
    assertThat(graph.edges().get(1).to()).isEqualTo(new GraphNodeRef("merge", "join"));
  }

  @Test
  void graphNodeOf_nodeReachedByTwoEdges_listsItOnce() {
    Node a = step("a");
    Node join = step("join");
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(new Edge(Start.INSTANCE, a), new Edge(a, join), new Edge(Start.INSTANCE, join))
            .build();

    WorkflowGraph graph = AppGraph.graphNodeOf(workflow).graph();

    assertThat(graph.nodes().stream().map(GraphNode::name).collect(toImmutableList()))
        .containsExactly("__START__", "a", "join")
        .inOrder();
  }

  @Test
  void graphNodeOf_workflowWithoutEdges_hasNoGraph() {
    GraphNode node = AppGraph.graphNodeOf(Workflow.builder().name("empty").build());

    assertThat(node).isEqualTo(new GraphNode("empty", "workflow"));
    assertThat(objectMapper.valueToTree(node).has("graph")).isFalse();
  }

  /** Returns the edge's {@code route} as JSON text, or null when the edge omits it. */
  private static @Nullable String routeJson(AppGraph.GraphEdge edge) {
    JsonNode route = objectMapper.valueToTree(edge).get("route");
    return route == null ? null : route.toString();
  }

  /** A toolset the graph draws under its class name. */
  private static final class InvoiceToolset implements BaseToolset {
    @Override
    public Flowable<BaseTool> getTools(@Nullable ReadonlyContext readonlyContext) {
      return Flowable.empty();
    }

    @Override
    public void close() {}
  }
}
