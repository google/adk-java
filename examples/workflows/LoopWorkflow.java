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
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

/**
 * A port of adk-python's {@code loop} workflow sample: an agent writes a headline about the user's
 * topic, a second agent grades whether it is about technology, and the workflow loops back with the
 * grader's feedback until it is.
 */
public final class LoopWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(String model) {
    LlmAgent generateHeadline = generateHeadlineAgent(model);
    // START hands the first node the user's message as Content.
    Node processInput =
        FunctionNodes.of("process_input", Content.class, Void.class, LoopWorkflow::processInput);
    Node routeHeadline =
        FunctionNodes.of("route_headline", Feedback.class, Void.class, LoopWorkflow::routeHeadline);
    return Workflow.builder()
        .name("loop")
        .edges(g ->
            g.fromStart()
                .chain(processInput, generateHeadline, evaluateHeadlineAgent(model), routeHeadline)
                .route(r -> r.on("unrelated").then(generateHeadline)))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints each headline and grade. It calls Gemini, so it needs
   * the credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("loop").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText("flower")))
        .blockingForEach(event -> {
          String text = event.stringifyContent();
          if (!text.isBlank()) {
            System.out.println("[" + event.author() + "] " + text);
          }
        });
  }

  /** Stores the user's topic in state, where the headline agent's instruction reads it. */
  private static Void processInput(Context context, Content message) {
    context.state().put("topic", message.text());
    return null;
  }

  /** Routes on the grade; no edge matches {@code tech-related}, so that grade ends the loop. */
  private static Void routeHeadline(Context context, Feedback feedback) {
    context.routes(ImmutableList.of(new Route.Tag(feedback.grade())));
    return null;
  }

  /** The grader's structured answer, with the fields of adk-python's {@code Feedback} model. */
  private record Feedback(String grade, String feedback) {}

  /** The schema of {@link Feedback}, which the grader's answer must match. */
  private static final Schema FEEDBACK_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "grade",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .enum_("tech-related", "unrelated")
                      .description(
                          "Decide if the headline is related to technology or software"
                              + " engineering.")
                      .build(),
                  "feedback",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description(
                          "If the headline is unrelated to technology, provide feedback on how to"
                              + " make it more tech-focused.")
                      .build()))
          .required("grade", "feedback")
          .build();

  private static LlmAgent generateHeadlineAgent(String model) {
    return LlmAgent.builder()
        .name("generate_headline")
        .model(model)
        .instruction(
            """
            Write a headline about the topic "{topic}".
            If feedback is provided, take it into account.
            The feedback: {feedback?}
            """)
        // Outputs its headline, which the grader takes as its user turn.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private static LlmAgent evaluateHeadlineAgent(String model) {
    return LlmAgent.builder()
        .name("evaluate_headline")
        .model(model)
        .instruction("Grade whether the headline is related to technology or software engineering.")
        .outputSchema(FEEDBACK_SCHEMA)
        // Stores the grade and feedback for the headline agent's next attempt.
        .outputKey("feedback")
        // Takes the headline as its user turn and outputs the parsed grade.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private LoopWorkflow() {}
}
