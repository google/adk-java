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
package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link PendingArtifactDelta}, the {@code onUserMessageCallback} to {@code
 * beforeAgentCallback} hand-off.
 *
 * <p>Exercised directly rather than only through {@link SaveFilesAsArtifactsPlugin}, because the
 * rules it enforces are its own: the {@code temp:} key convention, invocation scoping, clearing by
 * overwrite, and reading back state that carries no type information.
 */
@RunWith(JUnit4.class)
public class PendingArtifactDeltaTest {

  private static final String PLUGIN_NAME = "test_plugin";
  private static final String INVOCATION_ID = "e-1234";
  private static final String OTHER_INVOCATION_ID = "e-5678";
  private static final String KEY_FORMAT = "temp:%s:pending_delta:%s";
  private static final String KEY = KEY_FORMAT.formatted(PLUGIN_NAME, INVOCATION_ID);
  private static final String FILE_NAME = "report.pdf";
  private static final ImmutableMap<String, Integer> DELTA = ImmutableMap.of(FILE_NAME, 3);

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private InvocationContext mockInvocationContext;
  @Mock private CallbackContext mockCallbackContext;

  private final State state = new State(new ConcurrentHashMap<>());
  private final Session session = Session.builder("test_session").state(state).build();

  @Before
  public void setUp() {
    state.clear();
    when(mockInvocationContext.invocationId()).thenReturn(INVOCATION_ID);
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockCallbackContext.invocationId()).thenReturn(INVOCATION_ID);
    when(mockCallbackContext.state()).thenReturn(state);
  }

  @Test
  public void stash_writesUnderATempPrefixedInvocationScopedKey() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);

    assertThat(state).containsKey(KEY);
    assertThat(KEY).startsWith(State.TEMP_PREFIX);
  }

  @Test
  public void drain_returnsWhatWasStashed() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME))
        .containsExactly(FILE_NAME, 3);
  }

  @Test
  public void drainTwice_returnsEmptyTheSecondTime() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);
    PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME);

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME)).isEmpty();
  }

  @Test
  public void drain_clearsByOverwritingRatherThanRemoving() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);

    PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME);

    assertThat(state).containsEntry(KEY, ImmutableMap.of());
  }

  /**
   * Each agent's callback gets a {@link State} with its own fresh delta, and {@code BaseAgent}
   * emits an event for any before-agent callback that leaves one. So draining an already-drained
   * stash must not write at all — otherwise every agent after the first emits an event carrying an
   * empty artifact delta. This is what the {@code isEmpty} guard in {@code drain} buys.
   */
  @Test
  public void secondDrain_leavesNoStateDelta() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);
    PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME);

    State laterCallbackState = new State(state, new ConcurrentHashMap<>());
    when(mockCallbackContext.state()).thenReturn(laterCallbackState);

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME)).isEmpty();
    assertThat(laterCallbackState.hasDelta()).isFalse();
  }

  @Test
  public void drain_withNothingStashed_addsNoStateEntry() {
    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME)).isEmpty();

    assertThat(state).doesNotContainKey(KEY);
  }

  @Test
  public void clear_emptiesAStashThatWasNeverDrained() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);

    PendingArtifactDelta.clear(mockInvocationContext, PLUGIN_NAME);

    assertThat(state).containsEntry(KEY, ImmutableMap.of());
  }

  @Test
  public void clear_withNothingStashed_addsNoStateEntry() {
    PendingArtifactDelta.clear(mockInvocationContext, PLUGIN_NAME);

    assertThat(state).doesNotContainKey(KEY);
  }

  @Test
  public void anotherInvocationsStash_isNotVisible() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);
    when(mockCallbackContext.invocationId()).thenReturn(OTHER_INVOCATION_ID);

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME)).isEmpty();
    assertThat(state).containsEntry(KEY, DELTA);
  }

  @Test
  public void anotherPluginsStash_isNotVisible() {
    PendingArtifactDelta.stash(mockInvocationContext, PLUGIN_NAME, DELTA);

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, "other_plugin")).isEmpty();
  }

  // --- reading back untyped state ---------------------------------------------------------------

  @Test
  public void valueThatIsNotAMap_isIgnored() {
    state.put(KEY, "not a map");

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME)).isEmpty();
  }

  @Test
  public void entriesWithANonIntegerVersion_areDropped() {
    state.put(KEY, Map.of(FILE_NAME, "3", "chart.png", 7));

    assertThat(PendingArtifactDelta.drain(mockCallbackContext, PLUGIN_NAME))
        .containsExactly("chart.png", 7);
  }
}
