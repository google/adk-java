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

import static com.google.common.truth.Truth.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StateDiffTest {

  @Test
  public void get_returnsValueFromDeltaIfPresent() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key", "initialValue");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key", "newValue");
    State state = new State(stateMap, deltaMap);

    assertThat(state.get("key")).isEqualTo("newValue");
  }

  @Test
  public void get_returnsValueFromStateIfNotInDelta() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key", "initialValue");
    State state = new State(stateMap);

    assertThat(state.get("key")).isEqualTo("initialValue");
  }

  @Test
  public void get_returnsNullIfKeyInDeltaAsRemoved() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key", "initialValue");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key", State.REMOVED);
    State state = new State(stateMap, deltaMap);

    assertThat(state.get("key")).isNull();
  }

  @Test
  public void containsKey_respectsDelta() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key1", "value1");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key1", State.REMOVED);
    deltaMap.put("key2", "value2");
    State state = new State(stateMap, deltaMap);

    assertThat(state.containsKey("key1")).isFalse();
    assertThat(state.containsKey("key2")).isTrue();
  }

  @Test
  public void size_respectsDelta() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key1", "value1");
    stateMap.put("key2", "value2");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key1", State.REMOVED);
    deltaMap.put("key3", "value3");
    State state = new State(stateMap, deltaMap);

    assertThat(state.size()).isEqualTo(2); // key2, key3
  }

  @Test
  public void isEmpty_respectsDelta() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key1", "value1");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key1", State.REMOVED);
    State state = new State(stateMap, deltaMap);

    assertThat(state.isEmpty()).isTrue();
  }

  @Test
  public void entrySet_reflectsMergedState() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key1", "value1");
    stateMap.put("key2", "value2");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key1", "newValue1");
    deltaMap.put("key3", "value3");
    State state = new State(stateMap, deltaMap);

    Map<String, Object> expected = new HashMap<>();
    expected.put("key1", "newValue1");
    expected.put("key2", "value2");
    expected.put("key3", "value3");

    assertThat(state.entrySet()).containsExactlyElementsIn(expected.entrySet());
  }

  @Test
  public void keySet_reflectsMergedState() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key1", "value1");
    stateMap.put("key2", "value2");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key1", State.REMOVED);
    deltaMap.put("key3", "value3");
    State state = new State(stateMap, deltaMap);

    assertThat(state.keySet()).containsExactly("key2", "key3");
  }

  @Test
  public void values_reflectsMergedState() {
    ConcurrentMap<String, Object> stateMap = new ConcurrentHashMap<>();
    stateMap.put("key1", "value1");
    stateMap.put("key2", "value2");
    ConcurrentMap<String, Object> deltaMap = new ConcurrentHashMap<>();
    deltaMap.put("key1", "newValue1");
    deltaMap.put("key3", "value3");
    State state = new State(stateMap, deltaMap);

    assertThat(state.values()).containsExactly("newValue1", "value2", "value3");
  }
}
