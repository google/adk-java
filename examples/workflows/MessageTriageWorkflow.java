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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Locale;

/**
 * A port of ADK Kotlin's message-triage workflow sample, the graph from the ADK docs: an agent
 * labels a message as a bug, a customer-support request, a logistics question, or several of these,
 * and a function node emits one route per label so each matching handler runs.
 */
public final class MessageTriageWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** The characters the model may wrap a label in. */
  private static final CharMatcher LABEL_TRIM =
      CharMatcher.whitespace().or(CharMatcher.anyOf("\"'."));

  /** Builds the workflow, with the classifier on {@code model}. */
  public static Workflow create(String model) {
    return Workflow.builder()
        .name("routing_workflow")
        .edges(g ->
            g.fromStart()
                .chain(
                    processMessageAgent(model),
                    FunctionNodes.of(
                        "message_categories",
                        String.class,
                        Void.class,
                        MessageTriageWorkflow::messageCategories))
                .route(r -> {
                  r.on("BUG").then(responder("response_1_bug", "Handling bug..."));
                  r.on("CUSTOMER_SUPPORT")
                      .then(responder("response_2_support", "Handling customer support..."));
                  r.on("LOGISTICS")
                      .then(responder("response_3_logistics", "Handling logistics..."));
                }))
        .build();
  }

  /**
   * Runs the workflow on one message and prints each reply. It calls Gemini, so it needs the
   * credentials the README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("routing_workflow").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message =
        Content.fromParts(
            Part.fromText("The app crashes when I open my order, and my parcel is late."));
    runner
        .runAsync(USER_ID, sessionId, message)
        .blockingForEach(event -> {
          String text = event.stringifyContent();
          if (!text.isBlank()) {
            System.out.println("[" + event.author() + "] " + text);
          }
        });
  }

  /** Emits a route for each label in the classifier's answer; several routes fan out. */
  private static Void messageCategories(Context context, String labels) {
    // Normalizes each label, since the model may quote, punctuate or lowercase it.
    context.routes(
        Splitter.on(',')
            .trimResults(LABEL_TRIM)
            .splitToStream(labels)
            .map(label -> new Route.Tag(label.toUpperCase(Locale.ROOT).replace(' ', '_')))
            .collect(toImmutableList()));
    return null;
  }

  /** Returns a node that tells the user the message is being handled. */
  private static Node responder(String name, String reply) {
    return FunctionNodes.of(
        name,
        Object.class,
        Event.class,
        (context, input) ->
            Event.builder()
                .id(context.invocationContext().newUuid())
                .content(Content.builder().role("model").parts(Part.fromText(reply)).build())
                .build());
  }

  private static LlmAgent processMessageAgent(String model) {
    return LlmAgent.builder()
        .name("process_message")
        .model(model)
        // Outputs its labels, which the next node turns into routes.
        .mode(AgentMode.SINGLE_TURN)
        .instruction(
            """
            Classify user message into either "BUG", "CUSTOMER_SUPPORT",
            or "LOGISTICS". If you think a message applies to more than one category,
            reply with a comma separated list of categories.
            """)
        .build();
  }

  private MessageTriageWorkflow() {}
}
