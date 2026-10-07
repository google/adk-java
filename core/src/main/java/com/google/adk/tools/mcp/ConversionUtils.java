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

package com.google.adk.tools.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Utility class for converting between different representations of MCP tools. */
public final class ConversionUtils {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * Input schema for a tool with no parameters. This is the schema the deprecated {@code
   * McpSchema.Tool.builder()} filled in when none was set, and the MCP spec accepts it for a tool
   * that takes no arguments.
   */
  private static final ImmutableMap<String, Object> NO_PARAMETERS_SCHEMA =
      ImmutableMap.of("type", "object");

  /**
   * Converts an ADK tool to an MCP tool. The input schema is the declaration's {@code
   * parametersJsonSchema} when it is set and not empty, otherwise its {@code parameters} with
   * lower-case types, otherwise {@code {"type": "object"}}.
   */
  public static McpSchema.Tool adkToMcpToolType(BaseTool tool) {
    Optional<FunctionDeclaration> declaration = tool.declaration();
    Map<String, Object> inputSchema =
        declaration
            .flatMap(FunctionDeclaration::parametersJsonSchema)
            // ADK's Jackson 2 mapper: a Jackson 3 MCP SDK mapper would read a JsonNode as a bean.
            .map(schema -> JsonBaseModel.getMapper().convertValue(schema, MAP_TYPE))
            .filter(schema -> !schema.isEmpty())
            .or(
                () ->
                    declaration
                        .flatMap(FunctionDeclaration::parameters)
                        .map(ConversionUtils::toJsonSchema))
            .orElse(NO_PARAMETERS_SCHEMA);
    return McpSchema.Tool.builder(tool.name(), inputSchema).description(tool.description()).build();
  }

  /** Converts a genai {@link Schema}, which spells types in upper case, to JSON Schema. */
  private static Map<String, Object> toJsonSchema(Schema schema) {
    Map<String, Object> json = JsonBaseModel.getMapper().convertValue(schema, MAP_TYPE);
    lowercaseTypes(json);
    return json;
  }

  /**
   * Lower-cases the {@code type} of {@code node} and of its nested schemas, in place. A type with
   * no JSON Schema name, such as {@code TYPE_UNSPECIFIED}, is removed, so that schema accepts any
   * value.
   */
  private static void lowercaseTypes(Object node) {
    if (!(node instanceof Map<?, ?> rawSchema)) {
      return;
    }
    // Safe: convertValue built the whole tree from Map<String, Object> nodes.
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) rawSchema;
    if (schema.get("type") instanceof String type) {
      Type.Known known = new Type(type).knownEnum();
      if (known == Type.Known.TYPE_UNSPECIFIED) {
        schema.remove("type");
      } else {
        schema.put("type", known.name().toLowerCase(Locale.ROOT));
      }
    }
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      properties.values().forEach(ConversionUtils::lowercaseTypes);
    }
    lowercaseTypes(schema.get("items"));
    if (schema.get("anyOf") instanceof List<?> anyOf) {
      anyOf.forEach(ConversionUtils::lowercaseTypes);
    }
  }

  private ConversionUtils() {}
}
