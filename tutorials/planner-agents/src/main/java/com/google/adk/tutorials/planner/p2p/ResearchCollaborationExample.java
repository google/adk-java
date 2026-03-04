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

package com.google.adk.tutorials.planner.p2p;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.PlannerAgent;
import com.google.adk.planner.goap.AgentMetadata;
import com.google.adk.planner.p2p.P2PPlanner;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;

/**
 * P2P Research Collaboration Example.
 *
 * <p>Demonstrates Peer-to-Peer planning where agents activate dynamically as their input
 * dependencies become available. Supports iterative refinement — when a critic updates their
 * critique, the hypothesis agent re-activates.
 *
 * <p>Agent activation flow:
 *
 * <pre>
 *   Wave 1: literatureAgent starts (has: topic)
 *   Wave 2: hypothesisAgent starts (has: topic + researchFindings)
 *   Wave 3: criticAgent starts (has: topic + hypothesis)
 *   Wave 4: scorerAgent starts (has: topic + hypothesis + critique)
 *     → If score &lt; 0.85: hypothesisAgent re-activates (input "critique" changed)
 *     → Loop continues until score >= 0.85 or maxInvocations
 * </pre>
 *
 * <p>Usage: Set GOOGLE_API_KEY and run with initial state: {@code {"topic": "quantum entanglement
 * effects on information transfer"}}
 */
public class ResearchCollaborationExample {

  public static Map<String, String> saveFindings(
      @Schema(name = "researchFindings", description = "Research findings from literature review")
          String researchFindings) {
    return Map.of("researchFindings", researchFindings);
  }

  public static Map<String, String> saveHypothesis(
      @Schema(name = "hypothesis", description = "The formulated research hypothesis")
          String hypothesis) {
    return Map.of("hypothesis", hypothesis);
  }

  public static Map<String, String> saveCritique(
      @Schema(name = "critique", description = "Critical evaluation of the hypothesis")
          String critique) {
    return Map.of("critique", critique);
  }

  public static Map<String, String> saveScore(
      @Schema(name = "score", description = "Hypothesis quality score 0.0-1.0") String score) {
    return Map.of("score", score);
  }

  public static final BaseAgent ROOT_AGENT = createAgent();

  private static BaseAgent createAgent() {
    LlmAgent literatureAgent =
        LlmAgent.builder()
            .name("literatureAgent")
            .description("Search scientific literature on a topic")
            .model("gemini-2.0-flash")
            .instruction(
                "Research the topic={topic}. Provide a comprehensive literature review. Save the"
                    + " findings using the saveFindings tool.")
            .tools(FunctionTool.create(ResearchCollaborationExample.class, "saveFindings"))
            .build();

    LlmAgent hypothesisAgent =
        LlmAgent.builder()
            .name("hypothesisAgent")
            .description("Formulate hypothesis based on research")
            .model("gemini-2.0-flash")
            .instruction(
                "Based on topic={topic} and findings={researchFindings}, formulate a testable"
                    + " research hypothesis. If a critique is available at {critique}, improve your"
                    + " hypothesis to address the critique. Save using the saveHypothesis tool.")
            .tools(FunctionTool.create(ResearchCollaborationExample.class, "saveHypothesis"))
            .build();

    LlmAgent criticAgent =
        LlmAgent.builder()
            .name("criticAgent")
            .description("Critically evaluate a hypothesis")
            .model("gemini-2.0-flash")
            .instruction(
                "Evaluate the hypothesis={hypothesis} for topic={topic}. Identify strengths,"
                    + " weaknesses, and suggestions for improvement. Save using the saveCritique"
                    + " tool.")
            .tools(FunctionTool.create(ResearchCollaborationExample.class, "saveCritique"))
            .build();

    LlmAgent scorerAgent =
        LlmAgent.builder()
            .name("scorerAgent")
            .description("Score hypothesis quality 0.0-1.0")
            .model("gemini-2.0-flash")
            .instruction(
                "Score the hypothesis={hypothesis} considering the critique={critique} for"
                    + " topic={topic}. Rate from 0.0 (poor) to 1.0 (excellent). Save a numeric"
                    + " score using the saveScore tool.")
            .tools(FunctionTool.create(ResearchCollaborationExample.class, "saveScore"))
            .build();

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("literatureAgent", ImmutableList.of("topic"), "researchFindings"),
            new AgentMetadata(
                "hypothesisAgent", ImmutableList.of("topic", "researchFindings"), "hypothesis"),
            new AgentMetadata("criticAgent", ImmutableList.of("topic", "hypothesis"), "critique"),
            new AgentMetadata(
                "scorerAgent", ImmutableList.of("topic", "hypothesis", "critique"), "score"));

    // Exit when score >= 0.85 or after 10 total invocations
    return PlannerAgent.builder()
        .name("researcher")
        .description("Collaborative research with iterative hypothesis refinement")
        .subAgents(literatureAgent, hypothesisAgent, criticAgent, scorerAgent)
        .planner(
            new P2PPlanner(
                metadata,
                10,
                (state, count) -> {
                  Object scoreVal = state.get("score");
                  return scoreVal instanceof Number && ((Number) scoreVal).doubleValue() >= 0.85;
                }))
        .maxIterations(20)
        .build();
  }

  public static void main(String[] args) {
    AdkWebServer.start(ROOT_AGENT);
  }
}
