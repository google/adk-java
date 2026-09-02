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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Converts between ADK and Spring AI tool/function formats.
 *
 * <p>This converter handles the translation between ADK's BaseTool/FunctionDeclaration format and
 * Spring AI tool representations. This is a simplified initial version that focuses on basic schema
 * conversion and tool metadata handling.
 */
public class ToolConverter {

  private static final Logger logger = LoggerFactory.getLogger(ToolConverter.class);

  private final ObjectMapper objectMapper;
  private final ToolExecutionMode toolExecutionMode;
  private final AdkToolContextResolver toolContextResolver;

  /** Creates a converter that exposes tool definitions while ADK owns tool execution. */
  public ToolConverter() {
    this(new ObjectMapper(), ToolExecutionMode.ADK_MANAGED, null);
  }

  /** Creates a converter with an explicit tool execution owner and context resolver. */
  public ToolConverter(
      ObjectMapper objectMapper,
      ToolExecutionMode toolExecutionMode,
      AdkToolContextResolver toolContextResolver) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.toolExecutionMode =
        Objects.requireNonNull(toolExecutionMode, "toolExecutionMode must not be null");
    if (toolExecutionMode == ToolExecutionMode.SPRING_AI_MANAGED && toolContextResolver == null) {
      throw new IllegalArgumentException(
          "An AdkToolContextResolver is required when tool execution is SPRING_AI_MANAGED");
    }
    this.toolContextResolver = toolContextResolver;
  }

  public ToolExecutionMode getToolExecutionMode() {
    return toolExecutionMode;
  }

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
   * <p>This provides basic schema conversion for tool parameters.
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

    return springAiSchema;
  }

  private String convertTypeToString(Type type) {
    return switch (type.knownEnum()) {
      case STRING -> "string";
      case NUMBER -> "number";
      case INTEGER -> "integer";
      case BOOLEAN -> "boolean";
      case ARRAY -> "array";
      case OBJECT -> "object";
      default -> "string"; // fallback
    };
  }

  /**
   * Converts ADK tools to Spring AI ToolCallback format for tool calling.
   *
   * @param tools Map of ADK tools to convert
   * @return List of Spring AI ToolCallback objects
   */
  public List<ToolCallback> convertToSpringAiTools(Map<String, BaseTool> tools) {
    List<ToolCallback> toolCallbacks = new ArrayList<>();

    for (BaseTool tool : tools.values()) {
      if (tool.declaration().isPresent()) {
        FunctionDeclaration declaration = tool.declaration().get();

        if (toolExecutionMode == ToolExecutionMode.ADK_MANAGED) {
          // Spring AI still requires callbacks to expose tool definitions to the model. The
          // callback intentionally has no side effect: ADK will execute the returned function call
          // later with the InvocationContext-backed ToolContext.
          Function<Map<String, Object>, String> definitionOnlyCallback = arguments -> "";
          toolCallbacks.add(
              configureCallback(
                      tool,
                      declaration,
                      FunctionToolCallback.builder(tool.name(), definitionOnlyCallback))
                  .build());
        } else {
          BiFunction<
                  Map<String, Object>,
                  org.springframework.ai.chat.model.ToolContext,
                  Map<String, Object>>
              executableCallback =
                  (arguments, springAiToolContext) -> {
                    Map<String, Object> processedArguments =
                        processArguments(arguments, declaration);
                    ToolContext adkToolContext =
                        Objects.requireNonNull(
                            toolContextResolver.resolve(
                                tool, processedArguments, springAiToolContext),
                            "AdkToolContextResolver returned null for tool " + tool.name());
                    return tool.runAsync(processedArguments, adkToolContext).blockingGet();
                  };
          toolCallbacks.add(
              configureCallback(
                      tool,
                      declaration,
                      FunctionToolCallback.builder(tool.name(), executableCallback))
                  .build());
        }
      }
    }

    return toolCallbacks;
  }

  private <O> FunctionToolCallback.Builder<Map<String, Object>, O> configureCallback(
      BaseTool tool,
      FunctionDeclaration declaration,
      FunctionToolCallback.Builder<Map<String, Object>, O> callbackBuilder) {
    callbackBuilder.description(tool.description()).inputType(Map.class);

    if (declaration.parameters().isPresent()) {
      Map<String, Object> springAiSchema = convertSchemaToSpringAi(declaration.parameters().get());
      logger.debug("Generated Spring AI schema for {}: {}", tool.name(), springAiSchema);
      configureInputSchema(callbackBuilder, springAiSchema, tool.name());
    } else if (declaration.parametersJsonSchema().isPresent()) {
      configureInputSchema(callbackBuilder, declaration.parametersJsonSchema().get(), tool.name());
    }

    return callbackBuilder;
  }

  private <O> void configureInputSchema(
      FunctionToolCallback.Builder<Map<String, Object>, O> callbackBuilder,
      Object schema,
      String toolName) {
    try {
      String schemaJson = objectMapper.writeValueAsString(schema);
      callbackBuilder.inputSchema(schemaJson);
      logger.debug("Set input schema JSON for {}: {}", toolName, schemaJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Unable to serialize input schema for tool " + toolName, e);
    }
  }

  /** Normalizes provider-specific argument wrappers to the ADK tool's declared parameters. */
  private Map<String, Object> processArguments(
      Map<String, Object> arguments, FunctionDeclaration declaration) {
    if (declaration.parameters().isPresent()) {
      Schema schema = declaration.parameters().get();
      if (schema.properties().isPresent()) {
        return normalizeArguments(arguments, schema.properties().get().keySet());
      }
    } else if (declaration.parametersJsonSchema().isPresent()) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema =
            objectMapper.convertValue(declaration.parametersJsonSchema().get(), Map.class);
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> propertiesMap) {
          return normalizeArguments(arguments, propertiesMap.keySet());
        }
      } catch (IllegalArgumentException e) {
        logger.warn(
            "Error processing parametersJsonSchema for argument mapping: {}", e.getMessage());
      }
    }

    return arguments;
  }

  private Map<String, Object> normalizeArguments(
      Map<String, Object> arguments, Set<?> expectedParameters) {
    if (expectedParameters.stream().allMatch(arguments::containsKey)) {
      return arguments;
    }

    if (arguments.size() == 1) {
      Object singleValue = arguments.values().iterator().next();
      if (singleValue instanceof Map<?, ?> nestedArguments
          && expectedParameters.stream().allMatch(nestedArguments::containsKey)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedArguments = (Map<String, Object>) nestedArguments;
        return normalizedArguments;
      }
    }

    if (expectedParameters.size() == 1 && arguments.size() == 1) {
      Object expectedParameter = expectedParameters.iterator().next();
      if (expectedParameter instanceof String parameterName
          && !arguments.containsKey(parameterName)) {
        return Map.of(parameterName, arguments.values().iterator().next());
      }
    }

    return arguments;
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
