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
import com.google.adk.workflow.Node;
import com.google.adk.workflow.RunNodeConfig;
import com.google.adk.workflow.Workflow;
import com.google.common.base.Splitter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;

/**
 * A port of adk-python's {@code dynamic_fan_out_fan_in} workflow sample: one node splits the user's
 * comma-separated topics, runs a headline agent on each topic concurrently with {@code runNode},
 * and collects the headlines into one table. The number of runs comes from the input, not the
 * graph.
 */
public final class DynamicFanOutFanInWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(String model) {
    return Workflow.builder()
        .name("dynamic_fan_out_fan_in")
        .edges(g -> g.fromStart().then(orchestrator(generatorAgent(model))))
        .build();
  }

  /**
   * Runs the workflow on three topics and prints the table. It calls Gemini, so it needs the
   * credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("dynamic_fan_out_fan_in").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("AI, Cloud Computing, Quantum Computing"));
    runner
        .runAsync(USER_ID, sessionId, message)
        .blockingForEach(event -> {
          String text = event.stringifyContent();
          if (!text.isBlank()) {
            System.out.println("[" + event.author() + "] " + text);
          }
        });
  }

  /** Returns the node that runs {@code generator} on each topic. */
  private static Node orchestrator(Node generator) {
    return FunctionNodes.builder(Content.class, Event.class)
        .name("orchestrator")
        // Calls runNode, so it reruns on resume to collect a child's output after a pause.
        .rerunOnResume(true)
        .body((context, message) -> orchestrate(context, message, generator))
        .build();
  }

  /** Runs {@code generator} once per topic concurrently and tabulates the headlines. */
  private static Event orchestrate(Context context, Content message, Node generator) {
    List<String> topics =
        Splitter.on(',').trimResults().omitEmptyStrings().splitToList(message.text());
    context.emit(message(context, "Processing " + topics.size() + " topics in parallel."));
    // Fans out: each run gets its own sub-branch, so it sees only its own topic.
    Flowable<String> headlines =
        Flowable.range(0, topics.size())
            .concatMapEager(i -> {
              // Concurrent runs need explicit run IDs, which must contain a non-digit.
              RunNodeConfig config =
                  RunNodeConfig.builder().runId("topic_" + (i + 1)).useSubBranch(true).build();
              return context
                  .runNode(generator, topics.get(i), config)
                  .map(String::valueOf)
                  // A run with no output leaves its cell empty.
                  .defaultIfEmpty("")
                  // Runs each call on its own thread, since a model call blocks its thread.
                  .subscribeOn(context.invocationContext().scheduler())
                  .toFlowable();
            });
    // Fans in: one table row per topic, in topic order.
    List<String> rows = headlines.toList().blockingGet();
    return message(context, formatTable(topics, rows));
  }

  private static String formatTable(List<String> topics, List<String> headlines) {
    StringBuilder table =
        new StringBuilder("### Aggregated Headlines\n\n| Topic | Headline |\n| :--- | :--- |\n");
    for (int i = 0; i < topics.size(); i++) {
      table
          .append("| ")
          .append(topics.get(i))
          .append(" | ")
          .append(headlines.get(i))
          .append(" |\n");
    }
    return table.toString();
  }

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private static LlmAgent generatorAgent(String model) {
    return LlmAgent.builder()
        .name("generator")
        .model(model)
        .instruction(
            "Write a catchy one-line headline about the topic provided in the user message.")
        // Takes its topic as its user turn and outputs its headline.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private DynamicFanOutFanInWorkflow() {}
}
