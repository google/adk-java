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

import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
  public void clear_recordsRemovedInDeltaForEveryKey() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state =
        new State(new ConcurrentHashMap<>(ImmutableMap.of("app:a", 1, "user:b", 2, "c", 3)), delta);

    state.clear();

    assertThat(state).isEmpty();
    assertThat(delta)
        .containsExactly("app:a", State.REMOVED, "user:b", State.REMOVED, "c", State.REMOVED);
  }

  @Test
  public void clear_onEmptyState_leavesDeltaEmpty() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(), delta);

    state.clear();

    assertThat(delta).isEmpty();
  }

  @Test
  public void entrySet_setValueViaIterator_recordsDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);

    Object oldValue = state.entrySet().iterator().next().setValue(2);

    assertThat(oldValue).isEqualTo(1);
    assertThat(state).containsExactly("a", 2);
    assertThat(delta).containsExactly("a", 2);
  }

  @Test
  public void entrySet_setValueViaForEach_recordsDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);

    state.entrySet().forEach(entry -> entry.setValue(3));

    assertThat(state).containsExactly("a", 3);
    assertThat(delta).containsExactly("a", 3);
  }

  @Test
  public void entrySet_setValueViaStream_recordsDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);

    state.entrySet().stream().forEach(entry -> entry.setValue(4));

    assertThat(state).containsExactly("a", 4);
    assertThat(delta).containsExactly("a", 4);
  }

  @Test
  public void entrySet_setValueViaToArray_recordsDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);

    @SuppressWarnings("unchecked")
    Map.Entry<String, Object> fromArray = (Map.Entry<String, Object>) state.entrySet().toArray()[0];
    var unused = fromArray.setValue(5);

    assertThat(state).containsExactly("a", 5);
    assertThat(delta).containsExactly("a", 5);
  }

  @Test
  public void entrySet_addAndAddAll_recordDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);

    assertThat(state.entrySet().add(Map.entry("b", 2))).isTrue();
    assertThat(state.entrySet().add(Map.entry("a", 10))).isFalse();
    assertThat(state.entrySet().addAll(ImmutableList.of(Map.entry("c", 3)))).isTrue();

    assertThat(state).containsExactly("a", 10, "b", 2, "c", 3);
    assertThat(delta).containsExactly("a", 10, "b", 2, "c", 3);
  }

  @Test
  public void entrySet_setValueNull_throwsAndLeavesDeltaEmpty() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);
    Map.Entry<String, Object> entry = state.entrySet().iterator().next();

    assertThrows(NullPointerException.class, () -> entry.setValue(null));

    assertThat(state).containsExactly("a", 1);
    assertThat(delta).isEmpty();
  }

  @Test
  public void entrySet_remove_recordsRemovedOnlyWhenEntryMatches() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1, "b", 2)), delta);

    Object notAnEntry = "not an entry";
    assertThat(state.entrySet().remove(notAnEntry)).isFalse();
    assertThat(state.entrySet().remove(new SimpleEntry<>(null, 1))).isFalse();
    assertThat(state.entrySet().remove(new SimpleEntry<>("a", null))).isFalse();
    assertThat(state.entrySet().remove(new SimpleEntry<>("a", 99))).isFalse();
    assertThat(delta).isEmpty();

    assertThat(state.entrySet().remove(new SimpleEntry<>("a", 1))).isTrue();
    assertThat(state).containsExactly("b", 2);
    assertThat(delta).containsExactly("a", State.REMOVED);
  }

  @Test
  public void entrySet_bulkAndIteratorRemovals_recordEachStepInDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state =
        new State(
            new ConcurrentHashMap<>(ImmutableMap.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5)),
            delta);

    assertThat(state.entrySet().removeAll(ImmutableList.of())).isFalse();
    assertThat(delta).isEmpty();

    assertThat(state.entrySet().removeAll(ImmutableList.of(new SimpleEntry<>("a", 1)))).isTrue();
    assertThat(state).doesNotContainKey("a");
    assertThat(delta).containsExactly("a", State.REMOVED);

    assertThat(state.entrySet().removeIf(entry -> entry.getKey().equals("b"))).isTrue();
    assertThat(state).doesNotContainKey("b");
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED);

    assertThat(
            state
                .entrySet()
                .retainAll(ImmutableList.of(new SimpleEntry<>("c", 3), new SimpleEntry<>("d", 4))))
        .isTrue();
    assertThat(state).containsExactly("c", 3, "d", 4);
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED, "e", State.REMOVED);

    Iterator<Map.Entry<String, Object>> iterator = state.entrySet().iterator();
    String iterRemoved = iterator.next().getKey();
    iterator.remove();
    assertThrows(IllegalStateException.class, iterator::remove);
    assertThat(iterRemoved).isAnyOf("c", "d");
    assertThat(delta).containsEntry(iterRemoved, State.REMOVED);
    String remaining = iterRemoved.equals("c") ? "d" : "c";
    assertThat(state).containsExactly(remaining, remaining.equals("c") ? 3 : 4);

    state.entrySet().clear();
    assertThat(state).isEmpty();
    assertThat(delta)
        .containsExactly(
            "a", State.REMOVED,
            "b", State.REMOVED,
            "c", State.REMOVED,
            "d", State.REMOVED,
            "e", State.REMOVED);
  }

  @Test
  public void entrySet_iteratorRemoveBeforeNext_throwsAndLeavesDeltaEmpty() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);
    Iterator<Map.Entry<String, Object>> iterator = state.entrySet().iterator();

    assertThrows(IllegalStateException.class, iterator::remove);

    assertThat(state).containsExactly("a", 1);
    assertThat(delta).isEmpty();
  }

  @Test
  public void keySet_removalsAndClear_recordEachStepInDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state =
        new State(
            new ConcurrentHashMap<>(ImmutableMap.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5)),
            delta);

    assertThat(state.keySet().remove("absent")).isFalse();
    assertThat(state.keySet().removeAll(ImmutableList.of())).isFalse();
    assertThat(delta).isEmpty();

    assertThat(state.keySet().remove("a")).isTrue();
    assertThat(delta).containsExactly("a", State.REMOVED);

    assertThat(state.keySet().removeAll(ImmutableList.of("b"))).isTrue();
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED);

    assertThat(state.keySet().removeIf("c"::equals)).isTrue();
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED, "c", State.REMOVED);

    assertThat(state.keySet().retainAll(ImmutableList.of("d"))).isTrue();
    assertThat(state).containsExactly("d", 4);
    assertThat(delta)
        .containsExactly(
            "a", State.REMOVED, "b", State.REMOVED, "c", State.REMOVED, "e", State.REMOVED);

    state.keySet().clear();
    assertThat(state).isEmpty();
    assertThat(delta)
        .containsExactly(
            "a", State.REMOVED,
            "b", State.REMOVED,
            "c", State.REMOVED,
            "d", State.REMOVED,
            "e", State.REMOVED);
  }

  @Test
  public void keySet_iteratorRemove_recordsRemovedInDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)), delta);
    Iterator<String> iterator = state.keySet().iterator();

    assertThrows(IllegalStateException.class, iterator::remove);
    assertThat(iterator.next()).isEqualTo("a");
    iterator.remove();
    assertThrows(IllegalStateException.class, iterator::remove);

    assertThat(state).isEmpty();
    assertThat(delta).containsExactly("a", State.REMOVED);
  }

  @Test
  public void values_removeSingleDuplicate_recordsOnlyRemovedKeyInDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state =
        new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1, "b", 1, "c", 2)), delta);

    assertThat(state.values().remove(99)).isFalse();
    assertThat(delta).isEmpty();

    assertThat(state.values().remove(1)).isTrue();

    assertThat(state).hasSize(2);
    assertThat(state).containsEntry("c", 2);
    String removedKey = state.containsKey("a") ? "b" : "a";
    assertThat(delta).containsExactly(removedKey, State.REMOVED);
  }

  @Test
  public void values_bulkIteratorAndClearRemovals_recordEachStepInDelta() {
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state =
        new State(
            new ConcurrentHashMap<>(ImmutableMap.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5)),
            delta);

    assertThat(state.values().removeAll(ImmutableList.of())).isFalse();
    assertThat(delta).isEmpty();

    assertThat(state.values().removeAll(ImmutableList.of(1))).isTrue();
    assertThat(delta).containsExactly("a", State.REMOVED);

    assertThat(state.values().removeIf(Integer.valueOf(2)::equals)).isTrue();
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED);

    assertThat(state.values().retainAll(ImmutableList.of(3, 4))).isTrue();
    assertThat(state).containsExactly("c", 3, "d", 4);
    assertThat(delta).containsExactly("a", State.REMOVED, "b", State.REMOVED, "e", State.REMOVED);

    Iterator<Object> iterator = state.values().iterator();
    Object removedVal = iterator.next();
    iterator.remove();
    String iterRemoved = removedVal.equals(3) ? "c" : "d";
    assertThat(delta).containsEntry(iterRemoved, State.REMOVED);

    state.values().clear();
    assertThat(state).isEmpty();
    assertThat(delta)
        .containsExactly(
            "a", State.REMOVED,
            "b", State.REMOVED,
            "c", State.REMOVED,
            "d", State.REMOVED,
            "e", State.REMOVED);
  }

  @Test
  public void views_nullBulkArguments_throwNullPointerException() {
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)));

    assertThrows(NullPointerException.class, () -> state.entrySet().removeAll(null));
    assertThrows(NullPointerException.class, () -> state.entrySet().retainAll(null));
    assertThrows(NullPointerException.class, () -> state.entrySet().removeIf(null));
    assertThrows(NullPointerException.class, () -> state.keySet().removeAll(null));
    assertThrows(NullPointerException.class, () -> state.keySet().retainAll(null));
    assertThrows(NullPointerException.class, () -> state.keySet().removeIf(null));
    assertThrows(NullPointerException.class, () -> state.values().removeAll(null));
    assertThrows(NullPointerException.class, () -> state.values().retainAll(null));
    assertThrows(NullPointerException.class, () -> state.values().removeIf(null));
  }

  @Test
  public void keySetAndValues_addAndAddAll_throwUnsupportedOperationException() {
    State state = new State(new ConcurrentHashMap<>(ImmutableMap.of("a", 1)));

    assertThrows(UnsupportedOperationException.class, () -> state.keySet().add("b"));
    assertThrows(
        UnsupportedOperationException.class, () -> state.keySet().addAll(ImmutableList.of()));
    assertThrows(UnsupportedOperationException.class, () -> state.keySet().addAll(null));
    assertThrows(UnsupportedOperationException.class, () -> state.values().add(2));
    assertThrows(
        UnsupportedOperationException.class, () -> state.values().addAll(ImmutableList.of()));
    assertThrows(UnsupportedOperationException.class, () -> state.values().addAll(null));
  }

  @Test
  public void views_streamWhileGrowing_doesNotThrow() {
    State state = new State(new ConcurrentHashMap<>());
    for (int i = 0; i < 16; i++) {
      var unused = state.put("k" + i, i);
    }

    List<String> keys =
        state.keySet().stream().peek(key -> state.putIfAbsent(key + "_added", 99)).toList();
    List<Map.Entry<String, Object>> entries =
        state.entrySet().stream()
            .peek(entry -> state.putIfAbsent(entry.getKey() + "_e", 99))
            .toList();
    List<Object> values =
        state.values().stream().peek(val -> state.putIfAbsent("v_" + state.size(), 99)).toList();

    assertThat(keys.size()).isAtLeast(16);
    assertThat(entries.size()).isAtLeast(16);
    assertThat(values.size()).isAtLeast(16);
  }

  @Test
  public void detachedViewsBackingMap_viewWritesStayInSyncWithLiveStateAndDelta() {
    ConcurrentMap<String, Object> backing =
        new DetachedViewsMap(new ConcurrentHashMap<>(ImmutableMap.of("drop", 1, "bump", 2)));
    ConcurrentMap<String, Object> delta = new ConcurrentHashMap<>();
    State state = new State(backing, delta);

    assertThat(state.keySet().removeIf("drop"::equals)).isTrue();
    for (Map.Entry<String, Object> entry : state.entrySet()) {
      if (entry.getKey().equals("bump")) {
        var unused = entry.setValue(20);
      }
    }

    assertThat(backing).containsExactly("bump", 20);
    assertThat(state).containsExactly("bump", 20);
    assertThat(delta).containsExactly("drop", State.REMOVED, "bump", 20);
  }

  @Test
  public void nestedState_viewWritesAndClear_recordInInnerAndOuterDeltas() {
    ConcurrentMap<String, Object> backing =
        new ConcurrentHashMap<>(ImmutableMap.of("a", 1, "b", 2, "c", 3));
    ConcurrentMap<String, Object> innerDelta = new ConcurrentHashMap<>();
    ConcurrentMap<String, Object> outerDelta = new ConcurrentHashMap<>();
    State inner = new State(backing, innerDelta);
    State outer = new State(inner, outerDelta);

    outer.keySet().remove("a");
    for (Map.Entry<String, Object> entry : outer.entrySet()) {
      if (entry.getKey().equals("b")) {
        var unused = entry.setValue(20);
      }
    }
    assertThat(backing).containsExactly("b", 20, "c", 3);
    assertThat(innerDelta).containsExactly("a", State.REMOVED, "b", 20);
    assertThat(outerDelta).containsExactly("a", State.REMOVED, "b", 20);

    outer.clear();
    assertThat(backing).isEmpty();
    assertThat(innerDelta)
        .containsExactly("a", State.REMOVED, "b", State.REMOVED, "c", State.REMOVED);
    assertThat(outerDelta)
        .containsExactly("a", State.REMOVED, "b", State.REMOVED, "c", State.REMOVED);
  }

  /** A ConcurrentMap whose collection views are detached copies (like tokt's adapter). */
  private static final class DetachedViewsMap extends ForwardingConcurrentMap<String, Object> {
    private final ConcurrentMap<String, Object> delegate;

    DetachedViewsMap(ConcurrentMap<String, Object> delegate) {
      this.delegate = delegate;
    }

    @Override
    protected ConcurrentMap<String, Object> delegate() {
      return delegate;
    }

    @Override
    public Set<String> keySet() {
      return new LinkedHashSet<>(delegate.keySet());
    }

    @Override
    public Collection<Object> values() {
      return new ArrayList<>(delegate.values());
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      Set<Entry<String, Object>> copy = new LinkedHashSet<>();
      for (Entry<String, Object> entry : delegate.entrySet()) {
        copy.add(new SimpleEntry<>(entry.getKey(), entry.getValue()));
      }
      return copy;
    }
  }
}
