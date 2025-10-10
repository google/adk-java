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

import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolConverterTest {

  private ToolConverter toolConverter;

  @BeforeEach
  void setUp() {
    toolConverter = new ToolConverter();
  }

  @Test
  void testCreateToolRegistryWithEmptyTools() {
    Map<String, BaseTool> emptyTools = new HashMap<>();
    Map<String, ToolConverter.ToolMetadata> registry = toolConverter.createToolRegistry(emptyTools);

    assertThat(registry).isNotNull();
    assertThat(registry).isEmpty();
  }

  @Test
  void testCreateToolRegistryWithSingleTool() {
    // Create a simple tool implementation for testing
    FunctionDeclaration function =
        FunctionDeclaration.builder()
            .name("get_weather")
            .description("Get the current weather for a location")
            .build();

    BaseTool testTool =
        new BaseTool("get_weather", "Get the current weather for a location") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(function);
          }
        };

    Map<String, BaseTool> tools = Map.of("get_weather", testTool);
    Map<String, ToolConverter.ToolMetadata> registry = toolConverter.createToolRegistry(tools);

    assertThat(registry).hasSize(1);
    assertThat(registry).containsKey("get_weather");

    ToolConverter.ToolMetadata metadata = registry.get("get_weather");
    assertThat(metadata.getName()).isEqualTo("get_weather");
    assertThat(metadata.getDescription()).isEqualTo("Get the current weather for a location");
    assertThat(metadata.getDeclaration()).isEqualTo(function);
  }

  @Test
  void testCreateToolRegistryWithMultipleTools() {
    FunctionDeclaration weatherFunction =
        FunctionDeclaration.builder()
            .name("get_weather")
            .description("Get weather information")
            .build();

    FunctionDeclaration timeFunction =
        FunctionDeclaration.builder().name("get_time").description("Get current time").build();

    BaseTool weatherTool =
        new BaseTool("get_weather", "Get weather information") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(weatherFunction);
          }
        };

    BaseTool timeTool =
        new BaseTool("get_time", "Get current time") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(timeFunction);
          }
        };

    Map<String, BaseTool> tools =
        Map.of(
            "get_weather", weatherTool,
            "get_time", timeTool);

    Map<String, ToolConverter.ToolMetadata> registry = toolConverter.createToolRegistry(tools);

    assertThat(registry).hasSize(2);
    assertThat(registry).containsKey("get_weather");
    assertThat(registry).containsKey("get_time");

    assertThat(registry.get("get_weather").getName()).isEqualTo("get_weather");
    assertThat(registry.get("get_weather").getDescription()).isEqualTo("Get weather information");

    assertThat(registry.get("get_time").getName()).isEqualTo("get_time");
    assertThat(registry.get("get_time").getDescription()).isEqualTo("Get current time");
  }

  @Test
  void testConvertSchemaToSpringAi() {
    Schema stringSchema = Schema.builder().type("STRING").description("A string parameter").build();

    Map<String, Object> converted = toolConverter.convertSchemaToSpringAi(stringSchema);

    assertThat(converted).containsEntry("type", "string");
    assertThat(converted).containsEntry("description", "A string parameter");
  }

  @Test
  void testConvertSchemaToSpringAiWithObjectType() {
    Schema objectSchema =
        Schema.builder()
            .type("OBJECT")
            .description("An object parameter")
            .properties(
                Map.of(
                    "name", Schema.builder().type("STRING").build(),
                    "age", Schema.builder().type("INTEGER").build()))
            .required(List.of("name"))
            .build();

    Map<String, Object> converted = toolConverter.convertSchemaToSpringAi(objectSchema);

    assertThat(converted).containsEntry("type", "object");
    assertThat(converted).containsEntry("description", "An object parameter");
    assertThat(converted).containsKey("properties");
    assertThat(converted).containsEntry("required", List.of("name"));
  }

  @Test
  void testCreateToolRegistryWithToolWithoutDeclaration() {
    BaseTool testTool =
        new BaseTool("no_declaration_tool", "Tool without declaration") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.empty();
          }
        };

    Map<String, BaseTool> tools = Map.of("no_declaration_tool", testTool);
    Map<String, ToolConverter.ToolMetadata> registry = toolConverter.createToolRegistry(tools);

    assertThat(registry).isEmpty();
  }

  @Test
  void testToolMetadata() {
    FunctionDeclaration function =
        FunctionDeclaration.builder().name("test_function").description("Test description").build();

    ToolConverter.ToolMetadata metadata =
        new ToolConverter.ToolMetadata("test_function", "Test description", function);

    assertThat(metadata.getName()).isEqualTo("test_function");
    assertThat(metadata.getDescription()).isEqualTo("Test description");
    assertThat(metadata.getDeclaration()).isEqualTo(function);
  }
}
