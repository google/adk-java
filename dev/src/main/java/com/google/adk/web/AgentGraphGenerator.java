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

package com.google.adk.web;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.retrieval.BaseRetrievalTool;
import guru.nidi.graphviz.attribute.Arrow;
import guru.nidi.graphviz.attribute.Color;
import guru.nidi.graphviz.attribute.Label;
import guru.nidi.graphviz.attribute.Rank;
import guru.nidi.graphviz.attribute.Shape;
import guru.nidi.graphviz.attribute.Style;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.Factory;
import guru.nidi.graphviz.model.Link;
import guru.nidi.graphviz.model.MutableGraph;
import guru.nidi.graphviz.model.MutableNode;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class to generate Graphviz DOT representations of Agent structures. */
public class AgentGraphGenerator {

  private static final Logger log = LoggerFactory.getLogger(AgentGraphGenerator.class);

  private static final Color DARK_GREEN = Color.rgb("#0F5223");
  private static final Color LIGHT_GREEN = Color.rgb("#69CB87");
  private static final Color LIGHT_GRAY = Color.rgb("#CCCCCC");
  private static final Color BG_COLOR = Color.rgb("#333537");

  /**
   * Generates the DOT source string for the agent graph.
   *
   * @param rootAgent The root agent of the structure.
   * @param highlightPairs A list where each inner list contains two strings (fromNode, toNode)
   *     representing an edge to highlight. Order matters for direction.
   * @return The DOT source string, or null if graph generation fails.
   */
  public static Optional<String> getAgentGraphDotSource(
      BaseAgent rootAgent, List<List<String>> highlightPairs) {
    log.debug(
        "Building agent graph with root: {}, highlights: {}", rootAgent.name(), highlightPairs);
    try {
      MutableGraph graph =
          Factory.mutGraph("AgentGraph")
              .setDirected(true)
              .graphAttrs()
              .add(Rank.dir(Rank.RankDir.LEFT_TO_RIGHT), BG_COLOR.background());

      Set<String> visitedNodes = new HashSet<>();
      buildGraphRecursive(graph, rootAgent, highlightPairs, visitedNodes);

      String dotSource = Graphviz.fromGraph(graph).render(Format.DOT).toString();
      log.debug("Generated DOT source successfully.");
      return Optional.of(dotSource);
    } catch (Exception e) {
      log.error("Error generating agent graph DOT source", e);
      return Optional.empty();
    }
  }

  /** Recursively builds the graph structure. */
  private static void buildGraphRecursive(
      MutableGraph graph,
      BaseAgent agent,
      List<List<String>> highlightPairs,
      Set<String> visitedNodes) {
    if (agent == null || visitedNodes.contains(getNodeName(agent))) {
      return;
    }

    drawNode(graph, agent, highlightPairs, visitedNodes);

    if (agent.subAgents() != null) {
      for (BaseAgent subAgent : agent.subAgents()) {
        if (subAgent != null) {
          drawEdge(graph, getNodeName(agent), getNodeName(subAgent), highlightPairs);
          buildGraphRecursive(graph, subAgent, highlightPairs, visitedNodes);
        }
      }
    }

    if (agent instanceof LlmAgent) {
      LlmAgent llmAgent = (LlmAgent) agent;
      List<BaseTool> tools = llmAgent.canonicalTools().blockingFirst();
      if (tools != null) {
        for (BaseTool tool : tools) {
          if (tool != null) {
            drawNode(graph, tool, highlightPairs, visitedNodes);
            drawEdge(graph, getNodeName(agent), getNodeName(tool), highlightPairs);
          }
        }
      }
    }
  }

  /** Draws a node for an agent or tool, applying highlighting if applicable. */
  private static void drawNode(
      MutableGraph graph,
      Object toolOrAgent,
      List<List<String>> highlightPairs,
      Set<String> visitedNodes) {
    String name = getNodeName(toolOrAgent);
    if (name == null || name.isEmpty() || visitedNodes.contains(name)) {
      return;
    }

    Shape shape = getNodeShape(toolOrAgent);
    String caption = getNodeCaption(toolOrAgent);
    boolean isHighlighted = isNodeHighlighted(name, highlightPairs);

    MutableNode node =
        Factory.mutNode(name).add(Label.of(caption)).add(shape).add(LIGHT_GRAY.font());
    if (isHighlighted) {
      node.add(Style.FILLED);
      node.add(DARK_GREEN);
    } else {
      node.add(Style.ROUNDED);
      node.add(LIGHT_GRAY);
    }
    graph.add(node);
    visitedNodes.add(name);
    log.trace(
        "Added node: name={}, caption={}, shape={}, highlighted={}",
        name,
        caption,
        shape,
        isHighlighted);
  }

  /** Draws an edge between two nodes, applying highlighting if applicable. */
  private static void drawEdge(
      MutableGraph graph, String fromName, String toName, List<List<String>> highlightPairs) {
    if (fromName == null || fromName.isEmpty() || toName == null || toName.isEmpty()) {
      log.warn(
          "Skipping edge draw due to null or empty name: from='{}', to='{}'", fromName, toName);
      return;
    }

    Optional<Boolean> highlightForward = isEdgeHighlighted(fromName, toName, highlightPairs);
    Link link = Factory.to(Factory.mutNode(toName));

    if (highlightForward.isPresent()) {
      link = link.with(LIGHT_GREEN);
      if (!highlightForward.get()) { // If true, means b->a was highlighted, draw reverse arrow
        link = link.with(Arrow.NORMAL.dir(Arrow.DirType.BACK));
      } else {
        link = link.with(Arrow.NORMAL);
      }
    } else {
      link = link.with(LIGHT_GRAY, Arrow.NONE);
    }

    graph.add(Factory.mutNode(fromName).addLink(link));
    log.trace(
        "Added edge: from={}, to={}, highlighted={}",
        fromName,
        toName,
        highlightForward.isPresent());
  }

  private static String getNodeName(Object toolOrAgent) {
    if (toolOrAgent instanceof BaseAgent) {
      return ((BaseAgent) toolOrAgent).name();
    } else if (toolOrAgent instanceof BaseTool) {
      return ((BaseTool) toolOrAgent).name();
    } else {
      log.warn("Unsupported type for getNodeName: {}", toolOrAgent.getClass().getName());
      return "unknown_" + toolOrAgent.hashCode();
    }
  }

  private static String getNodeCaption(Object toolOrAgent) {
    String name = getNodeName(toolOrAgent); // Get name first
    if (toolOrAgent instanceof BaseAgent) {
      return "🤖 " + name;
    } else if (toolOrAgent instanceof BaseRetrievalTool) {
      return "🔎 " + name;
    } else if (toolOrAgent instanceof FunctionTool) {
      return "🔧 " + name;
    } else if (toolOrAgent instanceof AgentTool) {
      return "🤖 " + name;
    } else if (toolOrAgent instanceof BaseTool) {
      return "🔧 " + name;
    } else {
      log.warn("Unsupported type for getNodeCaption: {}", toolOrAgent.getClass().getName());
      return "❓ " + name;
    }
  }

  private static Shape getNodeShape(Object toolOrAgent) {
    if (toolOrAgent instanceof BaseAgent) {
      return Shape.ELLIPSE;
    } else if (toolOrAgent instanceof BaseRetrievalTool) {
      return Shape.CYLINDER;
    } else if (toolOrAgent instanceof FunctionTool) {
      return Shape.BOX;
    } else if (toolOrAgent instanceof BaseTool) {
      return Shape.BOX;
    } else {
      log.warn("Unsupported type for getNodeShape: {}", toolOrAgent.getClass().getName());
      return Shape.EGG;
    }
  }

  private static boolean isNodeHighlighted(String nodeName, List<List<String>> highlightPairs) {
    if (highlightPairs == null || nodeName == null) {
      return false;
    }
    for (List<String> pair : highlightPairs) {
      if (pair != null && pair.contains(nodeName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if an edge should be highlighted. Returns Optional<Boolean>: empty=no, true=forward,
   * false=backward
   */
  private static Optional<Boolean> isEdgeHighlighted(
      String fromName, String toName, List<List<String>> highlightPairs) {
    if (highlightPairs == null || fromName == null || toName == null) {
      return Optional.empty();
    }
    for (List<String> pair : highlightPairs) {
      if (pair != null && pair.size() == 2) {
        String pairFrom = pair.get(0);
        String pairTo = pair.get(1);
        if (fromName.equals(pairFrom) && toName.equals(pairTo)) {
          return Optional.of(true);
        }
        if (fromName.equals(pairTo) && toName.equals(pairFrom)) {
          return Optional.of(false);
        }
      }
    }
    return Optional.empty();
  }
}
