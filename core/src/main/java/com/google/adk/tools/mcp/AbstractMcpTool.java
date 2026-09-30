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
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
        mcpTool == null ? "" : (Strings.nullToEmpty(mcpTool.description())));

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

  public ToolAnnotations annotations() {
    return mcpTool.annotations();
  }

  public Map<String, Object> meta() {
    return mcpTool.meta();
  }

  public T getMcpSession() {
    return this.mcpSession;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    Map<String, Object> inputSchema = this.mcpTool.inputSchema();
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

  /**
   * Converts a {@link CallToolResult} into a tool response map; a null or error result becomes a
   * single {@code error} entry. Text items go under {@code text_output}, each parsed as a JSON
   * object or else wrapped as {@code {"text": ...}}. {@code structuredContent} and {@code _meta}
   * are added when present, and the full ordered {@code content} list when any item is not text.
   */
  @SuppressWarnings("PreferredInterfaceType") // BaseTool.runAsync() returns Map<String, Object>
  protected static Map<String, Object> wrapCallResult(
      ObjectMapper objectMapper, String mcpToolName, @Nullable CallToolResult callResult) {
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

    List<Map<String, Object>> textOutputs = new ArrayList<>();
    boolean hasNonTextContent = false;
    for (Content content : contents) {
      if (content instanceof TextContent textContent) {
        textOutputs.add(parseTextOutput(objectMapper, textContent.text()));
      } else {
        hasNonTextContent = true;
      }
    }

    ImmutableMap.Builder<String, Object> result = ImmutableMap.builder();
    if (!textOutputs.isEmpty()) {
      result.put("text_output", textOutputs);
    }
    // Skipped for text-only results, which would otherwise send their text twice.
    if (hasNonTextContent) {
      // Converted through the record so each item keeps its polymorphic "type" property.
      Map<String, Object> wireResult =
          objectMapper.convertValue(callResult, new TypeReference<Map<String, Object>>() {});
      result.put("content", wireResult.get("content"));
    }
    if (callResult.structuredContent() != null) {
      result.put("structuredContent", callResult.structuredContent());
    }
    if (callResult.meta() != null) {
      result.put("_meta", callResult.meta());
    }
    return result.buildOrThrow();
  }

  private static @Nullable Map<String, Object> parseTextOutput(
      ObjectMapper objectMapper, String text) {
    try {
      return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      return ImmutableMap.of("text", text);
    }
  }
}
