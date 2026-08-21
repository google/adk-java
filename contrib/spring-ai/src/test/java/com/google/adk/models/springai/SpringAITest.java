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
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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
    testObserver.assertValueCount(3);

    List<LlmResponse> responses = testObserver.values();
    assertThat(responses).hasSize(3);

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
    testObserver.assertValueCount(1000);
  }

  private ChatResponse createStreamingChatResponse(String text) {
    AssistantMessage assistantMessage = new AssistantMessage(text);
    Generation generation = new Generation(assistantMessage);
    return new ChatResponse(List.of(generation));
  }
}
