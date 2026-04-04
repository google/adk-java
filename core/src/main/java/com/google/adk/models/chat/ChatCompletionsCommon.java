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
import java.util.Map;

/** Shared models for Chat Completions Request and Response. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
final class ChatCompletionsCommon {

  private ChatCompletionsCommon() {}

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
