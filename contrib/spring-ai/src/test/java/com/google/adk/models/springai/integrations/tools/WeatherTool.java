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
package com.google.adk.models.springai.integrations.tools;

import com.google.adk.tools.Annotations;
import java.util.Map;

public class WeatherTool {

  @Annotations.Schema(description = "Function to get the weather forecast for a given city")
  public static Map<String, Object> getWeather(
      @Annotations.Schema(name = "city", description = "The city to get the weather forecast for")
          String city) {

    return Map.of(
        "city", city,
        "forecast", "a beautiful and sunny weather",
        "temperature", "from 10°C in the morning up to 24°C in the afternoon");
  }
}
