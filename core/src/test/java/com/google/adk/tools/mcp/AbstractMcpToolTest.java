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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
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

  private static final McpSchema.Tool MY_TOOL =
      McpSchema.Tool.builder("my_tool", ImmutableMap.of("type", "object")).build();

  private static final CallToolResult TEXT_AND_IMAGE_RESULT =
      CallToolResult.builder(
              ImmutableList.of(
                  TextContent.builder("{\"count\": 2}").build(),
                  ImageContent.builder("aW1hZ2U=", "image/png").build()))
          .structuredContent(ImmutableMap.of("count", 2))
          .build();

  private static final CallToolResultMapper THROWING_MAPPER =
      (mcpTool, callResult) -> {
        throw new IllegalStateException("mapper failed");
      };

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

  @Test
  public void mcpToolRunAsync_withCallToolResultMapper_returnsMapperResponseForRawResult() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    List<CallToolResult> mappedResults = new ArrayList<>();
    McpTool tool =
        new McpTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            recordingMapper(mappedResults));

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result)
        .containsExactly(
            "tool", "my_tool", "structured", ImmutableMap.of("count", 2), "contentItems", 2);
    assertThat(mappedResults).hasSize(1);
    assertThat(mappedResults.get(0)).isSameInstanceAs(TEXT_AND_IMAGE_RESULT);
  }

  @Test
  public void mcpAsyncToolRunAsync_withCallToolResultMapper_returnsMapperResponseForRawResult() {
    McpAsyncClient sessionMock = mock(McpAsyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(Mono.just(TEXT_AND_IMAGE_RESULT));
    List<CallToolResult> mappedResults = new ArrayList<>();
    McpAsyncTool tool =
        new McpAsyncTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            recordingMapper(mappedResults));

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result)
        .containsExactly(
            "tool", "my_tool", "structured", ImmutableMap.of("count", 2), "contentItems", 2);
    assertThat(mappedResults).hasSize(1);
    assertThat(mappedResults.get(0)).isSameInstanceAs(TEXT_AND_IMAGE_RESULT);
  }

  @Test
  public void mcpToolRunAsync_withCallToolResultMapper_mapsErrorResults() {
    CallToolResult errorResult =
        CallToolResult.builder(ImmutableList.of(TextContent.builder("quota exceeded").build()))
            .isError(true)
            .build();
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(errorResult);
    McpTool tool =
        new McpTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            (mcpTool, callResult) -> ImmutableMap.of("isError", callResult.isError()));

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsExactly("isError", true);
  }

  @Test
  public void mcpToolRunAsync_mapperThrows_failsWithoutCallingToolAgain() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    McpSessionManager managerMock = mock(McpSessionManager.class);
    McpTool tool = new McpTool(MY_TOOL, sessionMock, managerMock, objectMapper, THROWING_MAPPER);

    tool.runAsync(ImmutableMap.of(), mock(ToolContext.class))
        .test()
        .awaitDone(5, SECONDS)
        .assertError(IllegalStateException.class);

    verify(sessionMock).callTool(any());
    verifyNoInteractions(managerMock);
  }

  @Test
  public void mcpAsyncToolRunAsync_mapperThrows_failsWithoutCallingToolAgain() {
    McpAsyncClient sessionMock = mock(McpAsyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(Mono.just(TEXT_AND_IMAGE_RESULT));
    McpSessionManager managerMock = mock(McpSessionManager.class);
    McpAsyncTool tool =
        new McpAsyncTool(MY_TOOL, sessionMock, managerMock, objectMapper, THROWING_MAPPER);

    tool.runAsync(ImmutableMap.of(), mock(ToolContext.class))
        .test()
        .awaitDone(5, SECONDS)
        .assertError(IllegalStateException.class);

    verify(sessionMock).callTool(any());
    verifyNoInteractions(managerMock);
  }

  @Test
  public void mcpToolRunAsync_nullResult_returnsErrorWithoutCallingMapper() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(null);
    McpTool tool =
        new McpTool(
            MY_TOOL, sessionMock, mock(McpSessionManager.class), objectMapper, THROWING_MAPPER);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsExactly("error", "MCP framework error: CallToolResult was null");
  }

  @Test
  public void mcpAsyncToolRunAsync_emptyResult_returnsErrorWithoutCallingMapper() {
    McpAsyncClient sessionMock = mock(McpAsyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(Mono.empty());
    McpAsyncTool tool =
        new McpAsyncTool(
            MY_TOOL, sessionMock, mock(McpSessionManager.class), objectMapper, THROWING_MAPPER);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsExactly("error", "MCP framework error: CallToolResult was null");
  }

  @Test
  public void mcpToolRunAsync_callFailsOnce_retriesWithNewSessionAndMapsResult() {
    McpSyncClient failingSession = mock(McpSyncClient.class);
    when(failingSession.callTool(any())).thenThrow(new IllegalStateException("connection lost"));
    McpSyncClient newSession = mock(McpSyncClient.class);
    when(newSession.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    McpSessionManager managerMock = mock(McpSessionManager.class);
    when(managerMock.createSession()).thenReturn(newSession);
    List<CallToolResult> mappedResults = new ArrayList<>();
    McpTool tool =
        new McpTool(
            MY_TOOL, failingSession, managerMock, objectMapper, recordingMapper(mappedResults));

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsEntry("tool", "my_tool");
    assertThat(mappedResults).hasSize(1);
  }

  @Test
  public void mcpAsyncToolRunAsync_callFailsOnce_retriesWithNewSessionAndMapsResult() {
    McpAsyncClient failingSession = mock(McpAsyncClient.class);
    when(failingSession.callTool(any()))
        .thenReturn(Mono.error(new IllegalStateException("connection lost")));
    McpAsyncClient newSession = mock(McpAsyncClient.class);
    when(newSession.initialize())
        .thenReturn(
            Mono.just(
                McpSchema.InitializeResult.builder(
                        "2025-06-18",
                        McpSchema.ServerCapabilities.builder().build(),
                        McpSchema.Implementation.builder("server", "1.0").build())
                    .build()));
    when(newSession.callTool(any())).thenReturn(Mono.just(TEXT_AND_IMAGE_RESULT));
    McpSessionManager managerMock = mock(McpSessionManager.class);
    when(managerMock.createAsyncSession()).thenReturn(newSession);
    List<CallToolResult> mappedResults = new ArrayList<>();
    McpAsyncTool tool =
        new McpAsyncTool(
            MY_TOOL, failingSession, managerMock, objectMapper, recordingMapper(mappedResults));

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsEntry("tool", "my_tool");
    assertThat(mappedResults).hasSize(1);
  }

  private static CallToolResultMapper recordingMapper(List<CallToolResult> mappedResults) {
    return (mcpTool, callResult) -> {
      mappedResults.add(callResult);
      return ImmutableMap.of(
          "tool",
          mcpTool.name(),
          "structured",
          callResult.structuredContent(),
          "contentItems",
          callResult.content().size());
    };
  }
}
