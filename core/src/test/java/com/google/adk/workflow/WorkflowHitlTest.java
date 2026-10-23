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

import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.AGE_SCHEMA;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.events.Event;
import com.google.adk.testing.TestNode;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for pausing a workflow with a {@link RequestInput} until the user answers it. */
@RunWith(JUnit4.class)
public final class WorkflowHitlTest {

  @Test
  public void requestInput_withoutAnInterruptId_getsOne() {
    RequestInput request = RequestInput.builder().message("name?").build();

    assertThat(request.interruptId()).isNotEmpty();
  }

  @Test
  public void requestInput_toBuilder_keepsEveryProperty() {
    RequestInput request =
        RequestInput.builder()
            .interruptId("ask_1")
            .message("age?")
            .payload(ImmutableMap.of("hint", "years"))
            .responseSchema(AGE_SCHEMA)
            .build();

    assertThat(request.toBuilder().build()).isEqualTo(request);
  }

  @Test
  public void requestInput_emitted_pausesTheWorkflowWithTheRequest() {
    Node ask =
        ask(
            "ask",
            RequestInput.builder()
                .interruptId("ask_1")
                .message("Please provide user details.")
                .payload(ImmutableMap.of("fields", "name, age"))
                .build());
    Node reply = emitter("reply", "done");

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, reply))).turn(go());

    Event requestEvent = requestEvents(events).stream().collect(onlyElement());
    FunctionCall call = requestEvent.functionCalls().get(0);
    assertThat(call.name()).hasValue(RequestInput.FUNCTION_CALL_NAME);
    assertThat(call.id()).hasValue("ask_1");
    assertThat(call.args().orElseThrow())
        .containsAtLeast(
            RequestInput.MESSAGE_KEY,
            "Please provide user details.",
            RequestInput.PAYLOAD_KEY,
            ImmutableMap.of("fields", "name, age"));
    assertThat(requestEvent.longRunningToolIds()).hasValue(ImmutableSet.of("ask_1"));
    // History rewriting drops an event without a role, which would orphan the answer.
    assertThat(requestEvent.content().flatMap(Content::role)).hasValue("model");
    assertThat(pathsOf(events)).doesNotContain("wf@1/reply@1");
  }

  @Test
  public void requestInput_withAPayloadThatHasNoJsonForm_failsTheAskingNode() {
    Node ask =
        ask("ask", RequestInput.builder().interruptId("ask_1").payload(new Object()).build());

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, ask))).failingTurn(go());

    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(requestEvents(turn.events())).isEmpty();
  }

  @Test
  public void resume_answeredRequest_completesTheNodeAndRunsItsSuccessor() {
    Node ask = ask("ask", request("ask_1"));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());
    ImmutableMap<String, Object> details = ImmutableMap.of("name", "John", "age", 30);

    ImmutableList<Event> events = session.turn(requestAnswer("ask_1", details));

    assertThat(replyInput(events)).isEqualTo(details);
  }

  @Test
  public void resume_objectAnswerWithAWrongType_failsNamingTheInterrupt() {
    WorkflowSession session = sessionAwaitingAnswer(AGE_SCHEMA);
    Content reply = requestAnswer("ask_1", ImmutableMap.of("age", "25"));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> session.turn(reply));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt ask_1");
  }

  @Test
  public void resume_objectAnswerWithoutARequiredProperty_failsNamingTheInterrupt() {
    WorkflowSession session = sessionAwaitingAnswer(AGE_SCHEMA);
    Content reply = requestAnswer("ask_1", ImmutableMap.of("wrong", 1));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> session.turn(reply));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt ask_1");
  }

  @Test
  public void resume_objectAnswerWithAnUndeclaredKey_failsWithoutNamingTheKey() {
    WorkflowSession session = sessionAwaitingAnswer(AGE_SCHEMA);
    Content reply = requestAnswer("ask_1", ImmutableMap.of("age", 3, "s3cret", 1));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> session.turn(reply));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt ask_1");
    assertThat(error).hasMessageThat().doesNotContain("s3cret");
  }

  @Test
  public void resume_objectAnswerWithoutAnOptionalProperty_leavesItOut() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                ImmutableMap.of(
                    "name", Schema.builder().type(Type.Known.STRING).build(),
                    "age", Schema.builder().type(Type.Known.INTEGER).build()))
            .required("name")
            .build();
    WorkflowSession session = sessionAwaitingAnswer(schema);

    ImmutableList<Event> events =
        session.turn(requestAnswer("ask_1", ImmutableMap.of("name", "Ada")));

    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("name", "Ada"));
  }

  @Test
  public void resume_jsonLikeTextAnswerToAStringSchema_staysText() {
    WorkflowSession session =
        sessionAwaitingAnswer(Schema.builder().type(Type.Known.STRING).build());

    ImmutableList<Event> events =
        session.turn(requestAnswer("ask_1", ImmutableMap.of("result", "42")));

    assertThat(replyInput(events)).isEqualTo("42");
  }

  @Test
  public void resume_textAnswerToAnAnyOfWithAString_staysText() {
    Schema intOrString =
        Schema.builder()
            .anyOf(
                Schema.builder().type(Type.Known.INTEGER).build(),
                Schema.builder().type(Type.Known.STRING).build())
            .build();
    WorkflowSession session = sessionAwaitingAnswer(intOrString);

    ImmutableList<Event> events =
        session.turn(requestAnswer("ask_1", ImmutableMap.of("result", "42")));

    assertThat(replyInput(events)).isEqualTo("42");
  }

  @Test
  public void resume_textAnswerToABooleanSchema_isReadAsJson() {
    WorkflowSession session =
        sessionAwaitingAnswer(Schema.builder().type(Type.Known.BOOLEAN).build());

    ImmutableList<Event> events =
        session.turn(requestAnswer("ask_1", ImmutableMap.of("result", "true")));

    assertThat(replyInput(events)).isEqualTo(true);
  }

  @Test
  public void resume_jsonNullTextAnswerToANullableSchema_isANullAnswer() {
    WorkflowSession session =
        sessionAwaitingAnswer(Schema.builder().type(Type.Known.BOOLEAN).nullable(true).build());

    ImmutableList<Event> events =
        session.turn(requestAnswer("ask_1", ImmutableMap.of("result", "null")));

    assertThat(replyInput(events)).isNull();
  }

  @Test
  public void resume_nullAnswerToANonNullableSchema_failsNamingTheInterrupt() {
    WorkflowSession session = sessionAwaitingAnswer(AGE_SCHEMA);
    Content reply = requestAnswer("ask_1", Collections.singletonMap("result", null));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> session.turn(reply));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt ask_1");
  }

  @Test
  public void resume_jsonNullTextAnswerToANonNullableSchema_failsNamingTheInterrupt() {
    WorkflowSession session =
        sessionAwaitingAnswer(Schema.builder().type(Type.Known.BOOLEAN).build());
    Content reply = requestAnswer("ask_1", ImmutableMap.of("result", "null"));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> session.turn(reply));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt ask_1");
  }

  /** A session paused on {@code ask_1}, whose answer must match {@code schema}. */
  private static WorkflowSession sessionAwaitingAnswer(Schema schema) {
    Node ask =
        ask(
            "ask",
            RequestInput.builder()
                .interruptId("ask_1")
                .message("?")
                .responseSchema(schema)
                .build());
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());
    return session;
  }

  private static ImmutableList<Event> requestEvents(List<Event> events) {
    return events.stream()
        .filter(
            event ->
                event.functionCalls().stream()
                    .anyMatch(
                        call -> call.name().orElse("").equals(RequestInput.FUNCTION_CALL_NAME)))
        .collect(toImmutableList());
  }

  private static Content requestAnswer(String interruptId, Map<String, ?> response) {
    return answer(interruptId, response, RequestInput.FUNCTION_CALL_NAME);
  }

  private static RequestInput request(String interruptId) {
    return RequestInput.builder().interruptId(interruptId).message("need input").build();
  }

  private static Node ask(String name, RequestInput request) {
    return new TestNode(name, (ctx, input) -> Flowable.just(request));
  }
}
