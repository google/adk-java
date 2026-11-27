/*
 * Copyright 2026 Google LLC
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class StateTest {
  @Test
  public void constructor_nullDelta_createsEmptyConcurrentHashMap() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    State state = new State(stateMap, null);
    assertThat(state.hasDelta()).isFalse();
    state.put("key", "value");
    assertThat(state.hasDelta()).isTrue();
  }

  @Test
  public void constructor_regularMapState() {
    Map<String, Object> stateMap = new HashMap<>();
    stateMap.put("initial", "val");
    State state = new State(stateMap, null);
    // It should have copied the contents
    assertThat(state).containsEntry("initial", "val");
    state.put("key", "value");
    // The original map should NOT be updated because a copy was created
    assertThat(stateMap).doesNotContainKey("key");
  }

  @Test
  public void constructor_singleArgument() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    State state = new State(stateMap);
    assertThat(state.hasDelta()).isFalse();
    state.put("key", "value");
    assertThat(state.hasDelta()).isTrue();
  }

  @Test
  public void constructor_stateMapWithNullValues_replacesWithRemoved() {
    Map<String, Object> stateMap = new HashMap<>();
    stateMap.put("key1", "value1");
    stateMap.put("key2", null);
    State state = new State(stateMap);
    assertThat(state).containsEntry("key1", "value1");
    assertThat(state).containsEntry("key2", State.REMOVED);
  }

  @Test
  public void computeAndMerge_onCustomConcurrentMap_recordChangesAndRemovalsInDelta() {
    ConcurrentMap<String, Object> backing = new ConcurrentHashMap<>() {};
    backing.put("existing", 0);
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(backing, delta);

    Object existing = state.computeIfAbsent("existing", k -> 99);
    Object inserted = state.computeIfAbsent("a", k -> 1);
    assertThat(delta).containsExactly("a", 1);
    Object updated = state.computeIfPresent("a", (k, v) -> 3);
    assertThat(delta).containsExactly("a", 3);
    Object computed = state.compute("b", (k, v) -> 4);
    assertThat(delta).containsExactly("a", 3, "b", 4);
    Object merged = state.merge("b", 5, (o, n) -> (int) o + (int) n);
    assertThat(delta).containsExactly("a", 3, "b", 9);

    Object removedByPresent = state.computeIfPresent("a", (k, v) -> null);
    Object removedByCompute = state.compute("b", (k, v) -> null);
    state.put("c", 1);
    Object removedByMerge = state.merge("c", 2, (o, n) -> null);
    Object absentCompute = state.compute("missing", (k, v) -> null);

    assertThat(
            Arrays.asList(
                existing,
                inserted,
                updated,
                computed,
                merged,
                removedByPresent,
                removedByCompute,
                removedByMerge,
                absentCompute))
        .containsExactly(0, 1, 3, 4, 9, null, null, null, null)
        .inOrder();
    assertThat(state).containsExactly("existing", 0);
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED, "c", State.REMOVED);
    assertThat(state.hasDelta()).isTrue();
  }

  @Test
  public void compute_reentrantWriteOnCustomConcurrentMap_succeeds() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>() {}, delta);

    Object result =
        state.compute(
            "a",
            (k, v) -> {
              state.put("a", 1);
              return 2;
            });

    assertThat(result).isEqualTo(2);
    assertThat(state).containsExactly("a", 2);
    assertThat(delta).containsExactly("a", 2);
  }

  @Test
  public void delegatingCompute_computeAndMergeReturnTheValuesTheBackingMapStores() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = State.delegatingCompute(new ParsingMap(), delta);

    Object computed = state.compute("a", (k, v) -> "1");
    Object absent = state.computeIfAbsent("b", k -> "2");
    Object present = state.computeIfPresent("a", (k, v) -> "3");
    Object merged = state.merge("b", "4", (o, n) -> n);

    assertThat(Arrays.asList(computed, absent, present, merged))
        .containsExactly(1L, 2L, 3L, 4L)
        .inOrder();
    assertThat(state).containsExactly("a", 3L, "b", 4L);
    // The backing map owns the delta writes of compute and merge.
    assertThat(delta).isEmpty();
  }

  @Test
  public void delegatingCompute_sharesTheDeltaTheBackingMapRecordsWritesIn() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    ConcurrentMap<String, Object> recording =
        new ConcurrentHashMap<>() {
          @Override
          public Object compute(
              String key, BiFunction<? super String, ? super Object, ? extends Object> remapping) {
            Object value = super.compute(key, remapping);
            delta.put(key, value);
            return value;
          }
        };
    State state = State.delegatingCompute(recording, delta);

    state.compute("a", (k, v) -> 1);

    assertThat(state.hasDelta()).isTrue();
  }

  @Test
  public void delegatingCompute_nullDelta_throws() {
    assertThrows(
        NullPointerException.class, () -> State.delegatingCompute(new ConcurrentHashMap<>(), null));
  }

  @Test
  public void constructor_computeAndMergeKeepTheConcurrentMapDefaults() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ParsingMap(), delta);

    Object computed = state.compute("a", (k, v) -> "1");
    Object absent = state.computeIfAbsent("b", k -> "2");
    Object present = state.computeIfPresent("a", (k, v) -> "3");
    Object merged = state.merge("b", "4", (o, n) -> n);

    assertThat(Arrays.asList(computed, absent, present, merged))
        .containsExactly("1", "2", "3", "4")
        .inOrder();
    assertThat(state).containsExactly("a", "3", "b", "4");
    assertThat(delta).containsExactly("a", "3", "b", "4");
  }

  /** A map whose compute and merge methods store each new text value parsed as a long. */
  private static final class ParsingMap extends ConcurrentHashMap<String, Object> {
    @Override
    public Object compute(
        String key, BiFunction<? super String, ? super Object, ? extends Object> remapping) {
      return super.compute(key, (k, v) -> parse(remapping.apply(k, v)));
    }

    @Override
    public Object computeIfAbsent(String key, Function<? super String, ? extends Object> mapping) {
      return super.computeIfAbsent(key, k -> parse(mapping.apply(k)));
    }

    @Override
    public Object computeIfPresent(
        String key, BiFunction<? super String, ? super Object, ? extends Object> remapping) {
      return super.computeIfPresent(key, (k, v) -> parse(remapping.apply(k, v)));
    }

    @Override
    public Object merge(
        String key,
        Object value,
        BiFunction<? super Object, ? super Object, ? extends Object> remapping) {
      return super.merge(key, value, (o, n) -> parse(remapping.apply(o, n)));
    }

    private static Object parse(Object value) {
      return value instanceof String text ? Long.parseLong(text) : value;
    }
  }
}
