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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;

/**
 * Converts between ADK and Spring AI message formats.
 *
 * <p>This converter handles the translation between ADK's Content/Part format (based on Google's
 * genai.types) and Spring AI's Message/ChatResponse format. It supports:
 *
 * <ul>
 *   <li>Text content in all message types
 *   <li>Tool/function calls in assistant messages
 *   <li>Tool/function responses as {@link ToolResponseMessage}s
 *   <li>System instructions and configuration options
 * </ul>
 */
public class MessageConverter {

  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  /**
   * Message-metadata key the Spring AI Google GenAI provider uses to carry Gemini thought
   * signatures. These must be replayed on the model turn of the next request or tool calling fails,
   * so they are preserved across the ADK round-trip. See
   * https://ai.google.dev/gemini-api/docs/thought-signatures.
   */
  private static final String THOUGHT_SIGNATURES_KEY = "thoughtSignatures";

  private final ObjectMapper objectMapper;
  private final ToolConverter toolConverter;
  private final ConfigMapper configMapper;

  public MessageConverter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.toolConverter = new ToolConverter();
    this.configMapper = new ConfigMapper();
  }

  /**
   * Converts an ADK LlmRequest to a Spring AI Prompt.
   *
   * @param llmRequest The ADK request to convert
   * @return A Spring AI Prompt
   */
  public Prompt toLlmPrompt(LlmRequest llmRequest) {
    return toLlmPrompt(llmRequest, null);
  }

  /**
   * Converts an ADK LlmRequest to a Spring AI Prompt, using the target model's own default options
   * as the base for the prompt options.
   *
   * <p>Provider-specific chat models (for example Spring AI OpenAI {@code 2.0.0}) cast {@code
   * Prompt.getOptions()} directly to their own options type (e.g. {@code OpenAiChatOptions}) in
   * {@code createRequest(...)}. Passing provider-neutral options such as {@code
   * DefaultToolCallingChatOptions} therefore triggers a {@link ClassCastException}. To stay
   * compatible with any provider, the prompt options are built on top of the model's own default
   * options (obtained via {@code ChatModel.getOptions()}) so the resulting options keep the
   * concrete type the provider expects, while overlaying the ADK tools and generation config.
   *
   * @param llmRequest The ADK request to convert
   * @param modelDefaultOptions The target model's default options, or {@code null} if unavailable
   * @return A Spring AI Prompt
   */
  public Prompt toLlmPrompt(LlmRequest llmRequest, ChatOptions modelDefaultOptions) {
    List<Message> messages = new ArrayList<>();
    List<String> allSystemMessages = new ArrayList<>();

    // Collect system instructions from LlmRequest
    allSystemMessages.addAll(llmRequest.getSystemInstructions());

    // Collect system messages from Content objects
    List<Message> nonSystemMessages = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      String role = content.role().orElse("user").toLowerCase();
      if ("system".equals(role)) {
        // Extract text from system content and add to combined system message
        StringBuilder systemText = new StringBuilder();
        for (Part part : content.parts().orElse(List.of())) {
          if (part.text().isPresent()) {
            systemText.append(part.text().get());
          }
        }
        if (systemText.length() > 0) {
          allSystemMessages.add(systemText.toString());
        }
      } else {
        // Handle non-system messages normally
        nonSystemMessages.addAll(toSpringAiMessages(content));
      }
    }

    // Create single combined SystemMessage if any system content exists
    if (!allSystemMessages.isEmpty()) {
      String combinedSystemMessage = String.join("\n\n", allSystemMessages);
      messages.add(new SystemMessage(combinedSystemMessage));
    }

    // Add all non-system messages
    messages.addAll(nonSystemMessages);

    return new Prompt(messages, buildChatOptions(llmRequest, modelDefaultOptions));
  }

  /**
   * Builds the Spring AI {@link ChatOptions} for a request by overlaying the ADK generation config
   * and tools on top of the model's own default options.
   *
   * @param llmRequest The ADK request being converted
   * @param modelDefaultOptions The target model's default options, or {@code null} if unavailable
   * @return The chat options to attach to the prompt, or {@code null} when there is nothing
   *     ADK-specific to configure (letting the model apply its own defaults)
   */
  private ChatOptions buildChatOptions(LlmRequest llmRequest, ChatOptions modelDefaultOptions) {
    // ADK generation config (temperature, max tokens, ...) as provider-neutral options.
    ChatOptions adkChatOptions = configMapper.toSpringAiChatOptions(llmRequest.config());

    // ADK tools converted to Spring AI tool callbacks.
    List<ToolCallback> toolCallbacks = List.of();
    if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
      toolCallbacks = toolConverter.convertToSpringAiTools(llmRequest.tools());
    }

    boolean hasTools = !toolCallbacks.isEmpty();
    boolean hasAdkConfig = adkChatOptions != null;

    // Nothing ADK-specific to add: let the provider fall back to its own default options.
    if (!hasTools && !hasAdkConfig) {
      return null;
    }

    // Preferred path: start from the model's own options so the resulting options keep the concrete
    // provider type (e.g. OpenAiChatOptions) and preserve provider-specific settings such as the
    // API key, base URL and model name. This avoids the ClassCastException thrown by providers
    // (like Spring AI OpenAI 2.0.0) that cast Prompt.getOptions() to their own options type.
    if (modelDefaultOptions instanceof ToolCallingChatOptions) {
      ToolCallingChatOptions.Builder<?> optionsBuilder =
          ((ToolCallingChatOptions) modelDefaultOptions).mutate();
      if (hasTools) {
        optionsBuilder.toolCallbacks(toolCallbacks);
      }
      applyGenerationConfig(optionsBuilder, adkChatOptions);
      return optionsBuilder.build();
    }

    // Fallback: the model's default options are unavailable or not tool-calling capable. Preserve
    // the provider-neutral behavior, which works for providers that normalize generic options.
    if (hasTools) {
      ToolCallingChatOptions.Builder<?> optionsBuilder = ToolCallingChatOptions.builder();
      optionsBuilder.toolCallbacks(toolCallbacks);
      applyGenerationConfig(optionsBuilder, adkChatOptions);
      return optionsBuilder.build();
    }
    return adkChatOptions;
  }

  /** Copies the non-null generation parameters from {@code source} onto {@code builder}. */
  private void applyGenerationConfig(ChatOptions.Builder<?> builder, ChatOptions source) {
    if (source == null) {
      return;
    }
    if (source.getTemperature() != null) {
      builder.temperature(source.getTemperature());
    }
    if (source.getMaxTokens() != null) {
      builder.maxTokens(source.getMaxTokens());
    }
    if (source.getTopP() != null) {
      builder.topP(source.getTopP());
    }
    if (source.getTopK() != null) {
      builder.topK(source.getTopK());
    }
    if (source.getStopSequences() != null) {
      builder.stopSequences(source.getStopSequences());
    }
    if (source.getModel() != null) {
      builder.model(source.getModel());
    }
    if (source.getFrequencyPenalty() != null) {
      builder.frequencyPenalty(source.getFrequencyPenalty());
    }
    if (source.getPresencePenalty() != null) {
      builder.presencePenalty(source.getPresencePenalty());
    }
  }

  /**
   * Gets tool registry from ADK tools for internal tracking.
   *
   * @param llmRequest The ADK request containing tools
   * @return Map of tool metadata for tracking available tools
   */
  public Map<String, ToolConverter.ToolMetadata> getToolRegistry(LlmRequest llmRequest) {
    return toolConverter.createToolRegistry(llmRequest.tools());
  }

  /**
   * Converts an ADK Content to Spring AI Message(s).
   *
   * @param content The ADK content to convert
   * @return A list of Spring AI messages
   */
  private List<Message> toSpringAiMessages(Content content) {
    String role = content.role().orElse("user").toLowerCase();

    return switch (role) {
      case "user" -> handleUserContent(content);
      case "model", "assistant" -> List.of(handleAssistantContent(content));
      case "system" -> List.of(handleSystemContent(content));
      default -> throw new IllegalStateException("Unexpected role: " + role);
    };
  }

  private List<Message> handleUserContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
    List<Media> mediaList = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(
                functionResponse.id().orElse(""),
                functionResponse.name().orElse(""),
                toJson(functionResponse.response().orElse(Map.of()))));
      } else if (part.inlineData().isPresent()) {
        // Handle inline media data (images, audio, video, etc.)
        com.google.genai.types.Blob blob = part.inlineData().get();
        if (blob.mimeType().isPresent() && blob.data().isPresent()) {
          try {
            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
            // Create Media object from inline data using ByteArrayResource
            org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(blob.data().get());
            mediaList.add(new Media(mimeType, resource));
          } catch (Exception e) {
            // Log warning but continue processing other parts
            // In production, consider proper logging framework
            System.err.println("Warning: Failed to process media part: " + e.getMessage());
          }
        }
      } else if (part.fileData().isPresent()) {
        // Handle file-based media (URI references)
        com.google.genai.types.FileData fileData = part.fileData().get();
        if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
          try {
            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
            // Create Media object from file URI
            URI uri = URI.create(fileData.fileUri().get());
            mediaList.add(new Media(mimeType, uri));
          } catch (Exception e) {
            System.err.println("Warning: Failed to process media part: " + e.getMessage());
          }
        }
      }
    }

    List<Message> messages = new ArrayList<>();
    String text = textBuilder.toString();
    // Emit a UserMessage for any text/media, or for an otherwise-empty turn; but when the turn only
    // carries function responses, emit just the ToolResponseMessage so the request does not end on
    // the model's tool-call turn (which the backend rejects).
    if (!text.isEmpty() || !mediaList.isEmpty() || toolResponses.isEmpty()) {
      messages.add(UserMessage.builder().text(text).media(mediaList).build());
    }
    if (!toolResponses.isEmpty()) {
      messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
    }

    return messages;
  }

  private AssistantMessage handleAssistantContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    List<byte[]> thoughtSignatures = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      } else if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        toolCalls.add(
            new AssistantMessage.ToolCall(
                functionCall
                    .id()
                    .orElseThrow(() -> new IllegalStateException("Function call ID is missing")),
                "function",
                functionCall
                    .name()
                    .orElseThrow(() -> new IllegalStateException("Function call name is missing")),
                toJson(functionCall.args().orElse(Map.of()))));
        part.thoughtSignature().ifPresent(thoughtSignatures::add);
      }
    }

    String text = textBuilder.toString();
    if (toolCalls.isEmpty()) {
      return new AssistantMessage(text);
    }
    Map<String, Object> properties =
        thoughtSignatures.isEmpty() ? Map.of() : Map.of(THOUGHT_SIGNATURES_KEY, thoughtSignatures);
    return AssistantMessage.builder()
        .content(text)
        .properties(properties)
        .toolCalls(toolCalls)
        .build();
  }

  private SystemMessage handleSystemContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      }
    }
    return new SystemMessage(textBuilder.toString());
  }

  /**
   * Converts a Spring AI ChatResponse to an ADK LlmResponse.
   *
   * @param chatResponse The Spring AI response to convert
   * @return An ADK LlmResponse
   */
  public LlmResponse toLlmResponse(ChatResponse chatResponse) {
    return toLlmResponse(chatResponse, false);
  }

  /**
   * Converts a Spring AI ChatResponse to an ADK LlmResponse with streaming context.
   *
   * @param chatResponse The Spring AI response to convert
   * @param isStreaming Whether this is part of a streaming response
   * @return An ADK LlmResponse
   */
  public LlmResponse toLlmResponse(ChatResponse chatResponse, boolean isStreaming) {
    if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getResults())) {
      return LlmResponse.builder().build();
    }

    Generation generation = chatResponse.getResult();
    AssistantMessage assistantMessage = generation.getOutput();

    Content content = convertAssistantMessageToContent(assistantMessage);

    // For streaming responses, check if this is a partial response
    boolean isPartial = isStreaming && isPartialResponse(assistantMessage);
    boolean isTurnComplete = !isStreaming || isTurnCompleteResponse(chatResponse);

    LlmResponse.Builder responseBuilder =
        LlmResponse.builder().content(content).partial(isPartial).turnComplete(isTurnComplete);

    if (chatResponse.getMetadata() != null
        && chatResponse.getMetadata().getUsage() != null
        && !(chatResponse.getMetadata().getUsage() instanceof EmptyUsage)) {
      Usage springUsage = chatResponse.getMetadata().getUsage();

      GenerateContentResponseUsageMetadata adkUsage =
          GenerateContentResponseUsageMetadata.builder()
              .promptTokenCount(nullSafeInt(springUsage.getPromptTokens()))
              .candidatesTokenCount(nullSafeInt(springUsage.getCompletionTokens()))
              .totalTokenCount(nullSafeInt(springUsage.getTotalTokens()))
              .build();
      responseBuilder.usageMetadata(adkUsage);
    }
    return responseBuilder.build();
  }

  private int nullSafeInt(Integer value) {
    return value != null ? value.intValue() : 0;
  }

  /** Determines if an assistant message represents a partial response in streaming. */
  private boolean isPartialResponse(AssistantMessage message) {
    // Check if message has incomplete content (e.g., ends mid-sentence, has pending tool calls)
    if (message.getText() != null && !message.getText().isEmpty()) {
      String text = message.getText().trim();
      // Simple heuristic: if text doesn't end with punctuation, it might be partial
      if (!text.endsWith(".")
          && !text.endsWith("!")
          && !text.endsWith("?")
          && !text.endsWith("\n")
          && message.getToolCalls().isEmpty()) {
        return true;
      }
    }

    // If there are tool calls, it's typically not partial (tool calls are discrete)
    return false;
  }

  /** Determines if a chat response indicates the turn is complete. */
  private boolean isTurnCompleteResponse(ChatResponse response) {
    // In Spring AI, we can check the finish reason or other metadata
    // For now, assume turn is complete unless we have clear indication otherwise
    Generation generation = response.getResult();
    if (generation != null && generation.getMetadata() != null) {
      // Check if there's a finish reason indicating completion
      String finishReason = generation.getMetadata().getFinishReason();
      return finishReason == null
          || "stop".equals(finishReason)
          || "tool_calls".equals(finishReason);
    }
    return true;
  }

  private Content convertAssistantMessageToContent(AssistantMessage assistantMessage) {
    List<Part> parts = new ArrayList<>();

    // Add text content
    if (assistantMessage.getText() != null && !assistantMessage.getText().isEmpty()) {
      parts.add(Part.fromText(assistantMessage.getText()));
    }

    // Gemini thinking models return a thought signature per function-call part; keep them on the
    // parts so they can be replayed on the next turn (required for tool calling).
    List<byte[]> thoughtSignatures = extractThoughtSignatures(assistantMessage);
    int functionCallIndex = 0;

    // Add tool calls
    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
      if ("function".equals(toolCall.type())) {
        try {
          Map<String, Object> args =
              objectMapper.readValue(toolCall.arguments(), MAP_TYPE_REFERENCE);

          // Create FunctionCall with ID, name, and args to preserve tool call ID
          FunctionCall functionCall =
              FunctionCall.builder().id(toolCall.id()).name(toolCall.name()).args(args).build();

          // Create Part with the FunctionCall (preserves ID), reattaching any thought signature.
          Part.Builder partBuilder = Part.builder().functionCall(functionCall);
          if (functionCallIndex < thoughtSignatures.size()) {
            partBuilder.thoughtSignature(thoughtSignatures.get(functionCallIndex));
          }
          parts.add(partBuilder.build());
          functionCallIndex++;
        } catch (JsonProcessingException e) {
          throw MessageConversionException.jsonParsingFailed("tool call arguments", e);
        }
      }
    }

    return Content.builder().role("model").parts(parts).build();
  }

  @SuppressWarnings("unchecked")
  private static List<byte[]> extractThoughtSignatures(AssistantMessage assistantMessage) {
    Map<String, Object> metadata = assistantMessage.getMetadata();
    if (metadata == null) {
      return List.of();
    }
    Object signatures = metadata.get(THOUGHT_SIGNATURES_KEY);
    return (signatures instanceof List) ? (List<byte[]>) signatures : List.of();
  }

  private String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw MessageConversionException.jsonParsingFailed("object serialization", e);
    }
  }
}
