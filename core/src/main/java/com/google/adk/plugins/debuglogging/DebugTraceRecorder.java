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

import com.google.common.base.Preconditions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live invocations, and the timestamped entries filed into them — upstream's {@code
 * _invocation_states} dict together with {@code _add_entry}.
 *
 * <p>The two belong to one another: an entry is only ever created in order to be filed, and it is
 * filed against a state that is looked up in the same breath. It is also the only class that reads
 * the clock — the header and the first entry are stamped from the same source, so they cannot
 * disagree about when the run began.
 *
 * <p>It also answers "what if there is no state for this id?". Upstream warns and carries on, with
 * two different messages depending on whether an entry or a write asked; both are preserved, so a
 * trace of adk-java's logs reads the same as adk-python's. Neither path throws: a missing state is
 * logged and the run continues.
 *
 * <p>The map is concurrent because ADK runs invocations in parallel and every hook may arrive on a
 * different thread.
 */
final class DebugTraceRecorder {

  private static final Logger logger = LoggerFactory.getLogger(DebugTraceRecorder.class);

  private static final String NO_STATE_FOR_ENTRY =
      "No debug state for invocation {}, skipping entry";
  private static final String NO_STATE_FOR_WRITE =
      "No debug state for invocation {}, skipping write";

  /**
   * Upstream writes {@code datetime.now().isoformat()}, so the timestamp is local time with no
   * offset. The pattern is stated rather than left to {@link LocalDateTime#toString()}, which drops
   * zero seconds and prints a varying number of fractional digits — a trace reads better when every
   * line is the same width and sorts lexicographically.
   */
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

  private final ConcurrentMap<String, InvocationDebugState> states = new ConcurrentHashMap<>();
  private final Clock clock;

  DebugTraceRecorder(Clock clock) {
    this.clock = Preconditions.checkNotNull(clock);
  }

  /**
   * Registers {@code state} unless this invocation already has one.
   *
   * <p>"Unless" is load-bearing. {@code Runner.runAsync} invokes {@code onUserMessageCallback}
   * <b>before</b> {@code beforeRunCallback}, so either hook may be the first to reach an
   * invocation. Both call this, the second finds the state already open, and the user message — the
   * first thing anyone reading a debug trace looks for — is recorded rather than filed against an
   * invocation that does not exist yet.
   *
   * <p>The state is still created once, still keyed by invocation id, and still holds the same
   * header. This is the port's one behavioral difference from adk-python, which opens the state in
   * the later hook only.
   */
  void start(InvocationDebugState state) {
    states.putIfAbsent(state.invocationId(), state);
  }

  /** For the entry types upstream records without an agent name. */
  void record(String invocationId, DebugEntry.Type type, TracePayload data) {
    record(invocationId, type, null, data);
  }

  /**
   * Nullable so the overload above can omit the agent name, and a plain {@code String} rather than
   * an {@code Optional} because adk-java's {@code illegal-optional-check} profile bans {@code
   * Optional} as a parameter.
   */
  void record(
      String invocationId, DebugEntry.Type type, @Nullable String agentName, TracePayload data) {
    DebugEntry entry =
        new DebugEntry(now(), type, invocationId, Optional.ofNullable(agentName), data);
    forEntry(invocationId).ifPresent(state -> state.add(entry));
  }

  /** The state an entry belongs to, or empty — with upstream's warning — if it is gone. */
  Optional<InvocationDebugState> forEntry(String invocationId) {
    return lookup(invocationId, NO_STATE_FOR_ENTRY);
  }

  /**
   * The state to write out, left in place so that the closing entries can still be filed under it.
   *
   * <p>Upstream removes the state in a {@code finally} <em>after</em> writing, not before,
   * precisely because {@code session_state_snapshot} and {@code invocation_end} are recorded in
   * between. {@link #finish} is that {@code finally}.
   */
  Optional<InvocationDebugState> forWrite(String invocationId) {
    return lookup(invocationId, NO_STATE_FOR_WRITE);
  }

  /** Drops the invocation, whether or not the write succeeded. */
  void finish(String invocationId) {
    states.remove(invocationId);
  }

  /** The same clock the entries use, for an invocation's {@code start_time}. */
  String now() {
    return LocalDateTime.now(clock).format(TIMESTAMP);
  }

  private Optional<InvocationDebugState> lookup(String invocationId, String warning) {
    Optional<InvocationDebugState> state = Optional.ofNullable(states.get(invocationId));
    if (state.isEmpty()) {
      logger.warn(warning, invocationId);
    }
    return state;
  }
}
