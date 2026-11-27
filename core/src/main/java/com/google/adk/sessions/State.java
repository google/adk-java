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
import com.google.adk.annotations.Experimental;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Session state that also records each write made through {@link #put(String, Object)}, {@link
 * #remove(Object)} and the other write methods in a delta. Writes through the {@link #entrySet()},
 * {@link #keySet()} and {@link #values()} views and {@link #clear()} skip the delta, so no event
 * carries them; in the {@link com.google.adk.agents.Context#state()} of a workflow node, and in a
 * tool context under a state schema, they throw {@link UnsupportedOperationException}.
 */
@SuppressWarnings("ShouldNotSubclass")
public final class State implements ConcurrentMap<String, Object> {

  public static final String APP_PREFIX = "app:";
  public static final String USER_PREFIX = "user:";
  public static final String TEMP_PREFIX = "temp:";

  /** Sentinel object to mark removed entries in the delta map. */
  public static final Object REMOVED = RemovedSentinel.INSTANCE;

  private final ConcurrentMap<String, Object> state;
  private final ConcurrentMap<String, Object> delta;
  private final boolean delegatesCompute;

  public State(Map<String, Object> state) {
    this(state, null);
  }

  public State(Map<String, Object> state, @Nullable Map<String, Object> delta) {
    this(state, delta, /* delegatesCompute= */ false);
  }

  private State(
      Map<String, Object> state, @Nullable Map<String, Object> delta, boolean delegatesCompute) {
    Objects.requireNonNull(state, "state is null");
    this.state = toConcurrentMap(state);
    this.delta = delta == null ? new ConcurrentHashMap<>() : toConcurrentMap(delta);
    this.delegatesCompute = delegatesCompute;
  }

  /**
   * Returns a state whose {@code compute*} and {@code merge} methods call {@code state}'s own, so
   * they return what {@code state} stores. {@code state} must record those writes in {@code delta}
   * itself, which this state shares; the other write methods behave as in {@link #State(Map, Map)}.
   */
  @Experimental
  public static State delegatingCompute(
      ConcurrentMap<String, Object> state, ConcurrentMap<String, Object> delta) {
    Objects.requireNonNull(delta, "delta is null");
    return new State(state, delta, /* delegatesCompute= */ true);
  }

  /**
   * Converts a map to a concurrent map. Null values are converted to {@link #REMOVED} to avoid
   * NPEs.
   *
   * <p>If the map is already a concurrent map, it is returned as is. Otherwise, a new concurrent
   * map is created and returned.
   */
  private static ConcurrentMap<String, Object> toConcurrentMap(Map<String, Object> map) {
    if (map instanceof ConcurrentMap) {
      return (ConcurrentMap<String, Object>) map;
    }
    ConcurrentMap<String, Object> concurrentMap = new ConcurrentHashMap<>();
    map.forEach((key, value) -> concurrentMap.put(key, Optional.ofNullable(value).orElse(REMOVED)));
    return concurrentMap;
  }

  /**
   * Clears the backing map without recording removals in the delta.
   *
   * @throws UnsupportedOperationException if this state belongs to a workflow node's context, or to
   *     a tool context under a state schema
   */
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

  /**
   * Returns the entries view; read-only in a workflow node or a tool context under a state schema,
   * and writes elsewhere skip the delta.
   */
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
    // Compares entries, matching hashCode(): the backing map may be a State or a wrapper.
    return state.entrySet().equals(other.state.entrySet());
  }

  @Override
  public Object get(Object key) {
    return state.get(key);
  }

  @Override
  public int hashCode() {
    return state.entrySet().hashCode();
  }

  @Override
  public boolean isEmpty() {
    return state.isEmpty();
  }

  /**
   * Returns the key set view; read-only in a workflow node or a tool context under a state schema,
   * and writes elsewhere skip the delta.
   */
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

  @CanIgnoreReturnValue
  @Override
  public @Nullable Object compute(
      String key,
      BiFunction<? super String, ? super @Nullable Object, ? extends @Nullable Object>
          remappingFunction) {
    return delegatesCompute
        ? state.compute(key, remappingFunction)
        : ConcurrentMap.super.compute(key, remappingFunction);
  }

  @CanIgnoreReturnValue
  @Override
  public @Nullable Object computeIfAbsent(
      String key, Function<? super String, ? extends @Nullable Object> mappingFunction) {
    return delegatesCompute
        ? state.computeIfAbsent(key, mappingFunction)
        : ConcurrentMap.super.computeIfAbsent(key, mappingFunction);
  }

  @CanIgnoreReturnValue
  @Override
  public @Nullable Object computeIfPresent(
      String key,
      BiFunction<? super String, ? super Object, ? extends @Nullable Object> remappingFunction) {
    return delegatesCompute
        ? state.computeIfPresent(key, remappingFunction)
        : ConcurrentMap.super.computeIfPresent(key, remappingFunction);
  }

  @CanIgnoreReturnValue
  @Override
  public @Nullable Object merge(
      String key,
      Object value,
      BiFunction<? super Object, ? super Object, ? extends @Nullable Object> remappingFunction) {
    return delegatesCompute
        ? state.merge(key, value, remappingFunction)
        : ConcurrentMap.super.merge(key, value, remappingFunction);
  }

  @Override
  public int size() {
    return state.size();
  }

  /**
   * Returns the values view; read-only in a workflow node or a tool context under a state schema,
   * and writes elsewhere skip the delta.
   */
  @Override
  public Collection<Object> values() {
    return state.values();
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
