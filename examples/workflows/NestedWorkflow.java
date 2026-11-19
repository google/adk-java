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

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.agents.AgentMode;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.JoinNode;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Workflow;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A port of adk-python's {@code nested_workflow} sample: for the year the user names, a nested
 * workflow finds a famous person born that year and writes their bio while an agent describes an
 * event from that year, and a {@link JoinNode} waits for both branches before one message combines
 * them.
 */
public final class NestedWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final Pattern YEAR = Pattern.compile("\\b\\d{4}\\b");

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(String model) {
    // A Workflow is a Node, so a whole workflow can be one branch of another.
    Workflow findFamousPerson =
        Workflow.builder()
            .name("find_famous_person")
            .edges(g -> g.fromStart().chain(findNameAgent(model), generateBioAgent(model)))
            .build();
    Node processInput =
        FunctionNodes.of("process_input", Content.class, Void.class, NestedWorkflow::processInput);
    Node aggregateResults =
        FunctionNodes.of(
            "aggregate_results",
            new TypeReference<Map<String, String>>() {},
            Event.class,
            NestedWorkflow::aggregateResults);
    return Workflow.builder()
        .name("nested_workflow")
        .edges(g ->
            g.fromStart()
                .then(processInput)
                .then(findFamousPerson, findHistoricalEventAgent(model))
                .joinTo(JoinNode.create("join_for_aggregation"))
                .then(aggregateResults))
        .build();
  }

  /**
   * Runs the workflow on one year and prints each reply. It calls Gemini, so it needs the
   * credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("nested_workflow").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText("1984")))
        .blockingForEach(event -> {
          String text = event.stringifyContent();
          if (!text.isBlank()) {
            System.out.println("[" + event.author() + "] " + text);
          }
        });
  }

  /** Stores the year from the user's message in state, and fails the run if there is none. */
  private static Void processInput(Context context, Content message) {
    Matcher year = YEAR.matcher(message.text());
    if (!year.find()) {
      context.emit(message(context, "Please provide a valid 4-digit year (e.g., 1955)."));
      throw new IllegalArgumentException("Invalid year format.");
    }
    context.state().put("year", year.group());
    return null;
  }

  /** Combines both branches' answers, which the join passes on keyed by branch name. */
  private static Event aggregateResults(Context context, Map<String, String> answers) {
    return message(
        context,
        "# Year: "
            + context.state().get("year")
            + "\n\n## Famous Person Bio:\n\n"
            + answers.get("find_famous_person")
            + "\n\n## Historical Event:\n\n"
            + answers.get("find_historical_event"));
  }

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private static LlmAgent findNameAgent(String model) {
    return LlmAgent.builder()
        .name("find_name")
        .model(model)
        .instruction(
            """
            Find the name of one famous person who was born in this year: {year}.
            Return ONLY their name, nothing else.
            """)
        // Outputs the name, which generate_bio takes as its user turn.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private static LlmAgent generateBioAgent(String model) {
    return LlmAgent.builder()
        .name("generate_bio")
        .model(model)
        .instruction("Write a short, engaging 3-sentence biography for the specified person.")
        // Its answer, as the last node's output, is the nested workflow's output.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private static LlmAgent findHistoricalEventAgent(String model) {
    return LlmAgent.builder()
        .name("find_historical_event")
        .model(model)
        .instruction(
            """
            Describe one highly significant historical event that occurred in this year: {year}.
            Keep the description to 2 sentences.
            """)
        // Outputs its description, which the join collects.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private NestedWorkflow() {}
}
