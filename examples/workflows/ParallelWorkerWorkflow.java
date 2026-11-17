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
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.ParallelWorkers;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A port of adk-python's {@code parallel_worker} workflow sample: an agent lists three topics
 * related to the user's topic, a function node uppercases each one, an agent explains how each
 * relates to the original topic, and a last node joins the explanations. The two middle nodes are
 * parallel workers, which run once per item of their list input, concurrently.
 */
public final class ParallelWorkerWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(String model) {
    // START hands the first node the user's message as Content.
    Node processInput =
        FunctionNodes.of(
            "process_input", Content.class, Void.class, ParallelWorkerWorkflow::processInput);
    Node makeUpperCase =
        ParallelWorkers.parallelNode(
            FunctionNodes.of(
                "make_upper_case", String.class, String.class, ParallelWorkerWorkflow::upperCase));
    Node aggregate =
        FunctionNodes.of(
            "aggregate",
            new TypeReference<List<TopicExplanation>>() {},
            Event.class,
            ParallelWorkerWorkflow::aggregate);
    return Workflow.builder()
        .name("parallel_worker")
        .edges(g ->
            g.fromStart()
                .chain(
                    processInput,
                    findRelatedTopicsAgent(model),
                    makeUpperCase,
                    // An agent can be a parallel worker too; its model calls run concurrently.
                    ParallelWorkers.parallelNode(explainTopicAgent(model)),
                    aggregate))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints what each node produced. It calls Gemini, so it needs
   * the credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("parallel_worker").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText("machine learning")))
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

  /** Stores the user's topic in state, where both agents' instructions read it. */
  private static Void processInput(Context context, Content message) {
    context.state().put("topic", message.text());
    return null;
  }

  private static String upperCase(Context context, String topic) {
    return topic.toUpperCase(Locale.ROOT);
  }

  /** Joins the explanations, which the parallel agent outputs as a list in input order. */
  private static Event aggregate(Context context, List<TopicExplanation> explanations) {
    String text =
        explanations.stream()
            .map(explanation -> explanation.topic() + ": " + explanation.explanation())
            .collect(Collectors.joining("\n\n---\n\n"));
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private static LlmAgent findRelatedTopicsAgent(String model) {
    return LlmAgent.builder()
        .name("find_related_topics")
        .model(model)
        .instruction("Given the specific topic \"{topic}\", generate a list of 3 related topics.")
        .outputSchema(
            Schema.builder()
                .type(Type.Known.ARRAY)
                .items(Schema.builder().type(Type.Known.STRING).build())
                .build())
        // Outputs the parsed list, which the next node fans out over.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private static LlmAgent explainTopicAgent(String model) {
    return LlmAgent.builder()
        .name("explain_topic")
        .model(model)
        .instruction("Explain how the following topic relates to the original topic: \"{topic}\".")
        .outputSchema(TOPIC_EXPLANATION_SCHEMA)
        // Takes each topic as its user turn and outputs the parsed explanation.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  /** An explanation from the agent, with the fields of adk-python's {@code TopicExplanation}. */
  private record TopicExplanation(String topic, String explanation) {}

  /** The schema of {@link TopicExplanation}, which each run of the agent must match. */
  private static final Schema TOPIC_EXPLANATION_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "topic",
                  Schema.builder().type(Type.Known.STRING).build(),
                  "explanation",
                  Schema.builder().type(Type.Known.STRING).build()))
          .required("topic", "explanation")
          .build();

  private ParallelWorkerWorkflow() {}
}
