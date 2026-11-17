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
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.Map;

/**
 * A port of adk-python's {@code dynamic_nodes} workflow sample: the {@code loop} sample rewritten
 * as one node that runs the headline agents with {@code runNode} in a loop, instead of routing
 * between them with graph edges.
 */
public final class DynamicNodesWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(String model) {
    LlmAgent generateHeadline = generateHeadlineAgent(model);
    LlmAgent evaluateHeadline = evaluateHeadlineAgent(model);
    Node orchestrate =
        FunctionNodes.builder(Content.class, String.class)
            .name("orchestrate")
            // Calls runNode, so it reruns on resume to collect a child's output after a pause.
            .rerunOnResume(true)
            .body((context, message) ->
                orchestrate(context, message, generateHeadline, evaluateHeadline))
            .build();
    return Workflow.builder()
        .name("dynamic_nodes")
        .edges(g -> g.fromStart().then(orchestrate))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints each headline, each grade, and the result. It calls
   * Gemini, so it needs the credentials the README describes; the first argument, if any, overrides
   * the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("dynamic_nodes").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText("flower")))
        .blockingForEach(event -> {
          String path = event.nodeInfo().map(NodeInfo::path).orElse(null);
          String text = event.stringifyContent();
          if (!text.isBlank()) {
            System.out.println("[" + path + "] " + text);
          } else {
            event.output().ifPresent(output -> System.out.println("[" + path + "] " + output));
          }
        });
  }

  /**
   * Runs {@code generateHeadline} and then {@code evaluateHeadline} on its headline until the grade
   * is {@code tech-related}, and outputs that headline. As in adk-python, nothing limits the number
   * of rounds.
   */
  private static String orchestrate(
      Context context, Content message, Node generateHeadline, Node evaluateHeadline) {
    // The agents' instructions read the topic from state.
    context.state().put("topic", message.text());
    String headline;
    Map<?, ?> feedback;
    do {
      // A node body may block, so it waits for each child's output in turn.
      headline = (String) context.runNode(generateHeadline).blockingGet();
      // The grader's object output schema makes its reply arrive as a map.
      feedback = (Map<?, ?>) context.runNode(evaluateHeadline, headline).blockingGet();
    } while (!"tech-related".equals(feedback.get("grade")));
    return headline;
  }

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
        // Outputs its answer, so runNode returns the headline.
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

  /** The grader's answer, with the fields of adk-python's {@code Feedback} model. */
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

  private DynamicNodesWorkflow() {}
}
