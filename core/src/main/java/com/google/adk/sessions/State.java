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

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** A {@link State} object that also keeps track of the changes to the state. */
@SuppressWarnings("ShouldNotSubclass")
public final class State implements ConcurrentMap<String, Object> {

  public static final String APP_PREFIX = "app:";
  public static final String USER_PREFIX = "user:";
  public static final String TEMP_PREFIX = "temp:";

  public static final String REMOVED_SENTINEL_STRING = "__ADK_SENTINEL_REMOVED__";

  /** Sentinel object to mark removed entries in the delta map. */
  public static final Object REMOVED = RemovedSentinel.INSTANCE;

  private final ConcurrentMap<String, Object> state;
  private final ConcurrentMap<String, Object> delta;

  public State(ConcurrentMap<String, Object> state) {
    this(state, new ConcurrentHashMap<>());
  }

  public State(ConcurrentMap<String, Object> state, ConcurrentMap<String, Object> delta) {
    this.state = Objects.requireNonNull(state);
    this.delta = delta;
  }

  @Override
  public void clear() {
    state.clear();
  }

  @Override
  public boolean containsKey(Object key) {
    return state.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return state.containsValue(value);
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    return state.entrySet();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof State other)) {
      return false;
    }
    return state.equals(other.state);
  }

  @Override
  public Object get(Object key) {
    return state.get(key);
  }

  @Override
  public int hashCode() {
    return state.hashCode();
  }

  @Override
  public boolean isEmpty() {
    return state.isEmpty();
  }

  @Override
  public Set<String> keySet() {
    return state.keySet();
  }

  @Override
  public Object put(String key, Object value) {
    Object oldValue = state.put(key, value);
    delta.put(key, value);
    return oldValue;
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    Object existingValue = state.putIfAbsent(key, value);
    if (existingValue == null) {
      delta.put(key, value);
    }
    return existingValue;
  }

  @Override
  public void putAll(Map<? extends String, ? extends Object> m) {
    state.putAll(m);
    delta.putAll(m);
  }

  @Override
  public Object remove(Object key) {
    if (state.containsKey(key)) {
      delta.put((String) key, REMOVED);
    }
    return state.remove(key);
  }

  /**
   * Removes a key from the state map without recording the removal in the delta map. This is
   * intended for internal use when rebuilding state from an event stream where the removal is
   * already known and doesn't need to be represented as a new change.
   *
   * @param key The key to remove.
   * @return The previous value associated with key, or null if there was no mapping for key.
   */
  @CanIgnoreReturnValue
  public Object removeWithoutDelta(Object key) {
    return state.remove(key);
  }

  @Override
  public boolean remove(Object key, Object value) {
    boolean removed = state.remove(key, value);
    if (removed) {
      delta.put((String) key, REMOVED);
    }
    return removed;
  }

  @Override
  public boolean replace(String key, Object oldValue, Object newValue) {
    boolean replaced = state.replace(key, oldValue, newValue);
    if (replaced) {
      delta.put(key, newValue);
    }
    return replaced;
  }

  @Override
  public Object replace(String key, Object value) {
    Object oldValue = state.replace(key, value);
    if (oldValue != null) {
      delta.put(key, value);
    }
    return oldValue;
  }

  @Override
  public int size() {
    return state.size();
  }

  @Override
  public Collection<Object> values() {
    return state.values();
  }

  public boolean hasDelta() {
    return !delta.isEmpty();
  }

  /**
   * Checks if a value represents a removed state entry, accounting for deserialization from JSON.
   *
   * @param value The value to check.
   * @return True if the value indicates removal, false otherwise.
   */
  public static boolean isRemoved(Object value) {
    return value == REMOVED || Objects.equals(value, REMOVED_SENTINEL_STRING);
  }

  private static final class RemovedSentinel {
    public static final RemovedSentinel INSTANCE = new RemovedSentinel();

    private RemovedSentinel() {
      // Enforce singleton.
    }

    @JsonValue
    public String toJson() {
      return REMOVED_SENTINEL_STRING;
    }
  }
}
