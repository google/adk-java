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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.adk.agents.Context;
import com.google.adk.events.ToolConfirmation;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Static utilities for the confirmation request that a function or tool node run sends to ask the
 * user to approve it, and for the user's answer.
 */
final class ToolConfirmations {

  /** The interrupt ID prefix of a confirmation request, followed by the node path. */
  private static final String INTERRUPT_ID_PREFIX = "wf_tool_confirmation:";

  /** The schema of an answer to a confirmation request. */
  private static final Schema RESPONSE_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "hint", Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                  "confirmed", Schema.builder().type(Type.Known.BOOLEAN).nullable(true).build(),
                  "payload", Schema.builder().build()))
          .build();

  /** Returns the interrupt ID of the confirmation request of the node run in {@code context}. */
  private static String interruptId(Context context) {
    return INTERRUPT_ID_PREFIX + context.requireNodeState().nodePath();
  }

  /**
   * Returns the request that asks the user to approve calling {@code toolName} with {@code args}. A
   * non-empty {@code hint} replaces the default message, and a non-null {@code confirmationPayload}
   * goes into the request's payload.
   */
  static RequestInput request(
      Context context,
      String toolName,
      @Nullable Object args,
      @Nullable String hint,
      @Nullable Object confirmationPayload) {
    Map<String, @Nullable Object> payload = new LinkedHashMap<>();
    payload.put("tool_name", toolName);
    payload.put("args", args);
    if (confirmationPayload != null) {
      payload.put("confirmation_payload", confirmationPayload);
    }
    return RequestInput.builder()
        .interruptId(interruptId(context))
        .message(
            isNullOrEmpty(hint)
                ? "Please approve or reject the tool call " + toolName + "()."
                : hint)
        .payload(payload)
        .responseSchema(RESPONSE_SCHEMA)
        .build();
  }

  /**
   * Returns the user's answer to the confirmation request of the node run in {@code context}, or
   * null if there is none yet. A {@code confirmed} left out or null rejects the call, and a {@code
   * hint} left out or null reads as an empty hint.
   *
   * @throws WorkflowDataException if the answer is not a {@link ToolConfirmation}
   */
  static @Nullable ToolConfirmation answer(Context context, String nodeName) {
    Object answer = context.resumeInputs().get(interruptId(context));
    if (answer == null) {
      return null;
    }
    if (!(answer instanceof Map<?, ?> map)
        || !RESPONSE_SCHEMA.properties().orElseThrow().keySet().containsAll(map.keySet())) {
      throw notAToolConfirmation(nodeName);
    }
    Object confirmed = map.get("confirmed");
    Object hint = map.get("hint");
    if ((confirmed != null && !(confirmed instanceof Boolean))
        || (hint != null && !(hint instanceof String))) {
      throw notAToolConfirmation(nodeName);
    }
    ToolConfirmation.Builder confirmation = ToolConfirmation.builder();
    if (hint instanceof String text) {
      confirmation.hint(text);
    }
    return confirmation
        .confirmed(Boolean.TRUE.equals(confirmed))
        .payload(map.get("payload"))
        .build();
  }

  private static WorkflowDataException notAToolConfirmation(String nodeName) {
    return new WorkflowDataException(
        "The answer to the confirmation request of node '"
            + nodeName
            + "' is not a ToolConfirmation.");
  }

  private ToolConfirmations() {}
}
