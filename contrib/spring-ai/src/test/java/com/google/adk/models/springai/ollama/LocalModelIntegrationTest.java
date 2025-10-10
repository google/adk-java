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
package com.google.adk.models.springai.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.SpringAI;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

@EnabledIfEnvironmentVariable(named = "ADK_RUN_INTEGRATION_TESTS", matches = "true")
class LocalModelIntegrationTest {

  private static OllamaTestContainer ollamaContainer;
  private static SpringAI springAI;

  @BeforeAll
  static void setUpBeforeClass() {
    ollamaContainer = new OllamaTestContainer();
    ollamaContainer.start();

    OllamaApi ollamaApi = OllamaApi.builder().baseUrl(ollamaContainer.getBaseUrl()).build();
    OllamaChatOptions options =
        OllamaChatOptions.builder().model(ollamaContainer.getModelName()).build();

    OllamaChatModel chatModel =
        OllamaChatModel.builder().ollamaApi(ollamaApi).defaultOptions(options).build();
    springAI = new SpringAI(chatModel, ollamaContainer.getModelName());
  }

  @AfterAll
  static void tearDownAfterClass() {
    if (ollamaContainer != null) {
      ollamaContainer.stop();
    }
  }

  @Test
  void testBasicTextGeneration() {
    Content userContent =
        Content.builder().role("user").parts(List.of(Part.fromText("What is 2+2?"))).build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(request, false).test();

    testObserver.awaitDone(30, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    testObserver.assertValueCount(1);

    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);

    String responseText = response.content().get().parts().get().get(0).text().orElse("");
    assertThat(responseText).isNotEmpty();
    assertThat(responseText.toLowerCase()).contains("4");
  }

  @Test
  void testStreamingGeneration() {
    Content userContent =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("Write a short poem about cats.")))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(request, true).test();

    testObserver.awaitDone(30, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();

    List<LlmResponse> responses = testObserver.values();
    assertThat(responses).isNotEmpty();

    int totalTextLength = 0;
    for (LlmResponse response : responses) {
      if (response.content().isPresent() && response.content().get().parts().isPresent()) {
        for (Part part : response.content().get().parts().get()) {
          if (part.text().isPresent()) {
            totalTextLength += part.text().get().length();
          }
        }
      }
    }

    assertThat(totalTextLength).isGreaterThan(0);
  }

  @Test
  void testConversationFlow() {
    Content userContent1 =
        Content.builder().role("user").parts(List.of(Part.fromText("My name is Alice."))).build();

    LlmRequest request1 = LlmRequest.builder().contents(List.of(userContent1)).build();

    TestSubscriber<LlmResponse> testObserver1 = springAI.generateContent(request1, false).test();
    testObserver1.awaitDone(30, TimeUnit.SECONDS);
    testObserver1.assertComplete();
    testObserver1.assertNoErrors();

    LlmResponse response1 = testObserver1.values().get(0);
    assertThat(response1.content()).isPresent();

    Content assistantContent = response1.content().get();

    Content userContent2 =
        Content.builder().role("user").parts(List.of(Part.fromText("What is my name?"))).build();

    LlmRequest request2 =
        LlmRequest.builder()
            .contents(List.of(userContent1, assistantContent, userContent2))
            .build();

    TestSubscriber<LlmResponse> testObserver2 = springAI.generateContent(request2, false).test();
    testObserver2.awaitDone(30, TimeUnit.SECONDS);
    testObserver2.assertComplete();
    testObserver2.assertNoErrors();

    LlmResponse response2 = testObserver2.values().get(0);
    String responseText = response2.content().get().parts().get().get(0).text().orElse("");
    assertThat(responseText.toLowerCase()).contains("alice");
  }

  @Test
  void testWithConfiguration() {
    Content userContent =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("Generate a random number between 1 and 10.")))
            .build();

    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.1f).maxOutputTokens(50).build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).config(config).build();

    TestSubscriber<LlmResponse> testObserver = springAI.generateContent(request, false).test();

    testObserver.awaitDone(30, TimeUnit.SECONDS);
    testObserver.assertComplete();
    testObserver.assertNoErrors();
    testObserver.assertValueCount(1);

    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    String responseText = response.content().get().parts().get().get(0).text().orElse("");
    assertThat(responseText).isNotEmpty();
  }

  @Test
  void testModelInformation() {
    assertThat(springAI.model()).isEqualTo(ollamaContainer.getModelName());
  }

  @Test
  void testContainerHealth() {
    assertThat(ollamaContainer.isHealthy()).isTrue();
  }
}
