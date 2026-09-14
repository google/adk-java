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
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Preconditions;
import java.util.Optional;

/**
 * One line of the trace — port of adk-python's {@code _DebugEntry}.
 *
 * <p>Everything but {@code data} is the same for every entry; {@code data} is what {@link
 * TracePayload} models. Note that {@code agentName} is an entry field rather than payload, which is
 * why {@code agent_end} can carry {@link TracePayload.MarkerTrace} and still name an agent.
 *
 * <p>Component order is the serialized order, and matches upstream's field declaration.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record DebugEntry(
    String timestamp,
    @JsonProperty("entry_type") Type entryType,
    @JsonProperty("invocation_id") String invocationId,
    @JsonProperty("agent_name") Optional<String> agentName,
    TracePayload data) {

  /**
   * These components come from a caller's arguments rather than from an ADK object, so they are
   * worth guarding: a null here would not fail until the YAML write, on a different thread, long
   * after the hook that caused it returned.
   */
  DebugEntry {
    Preconditions.checkNotNull(timestamp);
    Preconditions.checkNotNull(entryType);
    Preconditions.checkNotNull(invocationId);
    Preconditions.checkNotNull(agentName);
    Preconditions.checkNotNull(data);
  }

  /**
   * The kinds of entry, with the wire names upstream writes as bare strings.
   *
   * <p>An enum rather than a {@code String}: a typo in one of these would produce a
   * plausible-looking file that silently does not match adk-python's.
   */
  enum Type {
    INVOCATION_START("invocation_start"),
    USER_MESSAGE("user_message"),
    AGENT_START("agent_start"),
    AGENT_END("agent_end"),
    LLM_REQUEST("llm_request"),
    LLM_RESPONSE("llm_response"),
    LLM_ERROR("llm_error"),
    TOOL_CALL("tool_call"),
    TOOL_RESPONSE("tool_response"),
    TOOL_ERROR("tool_error"),
    EVENT("event"),
    SESSION_STATE_SNAPSHOT("session_state_snapshot"),
    INVOCATION_END("invocation_end");

    private final String wireName;

    Type(String wireName) {
      this.wireName = wireName;
    }

    @JsonValue
    String wireName() {
      return wireName;
    }
  }
}
