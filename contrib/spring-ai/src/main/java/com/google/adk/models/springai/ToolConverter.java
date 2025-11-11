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

import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // Create a ToolCallback that wraps the ADK tool
        // Create a Function that takes Map input and calls the ADK tool
        java.util.function.Function<Map<String, Object>, String> toolFunction =
            args -> {
              try {
                logger.debug("Spring AI calling tool '{}'", tool.name());
                logger.debug("Raw args from Spring AI: {}", args);
                logger.debug("Args type: {}", args.getClass().getName());
                logger.debug("Args keys: {}", args.keySet());
                for (Map.Entry<String, Object> entry : args.entrySet()) {
                  logger.debug(
                      "  {} -> {} ({})",
                      entry.getKey(),
                      entry.getValue(),
                      entry.getValue().getClass().getName());
                }

                // Handle different argument formats that Spring AI might pass
                Map<String, Object> processedArgs = processArguments(args, declaration);
                logger.debug("Processed args for ADK: {}", processedArgs);

                // Call the ADK tool and wait for the result
                Map<String, Object> result = tool.runAsync(processedArgs, null).blockingGet();
                // Convert result back to JSON string
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
              } catch (Exception e) {
                throw new RuntimeException("Tool execution failed: " + e.getMessage(), e);
              }
            };

        FunctionToolCallback.Builder callbackBuilder =
            FunctionToolCallback.builder(tool.name(), toolFunction).description(tool.description());

        // Convert ADK schema to Spring AI schema if available
        if (declaration.parameters().isPresent()) {
          // Use Map.class to indicate the input is an object/map
          callbackBuilder.inputType(Map.class);

          // Convert ADK schema to Spring AI JSON schema format
          Map<String, Object> springAiSchema =
              convertSchemaToSpringAi(declaration.parameters().get());
          logger.debug("Generated Spring AI schema for {}: {}", tool.name(), springAiSchema);

          // Provide the schema as JSON string using inputSchema method
          try {
            String schemaJson =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(springAiSchema);
            callbackBuilder.inputSchema(schemaJson);
            logger.debug("Set input schema JSON: {}", schemaJson);
          } catch (Exception e) {
            logger.error("Error serializing schema to JSON: {}", e.getMessage(), e);
          }
        }

        toolCallbacks.add(callbackBuilder.build());
      }
    }

    return toolCallbacks;
  }

  /**
   * Process arguments from Spring AI format to ADK format. Spring AI might pass arguments in
   * different formats depending on the provider.
   */
  private Map<String, Object> processArguments(
      Map<String, Object> args, FunctionDeclaration declaration) {
    // If the arguments already match the expected format, return as-is
    if (declaration.parameters().isPresent()) {
      var schema = declaration.parameters().get();
      if (schema.properties().isPresent()) {
        var expectedParams = schema.properties().get().keySet();

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
            boolean allNestedParamsPresent =
                expectedParams.stream().allMatch(nestedArgs::containsKey);
            if (allNestedParamsPresent) {
              return nestedArgs;
            }
          }
        }

        // Check if we have a single parameter function and got a direct value
        if (expectedParams.size() == 1) {
          String expectedParam = expectedParams.iterator().next();
          if (args.size() == 1 && !args.containsKey(expectedParam)) {
            // Try to map the single value to the expected parameter name
            Object singleValue = args.values().iterator().next();
            return Map.of(expectedParam, singleValue);
          }
        }
      }
    }

    // If no processing worked, return original args and let ADK handle the error
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
