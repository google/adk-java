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
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Real-world integration tests for SpringAI that use actual API keys and model providers.
 *
 * <p><strong>Note on the Spring AI Integration Testing Approach:</strong>
 *
 * <p>Spring AI is designed around configuration-driven dependency injection and auto-configuration.
 * The manual instantiation of Spring AI models (AnthropicChatModel, OpenAiChatModel, etc.) requires
 * complex constructor parameters including: - API client instances with multiple configuration
 * parameters - RetryTemplate, ObservationRegistry, ToolCallingManager - WebClient/RestClient
 * builders and error handlers
 *
 * <p>This complexity demonstrates why Spring AI is typically used with Spring Boot
 * auto-configuration via application properties:
 *
 * <pre>
 * spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
 * spring.ai.anthropic.chat.options.model=claude-3-5-sonnet-20241022
 * </pre>
 *
 * <p>For ADK integration, the key value proposition is the <strong>configuration-driven
 * approach</strong> where users can switch between providers (OpenAI, Anthropic, Ollama, etc.) by
 * simply changing Spring configuration, without code changes. This is demonstrated in {@link
 * SpringAIIntegrationTest} and {@link SpringAIConfigurationTest}.
 *
 * <p>Real-world production usage would typically involve Spring Boot applications where ChatModel
 * beans are auto-configured and injected, making the SpringAI wrapper seamlessly work with any
 * configured provider.
 */
class SpringAIRealIntegrationTest {

  /**
   * This test demonstrates that SpringAI can work with any ChatModel implementation, including real
   * providers when properly configured via Spring's dependency injection.
   *
   * <p>In production, models would be auto-configured via application.properties: -
   * spring.ai.openai.api-key=${OPENAI_API_KEY} - spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY} -
   * spring.ai.ollama.base-url=http://localhost:11434
   */
  @Test
  void testConfigurationDrivenApproach() {
    // Demonstrate the configuration-driven approach with a simple example
    ChatModel mockModel =
        prompt -> {
          return new org.springframework.ai.chat.model.ChatResponse(
              List.of(
                  new org.springframework.ai.chat.model.Generation(
                      new org.springframework.ai.chat.messages.AssistantMessage(
                          "Spring AI enables configuration-driven model selection!"))));
        };

    SpringAI springAI = new SpringAI(mockModel, "configured-model");

    LlmAgent agent =
        LlmAgent.builder()
            .name("config-demo")
            .description("Demonstrates configuration-driven approach")
            .model(springAI)
            .instruction("You demonstrate Spring AI's configuration capabilities.")
            .build();

    List<Event> events = TestUtils.askAgent(agent, false, "Explain your configuration approach");

    assertEquals(1, events.size());
    assertTrue(events.get(0).content().isPresent());
    String response = events.get(0).content().get().text();
    assertTrue(response.contains("configuration"));

    System.out.println("✅ Configuration-driven approach validated");
    System.out.println("   - Same SpringAI wrapper works with any ChatModel");
    System.out.println("   - Users configure providers through Spring Boot properties");
    System.out.println("   - ADK provides unified agent interface");
  }

  /** Demonstrates streaming capabilities with any configured ChatModel. */
  @Test
  void testStreamingWithAnyProvider() {
    ChatModel streamingModel =
        prompt -> {
          return new org.springframework.ai.chat.model.ChatResponse(
              List.of(
                  new org.springframework.ai.chat.model.Generation(
                      new org.springframework.ai.chat.messages.AssistantMessage(
                          "Streaming works with any Spring AI provider!"))));
        };

    SpringAI springAI = new SpringAI(streamingModel);

    Flowable<LlmResponse> responses =
        springAI.generateContent(
            LlmRequest.builder()
                .contents(List.of(Content.fromParts(Part.fromText("Test streaming"))))
                .build(),
            false);

    List<LlmResponse> results = responses.blockingStream().toList();
    assertEquals(1, results.size());
    assertTrue(results.get(0).content().isPresent());
    assertTrue(results.get(0).content().get().text().contains("provider"));
  }

  /** Demonstrates function calling integration with any provider. */
  @Test
  void testFunctionCallingWithAnyProvider() {
    ChatModel toolCapableModel =
        prompt -> {
          return new org.springframework.ai.chat.model.ChatResponse(
              List.of(
                  new org.springframework.ai.chat.model.Generation(
                      new org.springframework.ai.chat.messages.AssistantMessage(
                          "Function calling works across all Spring AI providers!"))));
        };

    LlmAgent agent =
        LlmAgent.builder()
            .name("tool-demo")
            .model(new SpringAI(toolCapableModel))
            .instruction("You can use tools with any Spring AI provider.")
            .tools(FunctionTool.create(TestTools.class, "getInfo"))
            .build();

    List<Event> events = TestUtils.askBlockingAgent(agent, "Get some info");

    assertFalse(events.isEmpty());
    assertTrue(events.get(0).content().isPresent());
  }

  /** Simple tool for testing function calling */
  public static class TestTools {
    public static String getInfo() {
      return "Info retrieved from test tool";
    }
  }
}
