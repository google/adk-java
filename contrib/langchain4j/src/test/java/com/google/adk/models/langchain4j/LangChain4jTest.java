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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.SetModelResponseTool;
import com.google.genai.types.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LangChain4jTest {

  private static final String MODEL_NAME = "test-model";

  /**
   * An output schema with an untyped property, an array without items, and an untyped object with
   * properties.
   */
  private static final Schema FREE_FORM_SCHEMA =
      Schema.builder()
          .type("OBJECT")
          .properties(
              Map.of(
                  "extra",
                  Schema.builder().description("Anything").build(),
                  "tags",
                  Schema.builder().type("ARRAY").build(),
                  "nested",
                  Schema.builder()
                      .properties(Map.of("a", Schema.builder().type("STRING").build()))
                      .build()))
          .build();

  private ChatModel chatModel;
  private StreamingChatModel streamingChatModel;
  private LangChain4j langChain4j;
  private LangChain4j streamingLangChain4j;

  @BeforeEach
  void setUp() {
    chatModel = mock(ChatModel.class);
    streamingChatModel = mock(StreamingChatModel.class);

    langChain4j = LangChain4j.builder().chatModel(chatModel).modelName(MODEL_NAME).build();
    streamingLangChain4j =
        LangChain4j.builder().streamingChatModel(streamingChatModel).modelName(MODEL_NAME).build();
  }

  @Test
  void testBuilder() {
    ObjectMapper customMapper = new ObjectMapper();
    LangChain4j customLc4j =
        LangChain4j.builder()
            .chatModel(chatModel)
            .streamingChatModel(streamingChatModel)
            .objectMapper(customMapper)
            .modelName("custom-model")
            .build();

    assertThat(customLc4j.chatModel()).isEqualTo(chatModel);
    assertThat(customLc4j.streamingChatModel()).isEqualTo(streamingChatModel);
    assertThat(customLc4j.objectMapper()).isEqualTo(customMapper);
    assertThat(customLc4j.modelName()).isEqualTo("custom-model");
  }

  @Test
  @DisplayName("Should generate content using non-streaming chat model")
  void testGenerateContentWithChatModel() {
    // Given
    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(Part.fromText("Hello")))).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Hello, how can I help you?");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final Flowable<LlmResponse> responseFlowable = langChain4j.generateContent(llmRequest, false);
    final LlmResponse response = responseFlowable.blockingFirst();

    // Then
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Hello, how can I help you?");

    // Verify the request conversion
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    assertThat(capturedRequest.messages()).hasSize(1);
    assertThat(capturedRequest.messages().get(0)).isInstanceOf(UserMessage.class);
  }

  @Test
  @DisplayName("Should handle function calls in LLM responses")
  void testGenerateContentWithFunctionCall() {
    // Given
    // Create a mock FunctionTool
    final FunctionTool weatherTool = mock(FunctionTool.class);
    when(weatherTool.name()).thenReturn("getWeather");
    when(weatherTool.description()).thenReturn("Get weather for a city");

    // Create a mock FunctionDeclaration
    final FunctionDeclaration functionDeclaration = mock(FunctionDeclaration.class);
    when(weatherTool.declaration()).thenReturn(Optional.of(functionDeclaration));

    // Create a mock Schema
    final Schema schema = mock(Schema.class);
    when(functionDeclaration.parameters()).thenReturn(Optional.of(schema));

    // Create a mock Type
    final Type type = mock(Type.class);
    when(schema.type()).thenReturn(Optional.of(type));
    when(type.knownEnum()).thenReturn(Type.Known.OBJECT);

    // Create a mock for schema properties and required fields
    when(schema.properties()).thenReturn(Optional.of(Map.of("city", schema)));
    when(schema.required()).thenReturn(Optional.of(List.of("city")));

    // Create a real LlmRequest
    // We'll use a real LlmRequest but we won't add any tools to it
    // This is because we don't know the exact return type of LlmRequest.tools()
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What's the weather in Paris?"))))
            .build();

    // Mock the AI response with a function call
    final ToolExecutionRequest toolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("123")
            .name("getWeather")
            // language=json
            .arguments("{\"city\":\"Paris\"}")
            .build();

    final List<ToolExecutionRequest> toolExecutionRequests = List.of(toolExecutionRequest);

    final AiMessage aiMessage =
        AiMessage.builder().text("").toolExecutionRequests(toolExecutionRequests).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final Flowable<LlmResponse> responseFlowable = langChain4j.generateContent(llmRequest, false);
    final LlmResponse response = responseFlowable.blockingFirst();

    // Then
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();

    final List<Part> parts = response.content().get().parts().orElseThrow();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isPresent();

    final FunctionCall functionCall = parts.get(0).functionCall().orElseThrow();
    assertThat(functionCall.name()).isEqualTo(Optional.of("getWeather"));
    assertThat(functionCall.args()).isPresent();
    assertThat(functionCall.args().get()).containsEntry("city", "Paris");
  }

  @Test
  @DisplayName("Should handle multiple function calls in LLM responses")
  void testGenerateContentWithMultipleFunctionCall() {
    // Given
    // Create mock FunctionTools
    final FunctionTool weatherTool = mock(FunctionTool.class);
    when(weatherTool.name()).thenReturn("getWeather");
    when(weatherTool.description()).thenReturn("Get weather for a city");

    final FunctionTool timeTool = mock(FunctionTool.class);
    when(timeTool.name()).thenReturn("getCurrentTime");
    when(timeTool.description()).thenReturn("Get current time for a city");

    // Create mock FunctionDeclarations
    final FunctionDeclaration weatherDeclaration = mock(FunctionDeclaration.class);
    final FunctionDeclaration timeDeclaration = mock(FunctionDeclaration.class);
    when(weatherTool.declaration()).thenReturn(Optional.of(weatherDeclaration));
    when(timeTool.declaration()).thenReturn(Optional.of(timeDeclaration));

    // Create mock Schemas
    final Schema weatherSchema = mock(Schema.class);
    final Schema timeSchema = mock(Schema.class);
    when(weatherDeclaration.parameters()).thenReturn(Optional.of(weatherSchema));
    when(timeDeclaration.parameters()).thenReturn(Optional.of(timeSchema));

    // Create mock Types
    final Type weatherType = mock(Type.class);
    final Type timeType = mock(Type.class);
    when(weatherSchema.type()).thenReturn(Optional.of(weatherType));
    when(timeSchema.type()).thenReturn(Optional.of(timeType));
    when(weatherType.knownEnum()).thenReturn(Type.Known.OBJECT);
    when(timeType.knownEnum()).thenReturn(Type.Known.OBJECT);

    // Create mock schema properties
    when(weatherSchema.properties()).thenReturn(Optional.of(Map.of("city", weatherSchema)));
    when(timeSchema.properties()).thenReturn(Optional.of(Map.of("city", timeSchema)));
    when(weatherSchema.required()).thenReturn(Optional.of(List.of("city")));
    when(timeSchema.required()).thenReturn(Optional.of(List.of("city")));

    // Create LlmRequest
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.fromParts(
                        Part.fromText("What's the weather in Paris and the current time?"))))
            .build();

    // Mock multiple tool execution requests in the AI response
    final ToolExecutionRequest weatherRequest =
        ToolExecutionRequest.builder()
            .id("123")
            .name("getWeather")
            .arguments("{\"city\":\"Paris\"}")
            .build();

    final ToolExecutionRequest timeRequest =
        ToolExecutionRequest.builder()
            .id("456")
            .name("getCurrentTime")
            .arguments("{\"city\":\"Paris\"}")
            .build();

    final AiMessage aiMessage =
        AiMessage.builder()
            .text("")
            .toolExecutionRequests(List.of(weatherRequest, timeRequest))
            .build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();

    final List<Part> parts = response.content().get().parts().orElseThrow();
    assertThat(parts).hasSize(2);

    // Verify first function call (getWeather)
    assertThat(parts.get(0).functionCall()).isPresent();
    final FunctionCall weatherCall = parts.get(0).functionCall().orElseThrow();
    assertThat(weatherCall.name()).isEqualTo(Optional.of("getWeather"));
    assertThat(weatherCall.args()).isPresent();
    assertThat(weatherCall.args().get()).containsEntry("city", "Paris");

    // Verify second function call (getCurrentTime)
    assertThat(parts.get(1).functionCall()).isPresent();
    final FunctionCall timeCall = parts.get(1).functionCall().orElseThrow();
    assertThat(timeCall.name()).isEqualTo(Optional.of("getCurrentTime"));
    assertThat(timeCall.args()).isPresent();
    assertThat(timeCall.args().get()).containsEntry("city", "Paris");

    // Verify the ChatModel was called
    verify(chatModel).chat(any(ChatRequest.class));
  }

  @Test
  @DisplayName("Should handle streaming responses correctly")
  void testGenerateContentWithStreamingChatModel() {
    // Given
    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(Part.fromText("Hello")))).build();

    // Create a list to collect the responses
    final List<LlmResponse> responses = new ArrayList<>();

    // Set up the mock to capture and store the handler
    final StreamingChatResponseHandler[] handlerRef = new StreamingChatResponseHandler[1];

    doAnswer(
            invocation -> {
              // Store the handler for later use
              handlerRef[0] = invocation.getArgument(1);
              return null;
            })
        .when(streamingChatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    // When
    final Flowable<LlmResponse> responseFlowable =
        streamingLangChain4j.generateContent(llmRequest, true);

    // Subscribe to the flowable to collect responses
    final var disposable = responseFlowable.subscribe(responses::add);

    // Verify the streaming model was called
    verify(streamingChatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    // Get the captured handler
    final StreamingChatResponseHandler handler = handlerRef[0];

    // Simulate streaming responses
    handler.onPartialResponse("Hello");
    handler.onPartialResponse(", how");
    handler.onPartialResponse(" can I help");
    handler.onPartialResponse(" you?");

    // Simulate a function call in the complete response
    final ToolExecutionRequest toolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("123")
            .name("getWeather")
            .arguments("{\"city\":\"Paris\"}")
            .build();

    final AiMessage aiMessage =
        AiMessage.builder().text("").toolExecutionRequests(List.of(toolExecutionRequest)).build();

    // Simulate completion with a function call
    handler.onCompleteResponse(ChatResponse.builder().aiMessage(aiMessage).build());

    // Then
    assertThat(responses).hasSize(5); // 4 partial responses + 1 function call

    // Every response has the model role; the chunks are partial and the final response is not
    assertThat(responses)
        .allSatisfy(
            response -> assertThat(response.content().orElseThrow().role()).hasValue("model"));
    assertThat(responses.subList(0, 4).stream().map(LlmResponse::partial))
        .containsExactly(
            Optional.of(true), Optional.of(true), Optional.of(true), Optional.of(true));
    assertThat(responses.get(4).partial()).isEmpty();

    // Verify the partial responses
    assertThat(responses.get(0).content().orElseThrow().text()).isEqualTo("Hello");
    assertThat(responses.get(1).content().orElseThrow().text()).isEqualTo(", how");
    assertThat(responses.get(2).content().orElseThrow().text()).isEqualTo(" can I help");
    assertThat(responses.get(3).content().orElseThrow().text()).isEqualTo(" you?");

    // Verify the function call
    assertThat(responses.get(4).content().orElseThrow().parts().orElseThrow()).hasSize(1);
    assertThat(responses.get(4).content().orElseThrow().parts().orElseThrow().get(0).functionCall())
        .isPresent();
    final FunctionCall functionCall =
        responses
            .get(4)
            .content()
            .orElseThrow()
            .parts()
            .orElseThrow()
            .get(0)
            .functionCall()
            .orElseThrow();
    assertThat(functionCall.name()).isEqualTo(Optional.of("getWeather"));
    assertThat(functionCall.args().orElseThrow()).containsEntry("city", "Paris");

    disposable.dispose();
  }

  @Test
  @DisplayName("Should end a streamed text turn with its full text")
  void testGenerateContentWithStreamingChatModel_endsWithTheFullText() {
    final StreamedTurn turn = startStreamedTurn();

    turn.handler().onPartialResponse("Par");
    turn.handler().onPartialResponse("is");
    turn.handler()
        .onCompleteResponse(
            ChatResponse.builder()
                .aiMessage(AiMessage.from("Paris"))
                .tokenUsage(new TokenUsage(10, 5))
                .build());

    turn.subscriber().assertComplete();
    final List<LlmResponse> responses = turn.subscriber().values();
    assertThat(responses).hasSize(3);
    final LlmResponse last = responses.get(2);
    assertThat(last.partial()).isEmpty();
    assertThat(last.content().orElseThrow().role()).hasValue("model");
    assertThat(last.content().orElseThrow().text()).isEqualTo("Paris");
    assertThat(last.usageMetadata().orElseThrow().promptTokenCount()).hasValue(10);
  }

  @Test
  @DisplayName("Should end a streamed turn that has no text or calls with an empty response")
  void testGenerateContentWithStreamingChatModel_endsAnEmptyTurnWithAResponse() {
    final StreamedTurn turn = startStreamedTurn();

    turn.handler()
        .onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.builder().build()).build());

    turn.subscriber().assertComplete();
    final List<LlmResponse> responses = turn.subscriber().values();
    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).partial()).isEmpty();
    assertThat(responses.get(0).content().orElseThrow().parts().orElseThrow()).isEmpty();
  }

  @Test
  @DisplayName("Should end a streamed turn with an error when its calls can't be read")
  void testGenerateContentWithStreamingChatModel_endsWithAnErrorForUnreadableCallArguments() {
    final StreamedTurn turn = startStreamedTurn();

    turn.handler()
        .onCompleteResponse(
            ChatResponse.builder()
                .aiMessage(
                    AiMessage.from(
                        ToolExecutionRequest.builder()
                            .id("c1")
                            .name("getWeather")
                            .arguments("not json")
                            .build()))
                .build());

    turn.subscriber().assertError(RuntimeException.class);
  }

  @Test
  @DisplayName("Should save the whole streamed answer to the agent's outputKey")
  void testStreamingAgentRun_savesTheWholeAnswerToItsOutputKey() {
    doAnswer(
            invocation -> {
              StreamingChatResponseHandler handler = invocation.getArgument(1);
              handler.onPartialResponse("Par");
              handler.onPartialResponse("is");
              handler.onCompleteResponse(
                  ChatResponse.builder().aiMessage(AiMessage.from("Paris")).build());
              return null;
            })
        .when(streamingChatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    final LlmAgent agent =
        LlmAgent.builder().name("agent").model(streamingLangChain4j).outputKey("answer").build();
    final InMemoryRunner runner = new InMemoryRunner(agent);
    final Session session =
        runner.sessionService().createSession(runner.appName(), "user").blockingGet();

    runner
        .runAsync(
            "user",
            session.id(),
            Content.fromParts(Part.fromText("What is the capital of France?")),
            RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build())
        .blockingSubscribe();

    final Session updated =
        runner
            .sessionService()
            .getSession(runner.appName(), "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(updated.state()).containsEntry("answer", "Paris");
  }

  @Test
  @DisplayName("Should end a streamed turn with its text and calls in one response")
  void testGenerateContentWithStreamingChatModel_endsWithTextAndCallsInOneResponse() {
    final StreamedTurn turn = startStreamedTurn();

    turn.handler().onPartialResponse("Let me check.");
    final AiMessage aiMessage =
        AiMessage.builder()
            .text("Let me check.")
            .toolExecutionRequests(
                List.of(
                    ToolExecutionRequest.builder()
                        .id("c1")
                        .name("getWeather")
                        .arguments("{\"city\":\"Paris\"}")
                        .build(),
                    ToolExecutionRequest.builder()
                        .id("c2")
                        .name("getWeather")
                        .arguments("{\"city\":\"Rome\"}")
                        .build()))
            .build();
    turn.handler().onCompleteResponse(ChatResponse.builder().aiMessage(aiMessage).build());

    final List<LlmResponse> responses = turn.subscriber().values();
    assertThat(responses).hasSize(2);
    final LlmResponse last = responses.get(1);
    assertThat(last.partial()).isEmpty();
    final List<Part> parts = last.content().orElseThrow().parts().orElseThrow();
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).hasValue("Let me check.");
    assertThat(parts.subList(1, 3).stream().map(part -> part.functionCall().orElseThrow().id()))
        .containsExactly(Optional.of("c1"), Optional.of("c2"));
  }

  @Test
  @DisplayName("Should pass configuration options to LangChain4j")
  void testGenerateContentWithConfigOptions() {
    // Given
    final GenerateContentConfig config =
        GenerateContentConfig.builder()
            .temperature(0.7f)
            .topP(0.9f)
            .topK(40f)
            .maxOutputTokens(100)
            .presencePenalty(0.5f)
            .build();

    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("Hello"))))
            .config(config)
            .build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Hello, how can I help you?");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final var llmResponse = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Assert the llmResponse
    assertThat(llmResponse).isNotNull();
    assertThat(llmResponse.content()).isPresent();
    assertThat(llmResponse.content().get().text()).isEqualTo("Hello, how can I help you?");

    // Assert the request configuration
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    assertThat(capturedRequest.temperature()).isCloseTo(0.7, offset(0.001));
    assertThat(capturedRequest.topP()).isCloseTo(0.9, offset(0.001));
    assertThat(capturedRequest.topK()).isEqualTo(40);
    assertThat(capturedRequest.maxOutputTokens()).isEqualTo(100);
    assertThat(capturedRequest.presencePenalty()).isCloseTo(0.5, offset(0.001));
  }

  @Test
  @DisplayName("Should throw UnsupportedOperationException when connect is called")
  void testConnectThrowsUnsupportedOperationException() {
    // Given
    final LlmRequest llmRequest = LlmRequest.builder().build();

    // When/Then
    assertThatThrownBy(() -> langChain4j.connect(llmRequest))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("Live connection is not supported for LangChain4j models.");
  }

  @Test
  @DisplayName("Should handle tool calling in LLM responses")
  void testGenerateContentWithToolCalling() {
    // Given
    // Create a mock ChatResponse with a tool execution request
    final ToolExecutionRequest toolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("123")
            .name("getWeather")
            .arguments("{\"city\":\"Paris\"}")
            .build();

    final AiMessage aiMessage =
        AiMessage.builder().text("").toolExecutionRequests(List.of(toolExecutionRequest)).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // Create a LlmRequest with a user message
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What's the weather in Paris?"))))
            .build();

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response contains the expected function call
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();

    final List<Part> parts = response.content().get().parts().orElseThrow();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isPresent();

    final FunctionCall functionCall = parts.get(0).functionCall().orElseThrow();
    assertThat(functionCall.name()).isEqualTo(Optional.of("getWeather"));
    assertThat(functionCall.args()).isPresent();
    assertThat(functionCall.args().get()).containsEntry("city", "Paris");

    // Verify the ChatModel was called
    verify(chatModel).chat(any(ChatRequest.class));
  }

  @Test
  @DisplayName("Should set ToolChoice to AUTO when FunctionCallingConfig mode is AUTO")
  void testGenerateContentWithAutoToolChoice() {
    // Given
    // Create a FunctionCallingConfig with mode AUTO
    final FunctionCallingConfig functionCallingConfig = mock(FunctionCallingConfig.class);
    final FunctionCallingConfigMode functionMode = mock(FunctionCallingConfigMode.class);

    when(functionCallingConfig.mode()).thenReturn(Optional.of(functionMode));
    when(functionMode.knownEnum()).thenReturn(FunctionCallingConfigMode.Known.AUTO);

    // Create a ToolConfig with the FunctionCallingConfig
    final ToolConfig toolConfig = mock(ToolConfig.class);
    when(toolConfig.functionCallingConfig()).thenReturn(Optional.of(functionCallingConfig));

    // Create a GenerateContentConfig with the ToolConfig
    final GenerateContentConfig config =
        GenerateContentConfig.builder().toolConfig(toolConfig).build();

    // Create a LlmRequest with the config
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What's the weather in Paris?"))))
            .config(config)
            .build();

    // Mock the AI response
    final AiMessage aiMessage = AiMessage.from("It's sunny in Paris");

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("It's sunny in Paris");

    // Verify the request was built correctly with the tool config
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    // Verify tool choice is AUTO
    assertThat(capturedRequest.toolChoice())
        .isEqualTo(dev.langchain4j.model.chat.request.ToolChoice.AUTO);
  }

  @Test
  @DisplayName("Should set ToolChoice to REQUIRED when FunctionCallingConfig mode is ANY")
  void testGenerateContentWithAnyToolChoice() {
    // Given
    // Create a FunctionCallingConfig with mode ANY and allowed function names
    final FunctionCallingConfig functionCallingConfig = mock(FunctionCallingConfig.class);
    final FunctionCallingConfigMode functionMode = mock(FunctionCallingConfigMode.class);

    when(functionCallingConfig.mode()).thenReturn(Optional.of(functionMode));
    when(functionMode.knownEnum()).thenReturn(FunctionCallingConfigMode.Known.ANY);
    when(functionCallingConfig.allowedFunctionNames())
        .thenReturn(Optional.of(List.of("getWeather")));

    // Create a ToolConfig with the FunctionCallingConfig
    final ToolConfig toolConfig = mock(ToolConfig.class);
    when(toolConfig.functionCallingConfig()).thenReturn(Optional.of(functionCallingConfig));

    // Create a GenerateContentConfig with the ToolConfig
    final GenerateContentConfig config =
        GenerateContentConfig.builder().toolConfig(toolConfig).build();

    // Create a LlmRequest with the config
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What's the weather in Paris?"))))
            .config(config)
            .build();

    // Mock the AI response with a function call
    final ToolExecutionRequest toolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("123")
            .name("getWeather")
            .arguments("{\"city\":\"Paris\"}")
            .build();

    final AiMessage aiMessage =
        AiMessage.builder().text("").toolExecutionRequests(List.of(toolExecutionRequest)).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response contains the expected function call
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();

    final List<Part> parts = response.content().get().parts().orElseThrow();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isPresent();

    final FunctionCall functionCall = parts.get(0).functionCall().orElseThrow();
    assertThat(functionCall.name()).isEqualTo(Optional.of("getWeather"));
    assertThat(functionCall.args()).isPresent();
    assertThat(functionCall.args().get()).containsEntry("city", "Paris");

    // Verify the request was built correctly with the tool config
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    // Verify tool choice is REQUIRED (mapped from ANY)
    assertThat(capturedRequest.toolChoice())
        .isEqualTo(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED);
  }

  @Test
  @DisplayName("Should disable tool calling when FunctionCallingConfig mode is NONE")
  void testGenerateContentWithNoneToolChoice() {
    // Given
    // Create a FunctionCallingConfig with mode NONE
    final FunctionCallingConfig functionCallingConfig = mock(FunctionCallingConfig.class);
    final FunctionCallingConfigMode functionMode = mock(FunctionCallingConfigMode.class);

    when(functionCallingConfig.mode()).thenReturn(Optional.of(functionMode));
    when(functionMode.knownEnum()).thenReturn(FunctionCallingConfigMode.Known.NONE);

    // Create a ToolConfig with the FunctionCallingConfig
    final ToolConfig toolConfig = mock(ToolConfig.class);
    when(toolConfig.functionCallingConfig()).thenReturn(Optional.of(functionCallingConfig));

    // Create a GenerateContentConfig with the ToolConfig
    final GenerateContentConfig config =
        GenerateContentConfig.builder().toolConfig(toolConfig).build();

    // Create a LlmRequest with the config
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What's the weather in Paris?"))))
            .config(config)
            .build();

    // Mock the AI response with text (no function call)
    final AiMessage aiMessage = AiMessage.from("It's sunny in Paris");

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response contains text (no function call)
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("It's sunny in Paris");

    // Verify the request was built correctly with the tool config
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    // Verify tool specifications are empty
    assertThat(capturedRequest.toolSpecifications()).isEmpty();
  }

  @Test
  @DisplayName("Should handle structured responses with JSON schema")
  void testGenerateContentWithStructuredResponseJsonSchema() {
    // Given
    // Create a JSON schema for the structured response
    final JsonObjectSchema responseSchema =
        JsonObjectSchema.builder()
            .addProperty("name", JsonStringSchema.builder().build())
            .addProperty("age", JsonStringSchema.builder().build())
            .addProperty("city", JsonStringSchema.builder().build())
            .build();

    // Create a GenerateContentConfig without responseSchema
    final GenerateContentConfig config = GenerateContentConfig.builder().build();

    // Create a LlmRequest with the config
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(
                List.of(Content.fromParts(Part.fromText("Give me information about John Doe"))))
            .config(config)
            .build();

    // Mock the AI response with structured JSON data
    final String jsonResponse =
        """
        {
            "name": "John Doe",
            "age": "30",
            "city": "New York"
        }
        """;
    final AiMessage aiMessage = AiMessage.from(jsonResponse);

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response contains the expected JSON data
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo(jsonResponse);

    // Verify the request was built correctly
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    // Verify the request contains the expected messages
    assertThat(capturedRequest.messages()).hasSize(1);
    assertThat(capturedRequest.messages().get(0)).isInstanceOf(UserMessage.class);
    final UserMessage userMessage = (UserMessage) capturedRequest.messages().get(0);
    assertThat(userMessage.singleText()).isEqualTo("Give me information about John Doe");
  }

  @Test
  @DisplayName("Should handle MCP tools with parametersJsonSchema")
  void testGenerateContentWithMcpToolParametersJsonSchema() {
    // Given
    // Create a mock BaseTool for MCP tool
    final com.google.adk.tools.BaseTool mcpTool = mock(com.google.adk.tools.BaseTool.class);
    when(mcpTool.name()).thenReturn("mcpTool");
    when(mcpTool.description()).thenReturn("An MCP tool");

    // Create a mock FunctionDeclaration
    final FunctionDeclaration functionDeclaration = mock(FunctionDeclaration.class);
    when(mcpTool.declaration()).thenReturn(Optional.of(functionDeclaration));

    // MCP tools use parametersJsonSchema() instead of parameters()
    // Create a JSON schema object (Map representation)
    final Map<String, Object> jsonSchemaMap =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("city", Map.of("type", "string", "description", "City name")),
            "required",
            List.of("city"));

    // Mock parametersJsonSchema() to return the JSON schema object
    when(functionDeclaration.parametersJsonSchema()).thenReturn(Optional.of(jsonSchemaMap));
    when(functionDeclaration.parameters()).thenReturn(Optional.empty());

    // Create a LlmRequest with the MCP tool
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("Use the MCP tool"))))
            .tools(Map.of("mcpTool", mcpTool))
            .build();

    // Mock the AI response
    final AiMessage aiMessage = AiMessage.from("Tool executed successfully");

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Tool executed successfully");

    // Verify the request was built correctly with the tool specification
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    // Verify tool specifications were created from parametersJsonSchema
    assertThat(capturedRequest.toolSpecifications()).isNotEmpty();
    assertThat(capturedRequest.toolSpecifications().get(0).name()).isEqualTo("mcpTool");
    assertThat(capturedRequest.toolSpecifications().get(0).description()).isEqualTo("An MCP tool");
  }

  @Test
  @DisplayName("Should handle MCP tools with parametersJsonSchema when it's already a Schema")
  void testGenerateContentWithMcpToolParametersJsonSchemaAsSchema() {
    // Given
    // Create a mock BaseTool for MCP tool
    final com.google.adk.tools.BaseTool mcpTool = mock(com.google.adk.tools.BaseTool.class);
    when(mcpTool.name()).thenReturn("mcpTool");
    when(mcpTool.description()).thenReturn("An MCP tool");

    // Create a mock FunctionDeclaration
    final FunctionDeclaration functionDeclaration = mock(FunctionDeclaration.class);
    when(mcpTool.declaration()).thenReturn(Optional.of(functionDeclaration));

    // Create a Schema object directly (when parametersJsonSchema returns Schema)
    final Schema cityPropertySchema =
        Schema.builder().type("STRING").description("City name").build();

    final Schema objectSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(Map.of("city", cityPropertySchema))
            .required(List.of("city"))
            .build();

    // Mock parametersJsonSchema() to return Schema directly
    when(functionDeclaration.parametersJsonSchema()).thenReturn(Optional.of(objectSchema));
    when(functionDeclaration.parameters()).thenReturn(Optional.empty());

    // Create a LlmRequest with the MCP tool
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("Use the MCP tool"))))
            .tools(Map.of("mcpTool", mcpTool))
            .build();

    // Mock the AI response
    final AiMessage aiMessage = AiMessage.from("Tool executed successfully");

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response = langChain4j.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Tool executed successfully");

    // Verify the request was built correctly with the tool specification
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    // Verify tool specifications were created from parametersJsonSchema
    assertThat(capturedRequest.toolSpecifications()).isNotEmpty();
    assertThat(capturedRequest.toolSpecifications().get(0).name()).isEqualTo("mcpTool");
    assertThat(capturedRequest.toolSpecifications().get(0).description()).isEqualTo("An MCP tool");
  }

  @Test
  @DisplayName("Should convert free-form schemas in tool parameters")
  void testGenerateContentWithFreeFormToolParameters() {
    // Given
    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(AiMessage.from("Done"));
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response =
        langChain4j.generateContent(freeFormSetModelResponseRequest(), false).blockingFirst();

    // Then
    assertThat(response.content().get().text()).isEqualTo("Done");
    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final Map<String, JsonSchemaElement> properties =
        requestCaptor.getValue().toolSpecifications().get(0).parameters().properties();
    final JsonAnyOfSchema extra = (JsonAnyOfSchema) properties.get("extra");
    assertThat(extra.description()).isEqualTo("Anything");
    assertThat(extra.anyOf()).hasSize(5);
    assertThat(((JsonArraySchema) properties.get("tags")).items())
        .isInstanceOf(JsonAnyOfSchema.class);
    assertThat(((JsonObjectSchema) properties.get("nested")).properties().get("a"))
        .isInstanceOf(JsonStringSchema.class);
  }

  @Test
  @DisplayName("Should send free-form tool parameters through the Gemini provider")
  void testGenerateContentWithFreeFormToolParameters_geminiProvider() throws Exception {
    // Given
    final AtomicReference<String> requestBody = new AtomicReference<>();
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
          final byte[] reply =
              ("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Done\"}]},"
                      + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1,"
                      + "\"candidatesTokenCount\":1,\"totalTokenCount\":2}}")
                  .getBytes(UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, reply.length);
          try (OutputStream body = exchange.getResponseBody()) {
            body.write(reply);
          }
        });

    // When
    final LlmResponse response;
    server.start();
    try {
      final LangChain4j gemini =
          LangChain4j.builder()
              .chatModel(
                  GoogleAiGeminiChatModel.builder()
                      .apiKey("test-key")
                      .modelName("gemini-2.5-flash")
                      .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                      .maxRetries(0)
                      .build())
              .build();
      response = gemini.generateContent(freeFormSetModelResponseRequest(), false).blockingFirst();
    } finally {
      server.stop(0);
    }

    // Then
    assertThat(response.content().get().text()).isEqualTo("Done");
    // LangChain4j 1.12 sends "tools" as one object, later versions as a list of them.
    final JsonNode tools = new ObjectMapper().readTree(requestBody.get()).path("tools");
    final JsonNode properties =
        (tools.isArray() ? tools.path(0) : tools)
            .at("/functionDeclarations/0/parameters/properties");
    assertThat(properties.isObject()).isTrue();
    assertThat(properties.at("/extra/anyOf").size()).isEqualTo(5);
    assertThat(properties.at("/nested/properties/a/type").asText()).isEqualToIgnoringCase("string");
    // Gemini rejects an array schema without items.
    assertThat(
            properties.findParents("type").stream()
                .filter(node -> node.get("type").asText().equalsIgnoreCase("array")))
        .isNotEmpty()
        .allMatch(node -> node.has("items"));
  }

  private static LlmRequest freeFormSetModelResponseRequest() {
    return LlmRequest.builder()
        .contents(List.of(Content.fromParts(Part.fromText("Answer"))))
        .tools(Map.of(SetModelResponseTool.NAME, new SetModelResponseTool(FREE_FORM_SCHEMA)))
        .build();
  }

  @Test
  @DisplayName(
      "Should use TokenCountEstimator to estimate token usage when TokenUsage is not available")
  void testTokenCountEstimatorFallback() {
    // Given
    // Create a mock TokenCountEstimator
    final TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);
    when(tokenCountEstimator.estimateTokenCountInMessages(any())).thenReturn(50); // Input tokens
    when(tokenCountEstimator.estimateTokenCountInText(any())).thenReturn(20); // Output tokens

    // Create LangChain4j with the TokenCountEstimator using Builder
    final LangChain4j langChain4jWithEstimator =
        LangChain4j.builder()
            .chatModel(chatModel)
            .modelName(MODEL_NAME)
            .tokenCountEstimator(tokenCountEstimator)
            .build();

    // Create a LlmRequest
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What is the weather today?"))))
            .build();

    // Mock ChatResponse WITHOUT TokenUsage (simulating when LLM doesn't provide token counts)
    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("The weather is sunny today.");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatResponse.tokenUsage()).thenReturn(null); // No token usage from LLM
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response =
        langChain4jWithEstimator.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response has usage metadata estimated by TokenCountEstimator
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("The weather is sunny today.");

    // IMPORTANT: Verify that token usage was estimated via the TokenCountEstimator
    assertThat(response.usageMetadata()).isPresent();
    final GenerateContentResponseUsageMetadata usageMetadata = response.usageMetadata().get();
    assertThat(usageMetadata.promptTokenCount()).isEqualTo(Optional.of(50)); // From estimator
    assertThat(usageMetadata.candidatesTokenCount()).isEqualTo(Optional.of(20)); // From estimator
    assertThat(usageMetadata.totalTokenCount()).isEqualTo(Optional.of(70)); // 50 + 20

    // Verify the estimator was actually called
    verify(tokenCountEstimator).estimateTokenCountInMessages(any());
    verify(tokenCountEstimator).estimateTokenCountInText("The weather is sunny today.");
  }

  @Test
  @DisplayName("Should prioritize TokenCountEstimator over TokenUsage when estimator is provided")
  void testTokenCountEstimatorPriority() {
    // Given
    // Create a mock TokenCountEstimator
    final TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);
    when(tokenCountEstimator.estimateTokenCountInMessages(any())).thenReturn(100); // From estimator
    when(tokenCountEstimator.estimateTokenCountInText(any())).thenReturn(50); // From estimator

    // Create LangChain4j with the TokenCountEstimator using Builder
    final LangChain4j langChain4jWithEstimator =
        LangChain4j.builder()
            .chatModel(chatModel)
            .modelName(MODEL_NAME)
            .tokenCountEstimator(tokenCountEstimator)
            .build();

    // Create a LlmRequest
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("What is the weather today?"))))
            .build();

    // Mock ChatResponse WITH actual TokenUsage from the LLM
    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("The weather is sunny today.");
    final TokenUsage actualTokenUsage = new TokenUsage(30, 15, 45); // Actual token counts from LLM
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatResponse.tokenUsage()).thenReturn(actualTokenUsage); // LLM provides token usage
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response =
        langChain4jWithEstimator.generateContent(llmRequest, false).blockingFirst();

    // Then
    // IMPORTANT: When TokenCountEstimator is present, it takes priority over TokenUsage
    assertThat(response).isNotNull();
    assertThat(response.usageMetadata()).isPresent();
    final GenerateContentResponseUsageMetadata usageMetadata = response.usageMetadata().get();
    assertThat(usageMetadata.promptTokenCount()).isEqualTo(Optional.of(100)); // From estimator
    assertThat(usageMetadata.candidatesTokenCount()).isEqualTo(Optional.of(50)); // From estimator
    assertThat(usageMetadata.totalTokenCount()).isEqualTo(Optional.of(150)); // 100 + 50

    // Verify the estimator was called (it takes priority)
    verify(tokenCountEstimator).estimateTokenCountInMessages(any());
    verify(tokenCountEstimator).estimateTokenCountInText("The weather is sunny today.");
  }

  @Test
  @DisplayName("Should use the provider's TokenUsage when the estimator fails")
  void testTokenCountEstimatorFailure_fallsBackToTokenUsage() {
    final TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);
    when(tokenCountEstimator.estimateTokenCountInMessages(any()))
        .thenThrow(new IllegalStateException("estimator failed"));
    final LangChain4j langChain4jWithEstimator =
        LangChain4j.builder()
            .chatModel(chatModel)
            .modelName(MODEL_NAME)
            .tokenCountEstimator(tokenCountEstimator)
            .build();
    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(Part.fromText("Hi")))).build();
    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(
            ChatResponse.builder()
                .aiMessage(AiMessage.from("Hello"))
                .tokenUsage(new TokenUsage(30, 15, 45))
                .build());

    final LlmResponse response =
        langChain4jWithEstimator.generateContent(llmRequest, false).blockingFirst();

    final GenerateContentResponseUsageMetadata usageMetadata =
        response.usageMetadata().orElseThrow();
    assertThat(usageMetadata.promptTokenCount()).hasValue(30);
    assertThat(usageMetadata.candidatesTokenCount()).hasValue(15);
    assertThat(usageMetadata.totalTokenCount()).hasValue(45);
  }

  @Test
  @DisplayName("Should estimate the output of a turn with only calls from empty text")
  void testTokenCountEstimator_estimatesACallOnlyTurnFromEmptyText() {
    final TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);
    when(tokenCountEstimator.estimateTokenCountInMessages(any())).thenReturn(50);
    when(tokenCountEstimator.estimateTokenCountInText("")).thenReturn(3);
    final LangChain4j langChain4jWithEstimator =
        LangChain4j.builder()
            .chatModel(chatModel)
            .modelName(MODEL_NAME)
            .tokenCountEstimator(tokenCountEstimator)
            .build();
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("Weather in Paris?"))))
            .build();
    when(chatModel.chat(any(ChatRequest.class)))
        .thenReturn(
            ChatResponse.builder()
                .aiMessage(
                    AiMessage.from(
                        ToolExecutionRequest.builder()
                            .id("c1")
                            .name("getWeather")
                            .arguments("{\"city\":\"Paris\"}")
                            .build()))
                .tokenUsage(new TokenUsage(30, 15, 45))
                .build());

    final LlmResponse response =
        langChain4jWithEstimator.generateContent(llmRequest, false).blockingFirst();

    final GenerateContentResponseUsageMetadata usageMetadata =
        response.usageMetadata().orElseThrow();
    assertThat(usageMetadata.promptTokenCount()).hasValue(50);
    assertThat(usageMetadata.candidatesTokenCount()).hasValue(3);
    assertThat(usageMetadata.totalTokenCount()).hasValue(53);
  }

  @Test
  @DisplayName("Should not include usageMetadata when TokenUsage is null and no estimator provided")
  void testNoUsageMetadataWithoutEstimator() {
    // Given
    // Create LangChain4j WITHOUT TokenCountEstimator (default behavior)
    final LangChain4j langChain4jNoEstimator =
        LangChain4j.builder().chatModel(chatModel).modelName(MODEL_NAME).build();

    // Create a LlmRequest
    final LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.fromParts(Part.fromText("Hello, world!"))))
            .build();

    // Mock ChatResponse WITHOUT TokenUsage
    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Hello! How can I help you?");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatResponse.tokenUsage()).thenReturn(null); // No token usage from LLM
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final LlmResponse response =
        langChain4jNoEstimator.generateContent(llmRequest, false).blockingFirst();

    // Then
    // Verify the response does NOT have usage metadata
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Hello! How can I help you?");

    // IMPORTANT: usageMetadata should be empty when no TokenUsage and no estimator
    assertThat(response.usageMetadata()).isEmpty();
  }

  @Test
  @DisplayName("Should handle null AiMessage text without throwing NPE")
  void testGenerateContentWithNullAiMessageText() {
    // Given
    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(Part.fromText("Hello")))).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = mock(AiMessage.class);
    when(aiMessage.text()).thenReturn(null);
    when(aiMessage.hasToolExecutionRequests()).thenReturn(false);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    // When
    final Flowable<LlmResponse> responseFlowable = langChain4j.generateContent(llmRequest, false);
    final LlmResponse response = responseFlowable.blockingFirst();
    // Then - no NPE thrown, and content has no text parts
    assertThat(response).isNotNull();
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts().orElse(List.of())).isEmpty();
  }

  @Test
  @DisplayName("Should parse text/plain inlineData as TextContent without exception")
  void testGenerateContentWithTextPlainInlineData() {
    final String textPayload = "Hello, plain text.";
    final Blob textBlob =
        Blob.builder()
            .mimeType("text/plain")
            .data(textPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .build();
    final Part textPart = Part.builder().inlineData(textBlob).build();

    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(textPart))).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Acknowledged.");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    langChain4j.generateContent(llmRequest, false).blockingFirst();

    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    assertThat(capturedRequest.messages()).hasSize(1);
    assertThat(capturedRequest.messages().get(0)).isInstanceOf(UserMessage.class);
    final UserMessage userMessage = (UserMessage) capturedRequest.messages().get(0);

    assertThat(userMessage.contents()).hasSize(1);
    assertThat(userMessage.contents().get(0))
        .isInstanceOf(dev.langchain4j.data.message.TextContent.class);

    final dev.langchain4j.data.message.TextContent textContent =
        (dev.langchain4j.data.message.TextContent) userMessage.contents().get(0);
    assertThat(textContent.text()).isEqualTo(textPayload);
  }

  @Test
  @DisplayName("Should parse application/json inlineData as TextContent without exception")
  void testGenerateContentWithApplicationJsonInlineData() {
    final String jsonPayload = "{\"key\":\"value\"}";
    final Blob jsonBlob =
        Blob.builder()
            .mimeType("application/json")
            .data(jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .build();
    final Part jsonPart = Part.builder().inlineData(jsonBlob).build();

    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(jsonPart))).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Parsed JSON.");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    langChain4j.generateContent(llmRequest, false).blockingFirst();

    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    final UserMessage userMessage = (UserMessage) capturedRequest.messages().get(0);
    final dev.langchain4j.data.message.TextContent textContent =
        (dev.langchain4j.data.message.TextContent) userMessage.contents().get(0);
    assertThat(textContent.text()).isEqualTo(jsonPayload);
  }

  @Test
  @DisplayName(
      "Should throw IllegalArgumentException for genuinely unsupported inlineData mime types")
  void testGenerateContentWithUnsupportedMimeType() {
    final Blob unsupportedBlob =
        Blob.builder().mimeType("application/x-yaml").data(new byte[] {1, 2, 3, 4}).build();
    final Part unsupportedPart = Part.builder().inlineData(unsupportedBlob).build();

    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(unsupportedPart))).build();

    assertThatThrownBy(() -> langChain4j.generateContent(llmRequest, false).blockingFirst())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown or unhandled mime type: application/x-yaml");
  }

  @Test
  @DisplayName("Should extract and apply explicit charset from mimeType (e.g., UTF-16)")
  void testGenerateContentWithExplicitCharset() {
    final String textPayload = "Hello, this is strictly UTF-16 encoded text.";

    final byte[] utf16Bytes = textPayload.getBytes(java.nio.charset.StandardCharsets.UTF_16);

    final Blob textBlob =
        Blob.builder().mimeType("text/plain; charset=utf-16").data(utf16Bytes).build();
    final Part textPart = Part.builder().inlineData(textBlob).build();

    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(textPart))).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Acknowledged.");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    langChain4j.generateContent(llmRequest, false).blockingFirst();

    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    final UserMessage userMessage = (UserMessage) capturedRequest.messages().get(0);
    final dev.langchain4j.data.message.TextContent textContent =
        (dev.langchain4j.data.message.TextContent) userMessage.contents().get(0);

    assertThat(textContent.text()).isEqualTo(textPayload);
  }

  @Test
  @DisplayName("Should safely fallback to UTF-8 if provided charset is malformed or unsupported")
  void testGenerateContentWithMalformedCharsetFallback() {
    final String textPayload = "{\"status\": \"fallback to UTF-8 successful\"}";

    final byte[] utf8Bytes = textPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    final Blob jsonBlob =
        Blob.builder()
            .mimeType("application/json; charset=not-a-real-charset-12345")
            .data(utf8Bytes)
            .build();
    final Part jsonPart = Part.builder().inlineData(jsonBlob).build();

    final LlmRequest llmRequest =
        LlmRequest.builder().contents(List.of(Content.fromParts(jsonPart))).build();

    final ChatResponse chatResponse = mock(ChatResponse.class);
    final AiMessage aiMessage = AiMessage.from("Fallback verified.");
    when(chatResponse.aiMessage()).thenReturn(aiMessage);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    langChain4j.generateContent(llmRequest, false).blockingFirst();

    final ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(requestCaptor.capture());
    final ChatRequest capturedRequest = requestCaptor.getValue();

    final UserMessage userMessage = (UserMessage) capturedRequest.messages().get(0);
    final dev.langchain4j.data.message.TextContent textContent =
        (dev.langchain4j.data.message.TextContent) userMessage.contents().get(0);

    assertThat(textContent.text()).isEqualTo(textPayload);
  }

  @Test
  @DisplayName("Should auto-detect model name from ChatModel when not explicitly set")
  void testBuilderAutoDetectsModelNameFromChatModel() {
    // Given
    final ChatRequestParameters params = mock(ChatRequestParameters.class);
    when(params.modelName()).thenReturn("auto-detected-model");
    when(chatModel.defaultRequestParameters()).thenReturn(params);

    // When
    final LangChain4j lc4j = LangChain4j.builder().chatModel(chatModel).build();

    // Then
    assertThat(lc4j.modelName()).isEqualTo("auto-detected-model");
    assertThat(lc4j.model()).isEqualTo("auto-detected-model");
  }

  @Test
  @DisplayName("Should auto-detect model name from StreamingChatModel when not explicitly set")
  void testBuilderAutoDetectsModelNameFromStreamingChatModel() {
    // Given
    final ChatRequestParameters params = mock(ChatRequestParameters.class);
    when(params.modelName()).thenReturn("auto-detected-streaming-model");
    when(streamingChatModel.defaultRequestParameters()).thenReturn(params);

    // When
    final LangChain4j lc4j = LangChain4j.builder().streamingChatModel(streamingChatModel).build();

    // Then
    assertThat(lc4j.modelName()).isEqualTo("auto-detected-streaming-model");
    assertThat(lc4j.model()).isEqualTo("auto-detected-streaming-model");
  }

  @Test
  @DisplayName("Should prefer explicit model name over auto-detected one")
  void testBuilderPrefersExplicitModelName() {
    // Given
    final ChatRequestParameters params = mock(ChatRequestParameters.class);
    when(params.modelName()).thenReturn("auto-detected-model");
    when(chatModel.defaultRequestParameters()).thenReturn(params);

    // When
    final LangChain4j lc4j =
        LangChain4j.builder().chatModel(chatModel).modelName("explicit-model").build();

    // Then
    assertThat(lc4j.modelName()).isEqualTo("explicit-model");
  }

  @Test
  @DisplayName("Should fall back to StreamingChatModel name when ChatModel has none")
  void testBuilderFallsBackToStreamingModelNameWhenChatModelHasNone() {
    // Given
    final ChatRequestParameters emptyParams = mock(ChatRequestParameters.class);
    when(chatModel.defaultRequestParameters()).thenReturn(emptyParams);
    final ChatRequestParameters streamingParams = mock(ChatRequestParameters.class);
    when(streamingParams.modelName()).thenReturn("streaming-model");
    when(streamingChatModel.defaultRequestParameters()).thenReturn(streamingParams);

    // When
    final LangChain4j lc4j =
        LangChain4j.builder().chatModel(chatModel).streamingChatModel(streamingChatModel).build();

    // Then
    assertThat(lc4j.modelName()).isEqualTo("streaming-model");
  }

  @Test
  @DisplayName("Should fail when no source declares a model name")
  void testBuilderFailsWhenNoModelNameAvailable() {
    // Given
    final ChatRequestParameters emptyParams = mock(ChatRequestParameters.class);
    when(chatModel.defaultRequestParameters()).thenReturn(emptyParams);
    when(streamingChatModel.defaultRequestParameters()).thenReturn(emptyParams);

    // When / Then
    assertThrows(
        IllegalStateException.class,
        () ->
            LangChain4j.builder()
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .build());
    assertThrows(IllegalStateException.class, () -> LangChain4j.builder().build());
  }

  /** A streamed turn: its subscriber and the handler the test drives, as the model would. */
  private record StreamedTurn(
      TestSubscriber<LlmResponse> subscriber, StreamingChatResponseHandler handler) {}

  /** Starts a streamed turn and returns it before the model answers. */
  private StreamedTurn startStreamedTurn() {
    final StreamingChatResponseHandler[] handler = new StreamingChatResponseHandler[1];
    doAnswer(
            invocation -> {
              handler[0] = invocation.getArgument(1);
              return null;
            })
        .when(streamingChatModel)
        .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    final TestSubscriber<LlmResponse> subscriber =
        streamingLangChain4j
            .generateContent(
                LlmRequest.builder()
                    .contents(List.of(Content.fromParts(Part.fromText("Hello"))))
                    .build(),
                true)
            .test();
    return new StreamedTurn(subscriber, handler[0]);
  }
}
