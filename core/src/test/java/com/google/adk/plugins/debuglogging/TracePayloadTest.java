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
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.plugins.debuglogging.TracePayload.BranchTrace;
import com.google.adk.plugins.debuglogging.TracePayload.LlmErrorTrace;
import com.google.adk.plugins.debuglogging.TracePayload.MarkerTrace;
import com.google.adk.plugins.debuglogging.TracePayload.SessionStateTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolCallTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolErrorTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolResponseTrace;
import com.google.adk.plugins.debuglogging.TracePayload.UserMessageTrace;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the eight payload shapes the state layer adds, on their serialized form rather than on their
 * components — the wire keys are what a reader of a trace, or a script comparing adk-java's output
 * with adk-python's, actually sees.
 */
@RunWith(JUnit4.class)
public class TracePayloadTest {

  private static final String TOOL = "lookup_order";
  private static final String CALL_ID = "call-7";

  private final ObjectMapper mapper = DebugYamlWriter.configure(new ObjectMapper());

  private String serialize(TracePayload payload) throws Exception {
    return mapper.writeValueAsString(payload);
  }

  @Test
  public void userMessage_delegatesToContentTrace() throws Exception {
    Content message =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text("where is my order?").build()))
            .build();

    assertThat(serialize(UserMessageTrace.from(message)))
        .isEqualTo(
            "{\"content\":{\"role\":\"user\",\"parts\":[{\"text\":\"where is my order?\"}]}}");
  }

  @Test
  public void branch_whenSet_isRecorded() throws Exception {
    assertThat(serialize(new BranchTrace(Optional.of("root.researcher"))))
        .isEqualTo("{\"branch\":\"root.researcher\"}");
  }

  /** A root invocation has no branch, and upstream's {@code exclude_none} drops the key. */
  @Test
  public void branch_whenAbsent_leavesAnEmptyPayload() throws Exception {
    assertThat(serialize(new BranchTrace(Optional.empty()))).isEqualTo("{}");
  }

  /** The two marker types must still write {@code data: {}} rather than fail as an empty bean. */
  @Test
  public void marker_serializesAsAnEmptyMapping() throws Exception {
    assertThat(serialize(MarkerTrace.INSTANCE)).isEqualTo("{}");
  }

  @Test
  public void llmError_recordsTypeMessageAndModel() throws Exception {
    LlmRequest request = LlmRequest.builder().model("gemini-2.0-flash").build();

    assertThat(serialize(LlmErrorTrace.from(new IllegalStateException("quota exhausted"), request)))
        .isEqualTo(
            "{\"error_type\":\"IllegalStateException\",\"error_message\":\"quota exhausted\","
                + "\"model\":\"gemini-2.0-flash\"}");
  }

  /** Python's {@code str(error)} is always a string; a Java throwable may carry no message. */
  @Test
  public void llmError_withoutAMessage_omitsTheKeyRatherThanWritingNull() throws Exception {
    LlmRequest request = LlmRequest.builder().model("gemini-2.0-flash").build();

    assertThat(serialize(LlmErrorTrace.from(new IllegalStateException(), request)))
        .doesNotContain("error_message");
  }

  @Test
  public void toolCall_recordsNameCallIdAndArgs() throws Exception {
    ImmutableMap<String, Object> args = ImmutableMap.of("orderId", 42);

    assertThat(serialize(ToolCallTrace.of(TOOL, CALL_ID, args)))
        .isEqualTo(
            "{\"tool_name\":\"lookup_order\",\"function_call_id\":\"call-7\","
                + "\"args\":{\"orderId\":42}}");
  }

  /**
   * Upstream always passes a dict here, so a no-argument call records {@code args: {}}. This is the
   * one place the port deliberately does <em>not</em> use {@code NON_EMPTY}.
   */
  @Test
  public void toolCall_withNoArguments_stillEmitsTheArgsKey() throws Exception {
    assertThat(serialize(ToolCallTrace.of(TOOL, CALL_ID, new HashMap<>()))).contains("\"args\":{}");
  }

  @Test
  public void toolCall_argumentsGoThroughSafeSerializer() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("upload", new byte[] {1, 2, 3});

    assertThat(serialize(ToolCallTrace.of(TOOL, CALL_ID, args)))
        .contains("\"upload\":\"<bytes: 3 bytes>\"");
  }

  @Test
  public void toolResponse_recordsTheResult() throws Exception {
    ImmutableMap<String, Object> result = ImmutableMap.of("status", "shipped");

    assertThat(serialize(ToolResponseTrace.of(TOOL, CALL_ID, result)))
        .isEqualTo(
            "{\"tool_name\":\"lookup_order\",\"function_call_id\":\"call-7\","
                + "\"result\":{\"status\":\"shipped\"}}");
  }

  /** The arguments repeat here on purpose: a failure should be readable without its call entry. */
  @Test
  public void toolError_carriesTheArgumentsAlongsideTheError() throws Exception {
    ImmutableMap<String, Object> args = ImmutableMap.of("orderId", 42);

    String json =
        serialize(
            ToolErrorTrace.of(TOOL, CALL_ID, args, new IllegalArgumentException("no such id")));

    assertThat(json).contains("\"args\":{\"orderId\":42}");
    assertThat(json).contains("\"error_type\":\"IllegalArgumentException\"");
    assertThat(json).contains("\"error_message\":\"no such id\"");
  }

  @Test
  public void sessionState_recordsTheStateAndTheEventCount() throws Exception {
    Map<String, Object> state = new HashMap<>();
    state.put("cart_size", 2);
    Session session =
        Session.builder("session-1")
            .appName("shop")
            .userId("user-1")
            .state(state)
            .events(ImmutableList.of(Event.builder().id("e1").author("user").build()))
            .build();

    assertThat(serialize(SessionStateTrace.from(session)))
        .isEqualTo("{\"state\":{\"cart_size\":2},\"event_count\":1}");
  }
}
