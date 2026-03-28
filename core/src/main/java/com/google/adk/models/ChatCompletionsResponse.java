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

package com.google.adk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Objects for Chat Completion and Chat Completion Chunk API responses.
 *
 * <p>These classes are used for deserializing JSON responses from the `/chat/completions` endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class ChatCompletionsResponse {

  private ChatCompletionsResponse() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ChatCompletion {
    public String id;
    public List<Choice> choices;
    public Long created;
    public String model;
    public String object;

    @JsonProperty("service_tier")
    public String serviceTier;

    @JsonProperty("system_fingerprint")
    public String systemFingerprint;

    public Usage usage;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Choice {
    @JsonProperty("finish_reason")
    public String finishReason;

    public Integer index;
    public Logprobs logprobs;
    public Message message;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ChatCompletionChunk {
    public String id;
    public List<ChunkChoice> choices;
    public Long created;
    public String model;
    public String object;

    @JsonProperty("service_tier")
    public String serviceTier;

    @JsonProperty("system_fingerprint")
    public String systemFingerprint;

    public Usage usage;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ChunkChoice {
    @JsonProperty("finish_reason")
    public String finishReason;

    public Integer index;
    public Logprobs logprobs;
    public Message delta;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Message {
    public String content;
    public String refusal;
    public String role;

    @JsonProperty("tool_calls")
    public List<ToolCall> toolCalls;

    // function_call is not supported in ChatCompletionChunk and ChatCompletion support is
    // deprecated.
    @JsonProperty("function_call")
    public Function functionCall; // Fallback for deprecated top-level function calls

    public List<Annotation> annotations;
    public Audio audio;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ToolCall {
    // Index is only used in ChatCompletionChunk.
    public Integer index;
    public String id;
    public String type;
    public Function function;
    public Custom custom;

    @JsonProperty("extra_content")
    public Map<String, Object> extraContent;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Function {
    public String name;
    public String arguments; // JSON string
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Custom {
    public String input;
    public String name;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Logprobs {
    public List<TokenLogprob> content;
    public List<TokenLogprob> refusal;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class TokenLogprob {
    public String token;
    public List<Integer> bytes;
    public Double logprob;

    @JsonProperty("top_logprobs")
    public List<TokenLogprob> topLogprobs;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Usage {
    @JsonProperty("completion_tokens")
    public Integer completionTokens;

    @JsonProperty("prompt_tokens")
    public Integer promptTokens;

    @JsonProperty("total_tokens")
    public Integer totalTokens;

    @JsonProperty("thoughts_token_count")
    public Integer thoughtsTokenCount; // Gemini-specific extension

    @JsonProperty("completion_tokens_details")
    public CompletionTokensDetails completionTokensDetails;

    @JsonProperty("prompt_tokens_details")
    public PromptTokensDetails promptTokensDetails;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class CompletionTokensDetails {
    @JsonProperty("accepted_prediction_tokens")
    public Integer acceptedPredictionTokens;

    @JsonProperty("audio_tokens")
    public Integer audioTokens;

    @JsonProperty("reasoning_tokens")
    public Integer reasoningTokens;

    @JsonProperty("rejected_prediction_tokens")
    public Integer rejectedPredictionTokens;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class PromptTokensDetails {
    @JsonProperty("audio_tokens")
    public Integer audioTokens;

    @JsonProperty("cached_tokens")
    public Integer cachedTokens;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Annotation {
    public String type;

    @JsonProperty("url_citation")
    public UrlCitation urlCitation;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class UrlCitation {
    @JsonProperty("end_index")
    public Integer endIndex;

    @JsonProperty("start_index")
    public Integer startIndex;

    public String title;
    public String url;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class Audio {
    public String id;
    public String data;

    @JsonProperty("expires_at")
    public Long expiresAt;

    public String transcript;
  }
}
