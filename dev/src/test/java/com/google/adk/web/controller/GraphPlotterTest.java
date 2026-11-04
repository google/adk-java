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
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.tools.BaseTool;
import com.google.adk.web.controller.AppGraph.GraphEdge;
import com.google.adk.web.controller.AppGraph.GraphNode;
import com.google.adk.web.controller.AppGraph.GraphNodeRef;
import com.google.adk.web.controller.AppGraph.GraphTool;
import com.google.adk.web.controller.AppGraph.WorkflowGraph;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GraphPlotterTest {

  private static final String START = Start.INSTANCE.name();

  private static final String LIGHT_GRAPH =
      "graph [bgcolor=\"#F8FAFC\" fontname=Helvetica nodesep=0.5 pad=0.5 ranksep=0.8"
          + " splines=spline]";
  private static final String LIGHT_NODE =
      "node [color=\"#94A3B8\" fillcolor=\"#FFFFFF\" fontcolor=\"#0F172A\" fontname=Helvetica"
          + " fontsize=12 margin=\"0.25,0.15\" penwidth=1.5 shape=rect style=\"rounded,filled\"]";
  private static final String LIGHT_EDGE =
      "edge [arrowhead=vee arrowsize=0.7 color=\"#64748B\" fontcolor=\"#475569\""
          + " fontname=Helvetica fontsize=10 penwidth=1.2]";
  private static final String LIGHT_START =
      "__START__ [label=START color=\"#059669\" fillcolor=\"#10B981\" fixedsize=true"
          + " fontcolor=\"#0F172A\" fontname=\"Helvetica-Bold\" shape=oval style=filled"
          + " width=0.9]";
  private static final String LIGHT_END =
      "__END__ [label=END color=\"#DC2626\" fillcolor=\"#EF4444\" fixedsize=true"
          + " fontcolor=\"#0F172A\" fontname=\"Helvetica-Bold\" shape=oval style=filled"
          + " width=0.9]";

  @Test
  void plot_workflow_drawsItsGraphFromStartToEnd() {
    String dot = GraphPlotter.plot(AppGraph.graphNodeOf(supportWorkflow()), false);

    assertThat(dot)
        .isEqualTo(
            expectedDot(
                LIGHT_GRAPH,
                LIGHT_NODE,
                LIGHT_EDGE,
                "classify [label=classify fillcolor=\"#FFFFFF\" height=1.2 margin=\"0.0,0.0\""
                    + " shape=diamond style=filled tooltip=\"Node\" width=0.8]",
                "billing_agent [label=<<FONT COLOR=\"#42A5F5\" POINT-SIZE=\"14\">✦</FONT>"
                    + " billing_agent> fillcolor=\"#FFFFFF\" style=\"rounded,filled\""
                    + " tooltip=Agent]",
                "tech_flow [label=<<FONT COLOR=\"#9333EA\" POINT-SIZE=\"14\">⊷</FONT> tech_flow>"
                    + " fillcolor=\"#FFFFFF\" style=\"rounded,filled\" tooltip=Workflow]",
                "fallback [label=fallback fillcolor=\"#FFFFFF\" style=\"rounded,filled\""
                    + " tooltip=\"Node\"]",
                "summarize [label=summarize fillcolor=\"#FFFFFF\" style=\"rounded,filled\""
                    + " tooltip=\"Node\"]",
                "lookup_invoice [label=<<FONT COLOR=\"#6B7280\" POINT-SIZE=\"14\">🔧</FONT>"
                    + " lookup_invoice> fillcolor=\"#FFFFFF\" style=\"rounded,filled,dashed\""
                    + " tooltip=Tool]",
                LIGHT_START,
                "__START__ -> classify [label=\"\"]",
                "classify -> billing_agent [label=\"  billing\"]",
                "classify -> tech_flow [label=\"  tech\"]",
                "classify -> fallback [label=\"  __DEFAULT__\"]",
                "billing_agent -> summarize [label=\"\"]",
                "tech_flow -> summarize [label=\"\"]",
                "fallback -> summarize [label=\"\"]",
                "billing_agent -> lookup_invoice [color=\"#64748B\" style=dashed]",
                LIGHT_END,
                "summarize -> __END__"));
  }

  @Test
  void plot_agentTree_drawsAgentsAndToolsWithoutStartOrEnd() {
    String dot = GraphPlotter.plot(AppGraph.graphNodeOf(assistantAgent()), false);

    assertThat(dot)
        .isEqualTo(
            expectedDot(
                LIGHT_GRAPH,
                LIGHT_NODE,
                LIGHT_EDGE,
                "assistant [label=<<FONT COLOR=\"#42A5F5\" POINT-SIZE=\"14\">✦</FONT> assistant>"
                    + " fillcolor=\"#FFFFFF\" style=\"rounded,filled\" tooltip=Agent]",
                "helper [label=<<FONT COLOR=\"#42A5F5\" POINT-SIZE=\"14\">✦</FONT> helper>"
                    + " fillcolor=\"#FFFFFF\" style=\"rounded,filled\" tooltip=Agent]",
                "lookup_invoice [label=<<FONT COLOR=\"#6B7280\" POINT-SIZE=\"14\">🔧</FONT>"
                    + " lookup_invoice> fillcolor=\"#FFFFFF\" style=\"rounded,filled,dashed\""
                    + " tooltip=Tool]",
                "assistant -> helper [label=\"\"]",
                "helper -> lookup_invoice [color=\"#64748B\" style=dashed]"));
  }

  @Test
  void plot_darkMode_drawsWithTheDarkPalette() {
    GraphNode techFlow =
        AppGraph.graphNodeOf(supportWorkflow()).graph().nodes().stream()
            .filter(node -> node.name().equals("tech_flow"))
            .findFirst()
            .orElseThrow();

    String dot = GraphPlotter.plot(techFlow, true);

    assertThat(dot)
        .isEqualTo(
            expectedDot(
                "graph [bgcolor=\"#0F172A\" fontname=Helvetica nodesep=0.5 pad=0.5 ranksep=0.8"
                    + " splines=spline]",
                "node [color=\"#475569\" fillcolor=\"#1E293B\" fontcolor=\"#F8FAFC\""
                    + " fontname=Helvetica fontsize=12 margin=\"0.25,0.15\" penwidth=1.5"
                    + " shape=rect style=\"rounded,filled\"]",
                "edge [arrowhead=vee arrowsize=0.7 color=\"#94A3B8\" fontcolor=\"#CBD5E1\""
                    + " fontname=Helvetica fontsize=10 penwidth=1.2]",
                "diagnose [label=diagnose fillcolor=\"#1E293B\" style=\"rounded,filled\""
                    + " tooltip=\"Node\"]",
                "fix [label=fix fillcolor=\"#1E293B\" style=\"rounded,filled\" tooltip=\"Node\"]",
                "__START__ [label=START color=\"#047857\" fillcolor=\"#059669\" fixedsize=true"
                    + " fontcolor=\"#F8FAFC\" fontname=\"Helvetica-Bold\" shape=oval"
                    + " style=filled width=0.9]",
                "__START__ -> diagnose [label=\"\"]",
                "diagnose -> fix [label=\"\"]",
                "__END__ [label=END color=\"#B91C1C\" fillcolor=\"#DC2626\" fixedsize=true"
                    + " fontcolor=\"#F8FAFC\" fontname=\"Helvetica-Bold\" shape=oval"
                    + " style=filled width=0.9]",
                "fix -> __END__"));
  }

  @Test
  void plot_routesWithoutDefaultJoinsAndEscapedNames_drawsExpectedShapesAndLabels() {
    String quoted = "say \"hi\"";
    GraphNode root =
        workflowNode(
            "edge_cases",
            ImmutableList.of(
                new GraphNode(START, "start"),
                new GraphNode("pick", "node"),
                new GraphNode(
                    "fn",
                    "function",
                    null,
                    ImmutableList.of(new GraphTool("a<b&\"c'", "tool")),
                    null),
                new GraphNode("merge", "join"),
                new GraphNode(quoted, "node")),
            ImmutableList.of(
                edge(START, "pick", null),
                edge("pick", "fn", new Route.Tag("a")),
                edge("pick", "merge", new Route.Flag(true)),
                edge("fn", "merge", ImmutableList.of(new Route.Tag("x"), new Route.Tag("y"))),
                edge("fn", quoted, new Route.Num(1)),
                edge("merge", quoted, null)));

    String dot = GraphPlotter.plot(root, false);

    assertThat(dot)
        .isEqualTo(
            expectedDot(
                LIGHT_GRAPH,
                LIGHT_NODE,
                LIGHT_EDGE,
                "pick [label=<pick<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>"
                    + " fillcolor=\"#FFFFFF\" height=1.2 margin=\"0.0,0.0\" shape=diamond"
                    + " style=filled tooltip=\"Node\" width=0.8]",
                "fn [label=<<FONT COLOR=\"#10B981\" POINT-SIZE=\"14\">ƒ</FONT> fn<br/><br/><FONT"
                    + " POINT-SIZE=\"10\">⚠️ [NO DEFAULT]</FONT>> fillcolor=\"#FFFFFF\""
                    + " height=1.2 margin=\"0.0,0.0\" shape=diamond style=filled"
                    + " tooltip=Function width=0.8]",
                "merge [label=<<FONT COLOR=\"#F59E0B\" POINT-SIZE=\"14\">⌵</FONT> merge>"
                    + " fillcolor=\"#FFFFFF\" margin=\"0.05,0.05\" shape=oval style=filled"
                    + " tooltip=Join]",
                "\"say \\\"hi\\\"\" [label=\"say \\\"hi\\\"\" fillcolor=\"#FFFFFF\""
                    + " style=\"rounded,filled\" tooltip=\"Node\"]",
                "\"a<b&\\\"c'\" [label=<<FONT COLOR=\"#6B7280\" POINT-SIZE=\"14\">🔧</FONT>"
                    + " a&lt;b&amp;&quot;c&#x27;> fillcolor=\"#FFFFFF\""
                    + " style=\"rounded,filled,dashed\" tooltip=Tool]",
                LIGHT_START,
                "__START__ -> pick [label=\"\"]",
                "pick -> fn [label=\"  a\"]",
                "pick -> merge [label=\"  True\"]",
                "fn -> merge [label=\"  ['x', 'y']\"]",
                "fn -> \"say \\\"hi\\\"\" [label=\"  1\"]",
                "merge -> \"say \\\"hi\\\"\" [label=\"\"]",
                "fn -> \"a<b&\\\"c'\" [color=\"#64748B\" style=dashed]",
                LIGHT_END,
                "\"say \\\"hi\\\"\" -> __END__"));
  }

  @Test
  void plot_backslashesQuotesAndListRoutes_quotesIdentifiersAndListLabels() {
    String backslash = "a\\b";
    String escapedQuotes = "say \\\"hi\\\"";
    GraphNode root =
        workflowNode(
            "quirks",
            ImmutableList.of(
                new GraphNode(START, "start"),
                new GraphNode("pick", "node"),
                new GraphNode(backslash, "node"),
                new GraphNode(escapedQuotes, "node")),
            ImmutableList.of(
                edge(START, "pick", null),
                edge(
                    "pick",
                    backslash,
                    ImmutableList.of(
                        new Route.Tag("won't"), new Route.Tag("x\"y"), new Route.Tag("p\\q"))),
                edge("pick", escapedQuotes, Route.Default.INSTANCE)));

    String dot = GraphPlotter.plot(root, false);

    assertThat(dot)
        .isEqualTo(
            expectedDot(
                LIGHT_GRAPH,
                LIGHT_NODE,
                LIGHT_EDGE,
                "pick [label=pick fillcolor=\"#FFFFFF\" height=1.2 margin=\"0.0,0.0\""
                    + " shape=diamond style=filled tooltip=\"Node\" width=0.8]",
                "\"a\\b\" [label=\"a\\b\" fillcolor=\"#FFFFFF\" style=\"rounded,filled\""
                    + " tooltip=\"Node\"]",
                "\"say \\\"hi\\\"\" [label=\"say \\\"hi\\\"\" fillcolor=\"#FFFFFF\""
                    + " style=\"rounded,filled\" tooltip=\"Node\"]",
                LIGHT_START,
                "__START__ -> pick [label=\"\"]",
                "pick -> \"a\\b\" [label=\"  [\\\"won't\\\", 'x\\\"y', 'p\\\\q']\"]",
                "pick -> \"say \\\"hi\\\"\" [label=\"  __DEFAULT__\"]",
                LIGHT_END,
                "\"a\\b\" -> __END__",
                "\"say \\\"hi\\\"\" -> __END__"));
  }

  @Test
  void plot_falseRoute_labelsItAndMakesItsSourceConditional() {
    Node pick = step("pick");
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(
                new Edge(Start.INSTANCE, pick), new Edge(pick, step("no"), new Route.Flag(false)))
            .build();

    String dot = GraphPlotter.plot(AppGraph.graphNodeOf(workflow), false);

    assertThat(dot).contains("pick -> no [label=\"  False\"]");
    assertThat(dot)
        .contains("pick [label=<pick<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>");
  }

  @Test
  void plot_trueAndFalseRoutes_warnNoDefault() {
    Node pick = step("pick");
    Node count = step("count");
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(
                new Edge(Start.INSTANCE, pick),
                new Edge(pick, step("yes"), new Route.Flag(true)),
                new Edge(pick, step("no"), new Route.Flag(false)),
                new Edge(Start.INSTANCE, count),
                new Edge(count, step("zero"), new Route.Num(0)),
                new Edge(count, step("one"), new Route.Num(1)))
            .build();

    String dot = GraphPlotter.plot(AppGraph.graphNodeOf(workflow), false);

    assertThat(dot)
        .contains("pick [label=<pick<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>");
    assertThat(dot)
        .contains("count [label=<count<br/><br/><font point-size='10'>⚠️ [NO DEFAULT]</font>>");
  }

  @Test
  void plot_plainNameThatLooksLikeHtml_quotesItInsteadOfParsingMarkup() {
    GraphNode root =
        workflowNode("root", ImmutableList.of(new GraphNode("<b>", "node")), ImmutableList.of());

    String dot = GraphPlotter.plot(root, false);

    assertThat(dot).contains("\t\"<b>\" [label=\"<b>\" fillcolor=");
  }

  @Test
  void plot_plainNameThatLooksLikePort_quotesItInsteadOfReadingAPort() {
    GraphNode root =
        workflowNode("root", ImmutableList.of(new GraphNode("a:b", "node")), ImmutableList.of());

    String dot = GraphPlotter.plot(root, false);

    assertThat(dot).contains("\t\"a:b\" -> __END__\n");
  }

  @Test
  void plot_routedAgentWithDefaultRoute_drawsAnIconDiamondWithoutWarning() {
    Node router = llmAgent("router", List.of(), List.of());
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(
                new Edge(Start.INSTANCE, router),
                new Edge(router, step("special"), new Route.Tag("x")),
                new Edge(router, step("usual"), Route.Default.INSTANCE))
            .build();

    String dot = GraphPlotter.plot(AppGraph.graphNodeOf(workflow), false);

    assertThat(dot)
        .contains(
            "router [label=<<FONT COLOR=\"#42A5F5\" POINT-SIZE=\"14\">✦</FONT> router>"
                + " fillcolor=\"#FFFFFF\" height=1.2 margin=\"0.0,0.0\" shape=diamond"
                + " style=filled tooltip=Agent width=0.8]");
    assertThat(dot).doesNotContain("NO DEFAULT");
  }

  @Test
  void plot_agentsWithOnlyToolEdges_endAtEndAndShareOneToolNode() {
    BaseTool sharedTool = tool("t");
    Node step = step("step");
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(
                new Edge(Start.INSTANCE, step),
                new Edge(step, llmAgent("first_agent", List.of(sharedTool), List.of())),
                new Edge(step, llmAgent("second_agent", List.of(sharedTool), List.of())))
            .build();

    String dot = GraphPlotter.plot(AppGraph.graphNodeOf(workflow), false);

    assertThat(dot).contains("\tfirst_agent -> __END__\n");
    assertThat(dot).contains("\tsecond_agent -> __END__\n");
    assertThat(dot).doesNotContain("step -> __END__");
    assertThat(dot.lines().filter(line -> line.startsWith("\tt [")).count()).isEqualTo(1);
  }

  private static GraphNode workflowNode(
      String name, ImmutableList<GraphNode> nodes, ImmutableList<GraphEdge> edges) {
    return new GraphNode(name, "workflow", null, null, new WorkflowGraph(nodes, edges));
  }

  private static GraphEdge edge(String from, String to, @Nullable Object route) {
    return new GraphEdge(new GraphNodeRef(from, "node"), new GraphNodeRef(to, "node"), route);
  }

  private static String expectedDot(String... statements) {
    StringBuilder dot = new StringBuilder("// Workflow Visualization\ndigraph {\n");
    for (String statement : statements) {
      dot.append('\t').append(statement).append('\n');
    }
    return dot.append("}\n").toString();
  }
}
