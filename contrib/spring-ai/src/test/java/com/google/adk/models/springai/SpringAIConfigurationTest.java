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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAIConfigurationTest {

  private ChatModel mockChatModel;
  private SpringAI springAI;

  @BeforeEach
  void setUp() {
    mockChatModel = mock(ChatModel.class);
    springAI = new SpringAI(mockChatModel, "test-model");
  }

  @Test
  void testSpringAIWorksWithAnyChatModel() {
    AssistantMessage assistantMessage = new AssistantMessage("Hello from Spring AI!");
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    when(mockChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    Content userContent =
        Content.builder().role("user").parts(List.of(Part.fromText("Hello"))).build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(request, false).test();

    testObserver.awaitDone(5, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    testObserver.assertValueCount(1);

    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);
    assertThat(response.content().get().parts().get().get(0).text())
        .contains("Hello from Spring AI!");
  }

  @Test
  void testModelNameAccess() {
    assertThat(springAI.model()).isEqualTo("test-model");
  }

  @Test
  void testSpringAICanBeConfiguredWithAnyProvider() {
    // This test demonstrates that SpringAI works with any ChatModel implementation
    // Users can configure their preferred provider through Spring AI's configuration
    // without needing provider-specific ADK adapters

    // The SpringAI wrapper remains the same regardless of provider
    assertThat(springAI).isNotNull();
    assertThat(springAI.model()).isEqualTo("test-model");

    // Simulate different provider configurations
    ChatModel mockOpenAiModel = mock(ChatModel.class);
    SpringAI openAiSpringAI = new SpringAI(mockOpenAiModel, "gpt-4o-mini");
    assertThat(openAiSpringAI).isNotNull();
    assertThat(openAiSpringAI.model()).isEqualTo("gpt-4o-mini");

    ChatModel mockAnthropicModel = mock(ChatModel.class);
    SpringAI anthropicSpringAI = new SpringAI(mockAnthropicModel, "claude-4-5-sonnet-20250929");
    assertThat(anthropicSpringAI).isNotNull();
    assertThat(anthropicSpringAI.model()).isEqualTo("claude-4-5-sonnet-20250929");

    ChatModel mockOllamaModel = mock(ChatModel.class);
    SpringAI ollamaSpringAI = new SpringAI(mockOllamaModel, "llama3.2");
    assertThat(ollamaSpringAI).isNotNull();
    assertThat(ollamaSpringAI.model()).isEqualTo("llama3.2");
  }
}
