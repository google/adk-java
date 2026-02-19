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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** A {@link State} object that also keeps track of the changes to the state. */
@SuppressWarnings("ShouldNotSubclass") // Implementing Map is the desired interface for State.
public final class State implements Map<String, Object> {

  public static final String APP_PREFIX = "app:";
  public static final String USER_PREFIX = "user:";
  public static final String TEMP_PREFIX = "temp:";

  /** Sentinel object to mark removed entries in the delta map. */
  public static final Object REMOVED = null;

  /** The underlying state map. A Map that supports null values as "removed". */
  private final Map<String, Object> state;

  /** The delta map. A Map that supports null values as "removed". */
  private final Map<String, Object> delta;

  public State() {
    this(null, null);
  }

  public State(@Nullable Map<String, Object> state) {
    this(state, null);
  }

  /**
   * Creates a {@link State} object with the given state and delta maps.
   *
   * @param state the underlying state map. The Map needs to accept null values.
   * @param delta the delta map. The Map needs to accept null values.
   */
  public State(@Nullable Map<String, Object> state, @Nullable Map<String, Object> delta) {
    this.state = Optional.ofNullable(state).orElseGet(State::createSynchronizedHashMap);
    this.delta = Optional.ofNullable(delta).orElseGet(State::createSynchronizedHashMap);
  }

  /** Creates a new synchronized {@link HashMap}. */
  private static Map<String, Object> createSynchronizedHashMap() {
    return Collections.synchronizedMap(new HashMap<>());
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
      delta.put((String) key, null);
    }
    return state.remove(key);
  }

  @Override
  public boolean remove(Object key, Object value) {
    boolean removed = state.remove(key, value);
    if (removed) {
      delta.put((String) key, null);
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
}
