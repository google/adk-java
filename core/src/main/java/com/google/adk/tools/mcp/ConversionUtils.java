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
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Optional;

/** Utility class for converting between different representations of MCP tools. */
public final class ConversionUtils {

  private static final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
  private static final Map<String, Object> EMPTY_OBJECT_SCHEMA = Map.of();

  public static McpSchema.Tool adkToMcpToolType(BaseTool tool) {
    McpSchema.Tool.Builder builder =
        McpSchema.Tool.builder().name(tool.name()).description(tool.description());

    Optional<FunctionDeclaration> declarationOpt = tool.declaration();
    if (declarationOpt.isPresent()) {
      FunctionDeclaration declaration = declarationOpt.get();
      if (declaration.parametersJsonSchema().isPresent()) {
        Map<String, Object> schemaMap =
            JsonBaseModel.getMapper()
                .convertValue(
                    declaration.parametersJsonSchema().get(),
                    new TypeReference<Map<String, Object>>() {});
        return builder.inputSchema(schemaMap).build();
      } else if (declaration.parameters().isPresent()) {
        return builder.inputSchema(jsonMapper, declaration.parameters().get().toJson()).build();
      }
    }
    return builder.inputSchema(EMPTY_OBJECT_SCHEMA).build();
  }

  private ConversionUtils() {}
}
