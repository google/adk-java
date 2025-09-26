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
package com.google.adk.models.langchain4j;

import static com.google.adk.models.langchain4j.RunLoop.askAgent;
import static com.google.adk.models.langchain4j.RunLoop.askAgentStreaming;
import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class LangChain4jIntegrationTest {

  public static final String CLAUDE_3_7_SONNET_20250219 = "claude-3-7-sonnet-20250219";
  public static final String GEMINI_2_0_FLASH = "gemini-2.0-flash";
  public static final String GPT_4_O_MINI = "gpt-4o-mini";

  @Test
  @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "\\S+")
  void testSimpleAgent() {
    // given
    AnthropicChatModel claudeModel =
        AnthropicChatModel.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .modelName(CLAUDE_3_7_SONNET_20250219)
            .build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("science-app")
            .description("Science teacher agent")
            .model(new LangChain4j(claudeModel, CLAUDE_3_7_SONNET_20250219))
            .instruction(
                """
                You are a helpful science teacher that explains science concepts
                to kids and teenagers.
                """)
            .build();

    // when
    List<Event> events = askAgent(agent, "What is a qubit?");

    // then
    assertEquals(1, events.size());

    Event firstEvent = events.get(0);
    assertTrue(firstEvent.content().isPresent());

    Content content = firstEvent.content().get();
    System.out.println("Answer: " + content.text());
    assertTrue(content.text().contains("quantum"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "\\S+")
  void testSingleAgentWithTools() {
    // given
    AnthropicChatModel claudeModel =
        AnthropicChatModel.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .modelName(CLAUDE_3_7_SONNET_20250219)
            .build();

    BaseAgent agent =
        LlmAgent.builder()
            .name("friendly-weather-app")
            .description("Friend agent that knows about the weather")
            .model(new LangChain4j(claudeModel, CLAUDE_3_7_SONNET_20250219))
            .instruction(
                """
                You are a friendly assistant.

                If asked about the weather forecast for a city,
                you MUST call the `getWeather` function.
                """)
            .tools(FunctionTool.create(ToolExample.class, "getWeather"))
            .build();

    // when
    List<Event> events = askAgent(agent, "What's the weather like in Paris?");

    // then
    assertEquals(3, events.size());

    events.forEach(
        event -> {
          assertTrue(event.content().isPresent());
          System.out.printf("%nevent: %s%n", event.stringifyContent());
        });

    Event eventOne = events.get(0);
    Event eventTwo = events.get(1);
    Event eventThree = events.get(2);

    // assert the first event is a function call
    assertTrue(eventOne.content().isPresent());
    Content contentOne = eventOne.content().get();
    assertTrue(contentOne.parts().isPresent());
    List<Part> partsOne = contentOne.parts().get();
    assertEquals(1, partsOne.size());
    Optional<FunctionCall> functionCall = partsOne.get(0).functionCall();
    assertTrue(functionCall.isPresent());
    assertTrue(functionCall.get().name().isPresent());
    assertEquals("getWeather", functionCall.get().name().get());
    assertTrue(functionCall.get().args().isPresent());
    assertTrue(functionCall.get().args().get().containsKey("city"));

    // assert the second event is a function response
    assertTrue(eventTwo.content().isPresent());
    Content contentTwo = eventTwo.content().get();
    assertTrue(contentTwo.parts().isPresent());
    List<Part> partsTwo = contentTwo.parts().get();
    assertEquals(1, partsTwo.size());
    Optional<FunctionResponse> functionResponseTwo = partsTwo.get(0).functionResponse();
    assertTrue(functionResponseTwo.isPresent());

    // assert the third event is the final text response
    assertTrue(eventThree.finalResponse());
    assertTrue(eventThree.content().isPresent());
    Content contentThree = eventThree.content().get();
    assertTrue(contentThree.parts().isPresent());
    List<Part> partsThree = contentThree.parts().get();
    assertEquals(1, partsThree.size());
    assertTrue(partsThree.get(0).text().isPresent());
    assertTrue(partsThree.get(0).text().get().contains("beautiful"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "\\S+")
  void testAgentTool() {
    // given
    OpenAiChatModel gptModel =
        OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName(GPT_4_O_MINI)
            .build();

    LlmAgent weatherAgent =
        LlmAgent.builder()
            .name("weather-agent")
            .description("Weather agent")
            .model(GEMINI_2_0_FLASH)
            .instruction(
                """
                Your role is to always answer that the weather is sunny and 20°C.
                """)
            .build();

    BaseAgent agent =
        LlmAgent.builder()
            .name("friendly-weather-app")
            .description("Friend agent that knows about the weather")
            .model(new LangChain4j(gptModel))
            .instruction(
                """
                You are a friendly assistant.

                If asked about the weather forecast for a city,
                you MUST call the `weather-agent` function.
                """)
            .tools(AgentTool.create(weatherAgent))
            .build();

    // when
    List<Event> events = askAgent(agent, "What's the weather like in Paris?");

    // then
    assertEquals(3, events.size());
    events.forEach(
        event -> {
          assertTrue(event.content().isPresent());
          System.out.printf("%nevent: %s%n", event.stringifyContent());
        });

    assertEquals(1, events.get(0).functionCalls().size());
    assertEquals("weather-agent", events.get(0).functionCalls().get(0).name().get());

    assertEquals(1, events.get(1).functionResponses().size());
    assertTrue(
        events
            .get(1)
            .functionResponses()
            .get(0)
            .response()
            .get()
            .toString()
            .toLowerCase()
            .contains("sunny"));
    assertTrue(events.get(1).functionResponses().get(0).response().get().toString().contains("20"));

    {
      final var finalEvent = events.get(2);
      assertTrue(finalEvent.finalResponse());
      final var text = finalEvent.content().orElseThrow().text();
      assertTrue(text.contains("sunny"));
      assertTrue(text.contains("20"));
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = "\\S+")
  @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "\\S+")
  void testSubAgent() {
    // given
    OpenAiChatModel gptModel =
        OpenAiChatModel.builder()
            .baseUrl("http://langchain4j.dev/demo/openai/v1")
            .apiKey(Objects.requireNonNullElse(System.getenv("OPENAI_API_KEY"), "demo"))
            .modelName(GPT_4_O_MINI)
            .build();

    LlmAgent greeterAgent =
        LlmAgent.builder()
            .name("greeterAgent")
            .description("Friendly agent that greets users")
            .model(new LangChain4j(gptModel))
            .instruction(
                """
                You are a friendly that greets users.
                """)
            .build();

    LlmAgent farewellAgent =
        LlmAgent.builder()
            .name("farewellAgent")
            .description("Friendly agent that says goodbye to users")
            .model(new LangChain4j(gptModel))
            .instruction(
                """
                You are a friendly that says goodbye to users.
                """)
            .build();

    LlmAgent coordinatorAgent =
        LlmAgent.builder()
            .name("coordinator-agent")
            .description("Coordinator agent")
            .model(GEMINI_2_0_FLASH)
            .instruction(
                """
                Your role is to coordinate 2 agents:
                - `greeterAgent`: should reply to messages saying hello, hi, etc.
                - `farewellAgent`: should reply to messages saying bye, goodbye, etc.
                """)
            .subAgents(greeterAgent, farewellAgent)
            .build();

    // when
    List<Event> hiEvents = askAgent(coordinatorAgent, "Hi");
    List<Event> byeEvents = askAgent(coordinatorAgent, "Goodbye");

    // then
    hiEvents.forEach(
        event -> {
          System.out.println(event.stringifyContent());
        });
    byeEvents.forEach(
        event -> {
          System.out.println(event.stringifyContent());
        });

    // Assertions for hiEvents
    assertEquals(3, hiEvents.size());

    Event hiEvent1 = hiEvents.get(0);
    assertTrue(hiEvent1.content().isPresent());
    assertFalse(hiEvent1.functionCalls().isEmpty());
    assertEquals(1, hiEvent1.functionCalls().size());
    FunctionCall hiFunctionCall = hiEvent1.functionCalls().get(0);
    assertTrue(hiFunctionCall.id().isPresent());
    assertEquals(Optional.of("transferToAgent"), hiFunctionCall.name());
    assertEquals(Optional.of(Map.of("agentName", "greeterAgent")), hiFunctionCall.args());

    Event hiEvent2 = hiEvents.get(1);
    assertTrue(hiEvent2.content().isPresent());
    assertFalse(hiEvent2.functionResponses().isEmpty());
    assertEquals(1, hiEvent2.functionResponses().size());
    FunctionResponse hiFunctionResponse = hiEvent2.functionResponses().get(0);
    assertTrue(hiFunctionResponse.id().isPresent());
    assertEquals(Optional.of("transferToAgent"), hiFunctionResponse.name());
    assertEquals(Optional.of(Map.of()), hiFunctionResponse.response()); // Empty map for response

    Event hiEvent3 = hiEvents.get(2);
    assertTrue(hiEvent3.content().isPresent());
    assertTrue(hiEvent3.content().get().text().toLowerCase().contains("hello"));
    assertTrue(hiEvent3.finalResponse());

    // Assertions for byeEvents
    assertEquals(3, byeEvents.size());

    Event byeEvent1 = byeEvents.get(0);
    assertTrue(byeEvent1.content().isPresent());
    assertFalse(byeEvent1.functionCalls().isEmpty());
    assertEquals(1, byeEvent1.functionCalls().size());
    FunctionCall byeFunctionCall = byeEvent1.functionCalls().get(0);
    assertTrue(byeFunctionCall.id().isPresent());
    assertEquals(Optional.of("transferToAgent"), byeFunctionCall.name());
    assertEquals(Optional.of(Map.of("agentName", "farewellAgent")), byeFunctionCall.args());

    Event byeEvent2 = byeEvents.get(1);
    assertTrue(byeEvent2.content().isPresent());
    assertFalse(byeEvent2.functionResponses().isEmpty());
    assertEquals(1, byeEvent2.functionResponses().size());
    FunctionResponse byeFunctionResponse = byeEvent2.functionResponses().get(0);
    assertTrue(byeFunctionResponse.id().isPresent());
    assertEquals(Optional.of("transferToAgent"), byeFunctionResponse.name());
    assertEquals(Optional.of(Map.of()), byeFunctionResponse.response()); // Empty map for response

    Event byeEvent3 = byeEvents.get(2);
    assertTrue(byeEvent3.content().isPresent());
    assertTrue(byeEvent3.content().get().text().toLowerCase().contains("goodbye"));
    assertTrue(byeEvent3.finalResponse());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "\\S+")
  void testSimpleStreamingResponse() {
    // given
    AnthropicStreamingChatModel claudeStreamingModel =
        AnthropicStreamingChatModel.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .modelName(CLAUDE_3_7_SONNET_20250219)
            .build();

    LangChain4j lc4jClaude = new LangChain4j(claudeStreamingModel, CLAUDE_3_7_SONNET_20250219);

    // when
    Flowable<LlmResponse> responses =
        lc4jClaude.generateContent(
            LlmRequest.builder()
                .contents(List.of(Content.fromParts(Part.fromText("Why is the sky blue?"))))
                .build(),
            true);

    String fullResponse =
        String.join(
            "",
            responses
                .blockingStream()
                .map(llmResponse -> llmResponse.content().get().text())
                .toList());

    // then
    assertTrue(fullResponse.contains("blue"));
    assertTrue(fullResponse.contains("Rayleigh"));
    assertTrue(fullResponse.contains("scatter"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "\\S+")
  void testStreamingRunConfig() {
    // given
    OpenAiStreamingChatModel streamingModel =
        OpenAiStreamingChatModel.builder()
            .baseUrl("http://langchain4j.dev/demo/openai/v1")
            .apiKey(Objects.requireNonNullElse(System.getenv("OPENAI_API_KEY"), "demo"))
            .modelName(GPT_4_O_MINI)
            .build();

    //        AnthropicStreamingChatModel streamingModel = AnthropicStreamingChatModel.builder()
    //            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    //            .modelName(CLAUDE_3_7_SONNET_20250219)
    //            .build();

    //        GoogleAiGeminiStreamingChatModel streamingModel =
    // GoogleAiGeminiStreamingChatModel.builder()
    //            .apiKey(System.getenv("GOOGLE_API_KEY"))
    //            .modelName("gemini-2.0-flash")
    //            .build();

    LlmAgent agent =
        LlmAgent.builder()
            .name("streaming-agent")
            .description("Friendly science teacher agent")
            .instruction(
                """
                You're a friendly science teacher.
                You give concise answers about science topics.

                When someone greets you, respond with "Hello".
                If someone asks about the weather, call the `getWeather` function.
                """)
            .model(new LangChain4j(streamingModel, "GPT_4_O_MINI"))
            //            .model(new LangChain4j(streamingModel,
            // CLAUDE_3_7_SONNET_20250219))
            .tools(FunctionTool.create(ToolExample.class, "getWeather"))
            .build();

    // when
    List<Event> eventsHi = askAgentStreaming(agent, "Hi");
    String responseToHi =
        String.join("", eventsHi.stream().map(event -> event.content().get().text()).toList());

    List<Event> eventsQubit = askAgentStreaming(agent, "Tell me about qubits");
    String responseToQubit =
        String.join("", eventsQubit.stream().map(event -> event.content().get().text()).toList());

    List<Event> eventsWeather = askAgentStreaming(agent, "What's the weather in Paris?");
    String responseToWeather =
        String.join("", eventsWeather.stream().map(Event::stringifyContent).toList());

    // then

    // Assertions for "Hi"
    assertFalse(eventsHi.isEmpty(), "eventsHi should not be empty");
    // Depending on the model and streaming behavior, the number of events can vary.
    // If a single "Hello" is expected in one event:
    // assertEquals(1, eventsHi.size(), "Expected 1 event for 'Hi'");
    // assertEquals("Hello", responseToHi, "Response to 'Hi' should be 'Hello'");
    // If "Hello" can be streamed in multiple parts:
    assertTrue(responseToHi.trim().contains("Hello"), "Response to 'Hi' should be 'Hello'");

    // Assertions for "Tell me about qubits"
    assertTrue(eventsQubit.size() > 1, "Expected multiple streaming events for 'qubit' question");
    assertTrue(
        responseToQubit.toLowerCase().contains("qubit"),
        "Response to 'qubit' should contain 'qubit'");
    assertTrue(
        responseToQubit.toLowerCase().contains("quantum"),
        "Response to 'qubit' should contain 'quantum'");
    assertTrue(
        responseToQubit.toLowerCase().contains("superposition"),
        "Response to 'qubit' should contain 'superposition'");

    // Assertions for "What's the weather in Paris?"
    assertTrue(
        eventsWeather.size() > 2,
        "Expected multiple events for weather question (function call, response, text)");

    // Check for function call
    Optional<Event> functionCallEvent =
        eventsWeather.stream().filter(e -> !e.functionCalls().isEmpty()).findFirst();
    assertTrue(functionCallEvent.isPresent(), "Should contain a function call event for weather");
    FunctionCall fc = functionCallEvent.get().functionCalls().get(0);
    assertEquals(Optional.of("getWeather"), fc.name(), "Function call name should be 'getWeather'");
    assertTrue(
        fc.args().isPresent() && "Paris".equals(fc.args().get().get("city")),
        "Function call should be for 'Paris'");

    // Check for function response
    Optional<Event> functionResponseEvent =
        eventsWeather.stream().filter(e -> !e.functionResponses().isEmpty()).findFirst();
    assertTrue(
        functionResponseEvent.isPresent(), "Should contain a function response event for weather");
    FunctionResponse fr = functionResponseEvent.get().functionResponses().get(0);
    assertEquals(
        Optional.of("getWeather"), fr.name(), "Function response name should be 'getWeather'");
    assertTrue(fr.response().isPresent());
    Map<String, Object> weatherResponseMap = (Map<String, Object>) fr.response().get();
    assertEquals("Paris", weatherResponseMap.get("city"));
    assertTrue(weatherResponseMap.get("forecast").toString().contains("beautiful and sunny"));

    // Check the final aggregated text response
    // Consolidate text parts from events that are not function calls or responses
    String finalWeatherTextResponse =
        eventsWeather.stream()
            .filter(
                event ->
                    event.functionCalls().isEmpty()
                        && event.functionResponses().isEmpty()
                        && event.content().isPresent()
                        && event.content().get().text() != null)
            .map(event -> event.content().get().text())
            .collect(java.util.stream.Collectors.joining())
            .trim();

    assertTrue(
        finalWeatherTextResponse.contains("Paris"), "Final weather response should mention Paris");
    assertTrue(
        finalWeatherTextResponse.toLowerCase().contains("beautiful and sunny"),
        "Final weather response should mention 'beautiful and sunny'");
    assertTrue(
        finalWeatherTextResponse.contains("10"), "Final weather response should mention '10'");
    assertTrue(
        finalWeatherTextResponse.contains("24"), "Final weather response should mention '24'");

    // You can also assert on the concatenated `responseToWeather` if it's meant to capture the
    // full interaction text
    assertTrue(
        responseToWeather.contains("Function Call")
            && responseToWeather.contains("getWeather")
            && responseToWeather.contains("Paris"));
    assertTrue(
        responseToWeather.contains("Function Response")
            && responseToWeather.contains("beautiful and sunny weather"));
    assertTrue(responseToWeather.contains("sunny"));
    assertTrue(responseToWeather.contains("24"));
  }
}
