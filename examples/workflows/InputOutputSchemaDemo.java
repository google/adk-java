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
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.List;

/**
 * A port of ADK Kotlin's input and output schema sample, which ports adk-python's {@code
 * input_output_schema}. A weather agent with input and output schemas runs in single-turn mode, so
 * its parent can call it as a tool that takes a city and returns structured weather information.
 */
public final class InputOutputSchemaDemo {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /**
   * Builds a workflow with the weather concierge as its only node, running both agents on {@code
   * model}. A single-turn sub-agent runs as a tool only when its parent runs as a workflow node.
   */
  public static Workflow create(String model) {
    LlmAgent concierge =
        LlmAgent.builder()
            .name("root_agent")
            .model(model)
            .instruction(
                "You are a helpful weather concierge assistant. Use the weather_agent tool to get"
                    + " weather information for the user's city, and then answer the user in a"
                    + " friendly manner.")
            .subAgents(weatherAgent(model))
            .build();
    return Workflow.builder()
        .name("input_output_schema")
        .edges(g -> g.fromStart().then(concierge))
        .build();
  }

  /**
   * Asks about three cities in separate sessions and prints each message and tool call. It calls
   * Gemini and requires the credentials described in the README; the first argument, if any,
   * overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("input_output_schema").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    ImmutableList<String> questions =
        ImmutableList.of(
            "What is the weather in San Jose?",
            "Can you check the weather for Cupertino?",
            "What is the weather in Tokyo?");
    for (String question : questions) {
      System.out.println("\n=== " + question);
      String sessionId =
          runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
      Content message = Content.fromParts(Part.fromText(question));
      runner.runAsync(USER_ID, sessionId, message).blockingForEach(InputOutputSchemaDemo::print);
    }
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

  private static LlmAgent weatherAgent(String model) {
    return LlmAgent.builder()
        .name("weather_agent")
        .model(model)
        // Exposes this agent to its parent as a tool using these input and output schemas.
        .mode(AgentMode.SINGLE_TURN)
        // Omit transfer_to_agent so the agent answers directly instead of transferring back.
        .disallowTransferToParent(true)
        .inputSchema(CITY_QUERY_SCHEMA)
        .outputSchema(WEATHER_INFO_SCHEMA)
        .instruction(
            """
            Provide weather information for the requested city.

            For San Jose, return temperature: 26 C, conditions: Sunny.
            For Cupertino, return temperature: 16 C, conditions: Foggy.
            For any other city, return temperature: unknown, conditions: unknown.
            """)
        .build();
  }

  /** Schema for the weather agent's input, matching adk-python's {@code CityQuery} model. */
  private static final Schema CITY_QUERY_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "city",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The name of the city to query weather for, e.g. San Jose")
                      .build()))
          .required("city")
          .build();

  /** Schema for the weather agent's answer, matching adk-python's {@code WeatherInfo} model. */
  private static final Schema WEATHER_INFO_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "temperature",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The temperature in Celsius")
                      .build(),
                  "conditions",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The weather condition, e.g. Sunny")
                      .build()))
          .required("temperature", "conditions")
          .build();

  private InputOutputSchemaDemo() {}
}
