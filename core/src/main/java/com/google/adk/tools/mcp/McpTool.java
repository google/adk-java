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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.tools.mcp.McpToolException.McpToolSchemaException;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// TODO(b/413489523): Add support for auth. This is a TODO for Python as well.
/**
 * Initializes a MCP tool.
 *
 * <p>This wraps a MCP Tool interface and an active MCP Session. It invokes the MCP Tool through
 * executing the tool from remote MCP Session.
 */
public final class McpTool extends BaseTool {

  Tool mcpTool;
  McpSyncClient mcpSession;
  McpSessionManager mcpSessionManager;
  ObjectMapper objectMapper;

  /**
   * Creates a new McpTool with the default ObjectMapper.
   *
   * @param mcpTool The MCP tool to wrap.
   * @param mcpSession The MCP session to use to call the tool.
   * @param mcpSessionManager The MCP session manager to use to create new sessions.
   * @throws IllegalArgumentException If mcpTool or mcpSession are null.
   */
  public McpTool(Tool mcpTool, McpSyncClient mcpSession, McpSessionManager mcpSessionManager) {
    this(mcpTool, mcpSession, mcpSessionManager, JsonBaseModel.getMapper());
  }

  /**
   * Creates a new McpTool with the default ObjectMapper.
   *
   * @param mcpTool The MCP tool to wrap.
   * @param mcpSession The MCP session to use to call the tool.
   * @param mcpSessionManager The MCP session manager to use to create new sessions.
   * @param objectMapper The ObjectMapper to use to convert JSON schemas.
   * @throws IllegalArgumentException If mcpTool or mcpSession are null.
   */
  public McpTool(
      Tool mcpTool,
      McpSyncClient mcpSession,
      McpSessionManager mcpSessionManager,
      ObjectMapper objectMapper) {
    super(
        mcpTool == null ? "" : mcpTool.name(),
        mcpTool == null ? "" : (mcpTool.description().isEmpty() ? "" : mcpTool.description()));

    if (mcpTool == null) {
      throw new IllegalArgumentException("mcpTool cannot be null");
    }
    if (mcpSession == null) {
      throw new IllegalArgumentException("mcpSession cannot be null");
    }
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper cannot be null");
    }
    this.mcpTool = mcpTool;
    this.mcpSession = mcpSession;
    this.mcpSessionManager = mcpSessionManager;
    this.objectMapper = objectMapper;
  }

  public McpSyncClient getMcpSession() {
    return this.mcpSession;
  }

  public Schema toGeminiSchema(JsonSchema openApiSchema) {
    String jsonString = objectMapper.valueToTree(openApiSchema).toString();
    try {
      return Schema.fromJson(jsonString);
    } catch (Throwable e) {
      throw new McpToolSchemaException(String.format("MCP tool:%s, schema:%s failed to convert openApiSchema to GeminiSchema.", this.name(), jsonString), e);
    }
  }

  private void reintializeSession() {
    this.mcpSession = this.mcpSessionManager.createSession();
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(this.name())
            .description(this.description())
            .parameters(toGeminiSchema(this.mcpTool.inputSchema()))
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    return Single.<Map<String, Object>>fromCallable(
            () -> {
              CallToolResult callResult =
                  mcpSession.callTool(new CallToolRequest(this.name(), ImmutableMap.copyOf(args)));
              return wrapCallResult(this.objectMapper, this.name(), callResult);
            })
        .retryWhen(
            errors ->
                errors
                    .delay(100, MILLISECONDS)
                    .take(3)
                    .doOnNext(
                        error -> {
                          System.err.println("Retrying callTool due to: " + error);
                          reintializeSession();
                        }));
  }

  static Map<String, Object> wrapCallResult(
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
    return ImmutableMap.of("text_output", resultMaps);
  }
}
