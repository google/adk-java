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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import reactor.core.publisher.Mono;

@RunWith(JUnit4.class)
public final class McpAsyncToolsetTest {

  @Test
  public void builder_withCallToolResultMapper_toolsReturnMapperResponse() {
    McpSessionManager manager = mock(McpSessionManager.class);
    McpAsyncClient client = mock(McpAsyncClient.class);
    when(manager.createAsyncSession()).thenReturn(client);
    when(client.initialize()).thenReturn(Mono.empty());
    when(client.listTools())
        .thenReturn(
            Mono.just(
                ListToolsResult.builder(
                        ImmutableList.of(
                            Tool.builder("tool1", McpJsonDefaults.getMapper(), "{}").build()))
                    .build()));
    when(client.callTool(any()))
        .thenReturn(
            Mono.just(
                CallToolResult.builder(
                        ImmutableList.of(TextContent.builder("{\"count\": 2}").build()))
                    .structuredContent(ImmutableMap.of("count", 2))
                    .build()));

    McpAsyncToolset toolset =
        McpAsyncToolset.builder()
            .mcpSessionManager(manager)
            .callToolResultMapper(
                (mcpTool, callResult) ->
                    ImmutableMap.of("structured", callResult.structuredContent()))
            .build();
    BaseTool tool =
        getOnlyElement(toolset.getTools(mock(ReadonlyContext.class)).toList().blockingGet());
    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(tool.name()).isEqualTo("tool1");
    assertThat(result).containsExactly("structured", ImmutableMap.of("count", 2));
  }
}
