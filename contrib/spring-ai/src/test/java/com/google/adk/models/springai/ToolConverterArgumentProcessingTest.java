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
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/** Test argument processing logic in ToolConverter. */
class ToolConverterArgumentProcessingTest {

  @Test
  void testArgumentProcessingWithCorrectFormat() throws Exception {
    // Create tool converter and tool
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");
    Map<String, com.google.adk.tools.BaseTool> tools = Map.of("getWeatherInfo", tool);

    // Convert to Spring AI format
    List<ToolCallback> toolCallbacks = converter.convertToSpringAiTools(tools);
    assertThat(toolCallbacks).hasSize(1);

    // Test with correct argument format
    ToolCallback callback = toolCallbacks.get(0);
    Method processArguments = getProcessArgumentsMethod(converter);

    Map<String, Object> correctArgs = Map.of("location", "San Francisco");
    Map<String, Object> processedArgs =
        invokeProcessArguments(processArguments, converter, correctArgs, tool.declaration().get());

    assertThat(processedArgs).isEqualTo(correctArgs);
  }

  @Test
  void testArgumentProcessingWithNestedFormat() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");

    Method processArguments = getProcessArgumentsMethod(converter);

    // Test with nested arguments
    Map<String, Object> nestedArgs = Map.of("args", Map.of("location", "San Francisco"));
    Map<String, Object> processedArgs =
        invokeProcessArguments(processArguments, converter, nestedArgs, tool.declaration().get());

    assertThat(processedArgs).containsEntry("location", "San Francisco");
  }

  @Test
  void testArgumentProcessingWithDirectValue() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");

    Method processArguments = getProcessArgumentsMethod(converter);

    // Test with single direct value (wrong key name)
    Map<String, Object> directValueArgs = Map.of("value", "San Francisco");
    Map<String, Object> processedArgs =
        invokeProcessArguments(
            processArguments, converter, directValueArgs, tool.declaration().get());

    // Should map the single value to the expected parameter name
    assertThat(processedArgs).containsEntry("location", "San Francisco");
  }

  @Test
  void testArgumentProcessingWithNoMatch() throws Exception {
    ToolConverter converter = new ToolConverter();
    FunctionTool tool = FunctionTool.create(WeatherTools.class, "getWeatherInfo");

    Method processArguments = getProcessArgumentsMethod(converter);

    // Test with completely wrong format
    Map<String, Object> wrongArgs = Map.of("city", "San Francisco", "country", "USA");
    Map<String, Object> processedArgs =
        invokeProcessArguments(processArguments, converter, wrongArgs, tool.declaration().get());

    // Should return original args when no processing applies
    assertThat(processedArgs).isEqualTo(wrongArgs);
  }

  private Method getProcessArgumentsMethod(ToolConverter converter) throws Exception {
    Method method =
        ToolConverter.class.getDeclaredMethod(
            "processArguments", Map.class, com.google.genai.types.FunctionDeclaration.class);
    method.setAccessible(true);
    return method;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invokeProcessArguments(
      Method method,
      ToolConverter converter,
      Map<String, Object> args,
      com.google.genai.types.FunctionDeclaration declaration)
      throws Exception {
    return (Map<String, Object>) method.invoke(converter, args, declaration);
  }

  public static class WeatherTools {
    public static Map<String, Object> getWeatherInfo(String location) {
      return Map.of(
          "location", location,
          "temperature", "72°F",
          "condition", "sunny and clear",
          "humidity", "45%",
          "forecast", "Perfect weather for outdoor activities!");
    }
  }
}
