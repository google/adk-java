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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

/**
 * One invocation's header and the entries recorded under it — port of adk-python's {@code
 * _InvocationDebugState}. This is the YAML document that gets written when the invocation ends.
 *
 * <p>Mutable, deliberately: entries accumulate for the length of a run. Two things follow from
 * that, both required rather than stylistic:
 *
 * <ul>
 *   <li>The queue is <b>concurrent</b>. ADK's hooks fire from whichever Rx thread the run is on,
 *       and a parallel agent has several of them appending at once; an {@code ArrayList} would drop
 *       or corrupt entries.
 *   <li>{@link #entries()} returns an {@link ImmutableList} <b>snapshot</b>, never the live queue.
 *       The writer must see a document that cannot change underneath it.
 * </ul>
 *
 * <p>The app name and user id are optional even though upstream types them as required. {@link
 * Session} validates only its id, so a hand-built session can carry neither — and a debug plugin
 * that threw because the session it was <em>observing</em> was underpopulated would break the run
 * for the sake of a trace. Absent means the key is simply not written.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonPropertyOrder({"invocation_id", "session_id", "app_name", "user_id", "start_time", "entries"})
final class InvocationDebugState {

  private final String invocationId;
  private final String sessionId;
  private final Optional<String> appName;
  private final Optional<String> userId;
  private final String startTime;
  private final ConcurrentLinkedQueue<DebugEntry> entries = new ConcurrentLinkedQueue<>();

  private InvocationDebugState(
      String invocationId,
      String sessionId,
      @Nullable String appName,
      @Nullable String userId,
      String startTime) {
    this.invocationId = Preconditions.checkNotNull(invocationId);
    this.sessionId = Preconditions.checkNotNull(sessionId);
    this.appName = Optional.ofNullable(appName);
    this.userId = Optional.ofNullable(userId);
    this.startTime = Preconditions.checkNotNull(startTime);
  }

  /** The header adk-python fills in {@code before_run_callback}. */
  static InvocationDebugState of(InvocationContext context, String startTime) {
    Session session = context.session();
    return new InvocationDebugState(
        context.invocationId(), session.id(), session.appName(), context.userId(), startTime);
  }

  void add(DebugEntry entry) {
    entries.add(entry);
  }

  @JsonProperty("invocation_id")
  String invocationId() {
    return invocationId;
  }

  @JsonProperty("session_id")
  String sessionId() {
    return sessionId;
  }

  @JsonProperty("app_name")
  Optional<String> appName() {
    return appName;
  }

  @JsonProperty("user_id")
  Optional<String> userId() {
    return userId;
  }

  @JsonProperty("start_time")
  String startTime() {
    return startTime;
  }

  /** A snapshot, in the order the hooks fired. */
  @JsonProperty("entries")
  ImmutableList<DebugEntry> entries() {
    return ImmutableList.copyOf(entries);
  }
}
