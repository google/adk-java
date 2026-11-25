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
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

/**
 * A port of adk-python's {@code node_output} workflow sample: a function node's return value
 * becomes its output, a returned {@link Event} passes through as is, and an agent with an output
 * schema answers with structured data, which the next node receives decoded into a record.
 */
public final class NodeOutputWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(String model) {
    return Workflow.builder()
        .name("node_output")
        .edges(g ->
            g.fromStart()
                .chain(
                    FunctionNodes.of(
                        "generate_string_output",
                        String.class,
                        String.class,
                        NodeOutputWorkflow::generateStringOutput),
                    FunctionNodes.of(
                        "generate_event_output",
                        String.class,
                        Event.class,
                        NodeOutputWorkflow::generateEventOutput),
                    topicAgent(model),
                    FunctionNodes.of(
                        "consume_structured_output",
                        TopicDetails.class,
                        String.class,
                        NodeOutputWorkflow::consumeStructuredOutput)))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints what each node produced. It calls Gemini, so it needs
   * the credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("node_output").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText("cyberpunk future")))
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

  /** Returns a string, which becomes the node's output. */
  private static String generateStringOutput(Context context, String input) {
    return "Processed input: " + input;
  }

  /** Returns an {@link Event}, which passes through as is and could also carry routes or state. */
  private static Event generateEventOutput(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .output("Event wrapped output: " + text)
        .build();
  }

  /** Formats the agent's structured answer, decoded from the node input into a record. */
  private static String consumeStructuredOutput(Context context, TopicDetails topic) {
    return "Received structured output!\nTitle: "
        + topic.title()
        + "\nDescription: "
        + topic.description()
        + "\nCategory: "
        + topic.category();
  }

  private static LlmAgent topicAgent(String model) {
    return LlmAgent.builder()
        .name("generate_structured_output")
        .model(model)
        // Takes the previous node's output as its user turn and outputs the parsed topic.
        .mode(AgentMode.SINGLE_TURN)
        .instruction("Generate a creative topic based on the following input.")
        .outputSchema(TOPIC_SCHEMA)
        .build();
  }

  /** The agent's structured answer, with the fields of adk-python's {@code TopicDetails} model. */
  private record TopicDetails(String title, String description, String category) {}

  /** The schema of {@link TopicDetails}, which the agent's answer must match. */
  private static final Schema TOPIC_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "title",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The title of the generated topic.")
                      .build(),
                  "description",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("A short description of the topic.")
                      .build(),
                  "category",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The broad category of the topic.")
                      .build()))
          .required("title", "description", "category")
          .build();

  private NodeOutputWorkflow() {}
}
