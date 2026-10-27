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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Converts between ADK and Spring AI tool/function formats in both directions.
 *
 * <p>This converter handles the translation between ADK's {@link BaseTool}/{@link
 * FunctionDeclaration} format and Spring AI {@link ToolCallback} representations, including schema
 * conversion and {@link ToolContext} propagation.
 */
public class ToolConverter {

  private static final Logger logger = LoggerFactory.getLogger(ToolConverter.class);
  // FAIL_ON_TRAILING_TOKENS keeps a text result like "42 is the answer" from parsing as 42.
  private static final ObjectMapper objectMapper =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  /** Key for passing an ADK {@link ToolContext} through Spring AI's tool context map. */
  public static final String ADK_TOOL_CONTEXT_KEY = "adk_tool_context";

  /**
   * Creates a tool registry from ADK tools for internal tracking.
   *
   * <p>This method provides a way to track available tools, though Spring AI tool calling
   * integration will be enhanced in subsequent iterations.
   *
   * @param tools Map of ADK tools to process
   * @return Map of tool names to their metadata
   */
  public Map<String, ToolMetadata> createToolRegistry(Map<String, BaseTool> tools) {
    Map<String, ToolMetadata> registry = new HashMap<>();

    for (BaseTool tool : tools.values()) {
      if (tool.declaration().isPresent()) {
        FunctionDeclaration declaration = tool.declaration().get();
        ToolMetadata metadata = new ToolMetadata(tool.name(), tool.description(), declaration);
        registry.put(tool.name(), metadata);
      }
    }

    return registry;
  }

  /**
   * Converts ADK Schema to Spring AI compatible parameter schema.
   *
   * <p>Maps the {@code type}, {@code description}, {@code properties}, {@code required}, {@code
   * enum} and {@code items} keywords ({@code properties} values and {@code items} recursively).
   * Other JSON-Schema keywords (e.g. {@code nullable}, {@code anyOf}, {@code format}) are not
   * carried over yet.
   *
   * @param schema The ADK schema to convert
   * @return A Map representing the Spring AI compatible schema
   */
  public Map<String, Object> convertSchemaToSpringAi(Schema schema) {
    Map<String, Object> springAiSchema = new HashMap<>();

    if (schema.type().isPresent()) {
      Type type = schema.type().get();
      springAiSchema.put("type", convertTypeToString(type));
    }

    schema.description().ifPresent(desc -> springAiSchema.put("description", desc));

    if (schema.properties().isPresent()) {
      Map<String, Object> properties = new HashMap<>();
      schema
          .properties()
          .get()
          .forEach((key, value) -> properties.put(key, convertSchemaToSpringAi(value)));
      springAiSchema.put("properties", properties);
    }

    schema.required().ifPresent(required -> springAiSchema.put("required", required));

    // Preserve enum constraints and array item schemas; skip empty enums (unsatisfiable).
    schema
        .enum_()
        .filter(values -> !values.isEmpty())
        .ifPresent(values -> springAiSchema.put("enum", values));
    schema
        .items()
        .ifPresent(itemSchema -> springAiSchema.put("items", convertSchemaToSpringAi(itemSchema)));

    return springAiSchema;
  }

  /**
   * Converts a Spring AI parameter schema map to an ADK {@link Schema}.
   *
   * <p>Maps {@code type}, {@code description}, {@code properties}, {@code required}, {@code enum},
   * and {@code items} recursively, defaulting missing array {@code items} to {@code STRING} and
   * narrowing {@code required} to declared {@code properties}. As in Python ADK, a {@code $ref} is
   * resolved as a JSON pointer into the root schema, or else by its last segment in the root {@code
   * $defs} or {@code definitions}; keys next to the {@code $ref} override the referenced schema's,
   * and a circular reference becomes an object schema.
   *
   * @param springAiSchema The Spring AI schema map to convert
   * @return The converted ADK {@link Schema}
   */
  public Schema convertSchemaFromSpringAi(Map<String, Object> springAiSchema) {
    Objects.requireNonNull(springAiSchema, "springAiSchema cannot be null");
    return convertSchemaMap(springAiSchema, springAiSchema, ImmutableSet.of());
  }

  /**
   * Parses a Spring AI JSON Schema string into an ADK {@link Schema}.
   *
   * @param jsonSchema The JSON Schema string (e.g. from {@link ToolDefinition#inputSchema()})
   * @return The converted ADK {@link Schema}, or an empty object schema if {@code jsonSchema} is
   *     null or blank
   * @throws IllegalArgumentException if {@code jsonSchema} is not valid JSON
   */
  public Schema convertSchemaFromSpringAi(@Nullable String jsonSchema) {
    if (jsonSchema == null || jsonSchema.isBlank()) {
      return Schema.builder().type(Type.Known.OBJECT).build();
    }
    Map<String, Object> schemaMap = parseJsonSchemaMap(jsonSchema);
    if (schemaMap.isEmpty()) {
      return Schema.builder().type(Type.Known.OBJECT).build();
    }
    return convertSchemaFromSpringAi(schemaMap);
  }

  private Schema convertSchemaMap(
      Map<String, Object> springAiSchema,
      Map<String, Object> rootSchema,
      ImmutableSet<String> expandingRefs) {
    if (springAiSchema.isEmpty()) {
      // An empty schema accepts anything; Python ADK maps it to an object schema.
      return Schema.builder().type(Type.Known.OBJECT).build();
    }
    if (springAiSchema.get("$ref") instanceof String ref) {
      if (expandingRefs.contains(ref)) {
        return Schema.builder()
            .type(Type.Known.OBJECT)
            .description("Circular ref to " + ref.substring(ref.lastIndexOf('/') + 1))
            .build();
      }
      Map<String, Object> referenced = resolveRef(ref, rootSchema);
      if (referenced != null) {
        Map<String, Object> resolved = new LinkedHashMap<>(referenced);
        for (Map.Entry<String, Object> entry : springAiSchema.entrySet()) {
          if (!entry.getKey().equals("$ref")) {
            resolved.put(entry.getKey(), entry.getValue());
          }
        }
        return convertSchemaMap(
            resolved,
            rootSchema,
            ImmutableSet.<String>builder().addAll(expandingRefs).add(ref).build());
      }
    }
    Schema.Builder schemaBuilder = Schema.builder();

    // As in Python ADK, a type list such as ["integer", "null"] maps to a nullable single type,
    // preferring "array", and a "null"-only type maps to a nullable object.
    List<String> types = new ArrayList<>();
    if (springAiSchema.get("type") instanceof String typeStr && !typeStr.isEmpty()) {
      types.add(typeStr.toLowerCase(Locale.ROOT));
    } else if (springAiSchema.get("type") instanceof List<?> typeList) {
      typeList.stream()
          .filter(String.class::isInstance)
          .map(t -> ((String) t).toLowerCase(Locale.ROOT))
          .forEach(types::add);
    }
    Type resolvedType = null;
    if (!types.isEmpty()) {
      if (types.remove("null")) {
        schemaBuilder.nullable(true);
      }
      String typeStr =
          types.contains("array") ? "array" : types.stream().findFirst().orElse("object");
      resolvedType = convertStringToType(typeStr);
      schemaBuilder.type(resolvedType);
    }

    if (springAiSchema.get("description") instanceof String desc) {
      schemaBuilder.description(desc);
    }

    Map<String, Schema> properties = null;
    if (springAiSchema.get("properties") instanceof Map<?, ?> rawProperties) {
      properties = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
        if (entry.getKey() instanceof String key
            && entry.getValue() instanceof Map<?, ?> propSchema) {
          properties.put(key, convertSchemaMap(castToMap(propSchema), rootSchema, expandingRefs));
        }
      }
      schemaBuilder.properties(properties);
    }

    if (springAiSchema.get("required") instanceof List<?> rawRequired && properties != null) {
      List<String> required = new ArrayList<>();
      for (Object item : rawRequired) {
        if (item instanceof String reqStr && properties.containsKey(reqStr)) {
          required.add(reqStr);
        }
      }
      if (!required.isEmpty()) {
        schemaBuilder.required(required);
      }
    }

    if (springAiSchema.get("enum") instanceof List<?> rawEnum) {
      List<String> enumValues = new ArrayList<>();
      for (Object item : rawEnum) {
        if (item != null) {
          enumValues.add(String.valueOf(item));
        }
      }
      if (!enumValues.isEmpty()) {
        schemaBuilder.enum_(enumValues);
      }
    }

    if (springAiSchema.get("items") instanceof Map<?, ?> rawItems) {
      schemaBuilder.items(convertSchemaMap(castToMap(rawItems), rootSchema, expandingRefs));
    } else if (resolvedType != null && resolvedType.knownEnum() == Type.Known.ARRAY) {
      schemaBuilder.items(Schema.builder().type(Type.Known.STRING).build());
    }

    return schemaBuilder.build();
  }

  /**
   * Resolves {@code ref} as a JSON pointer into {@code rootSchema}, or else by its last segment in
   * the root {@code $defs} or {@code definitions}, or returns null if it names no object schema.
   */
  private static @Nullable Map<String, Object> resolveRef(
      String ref, Map<String, Object> rootSchema) {
    if (ref.equals("#")) {
      return rootSchema;
    }
    if (ref.startsWith("#/")) {
      Object current = rootSchema;
      for (String segment : ref.substring(2).split("/", -1)) {
        String token = segment.replace("~1", "/").replace("~0", "~");
        Integer index = Ints.tryParse(token);
        if (current instanceof Map<?, ?> map) {
          current = map.get(token);
        } else if (current instanceof List<?> list
            && index != null
            && index >= 0
            && index < list.size()) {
          current = list.get(index);
        } else {
          current = null;
        }
      }
      if (current != null) {
        return current instanceof Map<?, ?> map ? castToMap(map) : null;
      }
    }
    String refKey = ref.substring(ref.lastIndexOf('/') + 1);
    for (String defsKey : List.of("$defs", "definitions")) {
      if (rootSchema.get(defsKey) instanceof Map<?, ?> defs
          && defs.get(refKey) instanceof Map<?, ?> definition) {
        return castToMap(definition);
      }
    }
    return null;
  }

  /**
   * Converts a single ADK {@link BaseTool} to a Spring AI {@link ToolCallback}.
   *
   * @param tool The ADK tool to convert
   * @return An Optional containing the converted {@link ToolCallback}, or empty if the tool has no
   *     declaration
   */
  public Optional<ToolCallback> convertToSpringAiTool(BaseTool tool) {
    Objects.requireNonNull(tool, "tool cannot be null");
    if (tool.declaration().isEmpty()) {
      return Optional.empty();
    }
    FunctionDeclaration declaration = tool.declaration().get();

    BiFunction<Map<String, Object>, org.springframework.ai.chat.model.ToolContext, String>
        toolFunction =
            (args, springAiToolContext) -> {
              logger.debug("Spring AI calling tool '{}'", tool.name());
              Map<String, Object> processedArgs = processArguments(args, declaration);
              Map<String, Object> result =
                  tool.runAsync(processedArgs, adkToolContextFrom(springAiToolContext))
                      .blockingGet();
              try {
                return objectMapper.writeValueAsString(result);
              } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                    "Failed to serialize result from tool '" + tool.name() + "'", e);
              }
            };

    FunctionToolCallback.Builder callbackBuilder =
        FunctionToolCallback.builder(tool.name(), toolFunction)
            .description(tool.description())
            .inputType(Map.class);

    Optional<Object> schemaToSerialize =
        declaration
            .parameters()
            .<Object>map(this::convertSchemaToSpringAi)
            .or(declaration::parametersJsonSchema);
    if (schemaToSerialize.isPresent()) {
      try {
        callbackBuilder.inputSchema(objectMapper.writeValueAsString(schemaToSerialize.get()));
      } catch (JsonProcessingException e) {
        logger.error("Error serializing schema to JSON for tool '{}'", tool.name(), e);
      }
    }

    return Optional.of(callbackBuilder.build());
  }

  /**
   * Converts ADK tools to Spring AI ToolCallback format for tool calling, skipping tools without a
   * declaration.
   *
   * @param tools Map of ADK tools to convert
   * @return List of Spring AI ToolCallback objects
   */
  public List<ToolCallback> convertToSpringAiTools(Map<String, BaseTool> tools) {
    Objects.requireNonNull(tools, "tools cannot be null");
    List<ToolCallback> toolCallbacks = new ArrayList<>();
    for (BaseTool tool : tools.values()) {
      convertToSpringAiTool(tool).ifPresent(toolCallbacks::add);
    }
    return toolCallbacks;
  }

  /**
   * Converts a Spring AI {@link ToolCallback} to an ADK {@link BaseTool}.
   *
   * @param toolCallback The Spring AI tool callback to convert
   * @return A {@link SpringAiTool} wrapping the callback
   */
  public BaseTool convertToAdkTool(ToolCallback toolCallback) {
    return new SpringAiTool(toolCallback);
  }

  /**
   * Converts a collection of Spring AI {@link ToolCallback}s to a list of ADK {@link BaseTool}s.
   *
   * @param toolCallbacks Collection of Spring AI tool callbacks to convert
   * @return List of ADK {@link BaseTool} instances
   */
  public List<BaseTool> convertToAdkTools(Collection<? extends ToolCallback> toolCallbacks) {
    Objects.requireNonNull(toolCallbacks, "toolCallbacks cannot be null");
    ImmutableList.Builder<BaseTool> adkTools = ImmutableList.builder();
    for (ToolCallback toolCallback : toolCallbacks) {
      adkTools.add(convertToAdkTool(toolCallback));
    }
    return adkTools.build();
  }

  /**
   * Converts a Spring AI {@link ToolCallbackProvider} to an ADK {@link BaseToolset} that queries
   * the provider each time an agent lists its tools, so the toolset follows a provider whose tools
   * change.
   *
   * @param toolCallbackProvider Provider of Spring AI tool callbacks
   * @return A {@link SpringAiToolset} backed by {@code toolCallbackProvider}
   */
  public SpringAiToolset convertToAdkToolset(ToolCallbackProvider toolCallbackProvider) {
    return new SpringAiToolset(toolCallbackProvider);
  }

  /**
   * Parses a JSON schema string and normalizes it for Vertex AI (default array {@code items},
   * declared-only {@code required}); returns an empty map for a JSON {@code null}.
   *
   * @throws IllegalArgumentException if {@code jsonSchema} is not a JSON object
   */
  static Map<String, Object> parseJsonSchemaMap(String jsonSchema) {
    try {
      Map<String, Object> schemaMap = objectMapper.readValue(jsonSchema, MAP_TYPE_REFERENCE);
      if (schemaMap == null) {
        return ImmutableMap.of();
      }
      normalizeSchemaMap(schemaMap);
      return schemaMap;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to parse Spring AI JSON schema", e);
    }
  }

  private static void normalizeSchemaMap(Map<String, Object> schema) {
    Object type = schema.get("type");
    boolean isArray =
        "array".equalsIgnoreCase(String.valueOf(type))
            || (type instanceof List<?> typeList
                && typeList.stream().anyMatch(t -> "array".equalsIgnoreCase(String.valueOf(t))));
    if (isArray && schema.get("items") == null) {
      schema.put("items", Map.of("type", "string"));
    }
    Map<?, ?> rawProps =
        schema.get("properties") instanceof Map<?, ?> props ? props : ImmutableMap.of();
    for (Object propVal : rawProps.values()) {
      if (propVal instanceof Map<?, ?> propMap) {
        normalizeSchemaMap(castToMap(propMap));
      }
    }
    // Properties that come in through $ref or a composition are not visible here; keep required.
    boolean composed = Stream.of("$ref", "allOf", "anyOf", "oneOf").anyMatch(schema::containsKey);
    if (!composed && schema.get("required") instanceof List<?> rawReq) {
      ImmutableList<String> narrowed =
          rawReq.stream()
              .filter(String.class::isInstance)
              .map(String.class::cast)
              .filter(rawProps::containsKey)
              .collect(toImmutableList());
      if (narrowed.isEmpty()) {
        schema.remove("required");
      } else {
        schema.put("required", narrowed);
      }
    }
    for (String subschemaKey : List.of("items", "additionalProperties")) {
      if (schema.get(subschemaKey) instanceof Map<?, ?> subschema) {
        normalizeSchemaMap(castToMap(subschema));
      }
    }
    for (String defsKey : List.of("$defs", "definitions")) {
      if (schema.get(defsKey) instanceof Map<?, ?> defsMap) {
        for (Object defVal : defsMap.values()) {
          if (defVal instanceof Map<?, ?> defSchema) {
            normalizeSchemaMap(castToMap(defSchema));
          }
        }
      }
    }
    for (String unionKey : List.of("anyOf", "oneOf", "allOf")) {
      if (schema.get(unionKey) instanceof List<?> members) {
        for (Object member : members) {
          if (member instanceof Map<?, ?> memberSchema) {
            normalizeSchemaMap(castToMap(memberSchema));
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked") // JSON object keys are strings.
  static Map<String, Object> castToMap(Map<?, ?> rawMap) {
    return (Map<String, Object>) rawMap;
  }

  private String convertTypeToString(Type type) {
    return switch (type.knownEnum()) {
      case STRING -> "string";
      case NUMBER -> "number";
      case INTEGER -> "integer";
      case BOOLEAN -> "boolean";
      case ARRAY -> "array";
      case OBJECT -> "object";
      default -> "string";
    };
  }

  private Type convertStringToType(String lowerCaseType) {
    return switch (lowerCaseType) {
      case "string" -> new Type(Type.Known.STRING);
      case "number" -> new Type(Type.Known.NUMBER);
      case "integer" -> new Type(Type.Known.INTEGER);
      case "boolean" -> new Type(Type.Known.BOOLEAN);
      case "array" -> new Type(Type.Known.ARRAY);
      case "object" -> new Type(Type.Known.OBJECT);
      default -> new Type(Type.Known.STRING);
    };
  }

  /**
   * Parses a Spring AI tool result string into an ADK tool response map, wrapping non-object JSON
   * and plain text under {@code "result"}.
   */
  static Map<String, Object> parseToolResult(@Nullable String rawResult) {
    if (rawResult == null || rawResult.isBlank()) {
      return Map.of();
    }
    try {
      Object parsed = objectMapper.readValue(rawResult, Object.class);
      if (parsed == null) {
        return Map.of();
      }
      if (parsed instanceof Map<?, ?> map) {
        return castToMap(map);
      }
      return Map.of("result", parsed);
    } catch (JsonProcessingException e) {
      return Map.of("result", rawResult);
    }
  }

  private @Nullable ToolContext adkToolContextFrom(
      org.springframework.ai.chat.model.@Nullable ToolContext springAiToolContext) {
    if (springAiToolContext == null) {
      return null;
    }

    Object context = springAiToolContext.getContext().get(ADK_TOOL_CONTEXT_KEY);
    if (context == null) {
      return null;
    }
    if (!(context instanceof ToolContext adkToolContext)) {
      throw new IllegalArgumentException(
          "Spring AI tool context entry '"
              + ADK_TOOL_CONTEXT_KEY
              + "' must be an ADK ToolContext, but was "
              + context.getClass().getName());
    }
    return adkToolContext;
  }

  /**
   * Process arguments from Spring AI format to ADK format. Spring AI might pass arguments in
   * different formats depending on the provider.
   */
  private Map<String, Object> processArguments(
      @Nullable Map<String, Object> args, FunctionDeclaration declaration) {
    if (args == null) {
      return Map.of();
    }
    if (declaration.parameters().isPresent()) {
      var schema = declaration.parameters().get();
      if (schema.properties().isPresent()) {
        return normalizeArguments(args, schema.properties().get().keySet());
      }
    } else if (declaration.parametersJsonSchema().isPresent()) {
      Map<String, Object> schemaMap;
      try {
        schemaMap =
            objectMapper.convertValue(declaration.parametersJsonSchema().get(), MAP_TYPE_REFERENCE);
      } catch (IllegalArgumentException e) {
        logger.warn("Error processing parametersJsonSchema for argument mapping", e);
        return args;
      }
      if (schemaMap.get("properties") instanceof Map<?, ?> properties) {
        return normalizeArguments(args, castToMap(properties).keySet());
      }
    }

    // If no processing worked, return original args and let ADK handle the error
    return args;
  }

  private Map<String, Object> normalizeArguments(
      Map<String, Object> args, Set<String> expectedParams) {
    // Check if all expected parameters are present at the top level
    boolean allParamsPresent = expectedParams.stream().allMatch(args::containsKey);
    if (allParamsPresent) {
      return args;
    }

    // Check if arguments are nested under a single key (common pattern)
    if (args.size() == 1) {
      var singleValue = args.values().iterator().next();
      if (singleValue instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedArgs = (Map<String, Object>) singleValue;
        boolean allNestedParamsPresent = expectedParams.stream().allMatch(nestedArgs::containsKey);
        if (allNestedParamsPresent) {
          return nestedArgs;
        }
      }
    }

    // Check if we have a single parameter function and got a direct value
    if (expectedParams.size() == 1) {
      String expectedParam = expectedParams.iterator().next();
      if (args.size() == 1 && !args.containsKey(expectedParam)) {
        Object singleValue = args.values().iterator().next();
        return Map.of(expectedParam, singleValue);
      }
    }

    return args;
  }

  /** Simple metadata holder for tool information. */
  public static class ToolMetadata {
    private final String name;
    private final String description;
    private final FunctionDeclaration declaration;

    public ToolMetadata(String name, String description, FunctionDeclaration declaration) {
      this.name = name;
      this.description = description;
      this.declaration = declaration;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public FunctionDeclaration getDeclaration() {
      return declaration;
    }
  }
}
