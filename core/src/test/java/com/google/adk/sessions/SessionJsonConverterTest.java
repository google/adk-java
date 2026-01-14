package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SessionJsonConverterTest {
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  @Test
  public void convertEventToJson_fullEvent_success() throws JsonProcessingException {
    EventActions actions =
        EventActions.builder()
            .skipSummarization(true)
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key", "value")))
            .artifactDelta(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("artifact", Part.fromText("artifact_text"))))
            .transferToAgent("agent")
            .escalate(true)
            .build();

    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-123")
            .timestamp(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli())
            .errorCode(Optional.of(new FinishReason("OTHER")))
            .errorMessage(Optional.of("Something was not found"))
            .partial(true)
            .turnComplete(true)
            .interrupted(false)
            .branch(Optional.of("branch-1"))
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
    assertThat(actionsNode.get("artifactDelta").get("artifact").get("text").asText())
        .isEqualTo("artifact_text");
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
    actions.put(
        "artifactDelta", ImmutableMap.of("artifact", ImmutableMap.of("text", "artifact_text")));
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
    assertThat(eventActions.artifactDelta().get("artifact").text()).hasValue("artifact_text");
    assertThat(eventActions.transferToAgent()).hasValue("agent");
    assertThat(eventActions.escalate()).hasValue(true);
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
}
