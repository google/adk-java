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
package com.example.workflows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.Workflow;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

/**
 * A port of adk-python's {@code sequence} workflow sample: one agent names a random fruit, and the
 * next agent receives that answer as its input and tells a health benefit of the fruit.
 */
public final class SequenceWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(String model) {
    return Workflow.builder()
        .name("sequence")
        .edges(g -> g.fromStart().chain(generateFruitAgent(model), generateBenefitAgent(model)))
        .build();
  }

  /**
   * Runs the workflow once and prints each agent's answer. It calls Gemini, so it needs the
   * credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("sequence").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    // The workflow needs no input, so any message starts it.
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText("go")))
        .blockingForEach(event -> {
          String text = event.stringifyContent();
          if (!text.isBlank()) {
            System.out.println("[" + event.author() + "] " + text);
          }
        });
  }

  private static LlmAgent generateFruitAgent(String model) {
    return LlmAgent.builder()
        .name("generate_fruit_agent")
        .model(model)
        // Outputs the fruit, which the next agent takes as its user turn.
        .mode(AgentMode.SINGLE_TURN)
        .instruction("Return the name of a random fruit.\nReturn only the name, nothing else.")
        .build();
  }

  private static LlmAgent generateBenefitAgent(String model) {
    return LlmAgent.builder()
        .name("generate_benefit_agent")
        .model(model)
        // Takes the fruit as its user turn.
        .mode(AgentMode.SINGLE_TURN)
        .instruction("Tell me a health benefit about the specified fruit.")
        .build();
  }

  private SequenceWorkflow() {}
}
