/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * JSON boundary and scope rules shared by all JDBC dialects. Never mutates caller-owned data.
 *
 * <p>Two things are stored as JSON text: state documents and events.
 *
 * <ul>
 *   <li>A <b>state document</b> is a JSON object for one scope: an app, a user, or a session. Its
 *       keys carry no scope prefix; the table it is in says which scope it is. A key is either
 *       present with a value or absent, so a top-level {@code null} is never written and, if found,
 *       is read as absent. Inside a value, {@code null} is ordinary data.
 *   <li>An <b>event</b> is the whole Java {@link Event}, written with ADK's own mapper so that it
 *       reads back equal. One part is written differently, its state delta: {@code temp:} keys are
 *       left out, and a removed key is written as {@code null}.
 * </ul>
 *
 * <p>The methods come in the order data flows: a caller's map is split into scoped snapshots,
 * snapshots are applied to stored documents and written, stored documents are read and merged into
 * the map a caller sees.
 *
 * <p>Failures carry a fixed message and no cause: a Jackson message can quote the value it failed
 * on. Instances are immutable and safe to share between threads.
 */
final class SessionDataCodec {
  /**
   * Name of the per-call attribute that holds the event being written. The delta serializer
   * compares it with the object it is nested in, to act on that event's own delta only.
   */
  private static final String EVENT_ROOT = "com.google.adk.sessions.persistedEvent";

  /** A JSON object as a map that keeps the order of its keys. */
  private static final TypeReference<LinkedHashMap<String, Object>> STATE_TYPE =
      new TypeReference<>() {};

  // Configure a private copy once: changing ADK's shared mapper would affect unrelated services.
  private final ObjectMapper mapper = JsonBaseModel.getMapper().copy();
  // Event-only rules must not change how arbitrary model objects inside state are encoded.
  private final ObjectWriter eventWriter =
      mapper.copy().addMixIn(EventActions.class, PersistedActions.class).writer();
  // Text after the document, such as a second object, means the column does not hold what was
  // written; fail instead of reading only its beginning.
  private final ObjectReader treeReader =
      mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ObjectReader eventReader =
      mapper.readerFor(Event.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /**
   * Scoped delta snapshots. Only the outermost prefix is a scope; suffixes are literal keys.
   *
   * <p>So {@code app:temp:x} is the app's key {@code temp:x}. Each map is unmodifiable, holds
   * values in their JSON shape (maps, lists, strings, numbers, booleans), and marks a key to remove
   * with {@link State#REMOVED}.
   *
   * @param app the {@code app:} keys without the prefix
   * @param user the {@code user:} keys without the prefix
   * @param local the keys without a scope prefix
   */
  record ScopedState(
      Map<String, Object> app, Map<String, Object> user, Map<String, Object> local) {}

  /**
   * A safe codec message from a storage operation, not an arbitrary driver SessionException.
   *
   * <p>The store passes the message of this type on to the caller as it is. Any other {@link
   * SessionException} that surfaces during a transaction, for instance one thrown by the
   * application's data source, is not trusted that way and gets a message of the store's own.
   */
  static final class StorageFailure extends SessionException {
    private StorageFailure(String message) {
      super(message);
    }
  }

  /**
   * Splits a combined state or an event's state delta by scope, and takes a snapshot of it.
   *
   * <p>The values are put through JSON once, here. That detaches them from the caller's objects,
   * which may change while a transaction runs, gives them the shape they will have when read back,
   * and fails now, before any connection is borrowed, if a value cannot be written. {@code temp:}
   * entries are dropped before that, so their values are never looked at.
   *
   * @param state keys with scope prefixes; {@code null} is the same as empty
   * @throws SessionException if a value cannot be written as JSON
   */
  ScopedState splitState(@Nullable Map<String, Object> state) {
    try {
      Map<String, Object> app = new LinkedHashMap<>();
      Map<String, Object> user = new LinkedHashMap<>();
      Map<String, Object> local = new LinkedHashMap<>();
      if (state != null) {
        state.forEach(
            (key, value) -> {
              if (key.startsWith(State.APP_PREFIX)) {
                app.put(key.substring(State.APP_PREFIX.length()), value);
              } else if (key.startsWith(State.USER_PREFIX)) {
                user.put(key.substring(State.USER_PREFIX.length()), value);
              } else if (!key.startsWith(State.TEMP_PREFIX)) {
                local.put(key, value);
              }
            });
      }
      return new ScopedState(snapshotDelta(app), snapshotDelta(user), snapshotDelta(local));
    } catch (JsonProcessingException | RuntimeException e) {
      throw new SessionException("Unable to split session state.");
    }
  }

  /**
   * Writes an already scoped document, not a combined prefixed state or an event delta.
   *
   * <p>Entries whose value is {@code null} or {@link State#REMOVED} are left out: in a stored
   * document a key that is not there is how a removed key looks.
   */
  String writeState(Map<String, Object> state) {
    try {
      return mapper.writeValueAsString(activeValues(state));
    } catch (JsonProcessingException | RuntimeException e) {
      throw new StorageFailure("Unable to encode session state.");
    }
  }

  /**
   * Reads an active document. Only top-level nulls mean absence; nested nulls are user data.
   *
   * @param json the text of a state column
   * @return a new, mutable map that nothing else refers to
   * @throws StorageFailure if the text is {@code null}, not a single JSON object, or not JSON; a
   *     NULL column therefore fails rather than reading as an empty state
   */
  Map<String, Object> readState(@Nullable String json) {
    try {
      return activeValues(readObject(json));
    } catch (JsonProcessingException | RuntimeException e) {
      throw new StorageFailure("Unable to decode session state.");
    }
  }

  /**
   * Applies a scoped delta as replacement/deletion, not recursive map merging.
   *
   * <p>Both arguments are this codec's own snapshots, {@code current} from {@link #readState} and
   * {@code delta} from {@link #splitState}, so their values already have their JSON shape. The
   * result is a new map over those same values, meant only for {@link #writeState}; encoding and
   * decoding it once more here would double the cost of every state write.
   */
  Map<String, Object> applyState(Map<String, Object> current, @Nullable Map<String, Object> delta) {
    try {
      Map<String, Object> updated = activeValues(current);
      if (delta != null) {
        delta.forEach(
            (key, value) -> {
              if (value == null || value == State.REMOVED) {
                updated.remove(key);
              } else {
                updated.put(key, value);
              }
            });
      }
      return updated;
    } catch (RuntimeException e) {
      throw new StorageFailure("Unable to apply session state.");
    }
  }

  /**
   * Builds a session view; shared documents are authoritative for their own scopes.
   *
   * <p>The view keeps the values of the three documents instead of copying them through JSON a
   * second time. Pass documents decoded by {@link #readState} for this one view, so that no two
   * sessions end up sharing a nested value.
   */
  ConcurrentHashMap<String, Object> mergeState(
      Map<String, Object> app, Map<String, Object> user, Map<String, Object> local) {
    try {
      ConcurrentHashMap<String, Object> combined = new ConcurrentHashMap<>();
      // The session's own document should hold no prefixed keys. If one is there all the same,
      // it must not hide or resurrect a shared key, so it is skipped and the shared documents,
      // added next, decide what the prefixed keys are.
      activeValues(local)
          .forEach(
              (key, value) -> {
                if (!key.startsWith(State.APP_PREFIX)
                    && !key.startsWith(State.USER_PREFIX)
                    && !key.startsWith(State.TEMP_PREFIX)) {
                  combined.put(key, value);
                }
              });
      activeValues(app).forEach((key, value) -> combined.put(State.APP_PREFIX + key, value));
      activeValues(user).forEach((key, value) -> combined.put(State.USER_PREFIX + key, value));
      return combined;
    } catch (RuntimeException e) {
      throw new StorageFailure("Unable to merge session state.");
    }
  }

  /**
   * Stores the full Java event, with a narrowly scoped state-delta serialization override.
   *
   * <p>The result is what {@code event.toJson()} produces, except inside the event's own state
   * delta, where {@code temp:} keys are left out and removals are written as {@code null}; see
   * {@link PersistedDeltaSerializer}. The event itself is not changed.
   *
   * @throws SessionException if the event has no ID, without which it could not be read back, or
   *     holds a value that cannot be written as JSON
   */
  String writeEvent(Event event) {
    try {
      if (event.id() == null) {
        throw new IllegalArgumentException();
      }
      // The attribute tells the delta serializer which object is the event being stored.
      return eventWriter.withAttribute(EVENT_ROOT, event).writeValueAsString(event);
    } catch (JsonProcessingException | RuntimeException e) {
      throw new SessionException("Unable to encode session event.");
    }
  }

  /**
   * Reads an event written by {@link #writeEvent}.
   *
   * @param json the text of an {@code event_data} column
   * @throws StorageFailure if the text is not one JSON object that holds an event with an ID and a
   *     timestamp; a NULL column, which the Python v1 layout allows, is such a case
   */
  Event readEvent(@Nullable String json) {
    try {
      requireEventIdentity(json);
      // EventActions.Builder restores top-level null delta values as State.REMOVED. A literal
      // sentinel-looking string is deliberately never interpreted as a deletion.
      // Decode the original bytes, not an intermediate tree: a floating-point JsonNode can lose
      // nanoseconds from typed metadata Instants before their native deserializer sees the value.
      Event restored = eventReader.readValue(json);
      // writeEvent never stores a temp: key. Should a row hold one anyway, written by something
      // else, it still must not come back as part of a loaded session.
      if (restored.actions() != null && restored.actions().stateDelta() != null) {
        restored.actions().stateDelta().keySet().removeIf(key -> key.startsWith(State.TEMP_PREFIX));
      }
      return restored;
    } catch (IOException | RuntimeException e) {
      throw new StorageFailure("Unable to decode session event.");
    }
  }

  /**
   * Requires a JSON object whose {@code id} is text and whose {@code timestamp} fits a long. The
   * native builder supplies the current wall clock when timestamp is missing; a damaged record must
   * fail, not silently become a different event when read from storage.
   *
   * <p>History is the largest payload of a session read, so this scans tokens instead of building a
   * second, full tree of every event. As in a tree, the last duplicate of a field decides.
   */
  private void requireEventIdentity(@Nullable String json) throws IOException {
    boolean id = false;
    boolean timestamp = false;
    try (JsonParser parser = mapper.getFactory().createParser(json)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new IllegalArgumentException();
      }
      // Walk the fields of the root object only. Each turn of the loop is at a field name,
      // looks at the first token of its value, and then steps over the rest of that value.
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        String field = parser.currentName();
        JsonToken value = parser.nextToken();
        if (field.equals("id")) {
          id = value == JsonToken.VALUE_STRING;
        } else if (field.equals("timestamp")) {
          // An integer that does not fit a long is reported as BIG_INTEGER.
          timestamp =
              value == JsonToken.VALUE_NUMBER_INT
                  && parser.getNumberType() != JsonParser.NumberType.BIG_INTEGER;
        }
        // Does nothing after a scalar; after the start of an object or array it moves to its
        // end, so that an "id" nested inside some other field is never taken for the event's.
        parser.skipChildren();
      }
      // The object must close, and nothing may follow it.
      if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
        throw new IllegalArgumentException();
      }
    }
    if (!id || !timestamp) {
      throw new IllegalArgumentException();
    }
  }

  /**
   * Copies one scope of a delta through JSON and back. Removals take a detour: the sentinel {@link
   * State#REMOVED} is not something JSON can carry, so it is written as {@code null} and restored
   * afterwards, which also turns a plain {@code null} value into a removal.
   */
  private Map<String, Object> snapshotDelta(Map<String, Object> delta)
      throws JsonProcessingException {
    Map<String, Object> normalized = new LinkedHashMap<>();
    delta.forEach((key, value) -> normalized.put(key, value == State.REMOVED ? null : value));
    Map<String, Object> snapshot = readObject(mapper.writeValueAsString(normalized));
    snapshot.replaceAll((key, value) -> value == null ? State.REMOVED : value);
    return Collections.unmodifiableMap(snapshot);
  }

  /** Copies the entries that have a value, dropping those that are {@code null} or removed. */
  private static Map<String, Object> activeValues(Map<String, Object> state) {
    Map<String, Object> active = new LinkedHashMap<>();
    state.forEach(
        (key, value) -> {
          if (value != null && value != State.REMOVED) {
            active.put(key, value);
          }
        });
    return active;
  }

  /** Parses one JSON object into maps, lists and scalar values. */
  private Map<String, Object> readObject(@Nullable String json) throws JsonProcessingException {
    return mapper.convertValue(readObjectNode(json), STATE_TYPE);
  }

  /** Parses text that must be exactly one JSON object: not an array, a scalar, or {@code null}. */
  private ObjectNode readObjectNode(@Nullable String json) throws JsonProcessingException {
    JsonNode node = treeReader.readValue(json);
    if (!(node instanceof ObjectNode object)) {
      throw new IllegalArgumentException();
    }
    return object;
  }

  /**
   * Jackson mix-in that gives {@link EventActions#stateDelta()} its own serializer in the event
   * writer, without touching the class in core or the mapper other code uses.
   */
  private abstract static class PersistedActions {
    @JsonProperty("stateDelta")
    @JsonSerialize(using = PersistedDeltaSerializer.class)
    abstract Map<String, Object> stateDelta();
  }

  /**
   * Writes the state delta of the event being stored: {@code temp:} entries are skipped without
   * looking at their values, and a removed key is written as {@code null}, which the builder of
   * {@link EventActions} turns back into {@link State#REMOVED} on reading.
   *
   * <p>It applies only to the delta of the stored event itself. An {@link EventActions} can also
   * turn up as plain data, inside state or a tool's output, and is then written as usual.
   */
  private static final class PersistedDeltaSerializer extends JsonSerializer<Map<String, Object>> {
    @Override
    public void serialize(
        Map<String, Object> delta, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      // The delta's own object is not open yet, so the current context is the EventActions
      // around it and the parent is whatever holds that. For the stored event's delta the
      // parent is the event itself, writing its "actions" field.
      JsonStreamContext parent = generator.getOutputContext().getParent();
      if (parent == null
          || parent.getCurrentValue() != provider.getAttribute(EVENT_ROOT)
          || !"actions".equals(parent.getCurrentName())) {
        // An EventActions model used as arbitrary tool/output data is not the root event delta.
        provider.defaultSerializeValue(delta, generator);
        return;
      }
      generator.writeStartObject();
      for (Map.Entry<String, Object> entry : delta.entrySet()) {
        // Filter before inspecting/serializing the value: temp state need not be JSON-serializable.
        if (entry.getKey().startsWith(State.TEMP_PREFIX)) {
          continue;
        }
        generator.writeFieldName(entry.getKey());
        Object value = entry.getValue();
        if (value == null || value == State.REMOVED) {
          generator.writeNull();
        } else {
          provider.defaultSerializeValue(value, generator);
        }
      }
      generator.writeEndObject();
    }
  }
}
