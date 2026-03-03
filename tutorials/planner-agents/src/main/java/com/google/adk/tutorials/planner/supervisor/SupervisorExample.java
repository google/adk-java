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

package com.google.adk.tutorials.planner.supervisor;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.PlannerAgent;
import com.google.adk.models.Gemini;
import com.google.adk.planner.SupervisorPlanner;
import com.google.adk.web.AdkWebServer;

/**
 * Supervisor Planner Example.
 *
 * <p>Demonstrates LLM-driven agent coordination where the supervisor LLM dynamically decides which
 * sub-agent to run next based on the current state and conversation history.
 *
 * <p>The supervisor LLM evaluates available agents and decides the sequence: it might run research
 * first, then writing, then review — or might skip steps if the task is simple.
 */
public class SupervisorExample {

  public static final BaseAgent ROOT_AGENT = createAgent();

  private static BaseAgent createAgent() {
    LlmAgent researchAgent =
        LlmAgent.builder()
            .name("researchAgent")
            .description("Researches a topic and provides comprehensive findings")
            .model("gemini-2.0-flash")
            .instruction("Research the user's topic thoroughly. Provide detailed findings.")
            .build();

    LlmAgent writerAgent =
        LlmAgent.builder()
            .name("writerAgent")
            .description("Writes clear, well-structured content based on research")
            .model("gemini-2.0-flash")
            .instruction(
                "Write clear, well-structured content based on the research findings. Create"
                    + " engaging prose.")
            .build();

    LlmAgent reviewerAgent =
        LlmAgent.builder()
            .name("reviewerAgent")
            .description("Reviews content for accuracy, clarity, and completeness")
            .model("gemini-2.0-flash")
            .instruction(
                "Review the written content for accuracy, clarity, and completeness. Suggest"
                    + " improvements if needed.")
            .build();

    return PlannerAgent.builder()
        .name("supervisorAgent")
        .description("LLM-supervised task coordination")
        .subAgents(researchAgent, writerAgent, reviewerAgent)
        .planner(
            new SupervisorPlanner(
                Gemini.builder().modelName("gemini-2.0-flash").build(),
                "You are a project manager coordinating research, writing, and review agents."
                    + " Decide which agent to run next based on what has been accomplished so far."
                    + " Run researchAgent first, then writerAgent, then reviewerAgent. After all"
                    + " three have run, respond with DONE."))
        .maxIterations(10)
        .build();
  }

  public static void main(String[] args) {
    AdkWebServer.start(ROOT_AGENT);
  }
}
