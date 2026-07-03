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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class AgentRunRequestTest {

  private ObjectMapper objectMapper;

  private static final String BASIC_MESSAGE_JSON =
      """
        "appName": "testApp",
        "userId": "user123",
        "sessionId": "session456",
        "newMessage": {
          "parts": [
            {"text": "hello"}
          ]
        }
      """;

  private AgentRunRequest newRequest() {
    AgentRunRequest request = new AgentRunRequest();
    request.appName = "testApp";
    request.userId = "user123";
    request.sessionId = "session456";
    request.newMessage = Content.builder().parts(Part.builder().text("hello").build()).build();
    return request;
  }

  @BeforeEach
  public void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new Jdk8Module());
  }

  @Test
  public void deserialize_expectedUsage() throws Exception {
    String json = "{ %s }".formatted(BASIC_MESSAGE_JSON);

    AgentRunRequest request = objectMapper.readValue(json, AgentRunRequest.class);

    assertThat(request.appName).isEqualTo("testApp");
    assertThat(request.userId).isEqualTo("user123");
    assertThat(request.sessionId).isEqualTo("session456");
    assertThat(request.stateDelta).isNull();
  }

  @Test
  public void deserialize_withDeltaState() throws Exception {
    String json =
        """
        {
          %s,
          "stateDelta": {
            "key1": "value1",
            "key2": 42,
            "stringVal": "text",
            "intVal": 123,
            "boolVal": true,
            "doubleVal": 45.67,
            "nestedObj": {"inner": "value"}
          }
        }
        """
            .formatted(BASIC_MESSAGE_JSON);

    AgentRunRequest request = objectMapper.readValue(json, AgentRunRequest.class);

    assertThat(request.stateDelta).isNotNull();
    assertThat(request.stateDelta).hasSize(7);
    assertThat(request.stateDelta).containsEntry("key1", "value1");
    assertThat(request.stateDelta).containsEntry("key2", 42);
    assertThat(request.stateDelta).containsEntry("stringVal", "text");
    assertThat(request.stateDelta).containsEntry("intVal", 123);
    assertThat(request.stateDelta).containsEntry("boolVal", true);
    assertThat(request.stateDelta).containsEntry("doubleVal", 45.67);
    assertThat(request.stateDelta).containsKey("nestedObj");
  }

  @Test
  public void serialize_withStateDelta_success() throws Exception {
    AgentRunRequest request = newRequest();

    Map<String, Object> stateDelta = new HashMap<>();
    stateDelta.put("key1", "value1");
    stateDelta.put("key2", 42);
    stateDelta.put("key3", true);
    request.stateDelta = stateDelta;

    String json = objectMapper.writeValueAsString(request);

    JsonNode deltaState = objectMapper.readTree(json).get("stateDelta");

    assertThat(deltaState.get("key1").asText()).isEqualTo("value1");
    assertThat(deltaState.get("key2").asInt()).isEqualTo(42);
    assertThat(deltaState.get("key3").asBoolean()).isTrue();
  }

  @Test
  public void serialize_expectedUsage() throws Exception {
    AgentRunRequest request = newRequest();

    String json = objectMapper.writeValueAsString(request);

    JsonNode node = objectMapper.readTree(json);
    assertThat(node.get("appName").asText()).isEqualTo("testApp");
    assertThat(node.get("userId").asText()).isEqualTo("user123");
    assertThat(node.get("sessionId").asText()).isEqualTo("session456");
    if (node.has("stateDelta")) {
      assertThat(node.get("stateDelta").isNull()).isTrue();
    }
  }
}
