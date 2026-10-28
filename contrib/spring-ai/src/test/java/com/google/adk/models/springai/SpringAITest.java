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
package com.google.adk.models.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

class SpringAITest {

  private ChatModel mockChatModel;
  private StreamingChatModel mockStreamingChatModel;
  private LlmRequest testRequest;
  private ChatResponse testChatResponse;

  @BeforeEach
  void setUp() {
    mockChatModel = mock(ChatModel.class);
    mockStreamingChatModel = mock(StreamingChatModel.class);

    // Create test request
    Content userContent =
        Content.builder().role("user").parts(List.of(Part.fromText("Hello, how are you?"))).build();

    testRequest = LlmRequest.builder().contents(List.of(userContent)).build();

    // Create test response
    AssistantMessage assistantMessage = new AssistantMessage("I'm doing well, thank you!");
    Generation generation = new Generation(assistantMessage);
    testChatResponse = new ChatResponse(List.of(generation));
  }

  @Test
  void testConstructorWithChatModel() {
    SpringAI springAI = new SpringAI(mockChatModel);
    assertThat(springAI.model()).isNotEmpty();
  }

  @Test
  void testConstructorWithChatModelAndModelName() {
    String modelName = "test-model";
    SpringAI springAI = new SpringAI(mockChatModel, modelName);
    assertThat(springAI.model()).isEqualTo(modelName);
  }

  @Test
  void testConstructorWithStreamingChatModel() {
    SpringAI springAI = new SpringAI(mockStreamingChatModel);
    assertThat(springAI.model()).isNotEmpty();
  }

  @Test
  void testConstructorWithStreamingChatModelAndModelName() {
    String modelName = "test-streaming-model";
    SpringAI springAI = new SpringAI(mockStreamingChatModel, modelName);
    assertThat(springAI.model()).isEqualTo(modelName);
  }

  @Test
  void testConstructorWithBothModels() {
    String modelName = "test-both-models";
    SpringAI springAI = new SpringAI(mockChatModel, mockStreamingChatModel, modelName);
    assertThat(springAI.model()).isEqualTo(modelName);
  }

  @Test
  void testConstructorWithNullChatModel() {
    assertThrows(NullPointerException.class, () -> new SpringAI((ChatModel) null));
  }

  @Test
  void testConstructorWithNullStreamingChatModel() {
    assertThrows(NullPointerException.class, () -> new SpringAI((StreamingChatModel) null));
  }

  @Test
  void testConstructorWithNullModelName() {
    assertThrows(NullPointerException.class, () -> new SpringAI(mockChatModel, (String) null));
  }

  @Test
  void testGenerateContentNonStreaming() {
    when(mockChatModel.call(any(Prompt.class))).thenReturn(testChatResponse);

    SpringAI springAI = new SpringAI(mockChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, false).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    testObserver.assertValueCount(1);

    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);
    assertThat(response.content().get().parts().get().get(0).text())
        .contains("I'm doing well, thank you!");
  }

  @Test
  void testGenerateContentStreaming() {
    Flux<ChatResponse> responseFlux =
        Flux.just(
            createStreamingChatResponse("I'm"),
            createStreamingChatResponse(" doing"),
            createStreamingChatResponse(" well!"));

    when(mockStreamingChatModel.stream(any(Prompt.class))).thenReturn(responseFlux);

    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    // Three partial chunks and the aggregated response.
    testObserver.assertValueCount(4);

    List<LlmResponse> responses = testObserver.values();

    // Verify each streaming response
    for (LlmResponse response : responses) {
      assertThat(response.content()).isPresent();
      assertThat(response.content().get().parts()).isPresent();
    }
  }

  @Test
  void testGenerateContentNonStreamingWithoutChatModel() {
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, false).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertError(IllegalStateException.class);
  }

  @Test
  void testGenerateContentStreamingWithoutStreamingChatModel() throws InterruptedException {
    // Create a ChatModel that explicitly does not implement StreamingChatModel
    ChatModel nonStreamingChatModel =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            return testChatResponse;
          }
        };

    SpringAI springAI = new SpringAI(nonStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.await(5, TimeUnit.SECONDS);
    testObserver.assertError(
        throwable ->
            (throwable instanceof IllegalStateException
                    && throwable.getMessage().contains("StreamingChatModel is not configured"))
                || (throwable instanceof RuntimeException
                    && throwable.getMessage().contains("streaming is not supported")));
  }

  @Test
  void testGenerateContentWithException() {
    when(mockChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Test exception"));

    SpringAI springAI = new SpringAI(mockChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, false).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertError(RuntimeException.class);
  }

  @Test
  void testGenerateContentStreamingWithException() {
    Flux<ChatResponse> errorFlux = Flux.error(new RuntimeException("Streaming test exception"));
    when(mockStreamingChatModel.stream(any(Prompt.class))).thenReturn(errorFlux);

    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertError(RuntimeException.class);
  }

  @Test
  void testConnect() {
    SpringAI springAI = new SpringAI(mockChatModel);

    assertThrows(UnsupportedOperationException.class, () -> springAI.connect(testRequest));
  }

  @Test
  void testExtractModelName() {
    // Test with ChatModel mock
    SpringAI springAI1 = new SpringAI(mockChatModel);
    assertThat(springAI1.model()).contains("mock");

    // Test with StreamingChatModel mock
    SpringAI springAI2 = new SpringAI(mockStreamingChatModel);
    assertThat(springAI2.model()).contains("mock");
  }

  @Test
  void testGenerateContentWithEmptyResponse() {
    ChatResponse emptyChatResponse = new ChatResponse(List.of());
    when(mockChatModel.call(any(Prompt.class))).thenReturn(emptyChatResponse);

    SpringAI springAI = new SpringAI(mockChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, false).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    testObserver.assertValueCount(1);

    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isEmpty();
  }

  @Test
  void testGenerateContentStreamingBackpressure() {
    // Create a large number of streaming responses to test backpressure
    Flux<ChatResponse> largeResponseFlux =
        Flux.range(1, 1000)
            .map(i -> createStreamingChatResponse("Token " + i))
            .delayElements(Duration.ofMillis(1));

    when(mockStreamingChatModel.stream(any(Prompt.class))).thenReturn(largeResponseFlux);

    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(10, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    // 1000 partial chunks and the aggregated response.
    testObserver.assertValueCount(1001);
  }

  @Test
  void generateContent_streaming_emitsPartialChunksThenAggregatedResponse() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                createStreamingChatResponse("Hello there."),
                createStreamingChatResponse(" How are"),
                createStreamingChatResponse(" you?")));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertComplete();
    List<LlmResponse> responses = testObserver.values();
    assertThat(responses).hasSize(4);
    assertThat(responses.subList(0, 3))
        .allSatisfy(
            response -> {
              assertThat(response.partial()).hasValue(true);
              assertThat(response.turnComplete()).isEmpty();
            });
    assertThat(responses.subList(0, 3))
        .extracting(response -> response.content().get().text())
        .containsExactly("Hello there.", " How are", " you?");
    LlmResponse aggregated = responses.get(3);
    assertThat(aggregated.partial().orElse(false)).isFalse();
    assertThat(aggregated.turnComplete()).hasValue(true);
    assertThat(aggregated.content().get().text()).isEqualTo("Hello there. How are you?");
  }

  @Test
  void generateContent_streaming_aggregatedResponseCarriesLastUsage() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                createStreamingChatResponse("Hi", new DefaultUsage(3, 1, 4)),
                createStreamingChatResponse(" there", new DefaultUsage(7, 5, 12))));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    LlmResponse aggregated = testObserver.values().get(testObserver.values().size() - 1);
    assertThat(aggregated.usageMetadata().get().totalTokenCount()).hasValue(12);
  }

  @Test
  void generateContent_streamingCutShort_aggregatedResponseCarriesFinishReasonAndError() {
    ChatResponse lastChunk =
        new ChatResponse(
            List.of(
                new Generation(
                    new AssistantMessage(" there"),
                    ChatGenerationMetadata.builder().finishReason("MAX_TOKENS").build())));
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                createStreamingChatResponse("Hi"),
                lastChunk,
                createStreamingChatResponse("", new DefaultUsage(3, 1, 4))));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    List<LlmResponse> responses = testObserver.values();
    assertThat(responses.subList(0, responses.size() - 1))
        .allSatisfy(response -> assertThat(response.errorCode()).isEmpty());
    LlmResponse aggregated = responses.get(responses.size() - 1);
    assertThat(aggregated.partial().orElse(false)).isFalse();
    assertThat(aggregated.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.MAX_TOKENS);
    assertThat(aggregated.errorCode().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.MAX_TOKENS);
    assertThat(aggregated.errorMessage()).hasValue("Maximum tokens reached");
  }

  @Test
  void generateContent_streamingStopOnEmptyLastChunk_aggregatedResponseCarriesStopWithoutError() {
    ChatResponse emptyStopChunk =
        new ChatResponse(
            List.of(
                new Generation(
                    new AssistantMessage(""),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())));
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(createStreamingChatResponse("Hi"), emptyStopChunk));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    LlmResponse aggregated = testObserver.values().get(testObserver.values().size() - 1);
    assertThat(aggregated.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.STOP);
    assertThat(aggregated.errorCode()).isEmpty();
  }

  @Test
  void generateContent_streamingThoughts_aggregatedResponseKeepsThoughtsApartFromText() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                createThoughtChatResponse("Let me "),
                createThoughtChatResponse("think."),
                createStreamingChatResponse("Hello"),
                createStreamingChatResponse(" there.")));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    List<Part> parts =
        testObserver.values().get(testObserver.values().size() - 1).content().get().parts().get();
    assertThat(parts)
        .extracting(part -> part.text().get())
        .containsExactly("Let me think.", "Hello there.");
    assertThat(parts).extracting(part -> part.thought().orElse(false)).containsExactly(true, false);
  }

  @Test
  void generateContent_streamingMedia_aggregatedResponseKeepsMediaParts() {
    AssistantMessage imageMessage =
        AssistantMessage.builder()
            .content("")
            .media(
                List.of(
                    new Media(
                        MimeType.valueOf("image/png"), new ByteArrayResource(new byte[] {1, 2}))))
            .build();
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                createStreamingChatResponse("Here:"),
                new ChatResponse(List.of(new Generation(imageMessage)))));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    List<Part> parts =
        testObserver.values().get(testObserver.values().size() - 1).content().get().parts().get();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("Here:");
    assertThat(parts.get(1).inlineData().flatMap(Blob::mimeType)).hasValue("image/png");
  }

  @Test
  void generateContent_streaming_aggregatedResponseCarriesModelVersion() {
    ChatResponse lastChunk =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage(" there"))),
            ChatResponseMetadata.builder().model("gemini-test-001").build());
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(createStreamingChatResponse("Hi"), lastChunk));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    LlmResponse aggregated = testObserver.values().get(testObserver.values().size() - 1);
    assertThat(aggregated.modelVersion()).hasValue("gemini-test-001");
  }

  @Test
  void generateContent_streamingWithoutContent_endsWithResponseCarryingUsage() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(createStreamingChatResponse("", new DefaultUsage(3, 0, 3))));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertValueCount(1);
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.partial().orElse(false)).isFalse();
    assertThat(response.turnComplete()).hasValue(true);
    assertThat(response.content()).isEmpty();
    assertThat(response.usageMetadata().get().totalTokenCount()).hasValue(3);
  }

  @Test
  void generateContent_streamingWithoutChunks_emitsNothing() {
    when(mockStreamingChatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoValues();
  }

  @Test
  void generateContent_streamingFunctionCallWithoutId_aggregatedReusesPartialId() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(createToolCallChatResponse("", "get_weather")));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    List<LlmResponse> responses = testObserver.values();
    assertThat(responses).hasSize(2);
    FunctionCall partialCall = onlyFunctionCall(responses.get(0));
    FunctionCall aggregatedCall = onlyFunctionCall(responses.get(1));
    assertThat(aggregatedCall.name()).hasValue("get_weather");
    assertThat(aggregatedCall.id().get()).isNotEmpty().isEqualTo(partialCall.id().get());
  }

  @Test
  void generateContent_streamingTextAndFunctionCalls_aggregatesTextThenCallsInOrder() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                createStreamingChatResponse("Let me check."),
                createToolCallChatResponse("call_1", "get_weather"),
                createToolCallChatResponse("call_2", "get_time")));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    Content aggregated = testObserver.values().get(3).content().get();
    assertThat(aggregated.role()).hasValue("model");
    assertThat(aggregated.parts().get())
        .extracting(
            part ->
                part.text().orElseGet(() -> part.functionCall().flatMap(FunctionCall::id).get()))
        .containsExactly("Let me check.", "call_1", "call_2");
  }

  @Test
  void generateContent_streamingFunctionCallWithId_keepsProviderId() {
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(createToolCallChatResponse("call_1", "get_weather")));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    assertThat(testObserver.values())
        .extracting(response -> onlyFunctionCall(response).id().get())
        .containsExactly("call_1", "call_1");
  }

  @Test
  void generateContent_streamingError_isDeliveredOnce() {
    List<Throwable> undeliverable = new ArrayList<>();
    Consumer<? super Throwable> previousHandler = RxJavaPlugins.getErrorHandler();
    RxJavaPlugins.setErrorHandler(undeliverable::add);
    try {
      when(mockStreamingChatModel.stream(any(Prompt.class)))
          .thenReturn(Flux.error(new IllegalStateException("boom")));
      SpringAI springAI = new SpringAI(mockStreamingChatModel);

      TestSubscriber<LlmResponse> testObserver = springAI.generateContent(testRequest, true).test();

      testObserver.awaitDone(5, TimeUnit.SECONDS);
      testObserver.assertError(RuntimeException.class);
      assertThat(undeliverable).isEmpty();
    } finally {
      RxJavaPlugins.setErrorHandler(previousHandler);
    }
  }

  @Test
  void generateContent_streamingCancelled_cancelsUnderlyingStream() {
    AtomicBoolean cancelled = new AtomicBoolean();
    when(mockStreamingChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
    SpringAI springAI = new SpringAI(mockStreamingChatModel);

    springAI.generateContent(testRequest, true).test().cancel();

    assertThat(cancelled).isTrue();
  }

  private static FunctionCall onlyFunctionCall(LlmResponse response) {
    List<Part> parts = response.content().get().parts().get();
    assertThat(parts).hasSize(1);
    return parts.get(0).functionCall().get();
  }

  private ChatResponse createStreamingChatResponse(String text) {
    AssistantMessage assistantMessage = new AssistantMessage(text);
    Generation generation = new Generation(assistantMessage);
    return new ChatResponse(List.of(generation));
  }

  private static ChatResponse createStreamingChatResponse(String text, DefaultUsage usage) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text))),
        ChatResponseMetadata.builder().usage(usage).build());
  }

  private static ChatResponse createThoughtChatResponse(String text) {
    AssistantMessage message =
        AssistantMessage.builder().content(text).properties(Map.of("isThought", true)).build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  private static ChatResponse createToolCallChatResponse(String id, String name) {
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(id, "function", name, "{\"city\":\"Paris\"}")))
            .build();
    return new ChatResponse(List.of(new Generation(message)));
  }
}
