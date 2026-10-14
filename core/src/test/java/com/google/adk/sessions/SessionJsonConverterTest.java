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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.events.ToolConfirmation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.StringList;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SessionJsonConverterTest {
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final EventCompaction COMPACTION =
      EventCompaction.builder()
          .startTimestamp(1_700_000_000_100L)
          .endTimestamp(1_700_000_000_200L)
          .compactedContent(Content.fromParts(Part.fromText("summary")))
          .build();

  @Test
  public void convertEventToJson_fullEvent_success() throws JsonProcessingException {
    EventActions actions =
        EventActions.builder()
            .skipSummarization(true)
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key", "value")))
            .artifactDelta(new ConcurrentHashMap<>(ImmutableMap.of("artifact", 1)))
            .transferToAgent("agent")
            .escalate(true)
            .build();

    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-123")
            .timestamp(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli())
            .errorCode(new FinishReason("OTHER"))
            .errorMessage("Something was not found")
            .partial(true)
            .turnComplete(true)
            .interrupted(false)
            .branch("branch-1")
            .content(Content.fromParts(Part.fromText("Hello")))
            .actions(actions)
            .build();

    String json = SessionJsonConverter.convertEventToJson(event);
    JsonNode jsonNode = objectMapper.readTree(json);

    assertThat(jsonNode.get("author").asText()).isEqualTo("user");
    assertThat(jsonNode.get("invocationId").asText()).isEqualTo("inv-123");
    assertThat(jsonNode.get("timestamp").get("seconds").asLong()).isEqualTo(1672531200L);
    assertThat(jsonNode.get("errorCode").asText()).isEqualTo("OTHER");
    assertThat(jsonNode.get("errorMessage").asText()).isEqualTo("Something was not found");
    assertThat(jsonNode.get("content").get("parts").get(0).get("text").asText()).isEqualTo("Hello");

    JsonNode eventMetadata = jsonNode.get("eventMetadata");
    assertThat(eventMetadata.get("partial").asBoolean()).isTrue();
    assertThat(eventMetadata.get("turnComplete").asBoolean()).isTrue();
    assertThat(eventMetadata.get("interrupted").asBoolean()).isFalse();
    assertThat(eventMetadata.get("branch").asText()).isEqualTo("branch-1");

    JsonNode actionsNode = jsonNode.get("actions");
    assertThat(actionsNode.get("skipSummarization").asBoolean()).isTrue();
    assertThat(actionsNode.get("stateDelta").get("key").asText()).isEqualTo("value");
    assertThat(actionsNode.get("artifactDelta").get("artifact").asInt()).isEqualTo(1);
    assertThat(actionsNode.get("transferAgent").asText()).isEqualTo("agent");
    assertThat(actionsNode.get("escalate").asBoolean()).isTrue();
  }

  @Test
  public void convertEventToJson_minimalEvent_success() throws JsonProcessingException {
    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-123")
            .timestamp(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli())
            .build();

    String json = SessionJsonConverter.convertEventToJson(event);
    JsonNode jsonNode = objectMapper.readTree(json);

    assertThat(jsonNode.get("author").asText()).isEqualTo("user");
    assertThat(jsonNode.get("invocationId").asText()).isEqualTo("inv-123");
    assertThat(jsonNode.get("timestamp").get("seconds").asLong()).isEqualTo(1672531200L);
    assertThat(jsonNode.has("errorCode")).isFalse();
    assertThat(jsonNode.has("errorMessage")).isFalse();
    assertThat(jsonNode.has("content")).isFalse();
  }

  @Test
  public void fromApiEvent_fullEvent_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put("errorCode", "OK");
    apiEvent.put("errorMessage", "Success");
    apiEvent.put("branch", "branch-1");

    ImmutableMap<String, Object> content =
        ImmutableMap.of("parts", Collections.singletonList(ImmutableMap.of("text", "Hello")));
    apiEvent.put("content", content);

    Map<String, Object> eventMetadata = new HashMap<>();
    eventMetadata.put("partial", true);
    eventMetadata.put("turnComplete", true);
    eventMetadata.put("interrupted", false);
    eventMetadata.put("branch", "branch-meta");
    apiEvent.put("eventMetadata", eventMetadata);

    Map<String, Object> actions = new HashMap<>();
    actions.put("skipSummarization", true);
    actions.put("stateDelta", ImmutableMap.of("key", "value"));
    actions.put("artifactDelta", ImmutableMap.of("artifact", 1));
    actions.put("transferAgent", "agent");
    actions.put("escalate", true);
    apiEvent.put("actions", actions);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.invocationId()).isEqualTo("inv-123");
    assertThat(event.author()).isEqualTo("model");
    assertThat(event.timestamp()).isEqualTo(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli());
    assertThat(event.errorCode().get().toString()).isEqualTo("OK");
    assertThat(event.errorMessage()).hasValue("Success");
    assertThat(event.branch()).hasValue("branch-meta");
    assertThat(event.content().get().text()).isEqualTo("Hello");
    assertThat(event.partial().get()).isTrue();
    assertThat(event.turnComplete().get()).isTrue();
    assertThat(event.interrupted().get()).isFalse();

    EventActions eventActions = event.actions();
    assertThat(eventActions.skipSummarization()).hasValue(true);
    assertThat(eventActions.stateDelta()).containsEntry("key", "value");
    assertThat(eventActions.artifactDelta()).containsEntry("artifact", 1);
    assertThat(eventActions.transferToAgent()).hasValue("agent");
    assertThat(eventActions.escalate()).hasValue(true);
  }

  @Test
  public void fromApiEvent_withTransferToAgent_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");

    Map<String, Object> actions = new HashMap<>();
    actions.put("transferToAgent", "agent-id");
    apiEvent.put("actions", actions);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.actions().transferToAgent()).hasValue("agent-id");
  }

  @Test
  public void convertEventToJson_complexActions_success() throws JsonProcessingException {
    ConcurrentMap<String, ConcurrentMap<String, Object>> authConfigs = new ConcurrentHashMap<>();
    authConfigs.put("auth1", new ConcurrentHashMap<>(ImmutableMap.of("param1", "value1")));

    ConcurrentMap<String, ToolConfirmation> toolConfirmations = new ConcurrentHashMap<>();
    toolConfirmations.put(
        "tool1", ToolConfirmation.builder().hint("hint1").confirmed(true).build());

    EventActions actions =
        EventActions.builder()
            .requestedAuthConfigs(authConfigs)
            .requestedToolConfirmations(toolConfirmations)
            .endOfAgent(true)
            .build();

    GenerateContentResponseUsageMetadata usageMetadata =
        GenerateContentResponseUsageMetadata.builder().promptTokenCount(10).build();
    GroundingMetadata groundingMetadata = GroundingMetadata.builder().build();

    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-123")
            .timestamp(Instant.parse("2023-01-01T00:00:00.123Z").toEpochMilli())
            .actions(actions)
            .longRunningToolIds(ImmutableSet.of("tool-id-1"))
            .usageMetadata(usageMetadata)
            .groundingMetadata(groundingMetadata)
            .build();

    String json = SessionJsonConverter.convertEventToJson(event, true);
    JsonNode jsonNode = objectMapper.readTree(json);

    assertThat(jsonNode.get("timestamp").asText()).isEqualTo("2023-01-01T00:00:00.123Z");

    JsonNode eventMetadata = jsonNode.get("eventMetadata");
    assertThat(eventMetadata.get("longRunningToolIds").get(0).asText()).isEqualTo("tool-id-1");
    assertThat(eventMetadata.has("groundingMetadata")).isTrue();
    // The API drops the typed usageMetadata field, so it is stored in customMetadata.
    assertThat(eventMetadata.has("usageMetadata")).isFalse();
    assertThat(
            eventMetadata
                .get("customMetadata")
                .get("_usage_metadata")
                .get("promptTokenCount")
                .asInt())
        .isEqualTo(10);

    JsonNode actionsNode = jsonNode.get("actions");
    assertThat(actionsNode.get("requestedAuthConfigs").get("auth1").get("param1").asText())
        .isEqualTo("value1");
    // The API drops these too; only rawEvent carries them.
    assertThat(actionsNode.has("requestedToolConfirmations")).isFalse();
    assertThat(actionsNode.has("endOfAgent")).isFalse();

    JsonNode rawActions = jsonNode.get("rawEvent").get("actions");
    assertThat(rawActions.get("requestedToolConfirmations").get("tool1").get("hint").asText())
        .isEqualTo("hint1");
    assertThat(
            rawActions.get("requestedToolConfirmations").get("tool1").get("confirmed").asBoolean())
        .isTrue();
    assertThat(rawActions.get("endOfAgent").asBoolean()).isTrue();
    assertThat(jsonNode.get("rawEvent").get("usageMetadata").get("promptTokenCount").asInt())
        .isEqualTo(10);
  }

  @Test
  public void fromApiEvent_complexActions_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00.123Z");

    Map<String, Object> actions = new HashMap<>();
    actions.put("requestedAuthConfigs", ImmutableMap.of("auth1", ImmutableMap.of("p1", "v1")));
    actions.put(
        "requestedToolConfirmations",
        ImmutableMap.of("tool1", ImmutableMap.of("hint", "h1", "confirmed", true)));
    actions.put("endOfAgent", true);
    apiEvent.put("actions", actions);

    Map<String, Object> eventMetadata = new HashMap<>();
    eventMetadata.put("longRunningToolIds", ImmutableList.of("tool-1"));
    eventMetadata.put("usageMetadata", ImmutableMap.of("promptTokenCount", 10));
    eventMetadata.put("groundingMetadata", ImmutableMap.of());
    apiEvent.put("eventMetadata", eventMetadata);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.timestamp())
        .isEqualTo(Instant.parse("2023-01-01T00:00:00.123Z").toEpochMilli());
    assertThat(event.longRunningToolIds().get()).containsExactly("tool-1");
    assertThat(event.usageMetadata().get().promptTokenCount()).hasValue(10);
    assertThat(event.groundingMetadata()).isPresent();

    EventActions eventActions = event.actions();
    assertThat(eventActions.requestedAuthConfigs().get("auth1")).containsEntry("p1", "v1");
    assertThat(eventActions.requestedToolConfirmations().get("tool1").hint()).isEqualTo("h1");
    assertThat(eventActions.requestedToolConfirmations().get("tool1").confirmed()).isTrue();
    assertThat(eventActions.endOfAgent()).isTrue();
  }

  @Test
  public void convertEventToJson_agentState_success() throws JsonProcessingException {
    EventActions actions =
        EventActions.builder()
            .agentState(ImmutableMap.of("current_sub_agent", "b_agent", "times_looped", 2))
            .build();
    Event event =
        Event.builder()
            .author("agent")
            .invocationId("inv-1")
            .timestamp(Instant.parse("2023-01-01T00:00:00.123Z").toEpochMilli())
            .actions(actions)
            .build();

    String json = SessionJsonConverter.convertEventToJson(event, true);
    JsonNode jsonNode = objectMapper.readTree(json);
    JsonNode rawActions = jsonNode.get("rawEvent").get("actions");

    assertThat(jsonNode.get("actions").has("agentState")).isFalse();
    assertThat(rawActions.get("agentState").get("current_sub_agent").asText()).isEqualTo("b_agent");
    assertThat(rawActions.get("agentState").get("times_looped").asInt()).isEqualTo(2);
  }

  // As in Python ADK, a readable rawEvent replaces the typed fields as a whole.
  @Test
  public void fromApiEvent_rawEventPresent_takesPrecedenceOverTypedFields() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00.123Z");
    apiEvent.put(
        "content", ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "a"))));
    apiEvent.put("actions", new HashMap<>(ImmutableMap.of("transferAgent", "typed-agent")));
    apiEvent.put("eventMetadata", new HashMap<>(ImmutableMap.of("branch", "typed-branch")));
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "content",
            ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "b"))),
            "actions",
            ImmutableMap.of("transferToAgent", "raw-agent"),
            "branch",
            "raw-branch"));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.content().get().text()).isEqualTo("b");
    assertThat(event.actions().transferToAgent()).hasValue("raw-agent");
    assertThat(event.branch()).hasValue("raw-branch");
    assertThat(event.invocationId()).isEqualTo("inv-1");
    assertThat(event.author()).isEqualTo("agent");
    assertThat(event.timestamp())
        .isEqualTo(Instant.parse("2023-01-01T00:00:00.123Z").toEpochMilli());
  }

  // Python's Vertex session service keeps agentState and endOfAgent only under rawEvent, so a
  // session it wrote must still load with its checkpoints.
  @Test
  public void fromApiEvent_agentStateOnlyUnderRawEvent_readsCheckpoint() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00.123Z");
    // The six-key actions block Python writes, with no agentState and no endOfAgent.
    apiEvent.put("actions", new HashMap<>(ImmutableMap.of("stateDelta", new HashMap<>())));
    Map<String, Object> rawActions = new HashMap<>();
    rawActions.put("agentState", ImmutableMap.of("current_sub_agent", "b_agent"));
    rawActions.put("endOfAgent", true);
    apiEvent.put("rawEvent", ImmutableMap.of("actions", rawActions));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.actions().agentState()).isPresent();
    assertThat(event.actions().agentState().get()).containsEntry("current_sub_agent", "b_agent");
    assertThat(event.actions().endOfAgent()).isTrue();
  }

  @Test
  public void fromApiEvent_agentState_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00.123Z");
    Map<String, Object> actions = new HashMap<>();
    actions.put("agentState", ImmutableMap.of("current_sub_agent", "b_agent", "times_looped", 2));
    apiEvent.put("actions", actions);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.actions().agentState()).isPresent();
    assertThat(event.actions().agentState().get()).containsEntry("current_sub_agent", "b_agent");
    assertThat(event.actions().agentState().get()).containsEntry("times_looped", 2);
  }

  @Test
  public void fromApiEvent_agentStateNotAMap_isIgnored() {
    // Another runtime may write a non-map agentState; dropping it keeps the session loadable.
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00.123Z");
    Map<String, Object> actions = new HashMap<>();
    actions.put("agentState", "not-a-map");
    apiEvent.put("actions", actions);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.actions().agentState()).isEmpty();
  }

  @Test
  public void fromApiEvent_minimalEvent_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.invocationId()).isEqualTo("inv-123");
    assertThat(event.author()).isEqualTo("model");
    assertThat(event.timestamp()).isEqualTo(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli());
    assertThat(event.errorCode()).isEmpty();
    assertThat(event.errorMessage()).isEmpty();
    assertThat(event.branch()).isEmpty();
    assertThat(event.content()).isEmpty();
    assertThat(event.partial().orElse(false)).isFalse();
    assertThat(event.turnComplete().orElse(false)).isFalse();
    assertThat(event.interrupted().orElse(false)).isFalse();
  }

  @Test
  public void fromApiEvent_withMapTimestamp_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", ImmutableMap.of("seconds", 1672531200L, "nanos", 0));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.timestamp()).isEqualTo(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli());
  }

  @Test
  public void fromApiEvent_withInvalidContent_returnsNullContent() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put("content", "just a string, not a map");

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.content()).isEmpty();
  }

  @Test
  public void fromApiEvent_missingMetadataFields_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");

    Map<String, Object> eventMetadata = new HashMap<>();
    eventMetadata.put("partial", true);
    // turnComplete and interrupted are missing
    apiEvent.put("eventMetadata", eventMetadata);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.partial().get()).isTrue();
    assertThat(event.turnComplete().get()).isFalse();
    assertThat(event.interrupted().get()).isFalse();
  }

  @Test
  public void convertEventToJson_withStateRemoved_success() throws JsonProcessingException {
    EventActions actions =
        EventActions.builder()
            .stateDelta(
                new ConcurrentHashMap<>(ImmutableMap.of("key1", "value1", "key2", State.REMOVED)))
            .build();

    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-123")
            .timestamp(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli())
            .actions(actions)
            .build();

    String json = SessionJsonConverter.convertEventToJson(event);
    JsonNode jsonNode = objectMapper.readTree(json);

    JsonNode actionsNode = jsonNode.get("actions");
    assertThat(actionsNode.get("stateDelta").get("key1").asText()).isEqualTo("value1");
    assertThat(actionsNode.get("stateDelta").get("key2").isNull()).isTrue();
  }

  @Test
  public void fromApiEvent_withInvalidContentMap_returnsNullContent() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    // Parts should be a list, not a string
    apiEvent.put("content", ImmutableMap.of("parts", "invalid"));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.content()).isEmpty();
  }

  @Test
  public void fromApiEvent_withInvalidArtifactDelta_skipsInvalidEntries() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");

    Map<String, Object> artifactDelta = new HashMap<>();
    artifactDelta.put("valid", 1);
    artifactDelta.put("invalid", "not-a-map");

    Map<String, Object> actions = new HashMap<>();
    actions.put("artifactDelta", artifactDelta);
    apiEvent.put("actions", actions);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.actions().artifactDelta()).containsKey("valid");
    assertThat(event.actions().artifactDelta()).doesNotContainKey("invalid");
  }

  @Test
  public void fromApiEvent_missingTimestamp_throwsException() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");

    assertThrows(IllegalArgumentException.class, () -> SessionJsonConverter.fromApiEvent(apiEvent));
  }

  @Test
  public void fromApiEvent_withNullStateDeltaValue_success() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-123");
    apiEvent.put("author", "model");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");

    Map<String, Object> stateDelta = new HashMap<>();
    stateDelta.put("key1", "value1");
    stateDelta.put("key2", null);

    Map<String, Object> actions = new HashMap<>();
    actions.put("stateDelta", stateDelta);
    apiEvent.put("actions", actions);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    EventActions eventActions = event.actions();
    assertThat(eventActions.stateDelta()).containsEntry("key1", "value1");
    assertThat(eventActions.stateDelta()).containsEntry("key2", State.REMOVED);
  }

  @Test
  public void convertEventToJson_customMetadata_writtenUnderPythonKeys()
      throws JsonProcessingException {
    Event event =
        Event.builder()
            .author("agent")
            .invocationId("inv-1")
            .timestamp(1_700_000_000_123L)
            .customMetadata(
                ImmutableList.of(
                    CustomMetadata.builder().key("str").stringValue("v").build(),
                    CustomMetadata.builder().key("num").numericValue(1.5f).build(),
                    CustomMetadata.builder()
                        .key("list")
                        .stringListValue(
                            StringList.builder().values(ImmutableList.of("a", "b")).build())
                        .build()))
            .usageMetadata(
                GenerateContentResponseUsageMetadata.builder().totalTokenCount(12).build())
            .actions(EventActions.builder().compaction(COMPACTION).build())
            .build();

    JsonNode customMetadata =
        objectMapper
            .readTree(SessionJsonConverter.convertEventToJson(event))
            .get("eventMetadata")
            .get("customMetadata");

    assertThat(customMetadata.get("str").asText()).isEqualTo("v");
    assertThat(customMetadata.get("num").asDouble()).isEqualTo(1.5);
    assertThat(customMetadata.get("list").get(1).asText()).isEqualTo("b");
    assertThat(customMetadata.get("_usage_metadata").get("totalTokenCount").asInt()).isEqualTo(12);
    JsonNode compaction = customMetadata.get("_compaction");
    assertThat(compaction.get("startTimestamp").asDouble()).isWithin(1e-6).of(1_700_000_000.1);
    assertThat(compaction.get("endTimestamp").asDouble()).isWithin(1e-6).of(1_700_000_000.2);
    assertThat(compaction.get("compactedContent").get("parts").get(0).get("text").asText())
        .isEqualTo("summary");
  }

  @Test
  public void convertEventToJson_rawEvent_hasShapePythonAdkReads() throws JsonProcessingException {
    EventActions actions =
        EventActions.builder()
            .stateDelta(
                new ConcurrentHashMap<>(ImmutableMap.of("kept", "v", "removed", State.REMOVED)))
            .compaction(COMPACTION)
            .build();
    Event event =
        Event.builder()
            .id("event-1")
            .author("agent")
            .invocationId("inv-1")
            .timestamp(1_700_000_000_123L)
            .customMetadata(
                ImmutableList.of(CustomMetadata.builder().key("k").stringValue("v").build()))
            .actions(actions)
            .build();

    JsonNode rawEvent =
        objectMapper.readTree(SessionJsonConverter.convertEventToJson(event)).get("rawEvent");

    assertThat(rawEvent.get("id").asText()).isEqualTo("event-1");
    assertThat(rawEvent.get("timestamp").asDouble()).isWithin(1e-6).of(1_700_000_000.123);
    assertThat(rawEvent.get("customMetadata").get("k").asText()).isEqualTo("v");
    JsonNode rawActions = rawEvent.get("actions");
    assertThat(rawActions.get("stateDelta").get("kept").asText()).isEqualTo("v");
    assertThat(rawActions.get("stateDelta").get("removed").isNull()).isTrue();
    assertThat(rawActions.get("compaction").get("startTimestamp").asDouble())
        .isWithin(1e-6)
        .of(1_700_000_000.1);
  }

  // Python ADK fails to load the whole session on any of these.
  @Test
  public void convertEventToJson_rawEvent_leavesOutFieldsPythonAdkRejects()
      throws JsonProcessingException {
    EventActions actions =
        EventActions.builder()
            .requestedToolConfirmations(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("call-1", ToolConfirmation.builder().hint(null).build())))
            .deletedArtifactIds(ImmutableSet.of("artifact"))
            .setModelResponse(ImmutableMap.of("k", "v"))
            .build();
    Event event =
        Event.builder()
            .author("agent")
            .invocationId("inv-1")
            .timestamp(1L)
            .actions(actions)
            .build();

    JsonNode rawEvent =
        objectMapper.readTree(SessionJsonConverter.convertEventToJson(event)).get("rawEvent");

    assertThat(rawEvent.has("id")).isFalse();
    JsonNode rawActions = rawEvent.get("actions");
    assertThat(rawActions.get("requestedToolConfirmations").get("call-1").has("hint")).isFalse();
    assertThat(rawActions.has("deletedArtifactIds")).isFalse();
    assertThat(rawActions.has("setModelResponse")).isFalse();
  }

  @Test
  public void convertEventToJson_rawEvent_keepsNullMapValues() throws JsonProcessingException {
    Map<String, Object> agentState = new HashMap<>();
    agentState.put("unset", null);
    Event event =
        Event.builder()
            .author("agent")
            .invocationId("inv-1")
            .timestamp(1L)
            .actions(EventActions.builder().agentState(agentState).build())
            .build();

    JsonNode rawEvent =
        objectMapper.readTree(SessionJsonConverter.convertEventToJson(event)).get("rawEvent");

    assertThat(rawEvent.get("actions").get("agentState").get("unset").isNull()).isTrue();
  }

  @Test
  public void convertEventToJson_thenFromApiEvent_roundTripsWholeEvent() throws Exception {
    Event event =
        Event.builder()
            .id("event-1")
            .author("agent")
            .invocationId("inv-1")
            .timestamp(1_700_000_000_123L)
            .content(Content.fromParts(Part.fromText("hi")))
            .customMetadata(
                ImmutableList.of(CustomMetadata.builder().key("k").stringValue("v").build()))
            .usageMetadata(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(10)
                    .totalTokenCount(12)
                    .build())
            .finishReason(new FinishReason("STOP"))
            .modelVersion("model-1")
            .turnComplete(true)
            .actions(
                EventActions.builder()
                    .stateDelta(
                        new ConcurrentHashMap<>(ImmutableMap.of("k", "v", "gone", State.REMOVED)))
                    .transferToAgent("other")
                    .requestedToolConfirmations(
                        new ConcurrentHashMap<>(
                            ImmutableMap.of(
                                "call-1",
                                ToolConfirmation.builder().hint("ok?").confirmed(true).build())))
                    .endOfAgent(true)
                    .agentState(ImmutableMap.of("step", "2"))
                    .compaction(COMPACTION)
                    .build())
            .build();

    Event reloaded = SessionJsonConverter.fromApiEvent(asStoredEvent(event));

    assertThat(reloaded).isEqualTo(event);
  }

  @Test
  public void convertEventToJson_thenFromApiEvent_roundTripsInlineBytes() throws Exception {
    byte[] data = {(byte) 0xfb, (byte) 0xff, 0x01};
    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-1")
            .timestamp(1_700_000_000_123L)
            .content(Content.fromParts(Part.fromBytes(data, "image/png")))
            .build();

    Blob reloaded =
        SessionJsonConverter.fromApiEvent(asStoredEvent(event))
            .content()
            .get()
            .parts()
            .get()
            .get(0)
            .inlineData()
            .get();

    assertThat(reloaded.data().get()).isEqualTo(data);
    // Only rawEvent keeps the MIME type, so this proves the bytes came from it.
    assertThat(reloaded.mimeType()).hasValue("image/png");
  }

  @Test
  public void fromApiEvent_pythonWrittenRawEvent_readsFieldsJavaModels() {
    // As Python ADK dumps it: float seconds, URL-safe base64 and keys Java does not model.
    Map<String, Object> rawEvent = new HashMap<>();
    rawEvent.put("id", "python-event-id");
    rawEvent.put("invocationId", "inv-1");
    rawEvent.put("author", "agent");
    rawEvent.put("timestamp", 1_700_000_000.5);
    rawEvent.put("nodeInfo", ImmutableMap.of("path", ""));
    rawEvent.put(
        "content",
        ImmutableMap.of(
            "role",
            "model",
            "parts",
            ImmutableList.of(ImmutableMap.of("text", "hi", "thoughtSignature", "-_8B"))));
    rawEvent.put("usageMetadata", ImmutableMap.of("promptTokenCount", 10, "totalTokenCount", 12));
    rawEvent.put(
        "customMetadata",
        ImmutableMap.of(
            "flag",
            true,
            "k",
            "v",
            "n",
            2.5,
            "tags",
            ImmutableList.of("a", ImmutableMap.of("b", 1))));
    rawEvent.put(
        "actions",
        ImmutableMap.of(
            "requestedToolConfirmations",
            ImmutableMap.of("call-1", ImmutableMap.of("hint", "ok?", "confirmed", false)),
            "compaction",
            ImmutableMap.of(
                "startTimestamp",
                1_700_000_000.1,
                "endTimestamp",
                1_700_000_000.2,
                "compactedContent",
                ImmutableMap.of(
                    "role", "model", "parts", ImmutableList.of(ImmutableMap.of("text", "sum"))))));
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/server-id");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-11-14T22:13:20.500Z");
    apiEvent.put("rawEvent", rawEvent);

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("python-event-id");
    assertThat(event.timestamp()).isEqualTo(1_700_000_000_500L);
    assertThat(event.content().get().parts().get().get(0).thoughtSignature().get())
        .isEqualTo(new byte[] {(byte) 0xfb, (byte) 0xff, 0x01});
    assertThat(event.usageMetadata().get().totalTokenCount()).hasValue(12);
    assertThat(event.customMetadata().get())
        .containsExactly(
            CustomMetadata.builder().key("flag").stringValue("true").build(),
            CustomMetadata.builder().key("k").stringValue("v").build(),
            CustomMetadata.builder().key("n").numericValue(2.5f).build(),
            CustomMetadata.builder()
                .key("tags")
                .stringListValue(
                    StringList.builder().values(ImmutableList.of("a", "{\"b\":1}")).build())
                .build());
    assertThat(event.actions().requestedToolConfirmations().get("call-1").hint()).isEqualTo("ok?");
    assertThat(event.actions().compaction().get().startTimestamp()).isEqualTo(1_700_000_000_100L);
    assertThat(event.actions().compaction().get().endTimestamp()).isEqualTo(1_700_000_000_200L);
  }

  // Python timestamps carry microseconds, which event timestamps drop by flooring to millis.
  @Test
  public void fromApiEvent_pythonCompactionWithMicroseconds_floorsToMillis() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "user");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "actions",
            ImmutableMap.of(
                "compaction",
                ImmutableMap.of(
                    "startTimestamp",
                    1_700_000_000.1236,
                    "endTimestamp",
                    1_700_000_000.9999,
                    "compactedContent",
                    ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "s")))))));

    EventCompaction compaction =
        SessionJsonConverter.fromApiEvent(apiEvent).actions().compaction().get();

    assertThat(compaction.startTimestamp()).isEqualTo(1_700_000_000_123L);
    assertThat(compaction.endTimestamp()).isEqualTo(1_700_000_000_999L);
  }

  // Python ADK stores an event without raw_event when its Vertex AI SDK rejects that field.
  @Test
  public void fromApiEvent_pythonEventWithoutRawEvent_readsCustomMetadataKeys() {
    Map<String, Object> customMetadata = new HashMap<>();
    customMetadata.put("k", "v");
    customMetadata.put(
        "_usage_metadata",
        ImmutableMap.of(
            "prompt_token_count",
            10,
            "total_token_count",
            12,
            "prompt_tokens_details",
            ImmutableList.of(ImmutableMap.of("modality", "TEXT", "token_count", 10))));
    customMetadata.put(
        "_compaction",
        ImmutableMap.of(
            "start_timestamp",
            1_700_000_000.1,
            "end_timestamp",
            1_700_000_000.2,
            "compacted_content",
            ImmutableMap.of(
                "role", "model", "parts", ImmutableList.of(ImmutableMap.of("text", "summary")))));
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put("eventMetadata", new HashMap<>(ImmutableMap.of("customMetadata", customMetadata)));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.usageMetadata().get().promptTokenCount()).hasValue(10);
    assertThat(event.usageMetadata().get().promptTokensDetails().get().get(0).tokenCount())
        .hasValue(10);
    assertThat(event.actions().compaction().get().startTimestamp()).isEqualTo(1_700_000_000_100L);
    assertThat(event.actions().compaction().get().compactedContent().text()).isEqualTo("summary");
    assertThat(event.customMetadata().get())
        .containsExactly(CustomMetadata.builder().key("k").stringValue("v").build());
  }

  @Test
  public void fromApiEvent_pythonCompactionWithUserData_keepsItsKeys() {
    Map<String, Object> functionCall =
        ImmutableMap.of("name", "lookup", "args", ImmutableMap.of("user_id", "u1"));
    Map<String, Object> functionResponse =
        ImmutableMap.of("name", "lookup", "response", ImmutableMap.of("user_name", "Ann"));
    Map<String, Object> compaction =
        ImmutableMap.of(
            "start_timestamp",
            1_700_000_000.1,
            "end_timestamp",
            1_700_000_000.2,
            "compacted_content",
            ImmutableMap.of(
                "role",
                "model",
                "parts",
                ImmutableList.of(
                    ImmutableMap.of("function_call", functionCall),
                    ImmutableMap.of("function_response", functionResponse),
                    ImmutableMap.of(
                        "text", "summary", "part_metadata", ImmutableMap.of("user_tag", 1)))));
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "eventMetadata",
        new HashMap<>(
            ImmutableMap.of("customMetadata", ImmutableMap.of("_compaction", compaction))));

    List<Part> parts =
        SessionJsonConverter.fromApiEvent(apiEvent)
            .actions()
            .compaction()
            .get()
            .compactedContent()
            .parts()
            .get();

    assertThat(parts.get(0).functionCall().get().args().get()).containsExactly("user_id", "u1");
    assertThat(parts.get(1).functionResponse().get().response().get())
        .containsExactly("user_name", "Ann");
    assertThat(parts.get(2).partMetadata().get()).containsExactly("user_tag", 1);
  }

  // Java writes _compaction with camelCase keys.
  @Test
  public void fromApiEvent_javaCompactionWithPartMetadata_keepsItsKeys() {
    Map<String, Object> compaction =
        ImmutableMap.of(
            "startTimestamp",
            1_700_000_000.1,
            "endTimestamp",
            1_700_000_000.2,
            "compactedContent",
            ImmutableMap.of(
                "parts",
                ImmutableList.of(
                    ImmutableMap.of(
                        "text", "summary", "partMetadata", ImmutableMap.of("user_tag", 1)))));
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "eventMetadata",
        new HashMap<>(
            ImmutableMap.of("customMetadata", ImmutableMap.of("_compaction", compaction))));

    Part part =
        SessionJsonConverter.fromApiEvent(apiEvent)
            .actions()
            .compaction()
            .get()
            .compactedContent()
            .parts()
            .get()
            .get(0);

    assertThat(part.partMetadata().get()).containsExactly("user_tag", 1);
  }

  @Test
  public void fromApiEvent_unreadableRawEvent_fallsBackToTypedFields() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put("actions", new HashMap<>(ImmutableMap.of("transferAgent", "typed-agent")));
    apiEvent.put("rawEvent", ImmutableMap.of("id", "raw-id", "actions", "not-an-object"));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.actions().transferToAgent()).hasValue("typed-agent");
  }

  // As in Python ADK, an empty rawEvent counts as missing.
  @Test
  public void fromApiEvent_emptyRawEvent_usesTypedFields() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "content", ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "typed"))));
    apiEvent.put("actions", new HashMap<>(ImmutableMap.of("transferAgent", "typed-agent")));
    apiEvent.put("rawEvent", ImmutableMap.of());

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.content().get().text()).isEqualTo("typed");
    assertThat(event.actions().transferToAgent()).hasValue("typed-agent");
  }

  // As in Python ADK, an empty stored id falls back to the resource name.
  @Test
  public void fromApiEvent_rawEventEmptyId_usesResourceId() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put("rawEvent", ImmutableMap.of("id", "", "branch", "raw-branch"));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    // Only rawEvent carries the branch, so this proves rawEvent was read.
    assertThat(event.branch()).hasValue("raw-branch");
  }

  @Test
  public void fromApiEvent_rawEventWithInvalidBase64_fallsBackToTypedFields() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "user");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "content", ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "a"))));
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "id",
            "raw-id",
            "content",
            ImmutableMap.of(
                "parts",
                ImmutableList.of(ImmutableMap.of("inlineData", ImmutableMap.of("data", "!!!!"))))));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.content().get().text()).isEqualTo("a");
  }

  @Test
  public void fromApiEvent_rawEventWithNonStringBytes_fallsBackToTypedFields() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "user");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "content", ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "a"))));
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "id",
            "raw-id",
            "content",
            ImmutableMap.of(
                "parts",
                ImmutableList.of(
                    ImmutableMap.of(
                        "inlineData", ImmutableMap.of("data", ImmutableList.of(1, 2)))))));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.id()).isEqualTo("456");
    assertThat(event.content().get().text()).isEqualTo("a");
  }

  @Test
  public void fromApiEvent_unreadableRawEvent_keepsFieldsTheApiDropsFromRawActions() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    // Typed actions as the API returns them, without the fields it drops.
    apiEvent.put("actions", new HashMap<>(ImmutableMap.of("stateDelta", new HashMap<>())));
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "id",
            "raw-id",
            "content",
            ImmutableMap.of(
                "parts",
                ImmutableList.of(ImmutableMap.of("inlineData", ImmutableMap.of("data", "!!!!")))),
            "actions",
            ImmutableMap.of(
                "agentState",
                ImmutableMap.of("step", "2"),
                "endOfAgent",
                true,
                "requestedToolConfirmations",
                ImmutableMap.of("call-1", ImmutableMap.of("hint", "ok?", "confirmed", false)))));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    // The resource-name id shows that the typed-field fallback ran.
    assertThat(event.id()).isEqualTo("456");
    assertThat(event.actions().agentState().get()).containsEntry("step", "2");
    assertThat(event.actions().endOfAgent()).isTrue();
    assertThat(event.actions().requestedToolConfirmations().get("call-1").hint()).isEqualTo("ok?");
  }

  @Test
  public void fromApiEvent_unreadableRawEvent_prefersTypedActionsPerKey() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "actions", new HashMap<>(ImmutableMap.of("agentState", ImmutableMap.of("step", "typed"))));
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "content",
            ImmutableMap.of(
                "parts",
                ImmutableList.of(ImmutableMap.of("inlineData", ImmutableMap.of("data", "!!!!")))),
            "actions",
            ImmutableMap.of("agentState", ImmutableMap.of("step", "raw"), "endOfAgent", true)));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.actions().agentState().get()).containsEntry("step", "typed");
    assertThat(event.actions().endOfAgent()).isTrue();
  }

  @Test
  public void fromApiEvent_unreadableToolConfirmations_areSkipped() {
    Map<String, Object> toolConfirmations = new HashMap<>();
    toolConfirmations.put("call-1", "not-an-object");
    Event event = fromUnreadableRawEventWithActions(toolConfirmations);

    assertThat(event.actions().requestedToolConfirmations()).isEmpty();
    assertThat(event.actions().agentState()).isPresent();
  }

  @Test
  public void fromApiEvent_nullToolConfirmation_isSkipped() {
    Map<String, Object> toolConfirmations = new HashMap<>();
    toolConfirmations.put("call-1", null);
    toolConfirmations.put("call-2", ImmutableMap.of("hint", "ok?", "confirmed", false));
    Event event = fromUnreadableRawEventWithActions(toolConfirmations);

    assertThat(event.actions().requestedToolConfirmations().keySet()).containsExactly("call-2");
  }

  /** Reads an event whose rawEvent content is unreadable but whose actions carry these values. */
  private static Event fromUnreadableRawEventWithActions(Map<String, Object> toolConfirmations) {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "rawEvent",
        ImmutableMap.of(
            "content",
            ImmutableMap.of(
                "parts",
                ImmutableList.of(ImmutableMap.of("inlineData", ImmutableMap.of("data", "!!!!")))),
            "actions",
            ImmutableMap.of(
                "agentState",
                ImmutableMap.of("step", "2"),
                "requestedToolConfirmations",
                toolConfirmations)));
    return SessionJsonConverter.fromApiEvent(apiEvent);
  }

  @Test
  public void fromApiEvent_unreadableStoredUsageMetadata_isSkipped() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "eventMetadata",
        new HashMap<>(
            ImmutableMap.of(
                "customMetadata",
                ImmutableMap.of("_usage_metadata", ImmutableMap.of("total_token_count", "many")))));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.usageMetadata()).isEmpty();
  }

  // Java writes camelCase keys under _usage_metadata; snake_case conversion must leave them as is.
  @Test
  public void fromApiEvent_camelCaseUsageMetadataWithoutRawEvent_readsIt() {
    Map<String, Object> apiEvent = new HashMap<>();
    apiEvent.put("name", "sessions/123/events/456");
    apiEvent.put("invocationId", "inv-1");
    apiEvent.put("author", "agent");
    apiEvent.put("timestamp", "2023-01-01T00:00:00Z");
    apiEvent.put(
        "eventMetadata",
        new HashMap<>(
            ImmutableMap.of(
                "customMetadata",
                ImmutableMap.of("_usage_metadata", ImmutableMap.of("promptTokenCount", 10)))));

    Event event = SessionJsonConverter.fromApiEvent(apiEvent);

    assertThat(event.usageMetadata().get().promptTokenCount()).hasValue(10);
  }

  @Test
  public void convertEventToJson_unserializableValue_throwsUncheckedIOException() {
    Event event =
        Event.builder()
            .author("agent")
            .invocationId("inv-1")
            .timestamp(1L)
            .actions(
                EventActions.builder()
                    .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("k", new Object())))
                    .build())
            .build();

    assertThrows(UncheckedIOException.class, () -> SessionJsonConverter.convertEventToJson(event));
  }

  /** Returns the event as the API stores it, under a server-assigned name. */
  private static Map<String, Object> asStoredEvent(Event event) throws JsonProcessingException {
    Map<String, Object> apiEvent =
        objectMapper.readValue(
            SessionJsonConverter.convertEventToJson(event),
            new TypeReference<Map<String, Object>>() {});
    apiEvent.put("name", "sessions/123/events/server-id");
    return apiEvent;
  }
}
