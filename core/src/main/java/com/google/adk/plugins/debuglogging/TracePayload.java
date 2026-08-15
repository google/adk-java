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
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code data} of one debug entry.
 *
 * <p>adk-python passes {@code **kwargs} into a free-form {@code dict[str, Any]} in {@code
 * _add_entry}. Every entry type here has a known shape instead, so the union is sealed: a new type
 * cannot be added without declaring what it carries.
 *
 * <p>{@link BranchTrace} serves both {@code invocation_start} and {@code agent_start}, and {@link
 * MarkerTrace} serves {@code agent_end} and {@code invocation_end}, which upstream records with no
 * payload at all.
 */
sealed interface TracePayload
    permits EventTrace,
        LlmRequestTrace,
        LlmResponseTrace,
        TracePayload.BranchTrace,
        TracePayload.LlmErrorTrace,
        TracePayload.MarkerTrace,
        TracePayload.SessionStateTrace,
        TracePayload.ToolCallTrace,
        TracePayload.ToolErrorTrace,
        TracePayload.ToolResponseTrace,
        TracePayload.UserMessageTrace {

  /**
   * Which agent branch an entry belongs to — the payload of {@code invocation_start} and {@code
   * agent_start}.
   *
   * <p>A root invocation has no branch, and upstream's {@code exclude_none=True} then leaves the
   * key out; {@code NON_ABSENT} does the same here, so the entry records {@code data: {}}.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record BranchTrace(Optional<String> branch) implements TracePayload {}

  /**
   * The payload of an entry that has none — {@code agent_end} and {@code invocation_end}.
   *
   * <p>This is upstream's shape, not an omission: it records the latter as literally {@code
   * self._add_entry(invocation_id, "invocation_end")}. The entry still carries its timestamp, type,
   * invocation id and (for {@code agent_end}) agent name — for these two types the timestamp
   * <em>is</em> the information.
   *
   * <p>A singleton, because every instance is identical.
   */
  record MarkerTrace() implements TracePayload {

    static final MarkerTrace INSTANCE = new MarkerTrace();

    /**
     * Renders as {@code data: {}}, matching upstream's default-empty dict.
     *
     * <p>Jackson has no properties to discover on a component-less record, so the empty mapping is
     * stated rather than inferred — without this the writer would reject the entry.
     */
    @JsonValue
    ImmutableMap<String, Object> fields() {
      return ImmutableMap.of();
    }
  }

  /** The message that opened the invocation — {@code user_message}. */
  record UserMessageTrace(ContentTrace content) implements TracePayload {

    static UserMessageTrace from(Content userMessage) {
      return new UserMessageTrace(ContentTrace.from(userMessage));
    }
  }

  /**
   * A model call that threw — {@code llm_error}.
   *
   * <p>Upstream's {@code str(error)} is always a string; Java's {@link Throwable#getMessage()} may
   * be null, which is why the message is optional rather than the literal {@code "null"}.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record LlmErrorTrace(
      @JsonProperty("error_type") String errorType,
      @JsonProperty("error_message") Optional<String> errorMessage,
      Optional<String> model)
      implements TracePayload {

    static LlmErrorTrace from(Throwable error, LlmRequest request) {
      return new LlmErrorTrace(
          error.getClass().getSimpleName(),
          Optional.ofNullable(error.getMessage()),
          request.model());
    }
  }

  /**
   * A tool about to run — {@code tool_call}.
   *
   * <p>{@code args} is a plain map with no {@code NON_EMPTY}: upstream always passes a dict here,
   * so a tool called with no arguments records {@code args: {}} rather than dropping the key.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record ToolCallTrace(
      @JsonProperty("tool_name") String toolName,
      @JsonProperty("function_call_id") Optional<String> functionCallId,
      ImmutableMap<String, Object> args)
      implements TracePayload {

    static ToolCallTrace of(
        String toolName, @Nullable String functionCallId, Map<String, Object> args) {
      return new ToolCallTrace(
          toolName, Optional.ofNullable(functionCallId), SafeSerializer.serializeMap(args));
    }
  }

  /** What a tool returned — {@code tool_response}. */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record ToolResponseTrace(
      @JsonProperty("tool_name") String toolName,
      @JsonProperty("function_call_id") Optional<String> functionCallId,
      ImmutableMap<String, Object> result)
      implements TracePayload {

    static ToolResponseTrace of(
        String toolName, @Nullable String functionCallId, Map<String, Object> result) {
      return new ToolResponseTrace(
          toolName, Optional.ofNullable(functionCallId), SafeSerializer.serializeMap(result));
    }
  }

  /**
   * A tool that threw — {@code tool_error}.
   *
   * <p>Not folded into {@link ToolCallTrace} with two optional fields: the arguments are recorded
   * again here on purpose, so a failure is readable without hunting for the matching call entry.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record ToolErrorTrace(
      @JsonProperty("tool_name") String toolName,
      @JsonProperty("function_call_id") Optional<String> functionCallId,
      ImmutableMap<String, Object> args,
      @JsonProperty("error_type") String errorType,
      @JsonProperty("error_message") Optional<String> errorMessage)
      implements TracePayload {

    static ToolErrorTrace of(
        String toolName,
        @Nullable String functionCallId,
        Map<String, Object> args,
        Throwable error) {
      return new ToolErrorTrace(
          toolName,
          Optional.ofNullable(functionCallId),
          SafeSerializer.serializeMap(args),
          error.getClass().getSimpleName(),
          Optional.ofNullable(error.getMessage()));
    }
  }

  /**
   * Session state as the invocation ended — {@code session_state_snapshot}.
   *
   * <p>Written only when {@code includeSessionState} is on, matching upstream's guard.
   */
  record SessionStateTrace(
      ImmutableMap<String, Object> state, @JsonProperty("event_count") int eventCount)
      implements TracePayload {

    static SessionStateTrace from(Session session) {
      return new SessionStateTrace(
          SafeSerializer.serializeMap(session.state()), session.events().size());
    }
  }
}
