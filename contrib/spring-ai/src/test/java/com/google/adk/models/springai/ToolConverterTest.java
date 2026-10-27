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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.EventActions;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

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
            "Spring AI tool context entry 'adk_tool_context' must be an ADK ToolContext, but was"
                + " java.lang.String");
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

  @Test
  void testConvertSchemaFromSpringAiAllTypes() {
    assertThat(
            toolConverter
                .convertSchemaFromSpringAi(
                    Map.of("type", "string", "description", "A string parameter"))
                .type())
        .contains(new Type(Type.Known.STRING));
    assertThat(toolConverter.convertSchemaFromSpringAi(Map.of("type", "number")).type())
        .contains(new Type(Type.Known.NUMBER));
    assertThat(toolConverter.convertSchemaFromSpringAi(Map.of("type", "integer")).type())
        .contains(new Type(Type.Known.INTEGER));
    assertThat(toolConverter.convertSchemaFromSpringAi(Map.of("type", "boolean")).type())
        .contains(new Type(Type.Known.BOOLEAN));
    assertThat(toolConverter.convertSchemaFromSpringAi(Map.of("type", "array")).type())
        .contains(new Type(Type.Known.ARRAY));
    assertThat(toolConverter.convertSchemaFromSpringAi(Map.of("type", "object")).type())
        .contains(new Type(Type.Known.OBJECT));
    assertThat(toolConverter.convertSchemaFromSpringAi(Map.of("type", "unknown_type")).type())
        .contains(new Type(Type.Known.STRING));
  }

  @Test
  void testConvertSchemaFromSpringAiWithObjectEnumAndItems() {
    Map<String, Object> springAiSchema =
        Map.of(
            "type",
            "object",
            "description",
            "Order request",
            "properties",
            Map.of(
                "priority",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "Order priority",
                    "enum",
                    List.of("low", "high")),
                "tags",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of("type", "string", "enum", List.of("fragile", "gift"))),
                "emptyEnum",
                Map.of("type", "string", "enum", List.of()),
                "code",
                Map.of("type", "integer", "enum", List.of(1, 2, 3))),
            "required",
            List.of("priority"));

    Schema schema = toolConverter.convertSchemaFromSpringAi(springAiSchema);

    assertThat(schema.type()).contains(new Type(Type.Known.OBJECT));
    assertThat(schema.description()).contains("Order request");
    assertThat(schema.required()).contains(List.of("priority"));

    Map<String, Schema> properties = schema.properties().orElseThrow();
    Schema priority = properties.get("priority");
    assertThat(priority.type()).contains(new Type(Type.Known.STRING));
    assertThat(priority.description()).contains("Order priority");
    assertThat(priority.enum_()).contains(List.of("low", "high"));

    Schema tags = properties.get("tags");
    assertThat(tags.type()).contains(new Type(Type.Known.ARRAY));
    assertThat(tags.items().orElseThrow().type()).contains(new Type(Type.Known.STRING));
    assertThat(tags.items().orElseThrow().enum_()).contains(List.of("fragile", "gift"));

    assertThat(properties.get("emptyEnum").enum_()).isEmpty();
    assertThat(properties.get("code").enum_()).contains(List.of("1", "2", "3"));
  }

  @Test
  void testConvertSchemaFromSpringAiDefaultsArrayItemsAndNarrowsRequired() {
    Map<String, Object> springAiSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("tags", Map.of("type", "array"), "name", Map.of("type", "string")),
            "required",
            List.of("name", "undeclared_field"));

    Schema schema = toolConverter.convertSchemaFromSpringAi(springAiSchema);

    assertThat(schema.required()).contains(List.of("name"));
    Schema tags = schema.properties().orElseThrow().get("tags");
    assertThat(tags.type()).contains(new Type(Type.Known.ARRAY));
    assertThat(tags.items()).isPresent();
    assertThat(tags.items().get().type()).contains(new Type(Type.Known.STRING));
  }

  @Test
  void testConvertSchemaFromSpringAiResolvesRefsFromSpringGeneratedSchema() {
    String jsonSchema = JsonSchemaGenerator.generateForType(RouteRequest.class);

    Map<String, Schema> properties =
        toolConverter.convertSchemaFromSpringAi(jsonSchema).properties().orElseThrow();

    assertThat(properties.get("origin").type()).contains(new Type(Type.Known.OBJECT));
    assertThat(properties.get("origin").properties().orElseThrow())
        .containsOnlyKeys("city", "country");
    assertThat(properties.get("destination").type()).contains(new Type(Type.Known.OBJECT));
    assertThat(properties.get("destination").properties().orElseThrow())
        .containsOnlyKeys("city", "country");
  }

  @Test
  void testConvertSchemaFromSpringAiRefSiblingKeysOverrideDefinition() {
    Map<String, Object> springAiSchema =
        Map.of(
            "definitions",
            Map.of("Code", Map.of("type", "integer", "description", "Original")),
            "$ref",
            "#/definitions/Code",
            "description",
            "Override");

    Schema schema = toolConverter.convertSchemaFromSpringAi(springAiSchema);

    assertThat(schema.type()).contains(new Type(Type.Known.INTEGER));
    assertThat(schema.description()).contains("Override");
  }

  @Test
  void testConvertSchemaFromSpringAiCircularRefBecomesObject() {
    Map<String, Object> springAiSchema =
        Map.of(
            "$defs",
            Map.of(
                "Node",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("next", Map.of("$ref", "#/$defs/Node")))),
            "$ref",
            "#/$defs/Node");

    Schema next =
        toolConverter
            .convertSchemaFromSpringAi(springAiSchema)
            .properties()
            .orElseThrow()
            .get("next");

    assertThat(next.type()).contains(new Type(Type.Known.OBJECT));
    assertThat(next.description()).contains("Circular ref to Node");
  }

  @Test
  void testConvertSchemaFromSpringAiPrefersDefsOverDefinitionsForLastSegmentLookup() {
    Map<String, Object> springAiSchema =
        Map.of(
            "definitions",
            Map.of("Value", Map.of("type", "string")),
            "$defs",
            Map.of("Value", Map.of("type", "integer")),
            "$ref",
            "#/components/Value");

    Schema schema = toolConverter.convertSchemaFromSpringAi(springAiSchema);

    assertThat(schema.type()).contains(new Type(Type.Known.INTEGER));
  }

  @Test
  void testConvertSchemaFromSpringAiResolvesJsonPointerRefs() {
    Map<String, Object> springAiSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "origin",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))),
                "destination",
                Map.of("$ref", "#/properties/origin")));

    Schema destination =
        toolConverter
            .convertSchemaFromSpringAi(springAiSchema)
            .properties()
            .orElseThrow()
            .get("destination");

    assertThat(destination.type()).contains(new Type(Type.Known.OBJECT));
    assertThat(destination.properties().orElseThrow()).containsOnlyKeys("city");
  }

  @Test
  void testConvertSchemaFromSpringAiResolvesRootEscapedAndIndexedPointers() {
    Map<String, Object> springAiSchema =
        Map.of(
            "type",
            "object",
            "$defs",
            Map.of("a/b", Map.of("type", "integer")),
            "anyOf",
            List.of(Map.of("type", "boolean")),
            "properties",
            Map.of(
                "self", Map.of("$ref", "#"),
                "escaped", Map.of("$ref", "#/$defs/a~1b"),
                "indexed", Map.of("$ref", "#/anyOf/0"),
                "anything", Map.of()));

    Map<String, Schema> properties =
        toolConverter.convertSchemaFromSpringAi(springAiSchema).properties().orElseThrow();

    Schema self = properties.get("self");
    assertThat(self.type()).contains(new Type(Type.Known.OBJECT));
    assertThat(self.properties().orElseThrow().get("self").description())
        .contains("Circular ref to #");
    assertThat(properties.get("escaped").type()).contains(new Type(Type.Known.INTEGER));
    assertThat(properties.get("indexed").type()).contains(new Type(Type.Known.BOOLEAN));
    assertThat(properties.get("anything").type()).contains(new Type(Type.Known.OBJECT));
  }

  @Test
  void testUpperCaseArrayTypesGetDefaultItems() {
    Map<String, Object> springAiSchema = Map.of("type", "ARRAY");
    ToolCallback callback =
        FunctionToolCallback.builder("upper_tool", (Map<String, Object> args) -> "{}")
            .description("Upper-case types")
            .inputType(Map.class)
            .inputSchema("{\"type\":\"OBJECT\",\"properties\":{\"tags\":{\"type\":\"ARRAY\"}}}")
            .build();

    Schema schema = toolConverter.convertSchemaFromSpringAi(springAiSchema);
    Map<String, Object> rawProps =
        asMap(
            asMap(
                    toolConverter
                        .convertToAdkTool(callback)
                        .declaration()
                        .orElseThrow()
                        .parametersJsonSchema()
                        .orElseThrow())
                .get("properties"));

    assertThat(schema.items().orElseThrow().type()).contains(new Type(Type.Known.STRING));
    assertThat(asMap(rawProps.get("tags"))).containsEntry("items", Map.of("type", "string"));
  }

  @Test
  void testConvertToAdkToolNormalizesAdditionalPropertiesSchema() {
    String inputSchema =
        """
        {
          "type": "object",
          "properties": {
            "labels": {
              "type": "object",
              "additionalProperties": {
                "type": "object",
                "properties": {"values": {"type": "array"}},
                "required": ["values", "ghost"]
              }
            }
          }
        }
        """;
    ToolCallback callback =
        FunctionToolCallback.builder("label_tool", (Map<String, Object> args) -> "{}")
            .description("Label tool")
            .inputType(Map.class)
            .inputSchema(inputSchema)
            .build();

    Map<String, Object> normalized =
        asMap(
            toolConverter
                .convertToAdkTool(callback)
                .declaration()
                .orElseThrow()
                .parametersJsonSchema()
                .orElseThrow());

    Map<String, Object> valueSchema =
        asMap(asMap(asMap(normalized.get("properties")).get("labels")).get("additionalProperties"));
    assertThat(valueSchema).containsEntry("required", List.of("values"));
    assertThat(asMap(asMap(valueSchema.get("properties")).get("values")))
        .containsEntry("items", Map.of("type", "string"));
  }

  @Test
  void testConvertToAdkToolPreservesSpringGeneratedSchemaViaParametersJsonSchema() {
    String jsonSchema = JsonSchemaGenerator.generateForType(RouteRequest.class);
    ToolCallback callback =
        FunctionToolCallback.builder("route", (Map<String, Object> args) -> "{}")
            .description("Route tool")
            .inputType(Map.class)
            .inputSchema(jsonSchema)
            .build();

    BaseTool adkTool = toolConverter.convertToAdkTool(callback);

    FunctionDeclaration declaration = adkTool.declaration().orElseThrow();
    assertThat(declaration.parameters()).isEmpty();
    assertThat(declaration.parametersJsonSchema()).isPresent();
    Map<String, Object> rawSchema = asMap(declaration.parametersJsonSchema().get());
    assertThat(rawSchema).containsEntry("type", "object");
    assertThat(asMap(rawSchema.get("properties"))).containsKeys("origin", "destination");
  }

  @Test
  void testConvertToAdkToolNormalizesMissingArrayItemsAndRequiredInParametersJsonSchema() {
    String inputSchema =
        """
        {
          "type": "object",
          "$defs": {
            "SubItem": {
              "type": "object",
              "properties": {
                "nestedTags": {"type": "array"}
              },
              "required": ["nestedTags", "ghostField"]
            }
          },
          "definitions": {
            "Legacy": {"type": "array"}
          },
          "properties": {
            "tags": {"type": "array"},
            "choice": {
              "anyOf": [
                {"type": "array"},
                {"type": "string"}
              ]
            },
            "either": {"oneOf": [{"type": "array"}]},
            "both": {"allOf": [{"type": "array"}]},
            "noProps": {"type": "object", "required": ["x"]},
            "allGhosts": {"type": "object", "properties": {}, "required": ["x"]},
            "viaRef": {"$ref": "#/$defs/SubItem", "required": ["nestedTags"]}
          },
          "required": ["tags", "undeclared"]
        }
        """;
    ToolCallback callback =
        FunctionToolCallback.builder("tag_tool", (Map<String, Object> args) -> "{}")
            .description("Tag tool")
            .inputType(Map.class)
            .inputSchema(inputSchema)
            .build();

    BaseTool adkTool = toolConverter.convertToAdkTool(callback);

    Map<String, Object> normalized =
        asMap(adkTool.declaration().orElseThrow().parametersJsonSchema().orElseThrow());
    assertThat(normalized).containsEntry("required", List.of("tags"));
    Map<String, Object> props = asMap(normalized.get("properties"));
    assertThat(asMap(props.get("tags"))).containsEntry("items", Map.of("type", "string"));

    List<?> anyOfList = (List<?>) asMap(props.get("choice")).get("anyOf");
    assertThat(asMap(anyOfList.get(0))).containsEntry("items", Map.of("type", "string"));

    Map<String, Object> subItem = asMap(asMap(normalized.get("$defs")).get("SubItem"));
    assertThat(subItem).containsEntry("required", List.of("nestedTags"));
    assertThat(asMap(asMap(subItem.get("properties")).get("nestedTags")))
        .containsEntry("items", Map.of("type", "string"));

    assertThat(asMap(asMap(normalized.get("definitions")).get("Legacy")))
        .containsEntry("items", Map.of("type", "string"));
    List<?> oneOfList = (List<?>) asMap(props.get("either")).get("oneOf");
    assertThat(asMap(oneOfList.get(0))).containsEntry("items", Map.of("type", "string"));
    List<?> allOfList = (List<?>) asMap(props.get("both")).get("allOf");
    assertThat(asMap(allOfList.get(0))).containsEntry("items", Map.of("type", "string"));
    assertThat(asMap(props.get("noProps"))).doesNotContainKey("required");
    assertThat(asMap(props.get("allGhosts"))).doesNotContainKey("required");
    assertThat(asMap(props.get("viaRef"))).containsEntry("required", List.of("nestedTags"));
  }

  @Test
  void testNullableTypeListsDefaultArrayItemsAndMapToNullableSchema() {
    String inputSchema =
        """
        {
          "type": "object",
          "properties": {
            "tags": {"type": ["array", "null"]},
            "count": {"type": ["integer", "null"]},
            "mixed": {"type": ["string", "array"]},
            "anything": {"type": "array", "items": true},
            "nothing": {"type": "null"}
          }
        }
        """;
    ToolCallback callback =
        FunctionToolCallback.builder("nullable_tool", (Map<String, Object> args) -> "{}")
            .description("Nullable tool")
            .inputType(Map.class)
            .inputSchema(inputSchema)
            .build();

    Map<String, Object> rawProps =
        asMap(
            asMap(
                    toolConverter
                        .convertToAdkTool(callback)
                        .declaration()
                        .orElseThrow()
                        .parametersJsonSchema()
                        .orElseThrow())
                .get("properties"));
    Map<String, Schema> props =
        toolConverter.convertSchemaFromSpringAi(inputSchema).properties().orElseThrow();

    assertThat(asMap(rawProps.get("tags"))).containsEntry("items", Map.of("type", "string"));
    assertThat(props.get("tags").type()).contains(new Type(Type.Known.ARRAY));
    assertThat(props.get("tags").nullable()).contains(true);
    assertThat(props.get("tags").items().orElseThrow().type())
        .contains(new Type(Type.Known.STRING));
    assertThat(props.get("count").type()).contains(new Type(Type.Known.INTEGER));
    assertThat(props.get("count").nullable()).contains(true);
    assertThat(props.get("mixed").type()).contains(new Type(Type.Known.ARRAY));
    assertThat(asMap(rawProps.get("anything"))).containsEntry("items", true);
    assertThat(props.get("nothing").type()).contains(new Type(Type.Known.OBJECT));
    assertThat(props.get("nothing").nullable()).contains(true);
  }

  @Test
  void testConvertSchemaFromSpringAiJsonStringEdgeCases() {
    assertThat(toolConverter.convertSchemaFromSpringAi((String) null).type())
        .contains(new Type(Type.Known.OBJECT));
    assertThat(toolConverter.convertSchemaFromSpringAi("   ").type())
        .contains(new Type(Type.Known.OBJECT));
    assertThat(toolConverter.convertSchemaFromSpringAi("null").type())
        .contains(new Type(Type.Known.OBJECT));
    assertThatThrownBy(() -> toolConverter.convertSchemaFromSpringAi("not valid json{"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testSchemaRoundTripSymmetry() {
    Schema originalAdkSchema =
        Schema.builder()
            .type("OBJECT")
            .description("Order filter")
            .properties(
                Map.of(
                    "query",
                    Schema.builder().type("STRING").description("Search query").build(),
                    "limit",
                    Schema.builder().type("INTEGER").build(),
                    "minScore",
                    Schema.builder().type("NUMBER").build(),
                    "active",
                    Schema.builder().type("BOOLEAN").build(),
                    "status",
                    Schema.builder().type("STRING").enum_("open", "closed").build(),
                    "items",
                    Schema.builder()
                        .type("ARRAY")
                        .items(
                            Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of("id", Schema.builder().type("STRING").build()))
                                .required(List.of("id"))
                                .build())
                        .build()))
            .required(List.of("query"))
            .build();

    Map<String, Object> springAiMap = toolConverter.convertSchemaToSpringAi(originalAdkSchema);
    Schema roundTrippedAdkSchema = toolConverter.convertSchemaFromSpringAi(springAiMap);
    Map<String, Object> roundTrippedSpringAiMap =
        toolConverter.convertSchemaToSpringAi(roundTrippedAdkSchema);

    assertThat(roundTrippedAdkSchema).isEqualTo(originalAdkSchema);
    assertThat(roundTrippedSpringAiMap).isEqualTo(springAiMap);
  }

  @Test
  void testConvertToSpringAiToolSingleAndZeroArgTool() {
    FunctionDeclaration zeroArgDeclaration =
        FunctionDeclaration.builder().name("ping").description("Ping tool").build();
    BaseTool zeroArgTool =
        new BaseTool("ping", "Ping tool") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(zeroArgDeclaration);
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(Map.of("pong", true));
          }
        };
    BaseTool undeclaredTool =
        new BaseTool("undeclared", "No declaration") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.empty();
          }
        };

    Optional<ToolCallback> converted = toolConverter.convertToSpringAiTool(zeroArgTool);
    assertThat(converted).isPresent();
    assertThat(converted.get().getToolDefinition().name()).isEqualTo("ping");
    assertThat(converted.get().call("{}")).contains("pong");

    assertThat(toolConverter.convertToSpringAiTool(undeclaredTool)).isEmpty();
    assertThat(
            toolConverter.convertToSpringAiTools(
                Map.of("ping", zeroArgTool, "undeclared", undeclaredTool)))
        .hasSize(1);
  }

  @Test
  void testConvertToAdkToolDeclarationAndExecution() {
    AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();
    Function<Map<String, Object>, String> fn =
        args -> {
          capturedArgs.set(args);
          return "{\"temperature\":22,\"condition\":\"sunny\"}";
        };
    ToolCallback callback =
        FunctionToolCallback.builder("get_weather", fn)
            .description("Get weather for a city")
            .inputType(Map.class)
            .inputSchema(
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"City"
                    + " name\"}},\"required\":[\"city\"]}")
            .build();

    BaseTool adkTool = toolConverter.convertToAdkTool(callback);

    assertThat(adkTool.name()).isEqualTo("get_weather");
    assertThat(adkTool.description()).isEqualTo("Get weather for a city");
    assertThat(adkTool.declaration()).isPresent();

    FunctionDeclaration declaration = adkTool.declaration().get();
    assertThat(declaration.name()).contains("get_weather");
    assertThat(declaration.description()).contains("Get weather for a city");
    Map<String, Object> schemaMap = asMap(declaration.parametersJsonSchema().orElseThrow());
    assertThat(schemaMap).containsEntry("type", "object");
    assertThat(schemaMap).containsEntry("required", List.of("city"));
    assertThat(asMap(asMap(schemaMap.get("properties")).get("city")))
        .containsEntry("type", "string");

    Map<String, Object> result = adkTool.runAsync(Map.of("city", "Paris"), null).blockingGet();

    assertThat(capturedArgs.get()).containsEntry("city", "Paris");
    assertThat(result).containsEntry("temperature", 22).containsEntry("condition", "sunny");
  }

  @Test
  void testConvertToAdkToolPropagatesAdkToolContextAndReturnDirect() {
    AtomicReference<org.springframework.ai.chat.model.ToolContext> capturedContext =
        new AtomicReference<>();
    BiFunction<Map<String, Object>, org.springframework.ai.chat.model.ToolContext, String> fn =
        (args, ctx) -> {
          capturedContext.set(ctx);
          return "{\"ok\":true}";
        };
    ToolCallback callback =
        FunctionToolCallback.builder("direct_tool", fn)
            .description("Direct tool")
            .inputType(Map.class)
            .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
            .build();

    ToolContext adkContext = mock(ToolContext.class);
    EventActions actions = EventActions.builder().build();
    when(adkContext.actions()).thenReturn(actions);

    BaseTool adkTool = toolConverter.convertToAdkTool(callback);
    Map<String, Object> result = adkTool.runAsync(Map.of("k", "v"), adkContext).blockingGet();

    assertThat(result).containsEntry("ok", true);
    assertThat(capturedContext.get()).isNotNull();
    assertThat(capturedContext.get().getContext())
        .containsEntry(ToolConverter.ADK_TOOL_CONTEXT_KEY, adkContext);
    assertThat(actions.skipSummarization()).contains(true);
  }

  @Test
  void testConvertToAdkToolWrapsNonObjectResults() {
    ToolDefinition definition =
        ToolDefinition.builder()
            .name("scalar_tool")
            .description("Returns non-object payloads")
            .inputSchema("{\"type\":\"object\"}")
            .build();

    ToolCallback arrayCallback = mock(ToolCallback.class);
    when(arrayCallback.getToolDefinition()).thenReturn(definition);
    when(arrayCallback.call(any(), any())).thenReturn("[1,2,3]");
    assertThat(toolConverter.convertToAdkTool(arrayCallback).runAsync(Map.of(), null).blockingGet())
        .isEqualTo(Map.of("result", List.of(1, 2, 3)));

    ToolCallback numberCallback = mock(ToolCallback.class);
    when(numberCallback.getToolDefinition()).thenReturn(definition);
    when(numberCallback.call(any(), any())).thenReturn("42");
    assertThat(
            toolConverter.convertToAdkTool(numberCallback).runAsync(Map.of(), null).blockingGet())
        .isEqualTo(Map.of("result", 42));

    ToolCallback rawTextCallback = mock(ToolCallback.class);
    when(rawTextCallback.getToolDefinition()).thenReturn(definition);
    when(rawTextCallback.call(any(), any())).thenReturn("not json{");
    assertThat(
            toolConverter.convertToAdkTool(rawTextCallback).runAsync(Map.of(), null).blockingGet())
        .isEqualTo(Map.of("result", "not json{"));

    ToolCallback numericPrefixCallback = mock(ToolCallback.class);
    when(numericPrefixCallback.getToolDefinition()).thenReturn(definition);
    when(numericPrefixCallback.call(any(), any())).thenReturn("42 is the answer");
    assertThat(
            toolConverter
                .convertToAdkTool(numericPrefixCallback)
                .runAsync(Map.of(), null)
                .blockingGet())
        .isEqualTo(Map.of("result", "42 is the answer"));

    ToolCallback nullJsonCallback = mock(ToolCallback.class);
    when(nullJsonCallback.getToolDefinition()).thenReturn(definition);
    when(nullJsonCallback.call(any(), any())).thenReturn("null");
    assertThat(
            toolConverter.convertToAdkTool(nullJsonCallback).runAsync(Map.of(), null).blockingGet())
        .isEmpty();

    ToolCallback blankCallback = mock(ToolCallback.class);
    when(blankCallback.getToolDefinition()).thenReturn(definition);
    when(blankCallback.call(any(), any())).thenReturn("   ");
    assertThat(toolConverter.convertToAdkTool(blankCallback).runAsync(Map.of(), null).blockingGet())
        .isEmpty();
  }

  @Test
  void testConvertToAdkToolsFromCollectionAndToolsetFromProvider() {
    ToolCallback weather =
        FunctionToolCallback.builder("get_weather", (Map<String, Object> args) -> "{\"temp\":20}")
            .description("Weather")
            .inputType(Map.class)
            .build();
    ToolCallback time =
        FunctionToolCallback.builder(
                "get_time", (Map<String, Object> args) -> "{\"time\":\"12:00\"}")
            .description("Time")
            .inputType(Map.class)
            .build();

    List<BaseTool> fromList = toolConverter.convertToAdkTools(List.of(weather, time));
    assertThat(fromList).extracting(BaseTool::name).containsExactly("get_weather", "get_time");

    ToolCallbackProvider provider = ToolCallbackProvider.from(weather, time);
    BaseToolset toolset = toolConverter.convertToAdkToolset(provider);
    assertThat(toolset).isInstanceOf(SpringAiToolset.class);
    assertThat(toolset.getTools(null).toList().blockingGet())
        .extracting(BaseTool::name)
        .containsExactly("get_weather", "get_time");
  }

  @Test
  void testRoundTripAdkToolToSpringAiAndBackToAdk() {
    RecordingTool originalTool = new RecordingTool(Map.of("forecast", "sunny", "tempC", 24));
    ToolContext adkContext = mock(ToolContext.class);

    List<ToolCallback> springCallbacks =
        toolConverter.convertToSpringAiTools(Map.of(originalTool.name(), originalTool));
    List<BaseTool> roundTrippedTools = toolConverter.convertToAdkTools(springCallbacks);

    assertThat(roundTrippedTools).hasSize(1);
    BaseTool roundTrippedTool = roundTrippedTools.get(0);
    assertThat(roundTrippedTool.name()).isEqualTo(originalTool.name());
    assertThat(roundTrippedTool.description()).isEqualTo(originalTool.description());
    assertThat(roundTrippedTool.declaration().orElseThrow().parametersJsonSchema())
        .contains(
            toolConverter.convertSchemaToSpringAi(
                originalTool.declaration().orElseThrow().parameters().orElseThrow()));

    Map<String, Object> result =
        roundTrippedTool.runAsync(Map.of("location", "Paris"), adkContext).blockingGet();

    assertThat(result).isEqualTo(Map.of("forecast", "sunny", "tempC", 24));
    assertThat(originalTool.invocationCount()).isEqualTo(1);
    assertThat(originalTool.arguments()).isEqualTo(Map.of("location", "Paris"));
    assertThat(originalTool.context()).isSameAs(adkContext);
  }

  @Test
  void testRoundTripSpringAiToolToAdkAndBackToSpringAi() throws Exception {
    AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();
    AtomicReference<org.springframework.ai.chat.model.ToolContext> capturedContext =
        new AtomicReference<>();
    BiFunction<Map<String, Object>, org.springframework.ai.chat.model.ToolContext, String> fn =
        (args, ctx) -> {
          capturedArgs.set(args);
          capturedContext.set(ctx);
          return "{\"forecast\":\"rainy\"}";
        };
    ToolCallback originalCallback =
        FunctionToolCallback.builder("get_weather", fn)
            .description("Get weather for a location")
            .inputType(Map.class)
            .inputSchema(
                "{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}},\"required\":[\"location\"]}")
            .build();

    BaseTool adkTool = toolConverter.convertToAdkTool(originalCallback);
    List<ToolCallback> roundTrippedCallbacks =
        toolConverter.convertToSpringAiTools(Map.of(adkTool.name(), adkTool));

    assertThat(roundTrippedCallbacks).hasSize(1);
    ToolCallback roundTrippedCallback = roundTrippedCallbacks.get(0);
    assertThat(roundTrippedCallback.getToolDefinition().name()).isEqualTo("get_weather");
    assertThat(roundTrippedCallback.getToolDefinition().description())
        .isEqualTo("Get weather for a location");

    ToolContext adkContext = mock(ToolContext.class);
    org.springframework.ai.chat.model.ToolContext springContext =
        new org.springframework.ai.chat.model.ToolContext(
            Map.of(ToolConverter.ADK_TOOL_CONTEXT_KEY, adkContext));

    String jsonResult = roundTrippedCallback.call("{\"location\":\"London\"}", springContext);
    Map<String, Object> parsedResult =
        new ObjectMapper().readValue(jsonResult, new TypeReference<Map<String, Object>>() {});

    assertThat(parsedResult).isEqualTo(Map.of("forecast", "rainy"));
    assertThat(capturedArgs.get()).containsEntry("location", "London");
    assertThat(capturedContext.get().getContext())
        .containsEntry(ToolConverter.ADK_TOOL_CONTEXT_KEY, adkContext);
  }

  private record Location(String city, String country) {}

  private record RouteRequest(Location origin, Location destination) {}

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
