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
import com.google.adk.runner.Runner;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.JoinNode;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.ToolNodes;
import com.google.adk.workflow.Workflow;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A port of ADK Kotlin's order-processing workflow sample, with fraud review. Nested fraud and
 * inventory workflows and a credit-check tool run concurrently, and a {@link JoinNode} waits for
 * all three before a risk agent decides whether the order is charged, reviewed by finance and
 * compliance agents, or frozen as fraud. Every outcome ends with a receipt.
 */
public final class OrderProcessingWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final String RISK_KEY = "risk";
  private static final String FINANCE_KEY = "finance_review";
  private static final String COMPLIANCE_KEY = "compliance_review";

  /**
   * Orders as the storefront sends them: a small one, a large cross-border one, and a likely fraud.
   */
  private static final ImmutableList<String> SAMPLE_ORDERS =
      ImmutableList.of(
          "order_id=O-1001 customer_id=C-118 total_usd=120 ship_country=US bill_country=US"
              + " account_age_days=900",
          "order_id=O-1002 customer_id=C-310 total_usd=2400 ship_country=US bill_country=CA"
              + " account_age_days=400",
          "order_id=O-1003 customer_id=C-977 total_usd=4800 ship_country=NG bill_country=US"
              + " account_age_days=2");

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(String model) {
    // Each nested workflow is one fan-out branch, so risk_join waits for its last step.
    Workflow fraudCheck =
        Workflow.builder()
            .name("fraud_check")
            .edges(
                g ->
                    g.fromStart()
                        .chain(
                            new Step("geo_check", OrderProcessingWorkflow::geoCheck),
                            new Step("fraud_score", OrderProcessingWorkflow::fraudScore)))
            .build();
    Workflow inventory =
        Workflow.builder()
            .name("inventory")
            .edges(
                g ->
                    g.fromStart()
                        .chain(
                            new Step(
                                "reserve_inventory", OrderProcessingWorkflow::reserveInventory),
                            new Step("warehouse_hold", OrderProcessingWorkflow::warehouseHold)))
            .build();
    Node creditCheck = ToolNodes.asNode(FunctionTool.create(new CreditBureau(), "creditCheck"));
    Node assessRisk = riskAgent(model);
    Node financeReview = financeReviewAgent(model);
    Node complianceReview = complianceReviewAgent(model);
    Node chargeCard = new Step("charge_card", OrderProcessingWorkflow::chargeCard);
    Node freezeAccount = new Step("freeze_account", OrderProcessingWorkflow::freezeAccount);
    Node alertSecOps = new Step("alert_sec_ops", OrderProcessingWorkflow::alertSecOps);
    Node cancelOrder = new Step("cancel_order", OrderProcessingWorkflow::cancelOrder);
    Node sendReceipt = new Step("send_receipt", OrderProcessingWorkflow::sendReceipt);
    return Workflow.builder()
        .name("order_processing")
        .edges(
            g -> {
              // Stage 1: the three branches run concurrently; risk_join waits for all of them.
              g.fromStart()
                  .then(new ParseOrder())
                  .then(fraudCheck, inventory, creditCheck)
                  .joinTo(JoinNode.create("risk_join"))
                  .chain(assessRisk, new RiskDecision())
                  // Stage 2: route on risk decision; a route mapped to two nodes fans out.
                  .route(
                      r -> {
                        r.on("auto_approve").then(chargeCard);
                        r.on(r.anyOf("manual_review", "high_value"))
                            .then(financeReview, complianceReview);
                        r.on("fraud").then(freezeAccount, alertSecOps);
                        r.otherwise(cancelOrder);
                      });
              g.from(financeReview, complianceReview)
                  .joinTo(JoinNode.create("review_join"))
                  .then(new ReviewGate(/* maxRounds= */ 2))
                  .route(
                      r -> {
                        r.on("approved").then(chargeCard);
                        r.on("recheck").then(assessRisk); // Loops back for another assessment.
                        r.otherwise(cancelOrder);
                      });
              g.from(freezeAccount, alertSecOps)
                  .joinTo(JoinNode.create("audit_join"))
                  .then(cancelOrder);
              // Stage 3: only one outcome runs per order, so the receipt needs no JoinNode.
              g.from(chargeCard)
                  .chain(new Step("ship_order", OrderProcessingWorkflow::shipOrder), sendReceipt);
              g.from(cancelOrder).then(sendReceipt);
            })
        .build();
  }

  /**
   * Runs the workflow on three orders, each in its own session, and prints what each step and agent
   * did. It calls Gemini, so it needs the credentials the README describes; the first argument, if
   * any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("order_processing").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    for (String order : SAMPLE_ORDERS) {
      // Each sample order starts with its order_id field.
      System.out.println(
          "\n=== Order " + order.substring("order_id=".length(), order.indexOf(' ')));
      String sessionId =
          runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
      runner
          .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(order)))
          .blockingForEach(
              event -> {
                String path = event.nodeInfo().map(NodeInfo::path).orElse(null);
                String text = event.stringifyContent();
                if (!text.isBlank()) {
                  System.out.println("[" + path + "] " + text);
                } else {
                  event
                      .output()
                      .ifPresent(output -> System.out.println("[" + path + "] " + output));
                }
              });
    }
  }

  /** A mock credit bureau whose check runs as a step of the workflow. */
  public static final class CreditBureau {
    private static final ImmutableMap<String, Integer> CREDIT_LIMITS_USD =
        ImmutableMap.of("C-118", 1500, "C-310", 5000, "C-977", 500);

    /** Checks whether the customer's credit limit covers the order total. */
    @Annotations.Schema(
        description = "Checks whether the customer's credit limit covers the order total.")
    public ImmutableMap<String, Object> creditCheck(
        ToolContext toolContext,
        @Annotations.Schema(name = "customerId", description = "The customer ID, such as C-310")
            String customerId,
        @Annotations.Schema(name = "orderTotalUsd", description = "The order total in US dollars")
            int orderTotalUsd) {
      int limitUsd = CREDIT_LIMITS_USD.getOrDefault(customerId, 0);
      ImmutableMap<String, Object> result =
          ImmutableMap.of("creditLimitUsd", limitUsd, "covered", orderTotalUsd <= limitUsd);
      // The agents' instructions read the result from state, so the check also records it there.
      toolContext.state().put("credit", result);
      return result;
    }
  }

  /**
   * Parses the order's {@code key=value} fields into state, where the steps and agents read them,
   * and outputs the credit check's arguments.
   */
  private static final class ParseOrder implements Node {
    @Override
    public String name() {
      return "parse_order";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands the first node the user's message as Content.
      String text = ((Content) nodeInput).text();
      for (String token : Splitter.on(CharMatcher.whitespace()).omitEmptyStrings().split(text)) {
        // Splits at first '=' and never fails: a token without '=' maps to itself.
        int separator = token.indexOf('=');
        String key = separator < 0 ? token : token.substring(0, separator);
        String value = separator < 0 ? token : token.substring(separator + 1);
        Integer number = Ints.tryParse(value);
        context.state().put(key, number != null ? number : value);
      }
      // Every fan-out branch gets this output; only the tool reads it as its arguments.
      return Flowable.just(
          ImmutableMap.of(
              "customerId",
              context.state().get("customer_id"),
              "orderTotalUsd",
              context.state().get("total_usd")));
    }
  }

  /** Routes on the risk agent's decision, since an {@link LlmAgent} node emits no route. */
  private static final class RiskDecision implements Node {
    @Override
    public String name() {
      return "risk_decision";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // Without a decision, this node emits no route, so `otherwise` cancels the order.
      if (readStateField(context, RISK_KEY, "decision") instanceof String decision) {
        context.routes(ImmutableList.of(new Route.Tag(decision)));
      }
      return Flowable.empty();
    }
  }

  /**
   * Routes on both reviewers' verdicts: it emits approved when both approve, recheck when either
   * asks for one and fewer than {@code maxRounds} reviews have run, and no route otherwise, which
   * cancels the order.
   */
  private record ReviewGate(int maxRounds) implements Node {
    @Override
    public String name() {
      return "review_gate";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Object finance = readStateField(context, FINANCE_KEY, "verdict");
      Object compliance = readStateField(context, COMPLIANCE_KEY, "verdict");
      // The run id counts this node's runs in this invocation, so it is the review round.
      int round = Integer.parseInt(context.requireNodeState().runId());
      if (Objects.equals(finance, "approve") && Objects.equals(compliance, "approve")) {
        context.routes(ImmutableList.of(new Route.Tag("approved")));
      } else if ((Objects.equals(finance, "recheck") || Objects.equals(compliance, "recheck"))
          && round < maxRounds) {
        context.routes(ImmutableList.of(new Route.Tag("recheck")));
      }
      return Flowable.empty();
    }
  }

  /**
   * Returns {@code field} of the map an agent stored under {@code key}, or null if there is none.
   */
  private static @Nullable Object readStateField(Context context, String key, String field) {
    return context.state().get(key) instanceof Map<?, ?> map ? map.get(field) : null;
  }

  /** A step that stands in for a backend call and outputs what it did. */
  private record Step(String name, Function<Context, String> body) implements Node {
    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(body.apply(context));
    }
  }

  private static String geoCheck(Context context) {
    boolean mismatch =
        !Objects.equals(context.state().get("ship_country"), context.state().get("bill_country"));
    context.state().put("geo_mismatch", mismatch);
    return mismatch ? "The shipping and billing countries differ." : "The countries match.";
  }

  private static String fraudScore(Context context) {
    int score = 10;
    if (Boolean.TRUE.equals(context.state().get("geo_mismatch"))) {
      score += 25;
    }
    if (((Number) context.state().get("account_age_days")).intValue() < 30) {
      score += 35;
    }
    if (((Number) context.state().get("total_usd")).intValue() > 2000) {
      score += 15;
    }
    context.state().put("fraud_score", score);
    return "Fraud score: " + score + " out of 100.";
  }

  private static String reserveInventory(Context context) {
    String reservation = "RSV-" + context.state().get("order_id");
    context.state().put("reservation", reservation);
    return "Reserved stock for order "
        + context.state().get("order_id")
        + " as "
        + reservation
        + ".";
  }

  private static String warehouseHold(Context context) {
    String hold = context.state().get("reservation") + " is held at the NJ-2 warehouse.";
    context.state().put("inventory", hold);
    return hold;
  }

  private static String chargeCard(Context context) {
    return "Charged "
        + context.state().get("total_usd")
        + " USD for order "
        + context.state().get("order_id")
        + ".";
  }

  private static String shipOrder(Context context) {
    context.state().put("outcome", "charged and shipped");
    return "Shipped order " + context.state().get("order_id") + " from the NJ-2 warehouse.";
  }

  private static String freezeAccount(Context context) {
    return "Froze account " + context.state().get("customer_id") + " pending an investigation.";
  }

  private static String alertSecOps(Context context) {
    return "Sent SecOps the fraud signals for order " + context.state().get("order_id") + ".";
  }

  private static String cancelOrder(Context context) {
    context.state().put("outcome", "canceled");
    return "Canceled order "
        + context.state().get("order_id")
        + " and released "
        + context.state().get("reservation")
        + ".";
  }

  private static String sendReceipt(Context context) {
    return "Emailed customer "
        + context.state().get("customer_id")
        + ": order "
        + context.state().get("order_id")
        + " was "
        + context.state().get("outcome")
        + ".";
  }

  /** The risk agent's structured decision, which {@link RiskDecision} routes on. */
  private static final Schema RISK_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "decision",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .enum_("auto_approve", "manual_review", "high_value", "fraud")
                      .description(
                          "auto_approve: low risk. high_value: over 2000 USD but otherwise low"
                              + " risk. manual_review: the signals conflict. fraud: the signals"
                              + " point to fraud.")
                      .build(),
                  "reason",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("One sentence on the decision.")
                      .build()))
          .required("decision", "reason")
          .build();

  private static LlmAgent riskAgent(String model) {
    return LlmAgent.builder()
        .name("assess_risk")
        .model(model)
        .instruction(
            """
            Assess the risk of this order before the card is charged.
            Order {order_id}: {total_usd} USD from customer {customer_id}, whose account is
            {account_age_days} days old. It ships to {ship_country} and is billed in {bill_country}.
            Fraud score: {fraud_score} out of 100. Inventory: {inventory}. Credit: {credit}.
            If reviewers asked for a recheck, weigh their notes:
            {finance_review?} {compliance_review?}
            """)
        .outputSchema(RISK_SCHEMA)
        .outputKey(RISK_KEY)
        .build();
  }

  /** A reviewer's structured verdict, which {@link ReviewGate} routes on. */
  private static final Schema REVIEW_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "verdict",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .enum_("approve", "reject", "recheck")
                      .build(),
                  "note",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("One sentence on the verdict.")
                      .build()))
          .required("verdict", "note")
          .build();

  private static LlmAgent financeReviewAgent(String model) {
    return LlmAgent.builder()
        .name("finance_review")
        .model(model)
        .instruction(
            """
            You review orders for the finance team. Order {order_id} totals {total_usd} USD.
            Credit: {credit}. Risk assessment: {risk}.
            Approve if the customer's credit covers the order, reject if it does not, and ask for a
            recheck if the risk assessment overlooked something.
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey(FINANCE_KEY)
        .build();
  }

  private static LlmAgent complianceReviewAgent(String model) {
    return LlmAgent.builder()
        .name("compliance_review")
        .model(model)
        .instruction(
            """
            You review orders for the compliance team. Order {order_id} ships to {ship_country}, is
            billed in {bill_country}, and comes from an account {account_age_days} days old.
            Fraud score: {fraud_score} out of 100. Risk assessment: {risk}.
            Approve or reject the order, or ask for a recheck if the risk assessment overlooked
            something.
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey(COMPLIANCE_KEY)
        .build();
  }

  private OrderProcessingWorkflow() {}
}
