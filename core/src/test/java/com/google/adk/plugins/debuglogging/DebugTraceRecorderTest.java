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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.plugins.debuglogging.DebugEntry.Type;
import com.google.adk.plugins.debuglogging.TracePayload.BranchTrace;
import com.google.adk.plugins.debuglogging.TracePayload.MarkerTrace;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Covers the state layer end to end — the live-invocation map, the per-invocation accumulator, and
 * the entries filed into it — since none of the three means anything without the other two.
 *
 * <p>The clock is fixed, so the timestamp is asserted exactly rather than pattern-matched.
 */
@RunWith(JUnit4.class)
public class DebugTraceRecorderTest {

  private static final String INVOCATION_ID = "inv-1";
  private static final String AGENT = "root_agent";
  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-02T10:15:30.123Z"), ZoneOffset.UTC);

  private final ObjectMapper mapper = DebugYamlWriter.configure(new ObjectMapper());
  private final DebugTraceRecorder recorder = new DebugTraceRecorder(FIXED);

  private static InvocationContext invocationContext() {
    return InvocationContext.builder()
        .invocationId(INVOCATION_ID)
        .agent(LlmAgent.builder().name(AGENT).build())
        .session(Session.builder("session-1").appName("shop").userId("user-1").build())
        .sessionService(new InMemorySessionService())
        .runConfig(RunConfig.builder().build())
        .build();
  }

  private InvocationDebugState startedInvocation() {
    InvocationDebugState state = InvocationDebugState.of(invocationContext(), recorder.now());
    recorder.start(state);
    return state;
  }

  @Test
  public void header_carriesTheInvocationSessionAndStartTime() throws Exception {
    String json = mapper.writeValueAsString(startedInvocation());

    assertThat(json)
        .startsWith(
            "{\"invocation_id\":\"inv-1\",\"session_id\":\"session-1\",\"app_name\":\"shop\","
                + "\"user_id\":\"user-1\",\"start_time\":\"2026-08-02T10:15:30.123\","
                + "\"entries\":[");
  }

  /**
   * {@link Session.Builder#build} validates only the id, so a hand-built session can carry neither
   * an app name nor a user id. Null-checking them here would throw inside {@code beforeRunCallback}
   * and break a run <em>because the plugin was attached</em>. Both are {@link java.util.Optional},
   * and an absent one simply omits its key.
   */
  @Test
  public void header_sessionWithoutAppNameOrUserId_omitsThoseKeys() throws Exception {
    InvocationContext context =
        InvocationContext.builder()
            .invocationId(INVOCATION_ID)
            .agent(LlmAgent.builder().name(AGENT).build())
            .session(Session.builder("session-1").build())
            .sessionService(new InMemorySessionService())
            .runConfig(RunConfig.builder().build())
            .build();

    String json = mapper.writeValueAsString(InvocationDebugState.of(context, recorder.now()));

    assertThat(json).contains("\"invocation_id\":\"inv-1\"");
    assertThat(json).contains("\"session_id\":\"session-1\"");
    assertThat(json).doesNotContain("app_name");
    assertThat(json).doesNotContain("user_id");
  }

  @Test
  public void record_writesEveryEntryFieldInUpstreamsOrder() throws Exception {
    startedInvocation();

    recorder.record(INVOCATION_ID, Type.AGENT_START, AGENT, new BranchTrace(Optional.of("root")));

    assertThat(mapper.writeValueAsString(recorder.forEntry(INVOCATION_ID).orElseThrow()))
        .contains(
            "\"entries\":[{\"timestamp\":\"2026-08-02T10:15:30.123\","
                + "\"entry_type\":\"agent_start\",\"invocation_id\":\"inv-1\","
                + "\"agent_name\":\"root_agent\",\"data\":{\"branch\":\"root\"}}]");
  }

  @Test
  public void record_withoutAnAgentName_omitsTheKey() throws Exception {
    startedInvocation();

    recorder.record(INVOCATION_ID, Type.INVOCATION_END, MarkerTrace.INSTANCE);

    String json = mapper.writeValueAsString(recorder.forEntry(INVOCATION_ID).orElseThrow());
    assertThat(json).contains("\"entry_type\":\"invocation_end\"");
    assertThat(json).doesNotContain("agent_name");
  }

  /** {@code agent_end} and {@code invocation_end} carry no payload, and must still write one. */
  @Test
  public void record_markerEntry_writesAnEmptyDataMapping() throws Exception {
    startedInvocation();

    recorder.record(INVOCATION_ID, Type.AGENT_END, AGENT, MarkerTrace.INSTANCE);

    assertThat(mapper.writeValueAsString(recorder.forEntry(INVOCATION_ID).orElseThrow()))
        .contains("\"agent_name\":\"root_agent\",\"data\":{}}");
  }

  @Test
  public void record_keepsEntriesInTheOrderTheHooksFired() {
    InvocationDebugState state = startedInvocation();

    recorder.record(INVOCATION_ID, Type.USER_MESSAGE, MarkerTrace.INSTANCE);
    recorder.record(INVOCATION_ID, Type.AGENT_START, MarkerTrace.INSTANCE);
    recorder.record(INVOCATION_ID, Type.INVOCATION_END, MarkerTrace.INSTANCE);

    assertThat(state.entries().stream().map(DebugEntry::entryType))
        .containsExactly(Type.USER_MESSAGE, Type.AGENT_START, Type.INVOCATION_END)
        .inOrder();
  }

  /** A debug plugin that lost a state must log a gap, never break the run it is observing. */
  @Test
  public void record_forAnUnknownInvocation_isDroppedRatherThanThrown() {
    InvocationDebugState state = startedInvocation();

    recorder.record("some-other-invocation", Type.EVENT, MarkerTrace.INSTANCE);

    assertThat(state.entries()).isEmpty();
  }

  @Test
  public void entries_isASnapshotNotTheLiveQueue() {
    InvocationDebugState state = startedInvocation();
    recorder.record(INVOCATION_ID, Type.USER_MESSAGE, MarkerTrace.INSTANCE);

    ImmutableList<DebugEntry> taken = state.entries();
    recorder.record(INVOCATION_ID, Type.INVOCATION_END, MarkerTrace.INSTANCE);

    assertThat(taken).hasSize(1);
    assertThat(state.entries()).hasSize(2);
  }

  /** {@code forWrite} leaves the state in place so the closing entries can still be filed. */
  @Test
  public void forWrite_doesNotRemoveTheStateUntilFinishIsCalled() {
    startedInvocation();

    assertThat(recorder.forWrite(INVOCATION_ID)).isPresent();
    assertThat(recorder.forWrite(INVOCATION_ID)).isPresent();

    recorder.finish(INVOCATION_ID);

    assertThat(recorder.forWrite(INVOCATION_ID)).isEmpty();
  }
}
