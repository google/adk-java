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
import com.google.adk.agents.Context;
import com.google.adk.apps.App;
import com.google.adk.runner.Runner;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.ToolNodes;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;

/**
 * A returns desk whose steps include the function tools of {@link OrderSystem}. A routing map sends
 * each group of statuses, matched with {@code anyOf}, to the tool that handles it, and declines any
 * other status.
 */
public final class ReturnsWorkflow {

  private static final String USER_ID = "user";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    OrderSystem orders = new OrderSystem();
    Node returnDesk =
        FunctionNodes.of(
            "return_desk",
            new TypeReference<Map<String, String>>() {},
            new TypeReference<Map<String, String>>() {},
            ReturnsWorkflow::returnDesk);
    Node decline =
        FunctionNodes.of(
            "decline",
            new TypeReference<Map<String, String>>() {},
            String.class,
            ReturnsWorkflow::decline);
    return Workflow.builder()
        .name("returns")
        .edges(g ->
            g.fromStart()
                .chain(ToolNodes.asNode(FunctionTool.create(orders, "lookupOrder")), returnDesk)
                .route(r -> {
                  r.on(r.anyOf("processing", "packed"))
                      .then(ToolNodes.asNode(FunctionTool.create(orders, "cancelOrder")));
                  r.on(r.anyOf("shipped", "delivered"))
                      .then(ToolNodes.asNode(FunctionTool.create(orders, "sendReturnLabel")));
                  r.otherwise(decline);
                }))
        .build();
  }

  /** Runs the workflow on three orders and prints each step's output. */
  public static void main(String[] args) {
    App app = App.builder().name("returns").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    for (String orderId : ImmutableList.of("A-1001", "A-1002", "A-1003")) {
      String sessionId =
          runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
      // The first step is a tool, so the request is a JSON object of its arguments.
      Content request = Content.fromParts(Part.fromText("{\"orderId\": \"" + orderId + "\"}"));
      runner
          .runAsync(USER_ID, sessionId, request)
          .blockingForEach(
              event ->
                  event
                      .output()
                      .ifPresent(
                          output ->
                              System.out.println(
                                  "["
                                      + event.nodeInfo().map(NodeInfo::path).orElse(null)
                                      + "] "
                                      + output)));
    }
  }

  /** A mock order backend whose tools the returns workflow runs as graph steps. */
  public static final class OrderSystem {
    private static final ImmutableMap<String, String> STATUSES =
        ImmutableMap.of("A-1001", "processing", "A-1002", "delivered", "A-1003", "returned");

    /** Looks up where an order is in fulfillment. */
    @Schema(description = "Looks up where an order is in fulfillment.")
    public ImmutableMap<String, String> lookupOrder(
        @Schema(name = "orderId", description = "The order ID, such as A-1001") String orderId) {
      return ImmutableMap.of(
          "orderId", orderId, "status", STATUSES.getOrDefault(orderId, "unknown"));
    }

    /** Cancels an order that has not shipped yet and refunds it in full. */
    @Schema(description = "Cancels an order that has not shipped yet and refunds it in full.")
    public String cancelOrder(
        @Schema(name = "orderId", description = "The order ID, such as A-1001") String orderId) {
      return "Canceled " + orderId + " before it shipped. The full refund is on its way.";
    }

    /** Emails the customer a prepaid label for sending an order back. */
    @Schema(description = "Emails the customer a prepaid label for sending an order back.")
    public String sendReturnLabel(
        @Schema(name = "orderId", description = "The order ID, such as A-1001") String orderId) {
      return "Emailed a prepaid return label for "
          + orderId
          + ". The refund follows once the return arrives.";
    }
  }

  /** Routes on the order's status and passes the order ID to the next step. */
  private static Map<String, String> returnDesk(Context context, Map<String, String> lookup) {
    // A function tool outputs the map it returns.
    context.routes(ImmutableList.of(new Route.Tag(lookup.get("status"))));
    return ImmutableMap.of("orderId", lookup.get("orderId"));
  }

  /** Declines a return for any other status, such as an already returned order. */
  private static String decline(Context context, Map<String, String> request) {
    return "Order " + request.get("orderId") + " can't be returned.";
  }

  private ReturnsWorkflow() {}
}
