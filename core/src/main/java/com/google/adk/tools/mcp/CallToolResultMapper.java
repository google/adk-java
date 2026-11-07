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
   * values must serialize to JSON.
   */
  Map<String, Object> map(Tool mcpTool, CallToolResult callToolResult);
}
