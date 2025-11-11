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
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import java.util.Map;

public class LiveAudioSingleAgent {

  public static final BaseAgent WEATHER_AGENT =
      LlmAgent.builder()
          .name("weather_agent")
          .model("gemini-2.0-flash-live-001")
          .description("A helpful weather assistant that provides weather information.")
          .instruction(
              "You are a friendly weather assistant. When users ask about weather, "
                  + "you MUST call the getWeather tool with the location name. "
                  + "Extract the location from the user's question. "
                  + "ALWAYS use the getWeather tool to get accurate information - never make up weather data. "
                  + "After getting the tool result, provide a friendly and descriptive response. "
                  + "For general conversation or greetings, respond naturally and helpfully. "
                  + "Do NOT use code execution for anything.")
          .tools(FunctionTool.create(LiveAudioSingleAgent.class, "getWeather"))
          .build();

  public static Map<String, String> getWeather(
      @Schema(name = "location", description = "The location for which to retrieve weather")
          String location) {

    Map<String, Map<String, String>> weatherData =
        Map.of(
            "new york",
            Map.of(
                "status",
                "success",
                "temperature",
                "72°F (22°C)",
                "condition",
                "Partly cloudy",
                "report",
                "The weather in New York is partly cloudy with a temperature of 72°F (22°C)."),
            "london",
            Map.of(
                "status",
                "success",
                "temperature",
                "59°F (15°C)",
                "condition",
                "Rainy",
                "report",
                "The weather in London is rainy with a temperature of 59°F (15°C)."),
            "tokyo",
            Map.of(
                "status",
                "success",
                "temperature",
                "68°F (20°C)",
                "condition",
                "Clear",
                "report",
                "The weather in Tokyo is clear with a temperature of 68°F (20°C)."),
            "sydney",
            Map.of(
                "status",
                "success",
                "temperature",
                "77°F (25°C)",
                "condition",
                "Sunny",
                "report",
                "The weather in Sydney is sunny with a temperature of 77°F (25°C)."));

    String normalizedLocation = location.toLowerCase().trim();

    return weatherData.getOrDefault(
        normalizedLocation,
        Map.of(
            "status",
            "error",
            "report",
            String.format(
                "Weather information for '%s' is not available. Try New York, London, Tokyo, or"
                    + " Sydney.",
                location)));
  }

  public static void main(String[] args) {
    AdkWebServer.start(WEATHER_AGENT);
  }
}
