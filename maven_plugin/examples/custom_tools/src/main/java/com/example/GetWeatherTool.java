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

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Example custom weather tool. */
public class GetWeatherTool extends BaseTool {

  public GetWeatherTool() {
    super("get_weather", "Get current weather information for a city");
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name("get_weather")
            .description("Get current weather information for a city")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        Map.of(
                            "city",
                            Schema.builder()
                                .type("STRING")
                                .description("The city to fetch weather for")
                                .build()))
                    .required(List.of("city"))
                    .build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    String city = (String) args.get("city");

    if (isNullOrEmpty(city)) {
      return Single.just(Map.of("error", "City parameter is required"));
    }

    int temperature = getSimulatedTemperature(city);
    String condition = getSimulatedCondition(city);

    return Single.just(
        Map.of(
            "city", city,
            "temperature", temperature,
            "condition", condition));
  }

  private static int getSimulatedTemperature(String city) {
    int hash = city.toLowerCase().hashCode();
    return 15 + Math.abs(hash % 25);
  }

  private static String getSimulatedCondition(String city) {
    String[] conditions = {"sunny", "cloudy", "partly cloudy", "rainy", "overcast"};
    int hash = city.toLowerCase().hashCode();
    return conditions[Math.abs(hash % conditions.length)];
  }
}
