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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import reactor.core.publisher.Mono;

@RunWith(JUnit4.class)
public final class McpAsyncToolsetTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private McpSessionManager mockMcpSessionManager;
  @Mock private McpAsyncClient mockMcpAsyncClient;

  @Before
  public void setUp() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name("tool1")
            .inputSchema(McpJsonDefaults.getMapper(), "{}")
            .build();
    when(mockMcpSessionManager.createAsyncSession()).thenReturn(mockMcpAsyncClient);
    when(mockMcpAsyncClient.initialize()).thenReturn(Mono.empty());
    when(mockMcpAsyncClient.listTools())
        .thenReturn(Mono.just(new McpSchema.ListToolsResult(ImmutableList.of(tool), null)));
    when(mockMcpAsyncClient.callTool(any()))
        .thenReturn(
            Mono.just(
                McpSchema.CallToolResult.builder()
                    .addTextContent("{\"count\": 2}")
                    .structuredContent(ImmutableMap.of("count", 2))
                    .build()));
  }

  @Test
  public void getTools_withPropagateStructuredContent_toolsReturnStructuredContent() {
    McpAsyncToolset toolset =
        McpAsyncToolset.builder()
            .mcpSessionManager(mockMcpSessionManager)
            .propagateStructuredContent(true)
            .build();

    Map<String, Object> response = callOnlyTool(toolset);

    assertThat(response).containsEntry("structuredContent", ImmutableMap.of("count", 2));
  }

  @Test
  public void getTools_byDefault_toolsOmitStructuredContent() {
    McpAsyncToolset toolset =
        McpAsyncToolset.builder().mcpSessionManager(mockMcpSessionManager).build();

    Map<String, Object> response = callOnlyTool(toolset);

    assertThat(response).doesNotContainKey("structuredContent");
  }

  private static Map<String, Object> callOnlyTool(McpAsyncToolset toolset) {
    return toolset
        .getTools(/* readonlyContext= */ null)
        .blockingFirst()
        .runAsync(ImmutableMap.of(), /* toolContext= */ null)
        .blockingGet();
  }
}
