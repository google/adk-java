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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolException.McpToolDeclarationException;
import com.google.common.base.CharMatcher;
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
  protected final boolean propagateStructuredContent;

  // Volatile ensures write visibility in the asynchronous chain for McpAsyncTool.
  protected volatile T mcpSession;

  protected AbstractMcpTool(
      Tool mcpTool, T mcpSession, McpSessionManager mcpSessionManager, ObjectMapper objectMapper) {
    this(
        mcpTool,
        mcpSession,
        mcpSessionManager,
        objectMapper,
        /* propagateStructuredContent= */ false);
  }

  /**
   * Creates a tool whose responses include the result's {@code structuredContent} if {@code
   * propagateStructuredContent} is true.
   */
  protected AbstractMcpTool(
      Tool mcpTool,
      T mcpSession,
      McpSessionManager mcpSessionManager,
      ObjectMapper objectMapper,
      boolean propagateStructuredContent) {
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
    this.propagateStructuredContent = propagateStructuredContent;
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
   * Converts a {@link CallToolResult} into a tool response map without its {@code
   * structuredContent}.
   *
   * @deprecated Use {@link #wrapCallResult(ObjectMapper, CallToolResult, boolean)} with {@code
   *     false}; {@code mcpToolName} is unused.
   */
  @Deprecated
  @SuppressWarnings("PreferredInterfaceType") // BaseTool.runAsync() returns Map<String, Object>
  protected static Map<String, Object> wrapCallResult(
      ObjectMapper objectMapper, String mcpToolName, @Nullable CallToolResult callResult) {
    return wrapCallResult(objectMapper, callResult, /* propagateStructuredContent= */ false);
  }

  /**
   * Converts a {@link CallToolResult} into a tool response map; a null or error result becomes a
   * single {@code error} entry. Text items go under {@code text_output}, each parsed as a JSON
   * object or else wrapped as {@code {"text": ...}}, and the other items go under {@code content}
   * with base64 {@code data} and {@code blob} replaced by their decoded byte {@code size}. {@code
   * structuredContent} is added only if {@code propagateStructuredContent} is set, since servers
   * usually repeat it as JSON text.
   */
  @SuppressWarnings("PreferredInterfaceType") // BaseTool.runAsync() returns Map<String, Object>
  protected static Map<String, Object> wrapCallResult(
      ObjectMapper objectMapper,
      @Nullable CallToolResult callResult,
      boolean propagateStructuredContent) {
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

    List<@Nullable Map<String, Object>> textOutputs = new ArrayList<>();
    List<Content> nonTextContents = new ArrayList<>();
    for (Content content : contents) {
      if (content instanceof TextContent textContent) {
        textOutputs.add(parseTextOutput(objectMapper, textContent.text()));
      } else if (content != null) {
        nonTextContents.add(content);
      }
    }

    ImmutableMap.Builder<String, Object> result = ImmutableMap.builder();
    if (!textOutputs.isEmpty()) {
      result.put("text_output", textOutputs);
    }
    if (!nonTextContents.isEmpty()) {
      result.put(
          "content",
          nonTextContents.stream().map(item -> toContentMap(objectMapper, item)).toList());
    }
    if (propagateStructuredContent && callResult.structuredContent() != null) {
      result.put("structuredContent", callResult.structuredContent());
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

  private static Map<String, Object> toContentMap(ObjectMapper objectMapper, Content item) {
    ObjectNode node = objectMapper.valueToTree(item);
    // As text, base64 media costs the model far more tokens than the media itself.
    replaceBase64WithSize(node, "data");
    if (node.get("resource") instanceof ObjectNode resource) {
      replaceBase64WithSize(resource, "blob");
    }
    return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
  }

  private static void replaceBase64WithSize(ObjectNode node, String field) {
    if (node.get(field) instanceof TextNode base64) {
      node.remove(field);
      node.put("size", decodedSize(base64.textValue()));
    }
  }

  private static int decodedSize(String base64) {
    String encoded = CharMatcher.whitespace().removeFrom(base64);
    int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
    return (int) ((encoded.length() - padding) * 3L / 4);
  }
}
