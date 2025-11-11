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

package com.google.adk.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.genai.types.Content;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Data Transfer Object (DTO) for POST /run and POST /run-sse requests. Contains information needed
 * to execute an agent run.
 */
public class AgentRunRequest {
  @JsonProperty("appName")
  public String appName;

  @JsonProperty("userId")
  public String userId;

  @JsonProperty("sessionId")
  public String sessionId;

  @JsonProperty("newMessage")
  public Content newMessage;

  @JsonProperty("streaming")
  public boolean streaming = false;

  /**
   * Optional state delta to merge into the session state before running the agent. This allows
   * updating session state dynamically per request, useful for injecting configuration (e.g.,
   * replay mode settings) without modifying the stored session.
   */
  @JsonProperty("stateDelta")
  @Nullable
  public Map<String, Object> stateDelta;

  public AgentRunRequest() {}

  public String getAppName() {
    return appName;
  }

  public String getUserId() {
    return userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public Content getNewMessage() {
    return newMessage;
  }

  public boolean getStreaming() {
    return streaming;
  }

  @Nullable
  public Map<String, Object> getStateDelta() {
    return stateDelta;
  }
}
