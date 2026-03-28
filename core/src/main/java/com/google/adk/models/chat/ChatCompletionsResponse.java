/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.models.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FinishReason.Known;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Data Transfer Objects for Chat Completion and Chat Completion Chunk API responses.
 *
 * <p>See https://developers.openai.com/api/reference/resources/chat
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class ChatCompletionsResponse {

  private ChatCompletionsResponse() {}

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ChatCompletion {
    /** See class definition for more details. */
    public String id;

    /** See class definition for more details. */
    public List<Choice> choices;

    /** See class definition for more details. */
    public Long created;

    /** See class definition for more details. */
    public String model;

    /** See class definition for more details. */
    public String object;

    /** See class definition for more details. */
    @JsonProperty("service_tier")
    public String serviceTier;

    /** Deprecated. See class definition for more details. */
    @JsonProperty("system_fingerprint")
    public String systemFingerprint;

    /** See class definition for more details. */
    public Usage usage;

    /**
     * Converts this chat completion to a {@link LlmResponse}.
     *
     * @return the {@link LlmResponse} object.
     */
    public LlmResponse toLlmResponse() {
      Choice choice = (choices != null && !choices.isEmpty()) ? choices.get(0) : null;
      Content content = mapChoiceToContent(choice);

      LlmResponse.Builder builder = LlmResponse.builder().content(content);

      if (choice != null) {
        builder.finishReason(mapFinishReason(choice.finishReason));
      }

      if (model != null) {
        builder.modelVersion(model);
      }

      if (usage != null) {
        builder.usageMetadata(mapUsage(usage));
      }

      List<CustomMetadata> customMetadataList = buildCustomMetadata();
      return builder.customMetadata(customMetadataList).build();
    }

    /**
     * Maps the finish reason string to a {@link FinishReason}.
     *
     * @param reason the finish reason string.
     * @return the {@link FinishReason}, or {@code null} if the input reason is null.
     */
    private @Nullable FinishReason mapFinishReason(String reason) {
      if (reason == null) {
        return null;
      }
      return switch (reason) {
        case "stop", "tool_calls" -> new FinishReason(Known.STOP.toString());
        case "length" -> new FinishReason(Known.MAX_TOKENS.toString());
        case "content_filter" -> new FinishReason(Known.SAFETY.toString());
        default -> new FinishReason(Known.OTHER.toString());
      };
    }

    private GenerateContentResponseUsageMetadata mapUsage(Usage usage) {
      GenerateContentResponseUsageMetadata.Builder builder =
          GenerateContentResponseUsageMetadata.builder();
      if (usage.promptTokens != null) {
        builder.promptTokenCount(usage.promptTokens);
      }
      if (usage.completionTokens != null) {
        builder.candidatesTokenCount(usage.completionTokens);
      }
      if (usage.totalTokens != null) {
        builder.totalTokenCount(usage.totalTokens);
      }
      if (usage.thoughtsTokenCount != null) {
        builder.thoughtsTokenCount(usage.thoughtsTokenCount);
      } else if (usage.completionTokensDetails != null
          && usage.completionTokensDetails.reasoningTokens != null) {
        builder.thoughtsTokenCount(usage.completionTokensDetails.reasoningTokens);
      }
      return builder.build();
    }

    /**
     * Maps the chosen completion to a {@link Content} object.
     *
     * @param choice the completion choice to map, or {@code null}.
     * @return the {@link Content} object, which will be empty if the choice or its message is null.
     */
    private Content mapChoiceToContent(@Nullable Choice choice) {
      Content.Builder contentBuilder = Content.builder();
      if (choice != null && choice.message != null) {
        contentBuilder.role(mapRole(choice.message.role)).parts(mapMessageToParts(choice.message));
      }
      return contentBuilder.build();
    }

    private String mapRole(@Nullable String role) {
      return (role != null && role.equals(ChatCompletionsCommon.ROLE_ASSISTANT))
          ? ChatCompletionsCommon.ROLE_MODEL
          : role;
    }

    private List<Part> mapMessageToParts(Message message) {
      List<Part> parts = new ArrayList<>();
      if (message.content != null) {
        parts.add(Part.fromText(message.content));
      }
      if (message.refusal != null) {
        parts.add(Part.fromText(message.refusal));
      }
      if (message.toolCalls != null) {
        parts.addAll(mapToolCallsToParts(message.toolCalls));
      }
      return parts;
    }

    private List<Part> mapToolCallsToParts(List<ChatCompletionsCommon.ToolCall> toolCalls) {
      List<Part> parts = new ArrayList<>();
      for (ChatCompletionsCommon.ToolCall toolCall : toolCalls) {
        Part part = toolCall.toPart();
        if (part != null) {
          parts.add(part);
        }
      }
      return parts;
    }

    /**
     * Builds the list of custom metadata from the chat completion fields.
     *
     * @return a list of {@link CustomMetadata}, which will be empty if no relevant fields are set.
     */
    private List<CustomMetadata> buildCustomMetadata() {
      List<CustomMetadata> customMetadataList = new ArrayList<>();
      if (id != null) {
        customMetadataList.add(
            CustomMetadata.builder()
                .key(ChatCompletionsCommon.METADATA_KEY_ID)
                .stringValue(id)
                .build());
      }
      if (created != null) {
        customMetadataList.add(
            CustomMetadata.builder()
                .key(ChatCompletionsCommon.METADATA_KEY_CREATED)
                .stringValue(created.toString())
                .build());
      }
      if (object != null) {
        customMetadataList.add(
            CustomMetadata.builder()
                .key(ChatCompletionsCommon.METADATA_KEY_OBJECT)
                .stringValue(object)
                .build());
      }
      if (systemFingerprint != null) {
        customMetadataList.add(
            CustomMetadata.builder()
                .key(ChatCompletionsCommon.METADATA_KEY_SYSTEM_FINGERPRINT)
                .stringValue(systemFingerprint)
                .build());
      }
      if (serviceTier != null) {
        customMetadataList.add(
            CustomMetadata.builder()
                .key(ChatCompletionsCommon.METADATA_KEY_SERVICE_TIER)
                .stringValue(serviceTier)
                .build());
      }
      return customMetadataList;
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion%20%3E%20(schema)%20%3E%20(property)%20choices
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Choice {
    /** See class definition for more details. */
    @JsonProperty("finish_reason")
    public String finishReason;

    /** See class definition for more details. */
    public Integer index;

    /** See class definition for more details. */
    public Logprobs logprobs;

    /** See class definition for more details. */
    public Message message;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_chunk%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ChatCompletionChunk {
    /** See class definition for more details. */
    public String id;

    /** See class definition for more details. */
    public List<ChunkChoice> choices;

    /** See class definition for more details. */
    public Long created;

    /** See class definition for more details. */
    public String model;

    /** See class definition for more details. */
    public String object;

    /** See class definition for more details. */
    @JsonProperty("service_tier")
    public String serviceTier;

    /** Deprecated. See class definition for more details. */
    @JsonProperty("system_fingerprint")
    public String systemFingerprint;

    /** See class definition for more details. */
    public Usage usage;
  }

  /**
   * Used for streaming responses. See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_chunk%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ChunkChoice {
    /** See class definition for more details. */
    @JsonProperty("finish_reason")
    public String finishReason;

    /** See class definition for more details. */
    public Integer index;

    /** See class definition for more details. */
    public Logprobs logprobs;

    /** See class definition for more details. */
    public Message delta;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_message%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Message {
    /** See class definition for more details. */
    public String content;

    /** See class definition for more details. */
    public String refusal;

    /** See class definition for more details. */
    public String role;

    /** See class definition for more details. */
    @JsonProperty("tool_calls")
    public List<ChatCompletionsCommon.ToolCall> toolCalls;

    /** Deprecated. Use tool_calls instead. See class definition for more details. */
    @JsonProperty("function_call")
    public ChatCompletionsCommon.Function functionCall;

    /** See class definition for more details. */
    public List<Annotation> annotations;

    /** See class definition for more details. */
    public Audio audio;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_logprobs%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Logprobs {
    /** See class definition for more details. */
    public List<TokenLogprob> content;

    /** See class definition for more details. */
    public List<TokenLogprob> refusal;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_token_logprob%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class TokenLogprob {
    /** See class definition for more details. */
    public String token;

    /** See class definition for more details. */
    public List<Integer> bytes;

    /** See class definition for more details. */
    public Double logprob;

    /** See class definition for more details. */
    @JsonProperty("top_logprobs")
    public List<TokenLogprob> topLogprobs;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/completions#(resource)%20completions%20%3E%20(model)%20completion_usage%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Usage {
    /** See class definition for more details. */
    @JsonProperty("completion_tokens")
    public Integer completionTokens;

    /** See class definition for more details. */
    @JsonProperty("prompt_tokens")
    public Integer promptTokens;

    /** See class definition for more details. */
    @JsonProperty("total_tokens")
    public Integer totalTokens;

    /** See class definition for more details. */
    @JsonProperty("thoughts_token_count")
    public Integer thoughtsTokenCount;

    /** See class definition for more details. */
    @JsonProperty("completion_tokens_details")
    public CompletionTokensDetails completionTokensDetails;

    /** See class definition for more details. */
    @JsonProperty("prompt_tokens_details")
    public PromptTokensDetails promptTokensDetails;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/completions#(resource)%20completions%20%3E%20(model)%20completion_usage%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class CompletionTokensDetails {
    /** See class definition for more details. */
    @JsonProperty("accepted_prediction_tokens")
    public Integer acceptedPredictionTokens;

    /** See class definition for more details. */
    @JsonProperty("audio_tokens")
    public Integer audioTokens;

    /** See class definition for more details. */
    @JsonProperty("reasoning_tokens")
    public Integer reasoningTokens;

    /** See class definition for more details. */
    @JsonProperty("rejected_prediction_tokens")
    public Integer rejectedPredictionTokens;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/completions#(resource)%20completions%20%3E%20(model)%20completion_usage%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class PromptTokensDetails {
    /** See class definition for more details. */
    @JsonProperty("audio_tokens")
    public Integer audioTokens;

    /** See class definition for more details. */
    @JsonProperty("cached_tokens")
    public Integer cachedTokens;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_message%20%3E%20(schema)%20%3E%20(property)%20annotations
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Annotation {
    /** See class definition for more details. */
    public String type;

    /** See class definition for more details. */
    @JsonProperty("url_citation")
    public UrlCitation urlCitation;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_message%20%3E%20(schema)%20%3E%20(property)%20annotations%20%3E%20(items)%20%3E%20(property)%20url_citation
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class UrlCitation {
    /** See class definition for more details. */
    @JsonProperty("end_index")
    public Integer endIndex;

    /** See class definition for more details. */
    @JsonProperty("start_index")
    public Integer startIndex;

    /** See class definition for more details. */
    public String title;

    /** See class definition for more details. */
    public String url;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_audio%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Audio {
    /** See class definition for more details. */
    public String id;

    /** See class definition for more details. */
    public String data;

    /** See class definition for more details. */
    @JsonProperty("expires_at")
    public Long expiresAt;

    /** See class definition for more details. */
    public String transcript;
  }
}
