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

package com.example;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.util.Map;

/**
 * Example custom tool that simulates weather information retrieval.
 *
 * <p>This is a demonstration tool showing how to create custom tools using the @Schema annotation
 * approach.
 */
public class GetWeatherTool {

  @Schema(name = "get_weather", description = "Get current weather information for a city")
  public static Map<String, Object> getWeather(
      @Schema(name = "city", description = "The city to fetch weather for.") String city) {
    if (isNullOrEmpty(city)) {
      return Map.of("error", "City parameter is required");
    }

    // Simulate weather data retrieval
    int temperature = getSimulatedTemperature(city);
    String condition = getSimulatedCondition(city);

    return Map.of(
        "city", city,
        "temperature", temperature,
        "condition", condition);
  }

  private static int getSimulatedTemperature(String city) {
    // Simple hash-based simulation for consistent results
    int hash = city.toLowerCase().hashCode();
    return 15 + Math.abs(hash % 25); // Temperature between 15-40°C
  }

  private static String getSimulatedCondition(String city) {
    String[] conditions = {"sunny", "cloudy", "partly cloudy", "rainy", "overcast"};
    int hash = city.toLowerCase().hashCode();
    return conditions[Math.abs(hash % conditions.length)];
  }
}
