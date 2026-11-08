/*
 * Copyright 2026 Google LLC
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;

/**
 * A converter from the {@link CallToolResult} of an MCP tool call to the response of the ADK tool.
 *
 * <p>By default, MCP tools return only the text content of a result, under {@code text_output}. Set
 * a mapper on {@link McpToolset.Builder} or {@link McpAsyncToolset.Builder} to return other parts
 * of the result, such as {@code structuredContent} or images.
 */
@FunctionalInterface
public interface CallToolResultMapper {

  /**
   * Returns the response of {@code mcpTool} for {@code callToolResult}, including results with
   * {@code isError} set.
   *
   * <p>The response becomes the function response sent to the model, so it must not be null and its
   * values must serialize to JSON. If the mapper throws, the tool call fails and is not retried.
   */
  Map<String, Object> map(Tool mcpTool, CallToolResult callToolResult);

  /**
   * Returns the default mapper, which keeps only the text content under {@code text_output}, or
   * returns an {@code error}. It parses each text item as JSON with {@code objectMapper}, or keeps
   * it under {@code text}. Its responses are immutable, so a custom mapper copies one before adding
   * other parts of the result.
   */
  static CallToolResultMapper textOutput(ObjectMapper objectMapper) {
    checkNotNull(objectMapper);
    return (mcpTool, callToolResult) ->
        AbstractMcpTool.wrapCallResult(objectMapper, mcpTool.name(), callToolResult);
  }

  /**
   * Returns a mapper that returns the whole result in the MCP wire format: {@code content}, plus
   * {@code structuredContent}, {@code isError} and {@code _meta} when set. An {@code
   * afterToolCallback} can then reshape it, as the {@code examples/mcpfullresult} sample does.
   * Servers that repeat {@code structuredContent} as text send it to the model twice, and images
   * reach the model as base64 text, which it cannot read.
   */
  static CallToolResultMapper fullResult() {
    return (mcpTool, callToolResult) ->
        McpJsonDefaults.getMapper()
            .convertValue(callToolResult, new TypeRef<Map<String, Object>>() {});
  }
}
