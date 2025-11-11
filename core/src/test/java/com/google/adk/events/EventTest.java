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

package com.google.adk.events;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventTest {

  private static final FunctionCall FUNCTION_CALL =
      FunctionCall.builder().name("function_name").args(ImmutableMap.of("key", "value")).build();
  private static final Content CONTENT =
      Content.builder()
          .parts(ImmutableList.of(Part.builder().functionCall(FUNCTION_CALL).build()))
          .build();
  private static final EventActions EVENT_ACTIONS =
      EventActions.builder()
          .skipSummarization(true)
          .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key", "value")))
          .artifactDelta(
              new ConcurrentHashMap<>(
                  ImmutableMap.of("artifact_key", Part.builder().text("artifact_value").build())))
          .transferToAgent("agent_id")
          .escalate(true)
          .requestedAuthConfigs(
              new ConcurrentHashMap<>(
                  ImmutableMap.of(
                      "auth_config_key",
                      new ConcurrentHashMap<>(ImmutableMap.of("auth_key", "auth_value")))))
          .build();
  private static final Event EVENT =
      Event.builder()
          .id("event_id")
          .invocationId("invocation_id")
          .author("agent")
          .content(CONTENT)
          .actions(EVENT_ACTIONS)
          .longRunningToolIds(ImmutableSet.of("tool_id"))
          .partial(true)
          .turnComplete(true)
          .errorCode(new FinishReason("error_code"))
          .errorMessage("error_message")
          .finishReason(new FinishReason("finish_reason"))
          .usageMetadata(
              GenerateContentResponseUsageMetadata.builder()
                  .promptTokenCount(10)
                  .candidatesTokenCount(20)
                  .totalTokenCount(30)
                  .build())
          .avgLogprobs(0.5)
          .interrupted(true)
          .timestamp(123456789L)
          .build();

  @Test
  public void event_builder_works() {
    assertThat(EVENT.functionCalls()).containsExactly(FUNCTION_CALL);
    assertThat(EVENT.functionResponses()).isEmpty();
    assertThat(EVENT.longRunningToolIds().get()).containsExactly("tool_id");
    assertThat(EVENT.partial().get()).isTrue();
    assertThat(EVENT.turnComplete().get()).isTrue();
    assertThat(EVENT.errorCode()).hasValue(new FinishReason("error_code"));
    assertThat(EVENT.errorMessage()).hasValue("error_message");
    assertThat(EVENT.finishReason()).hasValue(new FinishReason("finish_reason"));
    assertThat(EVENT.usageMetadata())
        .hasValue(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(20)
                .totalTokenCount(30)
                .build());
    assertThat(EVENT.avgLogprobs()).hasValue(0.5);
    assertThat(EVENT.interrupted()).hasValue(true);
    assertThat(EVENT.timestamp()).isEqualTo(123456789L);
    assertThat(EVENT.actions()).isEqualTo(EVENT_ACTIONS);
  }

  @Test
  public void event_builder_fills_default_actions() {
    Event event =
        Event.builder().id("event_id").invocationId("invocation_id").author("agent").build();
    assertThat(event.id()).isEqualTo("event_id");
    assertThat(event.invocationId()).isEqualTo("invocation_id");
    assertThat(event.author()).isEqualTo("agent");
    assertThat(event.actions()).isEqualTo(EventActions.builder().build());
  }

  @Test
  public void event_builder_fills_default_timestamp() {
    long before = Instant.now().toEpochMilli();
    Event event =
        Event.builder().id("event_id").invocationId("invocation_id").author("agent").build();
    long after = Instant.now().toEpochMilli();
    assertThat(event.timestamp()).isAtLeast(before);
    assertThat(event.timestamp()).isAtMost(after);
  }

  @Test
  public void event_equals_works() {
    Event event1 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();
    Event event2 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  public void event_hashcode_works() {
    Event event1 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();
    Event event2 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  public void event_hashcode_works_with_map() {
    Event event1 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();
    Event event2 =
        Event.builder()
            .id("event_id_2")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();

    ImmutableMap<Event, String> map = ImmutableMap.of(event1, "e1", event2, "e2");
    assertThat(map).containsEntry(event1, "e1");
    assertThat(map).containsEntry(event2, "e2");
  }

  @Test
  public void event_json_serialization_works() throws Exception {
    String json = EVENT.toJson();
    Event deserializedEvent = Event.fromJson(json);
    assertThat(deserializedEvent).isEqualTo(EVENT);
  }
}
