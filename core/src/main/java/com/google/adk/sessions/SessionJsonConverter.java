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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.events.ToolConfirmation;
import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.StringList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utilities for converting session events to and from Vertex AI Sessions API JSON.
 *
 * <p>Events are stored as Python ADK stores them: the typed fields carry what the API keeps, and
 * {@code rawEvent} carries a copy of the event in the shape Python ADK reads.
 */
final class SessionJsonConverter {
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  /**
   * Maps event JSON stored in API {@code Struct}s as Python ADK does: null fields, but not null map
   * values, are left out on write, and the URL-safe base64 Python ADK writes is accepted on read.
   */
  private static final ObjectMapper storedJsonMapper =
      objectMapper
          .copy()
          .setDefaultPropertyInclusion(
              JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
          .registerModule(
              new SimpleModule().addDeserializer(byte[].class, new LenientBase64Deserializer()));

  private static final Logger logger = LoggerFactory.getLogger(SessionJsonConverter.class);

  // customMetadata keys under which Python ADK stores fields the API does not keep.
  private static final String USAGE_METADATA_KEY = "_usage_metadata";
  private static final String COMPACTION_KEY = "_compaction";

  /**
   * {@code EventActions} keys Python ADK rejects ({@code setModelResponse} only before 2.x), left
   * out of {@code rawEvent} so that it can read the event.
   */
  private static final ImmutableSet<String> PYTHON_UNSUPPORTED_ACTION_KEYS =
      ImmutableSet.of("deletedArtifactIds", "setModelResponse");

  /**
   * Keys holding user data ({@code FunctionCall.args}, {@code FunctionResponse.response} and {@code
   * Part.partMetadata}), whose nested keys {@link #snakeToCamelKeys} leaves as written.
   */
  private static final ImmutableSet<String> USER_DATA_KEYS =
      ImmutableSet.of("args", "response", "part_metadata", "partMetadata");

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
    EventActions actions = event.actions();
    Map<String, Object> metadataJson = new HashMap<>();
    event.partial().ifPresent(v -> metadataJson.put("partial", v));
    event.turnComplete().ifPresent(v -> metadataJson.put("turnComplete", v));
    event.interrupted().ifPresent(v -> metadataJson.put("interrupted", v));
    event.branch().ifPresent(v -> metadataJson.put("branch", v));
    event.longRunningToolIds().ifPresent(v -> putIfNotEmpty(metadataJson, "longRunningToolIds", v));
    event.groundingMetadata().ifPresent(v -> metadataJson.put("groundingMetadata", v));
    Map<String, Object> customMetadataJson =
        event
            .customMetadata()
            .map(SessionJsonConverter::customMetadataToJson)
            .orElseGet(LinkedHashMap::new);
    event.usageMetadata().ifPresent(v -> customMetadataJson.put(USAGE_METADATA_KEY, v));
    if (actions != null) {
      actions
          .compaction()
          .ifPresent(v -> customMetadataJson.put(COMPACTION_KEY, compactionToJson(v)));
    }
    putIfNotEmpty(metadataJson, "customMetadata", customMetadataJson);
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

    if (actions != null) {
      // The API keeps only these actions fields and drops the rest; rawEvent carries them.
      Map<String, Object> actionsJson = new HashMap<>();
      actions.skipSummarization().ifPresent(v -> actionsJson.put("skipSummarization", v));
      actionsJson.put("stateDelta", stateDeltaToJson(actions.stateDelta()));
      putIfNotEmpty(actionsJson, "artifactDelta", actions.artifactDelta());
      actions.transferToAgent().ifPresent(v -> actionsJson.put("transferAgent", v));
      actions.escalate().ifPresent(v -> actionsJson.put("escalate", v));
      putIfNotEmpty(actionsJson, "requestedAuthConfigs", actions.requestedAuthConfigs());
      eventJson.put("actions", actionsJson);
    }
    event.content().ifPresent(c -> eventJson.put("content", SessionUtils.encodeContent(c)));
    try {
      eventJson.put("rawEvent", rawEventToJson(event));
      return objectMapper.writeValueAsString(eventJson);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Serializes the event in the shape Python ADK writes to and reads from {@code rawEvent}. */
  private static Map<String, Object> rawEventToJson(Event event) throws JsonProcessingException {
    Map<String, Object> rawEvent =
        storedJsonMapper.readValue(
            storedJsonMapper.writeValueAsString(event),
            new TypeReference<Map<String, Object>>() {});
    rawEvent.put("timestamp", millisToSeconds(event.timestamp()));
    event.customMetadata().ifPresent(v -> rawEvent.put("customMetadata", customMetadataToJson(v)));
    EventActions actions = event.actions();
    if (rawEvent.get("actions") instanceof Map<?, ?> rawActions) {
      @SuppressWarnings("unchecked") // Jackson maps a JSON object to Map<String, Object>.
      Map<String, Object> actionsJson = (Map<String, Object>) rawActions;
      actionsJson.keySet().removeAll(PYTHON_UNSUPPORTED_ACTION_KEYS);
      actionsJson.put("stateDelta", stateDeltaToJson(actions.stateDelta()));
      actions.compaction().ifPresent(v -> actionsJson.put("compaction", compactionToJson(v)));
    }
    return rawEvent;
  }

  /** Maps {@code customMetadata} entries to the key-value object Python ADK stores. */
  private static Map<String, Object> customMetadataToJson(List<CustomMetadata> entries) {
    Map<String, Object> json = new LinkedHashMap<>();
    for (CustomMetadata entry : entries) {
      entry.key().ifPresent(key -> json.put(key, customMetadataValue(entry)));
    }
    return json;
  }

  private static @Nullable Object customMetadataValue(CustomMetadata entry) {
    return entry
        .stringValue()
        .<Object>map(value -> value)
        .or(entry::numericValue)
        .or(() -> entry.stringListValue().flatMap(StringList::values))
        .orElse(null);
  }

  /** Serializes a compaction with the epoch-second timestamps Python ADK uses. */
  private static ImmutableMap<String, Object> compactionToJson(EventCompaction compaction) {
    return ImmutableMap.of(
        "startTimestamp", millisToSeconds(compaction.startTimestamp()),
        "endTimestamp", millisToSeconds(compaction.endTimestamp()),
        "compactedContent", compaction.compactedContent());
  }

  private static double millisToSeconds(long millis) {
    return millis / 1000.0;
  }

  /** Floors to millis, as event timestamps are; rounding to micros first cancels float error. */
  private static long secondsToMillis(Number seconds) {
    return Math.floorDiv(Math.round(seconds.doubleValue() * 1_000_000), 1000);
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
   * <p>A readable {@code rawEvent} takes precedence over the typed fields, which remain the
   * fallback when {@code rawEvent} is missing or unreadable.
   *
   * @return parsed {@link Event}.
   */
  static Event fromApiEvent(Map<String, Object> apiEvent) {
    if (apiEvent.get("rawEvent") instanceof Map<?, ?> rawEvent && !rawEvent.isEmpty()) {
      long timestampMillis = convertToInstant(apiEvent.get("timestamp")).toEpochMilli();
      try {
        return fromRawEvent(apiEvent, rawEvent, timestampMillis);
      } catch (IllegalArgumentException e) {
        logger.warn(
            "Ignoring unreadable rawEvent ({}), using the typed event fields",
            Throwables.getRootCause(e).getClass().getSimpleName());
      }
    }
    return fromTypedFields(apiEvent);
  }

  private static Event fromRawEvent(
      Map<String, Object> apiEvent, Map<?, ?> rawEvent, long timestampMillis) {
    Map<Object, Object> eventJson = new HashMap<>(rawEvent);
    eventJson.put("invocationId", apiEvent.get("invocationId"));
    eventJson.put("author", apiEvent.get("author"));
    eventJson.put("timestamp", timestampMillis);
    // As in Python ADK 2.x, keep the stored id so a reloaded event matches the streamed one.
    if (!(eventJson.get("id") instanceof String id) || id.isEmpty()) {
      eventJson.put("id", eventIdFromName(apiEvent));
    }
    if (eventJson.get("customMetadata") instanceof Map<?, ?> customMetadata) {
      eventJson.put("customMetadata", customMetadataFromJson(customMetadata));
    }
    if (eventJson.get("actions") instanceof Map<?, ?> actions
        && actions.containsKey("compaction")) {
      Map<Object, Object> actionsJson = new HashMap<>(actions);
      actionsJson.put("compaction", compactionWithMillis(actionsJson.get("compaction")));
      eventJson.put("actions", actionsJson);
    }
    return storedJsonMapper.convertValue(eventJson, Event.class);
  }

  /** Reads the key-value {@code customMetadata} object, skipping the keys Python ADK reserves. */
  private static @Nullable ImmutableList<CustomMetadata> customMetadataFromJson(Map<?, ?> json) {
    ImmutableList<CustomMetadata> entries =
        json.entrySet().stream()
            .filter(
                e -> !USAGE_METADATA_KEY.equals(e.getKey()) && !COMPACTION_KEY.equals(e.getKey()))
            .map(e -> customMetadataEntry(String.valueOf(e.getKey()), e.getValue()))
            .collect(toImmutableList());
    return entries.isEmpty() ? null : entries;
  }

  private static CustomMetadata customMetadataEntry(String key, @Nullable Object value) {
    CustomMetadata.Builder entry = CustomMetadata.builder().key(key);
    if (value instanceof String string) {
      entry.stringValue(string);
    } else if (value instanceof Number number) {
      entry.numericValue(number.floatValue());
    } else if (value instanceof List<?> list) {
      entry.stringListValue(
          StringList.builder()
              .values(
                  list.stream()
                      .map(
                          item ->
                              item instanceof String string
                                  ? string
                                  : JsonBaseModel.toJsonString(item))
                      .collect(toImmutableList()))
              .build());
    } else if (value != null) {
      // CustomMetadata has no boolean or object value, so keep their JSON text.
      entry.stringValue(JsonBaseModel.toJsonString(value));
    }
    return entry.build();
  }

  /** Converts a compaction's epoch-second timestamps, as Python ADK writes them, to millis. */
  private static @Nullable Object compactionWithMillis(@Nullable Object compaction) {
    if (!(compaction instanceof Map<?, ?> json)
        || !(json.get("startTimestamp") instanceof Number start)
        || !(json.get("endTimestamp") instanceof Number end)) {
      return compaction;
    }
    Map<Object, Object> converted = new HashMap<>(json);
    converted.put("startTimestamp", secondsToMillis(start));
    converted.put("endTimestamp", secondsToMillis(end));
    return converted;
  }

  /** Camel-cases the snake_case keys Python ADK writes in {@code customMetadata} values. */
  private static @Nullable Object snakeToCamelKeys(@Nullable Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object nested = entry.getValue();
        converted.put(
            snakeToCamel(key), USER_DATA_KEYS.contains(key) ? nested : snakeToCamelKeys(nested));
      }
      return converted;
    }
    if (value instanceof List<?> list) {
      List<Object> converted = new ArrayList<>();
      for (Object nested : list) {
        converted.add(snakeToCamelKeys(nested));
      }
      return converted;
    }
    return value;
  }

  private static String snakeToCamel(String key) {
    // CaseFormat would lowercase a key that is already camelCase.
    return key.indexOf('_') < 0 ? key : CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, key);
  }

  /** Returns the value of {@code key} in the typed actions, or else in {@code rawEvent.actions}. */
  private static @Nullable Object actionsValue(
      @Nullable Map<String, Object> actions, Map<?, ?> rawActions, String key) {
    Object value = actions == null ? null : actions.get(key);
    return value != null ? value : rawActions.get(key);
  }

  private static String eventIdFromName(Map<String, Object> apiEvent) {
    return Iterables.getLast(Splitter.on('/').split(apiEvent.get("name").toString()));
  }

  /** Converts a stored value, or returns null so that one bad value cannot fail a load. */
  private static <T> @Nullable T convertStoredValue(
      @Nullable Object value, TypeReference<T> type, String field) {
    if (value == null) {
      return null;
    }
    try {
      return storedJsonMapper.convertValue(value, type);
    } catch (IllegalArgumentException e) {
      logger.warn(
          "Ignoring unreadable '{}' ({})",
          field,
          Throwables.getRootCause(e).getClass().getSimpleName());
      return null;
    }
  }

  @SuppressWarnings("unchecked") // Parsing raw Map from JSON following a known schema.
  private static Event fromTypedFields(Map<String, Object> apiEvent) {
    Map<String, Object> eventMetadata = (Map<String, Object>) apiEvent.get("eventMetadata");
    Map<String, Object> customMetadata =
        eventMetadata != null && eventMetadata.get("customMetadata") instanceof Map<?, ?> json
            ? (Map<String, Object>) json
            : ImmutableMap.of();

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
      eventActionsBuilder.requestedAuthConfigs(
          Optional.ofNullable(actionsMap.get("requestedAuthConfigs"))
              .map(SessionJsonConverter::asConcurrentMapOfConcurrentMaps)
              .orElse(new ConcurrentHashMap<>()));
    }
    // The API drops these typed fields; an unreadable rawEvent may still carry them.
    Map<?, ?> rawActions =
        apiEvent.get("rawEvent") instanceof Map<?, ?> rawEvent
                && rawEvent.get("actions") instanceof Map<?, ?> actions
            ? actions
            : ImmutableMap.of();
    Map<String, ToolConfirmation> toolConfirmations =
        convertStoredValue(
            actionsValue(actionsMap, rawActions, "requestedToolConfirmations"),
            new TypeReference<Map<String, ToolConfirmation>>() {},
            "requestedToolConfirmations");
    if (toolConfirmations != null) {
      eventActionsBuilder.requestedToolConfirmations(
          Maps.filterValues(toolConfirmations, Objects::nonNull));
    }
    Object endOfAgent = actionsValue(actionsMap, rawActions, "endOfAgent");
    if (endOfAgent instanceof Boolean value) {
      eventActionsBuilder.endOfAgent(value);
    } else if (endOfAgent != null) {
      logger.warn("Ignoring 'endOfAgent' of unexpected type {}", endOfAgent.getClass().getName());
    }
    Object agentState = actionsValue(actionsMap, rawActions, "agentState");
    if (agentState instanceof Map<?, ?> state) {
      eventActionsBuilder.agentState((Map<String, Object>) state);
    } else if (agentState != null) {
      // Drop a foreign agentState rather than fail the whole session load on a bad cast.
      logger.warn("Ignoring 'agentState' of unexpected type {}", agentState.getClass().getName());
    }
    eventActionsBuilder.compaction(
        convertStoredValue(
            compactionWithMillis(snakeToCamelKeys(customMetadata.get(COMPACTION_KEY))),
            new TypeReference<EventCompaction>() {},
            COMPACTION_KEY));

    Event event =
        Event.builder()
            .id(eventIdFromName(apiEvent))
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
                    .map(value -> new FinishReason((String) value))
                    .orElse(null))
            .errorMessage(
                Optional.ofNullable(apiEvent.get("errorMessage"))
                    .map(value -> (String) value)
                    .orElse(null))
            .build();
    if (eventMetadata != null) {
      List<String> longRunningToolIdsList = (List<String>) eventMetadata.get("longRunningToolIds");

      GroundingMetadata groundingMetadata = null;
      Object rawGroundingMetadata = eventMetadata.get("groundingMetadata");
      if (rawGroundingMetadata != null) {
        groundingMetadata =
            objectMapper.convertValue(rawGroundingMetadata, GroundingMetadata.class);
      }
      Object storedUsageMetadata = customMetadata.get(USAGE_METADATA_KEY);
      GenerateContentResponseUsageMetadata usageMetadata =
          convertStoredValue(
              storedUsageMetadata != null
                  ? snakeToCamelKeys(storedUsageMetadata)
                  : eventMetadata.get("usageMetadata"),
              new TypeReference<GenerateContentResponseUsageMetadata>() {},
              "usageMetadata");

      event =
          event.toBuilder()
              .partial(Optional.ofNullable((Boolean) eventMetadata.get("partial")).orElse(false))
              .turnComplete(
                  Optional.ofNullable((Boolean) eventMetadata.get("turnComplete")).orElse(false))
              .interrupted(
                  Optional.ofNullable((Boolean) eventMetadata.get("interrupted")).orElse(false))
              .branch((String) eventMetadata.get("branch"))
              .groundingMetadata(groundingMetadata)
              .usageMetadata(usageMetadata)
              .customMetadata(customMetadataFromJson(customMetadata))
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

  private static void putIfNotEmpty(Map<String, Object> map, String key, Map<?, ?> values) {
    if (values != null && !values.isEmpty()) {
      map.put(key, values);
    }
  }

  private static void putIfNotEmpty(
      Map<String, Object> map, String key, @Nullable Collection<?> values) {
    if (values != null && !values.isEmpty()) {
      map.put(key, values);
    }
  }

  /** A {@code byte[]} deserializer for standard base64 and the URL-safe base64 of Python ADK. */
  private static final class LenientBase64Deserializer extends StdDeserializer<byte[]> {
    LenientBase64Deserializer() {
      super(byte[].class);
    }

    @Override
    public byte[] deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      String text = parser.getValueAsString();
      if (text == null) {
        return (byte[]) context.handleUnexpectedToken(byte[].class, parser);
      }
      try {
        // Python ADK writes the URL-safe alphabet; map it to the standard one.
        return Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/'));
      } catch (IllegalArgumentException e) {
        // Leaves the value out of the message: it is event content.
        throw JsonMappingException.from(parser, "Invalid base64 value", e);
      }
    }
  }
}
