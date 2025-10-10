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
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

/**
 * Converts between ADK and Spring AI message formats.
 *
 * <p>This converter handles the translation between ADK's Content/Part format (based on Google's
 * genai.types) and Spring AI's Message/ChatResponse format. This is a simplified initial version
 * that focuses on text content and basic function calling.
 */
public class MessageConverter {

  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

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

    // Convert config to ChatOptions
    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(llmRequest.config());

    // Convert ADK tools to Spring AI ToolCallback and add to ChatOptions
    if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
      List<ToolCallback> toolCallbacks = toolConverter.convertToSpringAiTools(llmRequest.tools());
      if (!toolCallbacks.isEmpty()) {
        // Create new ChatOptions with tools included
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder();

        // Always set tool callbacks
        optionsBuilder.toolCallbacks(toolCallbacks);

        // Copy existing chat options properties if present
        if (chatOptions != null) {
          // Copy all relevant properties from existing ChatOptions
          if (chatOptions.getTemperature() != null) {
            optionsBuilder.temperature(chatOptions.getTemperature());
          }
          if (chatOptions.getMaxTokens() != null) {
            optionsBuilder.maxTokens(chatOptions.getMaxTokens());
          }
          if (chatOptions.getTopP() != null) {
            optionsBuilder.topP(chatOptions.getTopP());
          }
          if (chatOptions.getTopK() != null) {
            optionsBuilder.topK(chatOptions.getTopK());
          }
          if (chatOptions.getStopSequences() != null) {
            optionsBuilder.stopSequences(chatOptions.getStopSequences());
          }
          // Copy model name if present
          if (chatOptions.getModel() != null) {
            optionsBuilder.model(chatOptions.getModel());
          }
          // Copy frequency penalty if present
          if (chatOptions.getFrequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(chatOptions.getFrequencyPenalty());
          }
          // Copy presence penalty if present
          if (chatOptions.getPresencePenalty() != null) {
            optionsBuilder.presencePenalty(chatOptions.getPresencePenalty());
          }
        }

        chatOptions = optionsBuilder.build();
      }
    }

    return new Prompt(messages, chatOptions);
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
    List<ToolResponseMessage> toolResponseMessages = new ArrayList<>();

    for (Part part : content.parts().orElse(List.of())) {
      if (part.text().isPresent()) {
        textBuilder.append(part.text().get());
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        List<ToolResponseMessage.ToolResponse> responses =
            List.of(
                new ToolResponseMessage.ToolResponse(
                    functionResponse.id().orElse(""),
                    functionResponse.name().orElseThrow(),
                    toJson(functionResponse.response().orElseThrow())));
        toolResponseMessages.add(new ToolResponseMessage(responses));
      }
      // TODO: Handle multimedia content and function calls in later steps
    }

    List<Message> messages = new ArrayList<>();
    // Always add UserMessage even if empty to maintain message structure
    messages.add(new UserMessage(textBuilder.toString()));
    messages.addAll(toolResponseMessages);

    return messages;
  }

  private AssistantMessage handleAssistantContent(Content content) {
    StringBuilder textBuilder = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

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
      }
    }

    String text = textBuilder.toString();
    if (toolCalls.isEmpty()) {
      return new AssistantMessage(text);
    } else {
      return new AssistantMessage(text, Map.of(), toolCalls);
    }
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

    return LlmResponse.builder()
        .content(content)
        .partial(isPartial)
        .turnComplete(isTurnComplete)
        .build();
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

    // Add tool calls
    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
      if ("function".equals(toolCall.type())) {
        try {
          Map<String, Object> args =
              objectMapper.readValue(toolCall.arguments(), MAP_TYPE_REFERENCE);

          // Create FunctionCall with ID, name, and args to preserve tool call ID
          FunctionCall functionCall =
              FunctionCall.builder().id(toolCall.id()).name(toolCall.name()).args(args).build();

          // Create Part with the FunctionCall (preserves ID)
          parts.add(Part.builder().functionCall(functionCall).build());
        } catch (JsonProcessingException e) {
          throw MessageConversionException.jsonParsingFailed("tool call arguments", e);
        }
      }
    }

    return Content.builder().role("model").parts(parts).build();
  }

  private String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw MessageConversionException.jsonParsingFailed("object serialization", e);
    }
  }
}
