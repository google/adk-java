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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Transfer Objects for Chat Completion API requests.
 *
 * <p>Can be used to translate from a {@link LlmRequest} into a {@link ChatCompletionsRequest} using
 * {@link #fromLlmRequest(LlmRequest, boolean)}.
 *
 * <p>See
 * https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatCompletionsRequest {

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

  private static final Logger logger = LoggerFactory.getLogger(ChatCompletionsRequest.class);
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  /**
   * Converts a standard {@link LlmRequest} into a {@link ChatCompletionsRequest} for
   * /chat/completions compatible endpoints.
   *
   * @param llmRequest The internal source request containing contents, configuration, and tool
   *     definitions.
   * @param responseStreaming True if the request asks for a streaming response.
   * @return A populated ChatCompletionsRequest ready for JSON serialization.
   */
  public static ChatCompletionsRequest fromLlmRequest(
      LlmRequest llmRequest, boolean responseStreaming) {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = llmRequest.model().orElse("");
    request.stream = responseStreaming;
    if (responseStreaming) {
      StreamOptions options = new StreamOptions();
      options.includeUsage = true;
      request.streamOptions = options;
    }

    boolean isOSeries = request.model.matches("^o\\d+(?:-.*)?$");

    List<Message> messages = new ArrayList<>();

    llmRequest
        .config()
        .flatMap(config -> processSystemInstruction(config, isOSeries))
        .ifPresent(messages::add);

    for (Content content : llmRequest.contents()) {
      messages.addAll(processContent(content));
    }

    request.messages = ImmutableList.copyOf(messages);

    llmRequest
        .config()
        .ifPresent(
            config -> {
              handleConfigOptions(config, request);
              handleTools(config, request);
            });

    return request;
  }

  /**
   * Processes the system instruction configuration and returns a mapped Message if present.
   *
   * @param config The content generation configuration that may contain a system instruction.
   * @param isOSeries True if the target model belongs to the OpenAI o-series (e.g., o1, o3), which
   *     requires the "developer" role instead of the standard "system" role.
   * @return An Optional containing the mapped instruction, or empty if none exists.
   */
  private static Optional<Message> processSystemInstruction(
      GenerateContentConfig config, boolean isOSeries) {
    if (config.systemInstruction().isPresent()) {
      Message systemMsg = new Message();
      systemMsg.role = isOSeries ? "developer" : "system";
      systemMsg.content = new MessageContent(config.systemInstruction().get().text());
      return Optional.of(systemMsg);
    }
    return Optional.empty();
  }

  /**
   * Processes incoming content and returns a list of messages resulting from it.
   *
   * @param content The incoming content containing parts to map.
   * @return A list of mapped messages.
   */
  private static List<Message> processContent(Content content) {
    Message msg = new Message();
    String role = content.role().orElse("user");
    msg.role = role.equals("model") ? "assistant" : role;

    List<ContentPart> contentParts = new ArrayList<>();
    List<ChatCompletionsCommon.ToolCall> toolCalls = new ArrayList<>();
    List<Message> toolResponses = new ArrayList<>();

    content
        .parts()
        .ifPresent(
            parts -> {
              for (Part part : parts) {
                if (part.text().isPresent()) {
                  contentParts.add(processTextPart(part));
                } else if (part.inlineData().isPresent()) {
                  contentParts.add(processInlineDataPart(part));
                } else if (part.fileData().isPresent()) {
                  contentParts.add(processFileDataPart(part));
                } else if (part.functionCall().isPresent()) {
                  toolCalls.add(processFunctionCallPart(part));
                } else if (part.functionResponse().isPresent()) {
                  toolResponses.add(processFunctionResponsePart(part));
                } else if (part.executableCode().isPresent()) {
                  logger.warn("Executable code is not supported in Chat Completion conversion");
                } else if (part.codeExecutionResult().isPresent()) {
                  logger.warn(
                      "Code execution result is not supported in Chat Completion conversion");
                }
              }
            });

    if (!toolResponses.isEmpty()) {
      return toolResponses;
    } else {
      if (!toolCalls.isEmpty()) {
        msg.toolCalls = ImmutableList.copyOf(toolCalls);
      }
      if (!contentParts.isEmpty()) {
        if (contentParts.size() == 1 && Objects.equals(contentParts.get(0).type, "text")) {
          msg.content = new MessageContent(contentParts.get(0).text);
        } else {
          msg.content = new MessageContent(ImmutableList.copyOf(contentParts));
        }
      }
      List<Message> messages = new ArrayList<>();
      messages.add(msg);
      return messages;
    }
  }

  /**
   * Processes a text part and returns a mapped ContentPart.
   *
   * @param part The input part containing simple text.
   * @return The mapped text part.
   */
  private static ContentPart processTextPart(Part part) {
    ContentPart textPart = new ContentPart();
    textPart.type = "text";
    textPart.text = part.text().get();
    return textPart;
  }

  /**
   * Processes an inline data part and returns a mapped ContentPart.
   *
   * @param part The input part containing base64 inline data.
   * @return The mapped inline data part.
   */
  private static ContentPart processInlineDataPart(Part part) {
    ContentPart imgPart = new ContentPart();
    imgPart.type = "image_url";
    ImageUrl imageUrl = new ImageUrl();
    imageUrl.url =
        "data:"
            + part.inlineData().get().mimeType().orElse("image/jpeg")
            + ";base64,"
            + Base64.getEncoder().encodeToString(part.inlineData().get().data().get());
    imgPart.imageUrl = imageUrl;
    return imgPart;
  }

  /**
   * Processes a file data part and returns a mapped ContentPart.
   *
   * @param part The input part referencing a stored file via URI.
   * @return The mapped file data part.
   */
  private static ContentPart processFileDataPart(Part part) {
    ContentPart imgPart = new ContentPart();
    imgPart.type = "image_url";
    ImageUrl imageUrl = new ImageUrl();
    imageUrl.url = part.fileData().get().fileUri().orElse("");
    imgPart.imageUrl = imageUrl;
    return imgPart;
  }

  /**
   * Processes a function call part and returns a mapped ToolCall.
   *
   * @param part The input part containing a requested function call or invocation.
   * @return The mapped function call tool call.
   */
  private static ChatCompletionsCommon.ToolCall processFunctionCallPart(Part part) {
    com.google.genai.types.FunctionCall fc = part.functionCall().get();
    ChatCompletionsCommon.ToolCall toolCall = new ChatCompletionsCommon.ToolCall();
    toolCall.id = fc.id().orElse("call_" + fc.name().orElse("unknown"));
    toolCall.type = "function";
    ChatCompletionsCommon.Function function = new ChatCompletionsCommon.Function();
    function.name = fc.name().orElse("");
    if (fc.args().isPresent()) {
      try {
        function.arguments = objectMapper.writeValueAsString(fc.args().get());
      } catch (Exception e) {
        logger.warn("Failed to serialize function arguments", e);
      }
    }
    toolCall.function = function;
    return toolCall;
  }

  /**
   * Processes a function response part and returns a mapped Message.
   *
   * @param part The input part containing the execution results of a function.
   * @return The mapped tool response message.
   */
  private static Message processFunctionResponsePart(Part part) {
    FunctionResponse fr = part.functionResponse().get();
    Message toolResp = new Message();
    toolResp.role = "tool";
    toolResp.toolCallId = fr.id().orElse("");
    if (fr.response().isPresent()) {
      try {
        toolResp.content = new MessageContent(objectMapper.writeValueAsString(fr.response().get()));
      } catch (Exception e) {
        logger.warn("Failed to serialize tool response", e);
      }
    }
    return toolResp;
  }

  /**
   * Updates the request based on the provided configuration options.
   *
   * @param config The content generation configuration containing parameters such as temperature.
   * @param request The chat completions request to populate with matching options.
   */
  private static void handleConfigOptions(
      GenerateContentConfig config, ChatCompletionsRequest request) {
    config.temperature().ifPresent(v -> request.temperature = v.doubleValue());
    config.topP().ifPresent(v -> request.topP = v.doubleValue());
    config
        .maxOutputTokens()
        .ifPresent(
            v -> {
              request.maxCompletionTokens = Math.toIntExact(v);
            });
    config.stopSequences().ifPresent(v -> request.stop = new StopCondition(v));
    config.candidateCount().ifPresent(v -> request.n = Math.toIntExact(v));
    config.presencePenalty().ifPresent(v -> request.presencePenalty = v.doubleValue());
    config.frequencyPenalty().ifPresent(v -> request.frequencyPenalty = v.doubleValue());
    config.seed().ifPresent(v -> request.seed = v.longValue());

    if (config.responseJsonSchema().isPresent()) {
      ResponseFormatJsonSchema format = new ResponseFormatJsonSchema();
      ResponseFormatJsonSchema.JsonSchema schema = new ResponseFormatJsonSchema.JsonSchema();
      schema.name = "response_schema";
      schema.schema =
          objectMapper.convertValue(
              config.responseJsonSchema().get(), new TypeReference<Map<String, Object>>() {});
      schema.strict = true;
      format.jsonSchema = schema;
      request.responseFormat = format;
    } else if (config.responseMimeType().isPresent()
        && config.responseMimeType().get().equals("application/json")) {
      request.responseFormat = new ResponseFormatJsonObject();
    }

    if (config.responseLogprobs().isPresent() && config.responseLogprobs().get()) {
      request.logprobs = true;
      config.logprobs().ifPresent(v -> request.topLogprobs = Math.toIntExact(v));
    }
  }

  /**
   * Updates the request tools list based on the provided tools configuration.
   *
   * @param config The content generation configuration defining available tools.
   * @param request The chat completions request to populate with mapped tool definitions.
   */
  private static void handleTools(GenerateContentConfig config, ChatCompletionsRequest request) {
    if (config.tools().isPresent()) {
      List<Tool> tools = new ArrayList<>();
      for (com.google.genai.types.Tool t : config.tools().get()) {
        if (t.functionDeclarations().isPresent()) {
          for (FunctionDeclaration fd : t.functionDeclarations().get()) {
            Tool tool = new Tool();
            tool.type = "function";
            FunctionDefinition def = new FunctionDefinition();
            def.name = fd.name().orElse("");
            def.description = fd.description().orElse("");
            fd.parameters()
                .ifPresent(
                    params ->
                        def.parameters =
                            objectMapper.convertValue(
                                params, new TypeReference<Map<String, Object>>() {}));
            tool.function = def;
            tools.add(tool);
          }
        }
      }
      if (!tools.isEmpty()) {
        request.tools = ImmutableList.copyOf(tools);
        if (config.toolConfig().isPresent()
            && config.toolConfig().get().functionCallingConfig().isPresent()) {
          config
              .toolConfig()
              .get()
              .functionCallingConfig()
              .get()
              .mode()
              .ifPresent(
                  mode -> {
                    switch (mode.knownEnum()) {
                      case ANY -> request.toolChoice = new ToolChoiceMode("required");
                      case NONE -> request.toolChoice = new ToolChoiceMode("none");
                      case AUTO -> request.toolChoice = new ToolChoiceMode("auto");
                      default -> {}
                    }
                  });
        }
      }
    }
  }

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
