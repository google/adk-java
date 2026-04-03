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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.Base64;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Shared models for Chat Completions Request and Response. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
final class ChatCompletionsCommon {

  private ChatCompletionsCommon() {}

  private static final ObjectMapper objectMapper = new ObjectMapper();

  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_MODEL = "model";

  public static final String METADATA_KEY_ID = "id";
  public static final String METADATA_KEY_CREATED = "created";
  public static final String METADATA_KEY_OBJECT = "object";
  public static final String METADATA_KEY_SYSTEM_FINGERPRINT = "system_fingerprint";
  public static final String METADATA_KEY_SERVICE_TIER = "service_tier";

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_message_tool_call%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class ToolCall {
    /** See class definition for more details. */
    public Integer index;

    /** See class definition for more details. */
    public String id;

    /** See class definition for more details. */
    public String type;

    /** See class definition for more details. */
    public Function function;

    /** See class definition for more details. */
    public Custom custom;

    /**
     * Used to supply additional parameters for specific models, for example:
     * https://ai.google.dev/gemini-api/docs/openai#thinking
     */
    @JsonProperty("extra_content")
    public Map<String, Object> extraContent;

    /**
     * Converts the tool call to a {@link Part}.
     *
     * @return a {@link Part} containing the function call, or {@code null} if this tool call does
     *     not contain a function call.
     */
    public @Nullable Part toPart() {
      if (function != null) {
        FunctionCall fc = function.toFunctionCall(id);
        Part part = Part.builder().functionCall(fc).build();
        return applyThoughtSignature(part);
      }
      return null;
    }

    /**
     * Applies the thought signature from {@code extraContent} to the given {@link Part} if present.
     * This is used to support the Google Gemini/Vertex AI implementation of the chat/completions
     * API.
     *
     * @param part the {@link Part} to modify.
     * @return a new {@link Part} with the thought signature applied, or the original {@link Part}
     *     if no thought signature is found.
     */
    public Part applyThoughtSignature(Part part) {
      if (extraContent != null && extraContent.containsKey("google")) {
        Object googleObj = extraContent.get("google");
        if (googleObj instanceof Map<?, ?> googleMap) {
          Object sigObj = googleMap.get("thought_signature");
          if (sigObj instanceof String sig) {
            return part.toBuilder().thoughtSignature(Base64.getDecoder().decode(sig)).build();
          }
        }
      }
      return part;
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_message_function_tool_call%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class Function {
    /** See class definition for more details. */
    public String name;

    /** See class definition for more details. */
    public String arguments; // JSON string

    /**
     * Converts this function to a {@link FunctionCall}.
     *
     * @param toolCallId the ID of the tool call, or {@code null} if not applicable.
     * @return the {@link FunctionCall} object.
     */
    public FunctionCall toFunctionCall(@Nullable String toolCallId) {
      FunctionCall.Builder fcBuilder = FunctionCall.builder();
      if (name != null) {
        fcBuilder.name(name);
      }
      if (arguments != null) {
        try {
          Map<String, Object> args =
              objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
          fcBuilder.args(args);
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Failed to parse function arguments JSON: " + arguments, e);
        }
      }
      if (toolCallId != null) {
        fcBuilder.id(toolCallId);
      }
      return fcBuilder.build();
    }
  }

  /**
   * See
   * https://developers.openai.com/api/reference/resources/chat#(resource)%20chat.completions%20%3E%20(model)%20chat_completion_custom_tool%20%3E%20(schema)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  static class Custom {
    /** See class definition for more details. */
    public String input;

    /** See class definition for more details. */
    public String name;
  }
}
