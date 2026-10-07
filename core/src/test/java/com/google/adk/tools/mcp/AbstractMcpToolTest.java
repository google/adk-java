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
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

@RunWith(JUnit4.class)
public final class AbstractMcpToolTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testWrapCallResult_success() {
    CallToolResult result =
        CallToolResult.builder()
            .content(ImmutableList.of(TextContent.builder("success").build()))
            .isError(false)
            .build();

    Map<String, Object> map = AbstractMcpTool.wrapCallResult(objectMapper, "my_tool", result);

    assertThat(map).containsKey("text_output");
    List<?> content = (List<?>) map.get("text_output");
    assertThat(content).hasSize(1);

    Map<?, ?> contentItem = (Map<?, ?>) content.get(0);
    assertThat(contentItem).containsEntry("text", "success");
  }

  @Test
  public void instantiateWithToolBuilder_nullDescription_succeeds() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    McpSessionManager managerMock = mock(McpSessionManager.class);
    McpSchema.Tool schemaTool =
        McpSchema.Tool.builder("realTool", ImmutableMap.of("type", "object")).build();

    McpTool tool = new McpTool(schemaTool, sessionMock, managerMock, objectMapper);

    assertEquals("", tool.description());
    assertEquals("realTool", tool.name());
  }

  @Test
  public void mcpToolRunAsync_sendsNameAndArgumentsWithoutMeta() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any()))
        .thenReturn(
            CallToolResult.builder(ImmutableList.of(TextContent.builder("ok").build())).build());
    McpSchema.Tool schemaTool =
        McpSchema.Tool.builder("my_tool", ImmutableMap.of("type", "object")).build();
    McpTool tool =
        new McpTool(schemaTool, sessionMock, mock(McpSessionManager.class), objectMapper);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("query", "shoes"), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsKey("text_output");
    ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(sessionMock).callTool(request.capture());
    assertThat(request.getValue().name()).isEqualTo("my_tool");
    assertThat(request.getValue().arguments()).containsExactly("query", "shoes");
    assertThat(request.getValue().meta()).isNull();
  }

  @Test
  public void mcpAsyncToolRunAsync_sendsNameAndArgumentsWithoutMeta() {
    McpAsyncClient sessionMock = mock(McpAsyncClient.class);
    when(sessionMock.callTool(any()))
        .thenReturn(
            Mono.just(
                CallToolResult.builder(ImmutableList.of(TextContent.builder("ok").build()))
                    .build()));
    McpSchema.Tool schemaTool =
        McpSchema.Tool.builder("my_tool", ImmutableMap.of("type", "object")).build();
    McpAsyncTool tool =
        new McpAsyncTool(schemaTool, sessionMock, mock(McpSessionManager.class), objectMapper);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("query", "shoes"), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsKey("text_output");
    ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(sessionMock).callTool(request.capture());
    assertThat(request.getValue().name()).isEqualTo("my_tool");
    assertThat(request.getValue().arguments()).containsExactly("query", "shoes");
    assertThat(request.getValue().meta()).isNull();
  }
}
