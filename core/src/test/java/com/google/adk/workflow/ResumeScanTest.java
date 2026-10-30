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

import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.workflow.WorkflowResumeTesting.AGE_SCHEMA;
import static com.google.adk.workflow.WorkflowResumeTesting.LONG_RUNNING_CALL;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.callContent;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.workflow.ResumeScan.Interception;
import com.google.adk.workflow.ResumeScan.RecoveredNode;
import com.google.adk.workflow.WorkflowResumeTesting.MessageOutputNode;
import com.google.adk.workflow.WorkflowResumeTesting.WaitingNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ResumeScan}. */
@RunWith(JUnit4.class)
public final class ResumeScanTest {

  /** An object schema with an integer {@code count}. */
  private static final Schema COUNT_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(ImmutableMap.of("count", Schema.builder().type(Type.Known.INTEGER).build()))
          .build();

  private static final Schema INTEGER_ARRAY_SCHEMA =
      Schema.builder()
          .type(Type.Known.ARRAY)
          .items(Schema.builder().type(Type.Known.INTEGER).build())
          .build();

  private static final Schema NULL_SCHEMA = Schema.builder().type(Type.Known.NULL).build();

  @Test
  public void scan_interruptRaisedBelowAChild_isAnsweredOnThatChild() {
    ImmutableList<Event> events =
        ImmutableList.of(
            interruptEvent("wf@1/d@1/c@c", "ask_1"), answerEvent("ask_1", ImmutableMap.of("v", 1)));

    ImmutableMap<String, RecoveredNode> recovered = ResumeScan.scan(events, "wf@1", "inv");

    assertThat(recovered.keySet()).containsExactly("d@1");
    assertThat(recovered.get("d@1").resolvedResponses)
        .containsExactly("ask_1", ImmutableMap.of("v", 1));
  }

  @Test
  public void scan_outputDelegatedByAChild_leavesTheOwnersBranchUnset() {
    Event delegated =
        nodeEvent("wf@1/d@1/c@c")
            .branch("d@1.c@c")
            .output("out")
            .nodeInfo(
                NodeInfo.builder()
                    .path("wf@1/d@1/c@c")
                    .outputFor(ImmutableList.of("wf@1/d@1/c@c", "wf@1/d@1"))
                    .build())
            .build();

    ImmutableMap<String, RecoveredNode> recovered =
        ResumeScan.scan(ImmutableList.of(delegated), "wf@1", "inv");

    assertThat(recovered.get("d@1").output).isEqualTo("out");
    assertThat(recovered.get("d@1").branch).isNull();
  }

  @Test
  public void replayContext_withoutARecordedBranch_replaysOnTheScheduledBranch() {
    Context parent = parentContext();
    InvocationContext scheduled = parent.invocationContext().toBuilder().branch("root.a@1").build();

    Context replayed =
        ResumeScan.replayContext(
            parent,
            scheduled,
            TestNode.of("a"),
            "1",
            /* useAsOutput= */ false,
            Interception.replay(
                "out", /* route= */ null, /* branch= */ null, /* transferToAgent= */ null));

    assertThat(replayed.invocationContext().branch()).hasValue("root.a@1");
  }

  @Test
  public void replayContext_withARecordedBranch_replaysOnThatBranch() {
    Context parent = parentContext();
    InvocationContext scheduled = parent.invocationContext().toBuilder().branch("root.a@1").build();

    Context replayed =
        ResumeScan.replayContext(
            parent,
            scheduled,
            TestNode.of("a"),
            "1",
            /* useAsOutput= */ false,
            Interception.replay("out", /* route= */ null, "recorded", /* transferToAgent= */ null));

    assertThat(replayed.invocationContext().branch()).hasValue("recorded");
  }

  @Test
  public void scan_requestForInputWithoutLongRunningToolIds_isAnInterrupt() {
    // Sessions recorded before requests carried long-running tool IDs hold only the call.
    ImmutableList<Event> events =
        ImmutableList.of(
            nodeEvent("wf@1/ask@1")
                .content(callContent(RequestInput.FUNCTION_CALL_NAME, "ask_1"))
                .build());

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("ask@1");

    assertThat(recovered.unresolved()).containsExactly("ask_1");
  }

  @Test
  public void scan_answerFromAnotherInvocation_resolvesNothing() {
    ImmutableList<Event> events =
        ImmutableList.of(
            interruptEvent("wf@1/ask@1", "ask_1"),
            answerEvent("ask_1", ImmutableMap.of("v", 1)).toBuilder()
                .invocationId("other")
                .build());

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("ask@1");

    assertThat(recovered.unresolved()).containsExactly("ask_1");
  }

  @Test
  public void scan_interruptRaisedAgainAfterItsAnswer_waitsForANewAnswer() {
    ImmutableList<Event> events =
        ImmutableList.of(
            interruptEvent("wf@1/ask@1", "ask_1"),
            answerEvent("ask_1", ImmutableMap.of("v", 1)),
            interruptEvent("wf@1/ask@1", "ask_1"));

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("ask@1");

    assertThat(recovered.unresolved()).containsExactly("ask_1");
  }

  @Test
  public void scan_outputOnAnEventWithAnErrorCode_replaysTheOutput() {
    ImmutableList<Event> events =
        ImmutableList.of(
            nodeEvent("wf@1/chat@1")
                .output("cut short")
                .errorCode(new FinishReason("MAX_TOKENS"))
                .build());

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("chat@1");
    Interception interception =
        ResumeScan.intercept(
            emitter("chat", "fresh"),
            recovered,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);

    assertThat(recovered.failed).isFalse();
    assertThat(interception.shouldRun()).isFalse();
    assertThat(interception.output()).isEqualTo("cut short");
  }

  @Test
  public void scan_partialEvent_isIgnored() {
    ImmutableList<Event> events =
        ImmutableList.of(
            nodeEvent("wf@1/chat@1").errorCode(new FinishReason("RuntimeError")).build(),
            nodeEvent("wf@1/chat@1")
                .partial(true)
                .content(Content.builder().role("model").parts(Part.fromText("partial")).build())
                .nodeInfo(NodeInfo.builder().path("wf@1/chat@1").messageAsOutput(true).build())
                .build());

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("chat@1");

    assertThat(recovered.output).isNull();
    assertThat(recovered.failed).isTrue();
    assertThat(recovered.finishedAfterResume).isFalse();
  }

  @Test
  public void scan_answerOwnedByAnotherInterrupt_doesNotResolveByTheBranchsRunId() {
    ImmutableList<Event> events =
        ImmutableList.of(
            interruptEvent("wf@1/a@1", "1"),
            interruptEvent("wf@1/b@1", "ask_b"),
            answerEvent("ask_b", ImmutableMap.of("result", "ok")).toBuilder()
                .branch("wf@1.b@1")
                .build());

    ImmutableMap<String, RecoveredNode> recovered = ResumeScan.scan(events, "wf@1", "inv");

    assertThat(recovered.get("a@1").resolvedResponses).isEmpty();
    assertThat(recovered.get("b@1").resolvedResponses).containsExactly("ask_b", "ok");
  }

  @Test
  public void scan_answerOnABranchNamedAfterAnInterrupt_resolvesThatInterrupt() {
    ImmutableList<Event> events =
        ImmutableList.of(
            interruptEvent("wf@1/ask@1", "ask_1"),
            answerEvent("call_9", ImmutableMap.of("result", "ok")).toBuilder()
                .branch("wf@1.task@ask_1")
                .build());

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("ask@1");

    assertThat(recovered.resolvedResponses).containsExactly("ask_1", "ok");
  }

  @Test
  public void scan_failureAfterAnOutput_dropsTheOutput() {
    ImmutableList<Event> events =
        ImmutableList.of(
            nodeEvent("wf@1/n@1").output("stale").build(),
            nodeEvent("wf@1/n@1").errorCode(new FinishReason("IllegalStateException")).build());

    RecoveredNode recovered = ResumeScan.scan(events, "wf@1", "inv").get("n@1");

    assertThat(recovered.output).isNull();
    assertThat(recovered.failed).isTrue();
  }

  @Test
  public void intercept_finishedRerunNodeThatWaitsForOutput_stillRuns() {
    Node node = new WaitingNode("n", (ctx, input) -> Flowable.empty(), /* rerunOnResume= */ true);
    RecoveredNode answered = new RecoveredNode();
    answered.interruptIds.add("a");
    answered.resolvedResponses.put("a", 1);
    answered.finishedAfterResume = true;
    RecoveredNode unasked = new RecoveredNode();
    unasked.finishedAfterResume = true;

    Interception answeredInterception =
        ResumeScan.intercept(
            node,
            answered,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);
    Interception unaskedInterception =
        ResumeScan.intercept(
            node,
            unasked,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);

    assertThat(answeredInterception.shouldRun()).isTrue();
    assertThat(unaskedInterception.shouldRun()).isTrue();
  }

  @Test
  public void intercept_nothingRecorded_runsADynamicNodeButSkipsAGraphNode() {
    Node node = emitter("n", "out");
    RecoveredNode recovered = new RecoveredNode();

    Interception dynamic =
        ResumeScan.intercept(
            node,
            recovered,
            /* dynamic= */ true,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);
    Interception graph =
        ResumeScan.intercept(
            node,
            recovered,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);

    assertThat(dynamic.shouldRun()).isTrue();
    assertThat(graph.shouldRun()).isFalse();
  }

  @Test
  public void intercept_nodeCanceledMidRun_runsAgainAsAGraphNode() {
    RecoveredNode progressOnly = new RecoveredNode();

    Interception interception =
        ResumeScan.intercept(
            emitter("n", "out"),
            progressOnly,
            /* dynamic= */ false,
            /* canceledMidRun= */ true,
            /* failedThisTurn= */ false);

    assertThat(interception.shouldRun()).isTrue();
  }

  @Test
  public void intercept_nodeThatFailedThisTurn_runsAgainDespiteARecordedOutput() {
    RecoveredNode recovered = new RecoveredNode();
    recovered.output = "out";

    Interception interception =
        ResumeScan.intercept(
            emitter("n", "out"),
            recovered,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ true);

    assertThat(interception.shouldRun()).isTrue();
  }

  @Test
  public void intercept_answeredNodeThatDoesNotRerun_completesWithAllAnswersAsAMap() {
    RecoveredNode recovered = new RecoveredNode();
    recovered.interruptIds.addAll(ImmutableList.of("a", "b"));
    recovered.resolvedResponses.put("a", 1);
    recovered.resolvedResponses.put("b", 2);

    Interception interception =
        ResumeScan.intercept(
            emitter("n", "out"),
            recovered,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);

    assertThat(interception.shouldRun()).isFalse();
    assertThat(interception.output()).isEqualTo(ImmutableMap.of("a", 1, "b", 2));
  }

  @Test
  public void intercept_unresolvedInterruptWithPartialProgress_runsOnlyANodeThatRerunsOnResume() {
    RecoveredNode partialProgress = new RecoveredNode();
    partialProgress.interruptIds.addAll(ImmutableList.of("a", "b"));
    partialProgress.resolvedResponses.put("a", 1);
    RecoveredNode noProgress = new RecoveredNode();
    noProgress.interruptIds.addAll(ImmutableList.of("a", "b"));
    Node rerunNode = dispatcher("rerun", (ctx, input) -> Flowable.empty());
    Node graphNode = emitter("static", "out");

    Interception rerunWithProgress =
        ResumeScan.intercept(
            rerunNode,
            partialProgress,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);
    Interception rerunWithoutProgress =
        ResumeScan.intercept(
            rerunNode,
            noProgress,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);
    Interception staticWithProgress =
        ResumeScan.intercept(
            graphNode,
            partialProgress,
            /* dynamic= */ false,
            /* canceledMidRun= */ false,
            /* failedThisTurn= */ false);

    assertThat(rerunWithProgress.shouldRun()).isTrue();
    assertThat(rerunWithProgress.resumeInputs()).containsExactly("a", 1);
    assertThat(rerunWithoutProgress.shouldRun()).isFalse();
    assertThat(rerunWithoutProgress.interrupts()).containsExactly("a", "b");
    assertThat(staticWithProgress.shouldRun()).isFalse();
    assertThat(staticWithProgress.interrupts()).containsExactly("b");
  }

  @Test
  public void processRehydratedOutput_outputNoLongerFitsTheSchema_resumesUnvalidated() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ImmutableMap.of("city", Schema.builder().type(Type.Known.STRING).build()))
            .required("city")
            .build();

    Object output =
        ResumeScan.processRehydratedOutput(
            new MessageOutputNode("chat", "", schema), modelText("{\"town\": \"Paris\"}"));

    assertThat(output).isEqualTo(ImmutableMap.of("town", "Paris"));
  }

  @Test
  public void processRehydratedOutput_textThatIsNotJsonForAnObjectSchema_fails() {
    Node node = new MessageOutputNode("chat", "", AGE_SCHEMA);
    Content notJsonText = modelText("not json");
    Content bareWordText = modelText("hello");

    IllegalArgumentException notJson =
        assertThrows(
            IllegalArgumentException.class,
            () -> ResumeScan.processRehydratedOutput(node, notJsonText));
    IllegalArgumentException bareWord =
        assertThrows(
            IllegalArgumentException.class,
            () -> ResumeScan.processRehydratedOutput(node, bareWordText));

    assertThat(notJson)
        .hasMessageThat()
        .contains("Validation failed for rehydrated output against schema");
    assertThat(bareWord)
        .hasMessageThat()
        .contains("Validation failed for rehydrated output against schema");
  }

  @Test
  public void processRehydratedOutput_blankText_isNoOutput() {
    Object output =
        ResumeScan.processRehydratedOutput(
            new MessageOutputNode("chat", "", COUNT_SCHEMA), modelText("  "));

    assertThat(output).isNull();
  }

  @Test
  public void processRehydratedOutput_jsonNullForAnObjectSchema_isNoOutput() {
    Object output =
        ResumeScan.processRehydratedOutput(
            new MessageOutputNode("chat", "", AGE_SCHEMA), modelText("null"));

    assertThat(output).isNull();
  }

  @Test
  public void processRehydratedOutput_stringSchema_keepsTheText() {
    Node node = new MessageOutputNode("chat", "", Schema.builder().type(Type.Known.STRING).build());

    assertThat(ResumeScan.processRehydratedOutput(node, modelText("42"))).isEqualTo("42");
  }

  @Test
  public void validateAnswer_answerThatFits_isReturned() {
    Object answer = ResumeScan.validateAnswer(COUNT_SCHEMA, ImmutableMap.of("count", 1), "ask_1");

    assertThat(answer).isEqualTo(ImmutableMap.of("count", 1));
  }

  @Test
  public void validateAnswer_objectTheSchemaRejects_failsWithoutTheValue() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ResumeScan.validateAnswer(
                    COUNT_SCHEMA, ImmutableMap.of("result", "{\"count\": \"1\"}"), "ask_1"));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt ask_1");
    assertThat(error).hasMessageThat().doesNotContain("\"1\"");
  }

  @Test
  public void validateAnswer_textTheSchemaRejects_failsWithoutTheValue() {
    Schema booleanSchema = Schema.builder().type(Type.Known.BOOLEAN).build();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ResumeScan.validateAnswer(booleanSchema, ImmutableMap.of("result", "yes"), "a"));

    assertThat(error).hasMessageThat().contains("Validation failed for interrupt a");
    assertThat(error).hasMessageThat().doesNotContain("yes");
  }

  @Test
  public void validateAnswer_arrayOfMatchingItems_isReturned() {
    Object answer =
        ResumeScan.validateAnswer(
            INTEGER_ARRAY_SCHEMA, ImmutableMap.of("result", ImmutableList.of(1, 2)), "a");

    assertThat(answer).isEqualTo(ImmutableList.of(1, 2));
  }

  @Test
  public void validateAnswer_arrayWithAnItemOfAnotherType_fails() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResumeScan.validateAnswer(
                INTEGER_ARRAY_SCHEMA, ImmutableMap.of("result", ImmutableList.of(1, "2")), "a"));
  }

  @Test
  public void validateAnswer_numberTextForANumberSchema_isReadAsANumber() {
    Schema numberSchema = Schema.builder().type(Type.Known.NUMBER).build();

    Object answer = ResumeScan.validateAnswer(numberSchema, ImmutableMap.of("result", "2.5"), "a");

    assertThat(answer).isEqualTo(2.5);
  }

  @Test
  public void validateAnswer_textForANumberSchema_fails() {
    Schema numberSchema = Schema.builder().type(Type.Known.NUMBER).build();

    assertThrows(
        IllegalArgumentException.class,
        () -> ResumeScan.validateAnswer(numberSchema, ImmutableMap.of("result", "many"), "a"));
  }

  @Test
  public void validateAnswer_nullForANullSchema_isNull() {
    Object answer =
        ResumeScan.validateAnswer(NULL_SCHEMA, Collections.singletonMap("result", null), "a");

    assertThat(answer).isNull();
  }

  @Test
  public void validateAnswer_valueForANullSchema_fails() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ResumeScan.validateAnswer(NULL_SCHEMA, ImmutableMap.of("result", 1), "a"));
  }

  @Test
  public void validateAnswer_nullForAnAnyOfThatAllowsNull_isNull() {
    Schema ageOrNull = Schema.builder().anyOf(AGE_SCHEMA, NULL_SCHEMA).build();

    Object answer =
        ResumeScan.validateAnswer(ageOrNull, Collections.singletonMap("result", null), "a");

    assertThat(answer).isNull();
  }

  @Test
  public void validateAnswer_objectThatMatchesOneAnyOfAlternative_isReturned() {
    Schema countOrAge = Schema.builder().anyOf(COUNT_SCHEMA, AGE_SCHEMA).build();

    Object answer = ResumeScan.validateAnswer(countOrAge, ImmutableMap.of("age", 3), "a");

    assertThat(answer).isEqualTo(ImmutableMap.of("age", 3));
  }

  @Test
  public void validateAnswer_objectThatMatchesNoAnyOfAlternative_fails() {
    Schema countOrAge = Schema.builder().anyOf(COUNT_SCHEMA, AGE_SCHEMA).build();

    assertThrows(
        IllegalArgumentException.class,
        () -> ResumeScan.validateAnswer(countOrAge, ImmutableMap.of("height", 3), "a"));
  }

  /**
   * Returns an event in which the node at {@code path} waits on the long-running call {@code
   * interruptId}.
   */
  private static Event interruptEvent(String path, String interruptId) {
    return nodeEvent(path)
        .content(callContent(LONG_RUNNING_CALL, interruptId))
        .longRunningToolIds(ImmutableSet.of(interruptId))
        .build();
  }

  /** A user's answer event as a session holds it. */
  private static Event answerEvent(String interruptId, Map<String, ?> response) {
    return Event.builder()
        .id(Event.generateEventId())
        .author("user")
        .invocationId("inv")
        .content(answer(interruptId, response))
        .build();
  }

  private static Event.Builder nodeEvent(String path) {
    return newEvent()
        .author("wf")
        .invocationId("inv")
        .nodeInfo(NodeInfo.builder().path(path).build());
  }

  /** Returns the context of a node named {@code root} that runs on the {@code root} branch. */
  private static Context parentContext() {
    return new Context(
        TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
            .branch("root")
            .build(),
        NodeExecutionState.builder()
            .node(TestNode.of("root"))
            .eventSink((event, nodeFailure) -> Completable.complete())
            .runId("1")
            .build());
  }
}
