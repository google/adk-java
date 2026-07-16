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
import java.util.List;

/**
 * Response from the Sarvam AI chat completions endpoint. Supports both non-streaming and streaming
 * (SSE chunk) formats.
 *
 * @author Sandeep Belgavi
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChatResponse {

  @JsonProperty("id")
  private String id;

  @JsonProperty("object")
  private String object;

  @JsonProperty("created")
  private long created;

  @JsonProperty("model")
  private String model;

  @JsonProperty("choices")
  private List<ChatChoice> choices;

  @JsonProperty("usage")
  private ChatUsage usage;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getObject() {
    return object;
  }

  public void setObject(String object) {
    this.object = object;
  }

  public long getCreated() {
    return created;
  }

  public void setCreated(long created) {
    this.created = created;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public List<ChatChoice> getChoices() {
    return choices;
  }

  public void setChoices(List<ChatChoice> choices) {
    this.choices = choices;
  }

  public ChatUsage getUsage() {
    return usage;
  }

  public void setUsage(ChatUsage usage) {
    this.usage = usage;
  }
}
