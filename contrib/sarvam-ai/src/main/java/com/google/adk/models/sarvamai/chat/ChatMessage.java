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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A message in the Sarvam AI chat completion API (request or response).
 *
 * @author Sandeep Belgavi
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatMessage {

  @JsonProperty("role")
  private String role;

  @JsonProperty("content")
  private String content;

  @JsonProperty("reasoning_content")
  private String reasoningContent;

  @JsonProperty("tool_calls")
  private List<ChatToolCall> toolCalls;

  @JsonProperty("tool_call_id")
  private String toolCallId;

  public ChatMessage() {}

  public ChatMessage(String role, String content) {
    this.role = role;
    this.content = content;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getReasoningContent() {
    return reasoningContent;
  }

  public void setReasoningContent(String reasoningContent) {
    this.reasoningContent = reasoningContent;
  }

  public List<ChatToolCall> getToolCalls() {
    return toolCalls;
  }

  public void setToolCalls(List<ChatToolCall> toolCalls) {
    this.toolCalls = toolCalls;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public void setToolCallId(String toolCallId) {
    this.toolCallId = toolCallId;
  }
}
