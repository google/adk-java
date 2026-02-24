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
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;

/**
 * Integration tests with real Anthropic API.
 *
 * <p>To run these tests: 1. Set environment variable: export ANTHROPIC_API_KEY=your_actual_api_key
 * 2. Run: mvn test -Dtest=AnthropicApiIntegrationTest
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "\\S+")
class AnthropicApiIntegrationTest {

  private static final String CLAUDE_MODEL = "claude-sonnet-4-5";

  @Test
  void testSimpleAgentWithRealAnthropicApi() throws InterruptedException {
    // Add delay to avoid rapid requests
    Thread.sleep(2000);

    // Create Anthropic model using Spring AI's builder pattern
    AnthropicApi anthropicApi =
        AnthropicApi.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    AnthropicChatModel anthropicModel =
        AnthropicChatModel.builder().anthropicApi(anthropicApi).build();

    // Wrap with SpringAI
    SpringAI springAI = new SpringAI(anthropicModel, CLAUDE_MODEL);

    // Create agent
    LlmAgent agent =
        LlmAgent.builder()
            .name("science-teacher")
            .description("Science teacher agent using real Anthropic API")
            .model(springAI)
            .instruction("You are a helpful science teacher. Give concise explanations.")
            .build();

    // Test the agent
    List<Event> events = TestUtils.askAgent(agent, false, "What is a qubit?");

    // Verify response
    assertThat(events).hasSize(1);
    Event event = events.get(0);
    assertThat(event.content()).isPresent();

    String response = event.content().get().text();
    System.out.println("Anthropic Response: " + response);

    // Verify it's a real response about photons
    assertThat(response).isNotNull();
    assertThat(response.toLowerCase())
        .containsAnyOf("light", "particle", "electromagnetic", "quantum");
  }

  @Test
  void testStreamingWithRealAnthropicApi() throws InterruptedException {
    // Add delay to avoid rapid requests
    Thread.sleep(2000);

    AnthropicApi anthropicApi =
        AnthropicApi.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    AnthropicChatModel anthropicModel =
        AnthropicChatModel.builder().anthropicApi(anthropicApi).build();

    SpringAI springAI = new SpringAI(anthropicModel, CLAUDE_MODEL);

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
    AnthropicApi anthropicApi =
        AnthropicApi.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    AnthropicChatModel anthropicModel =
        AnthropicChatModel.builder().anthropicApi(anthropicApi).build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("weather-agent")
            .model(new SpringAI(anthropicModel, CLAUDE_MODEL))
            .instruction(
                """
                You are a helpful assistant.
                When asked about weather, you MUST use the getWeatherInfo function to get current conditions.
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
  void testDirectComparisonNonStreamingVsStreaming() throws InterruptedException {
    // Test both non-streaming and streaming with the same model to compare behavior
    AnthropicApi anthropicApi =
        AnthropicApi.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    AnthropicChatModel anthropicModel =
        AnthropicChatModel.builder().anthropicApi(anthropicApi).build();

    SpringAI springAI = new SpringAI(anthropicModel, CLAUDE_MODEL);

    // Same request for both tests
    Content userContent =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("What is the speed of light?")))
            .build();
    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    // Test non-streaming first
    TestSubscriber<LlmResponse> nonStreamingSubscriber =
        springAI.generateContent(request, false).test();
    nonStreamingSubscriber.awaitDone(30, TimeUnit.SECONDS);
    nonStreamingSubscriber.assertComplete();
    nonStreamingSubscriber.assertNoErrors();

    // Add assertions for non-streaming response
    List<LlmResponse> nonStreamingResponses = nonStreamingSubscriber.values();
    assertThat(nonStreamingResponses).isNotEmpty();

    LlmResponse nonStreamingResponse = nonStreamingResponses.get(0);
    assertThat(nonStreamingResponse).isNotNull();
    assertThat(nonStreamingResponse.content()).isPresent();

    Content content = nonStreamingResponse.content().get();
    assertThat(content.parts()).isPresent();
    assertThat(content.parts().get()).isNotEmpty();

    Part firstPart = content.parts().get().get(0);
    assertThat(firstPart.text()).isPresent();

    String nonStreamingText = firstPart.text().get();
    assertThat(nonStreamingText).isNotEmpty();
    assertThat(nonStreamingResponse.turnComplete().get()).isEqualTo(true);

    System.out.println("Non-streaming response: " + nonStreamingText);

    // Wait a bit before streaming test
    Thread.sleep(3000);

    // Test streaming
    TestSubscriber<LlmResponse> streamingSubscriber =
        springAI.generateContent(request, true).test();
    streamingSubscriber.awaitDone(30, TimeUnit.SECONDS);
    streamingSubscriber.assertComplete();
    streamingSubscriber.assertNoErrors();

    // Add assertions for streaming responses
    List<LlmResponse> streamingResponses = streamingSubscriber.values();
    assertThat(streamingResponses).isNotEmpty();

    // Verify streaming responses contain content
    StringBuilder streamingTextBuilder = new StringBuilder();
    for (LlmResponse response : streamingResponses) {
      if (response.content().isPresent()) {
        Content responseContent = response.content().get();
        if (responseContent.parts().isPresent() && !responseContent.parts().get().isEmpty()) {
          for (Part part : responseContent.parts().get()) {
            if (part.text().isPresent()) {
              streamingTextBuilder.append(part.text().get());
            }
          }
        }
      }
    }

    String streamingText = streamingTextBuilder.toString();
    assertThat(streamingText).isNotEmpty();

    // Verify final streaming response turnComplete status
    LlmResponse lastStreamingResponse = streamingResponses.get(streamingResponses.size() - 1);
    // For streaming, turnComplete may be empty or false for intermediate chunks
    // Check if present and verify the value
    if (lastStreamingResponse.turnComplete().isPresent()) {
      // If present, it should indicate completion status
      assertThat(lastStreamingResponse.turnComplete().get()).isInstanceOf(Boolean.class);
    }

    System.out.println("Streaming response: " + streamingText);

    // Verify both responses contain relevant information about speed of light
    assertThat(nonStreamingText.toLowerCase())
        .containsAnyOf("light", "speed", "299", "300", "kilometer", "meter");
    assertThat(streamingText.toLowerCase())
        .containsAnyOf("light", "speed", "299", "300", "kilometer", "meter");
  }

  @Test
  void testConfigurationOptions() {
    // Test with custom configuration
    AnthropicChatOptions options =
        AnthropicChatOptions.builder().model(CLAUDE_MODEL).temperature(0.7).maxTokens(100).build();

    AnthropicApi anthropicApi =
        AnthropicApi.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    AnthropicChatModel anthropicModel =
        AnthropicChatModel.builder().anthropicApi(anthropicApi).defaultOptions(options).build();

    SpringAI springAI = new SpringAI(anthropicModel, CLAUDE_MODEL);

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
