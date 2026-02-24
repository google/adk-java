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
import com.google.adk.events.ToolConfirmation;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
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
    return convertEventToJson(event, false);
  }

  /**
   * Converts an {@link Event} to its JSON string representation for API transmission.
   *
   * @param useIsoString if true, use ISO-8601 string for timestamp; otherwise use object format.
   * @return JSON string of the event.
   * @throws UncheckedIOException if serialization fails.
   */
  static String convertEventToJson(Event event, boolean useIsoString) {
    Map<String, Object> metadataJson = new HashMap<>();
    event.partial().ifPresent(v -> metadataJson.put("partial", v));
    event.turnComplete().ifPresent(v -> metadataJson.put("turnComplete", v));
    event.interrupted().ifPresent(v -> metadataJson.put("interrupted", v));
    event.branch().ifPresent(v -> metadataJson.put("branch", v));
    putIfNotEmpty(metadataJson, "longRunningToolIds", event.longRunningToolIds());
    event.groundingMetadata().ifPresent(v -> metadataJson.put("groundingMetadata", v));
    event.usageMetadata().ifPresent(v -> metadataJson.put("usageMetadata", v));
    Map<String, Object> eventJson = new HashMap<>();
    eventJson.put("author", event.author());
    eventJson.put("invocationId", event.invocationId());
    if (useIsoString) {
      eventJson.put("timestamp", Instant.ofEpochMilli(event.timestamp()).toString());
    } else {
      eventJson.put(
          "timestamp",
          new HashMap<>(
              ImmutableMap.of(
                  "seconds",
                  event.timestamp() / 1000,
                  "nanos",
                  (event.timestamp() % 1000) * 1000000)));
    }
    event.errorCode().ifPresent(errorCode -> eventJson.put("errorCode", errorCode));
    event.errorMessage().ifPresent(errorMessage -> eventJson.put("errorMessage", errorMessage));
    eventJson.put("eventMetadata", metadataJson);

    if (event.actions() != null) {
      Map<String, Object> actionsJson = new HashMap<>();
      EventActions actions = event.actions();
      actions.skipSummarization().ifPresent(v -> actionsJson.put("skipSummarization", v));
      actionsJson.put("stateDelta", stateDeltaToJson(actions.stateDelta()));
      putIfNotEmpty(actionsJson, "artifactDelta", actions.artifactDelta());
      actions
          .transferToAgent()
          .ifPresent(
              v -> {
                actionsJson.put("transferAgent", v);
              });
      actions.escalate().ifPresent(v -> actionsJson.put("escalate", v));
      if (actions.endOfAgent()) {
        actionsJson.put("endOfAgent", actions.endOfAgent());
      }
      putIfNotEmpty(actionsJson, "requestedAuthConfigs", actions.requestedAuthConfigs());
      putIfNotEmpty(
          actionsJson, "requestedToolConfirmations", actions.requestedToolConfirmations());
      eventJson.put("actions", actionsJson);
    }
    event.content().ifPresent(c -> eventJson.put("content", SessionUtils.encodeContent(c)));
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
  @SuppressWarnings("unchecked") // Safe because we check instanceof Map before casting.
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
  @SuppressWarnings("unchecked") // Parsing raw Map from JSON following a known schema.
  static Event fromApiEvent(Map<String, Object> apiEvent) {
    EventActions.Builder eventActionsBuilder = EventActions.builder();
    Map<String, Object> actionsMap = (Map<String, Object>) apiEvent.get("actions");
    if (actionsMap != null) {
      Boolean skipSummarization = (Boolean) actionsMap.get("skipSummarization");
      if (skipSummarization != null) {
        eventActionsBuilder.skipSummarization(skipSummarization);
      }
      eventActionsBuilder.stateDelta(stateDeltaFromJson(actionsMap.get("stateDelta")));
      Object artifactDelta = actionsMap.get("artifactDelta");
      eventActionsBuilder.artifactDelta(
          artifactDelta != null
              ? convertToArtifactDeltaMap(artifactDelta)
              : new ConcurrentHashMap<>());
      String transferAgent = (String) actionsMap.get("transferAgent");
      if (transferAgent == null) {
        transferAgent = (String) actionsMap.get("transferToAgent");
      }
      eventActionsBuilder.transferToAgent(transferAgent);
      Boolean escalate = (Boolean) actionsMap.get("escalate");
      if (escalate != null) {
        eventActionsBuilder.escalate(escalate);
      }
      Boolean endOfAgent = (Boolean) actionsMap.get("endOfAgent");
      if (endOfAgent != null) {
        eventActionsBuilder.endOfAgent(endOfAgent);
      }
      eventActionsBuilder.requestedAuthConfigs(
          Optional.ofNullable(actionsMap.get("requestedAuthConfigs"))
              .map(SessionJsonConverter::asConcurrentMapOfConcurrentMaps)
              .orElse(new ConcurrentHashMap<>()));
      eventActionsBuilder.requestedToolConfirmations(
          Optional.ofNullable(actionsMap.get("requestedToolConfirmations"))
              .map(SessionJsonConverter::asConcurrentMapOfToolConfirmations)
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
            .build();
    Map<String, Object> eventMetadata = (Map<String, Object>) apiEvent.get("eventMetadata");
    if (eventMetadata != null) {
      List<String> longRunningToolIdsList = (List<String>) eventMetadata.get("longRunningToolIds");

      GroundingMetadata groundingMetadata = null;
      Object rawGroundingMetadata = eventMetadata.get("groundingMetadata");
      if (rawGroundingMetadata != null) {
        groundingMetadata =
            objectMapper.convertValue(rawGroundingMetadata, GroundingMetadata.class);
      }
      GenerateContentResponseUsageMetadata usageMetadata = null;
      Object rawUsageMetadata = eventMetadata.get("usageMetadata");
      if (rawUsageMetadata != null) {
        usageMetadata =
            objectMapper.convertValue(rawUsageMetadata, GenerateContentResponseUsageMetadata.class);
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
              .usageMetadata(usageMetadata)
              .longRunningToolIds(
                  longRunningToolIdsList != null ? new HashSet<>(longRunningToolIdsList) : null)
              .build();
    }
    return event;
  }

  @SuppressWarnings("unchecked") // stateDeltaFromMap is a Map<String, Object> from JSON.
  private static ConcurrentMap<String, Object> stateDeltaFromJson(Object stateDeltaFromMap) {
    if (stateDeltaFromMap == null) {
      return new ConcurrentHashMap<>();
    }
    return ((Map<String, Object>) stateDeltaFromMap)
        .entrySet().stream()
            .collect(
                ConcurrentHashMap::new,
                (map, entry) ->
                    map.put(
                        entry.getKey(),
                        entry.getValue() == null ? State.REMOVED : entry.getValue()),
                ConcurrentHashMap::putAll);
  }

  private static Map<String, Object> stateDeltaToJson(Map<String, Object> stateDelta) {
    return stateDelta.entrySet().stream()
        .collect(
            HashMap::new,
            (map, entry) ->
                map.put(
                    entry.getKey(), entry.getValue() == State.REMOVED ? null : entry.getValue()),
            HashMap::putAll);
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
  private static ConcurrentMap<String, Integer> convertToArtifactDeltaMap(Object artifactDeltaObj) {
    if (!(artifactDeltaObj instanceof Map)) {
      return new ConcurrentHashMap<>();
    }
    ConcurrentMap<String, Integer> artifactDeltaMap = new ConcurrentHashMap<>();
    Map<String, Object> rawMap = (Map<String, Object>) artifactDeltaObj;
    for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
      try {
        Integer value = objectMapper.convertValue(entry.getValue(), Integer.class);
        artifactDeltaMap.put(entry.getKey(), value);
      } catch (IllegalArgumentException e) {
        logger.warn(
            "Error converting artifactDelta value to Integer for key: {}", entry.getKey(), e);
      }
    }
    return artifactDeltaMap;
  }

  /**
   * Converts a nested map into a {@link ConcurrentMap} of {@link ConcurrentMap}s.
   *
   * @return thread-safe nested map.
   */
  @SuppressWarnings("unchecked") // Parsing raw Map from JSON following a known schema.
  private static ConcurrentMap<String, ConcurrentMap<String, Object>>
      asConcurrentMapOfConcurrentMaps(Object value) {
    return ((Map<String, Map<String, Object>>) value)
        .entrySet().stream()
            .collect(
                ConcurrentHashMap::new,
                (map, entry) -> map.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue())),
                ConcurrentHashMap::putAll);
  }

  @SuppressWarnings("unchecked") // Parsing raw Map from JSON following a known schema.
  private static ConcurrentMap<String, ToolConfirmation> asConcurrentMapOfToolConfirmations(
      Object value) {
    return ((Map<String, Object>) value)
        .entrySet().stream()
            .collect(
                ConcurrentHashMap::new,
                (map, entry) ->
                    map.put(
                        entry.getKey(),
                        objectMapper.convertValue(entry.getValue(), ToolConfirmation.class)),
                ConcurrentHashMap::putAll);
  }

  private static void putIfNotEmpty(Map<String, Object> map, String key, Map<?, ?> values) {
    if (values != null && !values.isEmpty()) {
      map.put(key, values);
    }
  }

  private static void putIfNotEmpty(
      Map<String, Object> map, String key, Optional<? extends Collection<?>> values) {
    values.ifPresent(v -> putIfNotEmpty(map, key, v));
  }

  private static void putIfNotEmpty(
      Map<String, Object> map, String key, @Nullable Collection<?> values) {
    if (values != null && !values.isEmpty()) {
      map.put(key, values);
    }
  }
}
