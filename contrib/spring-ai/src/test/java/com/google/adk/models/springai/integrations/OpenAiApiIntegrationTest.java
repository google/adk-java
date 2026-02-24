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
package com.google.adk.models.springai.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.TestUtils;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Integration tests with real OpenAI API.
 *
 * <p>To run these tests: 1. Set environment variable: export OPENAI_API_KEY=your_actual_api_key 2.
 * Run: mvn test -Dtest=OpenAiApiIntegrationTest
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "\\S+")
class OpenAiApiIntegrationTest {

  private static final String GPT_MODEL = "gpt-4o-mini";

  @Test
  void testSimpleAgentWithRealOpenAiApi() {
    // Create OpenAI model using Spring AI's builder pattern
    OpenAiApi openAiApi = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
    OpenAiChatModel openAiModel = OpenAiChatModel.builder().openAiApi(openAiApi).build();

    // Wrap with SpringAI
    SpringAI springAI = new SpringAI(openAiModel, GPT_MODEL);

    // Create agent
    LlmAgent agent =
        LlmAgent.builder()
            .name("science-teacher")
            .description("Science teacher agent using real OpenAI API")
            .model(springAI)
            .instruction("You are a helpful science teacher. Give concise explanations.")
            .build();

    // Test the agent
    List<Event> events = TestUtils.askAgent(agent, false, "What is a photon?");

    // Verify response
    assertThat(events).hasSize(1);
    Event event = events.get(0);
    assertThat(event.content()).isPresent();

    String response = event.content().get().text();
    System.out.println("OpenAI Response: " + response);

    // Verify it's a real response about photons
    assertThat(response).isNotNull();
    assertThat(response.toLowerCase())
        .containsAnyOf("light", "particle", "electromagnetic", "quantum");
  }

  @Test
  void testStreamingWithRealOpenAiApi() {
    OpenAiApi openAiApi = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
    OpenAiChatModel openAiModel = OpenAiChatModel.builder().openAiApi(openAiApi).build();

    SpringAI springAI = new SpringAI(openAiModel, GPT_MODEL);

    // Test streaming directly
    Content userContent =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("Explain quantum mechanics in one sentence.")))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    TestSubscriber<LlmResponse> testSubscriber = springAI.generateContent(request, true).test();

    // Wait for completion
    testSubscriber.awaitDone(30, TimeUnit.SECONDS);
    testSubscriber.assertComplete();
    testSubscriber.assertNoErrors();

    // Verify streaming responses
    List<LlmResponse> responses = testSubscriber.values();
    assertThat(responses).isNotEmpty();

    // Combine all streaming responses
    StringBuilder fullResponse = new StringBuilder();
    for (LlmResponse response : responses) {
      if (response.content().isPresent()) {
        fullResponse.append(response.content().get().text());
      }
    }

    String result = fullResponse.toString();
    System.out.println("Streaming Response: " + result);
    assertThat(result.toLowerCase()).containsAnyOf("quantum", "mechanics", "physics");
  }

  @Test
  void testAgentWithToolsAndRealApi() {
    OpenAiApi openAiApi = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
    OpenAiChatModel openAiModel = OpenAiChatModel.builder().openAiApi(openAiApi).build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("weather-agent")
            .model(new SpringAI(openAiModel, GPT_MODEL))
            .instruction(
                """
                You are a helpful assistant.
                When asked about weather, use the getWeatherInfo function to get current conditions.
                """)
            .tools(FunctionTool.create(WeatherTools.class, "getWeatherInfo"))
            .build();

    List<Event> events =
        TestUtils.askAgent(agent, false, "What's the weather like in San Francisco?");

    // Should have multiple events: function call, function response, final answer
    assertThat(events).hasSizeGreaterThanOrEqualTo(1);

    // Print all events for debugging
    for (int i = 0; i < events.size(); i++) {
      Event event = events.get(i);
      System.out.println("Event " + i + ": " + event.stringifyContent());
    }

    // Verify final response mentions weather
    Event finalEvent = events.get(events.size() - 1);
    assertThat(finalEvent.finalResponse()).isTrue();
    String finalResponse = finalEvent.content().get().text();
    assertThat(finalResponse).isNotNull();
    assertThat(finalResponse.toLowerCase())
        .containsAnyOf("sunny", "weather", "temperature", "san francisco");
  }

  @Test
  void testConfigurationOptions() {
    // Test with custom configuration
    OpenAiChatOptions options =
        OpenAiChatOptions.builder().model(GPT_MODEL).temperature(0.7).maxTokens(100).build();

    OpenAiApi openAiApi = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
    OpenAiChatModel openAiModel =
        OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(options).build();

    SpringAI springAI = new SpringAI(openAiModel, GPT_MODEL);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromText("Say hello in exactly 5 words.")))
                        .build()))
            .build();

    TestSubscriber<LlmResponse> testSubscriber = springAI.generateContent(request, false).test();
    testSubscriber.awaitDone(15, TimeUnit.SECONDS);
    testSubscriber.assertComplete();
    testSubscriber.assertNoErrors();

    List<LlmResponse> responses = testSubscriber.values();
    assertThat(responses).hasSize(1);

    String response = responses.get(0).content().get().text();
    System.out.println("Configured Response: " + response);
    assertThat(response).isNotNull().isNotEmpty();
  }

  public static class WeatherTools {
    public static Map<String, Object> getWeatherInfo(String location) {
      return Map.of(
          "location", location,
          "temperature", "72°F",
          "condition", "sunny and clear",
          "humidity", "45%",
          "forecast", "Perfect weather for outdoor activities!");
    }
  }
}
