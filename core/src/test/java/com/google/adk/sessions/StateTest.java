package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Assert;
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
  public void constructor_nullState_throwsException() {
    Assert.assertThrows(NullPointerException.class, () -> new State(null, new HashMap<>()));
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
}
