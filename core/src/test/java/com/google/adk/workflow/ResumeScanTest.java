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

import com.google.adk.events.Event;
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
import io.reactivex.rxjava3.core.Flowable;
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
            emitter("chat", "fresh"), recovered, /* dynamic= */ false, /* canceledMidRun= */ false);

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
        ResumeScan.intercept(node, answered, /* dynamic= */ false, /* canceledMidRun= */ false);
    Interception unaskedInterception =
        ResumeScan.intercept(node, unasked, /* dynamic= */ false, /* canceledMidRun= */ false);

    assertThat(answeredInterception.shouldRun()).isTrue();
    assertThat(unaskedInterception.shouldRun()).isTrue();
  }

  @Test
  public void intercept_nothingRecorded_runsADynamicNodeButSkipsAGraphNode() {
    Node node = emitter("n", "out");
    RecoveredNode recovered = new RecoveredNode();

    Interception dynamic =
        ResumeScan.intercept(node, recovered, /* dynamic= */ true, /* canceledMidRun= */ false);
    Interception graph =
        ResumeScan.intercept(node, recovered, /* dynamic= */ false, /* canceledMidRun= */ false);

    assertThat(dynamic.shouldRun()).isTrue();
    assertThat(graph.shouldRun()).isFalse();
  }

  @Test
  public void intercept_nodeCanceledMidRun_runsAgainAsAGraphNode() {
    RecoveredNode progressOnly = new RecoveredNode();

    Interception interception =
        ResumeScan.intercept(
            emitter("n", "out"), progressOnly, /* dynamic= */ false, /* canceledMidRun= */ true);

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
            emitter("n", "out"), recovered, /* dynamic= */ false, /* canceledMidRun= */ false);

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
            rerunNode, partialProgress, /* dynamic= */ false, /* canceledMidRun= */ false);
    Interception rerunWithoutProgress =
        ResumeScan.intercept(
            rerunNode, noProgress, /* dynamic= */ false, /* canceledMidRun= */ false);
    Interception staticWithProgress =
        ResumeScan.intercept(
            graphNode, partialProgress, /* dynamic= */ false, /* canceledMidRun= */ false);

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
}
