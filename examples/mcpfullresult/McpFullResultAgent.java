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

package com.example.mcpfullresult;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.mcp.CallToolResultMapper;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;

/**
 * Defines an agent whose MCP tool returns the whole tool result, which an after-tool callback
 * reduces to its structured content before the model sees it.
 */
public final class McpFullResultAgent {
  private static final String BIGQUERY_MCP_URL = "https://bigquery.googleapis.com/mcp";

  private McpFullResultAgent() {}

  /**
   * Creates the agent. {@code accessToken} authenticates to the BigQuery MCP server, and {@code
   * project} is billed for the requests.
   */
  public static LlmAgent create(String accessToken, String project) {
    McpToolset toolset =
        McpToolset.builder()
            .connectionParams(
                StreamableHttpServerParameters.builder()
                    .url(BIGQUERY_MCP_URL)
                    .headers(
                        ImmutableMap.of(
                            "Authorization",
                            "Bearer " + accessToken,
                            "x-goog-user-project",
                            project))
                    .build())
            .toolFilter(ImmutableList.of("list_dataset_ids"))
            .callToolResultMapper(CallToolResultMapper.fullResult())
            .build();
    return LlmAgent.builder()
        .name("bigquery_agent")
        .description("Assistant that lists BigQuery datasets through the BigQuery MCP server.")
        .model("gemini-2.5-flash")
        .instruction("Answer questions about BigQuery datasets with the provided tools.")
        .tools(toolset)
        .afterToolCallbackSync(
            (invocationContext, tool, args, toolContext, response) ->
                keepStructuredContent((Map<?, ?>) response))
        .build();
  }

  /**
   * Replaces a successful result with its structured content, which the server also repeats as
   * text. Error results and results without structured content reach the model unchanged.
   */
  private static Optional<Map<String, Object>> keepStructuredContent(Map<?, ?> result) {
    Object structured = result.get("structuredContent");
    if (structured == null || Boolean.TRUE.equals(result.get("isError"))) {
      return Optional.empty();
    }
    return Optional.of(ImmutableMap.of("result", structured));
  }
}
