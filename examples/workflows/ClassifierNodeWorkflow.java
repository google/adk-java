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

import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.classifiers.Classifier;
import com.google.adk.classifiers.ClassifierNode;
import com.google.adk.classifiers.LlmClassifier;
import com.google.adk.classifiers.Question;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/**
 * A port of adk-python's {@code classifier_node} workflow sample. A {@link ClassifierNode} asks
 * which team should handle a support ticket and routes it to that team. Technical tickets go to a
 * second classifier node, which asks whether the ticket is urgent and either pages the on-call
 * engineer or files it in the engineering queue.
 */
public final class ClassifierNodeWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final String URGENCY_KEY = "urgency_answer";

  /** Builds the workflow using {@code model} for the classifier and both agents. */
  public static Workflow create(String model) {
    // Any Classifier works here, such as a dedicated decision model.
    Classifier classifier = LlmClassifier.builder().model(model).build();
    ClassifierNode triage =
        ClassifierNode.builder()
            .name("triage")
            .classifier(classifier)
            .question(
                Question.Choice.builder()
                    .instructions("Which team should handle this support ticket?")
                    .criterion("billing", "Invoices, refunds, or subscription charges")
                    .criterion("technical", "Bugs, errors, outages, or API problems")
                    .criterion("general", "Product questions and anything else")
                    .build())
            .answerKey("triage_answer")
            .build();
    ClassifierNode isUrgent =
        ClassifierNode.builder()
            .name("is_urgent")
            .classifier(classifier)
            .question(
                Question.Binary.builder()
                    .instructions(
                        "Is this an outage, or does it block the customer's production use?")
                    .build())
            .threshold(0.7)
            .answerKey(URGENCY_KEY)
            .build();
    // START hands the first node the user's message as Content.
    Node processInput =
        FunctionNodes.of(
            "process_input", Content.class, String.class, ClassifierNodeWorkflow::processInput);

    Node pageOncall =
        FunctionNodes.of(
            "page_oncall", String.class, Event.class, ClassifierNodeWorkflow::pageOncall);
    Node engQueue =
        FunctionNodes.of("eng_queue", String.class, Event.class, ClassifierNodeWorkflow::engQueue);
    return Workflow.builder()
        .name("classifier_node")
        .edges(g -> {
          g.fromStart()
              .chain(processInput, triage)
              .route(r -> {
                r.on("billing").then(billingAgent(model));
                r.on("technical").then(isUrgent);
                r.on("general").then(faqAgent(model));
              });
          g.from(isUrgent)
              .route(r -> {
                r.on(true).then(pageOncall);
                r.on(false).then(engQueue);
              });
        })
        .build();
  }

  /**
   * Runs the workflow on four tickets, each in its own session, and prints each reply. It calls
   * Gemini, so it needs the credentials the README describes; the first argument, if any, overrides
   * the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("classifier_node").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    ImmutableList<String> tickets =
        ImmutableList.of(
            "My invoice charged me twice this month.",
            "The checkout API returns 500 for every request and production is down.",
            "There is a typo on the pricing page.",
            "Do you have a dark mode?");
    for (String ticket : tickets) {
      System.out.println("\n=== " + ticket);
      String sessionId =
          runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
      runner
          .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(ticket)))
          .blockingForEach(event -> {
            String text = event.stringifyContent();
            if (!text.isBlank()) {
              System.out.println("[" + event.author() + "] " + text);
            }
          });
    }
  }

  /** Stores the ticket in state for the agents' instructions to read, and outputs it to triage. */
  private static String processInput(Context context, Content message) {
    String ticket = message.text();
    context.state().put("ticket", ticket);
    return ticket;
  }

  /** Reports the page with the urgency that {@code is_urgent} stored in state. */
  private static Event pageOncall(Context context, String ticket) {
    Map<?, ?> answer = (Map<?, ?>) context.state().get(URGENCY_KEY);
    String urgency = NumberFormat.getPercentInstance(Locale.ROOT).format(answer.get("probability"));
    return message(context, "Paged the on-call engineer (urgency " + urgency + "): " + ticket);
  }

  private static Event engQueue(Context context, String ticket) {
    return message(context, "Filed in the engineering queue: " + ticket);
  }

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private static LlmAgent billingAgent(String model) {
    return LlmAgent.builder()
        .name("billing_agent")
        .model(model)
        .instruction("You are a billing specialist. Reply to this ticket: {ticket}")
        .build();
  }

  private static LlmAgent faqAgent(String model) {
    return LlmAgent.builder()
        .name("faq_agent")
        .model(model)
        .instruction("You answer general product questions. Reply to: {ticket}")
        .build();
  }

  private ClassifierNodeWorkflow() {}
}
