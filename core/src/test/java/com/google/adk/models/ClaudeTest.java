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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Usage;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ClaudeTest {

  private Claude claude;
  private AnthropicClient mockClient;
  private MessageService mockMessageService;
  private Method partToAnthropicMessageBlockMethod;

  @Before
  public void setUp() throws Exception {
    mockClient = Mockito.mock(AnthropicClient.class);
    mockMessageService = Mockito.mock(MessageService.class);
    when(mockClient.messages()).thenReturn(mockMessageService);

    claude = new Claude("claude-3-opus", mockClient);

    partToAnthropicMessageBlockMethod =
        Claude.class.getDeclaredMethod("partToAnthropicMessageBlock", Part.class);
    partToAnthropicMessageBlockMethod.setAccessible(true);
  }

  // --- Existing partToAnthropicMessageBlock tests ---

  @Test
  public void testPartToAnthropicMessageBlock_mcpTool_legacyTextOutputKey() throws Exception {
    Map<String, Object> responseData =
        ImmutableMap.of("text_output", ImmutableMap.of("text", "Legacy result text"));
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString())
        .isEqualTo("{\"text_output\":{\"text\":\"Legacy result text\"}}");
  }

  @Test
  public void testPartToAnthropicMessageBlock_jsonFallback() throws Exception {
    Map<String, Object> responseData = ImmutableMap.of("custom_key", "custom_value");
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString()).contains("\"custom_key\":\"custom_value\"");
  }

  // --- Streaming tests ---

  @Test
  public void testStreaming_textChunksEmittedAsPartialResponses() {
    Stream<RawMessageStreamEvent> events =
        Stream.of(
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder().index(0).textDelta("Hello").build()),
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder().index(0).textDelta(", world!").build()),
            RawMessageStreamEvent.ofMessageStop(new RawMessageStopEvent.Builder().build()));

    StreamResponse<RawMessageStreamEvent> mockStreamResponse = mockStreamResponse(events);
    when(mockMessageService.createStreaming(
            any(com.anthropic.models.messages.MessageCreateParams.class)))
        .thenReturn(mockStreamResponse);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().text("Say hello").build()))
                        .build()))
            .build();

    List<LlmResponse> responses =
        claude.generateContent(request, /* stream= */ true).toList().blockingGet();

    // Filter out the turnComplete sentinel
    List<LlmResponse> textResponses =
        responses.stream()
            .filter(r -> r.content().isPresent())
            .collect(java.util.stream.Collectors.toList());

    assertThat(textResponses).hasSize(2);
    assertThat(textResponses.get(0).content().get().parts().get().get(0).text().get())
        .isEqualTo("Hello");
    assertThat(textResponses.get(0).partial().get()).isTrue();
    assertThat(textResponses.get(1).content().get().parts().get().get(0).text().get())
        .isEqualTo(", world!");
    assertThat(textResponses.get(1).partial().get()).isTrue();
  }

  @Test
  public void testStreaming_messageStopEmitsTurnComplete() {
    Stream<RawMessageStreamEvent> events =
        Stream.of(RawMessageStreamEvent.ofMessageStop(new RawMessageStopEvent.Builder().build()));

    StreamResponse<RawMessageStreamEvent> mockStreamResponse = mockStreamResponse(events);
    when(mockMessageService.createStreaming(
            any(com.anthropic.models.messages.MessageCreateParams.class)))
        .thenReturn(mockStreamResponse);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().text("Hi").build()))
                        .build()))
            .build();

    List<LlmResponse> responses =
        claude.generateContent(request, /* stream= */ true).toList().blockingGet();

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).turnComplete().get()).isTrue();
  }

  @Test
  public void testStreaming_toolCallAccumulatedAndEmittedOnBlockStop() {
    ToolUseBlock toolUseBlock =
        new ToolUseBlock.Builder()
            .id("tool_abc")
            .name("get_weather")
            .caller(new DirectCaller.Builder().build())
            .input(com.anthropic.core.JsonValue.from(java.util.Collections.emptyMap()))
            .build();

    Stream<RawMessageStreamEvent> events =
        Stream.of(
            RawMessageStreamEvent.ofContentBlockStart(
                new RawContentBlockStartEvent.Builder()
                    .index(0)
                    .contentBlock(toolUseBlock)
                    .build()),
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder()
                    .index(0)
                    .inputJsonDelta("{\"city\":")
                    .build()),
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder()
                    .index(0)
                    .inputJsonDelta("\"London\"}")
                    .build()),
            RawMessageStreamEvent.ofContentBlockStop(
                new RawContentBlockStopEvent.Builder().index(0).build()),
            RawMessageStreamEvent.ofMessageStop(new RawMessageStopEvent.Builder().build()));

    StreamResponse<RawMessageStreamEvent> mockStreamResponse = mockStreamResponse(events);
    when(mockMessageService.createStreaming(
            any(com.anthropic.models.messages.MessageCreateParams.class)))
        .thenReturn(mockStreamResponse);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().text("What's the weather?").build()))
                        .build()))
            .build();

    List<LlmResponse> responses =
        claude.generateContent(request, /* stream= */ true).toList().blockingGet();

    List<LlmResponse> toolResponses =
        responses.stream()
            .filter(r -> r.content().isPresent())
            .collect(java.util.stream.Collectors.toList());

    assertThat(toolResponses).hasSize(1);
    Part functionCallPart = toolResponses.get(0).content().get().parts().get().get(0);
    assertThat(functionCallPart.functionCall().isPresent()).isTrue();
    assertThat(functionCallPart.functionCall().get().id().get()).isEqualTo("tool_abc");
    assertThat(functionCallPart.functionCall().get().name().get()).isEqualTo("get_weather");
    assertThat(functionCallPart.functionCall().get().args().get()).containsEntry("city", "London");
  }

  @Test
  public void testStreaming_mixedTextAndToolCall() {
    ToolUseBlock toolUseBlock =
        new ToolUseBlock.Builder()
            .id("tool_xyz")
            .name("search")
            .caller(new DirectCaller.Builder().build())
            .input(com.anthropic.core.JsonValue.from(java.util.Collections.emptyMap()))
            .build();

    Stream<RawMessageStreamEvent> events =
        Stream.of(
            // Text block first
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder()
                    .index(0)
                    .textDelta("Let me search.")
                    .build()),
            // Tool call block second
            RawMessageStreamEvent.ofContentBlockStart(
                new RawContentBlockStartEvent.Builder()
                    .index(1)
                    .contentBlock(toolUseBlock)
                    .build()),
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder()
                    .index(1)
                    .inputJsonDelta("{\"query\":\"java\"}")
                    .build()),
            RawMessageStreamEvent.ofContentBlockStop(
                new RawContentBlockStopEvent.Builder().index(1).build()),
            RawMessageStreamEvent.ofMessageStop(new RawMessageStopEvent.Builder().build()));

    StreamResponse<RawMessageStreamEvent> mockStreamResponse = mockStreamResponse(events);
    when(mockMessageService.createStreaming(
            any(com.anthropic.models.messages.MessageCreateParams.class)))
        .thenReturn(mockStreamResponse);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().text("Search for java").build()))
                        .build()))
            .build();

    List<LlmResponse> responses =
        claude.generateContent(request, /* stream= */ true).toList().blockingGet();

    List<LlmResponse> contentResponses =
        responses.stream()
            .filter(r -> r.content().isPresent())
            .collect(java.util.stream.Collectors.toList());

    assertThat(contentResponses).hasSize(2);

    // First: partial text
    assertThat(contentResponses.get(0).content().get().parts().get().get(0).text().get())
        .isEqualTo("Let me search.");
    assertThat(contentResponses.get(0).partial().get()).isTrue();

    // Second: function call
    assertThat(
            contentResponses.get(1).content().get().parts().get().get(0).functionCall().isPresent())
        .isTrue();
    assertThat(
            contentResponses
                .get(1)
                .content()
                .get()
                .parts()
                .get()
                .get(0)
                .functionCall()
                .get()
                .name()
                .get())
        .isEqualTo("search");
  }

  @Test
  public void testStreaming_emptyStream_producesNoResponses() {
    StreamResponse<RawMessageStreamEvent> mockStreamResponse = mockStreamResponse(Stream.of());
    when(mockMessageService.createStreaming(
            any(com.anthropic.models.messages.MessageCreateParams.class)))
        .thenReturn(mockStreamResponse);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().text("Hi").build()))
                        .build()))
            .build();

    List<LlmResponse> responses =
        claude.generateContent(request, /* stream= */ true).toList().blockingGet();

    assertThat(responses).isEmpty();
  }

  @Test
  public void testStreaming_toolCallWithInvalidJson_fallsBackToEmptyArgs() {
    ToolUseBlock toolUseBlock =
        new ToolUseBlock.Builder()
            .id("tool_bad")
            .name("broken_tool")
            .caller(new DirectCaller.Builder().build())
            .input(com.anthropic.core.JsonValue.from(java.util.Collections.emptyMap()))
            .build();

    Stream<RawMessageStreamEvent> events =
        Stream.of(
            RawMessageStreamEvent.ofContentBlockStart(
                new RawContentBlockStartEvent.Builder()
                    .index(0)
                    .contentBlock(toolUseBlock)
                    .build()),
            RawMessageStreamEvent.ofContentBlockDelta(
                new RawContentBlockDeltaEvent.Builder()
                    .index(0)
                    .inputJsonDelta("not-valid-json")
                    .build()),
            RawMessageStreamEvent.ofContentBlockStop(
                new RawContentBlockStopEvent.Builder().index(0).build()));

    StreamResponse<RawMessageStreamEvent> mockStreamResponse = mockStreamResponse(events);
    when(mockMessageService.createStreaming(
            any(com.anthropic.models.messages.MessageCreateParams.class)))
        .thenReturn(mockStreamResponse);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder().text("Run it").build()))
                        .build()))
            .build();

    List<LlmResponse> responses =
        claude.generateContent(request, /* stream= */ true).toList().blockingGet();

    List<LlmResponse> toolResponses =
        responses.stream()
            .filter(r -> r.content().isPresent())
            .collect(java.util.stream.Collectors.toList());

    // Tool call is still emitted but with empty args on parse failure
    assertThat(toolResponses).hasSize(1);
    assertThat(toolResponses.get(0).content().get().parts().get().get(0).functionCall().isPresent())
        .isTrue();
    assertThat(
            toolResponses
                .get(0)
                .content()
                .get()
                .parts()
                .get()
                .get(0)
                .functionCall()
                .get()
                .args()
                .get())
        .isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static StreamResponse<RawMessageStreamEvent> mockStreamResponse(
      Stream<RawMessageStreamEvent> events) {
    StreamResponse<RawMessageStreamEvent> mock = Mockito.mock(StreamResponse.class);
    when(mock.stream()).thenReturn(events);
    return mock;
  @Test
  public void testClaudeUsageMapping_ShouldFailWhenMappingIsMissing() throws Exception {
    long inputTokens = 10L;
    long outputTokens = 20L;
    Usage mockUsage = mock(Usage.class);
    when(mockUsage.inputTokens()).thenReturn(inputTokens);
    when(mockUsage.outputTokens()).thenReturn(outputTokens);

    Message mockMessage = mock(Message.class);
    when(mockMessage.usage()).thenReturn(mockUsage);
    when(mockMessage.content()).thenReturn(Collections.emptyList());

    Method convertMethod =
        Claude.class.getDeclaredMethod("convertAnthropicResponseToLlmResponse", Message.class);
    convertMethod.setAccessible(true);
    LlmResponse result = (LlmResponse) convertMethod.invoke(claude, mockMessage);
    assertTrue(result.usageMetadata().isPresent());
    assertEquals(inputTokens, (long) result.usageMetadata().get().promptTokenCount().orElse(0));
    assertEquals(
        outputTokens, (long) result.usageMetadata().get().candidatesTokenCount().orElse(0));
  }
}
