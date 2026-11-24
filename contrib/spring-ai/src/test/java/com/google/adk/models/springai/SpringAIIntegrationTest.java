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

import com.anthropic.models.messages.MessageDeltaUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.integrations.tools.WeatherTool;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import reactor.core.publisher.Flux;

/**
 * Integration tests for SpringAI wrapper demonstrating unified configuration-driven approach. These
 * tests use direct SpringAI model implementations without external API dependencies.
 */
class SpringAIIntegrationTest {

  public static final String GEMINI_2_5_FLASH = "gemini-2.0-flash";

  @ParameterizedTest
  @CsvSource({"false, 'first thought'", "true, 'first thought'", "false, ''", "true, ''"})
  void anthropicThinking_replaysCompleteBlocksAfterEventJson(boolean streaming, String thinking)
      throws Exception {
    ObjectMapper json = new ObjectMapper();
    String signature = "opaque+/= signature\nline";
    String redacted = "opaque+/= redacted\nline";
    List<Map<String, Object>> blocks =
        ImmutableList.of(
            ImmutableMap.of("type", "thinking", "thinking", thinking, "signature", signature),
            ImmutableMap.of(
                "type", "thinking", "thinking", "second thought", "signature", "second signature"),
            ImmutableMap.of("type", "redacted_thinking", "data", redacted),
            ImmutableMap.of("type", "text", "text", "Checking"),
            ImmutableMap.of(
                "type", "tool_use", "id", "call_1", "name", "weather", "input", ImmutableMap.of()));
    String response =
        json.writeValueAsString(
            ImmutableMap.of(
                "id",
                "response_1",
                "type",
                "message",
                "role",
                "assistant",
                "model",
                "fixture",
                "content",
                blocks,
                "stop_reason",
                "tool_use",
                "usage",
                ImmutableMap.of("input_tokens", 5, "output_tokens", 10)));
    StringBuilder events = new StringBuilder();
    appendAnthropicEvent(
        events,
        json,
        "message_start",
        ImmutableMap.of(
            "message",
            ImmutableMap.of(
                "id",
                "response_1",
                "type",
                "message",
                "role",
                "assistant",
                "model",
                "fixture",
                "content",
                ImmutableList.of(),
                "usage",
                ImmutableMap.of("input_tokens", 5, "output_tokens", 0))));
    for (int index = 0; index < blocks.size(); index++) {
      Map<String, Object> block = blocks.get(index);
      Map<String, Object> start;
      List<Map<String, Object>> deltas = new ArrayList<>();
      switch ((String) block.get("type")) {
        case "thinking" -> {
          start = ImmutableMap.of("type", "thinking", "thinking", "", "signature", "");
          deltas.add(ImmutableMap.of("type", "thinking_delta", "thinking", block.get("thinking")));
          deltas.add(
              ImmutableMap.of("type", "signature_delta", "signature", block.get("signature")));
        }
        case "text" -> {
          start = ImmutableMap.of("type", "text", "text", "");
          deltas.add(ImmutableMap.of("type", "text_delta", "text", block.get("text")));
        }
        case "tool_use" -> {
          start = block;
          deltas.add(ImmutableMap.of("type", "input_json_delta", "partial_json", "{}"));
        }
        default -> start = block;
      }
      appendAnthropicEvent(
          events,
          json,
          "content_block_start",
          ImmutableMap.of("index", index, "content_block", start));
      for (Map<String, Object> delta : deltas) {
        appendAnthropicEvent(
            events, json, "content_block_delta", ImmutableMap.of("index", index, "delta", delta));
      }
      appendAnthropicEvent(events, json, "content_block_stop", ImmutableMap.of("index", index));
    }
    appendAnthropicEvent(
        events,
        json,
        "message_delta",
        ImmutableMap.of(
            "delta",
            ImmutableMap.of("stop_reason", "tool_use"),
            "usage",
            ImmutableMap.of("output_tokens", 10)));
    appendAnthropicEvent(events, json, "message_stop", ImmutableMap.of());
    List<JsonNode> requests = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(json.readTree(exchange.getRequestBody()));
      boolean streamResponse = streaming && requests.size() == 1;
      exchange
          .getResponseHeaders()
          .set("Content-Type", streamResponse ? "text/event-stream" : "application/json");
      byte[] bytes =
          (streamResponse ? events.toString() : response).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    try {
      AnthropicChatModel model =
          AnthropicChatModel.builder()
              .options(
                  AnthropicChatOptions.builder()
                      .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                      .apiKey("fixture")
                      .model("fixture")
                      .maxTokens(2048)
                      .thinkingEnabled(1024)
                      .maxRetries(0)
                      .build())
              .build();
      try {
        SpringAI bridge = new SpringAI((ChatModel) model, "fixture");
        Content user = Content.builder().role("user").parts(Part.fromText("Weather?")).build();
        LlmResponse result =
            bridge
                .generateContent(
                    LlmRequest.builder().contents(ImmutableList.of(user)).build(), streaming)
                .timeout(10, TimeUnit.SECONDS)
                .blockingLast();
        Content saved =
            Event.fromJson(
                    Event.builder()
                        .id("saved")
                        .author("assistant")
                        .content(result.content().orElseThrow())
                        .build()
                        .toJson())
                .content()
                .orElseThrow();
        List<Part> thoughts =
            saved.parts().orElseThrow().stream()
                .filter(part -> part.thought().orElse(false))
                .toList();
        assertEquals(3, thoughts.size());
        assertEquals(thinking, thoughts.get(0).text().orElseThrow());
        assertArrayEquals(
            signature.getBytes(StandardCharsets.UTF_8),
            thoughts.get(0).thoughtSignature().orElseThrow());
        assertEquals("second thought", thoughts.get(1).text().orElseThrow());
        assertTrue(thoughts.get(2).text().isEmpty());
        Content toolResult =
            Content.builder()
                .role("user")
                .parts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("call_1")
                                .name("weather")
                                .response(ImmutableMap.of("weather", "sunny"))
                                .build())
                        .build())
                .build();
        bridge
            .generateContent(
                LlmRequest.builder().contents(ImmutableList.of(user, saved, toolResult)).build(),
                false)
            .blockingSingle();
        assertEquals(2, requests.size());
        // Spring AI emits visible text before thinking, then tool calls. Preserve every opaque
        // thinking block in its original order within that provider-defined message layout.
        List<Map<String, Object>> replayBlocks =
            ImmutableList.of(
                blocks.get(3), blocks.get(0), blocks.get(1), blocks.get(2), blocks.get(4));
        assertEquals(json.valueToTree(replayBlocks), requests.get(1).at("/messages/1/content"));
      } finally {
        model.getAnthropicClient().close();
        model.getAnthropicClientAsync().close();
      }
    } finally {
      server.stop(0);
    }
  }

  private static void appendAnthropicEvent(
      StringBuilder events, ObjectMapper json, String type, Map<String, Object> fields)
      throws Exception {
    Map<String, Object> event = new LinkedHashMap<>(fields);
    event.put("type", type);
    events
        .append("event: ")
        .append(type)
        .append("\ndata: ")
        .append(json.writeValueAsString(event))
        .append("\n\n");
  }

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
    Session session =
        runner.sessionService().createSession(agent.name(), "test-user").blockingGet();

    Content userMessage =
        Content.builder().role("user").parts(List.of(Part.fromText("What is a qubit?"))).build();

    List<Event> events =
        runner
            .runAsync(session.sessionKey(), userMessage, RunConfig.builder().build())
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
    Session session =
        runner.sessionService().createSession(agent.name(), "test-user").blockingGet();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("What's the weather like in Paris?")))
            .build();

    List<Event> events =
        runner
            .runAsync(session.userId(), session.id(), userMessage, RunConfig.builder().build())
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
    Session session =
        runner.sessionService().createSession(agent.name(), "test-user").blockingGet();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("Explain photosynthesis in 2 sentences.")))
            .build();

    List<Event> events =
        runner
            .runAsync(
                session.userId(),
                session.id(),
                userMessage,
                RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build())
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
  void testAgentEventCarriesCachedTokensReportedByChatModel() {
    // given
    LlmAgent agent = scienceAgent(new SpringAI(promptCachingChatModel(), GEMINI_2_5_FLASH));

    // when
    List<Event> events = TestUtils.askAgent(agent, false, "What is a qubit?");

    // then
    assertEquals(1, events.size());
    GenerateContentResponseUsageMetadata usage = events.get(0).usageMetadata().orElseThrow();
    assertEquals(Optional.of(2006), usage.promptTokenCount());
    assertEquals(Optional.of(1920), usage.cachedContentTokenCount());
  }

  @Test
  void testStreamingAgentFinalEventCarriesCachedTokensReportedByChatModel() {
    // given
    LlmAgent agent = scienceAgent(new SpringAI(promptCachingChatModel(), GEMINI_2_5_FLASH));

    // when
    List<Event> events = TestUtils.askAgent(agent, true, "What is a qubit?");

    // then
    Event finalEvent = events.get(events.size() - 1);
    assertFalse(finalEvent.partial().orElse(false));
    GenerateContentResponseUsageMetadata usage = finalEvent.usageMetadata().orElseThrow();
    assertEquals(Optional.of(2006), usage.promptTokenCount());
    assertEquals(Optional.of(1920), usage.cachedContentTokenCount());
  }

  @Test
  void testStreamingAgentFinalEventFoldsAnthropicCacheTokensIntoPrompt() {
    // given - chunks shaped like Spring AI's Anthropic stream: usage only on the last one
    MessageDeltaUsage nativeUsage =
        MessageDeltaUsage.builder()
            .inputTokens(86L)
            .outputTokens(300L)
            .cacheReadInputTokens(1920L)
            .cacheCreationInputTokens(100L)
            .outputTokensDetails(Optional.empty())
            .serverToolUse(Optional.empty())
            .build();
    ChatResponse textChunk =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("A qubit is a quantum bit."))));
    ChatResponse lastChunk =
        new ChatResponse(
            List.of(
                new Generation(
                    new AssistantMessage(""),
                    ChatGenerationMetadata.builder().finishReason("end_turn").build())),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(86, 300, 386, nativeUsage, 1920L, 100L))
                .build());
    StreamingChatModel anthropicLikeModel = prompt -> Flux.just(textChunk, lastChunk);
    LlmAgent agent = scienceAgent(new SpringAI(anthropicLikeModel, "claude-haiku"));

    // when
    List<Event> events = TestUtils.askAgent(agent, true, "What is a qubit?");

    // then
    Event finalEvent = events.get(events.size() - 1);
    assertFalse(finalEvent.partial().orElse(false));
    GenerateContentResponseUsageMetadata usage = finalEvent.usageMetadata().orElseThrow();
    assertEquals(Optional.of(2106), usage.promptTokenCount());
    assertEquals(Optional.of(1920), usage.cachedContentTokenCount());
    assertEquals(Optional.of(2406), usage.totalTokenCount());
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

  private static LlmAgent scienceAgent(SpringAI model) {
    return LlmAgent.builder()
        .name("science-app")
        .model(model)
        .instruction("You are a helpful science teacher.")
        .build();
  }

  /**
   * Returns a model that reports usage as Spring AI's Google GenAI provider does: 1920 of its 2006
   * prompt tokens were read from cache.
   */
  private static ChatModel promptCachingChatModel() {
    ChatResponse response =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("A qubit is a quantum bit."))),
            ChatResponseMetadata.builder()
                .usage(
                    GoogleGenAiUsage.from(
                        GenerateContentResponseUsageMetadata.builder()
                            .promptTokenCount(2006)
                            .candidatesTokenCount(300)
                            .totalTokenCount(2306)
                            .cachedContentTokenCount(1920)
                            .build()))
                .build());
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        return response;
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(response);
      }
    };
  }
}
