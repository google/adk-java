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

import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RootAgentNodeTest {

  @Test
  public void rootNodeAgentWithoutModes_runsOnTheNodePathAsAChatAgent() {
    TestLlm model =
        TestLlm.create(createTextLlmResponse("Which city?"), createTextLlmResponse("Paris."));
    LlmAgent assistant =
        LlmAgent.builder()
            .name("assistant")
            .model(model)
            .subAgents(LlmAgent.builder().name("helper").model(TestLlm.create("unused")).build())
            .build();
    WorkflowSession session = new WorkflowSession(assistant);

    ImmutableList<Event> first = session.turn(userText("Plan a trip"));
    ImmutableList<Event> second = session.turn(userText("Paris"));

    assertThat(ImmutableSet.copyOf(pathsOf(first))).containsExactly("assistant@1");
    assertThat(ImmutableSet.copyOf(pathsOf(second))).containsExactly("assistant@1");
    assertThat(texts(model.getLastRequest().contents()))
        .containsExactly("Plan a trip", "Which city?", "Paris")
        .inOrder();
  }

  @Test
  public void rootAgentAppWithAChatMode_staysOnTheClassicFlow() {
    LlmAgent desk =
        LlmAgent.builder()
            .name("desk")
            .model(TestLlm.create(createTextLlmResponse("Desk here.")))
            .build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("desk")), desk);

    ImmutableList<Event> events = runAsRootAgent(coordinator, go());

    assertThat(lastText(events)).isEqualTo("Desk here.");
    assertThat(ImmutableSet.copyOf(pathsOf(events))).containsExactly("");
  }

  @Test
  public void singleTurnRootNodeAgent_isRejectedWhenTheRunnerIsBuilt() {
    LlmAgent root =
        LlmAgent.builder()
            .name("root")
            .model(TestLlm.create("ok"))
            .mode(AgentMode.SINGLE_TURN)
            .build();

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new WorkflowSession(root));

    assertThat(error).hasMessageThat().contains("got mode='single_turn'");
  }

  @Test
  public void chatRoot_runsAsAWorkflowNodeAndKeepsTheConversation() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which city?"), createTextLlmResponse("Paris is lovely."));
    LlmAgent guide = LlmAgent.builder().name("guide").model(model).mode(AgentMode.CHAT).build();
    WorkflowSession session = new WorkflowSession(guide);

    ImmutableList<Event> first = session.turn(userText("Plan a trip"));
    ImmutableList<Event> second = session.turn(userText("Paris"));

    assertThat(ImmutableSet.copyOf(pathsOf(first))).containsExactly("guide@1");
    assertThat(ImmutableSet.copyOf(pathsOf(second))).containsExactly("guide@1");
    assertThat(texts(model.getLastRequest().contents()))
        .containsExactly("Plan a trip", "Which city?", "Paris")
        .inOrder();
  }

  @Test
  public void transferChain_runsEachTargetAsANodeBelowTheAgentThatHandedOff() {
    LlmAgent desk =
        LlmAgent.builder()
            .name("desk")
            .model(TestLlm.create(createTextLlmResponse("Desk here.")))
            .build();
    LlmAgent billing =
        LlmAgent.builder()
            .name("billing")
            .model(TestLlm.create(transferTo("desk")))
            .subAgents(desk)
            .build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("billing")))
            .mode(AgentMode.CHAT)
            .subAgents(billing)
            .build();

    ImmutableList<Event> events = new WorkflowSession(coordinator).turn(go());

    assertThat(ImmutableSet.copyOf(pathsOf(events)))
        .containsExactly(
            "coordinator@1", "coordinator@1/billing@1", "coordinator@1/billing@1/desk@1");
    assertThat(lastText(events)).isEqualTo("Desk here.");
  }

  @Test
  public void followUpAtASubAgent_runsUnderTheRootsPath_andAHopBackGetsANewRunId() {
    // The root sets no mode, so as the app's root node it runs as a chat agent.
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create(createTextLlmResponse("Helper here."), transferTo("coordinator")))
            .mode(AgentMode.CHAT)
            .build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("helper"), createTextLlmResponse("Back at the root.")))
            .subAgents(helper)
            .build();
    WorkflowSession session = new WorkflowSession(coordinator);

    ImmutableList<Event> first = session.turn(userText("Hello"));
    ImmutableList<Event> second = session.turn(userText("Take me back"));

    assertThat(ImmutableSet.copyOf(pathsOf(first)))
        .containsExactly("coordinator@1", "coordinator@1/helper@1");
    assertThat(ImmutableSet.copyOf(pathsOf(second)))
        .containsExactly("coordinator@1/helper@1", "coordinator@2");
    assertThat(lastText(second)).isEqualTo("Back at the root.");
  }

  @Test
  public void answerToALongRunningCall_continuesTheRootInItsInvocationWithoutResumability() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("Approved."));
    LlmAgent assistant =
        LlmAgent.builder()
            .name("assistant")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    WorkflowSession session = new WorkflowSession(assistant, /* resumable= */ false);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(lastText(second)).isEqualTo("Approved.");
    assertThat(session.invocationIds()).hasSize(1);
  }

  @Test
  public void answerToAHandoffTargetsCall_continuesTheTargetRatherThanTheRoot() {
    TestLlm approverModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("Approved."));
    LlmAgent approver =
        LlmAgent.builder()
            .name("approver")
            .model(approverModel)
            .tools(pendingFunctionTool())
            .build();
    TestLlm rootModel = TestLlm.create(transferTo("approver"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(rootModel)
            .mode(AgentMode.CHAT)
            .subAgents(approver)
            .build();
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(lastText(second)).isEqualTo("Approved.");
    assertThat(ImmutableSet.copyOf(pathsOf(second))).containsExactly("coordinator@1/approver@1");
    assertThat(rootModel.getRequests()).hasSize(1);
  }

  @Test
  public void answerToAChildOfARootThatRanAgain_continuesThatChild() {
    TestLlm approverModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("Approved."));
    LlmAgent approver =
        LlmAgent.builder()
            .name("approver")
            .model(approverModel)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create(createTextLlmResponse("Helper here."), transferTo("coordinator")))
            .mode(AgentMode.CHAT)
            .build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("helper"), transferTo("approver")))
            .mode(AgentMode.CHAT)
            .subAgents(helper, approver)
            .build();
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(userText("Hello"));
    // The helper hands back, so the root runs as coordinator@2 and hands off to the approver.
    ImmutableList<Event> second = session.turn(userText("Take me back"));

    ImmutableList<Event> third =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(ImmutableSet.copyOf(pathsOf(second))).contains("coordinator@2/approver@1");
    assertThat(lastText(third)).isEqualTo("Approved.");
    // The resumed child keeps the path it ran under.
    assertThat(ImmutableSet.copyOf(pathsOf(third))).containsExactly("coordinator@2/approver@1");
  }

  @Test
  public void resumingAFinishedInvocation_doesNotRunTheRootAgain() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Hello."));
    LlmAgent assistant =
        LlmAgent.builder().name("assistant").model(model).mode(AgentMode.CHAT).build();
    WorkflowSession session = new WorkflowSession(assistant);
    String invocationId = session.turn(go()).get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    assertThat(resumed).isEmpty();
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void answerMixedWithText_isRejectedBeforeTheModelRuns() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")));
    LlmAgent assistant =
        LlmAgent.builder()
            .name("assistant")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    WorkflowSession session = new WorkflowSession(assistant);
    session.advance(go());
    Content mixed =
        Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .id("call-1")
                            .name("pendingTool")
                            .response(ImmutableMap.of("status", "approved"))
                            .build())
                    .build(),
                Part.fromText("and thanks"))
            .build();

    Throwable error = session.failingTurn(mixed).error();

    assertThat(error).isInstanceOf(IllegalArgumentException.class);
    assertThat(error).hasMessageThat().contains("both function responses and text");
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void failingChatRoot_persistsAnErrorEventOnItsNodePath() {
    AtomicInteger calls = new AtomicInteger();
    TestLlm model =
        new TestLlm(() ->
            calls.incrementAndGet() == 1
                ? Flowable.just(createTextLlmResponse("hello"))
                : Flowable.error(new IllegalStateException("model boom")));
    LlmAgent assistant =
        LlmAgent.builder().name("assistant").model(model).mode(AgentMode.CHAT).build();
    WorkflowSession session = new WorkflowSession(assistant);
    session.advance(userText("hi"));

    Throwable error = session.failingTurn(userText("again")).error();

    assertThat(error).hasMessageThat().isEqualTo("model boom");
    Event errorEvent = Iterables.getLast(session.storedEvents());
    assertThat(errorEvent.author()).isEqualTo("assistant");
    assertThat(pathOf(errorEvent)).isEqualTo("assistant@1");
    assertThat(errorEvent.errorMessage()).hasValue("model boom");
  }

  @Test
  public void peerHandoffOfAResumedChild_runsBesideItUnderTheRootsLatestRun() {
    TestLlm approverModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            transferTo("helper"));
    LlmAgent approver =
        LlmAgent.builder()
            .name("approver")
            .model(approverModel)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    TestLlm helperModel =
        TestLlm.create(
            createTextLlmResponse("Helper here."),
            transferTo("coordinator"),
            createTextLlmResponse("Helper again."));
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(helperModel).mode(AgentMode.CHAT).build();
    LlmAgent coordinator =
        chatRoot(TestLlm.create(transferTo("helper"), transferTo("approver")), helper, approver);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(userText("Hello"));
    // The helper hands back, so the root runs as coordinator@2 and hands off to the approver.
    session.advance(userText("Take me back"));

    ImmutableList<Event> third =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The helper runs under coordinator@2, so its run under coordinator@1 doesn't replay.
    assertThat(lastText(third)).isEqualTo("Helper again.");
    assertThat(ImmutableSet.copyOf(pathsOf(third)))
        .containsExactly("coordinator@2/approver@1", "coordinator@2/helper@1");
  }

  @Test
  public void rootThatFailedAfterAHandBack_runsAgainOnResume() {
    AtomicInteger calls = new AtomicInteger();
    TestLlm model =
        new TestLlm(() ->
            switch (calls.incrementAndGet()) {
              case 1 -> Flowable.just(transferTo("helper"));
              case 2 -> Flowable.error(new IllegalStateException("model boom"));
              default -> Flowable.just(createTextLlmResponse("Back again."));
            });
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create(transferTo("coordinator")))
            .mode(AgentMode.CHAT)
            .build();
    LlmAgent coordinator = chatRoot(model, helper);
    WorkflowSession session = new WorkflowSession(coordinator);
    String invocationId = session.failingTurn(userText("Hello")).events().get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    // The handoff's end-of-agent marker must not hide that the root failed after it.
    assertThat(lastText(resumed)).isEqualTo("Back again.");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  public void peerThatRanEarlierInTheInvocation_runsAgainWhenAResumedAgentHandsBack() {
    TestLlm approverModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            transferTo("helper"));
    LlmAgent approver =
        LlmAgent.builder()
            .name("approver")
            .model(approverModel)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    TestLlm helperModel =
        TestLlm.create(transferTo("approver"), createTextLlmResponse("Helper again."));
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(helperModel).mode(AgentMode.CHAT).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("helper")), helper, approver);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(userText("Hello"));

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The helper ran before in this invocation, so it runs again rather than replaying.
    assertThat(lastText(second)).isEqualTo("Helper again.");
    assertThat(helperModel.getRequests()).hasSize(2);
    assertThat(approverModel.getRequests()).hasSize(2);
  }

  @Test
  public void answerToAnAgentOnItsSecondRun_resumesThatRun() {
    TestLlm approverModel =
        TestLlm.create(
            transferTo("helper"),
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("Approved."));
    LlmAgent approver =
        LlmAgent.builder()
            .name("approver")
            .model(approverModel)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    TestLlm helperModel = TestLlm.create(transferTo("approver"));
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(helperModel).mode(AgentMode.CHAT).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("approver")), helper, approver);
    WorkflowSession session = new WorkflowSession(coordinator);
    // approver@1 hands off to the helper, which hands back to approver@2, which asks.
    session.advance(userText("Hello"));

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(lastText(second)).isEqualTo("Approved.");
    assertThat(helperModel.getRequests()).hasSize(1);
    assertThat(ImmutableSet.copyOf(pathsOf(second))).containsExactly("coordinator@1/approver@2");
  }

  @Test
  public void answerToAStepOfASequenceHandoffTargetInANonResumableApp_continuesOnlyThatStep() {
    TestLlm step1Model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("Step 1 done."));
    LlmAgent step1 =
        LlmAgent.builder().name("step1").model(step1Model).tools(pendingFunctionTool()).build();
    TestLlm step2Model = TestLlm.create(createTextLlmResponse("Step 2 done."));
    LlmAgent step2 = LlmAgent.builder().name("step2").model(step2Model).build();
    SequentialAgent seq = SequentialAgent.builder().name("seq").subAgents(step1, step2).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("seq")), seq);
    WorkflowSession session = new WorkflowSession(coordinator, /* resumable= */ false);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // Without resumability the sequence did not pause, so step2 already ran and only step1 goes on.
    assertThat(lastText(second)).isEqualTo("Step 1 done.");
    assertThat(step2Model.getRequests()).hasSize(1);
  }

  @Test
  public void answerToASequenceStepUnderALaterRootRunInANonResumableApp_resumesTheStepThere() {
    TestLlm step1Model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("Step 1 done."));
    LlmAgent step1 =
        LlmAgent.builder().name("step1").model(step1Model).tools(pendingFunctionTool()).build();
    LlmAgent step2 =
        LlmAgent.builder()
            .name("step2")
            .model(TestLlm.create(createTextLlmResponse("Step 2 done.")))
            .build();
    SequentialAgent seq = SequentialAgent.builder().name("seq").subAgents(step1, step2).build();
    TestLlm helperModel = TestLlm.create(transferTo("coordinator"));
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(helperModel).mode(AgentMode.CHAT).build();
    LlmAgent coordinator =
        chatRoot(TestLlm.create(transferTo("helper"), transferTo("seq")), helper, seq);
    WorkflowSession session = new WorkflowSession(coordinator, /* resumable= */ false);
    // The helper hands back, so the root runs as coordinator@2 and hands off to the sequence.
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The step resumes under coordinator@2, where it ran, and finds its call there.
    assertThat(lastText(second)).isEqualTo("Step 1 done.");
    assertThat(ImmutableSet.copyOf(pathsOf(second))).containsExactly("coordinator@2/step1@1");
    assertThat(step1Model.getRequests()).hasSize(2);
  }

  @Test
  public void answerToAStepOfASequenceHandoffTarget_runsTheRestOfTheSequence() {
    LlmAgent step1 =
        LlmAgent.builder()
            .name("step1")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Step 1 done.")))
            .tools(pendingFunctionTool())
            .build();
    LlmAgent step2 =
        LlmAgent.builder()
            .name("step2")
            .model(TestLlm.create(createTextLlmResponse("Step 2 done.")))
            .build();
    SequentialAgent seq = SequentialAgent.builder().name("seq").subAgents(step1, step2).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("seq")), seq);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(lastText(second)).isEqualTo("Step 2 done.");
  }

  @Test
  public void answerToAStepOfALoopHandoffTarget_runsTheRestOfTheLoop() {
    LlmAgent step1 =
        LlmAgent.builder()
            .name("step1")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Step 1 done."),
                    createTextLlmResponse("Step 1 again.")))
            .tools(pendingFunctionTool())
            .build();
    TestLlm step2Model =
        TestLlm.create(
            createTextLlmResponse("Step 2 done."), createTextLlmResponse("Step 2 again."));
    LlmAgent step2 = LlmAgent.builder().name("step2").model(step2Model).build();
    LoopAgent loop =
        LoopAgent.builder().name("loop").subAgents(step1, step2).maxIterations(2).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("loop")), loop);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The loop takes the answer, so step2 and the second iteration run too.
    assertThat(lastText(second)).isEqualTo("Step 2 again.");
    assertThat(step2Model.getRequests()).hasSize(2);
  }

  @Test
  public void answerToABranchOfAParallelStepOfASequence_runsTheRestOfTheSequence() {
    LlmAgent first =
        LlmAgent.builder()
            .name("first")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("First done.")))
            .tools(pendingFunctionTool())
            .build();
    LlmAgent second =
        LlmAgent.builder()
            .name("second")
            .model(TestLlm.create(createTextLlmResponse("Second done.")))
            .build();
    ParallelAgent fanout = ParallelAgent.builder().name("fanout").subAgents(first, second).build();
    TestLlm afterModel = TestLlm.create(createTextLlmResponse("After done."));
    LlmAgent after = LlmAgent.builder().name("after").model(afterModel).build();
    SequentialAgent seq = SequentialAgent.builder().name("seq").subAgents(fanout, after).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("seq")), seq);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> answered =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The sequence takes the answer, so the step after the parallel one runs.
    assertThat(lastText(answered)).isEqualTo("After done.");
    assertThat(afterModel.getRequests()).hasSize(1);
  }

  @Test
  public void answerToASubAgentOfASequenceStep_runsTheRestOfTheSequence() {
    LlmAgent sub =
        LlmAgent.builder()
            .name("sub")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Sub done.")))
            .tools(pendingFunctionTool())
            .build();
    LlmAgent mid =
        LlmAgent.builder()
            .name("mid")
            .model(TestLlm.create(transferTo("sub")))
            .subAgents(sub)
            .build();
    TestLlm stepXModel = TestLlm.create(createTextLlmResponse("StepX done."));
    LlmAgent stepX = LlmAgent.builder().name("stepX").model(stepXModel).build();
    SequentialAgent seq = SequentialAgent.builder().name("seq").subAgents(mid, stepX).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("seq")), seq);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The outer sequence takes the answer, so the step after mid runs.
    assertThat(lastText(second)).isEqualTo("StepX done.");
    assertThat(stepXModel.getRequests()).hasSize(1);
  }

  @Test
  public void answerToAStepOfANestedWorkflowBelowASequenceStep_runsTheRestOfTheSequence() {
    LlmAgent step1 =
        LlmAgent.builder()
            .name("step1")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Step 1 done.")))
            .tools(pendingFunctionTool())
            .build();
    SequentialAgent inner = SequentialAgent.builder().name("inner").subAgents(step1).build();
    LlmAgent mid =
        LlmAgent.builder()
            .name("mid")
            .model(TestLlm.create(transferTo("inner")))
            .subAgents(inner)
            .build();
    TestLlm stepXModel = TestLlm.create(createTextLlmResponse("StepX done."));
    LlmAgent stepX = LlmAgent.builder().name("stepX").model(stepXModel).build();
    SequentialAgent outer = SequentialAgent.builder().name("outer").subAgents(mid, stepX).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("outer")), outer);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The outer sequence takes the answer, so the step after mid runs.
    assertThat(lastText(second)).isEqualTo("StepX done.");
    assertThat(stepXModel.getRequests()).hasSize(1);
  }

  @Test
  public void taskRoot_worksInAScopeNamedByItsNodePath() {
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(createTextLlmResponse("Which one?")))
            .mode(AgentMode.TASK)
            .build();

    ImmutableList<Event> events = new WorkflowSession(planner).turn(userText("go"));

    assertThat(scopesOf(events, "planner")).containsExactly("planner@1");
  }

  @Test
  public void replyToATaskRoot_continuesTheTaskInItsScopeUntilItFinishes() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Which one?"), finishTask("f1", "done"));
    LlmAgent planner = LlmAgent.builder().name("planner").model(model).mode(AgentMode.TASK).build();
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(userText("go"));

    ImmutableList<Event> second = session.turn(userText("the second one"));

    assertThat(second.stream().flatMap(event -> event.output().stream()))
        .contains(ImmutableMap.of("result", "done"));
    Event reply =
        session.storedEvents().stream()
            .filter(event -> event.content().map(Content::text).orElse("").equals("the second one"))
            .collect(onlyElement());
    assertThat(reply.isolationScope()).hasValue("planner@1");
    assertThat(texts(model.getLastRequest().contents()))
        .containsExactly("go", "Which one?", "the second one")
        .inOrder();
  }

  @Test
  public void taskRootThatHandsOff_runsTheTargetOnce() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.TASK)
            .subAgents(LlmAgent.builder().name("helper").model(helperModel).build())
            .build();

    ImmutableList<Event> events = new WorkflowSession(planner).turn(go());

    assertThat(helperModel.getRequests()).hasSize(1);
    assertThat(
            events.stream()
                .filter(event -> event.author().equals("helper"))
                .map(Event::stringifyContent)
                .filter(text -> !text.isEmpty()))
        .containsExactly("Helper here.");
  }

  @Test
  public void followUpAfterATaskRootHandsOff_reachesTheTargetWithItsHistory() {
    TestLlm helperModel =
        TestLlm.create(createTextLlmResponse("Helper here."), createTextLlmResponse("Again."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.TASK)
            .subAgents(LlmAgent.builder().name("helper").model(helperModel).build())
            .build();
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(userText("first question"));

    session.advance(userText("second question"));

    assertThat(texts(helperModel.getLastRequest().contents()))
        .containsAtLeast("first question", "Helper here.", "second question")
        .inOrder();
  }

  @Test
  public void helperOfATaskRootThatAsks_answersTheReplyInTheTasksScope() {
    TestLlm helperModel =
        TestLlm.create(createTextLlmResponse("Which city?"), createTextLlmResponse("Got Paris."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.TASK)
            .subAgents(LlmAgent.builder().name("helper").model(helperModel).build())
            .build();
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(go());

    ImmutableList<Event> reply = session.turn(userText("Paris"));

    // The helper ran in the task's scope, so it takes the reply there and sees its own question.
    Event answer =
        reply.stream()
            .filter(event -> event.author().equals("helper") && !event.stringifyContent().isEmpty())
            .collect(onlyElement());
    assertThat(answer.stringifyContent()).isEqualTo("Got Paris.");
    assertThat(answer.isolationScope()).hasValue("planner@1");
    assertThat(helperModel.getLastRequest().contents())
        .containsAtLeast(
            Content.builder()
                .role("model")
                .parts(Part.builder().text("Which city?").build())
                .build(),
            userText("Paris"))
        .inOrder();
  }

  @Test
  public void taskRootWhoseTaskAsksTheUser_resumesThroughTheRootOnTheReply() {
    TestLlm workerModel =
        TestLlm.create(createTextLlmResponse("Which city?"), finishTask("f1", "Paris booked"));
    TestLlm rootModel =
        TestLlm.create(delegate("t1", "worker", "book a trip"), finishTask("f2", "trip done"));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(rootModel)
            .mode(AgentMode.TASK)
            .subAgents(task("worker", workerModel))
            .build();
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(userText("plan my trip"));

    ImmutableList<Event> reply = session.turn(userText("Paris"));

    assertThat(responseNames(reply)).contains("worker");
    assertThat(reply.stream().flatMap(event -> event.output().stream()))
        .contains(ImmutableMap.of("result", "trip done"));
    assertThat(rootModel.getRequests()).hasSize(2);
  }

  @Test
  public void resumableTaskRoot_replyByInvocationId_finishesTheTask() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Which city?"), finishTask("f1", "Paris"));
    LlmAgent planner = LlmAgent.builder().name("planner").model(model).mode(AgentMode.TASK).build();
    WorkflowSession session = new WorkflowSession(planner);
    String invocationId = session.turn(userText("plan my trip")).get(0).invocationId();

    ImmutableList<Event> reply = session.resume(invocationId, userText("Paris"));

    assertThat(model.getRequests()).hasSize(2);
    assertThat(model.getLastRequest().contents()).contains(userText("Paris"));
    assertThat(reply.stream().flatMap(event -> event.output().stream()))
        .contains(ImmutableMap.of("result", "Paris"));
  }

  @Test
  public void taskRootThatAsksAfterItsSubTaskFinished_resumesByInvocationId() {
    TestLlm rootModel =
        TestLlm.create(
            delegate("t1", "worker", "book"),
            createTextLlmResponse("Which city?"),
            finishTask("f2", "Paris"));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(rootModel)
            .mode(AgentMode.TASK)
            .subAgents(task("worker", TestLlm.create(finishTask("f1", "booked"))))
            .build();
    WorkflowSession session = new WorkflowSession(planner);
    String invocationId = session.turn(userText("plan my trip")).get(0).invocationId();

    ImmutableList<Event> reply = session.resume(invocationId, userText("Paris"));

    // The worker's finish_task ended the worker's task, not the root's.
    assertThat(rootModel.getRequests()).hasSize(3);
    assertThat(rootModel.getLastRequest().contents()).contains(userText("Paris"));
    assertThat(reply.stream().flatMap(event -> event.output().stream()))
        .contains(ImmutableMap.of("result", "Paris"));
  }

  @Test
  public void answerToATaskRootsCallAfterARetriedError_continuesTheTask() {
    AtomicInteger calls = new AtomicInteger();
    TestLlm model =
        new TestLlm(() ->
            switch (calls.getAndIncrement()) {
              case 0 -> Flowable.error(new IllegalStateException("model down"));
              case 1 ->
                  Flowable.just(
                      createFunctionCallLlmResponse(
                          "call-1", "pendingTool", ImmutableMap.of("message", "approve?")));
              default -> Flowable.just(finishTask("f1", "Approved."));
            });
    LlmAgent planner =
        new LlmAgent(
            LlmAgent.builder()
                .name("planner")
                .model(model)
                .mode(AgentMode.TASK)
                .tools(pendingFunctionTool())) {
          @Override
          public NodeConfig config() {
            return NodeConfig.builder()
                .retryConfig(
                    RetryConfig.builder().maxAttempts(2).initialDelay(Duration.ZERO).build())
                .build();
          }
        };
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(userText("plan my trip"));

    ImmutableList<Event> second =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The retry went on after the error, so the error did not end the task.
    assertThat(model.getRequests()).hasSize(3);
    assertThat(second.stream().flatMap(event -> event.output().stream()))
        .contains(ImmutableMap.of("result", "Approved."));
  }

  @Test
  public void resumingATaskRootThatFailed_doesNotRunItAgain() {
    TestLlm model = new TestLlm(() -> Flowable.error(new IllegalStateException("model down")));
    LlmAgent planner = LlmAgent.builder().name("planner").model(model).mode(AgentMode.TASK).build();
    WorkflowSession session = new WorkflowSession(planner);
    String invocationId =
        session.failingTurn(userText("plan my trip")).events().get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    assertThat(resumed).isEmpty();
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void resumingAFinishedTaskRoot_doesNotRunItAgain() {
    TestLlm model = TestLlm.create(finishTask("f1", "done"));
    LlmAgent planner = LlmAgent.builder().name("planner").model(model).mode(AgentMode.TASK).build();
    WorkflowSession session = new WorkflowSession(planner);
    String invocationId = session.turn(userText("plan my trip")).get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    assertThat(resumed).isEmpty();
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void replyToATaskRootByInvocationIdAfterAnotherInvocation_finishesTheTask() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which city?"),
            createTextLlmResponse("Still need a city."),
            finishTask("f1", "Paris"));
    LlmAgent planner = LlmAgent.builder().name("planner").model(model).mode(AgentMode.TASK).build();
    WorkflowSession session = new WorkflowSession(planner);
    String invocationId = session.turn(userText("plan my trip")).get(0).invocationId();
    session.advance(userText("hmm"));

    ImmutableList<Event> reply = session.resume(invocationId, userText("Paris"));

    assertThat(model.getLastRequest().contents()).contains(userText("Paris"));
    assertThat(reply.stream().flatMap(event -> event.output().stream()))
        .contains(ImmutableMap.of("result", "Paris"));
  }

  @Test
  public void replyToATaskRoot_keepsTheReplyAsTheUserContent() {
    List<String> seen = new ArrayList<>();
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(createTextLlmResponse("Which city?"), finishTask("f1", "Paris")))
            .mode(AgentMode.TASK)
            .beforeAgentCallback(callbackContext -> {
              callbackContext.userContent().map(Content::text).ifPresent(seen::add);
              return Maybe.empty();
            })
            .build();
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(userText("plan my trip"));

    session.advance(userText("Paris"));

    assertThat(seen).containsExactly("plan my trip", "Paris").inOrder();
  }

  @Test
  public void handoffBackToATaskRoot_keepsTheTasksScope() {
    TestLlm rootModel =
        TestLlm.create(
            transferTo("helper"), createTextLlmResponse("Back."), finishTask("f1", "ok"));
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create(createTextLlmResponse("Helper here."), transferTo("planner")))
            .build();
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(rootModel)
            .mode(AgentMode.TASK)
            .subAgents(helper)
            .build();
    WorkflowSession session = new WorkflowSession(planner);
    session.advance(userText("go"));

    ImmutableList<Event> second = session.turn(userText("take me back"));

    assertThat(scopesOf(second, "planner")).containsExactly("planner@1");
  }

  @Test
  public void replyToATaskUnderAChatSubAgent_resumesThroughItsDelegator() {
    TestLlm workerModel =
        TestLlm.create(createTextLlmResponse("Which one?"), finishTask("f1", "done"));
    TestLlm subModel =
        TestLlm.create(delegate("t1", "worker", "do it"), createTextLlmResponse("Sub done."));
    LlmAgent chatSub =
        LlmAgent.builder()
            .name("chat_sub")
            .model(subModel)
            .subAgents(task("worker", workerModel))
            .build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("chat_sub")), chatSub);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second = session.turn(userText("the second one"));

    assertThat(responseNames(second)).contains("worker");
    assertThat(lastText(second)).isEqualTo("Sub done.");
  }

  @Test
  public void answerToATaskAgentsLongRunningCall_resumesThroughItsDelegator() {
    assertAnswerToATaskResumesThroughItsDelegator(/* resumable= */ true);
  }

  @Test
  public void answerToATaskAgentsLongRunningCallWithoutResumability_resumesThroughItsDelegator() {
    assertAnswerToATaskResumesThroughItsDelegator(/* resumable= */ false);
  }

  private static void assertAnswerToATaskResumesThroughItsDelegator(boolean resumable) {
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    finishTask("f1", "approved")))
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .build();
    LlmAgent coordinator =
        chatRoot(
            TestLlm.create(
                delegate("t1", "worker", "get approval"), createTextLlmResponse("All done.")),
            worker);
    WorkflowSession session = new WorkflowSession(coordinator, resumable);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(responseNames(second)).contains("worker");
    assertThat(lastText(second)).isEqualTo("All done.");
  }

  @Test
  public void replyToATaskInsideAnotherTask_resumesThroughTheOutermostDelegator() {
    TestLlm innerModel =
        TestLlm.create(createTextLlmResponse("Which one?"), finishTask("f2", "inner done"));
    TestLlm outerModel =
        TestLlm.create(delegate("t2", "inner", "ask"), finishTask("f1", "outer done"));
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer")
            .model(outerModel)
            .mode(AgentMode.TASK)
            .subAgents(task("inner", innerModel))
            .build();
    LlmAgent coordinator =
        chatRoot(
            TestLlm.create(delegate("t1", "outer", "do it"), createTextLlmResponse("All done.")),
            outer);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second = session.turn(userText("the second one"));

    assertThat(responseNames(second)).containsAtLeast("inner", "outer");
    assertThat(lastText(second)).isEqualTo("All done.");
  }

  @Test
  @SuppressWarnings("deprecation") // The plain-text continuation shim is deprecated by design.
  public void answerToAStepOfASequenceWithLegacyResumability_runsTheRestOfTheSequence() {
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Worker done.")))
            .tools(pendingFunctionTool())
            .build();
    TestLlm afterModel = TestLlm.create(createTextLlmResponse("After done."));
    LlmAgent after = LlmAgent.builder().name("after").model(afterModel).build();
    SequentialAgent pipeline =
        SequentialAgent.builder().name("pipeline").subAgents(worker, after).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("pipeline")), pipeline);
    WorkflowSession session =
        new WorkflowSession(
            coordinator,
            ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build());
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // A legacy sequence stopped at the call, so it takes the answer and runs its later steps.
    assertThat(lastText(second)).isEqualTo("After done.");
    assertThat(afterModel.getRequests()).hasSize(1);
  }

  @Test
  public void answerToATaskAgentUnderASequence_resumesTheTaskInTheSequence() {
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Worker done.")))
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .build();
    SequentialAgent pipeline = SequentialAgent.builder().name("pipeline").subAgents(worker).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("pipeline")), pipeline);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The sequence takes the answer, as in Python, and runs the task again inside it.
    assertThat(lastText(second)).isEqualTo("Worker done.");
  }

  @Test
  public void answerToATaskAgentAfterAStepWithoutResumability_runsTheSequenceAgain() {
    TestLlm preModel =
        TestLlm.create(createTextLlmResponse("Pre done."), createTextLlmResponse("Pre again."));
    LlmAgent pre = LlmAgent.builder().name("pre").model(preModel).build();
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Worker done.")))
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .build();
    SequentialAgent pipeline =
        SequentialAgent.builder().name("pipeline").subAgents(pre, worker).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("pipeline")), pipeline);
    WorkflowSession session = new WorkflowSession(coordinator, /* resumable= */ false);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The task's delegator, the sequence, takes the answer, as in Python, so its steps run again.
    assertThat(lastText(second)).isEqualTo("Worker done.");
    assertThat(preModel.getRequests()).hasSize(2);
  }

  @Test
  public void answerToATaskAgentUnderAParallelAgent_resumesTheTaskInIt() {
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Worker done.")))
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .build();
    ParallelAgent fanout = ParallelAgent.builder().name("fanout").subAgents(worker).build();
    TestLlm coordinatorModel = TestLlm.create(transferTo("fanout"));
    LlmAgent coordinator = chatRoot(coordinatorModel, fanout);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The parallel agent takes the answer, as in Python, and runs the task again inside it.
    assertThat(lastText(second)).isEqualTo("Worker done.");
    assertThat(second.stream().map(Event::author)).contains("fanout");
    assertThat(coordinatorModel.getRequests()).hasSize(1);
  }

  @Test
  public void answerToASingleTurnAgentsLongRunningCall_resumesThroughItsDelegator() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Helper approved.")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    TestLlm coordinatorModel =
        TestLlm.create(
            createFunctionCallLlmResponse("h1", "helper", ImmutableMap.of("request", "approve")),
            createTextLlmResponse("All done."));
    LlmAgent coordinator = chatRoot(coordinatorModel, helper);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> second =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(responseNames(second)).contains("helper");
    assertThat(lastText(second)).isEqualTo("All done.");
  }

  @Test
  public void plainReplyAfterATaskAgentUnderASequence_goesToTheRootInANewInvocation() {
    LlmAgent worker = task("worker", TestLlm.create(createTextLlmResponse("Which one?")));
    LlmAgent after =
        LlmAgent.builder()
            .name("after")
            .model(TestLlm.create(createTextLlmResponse("After done.")))
            .build();
    SequentialAgent pipeline =
        SequentialAgent.builder().name("pipeline").subAgents(worker, after).build();
    TestLlm coordinatorModel =
        TestLlm.create(transferTo("pipeline"), createTextLlmResponse("Coordinator again."));
    LlmAgent coordinator = chatRoot(coordinatorModel, pipeline);
    WorkflowSession session = new WorkflowSession(coordinator);
    ImmutableList<Event> first = session.turn(go());

    ImmutableList<Event> second = session.turn(userText("the second one"));

    // In a sequence the task agent runs as an agent without a mode, so it leaves no task waiting.
    assertThat(scopesOf(first, "worker")).containsExactly("");
    assertThat(lastText(first)).isEqualTo("After done.");
    assertThat(lastText(second)).isEqualTo("Coordinator again.");
    assertThat(second.get(0).invocationId()).isNotEqualTo(first.get(0).invocationId());
  }

  @Test
  public void plainReplyToATaskThatAsked_joinsTheTasksInvocation() {
    assertPlainReplyJoinsTheTasksInvocation(/* resumable= */ true);
  }

  @Test
  public void plainReplyToATaskThatAskedWithoutResumability_joinsTheTasksInvocation() {
    assertPlainReplyJoinsTheTasksInvocation(/* resumable= */ false);
  }

  private static void assertPlainReplyJoinsTheTasksInvocation(boolean resumable) {
    LlmAgent coordinator =
        chatRoot(
            TestLlm.create(delegate("t1", "worker", "do it"), createTextLlmResponse("All done.")),
            task(
                "worker",
                TestLlm.create(createTextLlmResponse("Which one?"), finishTask("f1", "done"))));
    WorkflowSession session = new WorkflowSession(coordinator, resumable);
    String pausedInvocation = session.turn(go()).get(0).invocationId();

    ImmutableList<Event> second = session.turn(userText("the second one"));

    assertThat(lastText(second)).isEqualTo("All done.");
    assertThat(second.stream().map(Event::invocationId).distinct())
        .containsExactly(pausedInvocation);
  }

  @Test
  public void answerToAnotherCallWhileATaskWaits_doesNotJoinTheTasksInvocation() {
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "approve-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    delegate("t1", "worker", "do it"),
                    createTextLlmResponse("Approved.")))
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            // The answer resumes the coordinator, which leaves the task waiting on the user.
            .subAgents(task("worker", TestLlm.create(createTextLlmResponse("Which one?"))))
            .build();
    WorkflowSession session = new WorkflowSession(coordinator);
    String firstInvocation = session.turn(userText("approve it")).get(0).invocationId();
    String pausedInvocation = session.turn(userText("now the task")).get(0).invocationId();

    ImmutableList<Event> unused =
        session.turn(answer("approve-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The answer goes to its own call's invocation, not to the waiting task's.
    assertThat(firstInvocation).isNotEqualTo(pausedInvocation);
    assertThat(
            session.storedEvents().stream()
                .filter(event ->
                    event.functionResponses().stream()
                        .anyMatch(r -> r.id().filter("approve-1"::equals).isPresent()))
                .map(Event::invocationId))
        .containsExactly(firstInvocation);
  }

  @Test
  public void rootAgentAppWithATaskRoot_staysOnTheClassicFlow() {
    LlmAgent root =
        LlmAgent.builder().name("root").model(TestLlm.create("ok")).mode(AgentMode.TASK).build();

    ImmutableList<Event> events = runAsRootAgent(root, go());

    assertThat(lastText(events)).isEqualTo("ok");
    assertThat(ImmutableSet.copyOf(pathsOf(events))).containsExactly("");
  }

  @Test
  public void modeBelowARootThatIsNotAnLlmAgent_staysOnTheClassicFlow() {
    LlmAgent chat =
        LlmAgent.builder().name("a").model(TestLlm.create("ok")).mode(AgentMode.CHAT).build();
    SequentialAgent pipeline = SequentialAgent.builder().name("pipeline").subAgents(chat).build();

    ImmutableList<Event> events = new WorkflowSession(pipeline).turn(go());

    assertThat(lastText(events)).isEqualTo("ok");
    assertThat(ImmutableSet.copyOf(pathsOf(events))).containsExactly("");
  }

  @Test
  public void answerToASubAgentOnAParallelBranch_keepsItsBranch() {
    LlmAgent first =
        LlmAgent.builder()
            .name("first")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Approved.")))
            .tools(pendingFunctionTool())
            .build();
    LlmAgent second =
        LlmAgent.builder()
            .name("second")
            .model(TestLlm.create(createTextLlmResponse("Second here.")))
            .build();
    ParallelAgent fanout = ParallelAgent.builder().name("fanout").subAgents(first, second).build();
    LlmAgent coordinator = chatRoot(TestLlm.create(transferTo("fanout")), fanout);
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> answered =
        session.turn(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The answer reaches the first branch, so its call and response stay in its prompt.
    assertThat(lastText(answered)).isEqualTo("Approved.");
    assertThat(
            answered.stream()
                .filter(event -> event.author().equals("first"))
                .map(event -> event.branch().orElse(""))
                .distinct())
        .containsExactly("fanout.first");
  }

  @Test
  public void answerToOneOfTwoCallsOfAParallelNodeWithAModeBelowIt_waitsForTheOther() {
    LlmAgent first =
        LlmAgent.builder()
            .name("first")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-1", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("First done.")))
            .tools(pendingFunctionTool())
            .mode(AgentMode.CHAT)
            .build();
    LlmAgent second =
        LlmAgent.builder()
            .name("second")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "call-2", "pendingTool", ImmutableMap.of("message", "approve?")),
                    createTextLlmResponse("Second done.")))
            .tools(pendingFunctionTool())
            .build();
    ParallelAgent fanout = ParallelAgent.builder().name("fanout").subAgents(first, second).build();
    AtomicInteger downstreamRuns = new AtomicInteger();
    Node downstream =
        new TestNode(
            "downstream",
            (ctx, input) -> {
              downstreamRuns.incrementAndGet();
              return Flowable.just("down");
            });
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder()
                .name("wf")
                .edges(new Edge(Start.INSTANCE, fanout), new Edge(fanout, downstream))
                .build());
    session.advance(go());

    session.advance(answer("call-1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The parallel node still waits on the second call, so the node after it does not run yet.
    assertThat(downstreamRuns.get()).isEqualTo(0);
    session.advance(answer("call-2", ImmutableMap.of("status", "approved"), "pendingTool"));
    assertThat(downstreamRuns.get()).isEqualTo(1);
  }

  @Test
  public void dottedNameInARootTreeWithModes_isRejectedWhenTheRunnerIsBuilt() {
    LlmAgent desk = LlmAgent.builder().name("billing.desk").model(TestLlm.create("ok")).build();
    LlmAgent root =
        LlmAgent.builder()
            .name("root")
            .model(TestLlm.create("ok"))
            .mode(AgentMode.CHAT)
            .subAgents(desk)
            .build();

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new WorkflowSession(root));

    assertThat(error).hasMessageThat().contains("Agents [billing.desk] run as workflow nodes");
  }

  private static LlmAgent chatRoot(TestLlm model, BaseAgent... subAgents) {
    return LlmAgent.builder()
        .name("coordinator")
        .model(model)
        .mode(AgentMode.CHAT)
        .subAgents(subAgents)
        .build();
  }

  /** Runs one turn of an app built with {@code rootAgent(agent)}, which keeps the classic flow. */
  private static ImmutableList<Event> runAsRootAgent(BaseAgent agent, Content message) {
    Runner runner =
        Runner.builder().app(App.builder().name("app").rootAgent(agent).build()).build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();
    return ImmutableList.copyOf(
        runner.runAsync("user", session.id(), message).toList().blockingGet());
  }

  private static LlmAgent task(String name, TestLlm model) {
    return LlmAgent.builder().name(name).model(model).mode(AgentMode.TASK).build();
  }

  private static LlmResponse delegate(String id, String agent, String request) {
    return createFunctionCallLlmResponse(id, agent, ImmutableMap.of("request", request));
  }

  private static LlmResponse finishTask(String id, String result) {
    return createFunctionCallLlmResponse(id, "finish_task", ImmutableMap.of("result", result));
  }

  private static ImmutableSet<String> scopesOf(List<Event> events, String author) {
    return events.stream()
        .filter(event -> event.author().equals(author))
        .map(event -> event.isolationScope().orElse(""))
        .collect(toImmutableSet());
  }

  private static ImmutableSet<String> responseNames(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionResponses().stream())
        .flatMap(response -> response.name().stream())
        .collect(toImmutableSet());
  }

  private static LlmResponse transferTo(String agentName) {
    return createFunctionCallLlmResponse(
        "transfer-" + agentName, "transfer_to_agent", ImmutableMap.of("agent_name", agentName));
  }

  private static String lastText(List<Event> events) {
    return events.stream()
        .map(Event::stringifyContent)
        .filter(text -> !text.isEmpty())
        .reduce((first, second) -> second)
        .orElse("");
  }

  private static ImmutableList<String> texts(List<Content> contents) {
    return contents.stream().map(Content::text).collect(toImmutableList());
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }
}
