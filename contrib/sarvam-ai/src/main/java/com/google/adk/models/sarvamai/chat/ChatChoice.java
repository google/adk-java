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

/**
 * A choice in the Sarvam AI chat completion response. Handles both non-streaming ({@code message})
 * and streaming ({@code delta}) response formats.
 *
 * @author Sandeep Belgavi
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChatChoice {

  @JsonProperty("index")
  private int index;

  @JsonProperty("message")
  private ChatMessage message;

  @JsonProperty("delta")
  private ChatMessage delta;

  @JsonProperty("finish_reason")
  private String finishReason;

  public int getIndex() {
    return index;
  }

  public void setIndex(int index) {
    this.index = index;
  }

  public ChatMessage getMessage() {
    return message;
  }

  public void setMessage(ChatMessage message) {
    this.message = message;
  }

  public ChatMessage getDelta() {
    return delta;
  }

  public void setDelta(ChatMessage delta) {
    this.delta = delta;
  }

  public String getFinishReason() {
    return finishReason;
  }

  public void setFinishReason(String finishReason) {
    this.finishReason = finishReason;
  }

  /** Returns the effective message content, preferring delta for streaming responses. */
  public ChatMessage effectiveMessage() {
    return delta != null ? delta : message;
  }
}
