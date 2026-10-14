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
package com.example.contextcaching;

import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.common.collect.ImmutableList;
import java.time.Duration;

/** A weather assistant whose large, shared instruction makes it a good fit for context caching. */
public final class ContextCachingAgent {

  static final String DEFAULT_MODEL = "gemini-3.8-flash";

  /**
   * The shortest TTL Vertex AI accepts, so the demo can let the cache expire and create it again.
   */
  static final Duration CACHE_TTL = Duration.ofMinutes(1);

  /**
   * The system instruction, which is the bulk of the cached prefix. Explicit caching only starts
   * once that prefix reaches the model's minimum (2048 tokens for Gemini 2.5, 4096 for Gemini 3),
   * so generated station rows pad it past that.
   */
  private static final String WEATHER_REFERENCE = buildWeatherReference();

  private ContextCachingAgent() {}

  /** Returns an app that enables context caching for all its agents. */
  public static App createApp(String model) {
    LlmAgent agent =
        LlmAgent.builder()
            .name("weather_assistant")
            .description("Answers weather questions from a large shared reference.")
            .model(model)
            .instruction(WEATHER_REFERENCE)
            .build();
    return App.builder()
        .name("context_caching_demo")
        .rootAgent(agent)
        .contextCacheConfig(
            new ContextCacheConfig(/* cacheIntervals= */ 10, CACHE_TTL, /* minTokens= */ 0))
        .build();
  }

  private static String buildWeatherReference() {
    StringBuilder reference = new StringBuilder();
    reference.append(
        "You are a weather assistant. Answer questions using ONLY the weather reference below,"
            + " keep answers to one or two sentences, and cite the section or city you used.\n\n");

    ImmutableList<String> guidelines =
        ImmutableList.of(
            "Forecasting basics: a forecast combines current observations, numerical model"
                + " guidance, and local climatology; always state the valid time window and your"
                + " confidence.",
            "Wind chill: how cold the air feels once wind is accounted for. It is only defined for"
                + " temperatures at or below 10C and wind above 5 km/h.",
            "Heat index: the apparent temperature, combining air temperature and humidity. It is"
                + " meaningful above about 27C when relative humidity is high.",
            "Precipitation types: rain, drizzle, sleet, freezing rain, snow, and hail are"
                + " distinguished by the temperature profile between the cloud base and the"
                + " ground.",
            "Storm safety: during a thunderstorm, move indoors, avoid open fields and tall isolated"
                + " trees, and stay off corded electronics until 30 minutes after the last"
                + " thunder.",
            "Units: temperatures are in degrees Celsius, wind speed in km/h, and precipitation in"
                + " millimeters unless a value states otherwise.");
    for (int i = 0; i < guidelines.size(); i++) {
      reference.append("Section ").append(i + 1).append(": ").append(guidelines.get(i));
      reference.append('\n');
    }

    reference.append("\nClimate normals for named cities:\n");
    ImmutableList<String> cities =
        ImmutableList.of(
            "Marisol: coastal and mild; January average 12C, July average 24C, annual rainfall"
                + " 640 mm.",
            "Fjordheim: cold maritime; January average -6C, July average 15C, annual rainfall"
                + " 900 mm.",
            "Solara: hot desert; January average 18C, July average 41C, annual rainfall 90 mm.",
            "Verdant: temperate rainforest; January average 7C, July average 19C, annual rainfall"
                + " 2400 mm.",
            "Highpoint: alpine; January average -9C, July average 12C, annual rainfall 1100 mm.",
            "Puerto Brisa: tropical; January average 26C, July average 29C, annual rainfall"
                + " 1800 mm.",
            "Windgate: windy plains; January average -2C, July average 27C, annual rainfall"
                + " 520 mm.",
            "Frosthollow: subarctic; January average -22C, July average 16C, annual rainfall"
                + " 380 mm.");
    cities.forEach(city -> reference.append("  ").append(city).append('\n'));

    reference.append("\nAutomated station normals:\n");
    ImmutableList<String> windDirections =
        ImmutableList.of("N", "NE", "E", "SE", "S", "SW", "W", "NW");
    for (int i = 1; i <= 100; i++) {
      reference.append(
          String.format(
              "  Station S%d (sector %c%d): January average %dC, July average %dC, annual rainfall"
                  + " %d mm, prevailing wind %s at %d km/h.\n",
              i,
              (char) ('A' + i % 8),
              i % 12,
              -12 + (i * 7) % 34,
              14 + (i * 3) % 22,
              250 + (i * 37) % 2300,
              windDirections.get(i % 8),
              6 + (i * 5) % 38));
    }
    return reference.toString();
  }
}
