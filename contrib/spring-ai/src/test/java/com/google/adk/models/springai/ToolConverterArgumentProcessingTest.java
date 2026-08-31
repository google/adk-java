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
package com.google.adk.models.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.tools.FunctionTool;
import com.google.genai.types.FunctionDeclaration;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests provider-specific argument normalization in {@link ToolConverter}. */
class ToolConverterArgumentProcessingTest {

  @Test
  void declaredSchemaLeavesCorrectArgumentsUnchanged() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");
    Map<String, Object> arguments = Map.of("location", "San Francisco");

    assertThat(process(converter, arguments, tool.declaration().orElseThrow()))
        .isEqualTo(arguments);
  }

  @Test
  void declaredSchemaUnwrapsNestedArguments() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");

    assertThat(
            process(
                converter,
                Map.of("args", Map.of("location", "San Francisco")),
                tool.declaration().orElseThrow()))
        .containsEntry("location", "San Francisco");
  }

  @Test
  void declaredSchemaMapsDirectValueToSingleParameter() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");

    assertThat(
            process(converter, Map.of("value", "San Francisco"), tool.declaration().orElseThrow()))
        .containsEntry("location", "San Francisco");
  }

  @Test
  void declaredSchemaLeavesUnmatchedArgumentsUnchanged() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");
    Map<String, Object> arguments = Map.of("city", "San Francisco", "country", "USA");

    assertThat(process(converter, arguments, tool.declaration().orElseThrow()))
        .isEqualTo(arguments);
  }

  @Test
  void jsonSchemaLeavesCorrectArgumentsUnchanged() throws Exception {
    ToolConverter converter = new ToolConverter();
    Map<String, Object> arguments = Map.of("location", "San Francisco");

    assertThat(process(converter, arguments, jsonSchemaDeclaration())).isEqualTo(arguments);
  }

  @Test
  void jsonSchemaUnwrapsNestedArguments() throws Exception {
    ToolConverter converter = new ToolConverter();

    assertThat(
            process(
                converter,
                Map.of("args", Map.of("location", "San Francisco")),
                jsonSchemaDeclaration()))
        .containsEntry("location", "San Francisco");
  }

  @Test
  void jsonSchemaMapsDirectValueToSingleParameter() throws Exception {
    ToolConverter converter = new ToolConverter();

    assertThat(process(converter, Map.of("value", "San Francisco"), jsonSchemaDeclaration()))
        .containsEntry("location", "San Francisco");
  }

  @Test
  void jsonSchemaLeavesUnmatchedArgumentsUnchanged() throws Exception {
    ToolConverter converter = new ToolConverter();
    Map<String, Object> arguments = Map.of("city", "San Francisco", "country", "USA");

    assertThat(process(converter, arguments, jsonSchemaDeclaration())).isEqualTo(arguments);
  }

  private Map<String, Object> process(
      ToolConverter converter, Map<String, Object> arguments, FunctionDeclaration declaration)
      throws Exception {
    Method method =
        ToolConverter.class.getDeclaredMethod(
            "processArguments", Map.class, FunctionDeclaration.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> processed =
        (Map<String, Object>) method.invoke(converter, arguments, declaration);
    return processed;
  }

  private FunctionDeclaration jsonSchemaDeclaration() {
    return FunctionDeclaration.builder()
        .name("getWeatherInfo")
        .description("Get weather information")
        .parametersJsonSchema(
            Map.of("type", "object", "properties", Map.of("location", Map.of("type", "string"))))
        .build();
  }

  public static class WeatherTools {
    public static Map<String, Object> getWeatherInfo(String location) {
      return Map.of("location", location);
    }
  }
}
