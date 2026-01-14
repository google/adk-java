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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles JSON serialization and deserialization for session-related objects. */
final class SessionJsonConverter {
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final Logger logger = LoggerFactory.getLogger(SessionJsonConverter.class);

  private SessionJsonConverter() {}

  /**
   * Converts an {@link Event} to its JSON string representation for API transmission.
   *
   * @return JSON string of the event.
   * @throws UncheckedIOException if serialization fails.
   */
  static String convertEventToJson(Event event) {
    Map<String, Object> metadataJson = new HashMap<>();
    metadataJson.put("partial", event.partial());
    metadataJson.put("turnComplete", event.turnComplete());
    metadataJson.put("interrupted", event.interrupted());
    metadataJson.put("branch", event.branch().orElse(null));
    metadataJson.put(
        "long_running_tool_ids",
        event.longRunningToolIds() != null ? event.longRunningToolIds().orElse(null) : null);
    if (event.groundingMetadata() != null) {
      metadataJson.put("grounding_metadata", event.groundingMetadata());
    }

    Map<String, Object> eventJson = new HashMap<>();
    eventJson.put("author", event.author());
    eventJson.put("invocationId", event.invocationId());
    eventJson.put(
        "timestamp",
        new HashMap<>(
            ImmutableMap.of(
                "seconds",
                event.timestamp() / 1000,
                "nanos",
                (event.timestamp() % 1000) * 1000000)));
    if (event.errorCode().isPresent()) {
      eventJson.put("errorCode", event.errorCode());
    }
    if (event.errorMessage().isPresent()) {
      eventJson.put("errorMessage", event.errorMessage());
    }
    eventJson.put("eventMetadata", metadataJson);

    if (event.actions() != null) {
      Map<String, Object> actionsJson = new HashMap<>();
      actionsJson.put("skipSummarization", event.actions().skipSummarization());
      actionsJson.put("stateDelta", event.actions().stateDelta());
      actionsJson.put("artifactDelta", event.actions().artifactDelta());
      actionsJson.put("transferAgent", event.actions().transferToAgent());
      actionsJson.put("escalate", event.actions().escalate());
      actionsJson.put("requestedAuthConfigs", event.actions().requestedAuthConfigs());
      actionsJson.put("requestedToolConfirmations", event.actions().requestedToolConfirmations());
      actionsJson.put("endInvocation", event.actions().endInvocation());
      actionsJson.put("compaction", event.actions().compaction());
      eventJson.put("actions", actionsJson);
    }
    if (event.content().isPresent()) {
      eventJson.put("content", SessionUtils.encodeContent(event.content().get()));
    }
    if (event.errorCode().isPresent()) {
      eventJson.put("errorCode", event.errorCode().get());
    }
    if (event.errorMessage().isPresent()) {
      eventJson.put("errorMessage", event.errorMessage().get());
    }
    try {
      return objectMapper.writeValueAsString(eventJson);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Converts a raw value to a {@link Content} object.
   *
   * @return parsed {@link Content}, or {@code null} if conversion fails.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private static Content convertMapToContent(Object rawContentValue) {
    if (rawContentValue == null) {
      return null;
    }

    if (rawContentValue instanceof Map) {
      Map<String, Object> contentMap = (Map<String, Object>) rawContentValue;
      try {
        return objectMapper.convertValue(contentMap, Content.class);
      } catch (IllegalArgumentException e) {
        logger.warn("Error converting Map to Content", e);
        return null;
      }
    } else {
      logger.warn(
          "Unexpected type for 'content' in apiEvent: {}", rawContentValue.getClass().getName());
      return null;
    }
  }

  /**
   * Converts raw API event data into an {@link Event} object.
   *
   * @return parsed {@link Event}.
   */
  @SuppressWarnings("unchecked")
  static Event fromApiEvent(Map<String, Object> apiEvent) {
    EventActions.Builder eventActionsBuilder = EventActions.builder();
    if (apiEvent.get("actions") != null) {
      Map<String, Object> actionsMap = (Map<String, Object>) apiEvent.get("actions");
      if (actionsMap.get("skipSummarization") != null) {
        eventActionsBuilder.skipSummarization((Boolean) actionsMap.get("skipSummarization"));
      }
      eventActionsBuilder.stateDelta(
          actionsMap.get("stateDelta") != null
              ? new ConcurrentHashMap<>((Map<String, Object>) actionsMap.get("stateDelta"))
              : new ConcurrentHashMap<>());
      eventActionsBuilder.artifactDelta(
          actionsMap.get("artifactDelta") != null
              ? convertToArtifactDeltaMap(actionsMap.get("artifactDelta"))
              : new ConcurrentHashMap<>());
      eventActionsBuilder.transferToAgent((String) actionsMap.get("transferAgent"));
      if (actionsMap.get("escalate") != null) {
        eventActionsBuilder.escalate((Boolean) actionsMap.get("escalate"));
      }
      eventActionsBuilder.requestedAuthConfigs(
          Optional.ofNullable(actionsMap.get("requestedAuthConfigs"))
              .map(SessionJsonConverter::asConcurrentMapOfConcurrentMaps)
              .orElse(new ConcurrentHashMap<>()));
    }

    Event event =
        Event.builder()
            .id((String) Iterables.getLast(Splitter.on('/').split(apiEvent.get("name").toString())))
            .invocationId((String) apiEvent.get("invocationId"))
            .author((String) apiEvent.get("author"))
            .actions(eventActionsBuilder.build())
            .content(
                Optional.ofNullable(apiEvent.get("content"))
                    .map(SessionJsonConverter::convertMapToContent)
                    .map(SessionUtils::decodeContent)
                    .orElse(null))
            .timestamp(convertToInstant(apiEvent.get("timestamp")).toEpochMilli())
            .errorCode(
                Optional.ofNullable(apiEvent.get("errorCode"))
                    .map(value -> new FinishReason((String) value)))
            .errorMessage(
                Optional.ofNullable(apiEvent.get("errorMessage")).map(value -> (String) value))
            .branch(Optional.ofNullable(apiEvent.get("branch")).map(value -> (String) value))
            .build();
    // TODO(b/414263934): Add Event branch and grounding metadata for python parity.
    if (apiEvent.get("eventMetadata") != null) {
      Map<String, Object> eventMetadata = (Map<String, Object>) apiEvent.get("eventMetadata");
      List<String> longRunningToolIdsList = (List<String>) eventMetadata.get("longRunningToolIds");

      GroundingMetadata groundingMetadata = null;
      Object rawGroundingMetadata = eventMetadata.get("groundingMetadata");
      if (rawGroundingMetadata != null) {
        groundingMetadata =
            objectMapper.convertValue(rawGroundingMetadata, GroundingMetadata.class);
      }

      event =
          event.toBuilder()
              .partial(Optional.ofNullable((Boolean) eventMetadata.get("partial")).orElse(false))
              .turnComplete(
                  Optional.ofNullable((Boolean) eventMetadata.get("turnComplete")).orElse(false))
              .interrupted(
                  Optional.ofNullable((Boolean) eventMetadata.get("interrupted")).orElse(false))
              .branch(Optional.ofNullable((String) eventMetadata.get("branch")))
              .groundingMetadata(groundingMetadata)
              .longRunningToolIds(
                  longRunningToolIdsList != null ? new HashSet<>(longRunningToolIdsList) : null)
              .build();
    }
    return event;
  }

  /**
   * Converts a timestamp from a Map or String into an {@link Instant}.
   *
   * @param timestampObj map with "seconds"/"nanos" or an ISO string.
   * @return parsed {@link Instant}.
   */
  private static Instant convertToInstant(Object timestampObj) {
    if (timestampObj instanceof Map<?, ?> timestampMap) {
      return Instant.ofEpochSecond(
          ((Number) timestampMap.get("seconds")).longValue(),
          ((Number) timestampMap.get("nanos")).longValue());
    } else if (timestampObj != null) {
      return Instant.parse(timestampObj.toString());
    } else {
      throw new IllegalArgumentException("Timestamp not found in apiEvent");
    }
  }

  /**
   * Converts a raw object from "artifactDelta" into a {@link ConcurrentMap} of {@link String} to
   * {@link Part}.
   *
   * @param artifactDeltaObj The raw object from which to parse the artifact delta.
   * @return A {@link ConcurrentMap} representing the artifact delta.
   */
  @SuppressWarnings("unchecked")
  private static ConcurrentMap<String, Part> convertToArtifactDeltaMap(Object artifactDeltaObj) {
    if (!(artifactDeltaObj instanceof Map)) {
      return new ConcurrentHashMap<>();
    }
    ConcurrentMap<String, Part> artifactDeltaMap = new ConcurrentHashMap<>();
    Map<String, Map<String, Object>> rawMap = (Map<String, Map<String, Object>>) artifactDeltaObj;
    for (Map.Entry<String, Map<String, Object>> entry : rawMap.entrySet()) {
      try {
        Part part = objectMapper.convertValue(entry.getValue(), Part.class);
        artifactDeltaMap.put(entry.getKey(), part);
      } catch (IllegalArgumentException e) {
        logger.warn("Error converting artifactDelta value to Part for key: {}", entry.getKey(), e);
      }
    }
    return artifactDeltaMap;
  }

  /**
   * Converts a nested map into a {@link ConcurrentMap} of {@link ConcurrentMap}s.
   *
   * @return thread-safe nested map.
   */
  @SuppressWarnings("unchecked")
  private static ConcurrentMap<String, ConcurrentMap<String, Object>>
      asConcurrentMapOfConcurrentMaps(Object value) {
    return ((Map<String, Map<String, Object>>) value)
        .entrySet().stream()
            .collect(
                ConcurrentHashMap::new,
                (map, entry) -> map.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue())),
                ConcurrentHashMap::putAll);
  }
}
