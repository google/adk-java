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

import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Optional;

/** Utility class for converting between different representations of MCP tools. */
public final class ConversionUtils {

  private static final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

  public McpSchema.Tool adkToMcpToolType(BaseTool tool) {
    Optional<FunctionDeclaration> toolDeclaration = tool.declaration();
    if (toolDeclaration.isEmpty()) {
      return McpSchema.Tool.builder().name(tool.name()).description(tool.description()).build();
    }
    Schema geminiSchema = toolDeclaration.get().parameters().get();
    return McpSchema.Tool.builder()
        .name(tool.name())
        .description(tool.description())
        .inputSchema(jsonMapper, geminiSchema.toJson())
        .build();
  }

  private ConversionUtils() {}
}
