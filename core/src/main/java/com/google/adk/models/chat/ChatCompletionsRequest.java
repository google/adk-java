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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Objects for Chat Completion API requests.
 *
 * <p>See
 * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
final class ChatCompletionsRequest {

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20messages%20%3E%20(schema)
   */
  public List<Message> messages;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20model%20%3E%20(schema)
   */
  public String model;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20audio%20%3E%20(schema)
   */
  public AudioParam audio;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20frequency_penalty%20%3E%20(schema)
   */
  @JsonProperty("frequency_penalty")
  public Double frequencyPenalty;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20logit_bias%20%3E%20(schema)
   */
  @JsonProperty("logit_bias")
  public Map<String, Double> logitBias;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20logprobs%20%3E%20(schema)
   */
  public Boolean logprobs;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20max_completion_tokens%20%3E%20(schema)
   */
  @JsonProperty("max_completion_tokens")
  public Integer maxCompletionTokens;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20metadata%20%3E%20(schema)
   */
  public Map<String, String> metadata;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20modalities%20%3E%20(schema)
   */
  public List<String> modalities;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20n%20%3E%20(schema)
   */
  public Integer n;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20parallel_tool_calls%20%3E%20(schema)
   */
  @JsonProperty("parallel_tool_calls")
  public Boolean parallelToolCalls;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20prediction%20%3E%20(schema)
   */
  public Prediction prediction;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20presence_penalty%20%3E%20(schema)
   */
  @JsonProperty("presence_penalty")
  public Double presencePenalty;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20prompt_cache_key%20%3E%20(schema)
   */
  @JsonProperty("prompt_cache_key")
  public String promptCacheKey;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20prompt_cache_retention%20%3E%20(schema)
   */
  @JsonProperty("prompt_cache_retention")
  public String promptCacheRetention;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20reasoning_effort%20%3E%20(schema)
   */
  @JsonProperty("reasoning_effort")
  public String reasoningEffort;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20response_format%20%3E%20(schema)
   */
  @JsonProperty("response_format")
  public ResponseFormat responseFormat;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20safety_identifier%20%3E%20(schema)
   */
  @JsonProperty("safety_identifier")
  public String safetyIdentifier;

  /**
   * Deprecated. Use temperature instead. See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20seed%20%3E%20(schema)
   */
  public Long seed;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20service_tier%20%3E%20(schema)
   */
  @JsonProperty("service_tier")
  public String serviceTier;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20stop%20%3E%20(schema)
   */
  public StopCondition stop;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20store%20%3E%20(schema)
   */
  public Boolean store;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20stream%20%3E%20(schema)
   */
  public Boolean stream;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20stream_options%20%3E%20(schema)
   */
  @JsonProperty("stream_options")
  public StreamOptions streamOptions;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20temperature%20%3E%20(schema)
   */
  public Double temperature;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
   */
  @JsonProperty("tool_choice")
  public ToolChoice toolChoice;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tools%20%3E%20(schema)
   */
  public List<Tool> tools;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20top_logprobs%20%3E%20(schema)
   */
  @JsonProperty("top_logprobs")
  public Integer topLogprobs;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20top_p%20%3E%20(schema)
   */
  @JsonProperty("top_p")
  public Double topP;

  /**
   * Deprecated, use safety_identifier and prompt_cache_key instead. See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20user%20%3E%20(schema)
   */
  public String user;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20verbosity%20%3E%20(schema)
   */
  public String verbosity;

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20web_search_options%20%3E%20(schema)
   */
  @JsonProperty("web_search_options")
  public WebSearchOptions webSearchOptions;

  /**
   * Additional body parameters used for specific models, for example:
   * https://ai.google.dev/gemini-api/docs/openai#extra-body
   */
  @JsonProperty("extra_body")
  public Map<String, Object> extraBody;

  /**
   * A catch-all class for message parameters. See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20messages%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class Message {
    /** See class definition for more details. */
    public String role;

    /** See class definition for more details. */
    public MessageContent content;

    /** See class definition for more details. */
    public String name;

    /** See class definition for more details. */
    @JsonProperty("tool_calls")
    public List<ChatCompletionsCommon.ToolCall> toolCalls;

    /** Deprecated. Use tool_calls instead.See class definition for more details. */
    @JsonProperty("function_call")
    public FunctionCall functionCall;

    /** See class definition for more details. */
    @JsonProperty("tool_call_id")
    public String toolCallId;

    /** See class definition for more details. */
    public Audio audio;

    /** See class definition for more details. */
    public String refusal;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_content_part_text%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class ContentPart {
    /** See class definition for more details. */
    public String type;

    /** See class definition for more details. */
    public String text;

    /** See class definition for more details. */
    public String refusal;

    /** See class definition for more details. */
    @JsonProperty("image_url")
    public ImageUrl imageUrl;

    /** See class definition for more details. */
    @JsonProperty("input_audio")
    public InputAudio inputAudio;

    /** See class definition for more details. */
    public File file;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_content_part_text%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class ImageUrl {
    /** See class definition for more details. */
    public String url;

    /** See class definition for more details. */
    public String detail;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_content_part_text%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class InputAudio {
    /** See class definition for more details. */
    public String data;

    /** See class definition for more details. */
    public String format;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20messages%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class File {
    /** See class definition for more details. */
    @JsonProperty("file_data")
    public String fileData;

    /** See class definition for more details. */
    @JsonProperty("file_id")
    public String fileId;

    /** See class definition for more details. */
    public String filename;
  }

  /**
   * Deprecated. Function call details replaced by tool_calls. See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20messages%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class FunctionCall {
    /** See class definition for more details. */
    public String name;

    /** See class definition for more details. */
    public String arguments;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20audio%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class AudioParam {
    /** See class definition for more details. */
    public String format;

    /** See class definition for more details. */
    public VoiceConfig voice;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20audio%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class Audio {
    /** See class definition for more details. */
    public String id;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20prediction%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class Prediction {
    /** See class definition for more details. */
    public String type;

    /** See class definition for more details. */
    public Object content;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20stream_options%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class StreamOptions {
    /** See class definition for more details. */
    @JsonProperty("include_obfuscation")
    public Boolean includeObfuscation;

    /** See class definition for more details. */
    @JsonProperty("include_usage")
    public Boolean includeUsage;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tools%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class Tool {
    /** See class definition for more details. */
    public String type;

    /** See class definition for more details. */
    public FunctionDefinition function;

    /** See class definition for more details. */
    public CustomTool custom;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tools%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class FunctionDefinition {
    /** See class definition for more details. */
    public String name;

    /** See class definition for more details. */
    public String description;

    /** See class definition for more details. */
    public Map<String, Object> parameters;

    /** See class definition for more details. */
    public Boolean strict;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_custom_tool%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class CustomTool {
    /** See class definition for more details. */
    public String name;

    /** See class definition for more details. */
    public String description;

    /** See class definition for more details. */
    public Object format;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20web_search_options%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class WebSearchOptions {
    /** See class definition for more details. */
    @JsonProperty("search_context_size")
    public String searchContextSize;

    /** See class definition for more details. */
    @JsonProperty("user_location")
    public UserLocation userLocation;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20web_search_options%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class UserLocation {
    /** See class definition for more details. */
    public String type;

    /** See class definition for more details. */
    public ApproximateLocation approximate;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20web_search_options%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class ApproximateLocation {
    /** See class definition for more details. */
    public String city;

    /** See class definition for more details. */
    public String country;

    /** See class definition for more details. */
    public String region;

    /** See class definition for more details. */
    public String timezone;
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20response_format%20%3E%20(schema)
   */
  interface ResponseFormat {}

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20response_format%20%3E%20(schema)
   */
  static class ResponseFormatText implements ResponseFormat {
    public String type = "text";
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20response_format%20%3E%20(schema)
   */
  static class ResponseFormatJsonObject implements ResponseFormat {
    public String type = "json_object";
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20response_format%20%3E%20(schema)
   */
  static class ResponseFormatJsonSchema implements ResponseFormat {
    public String type = "json_schema";

    @JsonProperty("json_schema")
    public JsonSchema jsonSchema;

    /**
     * See
     * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20response_format%20%3E%20(schema)
     */
    static class JsonSchema {
      /** See class definition for more details. */
      public String name;

      /** See class definition for more details. */
      public String description;

      /** See class definition for more details. */
      public Map<String, Object> schema;

      /** See class definition for more details. */
      public Boolean strict;
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
   */
  interface ToolChoice {}

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
   */
  static class ToolChoiceMode implements ToolChoice {
    private final String mode;

    public ToolChoiceMode(String mode) {
      this.mode = mode;
    }

    @JsonValue
    public String getMode() {
      return mode;
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
   */
  static class NamedToolChoice implements ToolChoice {
    /** See class definition for more details. */
    public String type = "function";

    /** See class definition for more details. */
    public FunctionName function;

    /**
     * See
     * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
     */
    static class FunctionName {
      /** See class definition for more details. */
      public String name;
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
   */
  static class NamedToolChoiceCustom implements ToolChoice {
    /** See class definition for more details. */
    public String type = "custom";

    /** See class definition for more details. */
    public CustomName custom;

    /**
     * See
     * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20tool_choice%20%3E%20(schema)
     */
    static class CustomName {
      /** See class definition for more details. */
      public String name;
    }
  }

  /**
   * Wrapper class for stop. See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20stop%20%3E%20(schema)
   */
  static class StopCondition {
    private final String stringValue;
    private final List<String> listValue;

    @JsonCreator
    public StopCondition(String stringValue) {
      this.stringValue = stringValue;
      this.listValue = null;
    }

    @JsonCreator
    public StopCondition(List<String> listValue) {
      this.stringValue = null;
      this.listValue = listValue;
    }

    @JsonValue
    public Object getValue() {
      return stringValue != null ? stringValue : listValue;
    }
  }

  /**
   * Wrapper class for messages. See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20messages%20%3E%20(schema)
   */
  static class MessageContent {
    private final String stringValue;
    private final List<ContentPart> listValue;

    @JsonCreator
    public MessageContent(String stringValue) {
      this.stringValue = stringValue;
      this.listValue = null;
    }

    @JsonCreator
    public MessageContent(List<ContentPart> listValue) {
      this.stringValue = null;
      this.listValue = listValue;
    }

    @JsonValue
    public Object getValue() {
      return stringValue != null ? stringValue : listValue;
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create#(resource)%20chat.completions%20%3E%20(method)%20create%20%3E%20(params)%200.non_streaming%20%3E%20(param)%20audio%20%3E%20(schema)
   */
  static class VoiceConfig {
    private final String stringValue;
    private final Map<String, Object> mapValue;

    @JsonCreator
    public VoiceConfig(String stringValue) {
      this.stringValue = stringValue;
      this.mapValue = null;
    }

    @JsonCreator
    public VoiceConfig(Map<String, Object> mapValue) {
      this.stringValue = null;
      this.mapValue = mapValue;
    }

    @JsonValue
    public Object getValue() {
      return stringValue != null ? stringValue : mapValue;
    }
  }
}
