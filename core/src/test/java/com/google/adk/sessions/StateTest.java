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

import java.util.HashMap;
import java.util.Map;
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
}
