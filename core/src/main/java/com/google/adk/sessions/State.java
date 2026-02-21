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
    // Delta should likely be cleared too if we are clearing the state,
    // or we might want to mark everything as removed in delta.
    // Given the Python implementation doesn't have clear, and this is a local view,
    // clearing both seems appropriate to reset the object.
    delta.clear();
  }

  @Override
  public boolean containsKey(Object key) {
    if (delta.containsKey(key)) {
      return delta.get(key) != REMOVED;
    }
    return state.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    // This is expensive but necessary for correctness with the merged view.
    return values().contains(value);
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    // This provides a snapshot, not a live view backed by the map, which differs from standard Map
    // contract.
    // However, given the complexity of merging two concurrent maps, this is a reasonable compromise
    // for this specific implementation.
    // TODO: Consider implementing a live view if needed.
    Map<String, Object> merged = new ConcurrentHashMap<>(state);
    for (Entry<String, Object> entry : delta.entrySet()) {
      if (entry.getValue() == REMOVED) {
        merged.remove(entry.getKey());
      } else {
        merged.put(entry.getKey(), entry.getValue());
      }
    }
    return merged.entrySet();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Map)) {
      return false;
    }
    Map<?, ?> other = (Map<?, ?>) o;
    // We can't easily rely on state.equals() because our "content" is merged.
    // Validating equality against another Map requires checking the merged view.
    if (size() != other.size()) {
      return false;
    }
    try {
      for (Entry<String, Object> e : entrySet()) {
        String key = e.getKey();
        Object value = e.getValue();
        if (value == null) {
          if (!(other.get(key) == null && other.containsKey(key))) return false;
        } else {
          if (!value.equals(other.get(key))) return false;
        }
      }
    } catch (ClassCastException | NullPointerException unused) {
      return false;
    }
    return true;
  }

  @Override
  public Object get(Object key) {
    if (delta.containsKey(key)) {
      Object value = delta.get(key);
      return value == REMOVED ? null : value;
    }
    return state.get(key);
  }

  @Override
  public int hashCode() {
    // Similar to equals, we need to calculate hash code based on the merged entry set.
    int h = 0;
    for (Entry<String, Object> entry : entrySet()) {
      h += entry.hashCode();
    }
    return h;
  }

  @Override
  public boolean isEmpty() {
    if (delta.isEmpty()) {
      return state.isEmpty();
    }
    // If delta is not empty, we need to check if it effectively removes everything from state
    // or adds something.
    return size() == 0;
  }

  @Override
  public Set<String> keySet() {
    // Snapshot view
    Map<String, Object> merged = new ConcurrentHashMap<>(state);
    for (Entry<String, Object> entry : delta.entrySet()) {
      if (entry.getValue() == REMOVED) {
        merged.remove(entry.getKey());
      } else {
        merged.put(entry.getKey(), entry.getValue());
      }
    }
    return merged.keySet();
  }

  @Override
  public Object put(String key, Object value) {
    // Current value logic needs to check delta first to return correct "oldValue"
    Object oldValue = get(key);
    state.put(key, value);
    delta.put(key, value);
    return oldValue;
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    Object currentValue = get(key);
    if (currentValue == null) {
      put(key, value);
      return null;
    }
    return currentValue;
  }

  @Override
  public void putAll(Map<? extends String, ? extends Object> m) {
    state.putAll(m);
    delta.putAll(m);
  }

  @Override
  public Object remove(Object key) {
    Object oldValue = get(key);
    // We explicitly check for containment in the merged view to ensure we return the correct old
    // value.
    if (state.containsKey(key) || (delta.containsKey(key) && delta.get(key) != REMOVED)) {
      delta.put((String) key, REMOVED);
    }

    // We remove from the state map to keep it consistent with the write-through behavior of this
    // class.
    state.remove(key);
    return oldValue;
  }

  @Override
  public boolean remove(Object key, Object value) {
    Object currentValue = get(key);
    if (Objects.equals(currentValue, value) && (currentValue != null || containsKey(key))) {
      remove(key);
      return true;
    }
    return false;
  }

  @Override
  public boolean replace(String key, Object oldValue, Object newValue) {
    Object currentValue = get(key);
    if (Objects.equals(currentValue, oldValue) && (currentValue != null || containsKey(key))) {
      put(key, newValue);
      return true;
    }
    return false;
  }

  @Override
  public Object replace(String key, Object value) {
    Object currentValue = get(key);
    if (currentValue != null || containsKey(key)) {
      put(key, value);
      return currentValue;
    }
    return null;
  }

  @Override
  public int size() {
    // Expensive, but accurate merged size.
    return entrySet().size();
  }

  @Override
  public Collection<Object> values() {
    // Snapshot view
    Map<String, Object> merged = new ConcurrentHashMap<>(state);
    for (Entry<String, Object> entry : delta.entrySet()) {
      if (entry.getValue() == REMOVED) {
        merged.remove(entry.getKey());
      } else {
        merged.put(entry.getKey(), entry.getValue());
      }
    }
    return merged.values();
  }

  public boolean hasDelta() {
    return !delta.isEmpty();
  }

  private static final class RemovedSentinel {
    public static final RemovedSentinel INSTANCE = new RemovedSentinel();

    private RemovedSentinel() {
      // Enforce singleton.
    }

    @JsonValue
    public String toJson() {
      return "__ADK_SENTINEL_REMOVED__";
    }
  }
}
