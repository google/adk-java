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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Carries artifact versions from {@code onUserMessageCallback}, which has no {@link
 * com.google.adk.events.EventActions} to write to, across to {@code beforeAgentCallback}, which
 * does.
 *
 * <p>The hand-off rides on the session state under a {@link
 * com.google.adk.sessions.State#TEMP_PREFIX} key, so {@code BaseSessionService.appendEvent} skips
 * it when applying the state delta and the bookkeeping never reaches persisted session state. The
 * key also carries the invocation id, so concurrent invocations sharing one session cannot read
 * each other's pending versions.
 *
 * <p>Draining overwrites the entry with an empty map rather than removing it. {@code State.remove}
 * would write the {@code State.REMOVED} sentinel into the event's state delta, which does not
 * survive a JSON round trip.
 */
final class PendingArtifactDelta {

  private static final String KEY = "temp:%s:pending_delta:%s";

  private PendingArtifactDelta() {}

  /** Stores the versions produced during this invocation. */
  static void stash(
      InvocationContext invocationContext, String pluginName, ImmutableMap<String, Integer> delta) {
    invocationContext
        .session()
        .state()
        .put(key(pluginName, invocationContext.invocationId()), delta);
  }

  /**
   * Returns the stored versions and clears them, so only the first agent callback reports them.
   *
   * <p>The write is guarded on there being something to clear, and the guard is load-bearing: a
   * write through {@link CallbackContext#state()} sets the state delta, and {@code BaseAgent} emits
   * an event for any before-agent callback that left one. Clearing unconditionally would therefore
   * emit an event carrying an empty artifact delta for every agent after the first.
   */
  static ImmutableMap<String, Integer> drain(CallbackContext callbackContext, String pluginName) {
    String key = key(pluginName, callbackContext.invocationId());
    ImmutableMap<String, Integer> pending = read(callbackContext.state().get(key));
    if (!pending.isEmpty()) {
      callbackContext.state().put(key, ImmutableMap.of());
    }
    return pending;
  }

  /**
   * Discards versions left behind by an invocation that ended before any agent ran, which happens
   * when a {@code beforeRunCallback} on another plugin halts the run. There is no {@link
   * com.google.adk.events.EventActions} to report them on at that point, so they are dropped rather
   * than returned.
   *
   * <p>Only writes when something is actually stashed: an unconditional write would add an entry
   * for every invocation that uploaded nothing, which is more state noise than the leak it
   * prevents.
   */
  static void clear(InvocationContext invocationContext, String pluginName) {
    Map<String, Object> state = invocationContext.session().state();
    String key = key(pluginName, invocationContext.invocationId());
    if (!read(state.get(key)).isEmpty()) {
      state.put(key, ImmutableMap.of());
    }
  }

  private static String key(String pluginName, String invocationId) {
    return KEY.formatted(pluginName, invocationId);
  }

  private static ImmutableMap<String, Integer> read(Object stashed) {
    if (!(stashed instanceof Map<?, ?> entries)) {
      return ImmutableMap.of();
    }
    return entries.entrySet().stream()
        .filter(PendingArtifactDelta::isVersionEntry)
        .collect(
            toImmutableMap(entry -> (String) entry.getKey(), entry -> (Integer) entry.getValue()));
  }

  /** State values survive a JSON round trip untyped, so each entry is checked before it is kept. */
  private static boolean isVersionEntry(Map.Entry<?, ?> entry) {
    return entry.getKey() instanceof String && entry.getValue() instanceof Integer;
  }
}
