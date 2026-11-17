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
import com.google.adk.workflow.RunNodeConfig;
import com.google.adk.workflow.Workflow;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

/**
 * A port of adk-python's {@code use_as_output} workflow sample. A node runs an agent with {@code
 * runNode} and {@code useAsOutput}, so the agent's summary becomes the node's own output and flows
 * on to the next node.
 */
public final class UseAsOutputWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(String model) {
    LlmAgent summarizer = summarizerAgent(model);
    Node orchestrate =
        FunctionNodes.builder(Content.class, String.class)
            .name("orchestrate")
            // Calls runNode, so it reruns on resume to collect a child's output after a pause.
            .rerunOnResume(true)
            .body((context, message) -> orchestrate(context, message, summarizer))
            .build();
    Node finalize =
        FunctionNodes.of(
            "finalize", String.class, String.class, UseAsOutputWorkflow::finalizeSummary);
    return Workflow.builder()
        .name("use_as_output")
        .edges(g -> g.fromStart().chain(orchestrate, finalize))
        .build();
  }

  /**
   * Runs the workflow on one sentence and prints what each node produced. It calls Gemini, so it
   * needs the credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("use_as_output").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    String text =
        "The quick brown fox jumped over the lazy dog near the riverbank on a warm summer"
            + " afternoon";
    runner
        .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(text)))
        .blockingForEach(event -> {
          String path = event.nodeInfo().map(NodeInfo::path).orElse(null);
          String reply = event.stringifyContent();
          if (!reply.isBlank()) {
            System.out.println("[" + path + "] " + reply);
          } else {
            event.output().ifPresent(output -> System.out.println("[" + path + "] " + output));
          }
        });
  }

  /** Runs {@code summarizer} on the user's text and makes the summary this node's output. */
  private static String orchestrate(Context context, Content message, Node summarizer) {
    RunNodeConfig config = RunNodeConfig.builder().useAsOutput(true).build();
    // The summary is already this node's output, so returning it emits no second output.
    return (String) context.runNode(summarizer, message.text(), config).blockingGet();
  }

  /** Receives the summary that {@code useAsOutput} made {@code orchestrate}'s output. */
  private static String finalizeSummary(Context context, String summary) {
    return "final: " + summary;
  }

  private static LlmAgent summarizerAgent(String model) {
    return LlmAgent.builder()
        .name("summarizer")
        .model(model)
        .instruction("Summarize the following text in one sentence.")
        // Takes the node input as its user turn and outputs its answer.
        .mode(AgentMode.SINGLE_TURN)
        .build();
  }

  private UseAsOutputWorkflow() {}
}
