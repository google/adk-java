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

package com.google.adk.workflow;

import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A node's request for user input, which pauses the workflow until the user answers it. A node
 * emits it from {@link Node#runNode}, and the runtime records it as a long-running {@value
 * #FUNCTION_CALL_NAME} function call whose ID is {@link #interruptId}. The user answers with a
 * function response that carries the same ID.
 */
@Experimental
@AutoValue
public abstract class RequestInput {

  /** The name of the function call that carries a request for input. */
  public static final String FUNCTION_CALL_NAME = "adk_request_input";

  static final String INTERRUPT_ID_KEY = "interruptId";
  static final String MESSAGE_KEY = "message";
  static final String PAYLOAD_KEY = "payload";
  static final String RESPONSE_SCHEMA_KEY = "response_schema";

  /** Returns the ID of the request, which the answering function response carries. */
  public abstract String interruptId();

  /** Returns the message to show the user, if any. */
  public abstract Optional<String> message();

  /** Returns additional data for rendering the request, if any. */
  public abstract Optional<Object> payload();

  /**
   * Returns the schema the answer must match, if any. An answer of the form {@code {"result":
   * value}} is unwrapped first, and a string value is read as JSON unless the schema accepts a
   * string.
   */
  public abstract Optional<Schema> responseSchema();

  /** Returns a builder whose interrupt ID is a new random UUID. */
  public static Builder builder() {
    return new AutoValue_RequestInput.Builder().interruptId(UUID.randomUUID().toString());
  }

  public abstract Builder toBuilder();

  /**
   * Returns the long-running function-call event that records this request. It has the model role,
   * so history rewriting keeps it as the call that the answer responds to.
   *
   * @throws IllegalArgumentException if the payload has no JSON form
   */
  Event toEvent(InvocationContext invocationContext) {
    Map<String, @Nullable Object> args = new LinkedHashMap<>();
    args.put(INTERRUPT_ID_KEY, interruptId());
    args.put(MESSAGE_KEY, message().orElse(null));
    // Converted here so a payload with no JSON form fails the asking node, not the session service.
    args.put(PAYLOAD_KEY, payload().map(RequestInput::toJsonValue).orElse(null));
    args.put(RESPONSE_SCHEMA_KEY, responseSchema().map(RequestInput::toJsonValue).orElse(null));
    FunctionCall call =
        FunctionCall.builder().name(FUNCTION_CALL_NAME).id(interruptId()).args(args).build();
    return Event.builder()
        .id(invocationContext.newUuid())
        .timestamp(invocationContext.now().toEpochMilli())
        .author("")
        .content(
            Content.builder()
                .role("model")
                .parts(Part.builder().functionCall(call).build())
                .build())
        .longRunningToolIds(ImmutableSet.of(interruptId()))
        .build();
  }

  private static @Nullable Object toJsonValue(Object value) {
    return JsonBaseModel.getMapper().convertValue(value, Object.class);
  }

  /** Builder for {@link RequestInput}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder interruptId(String interruptId);

    @CanIgnoreReturnValue
    public abstract Builder message(@Nullable String message);

    @CanIgnoreReturnValue
    public abstract Builder payload(@Nullable Object payload);

    @CanIgnoreReturnValue
    public abstract Builder responseSchema(@Nullable Schema responseSchema);

    public abstract RequestInput build();
  }
}
