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

import static com.google.adk.testing.TestUtils.createEvent;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private static final McpSchema.Tool NULLABLE_TOOL =
      McpSchema.Tool.builder(
              "set_value",
              ImmutableMap.of(
                  "type",
                  "object",
                  "properties",
                  ImmutableMap.of(
                      "value", ImmutableMap.of("type", ImmutableList.of("string", "null")))))
          .build();

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
    McpSessionManager managerMock = mock(McpSessionManager.class);
    McpTool tool = new McpTool(schemaTool, sessionMock, managerMock, objectMapper);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("query", "shoes"), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsKey("text_output");
    ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(sessionMock).callTool(request.capture());
    assertThat(request.getValue().name()).isEqualTo("my_tool");
    assertThat(request.getValue().arguments()).containsExactly("query", "shoes");
    assertThat(request.getValue().meta()).isNull();
    verifyNoInteractions(managerMock);
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
    McpSessionManager managerMock = mock(McpSessionManager.class);
    McpAsyncTool tool = new McpAsyncTool(schemaTool, sessionMock, managerMock, objectMapper);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("query", "shoes"), mock(ToolContext.class)).blockingGet();

    assertThat(result).containsKey("text_output");
    ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(sessionMock).callTool(request.capture());
    assertThat(request.getValue().name()).isEqualTo("my_tool");
    assertThat(request.getValue().arguments()).containsExactly("query", "shoes");
    assertThat(request.getValue().meta()).isNull();
    verifyNoInteractions(managerMock);
  }

  @Test
  public void mcpToolRunAsync_withoutMapper_returnsOnlyTextOutput() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    McpTool tool = new McpTool(MY_TOOL, sessionMock, mock(McpSessionManager.class), objectMapper);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result)
        .containsExactly("text_output", ImmutableList.of(ImmutableMap.of("count", 2)));
  }

  @Test
  public void mcpAsyncToolRunAsync_withoutMapper_returnsOnlyTextOutput() {
    McpAsyncClient sessionMock = mock(McpAsyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(Mono.just(TEXT_AND_IMAGE_RESULT));
    McpAsyncTool tool =
        new McpAsyncTool(MY_TOOL, sessionMock, mock(McpSessionManager.class), objectMapper);

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result)
        .containsExactly("text_output", ImmutableList.of(ImmutableMap.of("count", 2)));
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
  public void mcpToolRunAsync_mapperBuildingOnTextOutput_keepsTextOutputAndAddsStructuredContent() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    CallToolResultMapper textOutput = CallToolResultMapper.textOutput(objectMapper);
    McpTool tool =
        new McpTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            (mcpTool, callResult) ->
                ImmutableMap.<String, Object>builder()
                    .putAll(textOutput.map(mcpTool, callResult))
                    .put("structuredContent", callResult.structuredContent())
                    .buildOrThrow());

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result)
        .containsExactly(
            "text_output",
            ImmutableList.of(ImmutableMap.of("count", 2)),
            "structuredContent",
            ImmutableMap.of("count", 2));
  }

  @Test
  public void mcpToolRunAsync_withFullResultMapper_returnsWholeResult() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    McpTool tool =
        new McpTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            CallToolResultMapper.fullResult());

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet();

    assertThat(result)
        .containsExactly(
            "content",
            ImmutableList.of(
                ImmutableMap.of("type", "text", "text", "{\"count\": 2}"),
                ImmutableMap.of("type", "image", "data", "aW1hZ2U=", "mimeType", "image/png")),
            "isError",
            false,
            "structuredContent",
            ImmutableMap.of("count", 2));
  }

  @Test
  public void fullResultMapper_withAfterToolCallback_callbackReshapesWholeResult() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    McpTool tool =
        new McpTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            CallToolResultMapper.fullResult());
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .afterToolCallbackSync(
                    (unusedInvocationContext,
                        unusedTool,
                        unusedArgs,
                        unusedToolContext,
                        response) -> {
                      Map<?, ?> result = (Map<?, ?>) response;
                      Object structured = result.get("structuredContent");
                      if (structured == null || Boolean.TRUE.equals(result.get("isError"))) {
                        return Optional.empty();
                      }
                      return Optional.of(ImmutableMap.of("result", structured));
                    })
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_id")
                                .name("my_tool")
                                .args(ImmutableMap.of())
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(invocationContext, event, ImmutableMap.of("my_tool", tool))
            .blockingGet();

    assertThat(getOnlyElement(functionResponseEvent.functionResponses()).response())
        .hasValue(ImmutableMap.of("result", ImmutableMap.of("count", 2)));
  }

  @Test
  public void mcpToolRunAsync_mapperReturnsNull_failsWithMapperMessage() {
    McpSyncClient sessionMock = mock(McpSyncClient.class);
    when(sessionMock.callTool(any())).thenReturn(TEXT_AND_IMAGE_RESULT);
    McpTool tool =
        new McpTool(
            MY_TOOL,
            sessionMock,
            mock(McpSessionManager.class),
            objectMapper,
            (mcpTool, callResult) -> null);

    NullPointerException exception =
        assertThrows(
            NullPointerException.class,
            () -> tool.runAsync(ImmutableMap.of(), mock(ToolContext.class)).blockingGet());

    assertThat(exception).hasMessageThat().contains("CallToolResultMapper returned null");
    verify(sessionMock).callTool(any());
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

  @Test
  public void mcpToolRunAsync_preservesExplicitNull() throws Exception {
    explicitNullReachesTheMcpClient(false);
  }

  @Test
  public void mcpAsyncToolRunAsync_preservesExplicitNull() throws Exception {
    explicitNullReachesTheMcpClient(true);
  }

  @Test
  public void mcpToolRunAsync_preservesOmittedArgument() {
    omittedArgumentRemainsOmitted(false);
  }

  @Test
  public void mcpAsyncToolRunAsync_preservesOmittedArgument() {
    omittedArgumentRemainsOmitted(true);
  }

  @Test
  public void mcpToolRunAsync_keepsUnmodifiableArgumentSnapshot() {
    requestArgumentsRemainAnUnmodifiableSnapshot(false);
  }

  @Test
  public void mcpAsyncToolRunAsync_keepsUnmodifiableArgumentSnapshot() {
    requestArgumentsRemainAnUnmodifiableSnapshot(true);
  }

  @Test
  public void mcpToolRunAsync_preservesNestedNull() {
    nestedNullIsAlreadyPreserved(false);
  }

  @Test
  public void mcpAsyncToolRunAsync_preservesNestedNull() {
    nestedNullIsAlreadyPreserved(true);
  }

  @Test
  public void mcpToolRunAsync_functionDispatchPreservesExplicitNull() {
    functionDispatchPreservesExplicitNull(false);
  }

  @Test
  public void mcpAsyncToolRunAsync_functionDispatchPreservesExplicitNull() {
    functionDispatchPreservesExplicitNull(true);
  }

  private void explicitNullReachesTheMcpClient(boolean async) throws Exception {
    NullableArgumentsFixture fixture = new NullableArgumentsFixture(async);
    Map<String, Object> arguments = nullableArguments();

    CallToolRequest request = fixture.invoke(arguments);

    assertThat(request.arguments()).containsExactly("value", null);
    assertThat(McpJsonDefaults.getMapper().writeValueAsString(request)).contains("\"value\":null");
    assertThat(arguments).containsExactly("value", null);
    verifyNoInteractions(fixture.manager);
  }

  private void omittedArgumentRemainsOmitted(boolean async) {
    NullableArgumentsFixture fixture = new NullableArgumentsFixture(async);
    CallToolRequest request = fixture.invoke(ImmutableMap.of());

    assertThat(request.arguments()).isEmpty();
    verifyNoInteractions(fixture.manager);
  }

  private void requestArgumentsRemainAnUnmodifiableSnapshot(boolean async) {
    NullableArgumentsFixture fixture = new NullableArgumentsFixture(async);
    Map<String, Object> arguments = nullableArguments();
    CallToolRequest request = fixture.invoke(arguments);

    arguments.put("value", "changed");
    arguments.put("extra", "added");

    assertThat(request.arguments()).containsExactly("value", null);
    assertThrows(
        UnsupportedOperationException.class, () -> request.arguments().put("value", "changed"));
    verifyNoInteractions(fixture.manager);
  }

  private void nestedNullIsAlreadyPreserved(boolean async) {
    NullableArgumentsFixture fixture = new NullableArgumentsFixture(async);
    CallToolRequest request = fixture.invoke(ImmutableMap.of("nested", nullableArguments()));

    assertThat((Map<?, ?>) request.arguments().get("nested")).containsExactly("value", null);
    verifyNoInteractions(fixture.manager);
  }

  private void functionDispatchPreservesExplicitNull(boolean async) {
    NullableArgumentsFixture fixture = new NullableArgumentsFixture(async);
    InvocationContext context =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build())).build());
    Event call =
        createEvent("call").toBuilder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(
                        ImmutableList.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .id("call-1")
                                        .name("set_value")
                                        .args(nullableArguments())
                                        .build())
                                .build()))
                    .build())
            .build();

    Event response =
        Functions.handleFunctionCalls(
                context, call, ImmutableMap.of(fixture.tool.name(), fixture.tool))
            .timeout(5, SECONDS)
            .blockingGet();

    assertThat(response.functionResponses()).hasSize(1);
    assertThat(response.functionResponses().get(0).id()).hasValue("call-1");
    assertThat(fixture.capturedRequest().arguments()).containsExactly("value", null);
    verifyNoInteractions(fixture.manager);
  }

  private static final class NullableArgumentsFixture {
    private final boolean async;
    private final McpSyncClient syncClient = mock(McpSyncClient.class);
    private final McpAsyncClient asyncClient = mock(McpAsyncClient.class);
    private final McpSessionManager manager = mock(McpSessionManager.class);
    private final BaseTool tool;

    NullableArgumentsFixture(boolean async) {
      this.async = async;
      CallToolResult result =
          CallToolResult.builder(ImmutableList.of(TextContent.builder("ok").build())).build();
      // Keep retries deterministic if the adapter fails before reaching the SDK.
      if (async) {
        when(asyncClient.callTool(any())).thenReturn(Mono.just(result));
        when(manager.createAsyncSession()).thenReturn(asyncClient);
        when(asyncClient.initialize())
            .thenReturn(
                Mono.just(
                    McpSchema.InitializeResult.builder(
                            "2025-06-18",
                            McpSchema.ServerCapabilities.builder().build(),
                            McpSchema.Implementation.builder("server", "1.0").build())
                        .build()));
        tool = new McpAsyncTool(NULLABLE_TOOL, asyncClient, manager);
      } else {
        when(syncClient.callTool(any())).thenReturn(result);
        when(manager.createSession()).thenReturn(syncClient);
        tool = new McpTool(NULLABLE_TOOL, syncClient, manager);
      }
    }

    private CallToolRequest invoke(Map<String, Object> arguments) {
      Map<String, Object> result =
          tool.runAsync(arguments, mock(ToolContext.class)).timeout(5, SECONDS).blockingGet();
      assertThat(result).containsKey("text_output");
      return capturedRequest();
    }

    private CallToolRequest capturedRequest() {
      ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
      if (async) {
        verify(asyncClient).callTool(request.capture());
      } else {
        verify(syncClient).callTool(request.capture());
      }
      return request.getValue();
    }
  }

  private static Map<String, Object> nullableArguments() {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("value", null);
    return arguments;
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
