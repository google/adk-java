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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class MessageConverterTest {

  private MessageConverter messageConverter;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    messageConverter = new MessageConverter(objectMapper);
  }

  @Test
  void testToLlmPromptWithUserMessage() {
    Content userContent =
        Content.builder().role("user").parts(List.of(Part.fromText("Hello, how are you?"))).build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(UserMessage.class);
    UserMessage userMessage = (UserMessage) message;
    assertThat(userMessage.getText()).isEqualTo("Hello, how are you?");
    assertThat(userMessage.getMedia()).isEmpty();
  }

  @Test
  void testToLlmPromptWithSystemInstructions() {
    Content userContent =
        Content.builder().role("user").parts(List.of(Part.fromText("Hello"))).build();

    LlmRequest request =
        LlmRequest.builder()
            .appendInstructions(List.of("You are a helpful assistant"))
            .contents(List.of(userContent))
            .build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(2);

    Message systemMessage = prompt.getInstructions().get(0);
    assertThat(systemMessage).isInstanceOf(SystemMessage.class);
    assertThat(((SystemMessage) systemMessage).getText()).isEqualTo("You are a helpful assistant");

    Message userMessage = prompt.getInstructions().get(1);
    assertThat(userMessage).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) userMessage).getText()).isEqualTo("Hello");
  }

  @Test
  void testToLlmPromptWithAssistantMessage() {
    Content assistantContent =
        Content.builder()
            .role("model")
            .parts(List.of(Part.fromText("I'm doing well, thank you!")))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(assistantContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(AssistantMessage.class);
    assertThat(((AssistantMessage) message).getText()).isEqualTo("I'm doing well, thank you!");
  }

  @Test
  void testToLlmPromptWithFunctionCall() {
    FunctionCall functionCall =
        FunctionCall.builder()
            .name("get_weather")
            .args(Map.of("location", "San Francisco"))
            .id("call_123")
            .build();

    // Create Part with FunctionCall inside using Part.builder
    Part functionCallPart = Part.builder().functionCall(functionCall).build();

    Content assistantContent =
        Content.builder()
            .role("model")
            .parts(Part.fromText("Let me check the weather for you."), functionCallPart)
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(assistantContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(AssistantMessage.class);

    AssistantMessage assistantMessage = (AssistantMessage) message;
    assertThat(assistantMessage.getText()).isEqualTo("Let me check the weather for you.");
    assertThat(assistantMessage.getToolCalls()).hasSize(1);

    AssistantMessage.ToolCall toolCall = assistantMessage.getToolCalls().get(0);
    assertThat(toolCall.id()).isEqualTo("call_123"); // ID should be preserved now
    assertThat(toolCall.name()).isEqualTo("get_weather");
    assertThat(toolCall.type()).isEqualTo("function");
  }

  @Test
  void testToLlmPromptWithFunctionResponse() {
    // TODO: This test is currently limited due to Spring AI 1.1.0 API constraints
    // ToolResponseMessage constructors are protected, so function responses are skipped
    // Once Spring AI provides public APIs, this test should be updated to verify:
    // 1. ToolResponseMessage is created
    // 2. Tool response data is properly converted
    // 3. Tool call IDs are preserved

    FunctionResponse functionResponse =
        FunctionResponse.builder()
            .name("get_weather")
            .response(Map.of("temperature", "72°F", "condition", "sunny"))
            .id("call_123")
            .build();

    Content userContent =
        Content.builder()
            .role("user")
            .parts(
                Part.fromText("What's the weather?"),
                Part.fromFunctionResponse(
                    functionResponse.name().orElse(""),
                    functionResponse.response().orElse(Map.of())))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    // Currently only UserMessage is created (function response is skipped)
    assertThat(prompt.getInstructions()).hasSize(1);

    Message userMessage = prompt.getInstructions().get(0);
    assertThat(userMessage).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) userMessage).getText()).isEqualTo("What's the weather?");

    // When Spring AI provides public API for ToolResponseMessage, uncomment:
    // Message toolResponseMessage = prompt.getInstructions().get(1);
    // assertThat(toolResponseMessage).isInstanceOf(ToolResponseMessage.class);
    // ToolResponseMessage toolResponse = (ToolResponseMessage) toolResponseMessage;
    // assertThat(toolResponse.getResponses()).hasSize(1);
    // ToolResponseMessage.ToolResponse response = toolResponse.getResponses().get(0);
    // assertThat(response.name()).isEqualTo("get_weather");
  }

  @Test
  void testToLlmResponseFromChatResponse() {
    AssistantMessage assistantMessage = new AssistantMessage("Hello there!");
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

    assertThat(llmResponse.content()).isPresent();
    Content content = llmResponse.content().get();
    assertThat(content.role()).contains("model");
    assertThat(content.parts()).isPresent();
    assertThat(content.parts().get()).hasSize(1);
    assertThat(content.parts().get().get(0).text()).contains("Hello there!");
  }

  @Test
  void testToLlmResponseFromChatResponseWithToolCalls() {
    AssistantMessage.ToolCall toolCall =
        new AssistantMessage.ToolCall(
            "call_123", "function", "get_weather", "{\"location\":\"San Francisco\"}");

    AssistantMessage assistantMessage =
        AssistantMessage.builder()
            .content("Let me check the weather.")
            .toolCalls(List.of(toolCall))
            .build();

    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

    assertThat(llmResponse.content()).isPresent();
    Content content = llmResponse.content().get();
    assertThat(content.parts()).isPresent();
    assertThat(content.parts().get()).hasSize(2);

    Part textPart = content.parts().get().get(0);
    assertThat(textPart.text()).contains("Let me check the weather.");

    Part functionCallPart = content.parts().get().get(1);
    assertThat(functionCallPart.functionCall()).isPresent();
    assertThat(functionCallPart.functionCall().get().name()).contains("get_weather");
    // Verify ID is preserved
    assertThat(functionCallPart.functionCall().get().id()).contains("call_123");
  }

  @Test
  void testToolCallIdPreservedInConversion() {
    // Create AssistantMessage with tool call including ID
    AssistantMessage.ToolCall toolCall =
        new AssistantMessage.ToolCall(
            "call_abc123", // ID must be preserved
            "function",
            "get_weather",
            "{\"location\":\"San Francisco\"}");

    AssistantMessage assistantMessage =
        AssistantMessage.builder()
            .content("Let me check the weather.")
            .toolCalls(List.of(toolCall))
            .build();

    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    // Convert to LlmResponse
    LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

    // Verify the converted content preserves the tool call ID
    assertThat(llmResponse.content()).isPresent();
    Content content = llmResponse.content().get();
    assertThat(content.parts()).isPresent();

    List<Part> parts = content.parts().get();
    Part functionCallPart =
        parts.stream()
            .filter(p -> p.functionCall().isPresent())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected function call part"));

    FunctionCall convertedCall = functionCallPart.functionCall().get();
    assertThat(convertedCall.id()).contains("call_abc123"); // ✅ ID MUST BE PRESERVED
    assertThat(convertedCall.name()).contains("get_weather");
    assertThat(convertedCall.args()).isPresent();
    assertThat(convertedCall.args().get()).containsEntry("location", "San Francisco");
  }

  @Test
  void testToLlmResponseWithEmptyResponse() {
    ChatResponse emptyChatResponse = new ChatResponse(List.of());

    LlmResponse llmResponse = messageConverter.toLlmResponse(emptyChatResponse);

    assertThat(llmResponse.content()).isEmpty();
  }

  @Test
  void testToLlmResponseWithNullResponse() {
    LlmResponse llmResponse = messageConverter.toLlmResponse(null);

    assertThat(llmResponse.content()).isEmpty();
  }

  @Test
  void testToLlmResponseStreamingMode() {
    AssistantMessage assistantMessage = new AssistantMessage("Partial response");
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse, true);

    assertThat(llmResponse.partial()).contains(true);
    assertThat(llmResponse.turnComplete()).contains(true);
  }

  @Test
  void testToLlmResponseNonStreamingMode() {
    AssistantMessage assistantMessage = new AssistantMessage("Complete response.");
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse, false);

    assertThat(llmResponse.partial()).contains(false);
    assertThat(llmResponse.turnComplete()).contains(true);
  }

  @Test
  void testPartialResponseDetection() {
    // Test partial response (no punctuation ending)
    AssistantMessage partialMessage = new AssistantMessage("I am thinking");
    Generation partialGeneration = new Generation(partialMessage);
    ChatResponse partialResponse = new ChatResponse(List.of(partialGeneration));

    LlmResponse partialLlmResponse = messageConverter.toLlmResponse(partialResponse, true);
    assertThat(partialLlmResponse.partial()).contains(true);

    // Test complete response (ends with punctuation)
    AssistantMessage completeMessage = new AssistantMessage("I am done.");
    Generation completeGeneration = new Generation(completeMessage);
    ChatResponse completeResponse = new ChatResponse(List.of(completeGeneration));

    LlmResponse completeLlmResponse = messageConverter.toLlmResponse(completeResponse, true);
    assertThat(completeLlmResponse.partial()).contains(false);
  }

  @Test
  void testHandleSystemContent() {
    Content systemContent =
        Content.builder()
            .role("system")
            .parts(List.of(Part.fromText("You are a helpful assistant.")))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(systemContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(SystemMessage.class);
    assertThat(((SystemMessage) message).getText()).isEqualTo("You are a helpful assistant.");
  }

  @Test
  void testHandleUnknownRole() {
    Content unknownContent =
        Content.builder().role("unknown").parts(List.of(Part.fromText("Test message"))).build();

    LlmRequest request = LlmRequest.builder().contents(List.of(unknownContent)).build();

    assertThrows(IllegalStateException.class, () -> messageConverter.toLlmPrompt(request));
  }

  @Test
  void testMultipleContentParts() {
    Content multiPartContent =
        Content.builder()
            .role("user")
            .parts(List.of(Part.fromText("First part. "), Part.fromText("Second part.")))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(multiPartContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) message).getText()).isEqualTo("First part. Second part.");
  }

  @Test
  void testEmptyContentParts() {
    Content emptyContent = Content.builder().role("user").build();

    LlmRequest request = LlmRequest.builder().contents(List.of(emptyContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) message).getText()).isEmpty();
  }

  @Test
  void testGetToolRegistry() {
    Map<String, com.google.adk.tools.BaseTool> emptyTools = Map.of();
    LlmRequest request = LlmRequest.builder().contents(List.of()).build();

    Map<String, ToolConverter.ToolMetadata> toolRegistry =
        messageConverter.getToolRegistry(request);

    assertThat(toolRegistry).isNotNull();
  }

  @Test
  void testCombineMultipleSystemMessagesForGeminiCompatibility() {
    // Test that multiple system Content objects are combined into one system message for Gemini
    // compatibility
    Content systemContent1 =
        Content.builder()
            .role("system")
            .parts(List.of(Part.fromText("You are a helpful assistant.")))
            .build();
    Content systemContent2 =
        Content.builder()
            .role("system")
            .parts(List.of(Part.fromText("Be concise in your responses.")))
            .build();
    Content userContent =
        Content.builder().role("user").parts(List.of(Part.fromText("Hello world"))).build();

    LlmRequest request =
        LlmRequest.builder().contents(List.of(systemContent1, systemContent2, userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    // Should have exactly one system message (combined) plus the user message
    assertThat(prompt.getInstructions()).hasSize(2);

    // First message should be the combined system message
    Message firstMessage = prompt.getInstructions().get(0);
    assertThat(firstMessage).isInstanceOf(SystemMessage.class);
    String combinedSystemText = ((SystemMessage) firstMessage).getText();
    assertThat(combinedSystemText)
        .contains("You are a helpful assistant.")
        .contains("Be concise in your responses.");

    // Second message should be the user message
    Message secondMessage = prompt.getInstructions().get(1);
    assertThat(secondMessage).isInstanceOf(UserMessage.class);
    assertThat(((UserMessage) secondMessage).getText()).isEqualTo("Hello world");
  }

  @Test
  void testUserMessageWithInlineMediaData() {
    // Test conversion of ADK Content with inline media (image bytes) to Spring AI UserMessage
    byte[] imageData = "fake-image-data".getBytes();
    String mimeType = "image/png";

    Content userContent =
        Content.builder()
            .role("user")
            .parts(
                List.of(
                    Part.fromText("What's in this image?"),
                    Part.builder()
                        .inlineData(
                            com.google.genai.types.Blob.builder()
                                .mimeType(mimeType)
                                .data(imageData)
                                .build())
                        .build()))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(UserMessage.class);

    UserMessage userMessage = (UserMessage) message;
    assertThat(userMessage.getText()).isEqualTo("What's in this image?");
    assertThat(userMessage.getMedia()).hasSize(1);
    org.springframework.ai.content.Media media = userMessage.getMedia().get(0);
    assertThat(media.getMimeType().toString()).isEqualTo(mimeType);
    assertThat(media.getData()).isInstanceOf(byte[].class);
    byte[] actualData = (byte[]) media.getData();
    assertThat(actualData).isEqualTo(imageData);
  }

  @Test
  void testUserMessageWithFileMediaData() {
    // Test conversion of ADK Content with file-based media (URI) to Spring AI UserMessage
    String fileUri = "gs://bucket/image.jpg";
    String mimeType = "image/jpeg";

    Content userContent =
        Content.builder()
            .role("user")
            .parts(
                List.of(
                    Part.fromText("Analyze this image"),
                    Part.builder()
                        .fileData(
                            com.google.genai.types.FileData.builder()
                                .mimeType(mimeType)
                                .fileUri(fileUri)
                                .build())
                        .build()))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    Message message = prompt.getInstructions().get(0);
    assertThat(message).isInstanceOf(UserMessage.class);

    UserMessage userMessage = (UserMessage) message;
    assertThat(userMessage.getText()).isEqualTo("Analyze this image");
    assertThat(userMessage.getMedia()).hasSize(1);
    org.springframework.ai.content.Media media = userMessage.getMedia().get(0);
    assertThat(media.getMimeType().toString()).isEqualTo(mimeType);
    assertThat(media.getData()).isInstanceOf(String.class);
    String actualUri = (String) media.getData();
    assertThat(actualUri).isEqualTo(fileUri);
  }

  @Test
  void testUserMessageWithMultipleMediaAttachments() {
    // Test conversion with multiple media attachments
    byte[] image1 = "image1-data".getBytes();
    byte[] image2 = "image2-data".getBytes();

    Content userContent =
        Content.builder()
            .role("user")
            .parts(
                List.of(
                    Part.fromText("Compare these images"),
                    Part.builder()
                        .inlineData(
                            com.google.genai.types.Blob.builder()
                                .mimeType("image/png")
                                .data(image1)
                                .build())
                        .build(),
                    Part.builder()
                        .inlineData(
                            com.google.genai.types.Blob.builder()
                                .mimeType("image/jpeg")
                                .data(image2)
                                .build())
                        .build()))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    UserMessage userMessage = (UserMessage) prompt.getInstructions().get(0);
    assertThat(userMessage.getText()).isEqualTo("Compare these images");
    assertThat(userMessage.getMedia()).hasSize(2);
  }

  @Test
  void testUserMessageWithMediaOnly() {
    // Test conversion with media but no text
    byte[] imageData = "image-only".getBytes();

    Content userContent =
        Content.builder()
            .role("user")
            .parts(
                List.of(
                    Part.builder()
                        .inlineData(
                            com.google.genai.types.Blob.builder()
                                .mimeType("image/png")
                                .data(imageData)
                                .build())
                        .build()))
            .build();

    LlmRequest request = LlmRequest.builder().contents(List.of(userContent)).build();

    Prompt prompt = messageConverter.toLlmPrompt(request);

    assertThat(prompt.getInstructions()).hasSize(1);
    UserMessage userMessage = (UserMessage) prompt.getInstructions().get(0);
    assertThat(userMessage.getText()).isEmpty();
    assertThat(userMessage.getMedia()).hasSize(1);
  }
}
