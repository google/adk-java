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

package com.google.adk.tutorials.planner.goap;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.PlannerAgent;
import com.google.adk.planner.goap.AgentMetadata;
import com.google.adk.planner.goap.GoalOrientedPlanner;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;

/**
 * GOAP Story Writer Example with embedded feedback loop.
 *
 * <p>Demonstrates a GOAP-planned pipeline with an inner loop: story generation → style editing +
 * scoring (loops until quality threshold) → audience adaptation.
 *
 * <p>Agent dependency graph:
 *
 * <pre>
 *   storyGenerator   (topic → story)
 *   styleReviewLoop  (story, style → styledStory)    [inner LoopAgent]
 *   audienceEditor   (styledStory, audience → finalStory)
 * </pre>
 *
 * <p>Usage: Set GOOGLE_API_KEY and run with initial state: {@code {"topic": "dragons and a wizard",
 * "style": "fantasy", "audience": "young adults"}}
 */
public class StoryWriterExample {

  public static Map<String, String> saveStory(
      @Schema(name = "story", description = "The generated story") String story) {
    return Map.of("story", story);
  }

  public static Map<String, String> saveStyledStory(
      @Schema(name = "styledStory", description = "The style-edited story") String styledStory) {
    return Map.of("styledStory", styledStory);
  }

  public static Map<String, String> saveScore(
      @Schema(name = "score", description = "Style quality score 0.0-1.0") String score) {
    return Map.of("score", score);
  }

  public static Map<String, String> saveFinalStory(
      @Schema(name = "finalStory", description = "The audience-adapted final story")
          String finalStory) {
    return Map.of("finalStory", finalStory);
  }

  public static final BaseAgent ROOT_AGENT = createAgent();

  private static BaseAgent createAgent() {
    LlmAgent storyGenerator =
        LlmAgent.builder()
            .name("storyGenerator")
            .description("Generate initial story from topic")
            .model("gemini-2.0-flash")
            .instruction("Write a short story about {topic}. Save it using the saveStory tool.")
            .tools(FunctionTool.create(StoryWriterExample.class, "saveStory"))
            .build();

    LlmAgent styleEditor =
        LlmAgent.builder()
            .name("styleEditor")
            .description("Edit story for target style")
            .model("gemini-2.0-flash")
            .instruction(
                "Edit the story at {story} to match the {style} style. Save the result using the"
                    + " saveStyledStory tool.")
            .tools(FunctionTool.create(StoryWriterExample.class, "saveStyledStory"))
            .build();

    LlmAgent styleScorer =
        LlmAgent.builder()
            .name("styleScorer")
            .description("Score story style quality 0.0-1.0")
            .model("gemini-2.0-flash")
            .instruction(
                "Score how well the story at {styledStory} matches the target style={style}."
                    + " Provide a score from 0.0 to 1.0. Save the score using the saveScore tool.")
            .tools(FunctionTool.create(StoryWriterExample.class, "saveScore"))
            .build();

    // Style review loop: alternates between editing and scoring until quality >= 0.8
    LoopAgent styleReviewLoop =
        LoopAgent.builder()
            .name("styleReviewLoop")
            .description("Iteratively edit and score story style until quality threshold is met")
            .subAgents(styleEditor, styleScorer)
            .maxIterations(5)
            .afterAgentCallback(
                ctx -> {
                  Object scoreObj = ctx.state().get("score");
                  if (scoreObj != null) {
                    double score = Double.parseDouble(scoreObj.toString());
                    if (score >= 0.8) {
                      ctx.eventActions().setEscalate(true);
                    }
                  }
                  return Maybe.empty();
                })
            .build();

    LlmAgent audienceEditor =
        LlmAgent.builder()
            .name("audienceEditor")
            .description("Adapt story for target audience")
            .model("gemini-2.0-flash")
            .instruction(
                "Edit the story at {styledStory} to be appropriate for the target"
                    + " audience={audience}. Save the result using the saveFinalStory tool.")
            .tools(FunctionTool.create(StoryWriterExample.class, "saveFinalStory"))
            .build();

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("storyGenerator", ImmutableList.of("topic"), "story"),
            new AgentMetadata("styleReviewLoop", ImmutableList.of("story", "style"), "styledStory"),
            new AgentMetadata(
                "audienceEditor", ImmutableList.of("styledStory", "audience"), "finalStory"));

    return PlannerAgent.builder()
        .name("storyWriter")
        .description("Writes styled stories for target audiences using GOAP planning")
        .subAgents(storyGenerator, styleReviewLoop, audienceEditor)
        .planner(new GoalOrientedPlanner("finalStory", metadata))
        .build();
  }

  public static void main(String[] args) {
    AdkWebServer.start(ROOT_AGENT);
  }
}
