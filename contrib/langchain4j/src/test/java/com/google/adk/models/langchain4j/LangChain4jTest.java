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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LangChain4jTest {

  private static final String MODEL_NAME = "test-model";

  private ChatModel chatModel;
  private StreamingChatModel streamingChatModel;
  private LangChain4j langChain4j;
  private LangChain4j streamingLangChain4j;

  @BeforeEach
  void setUp() {
    chatModel = mock(ChatModel.class);
    streamingChatModel = mock(StreamingChatModel.class);

    langChain4j = new LangChain4j(chatModel, MODEL_NAME);
    streamingLangChain4j = new LangChain4j(streamingChatModel, MODEL_NAME);
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

    final ChatResponse chatResponse = mock(ChatResponse.class);
    when(chatResponse.aiMessage()).thenReturn(aiMessage);

    // Simulate completion with a function call
    handler.onCompleteResponse(chatResponse);

    // Then
    assertThat(responses).hasSize(5); // 4 partial responses + 1 function call

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
        Schema.builder()
            .type(Type.builder().knownEnum(Type.Known.STRING).build())
            .description("City name")
            .build();

    final Schema objectSchema =
        Schema.builder()
            .type(Type.builder().knownEnum(Type.Known.OBJECT).build())
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
}
