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
import static org.mockito.Mockito.mock;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

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
  }

  @Test
  void testToolCallbackWithoutAdkContextKeepsExistingNullContextBehavior() {
    RecordingTool tool = new RecordingTool();
    ToolCallback callback = toolConverter.convertToSpringAiTools(Map.of(tool.name(), tool)).get(0);

    String directResult = callback.call("{\"location\":\"Paris\"}");
    String emptyContextResult =
        callback.call(
            "{\"location\":\"Paris\"}",
            new org.springframework.ai.chat.model.ToolContext(Map.of()));

    assertThat(directResult).contains("sunny");
    assertThat(emptyContextResult).contains("sunny");
    assertThat(tool.invocationCount()).isEqualTo(2);
    assertThat(tool.context()).isNull();
    assertThat(tool.arguments()).containsEntry("location", "Paris");
  }

  @Test
  void testToolCallbackUsesAdkContextFromSpringAiToolContext() {
    RecordingTool tool = new RecordingTool();
    ToolCallback callback = toolConverter.convertToSpringAiTools(Map.of(tool.name(), tool)).get(0);
    ToolContext adkToolContext = mock(ToolContext.class);
    org.springframework.ai.chat.model.ToolContext springAiToolContext =
        new org.springframework.ai.chat.model.ToolContext(
            Map.of(ToolConverter.ADK_TOOL_CONTEXT, adkToolContext));

    String result = callback.call("{\"location\":\"Paris\"}", springAiToolContext);

    assertThat(result).contains("sunny");
    assertThat(tool.invocationCount()).isEqualTo(1);
    assertThat(tool.context()).isSameAs(adkToolContext);
    assertThat(tool.arguments()).containsEntry("location", "Paris");
  }

  @Test
  void testToolCallbackRejectsWrongAdkContextType() {
    RecordingTool tool = new RecordingTool();
    ToolCallback callback = toolConverter.convertToSpringAiTools(Map.of(tool.name(), tool)).get(0);
    org.springframework.ai.chat.model.ToolContext springAiToolContext =
        new org.springframework.ai.chat.model.ToolContext(
            Map.of(ToolConverter.ADK_TOOL_CONTEXT, "not an ADK ToolContext"));

    assertThatThrownBy(() -> callback.call("{\"location\":\"Paris\"}", springAiToolContext))
        .isInstanceOf(ToolExecutionException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage(
            "Spring AI tool context entry 'adk_tool_context' must be an ADK ToolContext, but was java.lang.String");
    assertThat(tool.invocationCount()).isZero();
  }

  private static final class RecordingTool extends BaseTool {
    private final FunctionDeclaration declaration;
    private final AtomicInteger invocationCount = new AtomicInteger();
    private final AtomicReference<Map<String, Object>> arguments = new AtomicReference<>();
    private final AtomicReference<ToolContext> context = new AtomicReference<>();

    private RecordingTool() {
      super("get_weather", "Get weather for a location");
      this.declaration =
          FunctionDeclaration.builder()
              .name(name())
              .description(description())
              .parameters(
                  Schema.builder()
                      .type("OBJECT")
                      .properties(Map.of("location", Schema.builder().type("STRING").build()))
                      .required(List.of("location"))
                      .build())
              .build();
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(declaration);
    }

    @Override
    public Single<Map<String, Object>> runAsync(
        Map<String, Object> arguments, ToolContext toolContext) {
      invocationCount.incrementAndGet();
      this.arguments.set(arguments);
      context.set(toolContext);
      return Single.just(Map.of("forecast", "sunny"));
    }

    private int invocationCount() {
      return invocationCount.get();
    }

    private Map<String, Object> arguments() {
      return arguments.get();
    }

    private ToolContext context() {
      return context.get();
    }
  }
}
