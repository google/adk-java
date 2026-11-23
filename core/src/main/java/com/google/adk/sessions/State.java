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
import com.google.common.collect.ForwardingMapEntry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Session state that records each write, including writes through {@link #entrySet()}, {@link
 * #keySet()}, {@link #values()} and {@link #clear()}, in a delta. In the {@link
 * com.google.adk.agents.Context#state()} of a workflow node, those views are read-only and {@link
 * #clear()} throws {@link UnsupportedOperationException}.
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

  public State(Map<String, Object> state) {
    this(state, null);
  }

  public State(Map<String, Object> state, @Nullable Map<String, Object> delta) {
    Objects.requireNonNull(state, "state is null");
    this.state = toConcurrentMap(state);
    this.delta = delta == null ? new ConcurrentHashMap<>() : toConcurrentMap(delta);
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
   * Clears the backing map and records a {@link #REMOVED} delta entry for each removed key,
   * including merged {@link #APP_PREFIX} and {@link #USER_PREFIX} keys (which deletes them across
   * sessions).
   *
   * @throws UnsupportedOperationException if this state belongs to a workflow node's context
   */
  @Override
  public void clear() {
    List<String> keys = new ArrayList<>(state.keySet());
    state.clear();
    for (String key : keys) {
      delta.put(key, REMOVED);
    }
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
   * Returns the entries view, recording writes and removals in the delta; read-only in a workflow
   * node.
   */
  @Override
  public Set<Entry<String, Object>> entrySet() {
    return new EntrySetView();
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

  /** Returns the key set view, recording removals in the delta; read-only in a workflow node. */
  @Override
  public Set<String> keySet() {
    return new KeySetView();
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

  @Override
  public int size() {
    return state.size();
  }

  /** Returns the values view, recording removals in the delta; read-only in a workflow node. */
  @Override
  public Collection<Object> values() {
    return new ValuesView();
  }

  public boolean hasDelta() {
    return !delta.isEmpty();
  }

  private static void checkViewWritable(Collection<?> backingView) {
    // removeAll of an empty collection changes nothing, but a read-only view throws.
    var unused = backingView.removeAll(ImmutableList.of());
  }

  private final class EntrySetView extends AbstractSet<Entry<String, Object>> {
    @Override
    public Iterator<Entry<String, Object>> iterator() {
      Iterator<Entry<String, Object>> delegate = state.entrySet().iterator();
      return new Iterator<>() {
        private @Nullable String lastKey;

        @Override
        public boolean hasNext() {
          return delegate.hasNext();
        }

        @Override
        public Entry<String, Object> next() {
          Entry<String, Object> entry = delegate.next();
          lastKey = entry.getKey();
          return new TrackingEntry(entry);
        }

        @Override
        public void remove() {
          delegate.remove();
          String key = Objects.requireNonNull(lastKey);
          lastKey = null;
          // After delegate (which throws on a node's read-only view); syncs detached views (tokt).
          var unused = state.remove(key);
          delta.put(key, REMOVED);
        }
      };
    }

    @Override
    public Spliterator<Entry<String, Object>> spliterator() {
      return Spliterators.spliteratorUnknownSize(
          iterator(), Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL);
    }

    @Override
    public int size() {
      return state.size();
    }

    @Override
    public boolean contains(Object o) {
      return state.entrySet().contains(o);
    }

    @Override
    public boolean add(Entry<String, Object> entry) {
      checkViewWritable(state.entrySet());
      return State.this.put(entry.getKey(), entry.getValue()) == null;
    }

    @Override
    public boolean addAll(Collection<? extends Entry<String, Object>> c) {
      checkViewWritable(state.entrySet());
      return super.addAll(c);
    }

    @Override
    public boolean remove(Object o) {
      checkViewWritable(state.entrySet());
      if (!(o instanceof Entry<?, ?> entry)) {
        return false;
      }
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key == null || value == null) {
        return false;
      }
      return State.this.remove(key, value);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      checkViewWritable(state.entrySet());
      return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      checkViewWritable(state.entrySet());
      return super.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super Entry<String, Object>> filter) {
      checkViewWritable(state.entrySet());
      return super.removeIf(filter);
    }

    @Override
    public void clear() {
      State.this.clear();
    }
  }

  private final class TrackingEntry extends ForwardingMapEntry<String, Object> {
    private final Entry<String, Object> delegate;

    TrackingEntry(Entry<String, Object> delegate) {
      this.delegate = delegate;
    }

    @Override
    protected Entry<String, Object> delegate() {
      return delegate;
    }

    @Override
    public Object setValue(Object value) {
      Object oldValue = delegate.setValue(value);
      // After delegate (which throws on a node's read-only view); syncs detached views (tokt).
      var unused = state.put(delegate.getKey(), value);
      delta.put(delegate.getKey(), value);
      return oldValue;
    }
  }

  private final class KeySetView extends AbstractSet<String> {
    @Override
    public Iterator<String> iterator() {
      return Iterators.transform(entrySet().iterator(), Entry::getKey);
    }

    @Override
    public Spliterator<String> spliterator() {
      return Spliterators.spliteratorUnknownSize(
          iterator(), Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL);
    }

    @Override
    public int size() {
      return state.size();
    }

    @Override
    public boolean contains(Object o) {
      return state.containsKey(o);
    }

    @Override
    public boolean addAll(Collection<? extends String> c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
      checkViewWritable(state.keySet());
      return State.this.remove(o) != null;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      checkViewWritable(state.keySet());
      return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      checkViewWritable(state.keySet());
      return super.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super String> filter) {
      checkViewWritable(state.keySet());
      return super.removeIf(filter);
    }

    @Override
    public void clear() {
      State.this.clear();
    }
  }

  // Checks state.entrySet() since ValuesView mutates via it and CHM values().removeAll scans O(N).
  private final class ValuesView extends AbstractCollection<Object> {
    @Override
    public Iterator<Object> iterator() {
      return Iterators.transform(entrySet().iterator(), Entry::getValue);
    }

    @Override
    public Spliterator<Object> spliterator() {
      return Spliterators.spliteratorUnknownSize(
          iterator(), Spliterator.CONCURRENT | Spliterator.NONNULL);
    }

    @Override
    public int size() {
      return state.size();
    }

    @Override
    public boolean contains(Object o) {
      return state.containsValue(o);
    }

    @Override
    public boolean addAll(Collection<?> c) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
      checkViewWritable(state.entrySet());
      return super.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      checkViewWritable(state.entrySet());
      return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      checkViewWritable(state.entrySet());
      return super.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super Object> filter) {
      checkViewWritable(state.entrySet());
      return super.removeIf(filter);
    }

    @Override
    public void clear() {
      State.this.clear();
    }
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
