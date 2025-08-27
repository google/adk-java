/*
 * Copyright 2025 Google LLC
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
package com.google.adk.tutorials;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.planners.PlanReActPlanner;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Scanner;

public class CityTimeWeather {

  public static final BaseAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("multi_tool_agent")
          .model("gemini-2.0-flash-lite")
          .description("Agent to answer questions about the time and weather in a city.")
          .planner(new PlanReActPlanner())
          .instruction(
              "You are a helpful agent who can answer user questions about the time and weather in a city.")
          .tools(
              FunctionTool.create(CityTimeWeather.class, "getCurrentTime"),
              FunctionTool.create(CityTimeWeather.class, "getWeather"))
          .build();

  public static Map<String, String> getCurrentTime(
      @Schema(
              name = "city",
              description = "The name of the city for which to retrieve the current time")
          String city) {
    String normalizedCity =
        Normalizer.normalize(city, Normalizer.Form.NFD)
            .trim()
            .toLowerCase()
            .replaceAll("(\\p{IsM}+|\\p{IsP}+)", "")
            .replaceAll("\\s+", "_");

    return ZoneId.getAvailableZoneIds().stream()
        .filter(zid -> zid.toLowerCase().endsWith("/" + normalizedCity))
        .findFirst()
        .map(
            zid ->
                Map.of(
                    "status",
                    "success",
                    "report",
                    "The current time in "
                        + city
                        + " is "
                        + ZonedDateTime.now(ZoneId.of(zid))
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                        + "."))
        .orElse(
            Map.of(
                "status",
                "error",
                "report",
                "Sorry, I don't have timezone information for " + city + "."));
  }

  public static Map<String, String> getWeather(
      @Schema(
              name = "city",
              description = "The name of the city for which to retrieve the weather report")
          String city) {
    if (city.equalsIgnoreCase("new york")) {
      return Map.of(
          "status",
          "success",
          "report",
          "The weather in New York is sunny with a temperature of 25 degrees Celsius (77 degrees"
              + " Fahrenheit).");

    } else if(city.equalsIgnoreCase("shanghai")) {
      return Map.of(
          "status",
          "success",
          "report",
          "The weather in shang hai is sunny with a temperature of 25 degrees Celsius (77 degrees"
              + " Fahrenheit).");
    } else {
      return Map.of(
          "status", "error", "report", "Weather information for " + city + " is not available.");
    }
  }

  private static String USER_ID = "student";
  private static String NAME = "multi_tool_agent";

  public static void main(String[] args) throws Exception {
    InMemoryRunner runner = new InMemoryRunner(ROOT_AGENT);

    Session session =
        runner
            .sessionService()
            .createSession(NAME, USER_ID)
            .blockingGet();

    try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
      while (true) {
        System.out.print("\nYou > ");
        String userInput = scanner.nextLine();

        if ("quit".equalsIgnoreCase(userInput)) {
          break;
        }

        Content userMsg = Content.fromParts(Part.fromText(userInput));
        Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);

        System.out.print("\nAgent > ");
        events.blockingForEach(event -> System.out.println(event.stringifyContent()));
      }
    }
  }
}
