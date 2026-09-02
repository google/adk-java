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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

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

  @Test
  void testConvertToSpringAiToolsWithParametersJsonSchema() {
    Map<String, Object> jsonSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("location", Map.of("type", "string", "description", "City name")),
            "required",
            List.of("location"));

    FunctionDeclaration function =
        FunctionDeclaration.builder()
            .name("get_weather")
            .description("Get weather for a location")
            .parametersJsonSchema(jsonSchema)
            .build();

    BaseTool testTool =
        new BaseTool("get_weather", "Get weather for a location") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(function);
          }
        };

    Map<String, BaseTool> tools = Map.of("get_weather", testTool);
    List<ToolCallback> toolCallbacks = toolConverter.convertToSpringAiTools(tools);

    assertThat(toolCallbacks).hasSize(1);
    assertThat(toolCallbacks.get(0).getToolDefinition().name()).isEqualTo("get_weather");
    assertThat(toolCallbacks.get(0).getToolDefinition().inputSchema())
        .contains("\"location\"")
        .contains("\"required\"");
  }

  @Test
  void convertedToolCallbackOnlyProvidesDefinitionAndDoesNotExecuteAdkTool() {
    FunctionDeclaration function =
        FunctionDeclaration.builder()
            .name("context_tool")
            .description("A tool that requires ADK execution context")
            .parametersJsonSchema(
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("value", Map.of("type", "string")),
                    "required",
                    List.of("value")))
            .build();
    List<ToolContext> receivedContexts = new ArrayList<>();
    BaseTool contextTool =
        new BaseTool("context_tool", "A tool that requires ADK execution context") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(function);
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            receivedContexts.add(toolContext);
            return Single.just(Map.of("value", args.get("value")));
          }
        };

    ToolCallback callback =
        toolConverter.convertToSpringAiTools(Map.of(contextTool.name(), contextTool)).get(0);

    String resultWithoutSpringContext = callback.call("{\"value\":\"test\"}");
    String resultWithSpringContext =
        callback.call(
            "{\"value\":\"test\"}",
            new org.springframework.ai.chat.model.ToolContext(Map.of("requestId", "test")));

    assertThat(resultWithoutSpringContext).isEmpty();
    assertThat(resultWithSpringContext).isEmpty();
    assertThat(receivedContexts).isEmpty();
  }

  @Test
  void springAiManagedModeRequiresToolContextResolver() {
    assertThatThrownBy(
            () -> new ToolConverter(new ObjectMapper(), ToolExecutionMode.SPRING_AI_MANAGED, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AdkToolContextResolver");
  }

  @Test
  void springAiManagedCallbackExecutesToolWithResolvedAdkContext() {
    FunctionDeclaration function =
        FunctionDeclaration.builder()
            .name("context_tool")
            .description("A tool that requires ADK execution context")
            .parametersJsonSchema(
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string"))))
            .build();
    AtomicReference<ToolContext> receivedAdkContext = new AtomicReference<>();
    AtomicReference<org.springframework.ai.chat.model.ToolContext> receivedSpringAiContext =
        new AtomicReference<>();
    AtomicReference<Map<String, Object>> receivedArguments = new AtomicReference<>();
    ToolContext resolvedContext = org.mockito.Mockito.mock(ToolContext.class);
    BaseTool contextTool =
        new BaseTool("context_tool", "A tool that requires ADK execution context") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(function);
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            receivedAdkContext.set(toolContext);
            return Single.just(Map.of("echo", args.get("value")));
          }
        };
    ToolConverter executableConverter =
        new ToolConverter(
            new ObjectMapper(),
            ToolExecutionMode.SPRING_AI_MANAGED,
            (tool, arguments, springAiContext) -> {
              receivedArguments.set(arguments);
              receivedSpringAiContext.set(springAiContext);
              return resolvedContext;
            });
    ToolCallback callback =
        executableConverter.convertToSpringAiTools(Map.of(contextTool.name(), contextTool)).get(0);
    org.springframework.ai.chat.model.ToolContext springAiContext =
        new org.springframework.ai.chat.model.ToolContext(Map.of("requestId", "test"));

    String result = callback.call("{\"wrapper\":{\"value\":\"hello\"}}", springAiContext);

    assertThat(result).contains("\"echo\":\"hello\"");
    assertThat(receivedArguments.get()).containsEntry("value", "hello");
    assertThat(receivedSpringAiContext.get()).isSameAs(springAiContext);
    assertThat(receivedAdkContext.get()).isSameAs(resolvedContext);
  }

  @Test
  void springAiManagedCallbackRejectsNullResolvedContext() {
    FunctionDeclaration function =
        FunctionDeclaration.builder().name("context_tool").description("context tool").build();
    BaseTool contextTool =
        new BaseTool("context_tool", "context tool") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(function);
          }
        };
    ToolConverter executableConverter =
        new ToolConverter(
            new ObjectMapper(), ToolExecutionMode.SPRING_AI_MANAGED, (tool, args, context) -> null);
    ToolCallback callback =
        executableConverter.convertToSpringAiTools(Map.of(contextTool.name(), contextTool)).get(0);

    assertThatThrownBy(() -> callback.call("{}"))
        .hasRootCauseInstanceOf(NullPointerException.class)
        .hasRootCauseMessage("AdkToolContextResolver returned null for tool context_tool");
  }
}
