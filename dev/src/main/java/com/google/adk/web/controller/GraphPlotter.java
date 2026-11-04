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

import static com.google.adk.web.controller.AppGraph.AGENT_NODE_TYPE;
import static com.google.adk.web.controller.AppGraph.JOIN_NODE_TYPE;
import static com.google.adk.web.controller.AppGraph.TOOL_NODE_TYPE;
import static com.google.adk.web.controller.AppGraph.WORKFLOW_NODE_TYPE;
import static java.util.stream.Collectors.joining;

import com.google.adk.web.controller.AppGraph.GraphEdge;
import com.google.adk.web.controller.AppGraph.GraphNode;
import com.google.adk.web.controller.AppGraph.GraphTool;
import com.google.adk.web.controller.AppGraph.WorkflowGraph;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Start;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Renders a graph as Graphviz DOT source for the Dev UI: a workflow graph from START to END, or an
 * agent tree with attached tools drawn as dashed nodes.
 */
final class GraphPlotter {

  private static final Palette DARK_PALETTE =
      new Palette(
          "#0F172A", "#1E293B", "#475569", "#F8FAFC", "#94A3B8", "#CBD5E1", "#059669", "#047857",
          "#DC2626", "#B91C1C");

  private static final Palette LIGHT_PALETTE =
      new Palette(
          "#F8FAFC", "#FFFFFF", "#94A3B8", "#0F172A", "#64748B", "#475569", "#10B981", "#059669",
          "#EF4444", "#DC2626");

  private static final ImmutableMap<String, Icon> ICONS =
      ImmutableMap.of(
          AGENT_NODE_TYPE,
          new Icon("✦", "#42A5F5"),
          WORKFLOW_NODE_TYPE,
          new Icon("⊷", "#9333EA"),
          "function",
          new Icon("ƒ", "#10B981"),
          JOIN_NODE_TYPE,
          new Icon("⌵", "#F59E0B"),
          TOOL_NODE_TYPE,
          new Icon("🔧", "#6B7280"));

  private static final String START_NODE_NAME = Start.INSTANCE.name();

  private static final String END_NODE_NAME = "__END__";

  private static final String NO_DEFAULT_WARNING = "⚠️ [NO DEFAULT]";

  private static final Pattern DOT_ID =
      Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*|-?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)");

  private static final ImmutableSet<String> DOT_KEYWORDS =
      ImmutableSet.of("node", "edge", "graph", "digraph", "subgraph", "strict");

  /** An even run of backslashes, then a double quote that may already be escaped. */
  private static final Pattern OPTIONALLY_ESCAPED_QUOTE = Pattern.compile("((?:\\\\\\\\)*)\\\\?\"");

  static String plot(GraphNode root, boolean darkMode) {
    Palette palette = darkMode ? DARK_PALETTE : LIGHT_PALETTE;
    List<PlotNode> nodes = new ArrayList<>();
    List<PlotEdge> edges = new ArrayList<>();
    WorkflowGraph graph = root.graph();
    if (graph != null) {
      for (GraphNode node : graph.nodes()) {
        nodes.add(new PlotNode(node.name(), node.type(), toolsOf(node)));
      }
      for (GraphEdge edge : graph.edges()) {
        edges.add(new PlotEdge(edge.from().name(), edge.to().name(), edge.route(), false));
      }
    } else {
      addAgentTree(root, nodes, edges);
    }
    addTools(nodes, edges);

    DotWriter dot = new DotWriter();
    dot.statement(
        "graph",
        Map.of(
            "bgcolor", palette.background(),
            "pad", "0.5",
            "nodesep", "0.5",
            "ranksep", "0.8",
            "fontname", "Helvetica",
            "splines", "spline"));
    dot.statement(
        "node",
        Map.of(
            "shape", "rect",
            "style", "rounded,filled",
            "fillcolor", palette.nodeFill(),
            "color", palette.nodeLine(),
            "penwidth", "1.5",
            "fontname", "Helvetica",
            "fontcolor", palette.nodeFont(),
            "fontsize", "12",
            "margin", "0.25,0.15"));
    dot.statement(
        "edge",
        Map.of(
            "color", palette.edgeLine(),
            "penwidth", "1.2",
            "fontname", "Helvetica",
            "fontcolor", palette.edgeFont(),
            "fontsize", "10",
            "arrowhead", "vee",
            "arrowsize", "0.7"));
    for (PlotNode node : nodes) {
      if (!node.name().equals(START_NODE_NAME)) {
        drawNode(dot, node, edges, palette);
      }
    }
    for (PlotEdge edge : edges) {
      if (edge.from().equals(START_NODE_NAME)) {
        drawTerminus(
            dot, START_NODE_NAME, "START", palette.startFill(), palette.startLine(), palette);
      }
      if (edge.isToolEdge()) {
        dot.edge(
            edge.from(), edge.to(), null, Map.of("style", "dashed", "color", palette.edgeLine()));
      } else {
        String label = edge.route() == null ? "" : "  " + routeLabel(edge.route());
        dot.edge(edge.from(), edge.to(), plainLabel(label), Map.of());
      }
    }
    List<PlotNode> terminalNodes =
        nodes.stream()
            .filter(
                node ->
                    !node.name().equals(START_NODE_NAME)
                        && !node.name().equals(END_NODE_NAME)
                        && !node.type().equals(TOOL_NODE_TYPE)
                        && edges.stream()
                            .noneMatch(
                                edge -> edge.from().equals(node.name()) && !edge.isToolEdge()))
            .toList();
    if (graph != null && !terminalNodes.isEmpty()) {
      drawTerminus(dot, END_NODE_NAME, "END", palette.endFill(), palette.endLine(), palette);
      for (PlotNode node : terminalNodes) {
        dot.edge(node.name(), END_NODE_NAME, null, Map.of());
      }
    }
    return dot.build();
  }

  private record PlotNode(String name, String type, ImmutableList<GraphTool> tools) {}

  /** An edge to draw; {@code route} is a {@link Route}, a list of them, or null. */
  private record PlotEdge(String from, String to, @Nullable Object route, boolean isToolEdge) {}

  private record Palette(
      String background,
      String nodeFill,
      String nodeLine,
      String nodeFont,
      String edgeLine,
      String edgeFont,
      String startFill,
      String startLine,
      String endFill,
      String endLine) {}

  private record Icon(String glyph, String color) {}

  /** A label in DOT form: a quoted string, or HTML-like markup, which DOT takes unquoted. */
  private record DotLabel(String dot) {}

  private static ImmutableList<GraphTool> toolsOf(GraphNode node) {
    return node.tools() == null ? ImmutableList.of() : node.tools();
  }

  private static void addAgentTree(GraphNode agent, List<PlotNode> nodes, List<PlotEdge> edges) {
    nodes.add(new PlotNode(agent.name(), AGENT_NODE_TYPE, toolsOf(agent)));
    if (agent.subAgents() == null) {
      return;
    }
    for (GraphNode sub : agent.subAgents()) {
      edges.add(new PlotEdge(agent.name(), sub.name(), null, false));
      addAgentTree(sub, nodes, edges);
    }
  }

  private static void addTools(List<PlotNode> nodes, List<PlotEdge> edges) {
    Map<String, String> toolTypes = new LinkedHashMap<>();
    List<PlotEdge> toolEdges = new ArrayList<>();
    for (PlotNode node : nodes) {
      if (node.name().equals(START_NODE_NAME)) {
        continue;
      }
      for (GraphTool tool : node.tools()) {
        if (tool.name().isEmpty()) {
          continue;
        }
        toolTypes.putIfAbsent(tool.name(), tool.type());
        toolEdges.add(new PlotEdge(node.name(), tool.name(), null, true));
      }
    }
    for (Map.Entry<String, String> tool : toolTypes.entrySet()) {
      if (nodes.stream().noneMatch(node -> node.name().equals(tool.getKey()))) {
        nodes.add(new PlotNode(tool.getKey(), tool.getValue(), ImmutableList.of()));
      }
    }
    edges.addAll(toolEdges);
  }

  private static void drawNode(
      DotWriter dot, PlotNode node, List<PlotEdge> edges, Palette palette) {
    List<PlotEdge> outgoing =
        edges.stream().filter(edge -> edge.from().equals(node.name())).toList();
    Icon icon = ICONS.get(node.type());
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("tooltip", capitalize(node.type()));
    attributes.put("fillcolor", palette.nodeFill());
    if (outgoing.stream().anyMatch(edge -> edge.route() != null)) {
      boolean hasDefault =
          outgoing.stream()
              .anyMatch(
                  edge ->
                      !edge.isToolEdge()
                          && (edge.route() == null || edge.route() == Route.Default.INSTANCE));
      DotLabel label;
      if (hasDefault) {
        label = nodeLabel(node.name(), icon);
      } else if (icon != null) {
        label =
            htmlLabel(
                iconHtml(icon)
                    + " "
                    + escapeHtml(node.name())
                    + "<br/><br/><FONT POINT-SIZE=\"10\">"
                    + NO_DEFAULT_WARNING
                    + "</FONT>");
      } else {
        label =
            htmlLabel(
                escapeHtml(node.name())
                    + "<br/><br/><font point-size='10'>"
                    + NO_DEFAULT_WARNING
                    + "</font>");
      }
      attributes.put("shape", "diamond");
      attributes.put("style", "filled");
      attributes.put("height", "1.2");
      attributes.put("width", "0.8");
      attributes.put("margin", "0.0,0.0");
      dot.node(node.name(), label, attributes);
    } else if (node.type().equals(JOIN_NODE_TYPE)) {
      attributes.put("shape", "oval");
      attributes.put("style", "filled");
      attributes.put("margin", "0.05,0.05");
      dot.node(node.name(), nodeLabel(node.name(), icon), attributes);
    } else if (node.type().equals(TOOL_NODE_TYPE)) {
      attributes.put("style", "rounded,filled,dashed");
      dot.node(node.name(), nodeLabel(node.name(), icon), attributes);
    } else {
      attributes.put("style", "rounded,filled");
      dot.node(node.name(), nodeLabel(node.name(), icon), attributes);
    }
  }

  private static void drawTerminus(
      DotWriter dot, String name, String label, String fill, String line, Palette palette) {
    dot.node(
        name,
        plainLabel(label),
        Map.of(
            "shape", "oval",
            "style", "filled",
            "fillcolor", fill,
            "color", line,
            "fontcolor", palette.nodeFont(),
            "fontname", "Helvetica-Bold",
            "width", "0.9",
            "fixedsize", "true"));
  }

  private static DotLabel nodeLabel(String name, @Nullable Icon icon) {
    return icon == null ? plainLabel(name) : htmlLabel(iconHtml(icon) + " " + escapeHtml(name));
  }

  private static String iconHtml(Icon icon) {
    return "<FONT COLOR=\"" + icon.color() + "\" POINT-SIZE=\"14\">" + icon.glyph() + "</FONT>";
  }

  /** Formats a route, or a list of routes, as an edge label the way Python prints them. */
  private static String routeLabel(Object route) {
    if (route instanceof List<?> routes) {
      return routes.stream()
          .map(
              item ->
                  item instanceof Route.Tag tag ? quoteRouteItem(tag.value()) : routeLabel(item))
          .collect(joining(", ", "[", "]"));
    }
    if (route instanceof Route.Tag tag) {
      return tag.value();
    }
    if (route instanceof Route.Num num) {
      return Long.toString(num.value());
    }
    if (route instanceof Route.Flag flag) {
      return flag.value() ? "True" : "False";
    }
    return Route.DEFAULT_ROUTE_SENTINEL;
  }

  /** Quotes and escapes a string element inside a multi-route edge label, as Python's repr does. */
  private static String quoteRouteItem(String text) {
    String quote = text.contains("'") && !text.contains("\"") ? "\"" : "'";
    String escaped =
        text.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(quote, "\\" + quote);
    return quote + escaped + quote;
  }

  private static String escapeHtml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }

  private static String capitalize(String text) {
    return text.isEmpty() ? text : Character.toTitleCase(text.charAt(0)) + text.substring(1);
  }

  private static DotLabel plainLabel(String text) {
    return new DotLabel(quoteDot(text));
  }

  private static DotLabel htmlLabel(String markup) {
    return new DotLabel("<" + markup + ">");
  }

  /**
   * Formats {@code value} as a DOT identifier, quoting it and escaping unescaped double quotes
   * unless it is a plain identifier or number.
   */
  private static String quoteDot(String value) {
    if (DOT_ID.matcher(value).matches() && !DOT_KEYWORDS.contains(value.toLowerCase(Locale.ROOT))) {
      return value;
    }
    Matcher matcher = OPTIONALLY_ESCAPED_QUOTE.matcher(value);
    return "\""
        + matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "\\\""))
        + "\"";
  }

  /** Builds Graphviz DOT source with {@code label} placed before other sorted attributes. */
  private static final class DotWriter {
    private final StringBuilder out = new StringBuilder("// Workflow Visualization\ndigraph {\n");

    void statement(String kind, Map<String, String> attributes) {
      line(kind + " " + attributeList(null, attributes));
    }

    void node(String name, DotLabel label, Map<String, String> attributes) {
      line(quoteDot(name) + " " + attributeList(label, attributes));
    }

    void edge(String from, String to, @Nullable DotLabel label, Map<String, String> attributes) {
      String list =
          label == null && attributes.isEmpty() ? "" : " " + attributeList(label, attributes);
      line(quoteDot(from) + " -> " + quoteDot(to) + list);
    }

    String build() {
      return out.append("}\n").toString();
    }

    private void line(String text) {
      out.append('\t').append(text).append('\n');
    }

    private static String attributeList(@Nullable DotLabel label, Map<String, String> attributes) {
      List<String> items = new ArrayList<>();
      if (label != null) {
        items.add("label=" + label.dot());
      }
      attributes.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              attribute -> items.add(attribute.getKey() + "=" + quoteDot(attribute.getValue())));
      return "[" + String.join(" ", items) + "]";
    }
  }

  private GraphPlotter() {}
}
