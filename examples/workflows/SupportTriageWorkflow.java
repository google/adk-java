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
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.runner.Runner;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.Map;

/**
 * A port of ADK Kotlin's support-triage workflow sample. A classifier labels the request, a routing
 * map sends it to a billing or tech specialist who looks up the facts with a tool, and a summarizer
 * writes the reply. Any other request goes straight to the summarizer through {@code otherwise}.
 */
public final class SupportTriageWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /**
   * Builds the workflow, with every agent on {@code model}. The billing specialist reads the
   * customer's account ID from the {@code account_id} session state key, so pass it with each
   * request.
   */
  public static Workflow create(String model) {
    LlmAgent billing = billingAgent(model);
    LlmAgent tech = techAgent(model);
    LlmAgent summarize = summaryAgent(model);
    // START hands the first node the user's message as Content.
    Node saveRequest =
        FunctionNodes.of(
            "save_request", Content.class, String.class, SupportTriageWorkflow::saveRequest);
    Node requestCategory =
        FunctionNodes.of(
            "request_category", Triage.class, Void.class, SupportTriageWorkflow::requestCategory);
    return Workflow.builder()
        .name("support_triage")
        .edges(g -> {
          // An LlmAgent is a Node, so it goes straight into the graph.
          g.fromStart()
              .chain(saveRequest, classifierAgent(model), requestCategory)
              .route(r -> {
                r.on("billing").then(billing);
                r.on("tech").then(tech);
                r.otherwise(summarize);
              });
          g.from(billing).then(summarize);
          g.from(tech).then(summarize);
        })
        .build();
  }

  /**
   * Runs the workflow on one request from a signed-in customer and prints what each node said. It
   * calls Gemini, so it needs the credentials the README describes; the first argument, if any,
   * overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("support_triage").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content request =
        Content.fromParts(Part.fromText("I was charged twice for my subscription this month."));
    // The support app knows who is signed in, so it passes the account ID as session state.
    Map<String, Object> account = ImmutableMap.of("account_id", "ACC-1042");
    runner
        .runAsync(USER_ID, sessionId, request, RunConfig.builder().build(), account)
        .blockingForEach(event -> {
          String text = event.stringifyContent();
          // Skips the specialists' tool calls and results, printing only what nodes said.
          if (!text.isBlank()
              && event.functionCalls().isEmpty()
              && event.functionResponses().isEmpty()) {
            System.out.println(
                "[" + event.nodeInfo().map(NodeInfo::path).orElse(null) + "] " + text);
          }
        });
  }

  /** A charge on a customer's account. */
  public record Charge(
      String id, String date, String description, int amountCents, String currency) {}

  /** A mock billing backend that the billing specialist queries through a tool. */
  public static final class BillingSystem {

    /** Lists the charges on an account from the last 60 days, newest first. */
    @Annotations.Schema(
        description = "Lists the charges on an account from the last 60 days, newest first.")
    public ImmutableList<Charge> listRecentCharges(
        @Annotations.Schema(
                name = "accountId",
                description = "The customer's account ID, such as ACC-1042")
            String accountId) {
      if (!accountId.equals("ACC-1042")) {
        return ImmutableList.of();
      }
      return ImmutableList.of(
          new Charge("ch_302", "2026-09-01", "Pro plan, monthly", 1200, "USD"),
          new Charge("ch_301", "2026-09-01", "Pro plan, monthly", 1200, "USD"),
          new Charge("ch_214", "2026-08-01", "Pro plan, monthly", 1200, "USD"));
    }
  }

  /** A mock status page that the support engineer checks through a tool. */
  public static final class StatusPage {

    /** Returns the current status of each service. */
    @Annotations.Schema(description = "Returns the current status of each service.")
    public ImmutableMap<String, String> getServiceStatus() {
      return ImmutableMap.of(
          "sync", "Degraded since 09:10 UTC. A fix is rolling out.",
          "login", "Operational",
          "billing", "Operational");
    }
  }

  /**
   * Stores the customer's request in state so the specialists and the summarizer can read it. After
   * a specialist runs, the summarizer sees only that specialist's notes.
   */
  private static String saveRequest(Context context, Content message) {
    String request = message.text();
    context.state().put("request", request);
    return request;
  }

  /** Routes on the category the classifier returned, since an agent node emits no route. */
  private static Void requestCategory(Context context, Triage triage) {
    // No edge matches "other", so otherwise takes the request.
    context.routes(ImmutableList.of(new Route.Tag(triage.category())));
    return null;
  }

  private static LlmAgent classifierAgent(String model) {
    return LlmAgent.builder()
        .name("classifier")
        .model(model)
        // Takes the request as its user turn and outputs the parsed category.
        .mode(AgentMode.SINGLE_TURN)
        .instruction("Classify the customer's latest message.")
        .outputSchema(TRIAGE_SCHEMA)
        .build();
  }

  private static LlmAgent billingAgent(String model) {
    return LlmAgent.builder()
        .name("billing")
        .model(model)
        // Outputs its notes, which the summarizer takes as its user turn.
        .mode(AgentMode.SINGLE_TURN)
        .instruction(
            """
            You are a billing specialist. The customer's account ID is {account_id}.
            The customer wrote: {request}
            Look up their recent charges, then note in two bullets what happened and how to fix it.
            """)
        .tools(FunctionTool.create(new BillingSystem(), "listRecentCharges"))
        .build();
  }

  private static LlmAgent techAgent(String model) {
    return LlmAgent.builder()
        .name("tech")
        .model(model)
        // Outputs its notes, which the summarizer takes as its user turn.
        .mode(AgentMode.SINGLE_TURN)
        .instruction(
            """
            You are a support engineer.
            The customer wrote: {request}
            Check the service status, then note in two bullets the likely cause and the fix or
            workaround.
            """)
        .tools(FunctionTool.create(new StatusPage(), "getServiceStatus"))
        .build();
  }

  private static LlmAgent summaryAgent(String model) {
    return LlmAgent.builder()
        .name("summary")
        .model(model)
        // Takes a specialist's notes as its user turn, or the request if reached via otherwise.
        .mode(AgentMode.SINGLE_TURN)
        .instruction(
            """
            The customer wrote: {request}
            Write the reply in at most four sentences, based on any specialist notes you are given;
            without notes, say that a support agent will follow up. Do not mention internal tools or
            IDs other than the customer's.
            """)
        .build();
  }

  /** The classifier's structured answer, which matches {@link #TRIAGE_SCHEMA}. */
  private record Triage(String category) {}

  /** The schema of {@link Triage}, so routing never depends on parsing free text. */
  private static final Schema TRIAGE_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "category",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .enum_("billing", "tech", "other")
                      .description(
                          "billing: charges, refunds or plans. tech: errors, outages or app"
                              + " problems. other: anything else.")
                      .build()))
          .required("category")
          .build();

  private SupportTriageWorkflow() {}
}
