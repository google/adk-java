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
import com.google.adk.runner.Runner;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A port of ADK Kotlin's single-turn sub-agent sample, which ports adk-python's {@code
 * single_turn_sub_agent}. A phone sales assistant turns the user's request into structured
 * preferences and calls a single-turn sub-agent, which checks prices with a tool and returns a
 * structured recommendation without talking to the user. Unlike an {@code AgentTool}, the
 * sub-agent's tool calls stay in the session history.
 */
public final class SingleTurnSubAgentDemo {

  // Avoid gemini-3.8-flash, which often keeps calling check_phone_price instead of answering.
  private static final String DEFAULT_MODEL = "gemini-4-argon";
  private static final String USER_ID = "user";

  /**
   * Builds a workflow with the sales assistant as its only node, running both agents on {@code
   * model}. A single-turn sub-agent runs as a tool only when its parent runs as a workflow node.
   */
  public static Workflow create(String model) {
    LlmAgent salesAssistant =
        LlmAgent.builder()
            .name("root_agent")
            .model(model)
            .instruction(
                """
                You are a helpful phone sales associate.
                If the user is asking for a phone recommendation, use the `phone_recommender` to
                get a structured recommendation.
                Once the recommender finishes, present the model, price, and reason to the user
                in a friendly way.
                """)
            .subAgents(phoneRecommenderAgent(model))
            .build();
    return Workflow.builder()
        .name("single_turn_sub_agent")
        .edges(g -> g.fromStart().then(salesAssistant))
        .build();
  }

  /**
   * Runs one recommendation request and prints each message and tool call. It calls Gemini and
   * requires the credentials described in the README; the first argument, if any, overrides the
   * model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("single_turn_sub_agent").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message =
        Content.fromParts(
            Part.fromText(
                "I need a phone mostly for gaming. I have about $1000 to spend, and I prefer large"
                    + " phones."));
    runner.runAsync(USER_ID, sessionId, message).blockingForEach(SingleTurnSubAgentDemo::print);
  }

  /** Prints the agent's text and each tool call in the event. */
  private static void print(Event event) {
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
    }
  }

  private static LlmAgent phoneRecommenderAgent(String model) {
    return LlmAgent.builder()
        .name("phone_recommender")
        .model(model)
        .description("Recommends a Pixel phone based on preferences.")
        // Exposes this agent to its parent as a tool using these input and output schemas.
        .mode(AgentMode.SINGLE_TURN)
        .inputSchema(USER_PREFERENCES_SCHEMA)
        .outputSchema(PHONE_RECOMMENDATION_SCHEMA)
        .tools(FunctionTool.create(new PixelPrices(), "checkPhonePrice"))
        .instruction(
            """
            You are an expert Google Pixel hardware recommender.
            Based on the provided UserPreferences, recommend exactly one Pixel phone model.
            You must use the `check_phone_price` tool to find the exact current price of the model
            you are recommending before you finish your task.
            Only recommend these phones: Pixel 10a, Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL,
            Pixel 10 Pro Fold.
            """)
        .build();
  }

  /** Schema for the user's preferences, matching adk-python's {@code UserPreferences} model. */
  private static final Schema USER_PREFERENCES_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "budget",
                  Schema.builder()
                      .type(Type.Known.INTEGER)
                      .description("The user's maximum budget in USD")
                      .build(),
                  "primary_use",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description(
                          "What the user primarily uses their phone for (e.g., photography,"
                              + " gaming, basics)")
                      .build(),
                  "preferred_size",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("Preferred phone size (e.g., small, large, any)")
                      .build()))
          .required("budget", "primary_use", "preferred_size")
          .build();

  /**
   * Schema for the phone recommendation, matching adk-python's {@code PhoneRecommendation} model.
   */
  private static final Schema PHONE_RECOMMENDATION_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "model_name", Schema.builder().type(Type.Known.STRING).build(),
                  "price", Schema.builder().type(Type.Known.NUMBER).build(),
                  "reason", Schema.builder().type(Type.Known.STRING).build()))
          .required("model_name", "price", "reason")
          .build();

  /** Mock price list that the recommender queries through a tool. */
  public static final class PixelPrices {

    // Longest names first, since a shorter name like "Pixel 10" is part of longer ones.
    private static final ImmutableMap<String, Double> PRICES =
        ImmutableMap.of(
            "Pixel 10 Pro Fold", 1799.0,
            "Pixel 10 Pro XL", 1199.0,
            "Pixel 10 Pro", 999.0,
            "Pixel 10a", 499.0,
            "Pixel 10", 799.0);

    /** Mock tool to check the current price of a Pixel phone model. */
    @Annotations.Schema(
        name = "check_phone_price",
        description = "Mock tool to check the current price of a Pixel phone model.")
    public double checkPhonePrice(
        @Annotations.Schema(
                name = "model_name",
                description = "The Pixel model, such as Pixel 10 Pro")
            String modelName) {
      String lowerCaseName = modelName.toLowerCase(Locale.ROOT);
      return PRICES.entrySet().stream()
          .filter(entry -> lowerCaseName.contains(entry.getKey().toLowerCase(Locale.ROOT)))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(799.0);
    }
  }

  private SingleTurnSubAgentDemo() {}
}
