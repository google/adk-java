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

package com.google.adk;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for JSON serialization/deserialization of classes inheriting from JsonBaseModel. */
@RunWith(JUnit4.class)
public class JsonBaseModelTest {

  @Test
  public void eventSerialization_usesCamelCase() {
    Event event =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId("test-invocation-id")
            .author("user")
            .content(
                Content.builder().parts(ImmutableList.of(Part.fromText("Hello, world!"))).build())
            .actions(
                EventActions.builder()
                    .stateDelta(
                        new ConcurrentHashMap<String, Object>(ImmutableMap.of("key", "value")))
                    .build())
            .partial(true)
            .turnComplete(false)
            .errorCode(new FinishReason("TEST_ERROR"))
            .errorMessage("This is a test error")
            .interrupted(true)
            .longRunningToolIds(ImmutableSet.of("tool_id_1", "tool_id_2"))
            .build();

    String json = event.toJson();

    // Basic checks for camelCase keys
    assertThat(json).contains("\"invocationId\":");
    assertThat(json).contains("\"errorCode\":");
    assertThat(json).contains("\"errorMessage\":");
    assertThat(json).contains("\"turnComplete\":");
    assertThat(json).contains("\"longRunningToolIds\":");
    assertThat(json).contains("\"partial\":");
    assertThat(json).contains("\"interrupted\":");
    assertThat(json).contains("\"stateDelta\":");
  }

  @Test
  public void eventDeserialization_handlesCamelCase() {
    String json =
        "{"
            + "\"id\":\"test-id\","
            + "\"invocationId\":\"test-invocation\","
            + "\"author\":\"agent\","
            + "\"content\":{\"parts\":[{\"text\":\"Response text\"}]},"
            + "\"partial\":false,"
            + "\"turnComplete\":true,"
            + "\"errorCode\":null," // Test null handling
            + "\"errorMessage\":null," // same as above
            + "\"interrupted\":false,\"longRunningToolIds\":[\"tool_id_3\"],"
            + "\"actions\":{\"stateDelta\":{\"key\":\"value\"},\"artifactDelta\":{},\"requestedAuthConfigs\":{}},"
            + "\"timestamp\":1234567890}";

    Event event = Event.fromJson(json);

    assertThat(event).isNotNull();
    assertThat(event.id()).isEqualTo("test-id");
    assertThat(event.invocationId()).isEqualTo("test-invocation");
    assertThat(event.author()).isEqualTo("agent");
    assertThat(event.content()).isPresent();
    assertThat(event.content().get().parts()).isPresent();
    assertThat(event.content().get().parts().get()).hasSize(1);
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Response text");
    assertThat(event.actions()).isNotNull();
    assertThat(event.actions().stateDelta()).containsExactly("key", "value");
    assertThat(event.partial()).hasValue(false);
    assertThat(event.turnComplete()).hasValue(true);
    assertThat(event.errorCode()).isEmpty();
    assertThat(event.errorMessage()).isEmpty();
    assertThat(event.interrupted()).hasValue(false);
    assertThat(event.longRunningToolIds()).hasValue(ImmutableSet.of("tool_id_3"));
    assertThat(event.timestamp()).isEqualTo(1234567890L);
  }
}
