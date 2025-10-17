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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolException.McpToolDeclarationException;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base class for MCP tools.
 *
 * @param <T> The type of the MCP session client.
 */
public abstract class AbstractMcpTool<T> extends BaseTool {

  protected final Tool mcpTool;
  protected final McpSessionManager mcpSessionManager;
  protected final ObjectMapper objectMapper;

  // Volatile ensures write visibility in the asynchronous chain for McpAsyncTool.
  protected volatile T mcpSession;

  protected AbstractMcpTool(
      Tool mcpTool, T mcpSession, McpSessionManager mcpSessionManager, ObjectMapper objectMapper) {
    super(
        mcpTool == null ? "" : mcpTool.name(),
        mcpTool == null ? "" : (mcpTool.description().isEmpty() ? "" : mcpTool.description()));

    if (mcpTool == null) {
      throw new IllegalArgumentException("mcpTool cannot be null");
    }
    if (mcpSession == null) {
      throw new IllegalArgumentException("mcpSession cannot be null");
    }
    if (mcpSessionManager == null) {
      throw new IllegalArgumentException("mcpSessionManager cannot be null");
    }
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper cannot be null");
    }
    this.mcpTool = mcpTool;
    this.mcpSession = mcpSession;
    this.mcpSessionManager = mcpSessionManager;
    this.objectMapper = objectMapper;
  }

  public T getMcpSession() {
    return this.mcpSession;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    JsonSchema inputSchema = this.mcpTool.inputSchema();
    Map<String, Object> outputSchema = this.mcpTool.outputSchema();
    try {
      return Optional.ofNullable(inputSchema)
          .map(
              value -> {
                FunctionDeclaration.Builder builder =
                    FunctionDeclaration.builder()
                        .name(this.name())
                        .description(this.description())
                        .parametersJsonSchema(value);
                Optional.ofNullable(outputSchema).ifPresent(builder::responseJsonSchema);
                return builder.build();
              });
    } catch (RuntimeException e) {
      throw new McpToolDeclarationException(
          String.format(
              "MCP tool:%s failed to get declaration, inputSchema:%s. outputSchema:%s.",
              this.name(), inputSchema, outputSchema),
          e);
    }
  }

  @SuppressWarnings("PreferredInterfaceType") // BaseTool.runAsync() returns Map<String, Object>
  protected static Map<String, Object> wrapCallResult(
      ObjectMapper objectMapper, String mcpToolName, CallToolResult callResult) {
    if (callResult == null) {
      return ImmutableMap.of("error", "MCP framework error: CallToolResult was null");
    }

    List<Content> contents = callResult.content();
    Boolean isToolError = callResult.isError();

    if (isToolError != null && isToolError) {
      String errorMessage = "Tool execution failed.";
      if (contents != null
          && !contents.isEmpty()
          && contents.get(0) instanceof TextContent textContent) {
        if (textContent.text() != null && !textContent.text().isEmpty()) {
          errorMessage += " Details: " + textContent.text();
        }
      }
      return ImmutableMap.of("error", errorMessage);
    }

    if (contents == null || contents.isEmpty()) {
      return ImmutableMap.of();
    }

    List<String> textOutputs = new ArrayList<>();
    for (Content content : contents) {
      if (content instanceof TextContent textContent) {
        if (textContent.text() != null) {
          textOutputs.add(textContent.text());
        }
      }
    }

    if (textOutputs.isEmpty()) {
      return ImmutableMap.of(
          "error",
          "Tool '" + mcpToolName + "' returned content that is not TextContent.",
          "content_details",
          contents.toString());
    }

    List<Map<String, Object>> resultMaps = new ArrayList<>();
    for (String textOutput : textOutputs) {
      try {
        resultMaps.add(
            objectMapper.readValue(textOutput, new TypeReference<Map<String, Object>>() {}));
      } catch (JsonProcessingException e) {
        resultMaps.add(ImmutableMap.of("text", textOutput));
      }
    }
    return ImmutableMap.of("result", resultMaps);
  }
}
