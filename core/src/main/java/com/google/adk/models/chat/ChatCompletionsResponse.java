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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FinishReason.Known;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Transfer Objects for Chat Completion and Chat Completion Chunk API responses.
 *
 * <p>See https://developers.openai.com/api/reference/resources/chat
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChatCompletionsResponse {

  private ChatCompletionsResponse() {}

  static @Nullable FinishReason mapFinishReason(@Nullable String reason) {
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

  static @Nullable GenerateContentResponseUsageMetadata mapUsage(@Nullable Usage usage) {
    if (usage == null) {
      return null;
    }
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
   * Maps the chat role string to the model role string.
   *
   * @param role the chat role string, or {@code null}.
   * @return the model role string, or the input role if it doesn't match the assistant role.
   */
  static @Nullable String mapRole(@Nullable String role) {
    if (role == null) {
      return null;
    }
    return role.equals(ChatCompletionsCommon.ROLE_ASSISTANT)
        ? ChatCompletionsCommon.ROLE_MODEL
        : role;
  }

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

      ImmutableList<CustomMetadata> customMetadataList = buildCustomMetadata();
      return builder.customMetadata(customMetadataList).build();
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

    private ImmutableList<Part> mapMessageToParts(Message message) {
      ImmutableList.Builder<Part> parts = ImmutableList.builder();
      if (message.content != null) {
        parts.add(Part.fromText(message.content));
      }
      if (message.refusal != null) {
        parts.add(Part.fromText(message.refusal));
      }
      if (message.toolCalls != null) {
        parts.addAll(mapToolCallsToParts(message.toolCalls));
      }
      return parts.build();
    }

    /**
     * Maps a list of tool calls to a list of {@link Part} objects.
     *
     * @param toolCalls the list of tool calls to map (non-null).
     * @return a list of parts containing converted tool calls.
     */
    private ImmutableList<Part> mapToolCallsToParts(
        List<ChatCompletionsCommon.ToolCall> toolCalls) {

      ImmutableList.Builder<Part> parts = ImmutableList.builder();
      for (ChatCompletionsCommon.ToolCall toolCall : toolCalls) {
        Part part = toolCall.toPart();
        if (part != null) {
          parts.add(part);
        }
      }
      return parts.build();
    }

    /**
     * Builds the list of custom metadata from the chat completion fields.
     *
     * @return a list of {@link CustomMetadata}, which will be empty if no relevant fields are set.
     */
    private ImmutableList<CustomMetadata> buildCustomMetadata() {
      ImmutableList.Builder<CustomMetadata> customMetadataList = ImmutableList.builder();
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
      return customMetadataList.build();
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

  /** Accumulates chunks into a final response. */
  static class ChatCompletionChunkCollection {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger =
        LoggerFactory.getLogger(ChatCompletionChunkCollection.class);

    private final StringBuilder contentParts = new StringBuilder();
    private final Map<Integer, Part> toolCallParts = new TreeMap<>();
    private final Map<Integer, StringBuilder> toolCallArgsAccumulator = new HashMap<>();
    private String role = "";
    private String model = "";
    private Usage usage;
    private final Map<String, String> customMetadataMap = new HashMap<>();

    private ImmutableList<CustomMetadata> getCustomMetadataList() {
      ImmutableList.Builder<CustomMetadata> list = ImmutableList.builder();
      for (Entry<String, String> entry : customMetadataMap.entrySet()) {
        list.add(
            CustomMetadata.builder().key(entry.getKey()).stringValue(entry.getValue()).build());
      }
      return list.build();
    }

    /**
     * Processes a single chunk of a chat completion response.
     *
     * @param chunk the chunk to process, or {@code null}.
     * @return a list of {@link LlmResponse} objects generated from this chunk.
     */
    public ImmutableList<LlmResponse> processChunk(ChatCompletionChunk chunk) {
      if (chunk == null) {
        return ImmutableList.of();
      }

      updateState(chunk);

      ImmutableList.Builder<LlmResponse> responses = ImmutableList.builder();
      if (chunk.choices == null || chunk.choices.isEmpty()) {
        addGenericResponseIfSet(responses);
        return responses.build();
      }

      // The ADK only supports n=1 choices. If more than 1 choice is returned, all choices
      // after the first will be dropped.
      if (chunk.choices.size() > 1) {
        logger.error(
            "Multiple choices found in streaming response but only the first one will be used.");
      }
      ChunkChoice choice = chunk.choices.get(0);

      ImmutableList<Part> chunkParts = mapDeltaToParts(choice);

      responses.add(buildPartialResponse(chunkParts));

      if (choice.finishReason != null && !choice.finishReason.isEmpty()) {
        responses.add(buildFinalResponse(choice));
      }

      return responses.build();
    }

    /**
     * Updates the internal state (model, usage, metadata) from the chunk.
     *
     * @param chunk the chunk to read from.
     */
    private void updateState(ChatCompletionChunk chunk) {
      if (chunk.model != null) {
        this.model = chunk.model;
      }
      if (chunk.usage != null) {
        this.usage = chunk.usage;
      }

      if (chunk.id != null) {
        customMetadataMap.put(ChatCompletionsCommon.METADATA_KEY_ID, chunk.id);
      }
      if (chunk.created != null) {
        customMetadataMap.put(ChatCompletionsCommon.METADATA_KEY_CREATED, chunk.created.toString());
      }
      if (chunk.object != null) {
        customMetadataMap.put(ChatCompletionsCommon.METADATA_KEY_OBJECT, chunk.object);
      }
      if (chunk.systemFingerprint != null) {
        customMetadataMap.put(
            ChatCompletionsCommon.METADATA_KEY_SYSTEM_FINGERPRINT, chunk.systemFingerprint);
      }
      if (chunk.serviceTier != null) {
        customMetadataMap.put(ChatCompletionsCommon.METADATA_KEY_SERVICE_TIER, chunk.serviceTier);
      }
    }

    /**
     * Adds a generic response to the list if usage or metadata is set but choices are empty.
     *
     * @param responses the list to add to.
     */
    private void addGenericResponseIfSet(ImmutableList.Builder<LlmResponse> responses) {
      if (this.usage != null || !customMetadataMap.isEmpty()) {
        responses.add(
            LlmResponse.builder()
                .partial(true)
                .modelVersion(this.model)
                .usageMetadata(mapUsage(this.usage))
                .customMetadata(getCustomMetadataList())
                .build());
      }
    }

    /**
     * Maps the choice's delta to a list of parts and updates state.
     *
     * @param choice the choice to map.
     * @return a list of {@link Part}s for this chunk.
     */
    private ImmutableList<Part> mapDeltaToParts(ChunkChoice choice) {
      ImmutableList.Builder<Part> chunkParts = ImmutableList.builder();
      if (choice.delta != null) {
        updateRole(choice.delta.role);
        appendContent(choice.delta.content, chunkParts);
        appendRefusal(choice.delta.refusal, chunkParts);
        appendToolCalls(choice.delta.toolCalls, chunkParts);
      }
      return chunkParts.build();
    }

    /**
     * Updates the accumulated role if the delta contains a valid role.
     *
     * @param deltaRole the role string from the delta, or {@code null}.
     */
    private void updateRole(@Nullable String deltaRole) {
      if (deltaRole != null && !deltaRole.isEmpty()) {
        String mapped = ChatCompletionsResponse.mapRole(deltaRole);
        if (mapped != null) {
          this.role = mapped;
        }
      }
    }

    /**
     * Appends content to the accumulator and adds it to the chunk parts.
     *
     * @param content the content string, or {@code null}.
     * @param chunkParts the list of parts for this chunk.
     */
    private void appendContent(@Nullable String content, ImmutableList.Builder<Part> chunkParts) {
      if (content != null && !content.isEmpty()) {
        contentParts.append(content);
        chunkParts.add(Part.fromText(content));
      }
    }

    /**
     * Appends refusal to the accumulator and adds it to the chunk parts.
     *
     * @param refusal the refusal string, or {@code null}.
     * @param chunkParts the list of parts for this chunk.
     */
    private void appendRefusal(@Nullable String refusal, ImmutableList.Builder<Part> chunkParts) {
      if (refusal != null && !refusal.isEmpty()) {
        if (contentParts.length() > 0) {
          contentParts.append("\n");
        }
        contentParts.append(refusal);
        chunkParts.add(Part.fromText(refusal));
      }
    }

    /**
     * Appends tool calls to the accumulator and adds them to the chunk parts.
     *
     * @param toolCalls the list of tool calls, or {@code null}.
     * @param chunkParts the list of parts for this chunk.
     */
    private void appendToolCalls(
        @Nullable List<ChatCompletionsCommon.ToolCall> toolCalls,
        ImmutableList.Builder<Part> chunkParts) {
      if (toolCalls != null) {
        for (ChatCompletionsCommon.ToolCall toolCall : toolCalls) {
          Part p = upsertToolCall(toolCall);
          if (p != null) {
            chunkParts.add(p);
          }
        }
      }
    }

    /**
     * Builds a partial {@link LlmResponse} for the current chunk parts.
     *
     * @param chunkParts the parts for this chunk.
     * @return the partial response.
     */
    private LlmResponse buildPartialResponse(List<Part> chunkParts) {
      return LlmResponse.builder()
          .partial(true)
          .content(Content.builder().role(this.role).parts(chunkParts).build())
          .modelVersion(this.model)
          .usageMetadata(mapUsage(this.usage))
          .customMetadata(getCustomMetadataList())
          .build();
    }

    /**
     * Builds the final {@link LlmResponse} with all accumulated content.
     *
     * @param choice the choice containing the finish reason.
     * @return the final response.
     */
    private LlmResponse buildFinalResponse(ChunkChoice choice) {
      return LlmResponse.builder()
          .content(Content.builder().role(this.role).parts(getContentParts()).build())
          .finishReason(ChatCompletionsResponse.mapFinishReason(choice.finishReason))
          .modelVersion(this.model)
          .usageMetadata(mapUsage(this.usage))
          .customMetadata(getCustomMetadataList())
          .build();
    }

    /**
     * Upserts a tool call from a chunk into the collection and returns the part for this chunk.
     *
     * @param toolCall the tool call from the chunk.
     * @return the {@link Part} to emit for this chunk, or {@code null} if it cannot be converted.
     */
    private Part upsertToolCall(ChatCompletionsCommon.ToolCall toolCall) {
      int index = toolCall.index != null ? toolCall.index : toolCallParts.size();

      initializeToolCallState(index);
      updateAccumulatedToolCall(index, toolCall);

      return buildChunkToolCallPart(toolCall);
    }

    /**
     * Initializes the state for a new tool call index if it doesn't exist.
     *
     * @param index the index of the tool call.
     */
    private void initializeToolCallState(int index) {
      if (!toolCallParts.containsKey(index)) {
        toolCallParts.put(
            index, Part.builder().functionCall(FunctionCall.builder().build()).build());
        toolCallArgsAccumulator.put(index, new StringBuilder());
      }
    }

    /**
     * Updates the accumulated tool call state with data from the chunk.
     *
     * @param index the index of the tool call.
     * @param toolCall the tool call from the chunk.
     */
    private void updateAccumulatedToolCall(int index, ChatCompletionsCommon.ToolCall toolCall) {
      Part part = toolCallParts.get(index);
      FunctionCall.Builder fcBuilder =
          part.functionCall().isPresent()
              ? part.functionCall().get().toBuilder()
              : FunctionCall.builder();

      if (toolCall.id != null) {
        fcBuilder.id(toolCall.id);
      }

      appendFunctionDetails(fcBuilder, toolCall.function, index);

      part = toolCall.applyThoughtSignature(part);
      Part updatedPart = part.toBuilder().functionCall(fcBuilder.build()).build();
      toolCallParts.put(index, updatedPart);
    }

    private void appendFunctionDetails(
        FunctionCall.Builder fcBuilder, ChatCompletionsCommon.Function function, int index) {
      if (function == null) {
        return;
      }
      if (function.name != null) {
        fcBuilder.name(function.name);
      }
      if (function.arguments != null) {
        toolCallArgsAccumulator.get(index).append(function.arguments);
      }
    }

    /**
     * Builds the {@link Part} for the current chunk's tool call.
     *
     * @param toolCall the tool call from the chunk.
     * @return the {@link Part} for this chunk.
     */
    private Part buildChunkToolCallPart(ChatCompletionsCommon.ToolCall toolCall) {
      Part chunkPart = toolCall.toPart();
      if (chunkPart == null) {
        FunctionCall.Builder chunkFcBuilder = FunctionCall.builder();
        if (toolCall.id != null) {
          chunkFcBuilder.id(toolCall.id);
        }
        chunkPart = Part.builder().functionCall(chunkFcBuilder.build()).build();
        chunkPart = toolCall.applyThoughtSignature(chunkPart);
      }
      return chunkPart;
    }

    private ImmutableList<Part> getContentParts() {
      ImmutableList.Builder<Part> parts = ImmutableList.builder();
      if (contentParts.length() > 0) {
        parts.add(Part.fromText(contentParts.toString()));
      }

      // If a server sends keys 0 and 2 but not 1 then squash the indices and
      // return parts at indices 0 and 1.
      ImmutableList<Integer> sortedKeys = ImmutableList.sortedCopyOf(toolCallParts.keySet());

      for (int index : sortedKeys) {
        Part part = toolCallParts.get(index);
        if (part != null && part.functionCall().isPresent()) {
          FunctionCall fc = part.functionCall().get();
          StringBuilder argsSb = toolCallArgsAccumulator.get(index);
          if (argsSb != null && argsSb.length() > 0) {
            try {
              Map<String, Object> args =
                  objectMapper.readValue(
                      argsSb.toString(), new TypeReference<Map<String, Object>>() {});
              fc = fc.toBuilder().args(args).build();
              part = part.toBuilder().functionCall(fc).build();
            } catch (JsonProcessingException e) {
              throw new IllegalArgumentException(
                  "Failed to parse final tool call arguments: " + argsSb, e);
            }
          }
        }
        parts.add(part);
      }
      return parts.build();
    }
  }
}
