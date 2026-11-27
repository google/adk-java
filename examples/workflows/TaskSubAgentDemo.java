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

import static java.util.stream.Collectors.joining;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.runner.Runner;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A port of ADK Kotlin's task sub-agent sample, which ports adk-python's {@code task_sub_agent}: a
 * food-delivery coordinator delegates to two task sub-agents, one that collects the order and one
 * that collects the payment details. Each holds its own conversation with the user until it
 * finishes its task with structured data, which answers the coordinator's call, and the coordinator
 * then places the order with a tool.
 */
public final class TaskSubAgentDemo {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /**
   * Builds a workflow with the coordinator as its only node, running every agent on {@code model}.
   * A task sub-agent runs as a tool only when its parent runs as a workflow node.
   */
  public static Workflow create(String model) {
    FoodDelivery delivery = new FoodDelivery();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .instruction(
                """
                You are a helpful coordinator for a food delivery service.
                You need both order and payment information to place an order.
                """)
            // A task sub-agent is offered to the coordinator as a tool, not a transfer target.
            .subAgents(orderCollectorAgent(model, delivery), paymentCollectorAgent(model))
            .tools(FunctionTool.create(delivery, "placeOrder"))
            .build();
    return Workflow.builder()
        .name("task_sub_agent")
        .edges(g -> g.fromStart().then(coordinator))
        .build();
  }

  /**
   * Runs a conversation in which the user orders food and then pays, printing each message and tool
   * call and approving any tool call that asks. It calls Gemini, so it needs the credentials the
   * README describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("task_sub_agent").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Deque<String> replies =
        new ArrayDeque<>(
            ImmutableList.of(
                "I want 4 pizzas and 2 salads.",
                "That's everything.",
                "My credit card is 1234-5678-9012-3456 and my CVV is 123."));
    Content message = Content.fromParts(Part.fromText(replies.removeFirst()));
    while (message != null) {
      String confirmationId = send(runner, sessionId, message);
      // Approves a pending confirmation request, or else sends the next reply.
      if (confirmationId != null) {
        message = approval(confirmationId);
      } else {
        String reply = replies.poll();
        message = reply == null ? null : Content.fromParts(Part.fromText(reply));
      }
    }
  }

  /**
   * Sends {@code message}, prints the messages and tool calls that follow, and returns the ID of
   * the confirmation request the turn ends with, or null if it ends without one.
   */
  private static @Nullable String send(Runner runner, String sessionId, Content message) {
    String confirmationId = null;
    for (Event event : runner.runAsync(USER_ID, sessionId, message).blockingIterable()) {
      String text =
          event.content().flatMap(Content::parts).stream()
              .flatMap(List::stream)
              .flatMap(part -> part.text().stream())
              .collect(joining());
      if (!text.isBlank()) {
        System.out.println("[" + event.author() + "] " + text);
      }
      for (FunctionCall call : event.functionCalls()) {
        System.out.println(
            "["
                + event.author()
                + "] calls "
                + call.name().orElse("")
                + call.args().orElse(ImmutableMap.of()));
        if (call.name().orElse("").equals(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)) {
          confirmationId = call.id().orElseThrow();
        }
      }
    }
    return confirmationId;
  }

  /** The user's approval of the tool call for confirmation request {@code confirmationId}. */
  private static Content approval(String confirmationId) {
    FunctionResponse response =
        FunctionResponse.builder()
            .id(confirmationId)
            .name(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
            .response(ImmutableMap.of("confirmed", true))
            .build();
    return Content.fromParts(Part.builder().functionResponse(response).build());
  }

  private static LlmAgent orderCollectorAgent(String model, FoodDelivery delivery) {
    return LlmAgent.builder()
        .name("order_collector")
        .model(model)
        .description("Collects the food order from the user.")
        // Chats with the user until its finish_task call carries the order as a list of items.
        .mode(AgentMode.TASK)
        .outputSchema(Schema.builder().type(Type.Known.ARRAY).items(ORDER_ITEM_SCHEMA).build())
        .instruction(
            """
            You are an order collection assistant for a food delivery service.
            Our menu today has exactly 3 items: 1. Pizza, 2. Burger, 3. Salad.
            Ask the user what they would like to order and collect their choice and quantity.
            Do not offer anything else.
            If the combined quantity of items exceeds 5, you MUST use the `confirmation` tool to get
            user's confirmation before proceeding.
            Do not ask for confirmation in natural language, always use the confirmation tool.
            Once you have their final order and confirmation if needed, finish your task.
            """)
        .tools(FunctionTool.create(delivery, "confirmation", /* requireConfirmation= */ true))
        .build();
  }

  private static LlmAgent paymentCollectorAgent(String model) {
    return LlmAgent.builder()
        .name("payment_collector")
        .model(model)
        .description("Collects credit card and CVV from the user.")
        .mode(AgentMode.TASK)
        .outputSchema(PAYMENT_INFO_SCHEMA)
        .instruction(
            """
            You are a payment collection assistant.
            Ask the user for their credit card number and CVV.
            Once you have both pieces of information, finish your task.
            """)
        .build();
  }

  /** The schema of {@link OrderItem}, one item of the order collector's output. */
  private static final Schema ORDER_ITEM_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "name",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("Name of the food item ordered")
                      .build(),
                  "quantity",
                  Schema.builder()
                      .type(Type.Known.INTEGER)
                      .description("Quantity ordered")
                      .build()))
          .required("name", "quantity")
          .build();

  /** The schema of {@link PaymentInfo}, the payment collector's output. */
  private static final Schema PAYMENT_INFO_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "creditCardNumber", Schema.builder().type(Type.Known.STRING).build(),
                  "cvv", Schema.builder().type(Type.Known.STRING).build()))
          .required("creditCardNumber", "cvv")
          .build();

  /** One item of a food order. */
  public record OrderItem(String name, int quantity) {}

  /** The card that pays for an order. */
  public record PaymentInfo(String creditCardNumber, String cvv) {}

  /** A mock food-delivery backend whose methods the agents call as tools. */
  public static final class FoodDelivery {

    /** Mock an order placement operation. */
    @Annotations.Schema(name = "place_order", description = "Mock an order placement operation.")
    public String placeOrder(
        @Annotations.Schema(name = "orders", description = "The items to order")
            List<OrderItem> orders,
        @Annotations.Schema(name = "paymentInfo", description = "The card that pays for the order")
            PaymentInfo paymentInfo) {
      int items = orders.stream().mapToInt(OrderItem::quantity).sum();
      return "Successfully placed order for " + items + " items.";
    }

    /** Confirm proceeding with the order. */
    @Annotations.Schema(name = "confirmation", description = "Confirm proceeding with the order.")
    public String confirmation() {
      return "Proceeding with order.";
    }
  }

  private TaskSubAgentDemo() {}
}
