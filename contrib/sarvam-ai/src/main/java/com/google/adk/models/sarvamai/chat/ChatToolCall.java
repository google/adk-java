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

package com.google.adk.models.sarvamai.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI-format tool call (id, type, function with name and arguments). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChatToolCall {

  @JsonProperty("id")
  private String id;

  @JsonProperty("type")
  private String type;

  @JsonProperty("function")
  private ChatToolCallFunction function;

  public ChatToolCall() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public ChatToolCallFunction getFunction() {
    return function;
  }

  public void setFunction(ChatToolCallFunction function) {
    this.function = function;
  }

  /** Inner function object (name, arguments). */
  public static final class ChatToolCallFunction {
    @JsonProperty("name")
    private String name;

    @JsonProperty("arguments")
    private String arguments;

    public ChatToolCallFunction() {}

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getArguments() {
      return arguments;
    }

    public void setArguments(String arguments) {
      this.arguments = arguments;
    }
  }
}
