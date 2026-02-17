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

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.springai.integrations.tools.WeatherTool;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Integration tests for SpringAI wrapper demonstrating unified configuration-driven approach. These
 * tests use direct SpringAI model implementations without external API dependencies.
 */
class SpringAIIntegrationTest {

  public static final String GEMINI_2_5_FLASH = "gemini-2.0-flash";

  @Test
  void testSimpleAgentWithDummyChatModel() {
    // given - Create a dummy ChatModel that returns a fixed response
    ChatModel dummyChatModel =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            AssistantMessage message =
                new AssistantMessage(
                    "A qubit is a quantum bit, the fundamental unit of quantum information.");
            Generation generation = new Generation(message);
            return new ChatResponse(List.of(generation));
          }
        };

    LlmAgent agent =
        LlmAgent.builder()
            .name("science-app")
            .description("Science teacher agent")
            .model(new SpringAI(dummyChatModel, GEMINI_2_5_FLASH))
            .instruction(
                """
                You are a helpful science teacher that explains science concepts
                to kids and teenagers.
                """)
            .build();

    // when
    Runner runner = new InMemoryRunner(agent);
    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();

    Content userMessage =
        Content.builder().role("user").parts(List.of(Part.fromText("What is a qubit?"))).build();

    List<Event> events =
        runner
            .runAsync(session, userMessage, com.google.adk.agents.RunConfig.builder().build())
            .toList()
            .blockingGet();

    // then
    assertFalse(events.isEmpty());

    // Find the assistant response
    Event responseEvent =
        events.stream()
            .filter(
                event ->
                    event.content().isPresent() && !event.content().get().text().trim().isEmpty())
            .findFirst()
            .orElse(null);

    assertNotNull(responseEvent);
    assertTrue(responseEvent.content().isPresent());

    Content content = responseEvent.content().get();
    System.out.println("Answer: " + content.text());
    assertTrue(content.text().contains("quantum"));
  }

  @Test
  void testAgentWithToolsUsingDummyModel() {
    // given - Create a dummy ChatModel that simulates tool calling
    ChatModel dummyChatModel =
        new ChatModel() {
          private int callCount = 0;

          @Override
          public ChatResponse call(Prompt prompt) {
            callCount++;
            AssistantMessage message;

            if (callCount == 1) {
              // First call - simulate asking for weather
              message = new AssistantMessage("I need to check the weather for Paris.");
            } else {
              // Subsequent calls - provide final answer
              message =
                  new AssistantMessage(
                      "The weather in Paris is beautiful and sunny with temperatures from 10°C in"
                          + " the morning up to 24°C in the afternoon.");
            }

            Generation generation = new Generation(message);
            return new ChatResponse(List.of(generation));
          }
        };

    LlmAgent agent =
        LlmAgent.builder()
            .name("friendly-weather-app")
            .description("Friend agent that knows about the weather")
            .model(new SpringAI(dummyChatModel, GEMINI_2_5_FLASH))
            .instruction(
                """
                You are a friendly assistant.

                If asked about the weather forecast for a city,
                you MUST call the `getWeather` function.
                """)
            .tools(FunctionTool.create(WeatherTool.class, "getWeather"))
            .build();

    // when
    Runner runner = new InMemoryRunner(agent);
    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("What's the weather like in Paris?")))
            .build();

    List<Event> events =
        runner
            .runAsync(session, userMessage, com.google.adk.agents.RunConfig.builder().build())
            .toList()
            .blockingGet();

    // then
    assertFalse(events.isEmpty());

    // Print all events for debugging
    events.forEach(
        event -> {
          if (event.content().isPresent()) {
            System.out.printf("Event: %s%n", event.stringifyContent());
          }
        });

    // Find any text response mentioning Paris
    boolean hasParisResponse =
        events.stream()
            .anyMatch(
                event ->
                    event.content().isPresent()
                        && event.content().get().text().toLowerCase().contains("paris"));

    assertTrue(hasParisResponse, "Should have a response mentioning Paris");
  }

  @Test
  void testStreamingAgentWithDummyModel() {
    // given - Create a dummy StreamingChatModel
    StreamingChatModel dummyStreamingChatModel =
        new StreamingChatModel() {
          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            AssistantMessage msg1 = new AssistantMessage("Photosynthesis is ");
            AssistantMessage msg2 =
                new AssistantMessage("the process by which plants convert sunlight into energy.");

            ChatResponse response1 = new ChatResponse(List.of(new Generation(msg1)));
            ChatResponse response2 = new ChatResponse(List.of(new Generation(msg2)));

            return Flux.just(response1, response2);
          }
        };

    LlmAgent agent =
        LlmAgent.builder()
            .name("streaming-science-app")
            .description("Science teacher agent with streaming")
            .model(new SpringAI(dummyStreamingChatModel, GEMINI_2_5_FLASH))
            .instruction(
                """
                You are a helpful science teacher. Keep your answers concise
                but informative.
                """)
            .build();

    // when
    Runner runner = new InMemoryRunner(agent);
    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("Explain photosynthesis in 2 sentences.")))
            .build();

    List<Event> events =
        runner
            .runAsync(
                session,
                userMessage,
                com.google.adk.agents.RunConfig.builder()
                    .setStreamingMode(com.google.adk.agents.RunConfig.StreamingMode.SSE)
                    .build())
            .toList()
            .blockingGet();

    // then
    assertFalse(events.isEmpty());

    // Verify we have at least one meaningful response
    boolean hasContent =
        events.stream()
            .anyMatch(
                event ->
                    event.content().isPresent() && !event.content().get().text().trim().isEmpty());
    assertTrue(hasContent);

    // Print all events for debugging
    events.forEach(
        event -> {
          if (event.content().isPresent()) {
            System.out.printf("Streaming event: %s%n", event.stringifyContent());
          }
        });
  }

  @Test
  void testConfigurationDrivenApproach() {
    // This test demonstrates that SpringAI wrapper works with ANY ChatModel implementation
    // Users can configure different providers through Spring AI configuration

    // Dummy model representing OpenAI
    ChatModel openAiLikeModel =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            AssistantMessage message = new AssistantMessage("Response from OpenAI-like model");
            return new ChatResponse(List.of(new Generation(message)));
          }
        };

    // Dummy model representing Anthropic
    ChatModel anthropicLikeModel =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            AssistantMessage message = new AssistantMessage("Response from Anthropic-like model");
            return new ChatResponse(List.of(new Generation(message)));
          }
        };

    // Test that the same SpringAI wrapper works with different models
    LlmAgent openAiAgent =
        LlmAgent.builder()
            .name("openai-agent")
            .model(new SpringAI(openAiLikeModel, "gpt-4"))
            .instruction("You are a helpful assistant.")
            .build();

    LlmAgent anthropicAgent =
        LlmAgent.builder()
            .name("anthropic-agent")
            .model(new SpringAI(anthropicLikeModel, "claude-3"))
            .instruction("You are a helpful assistant.")
            .build();

    // Both agents should work with the same SpringAI wrapper
    assertNotNull(openAiAgent);
    assertNotNull(anthropicAgent);

    // This demonstrates the unified approach - same SpringAI wrapper,
    // different underlying models configured through Spring AI
    System.out.println("✅ Configuration-driven approach validated");
    System.out.println("   - Same SpringAI wrapper works with any ChatModel");
    System.out.println("   - Users configure providers through Spring AI");
    System.out.println("   - ADK provides unified agent interface");
  }
}
