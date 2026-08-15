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

import static java.util.function.Predicate.not;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;

/**
 * One {@link Event} as it appears in the trace — port of adk-python's {@code on_event_callback}.
 *
 * <p>Grounding metadata is recorded as a bare {@code has_grounding_metadata: true} rather than
 * copied, as upstream does — the payload is large and adds nothing to a readable trace.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record EventTrace(
    @JsonProperty("event_id") String eventId,
    String author,
    Optional<ContentTrace> content,
    @JsonProperty("is_final_response") boolean isFinalResponse,
    Optional<Boolean> partial,
    @JsonProperty("turn_complete") Optional<Boolean> turnComplete,
    Optional<String> branch,
    Optional<EventActionsTrace> actions,
    @JsonProperty("has_grounding_metadata") Optional<Boolean> hasGroundingMetadata,
    @JsonProperty("usage_metadata") Optional<UsageTrace> usageMetadata,
    @JsonProperty("error_code") Optional<String> errorCode,
    @JsonProperty("error_message") Optional<String> errorMessage,
    @JsonProperty("long_running_tool_ids") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        ImmutableList<String> longRunningToolIds)
    implements TracePayload {

  static EventTrace from(Event event) {
    return new EventTrace(
        event.id(),
        event.author(),
        event.content().map(ContentTrace::from),
        event.finalResponse(),
        event.partial(),
        event.turnComplete(),
        event.branch(),
        EventActionsTrace.from(event.actions()),
        event.groundingMetadata().map(unused -> Boolean.TRUE),
        event.usageMetadata().map(UsageTrace::fromEvent),
        event.errorCode().map(String::valueOf),
        event.errorMessage(),
        event.longRunningToolIds().map(ImmutableList::copyOf).orElseGet(ImmutableList::of));
  }

  /**
   * What an event's {@link EventActions} contributed, as it appears in the trace.
   *
   * <p>Two details are load-bearing and both come from upstream's {@code on_event_callback}:
   *
   * <ul>
   *   <li>Requested auth configs are recorded as a <b>count, never their content</b> — the map is
   *       free-form and auth-related, and a debug file gets pasted into bug reports.
   *   <li>The artifact delta keeps its filename → version mapping, which is the only reason it is
   *       worth recording at all.
   * </ul>
   *
   * <p>{@link EventActions} hands out its <em>live</em> maps, so every one of them is copied rather
   * than referenced — a trace must not change after it was taken.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record EventActionsTrace(
      @JsonProperty("state_delta") @JsonInclude(JsonInclude.Include.NON_EMPTY)
          ImmutableMap<String, Object> stateDelta,
      @JsonProperty("artifact_delta") @JsonInclude(JsonInclude.Include.NON_EMPTY)
          ImmutableMap<String, Integer> artifactDelta,
      @JsonProperty("transfer_to_agent") Optional<String> transferToAgent,
      Optional<Boolean> escalate,
      @JsonProperty("requested_auth_configs") Optional<Integer> requestedAuthConfigs) {

    /** Empty when the event requested nothing, matching upstream's {@code if actions_data:}. */
    static Optional<EventActionsTrace> from(EventActions actions) {
      EventActionsTrace trace =
          new EventActionsTrace(
              SafeSerializer.serializeMap(actions.stateDelta()),
              ImmutableMap.copyOf(actions.artifactDelta()),
              actions.transferToAgent(),
              actions.escalate(),
              countOf(actions.requestedAuthConfigs()));
      return trace.isEmpty() ? Optional.empty() : Optional.of(trace);
    }

    /** How many were requested — never which, since the map is free-form and auth-related. */
    private static Optional<Integer> countOf(Map<String, ?> requested) {
      return Optional.of(requested).filter(not(Map::isEmpty)).map(Map::size);
    }

    @JsonIgnore
    boolean isEmpty() {
      return stateDelta.isEmpty()
          && artifactDelta.isEmpty()
          && transferToAgent.isEmpty()
          && escalate.isEmpty()
          && requestedAuthConfigs.isEmpty();
    }
  }
}
