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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  void testConvertSchemaToSpringAiPreservesEnumAndItems() {
    // Regression: convertSchemaToSpringAi used to silently drop "enum" and "items",
    // degrading enum parameters to free-form text and losing array element schemas.
    Schema enumParam =
        Schema.builder()
            .type("STRING")
            .description("Report detail level")
            .enum_("basic", "detailed")
            .build();
    Schema itemsSchema = Schema.builder().type("STRING").enum_("cardio", "dental").build();
    Schema arrayParam =
        Schema.builder().type("ARRAY").description("Included services").items(itemsSchema).build();
    Schema objectSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                Map.of(
                    "level", enumParam,
                    "services", arrayParam))
            .required(List.of("level"))
            .build();

    Map<String, Object> converted = toolConverter.convertSchemaToSpringAi(objectSchema);

    Map<String, Object> properties = asMap(converted.get("properties"));

    Map<String, Object> convertedEnum = asMap(properties.get("level"));
    assertThat(convertedEnum).containsEntry("type", "string");
    assertThat(convertedEnum).containsEntry("enum", List.of("basic", "detailed"));

    Map<String, Object> convertedArray = asMap(properties.get("services"));
    assertThat(convertedArray).containsEntry("type", "array");
    Map<String, Object> convertedItems = asMap(convertedArray.get("items"));
    assertThat(convertedItems).containsEntry("type", "string");
    assertThat(convertedItems).containsEntry("enum", List.of("cardio", "dental"));
  }

  @Test
  void testConvertSchemaToSpringAiRecursesIntoObjectItemsProperties() {
    // Array-of-objects parameters (the shape FunctionTool generates for List<>) must keep
    // the properties of each item: recursion has to flow through properties inside items.
    Schema itemObject =
        Schema.builder()
            .type("OBJECT")
            .properties(
                Map.of(
                    "name", Schema.builder().type("STRING").build(),
                    "level", Schema.builder().type("STRING").enum_("basic", "detailed").build()))
            .required(List.of("name"))
            .build();
    Schema arrayOfObjects =
        Schema.builder().type("ARRAY").description("Line items").items(itemObject).build();

    Map<String, Object> converted = toolConverter.convertSchemaToSpringAi(arrayOfObjects);

    assertThat(converted).containsEntry("type", "array");
    Map<String, Object> items = asMap(converted.get("items"));
    assertThat(items).containsEntry("type", "object");
    Map<String, Object> itemProperties = asMap(items.get("properties"));
    Map<String, Object> itemEnum = asMap(itemProperties.get("level"));
    assertThat(itemEnum).containsEntry("enum", List.of("basic", "detailed"));
    assertThat(items).containsEntry("required", List.of("name"));
  }

  @Test
  void testConvertToSpringAiToolsOutputSchemaContainsEnumAndItems() throws Exception {
    // End-to-end over the user-visible output (the repro from the issue): the serialized
    // inputSchema JSON of a converted tool must contain enum and items.
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("reportTool")
            .description("Generates a report")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        Map.of(
                            "level",
                            Schema.builder().type("STRING").enum_("basic", "detailed").build(),
                            "services",
                            Schema.builder()
                                .type("ARRAY")
                                .items(
                                    Schema.builder()
                                        .type("STRING")
                                        .enum_("cardio", "dental")
                                        .build())
                                .build()))
                    .required(List.of("level"))
                    .build())
            .build();
    BaseTool tool =
        new BaseTool("reportTool", "Generates a report") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(declaration);
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(Map.of());
          }
        };

    List<ToolCallback> callbacks = toolConverter.convertToSpringAiTools(Map.of("reportTool", tool));

    assertThat(callbacks).hasSize(1);
    String inputSchema = callbacks.get(0).getToolDefinition().inputSchema();
    Map<String, Object> schemaJson =
        new ObjectMapper().readValue(inputSchema, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> properties = asMap(schemaJson.get("properties"));
    assertThat(asMap(properties.get("level"))).containsEntry("enum", List.of("basic", "detailed"));
    Map<String, Object> services = asMap(properties.get("services"));
    assertThat(services).containsEntry("type", "array");
    assertThat(asMap(services.get("items"))).containsEntry("enum", List.of("cardio", "dental"));
  }

  @Test
  void testConvertSchemaToSpringAiSkipsEmptyEnum() {
    // "enum": [] is unsatisfiable under JSON Schema semantics (no valid value exists);
    // an empty enum must be skipped rather than forwarded.
    Schema emptyEnumSchema = Schema.builder().type("STRING").enum_(List.of()).build();

    Map<String, Object> converted = toolConverter.convertSchemaToSpringAi(emptyEnumSchema);

    assertThat(converted).containsEntry("type", "string");
    assertThat(converted).doesNotContainKey("enum");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
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
  void testToolCallbackDirectInvocationUsesNullAdkContext() {
    RecordingTool tool = new RecordingTool();
    ToolCallback callback = toolConverter.convertToSpringAiTools(Map.of(tool.name(), tool)).get(0);

    String result = callback.call("{\"location\":\"Paris\"}");

    assertThat(result).contains("sunny");
    assertThat(tool.invocationCount()).isEqualTo(1);
    assertThat(tool.context()).isNull();
    assertThat(tool.arguments()).containsEntry("location", "Paris");
  }

  @Test
  void testToolCallbackWithEmptySpringAiContextUsesNullAdkContext() {
    RecordingTool tool = new RecordingTool();
    ToolCallback callback = toolConverter.convertToSpringAiTools(Map.of(tool.name(), tool)).get(0);

    String result =
        callback.call(
            "{\"location\":\"Paris\"}",
            new org.springframework.ai.chat.model.ToolContext(Map.of()));

    assertThat(result).contains("sunny");
    assertThat(tool.invocationCount()).isEqualTo(1);
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
            Map.of(ToolConverter.ADK_TOOL_CONTEXT_KEY, adkToolContext));

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
            Map.of(ToolConverter.ADK_TOOL_CONTEXT_KEY, "not an ADK ToolContext"));

    assertThatThrownBy(() -> callback.call("{\"location\":\"Paris\"}", springAiToolContext))
        .isInstanceOf(ToolExecutionException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage(
            "Spring AI tool context entry 'adk_tool_context' must be an ADK ToolContext, but was java.lang.String");
    assertThat(tool.invocationCount()).isZero();
  }

  @Test
  void testToolCallbackWrapsResultSerializationFailure() {
    RecordingTool tool = new RecordingTool(Map.of("unserializable", new Object()));
    ToolCallback callback = toolConverter.convertToSpringAiTools(Map.of(tool.name(), tool)).get(0);

    assertThatThrownBy(() -> callback.call("{\"location\":\"Paris\"}"))
        .isInstanceOf(ToolExecutionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(tool.invocationCount()).isEqualTo(1);
  }

  private static final class RecordingTool extends BaseTool {
    private final FunctionDeclaration declaration;
    private final Map<String, Object> result;
    private final AtomicInteger invocationCount = new AtomicInteger();
    private final AtomicReference<Map<String, Object>> arguments = new AtomicReference<>();
    private final AtomicReference<ToolContext> context = new AtomicReference<>();

    private RecordingTool() {
      this(Map.of("forecast", "sunny"));
    }

    private RecordingTool(Map<String, Object> result) {
      super("get_weather", "Get weather for a location");
      this.result = result;
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
      return Single.just(result);
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
