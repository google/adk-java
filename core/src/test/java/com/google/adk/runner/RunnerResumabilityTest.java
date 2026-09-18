/*
 * Copyright 2025 Google LLC
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

package com.google.adk.runner;

import static com.google.adk.testing.ResumabilityTestUtils.answerCall;
import static com.google.adk.testing.ResumabilityTestUtils.assertEndOfAgent;
import static com.google.adk.testing.ResumabilityTestUtils.assertNoEndOfAgent;
import static com.google.adk.testing.ResumabilityTestUtils.confirmingEchoFunctionTool;
import static com.google.adk.testing.ResumabilityTestUtils.longRunningEchoFunctionTool;
import static com.google.adk.testing.ResumabilityTestUtils.newSession;
import static com.google.adk.testing.ResumabilityTestUtils.pauseThenSay;
import static com.google.adk.testing.ResumabilityTestUtils.pausingAgent;
import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.ResumabilityTestUtils.resumableRunner;
import static com.google.adk.testing.ResumabilityTestUtils.resume;
import static com.google.adk.testing.ResumabilityTestUtils.runTurn;
import static com.google.adk.testing.ResumabilityTestUtils.textAgent;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.adk.testing.TestUtils.simplifyResumableEvents;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.Session;
import com.google.adk.telemetry.Tracing;
import com.google.adk.testing.ResumabilityTestUtils.Tools;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Runner tests for durable resumability: {@code ResumabilityConfig.resumable(true)}. */
@RunWith(JUnit4.class)
@SuppressWarnings("deprecation") // The class exists to exercise the deprecated resumability flags.
public final class RunnerResumabilityTest {
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private Tracer originalTracer;

  private BasePlugin mockPlugin(String name) {
    // Need CALLS_REAL_METHODS to avoid NPE. The default implementation is only returning
    // Maybe.empty()
    BasePlugin plugin = mock(BasePlugin.class, CALLS_REAL_METHODS);
    when(plugin.getName()).thenReturn(name);
    return plugin;
  }

  @Before
  public void setUp() {
    this.originalTracer = Tracing.getTracer();
    Tracing.setTracerForTesting(
        openTelemetryRule.getOpenTelemetry().getTracer("RunnerResumabilityTest"));
  }

  @After
  public void tearDown() {
    Tracing.setTracerForTesting(originalTracer);
  }

  // OSS HITL: after an adk_request_confirmation resumes sub-agent B in a SequentialAgent(A, B, C),
  // the workflow must advance to C without re-running the already completed A.
  @Test
  public void runAsync_withToolConfirmation_inSequentialAgent_runsLaterSubAgentsAfterResume() {
    LlmAgent agentA =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("agent A done")))
            .name("a_agent")
            .build();
    // With resumability on, B pauses right after requesting confirmation (no extra model call), so
    // a
    // single follow-up response covers the resume.
    TestLlm bTestLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "tool_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("Response after user confirmed."));
    LlmAgent agentB =
        createTestAgentBuilder(bTestLlm)
            .name("b_agent")
            .tools(confirmingEchoFunctionTool())
            .build();
    LlmAgent agentC =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("agent C done")))
            .name("c_agent")
            .build();
    SequentialAgent workflowAgent =
        SequentialAgent.builder()
            .name("workflow_agent")
            .subAgents(ImmutableList.of(agentA, agentB, agentC))
            .build();
    Runner runner = resumableRunner(workflowAgent);
    Session session = newSession(runner);

    ImmutableList<Event> eventsBeforeConfirmation = runTurn(runner, session, "from user");

    // Turn 1: A runs, B pauses for confirmation, and C must not run yet.
    assertThat(simplifyEvents(eventsBeforeConfirmation)).contains("a_agent: agent A done");
    assertThat(simplifyEvents(eventsBeforeConfirmation)).doesNotContain("c_agent: agent C done");

    FunctionCall askUserConfirmationFunctionCall =
        Iterables.getOnlyElement(
            eventsBeforeConfirmation.stream()
                .map(Functions::getAskUserConfirmationFunctionCalls)
                .filter(functionCalls -> !functionCalls.isEmpty())
                .findFirst()
                .get());
    ImmutableList<Event> eventsAfterConfirmation =
        ImmutableList.copyOf(
            runner
                .runAsync(
                    "user",
                    session.id(),
                    Content.fromParts(
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id(askUserConfirmationFunctionCall.id().get())
                                    .name(askUserConfirmationFunctionCall.name().get())
                                    .response(ImmutableMap.of("confirmed", true)))
                            .build()))
                .toList()
                .blockingGet());

    // Turn 2: B resumes and executes the tool, then C runs (A is not re-run), with per-agent and
    // workflow checkpoints.
    assertThat(simplifyResumableEvents(eventsAfterConfirmation))
        .containsExactly(
            "b_agent: FunctionResponse(name=echoTool, response={message=hello})",
            "b_agent: Response after user confirmed.",
            "b_agent: end_of_agent",
            "workflow_agent: agent_state={current_sub_agent=c_agent}",
            "c_agent: agent C done",
            "c_agent: end_of_agent",
            "workflow_agent: end_of_agent")
        .inOrder();
  }

  // Long-running-call HITL: a pending long-running function call (not the confirmation flow) pauses
  // SequentialAgent(A, B, C) after B; on resume B continues and C runs, without re-running A.
  @Test
  public void runAsync_withLongRunningCall_inSequentialAgent_runsLaterSubAgentsAfterResume() {
    LlmAgent agentA =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("agent A done")))
            .name("a_agent")
            .build();
    // With resumability on, B pauses right after the no-result long-running call (no extra model
    // call), so a single follow-up response covers the resume.
    TestLlm bTestLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("agent B resumed"));
    LlmAgent agentB =
        createTestAgentBuilder(bTestLlm).name("b_agent").tools(pendingFunctionTool()).build();
    LlmAgent agentC =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("agent C done")))
            .name("c_agent")
            .build();
    SequentialAgent workflowAgent =
        SequentialAgent.builder()
            .name("workflow_agent")
            .subAgents(ImmutableList.of(agentA, agentB, agentC))
            .build();
    Runner runner = resumableRunner(workflowAgent);
    Session session = newSession(runner);

    ImmutableList<Event> eventsBeforeResume = runTurn(runner, session, "from user");

    // Turn 1: A runs, B issues the long-running call and pauses; C must not run yet. B must not
    // make
    // a further model call after the pending call.
    assertThat(simplifyEvents(eventsBeforeResume)).contains("a_agent: agent A done");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("b_agent: agent B resumed");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("c_agent: agent C done");

    ImmutableList<Event> eventsAfterResume =
        answerCall(
            runner, session, "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello"));

    // Turn 2: B resumes from the long-running response and C runs (A is not re-run), with per-agent
    // and workflow checkpoints.
    assertThat(simplifyResumableEvents(eventsAfterResume))
        .containsExactly(
            "b_agent: agent B resumed",
            "b_agent: end_of_agent",
            "workflow_agent: agent_state={current_sub_agent=c_agent}",
            "c_agent: agent C done",
            "c_agent: end_of_agent",
            "workflow_agent: end_of_agent")
        .inOrder();
  }

  // A resumable LoopAgent(w1, w2) paused on w1's long-running call resumes w1 and then advances the
  // loop to w2 and closes it, rather than resuming only the paused sub-agent -- the loop advances
  // like a SequentialAgent.
  @Test
  public void runAsync_withLongRunningCall_inLoopAgent_runsRemainingSubAgentsAfterResume() {
    TestLlm w1TestLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("w1 resumed"));
    LlmAgent w1 =
        createTestAgentBuilder(w1TestLlm).name("w1_agent").tools(pendingFunctionTool()).build();
    LlmAgent w2 =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("w2 done")))
            .name("w2_agent")
            .build();
    LoopAgent workflowAgent =
        LoopAgent.builder()
            .name("loop_agent")
            .subAgents(ImmutableList.of(w1, w2))
            .maxIterations(1)
            .build();
    Runner runner = resumableRunner(workflowAgent);
    Session session = newSession(runner);

    ImmutableList<Event> eventsBeforeResume = runTurn(runner, session, "from user");

    // Turn 1: w1 issues the long-running call and pauses; w2 must not run yet.
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("w1_agent: w1 resumed");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("w2_agent: w2 done");

    ImmutableList<Event> eventsAfterResume =
        answerCall(
            runner, session, "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello"));

    // Turn 2: w1 resumes and the loop advances to w2 and closes, with per-agent and loop
    // checkpoints (w1 is not re-run from the start of the iteration).
    assertThat(simplifyResumableEvents(eventsAfterResume))
        .containsExactly(
            "w1_agent: w1 resumed",
            "w1_agent: end_of_agent",
            "loop_agent: agent_state={current_sub_agent=w2_agent, times_looped=0}",
            "w2_agent: w2 done",
            "w2_agent: end_of_agent",
            "loop_agent: end_of_agent")
        .inOrder();
  }

  // A resumable plain-agent transfer closes the root (end_of_agent) the moment it transfers, before
  // the transferred sub-agent runs, and a later turn resumes at the sub-agent, not the finished
  // root.
  @Test
  public void runAsync_resumable_transferToSubAgent_closesRootThenResumesSubAgent() {
    Content transferCall =
        Content.fromParts(
            Part.fromFunctionCall(
                "transfer_to_agent", ImmutableMap.of("agent_name", "sub_agent_1")));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(transferCall),
            createTextLlmResponse("response1"),
            createTextLlmResponse("response2"));
    LlmAgent subAgent1 = createTestAgentBuilder(testLlm).name("sub_agent_1").build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    Runner runner = resumableRunner(rootAgent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "hi");

    // The root closes right after the transfer, before the sub-agent runs.
    assertThat(simplifyResumableEvents(turn1))
        .containsExactly(
            "root_agent: FunctionCall(name=transfer_to_agent, args={agent_name=sub_agent_1})",
            "root_agent: FunctionResponse(name=transfer_to_agent, response={})",
            "root_agent: end_of_agent",
            "sub_agent_1: response1",
            "sub_agent_1: end_of_agent")
        .inOrder();

    ImmutableList<Event> turn2 = runTurn(runner, session, "again");

    // A new turn resumes at the transferred sub-agent, not the finished root.
    assertThat(simplifyEvents(turn2)).contains("sub_agent_1: response2");
  }

  // A sub-agent a transfer routed to can itself pause on a long-running call and be resumed: the
  // root closes on transfer, the sub-agent pauses on its long-running call, and a resume carrying
  // the matching function response continues that same sub-agent invocation to completion.
  @Test
  public void runAsync_resumable_transferredSubAgentPausesOnLongRunningCall_resumesSubAgent() {
    Content transferCall =
        Content.fromParts(
            Part.fromFunctionCall(
                "transfer_to_agent", ImmutableMap.of("agent_name", "sub_agent_1")));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(transferCall),
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("resumed answer"));
    LlmAgent subAgent1 =
        createTestAgentBuilder(testLlm).name("sub_agent_1").tools(pendingFunctionTool()).build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    Runner runner = resumableRunner(rootAgent);
    Session session = newSession(runner);

    ImmutableList<Event> pausedTurn = runTurn(runner, session, "hi");
    String pausedInvocationId = pausedTurn.get(0).invocationId();

    // Root closes on transfer, then the sub-agent runs and pauses on its long-running call.
    ImmutableList<String> pausedEvents = simplifyResumableEvents(pausedTurn);
    assertThat(pausedEvents)
        .containsAtLeast(
            "root_agent: end_of_agent",
            "sub_agent_1: FunctionCall(name=pendingTool, args={message=hello})")
        .inOrder();
    // The sub-agent neither finished nor produced its answer while paused.
    assertThat(pausedEvents).doesNotContain("sub_agent_1: end_of_agent");
    assertThat(simplifyEvents(pausedTurn)).doesNotContain("sub_agent_1: resumed answer");

    ImmutableList<Event> resumed =
        ImmutableList.copyOf(
            runner
                .runAsync(
                    "user",
                    session.id(),
                    /* invocationId= */ null,
                    Content.fromParts(
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id("lro_call_id")
                                    .name("pendingTool")
                                    .response(ImmutableMap.of("message", "hello")))
                            .build()),
                    RunConfig.builder().build(),
                    /* stateDelta= */ null)
                .toList()
                .blockingGet());

    // The resume continues the transferred sub-agent (same invocation) to completion.
    assertThat(resumed).isNotEmpty();
    assertThat(resumed.stream().allMatch(event -> event.invocationId().equals(pausedInvocationId)))
        .isTrue();
    assertThat(simplifyEvents(resumed)).contains("sub_agent_1: resumed answer");
    assertThat(resumed.stream().anyMatch(event -> event.actions().endOfAgent())).isTrue();
  }

  // A resumable invocation paused on two long-running calls stays paused until both are answered:
  // answering one resumes without re-invoking the model, and answering the second lets the model
  // summarize.
  @Test
  public void runAsync_withTwoLongRunningCalls_pausesUntilBothAnswered() {
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(
                Content.builder()
                    .role("model")
                    .parts(
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder()
                                    .id("call_a")
                                    .name("pendingTool")
                                    .args(ImmutableMap.of("message", "a")))
                            .build(),
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder()
                                    .id("call_b")
                                    .name("pendingTool")
                                    .args(ImmutableMap.of("message", "b")))
                            .build())
                    .build()),
            createTextLlmResponse("both approved"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("root_agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    runTurn(runner, session, "start");
    // Turn 1: both long-running calls are issued and the invocation pauses; the model is called
    // once.
    assertThat(testLlm.getRequests()).hasSize(1);

    ImmutableList<Event> afterFirstAnswer =
        answerCall(runner, session, "call_a", "pendingTool", ImmutableMap.of("message", "a"));
    // One call answered is not enough: nothing runs and the model is not re-invoked.
    assertThat(afterFirstAnswer).isEmpty();
    assertThat(testLlm.getRequests()).hasSize(1);

    ImmutableList<Event> afterSecondAnswer =
        answerCall(runner, session, "call_b", "pendingTool", ImmutableMap.of("message", "b"));
    // Both answered: the model is re-invoked and summarizes.
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(simplifyEvents(afterSecondAnswer)).contains("root_agent: both approved");
  }

  // A value-returning long-running tool is not a pending request: it resolves the call in the same
  // turn, so even with resumability on the flow continues and the model summarizes the result (two
  // model calls) rather than pausing. Only a no-result long-running tool pauses.
  @Test
  public void runAsync_withLongRunningCall_resumable_valueReturn_continuesAndSummarizes() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("summarized echo"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(longRunningEchoFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> events = runTurn(runner, session, "from user");

    // The value-returning call was summarized in the same turn: the model was re-invoked (two
    // calls) and the summary surfaced, with no pause. No end-of-agent checkpoint is emitted, so
    // the invocation stays resumable.
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(simplifyEvents(events)).contains("agent: summarized echo");
    assertThat(events.stream().anyMatch(event -> event.actions().endOfAgent())).isFalse();
  }

  // On resume the runner runs the same plugin bracket as the new-invocation path (on-user-message,
  // before-run, after-run, on-event), not only on-event: each fires once on the initial turn and
  // once more on the resume turn.
  @Test
  public void runAsync_resume_runsFullPluginBracket() {
    BasePlugin resumePlugin = mockPlugin("resume");
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("resumed and summarized"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent, resumePlugin);
    Session session = newSession(runner);

    runTurn(runner, session, "start");

    ImmutableList<Event> resumed =
        answerCall(
            runner, session, "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello"));

    assertThat(simplifyEvents(resumed)).contains("agent: resumed and summarized");
    // The full bracket ran on both the initial turn and the resume turn (before the fix, the resume
    // turn ran only onEventCallback, so these would each be invoked once).
    verify(resumePlugin, times(2)).onUserMessageCallback(any(), any());
    verify(resumePlugin, times(2)).beforeRunCallback(any());
    verify(resumePlugin, times(2)).afterRunCallback(any());
    verify(resumePlugin, atLeastOnce()).onEventCallback(any(), any());
  }

  // An unanswered long-running call keeps the invocation paused however it is resumed. A plain-text
  // message continues the agent instead of throwing "No matching function call", and a resume with
  // no message at all is equally inert; in neither case is the model re-invoked.
  @Test
  public void resume_pausedCallUnanswered_staysPausedForPlainTextAndNoMessage() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("should not be reached"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> firstTurn = runTurn(runner, session, "start");
    String invocationId = firstTurn.get(0).invocationId();

    // A plain-text message with an explicit invocation id: before the fix this threw
    // IllegalArgumentException; now it returns without throwing.
    ImmutableList<Event> resumedWithText =
        resume(
            runner,
            session,
            invocationId,
            Content.fromParts(Part.fromText("please continue")),
            /* stateDelta= */ null);
    assertThat(resumedWithText).isEmpty();

    ImmutableList<Event> resumedWithNoMessage =
        resume(runner, session, invocationId, /* newMessage= */ null, /* stateDelta= */ null);
    assertThat(resumedWithNoMessage).isEmpty();
    // The call is still unanswered, so the model was never re-invoked past the pausing turn.
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  // A nested Sequential whose inner sub-agent pauses silently must leave the OUTER checkpoint on
  // the unfinished inner workflow, not advance it -- otherwise the resume re-runs completed work.
  @Test
  public void runAsync_resume_nestedSequentialSilentPause_checkpointStaysOnUnfinishedInner() {
    LlmAgent a1 = pausingAgent("a1_agent", pauseThenSay("lro_call_id", "a1 resumed"));
    LlmAgent a2 = textAgent("a2_agent", "a2 done");
    SequentialAgent inner =
        SequentialAgent.builder().name("inner_agent").subAgents(ImmutableList.of(a1, a2)).build();
    LlmAgent b = textAgent("b_agent", "b done");
    SequentialAgent outer =
        SequentialAgent.builder().name("outer_agent").subAgents(ImmutableList.of(inner, b)).build();
    Runner runner = resumableRunner(outer);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "start");
    // a1 pauses, so neither a2 nor b runs, and no workflow closes.
    assertThat(simplifyEvents(turn1)).doesNotContain("a2_agent: a2 done");
    assertThat(simplifyEvents(turn1)).doesNotContain("b_agent: b done");
    assertNoEndOfAgent(turn1, "outer_agent");
    // The outer checkpoint still names the unfinished inner workflow.
    assertThat(simplifyResumableEvents(turn1))
        .contains("outer_agent: agent_state={current_sub_agent=inner_agent}");

    ImmutableList<Event> resumed =
        answerCall(runner, session, "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi"));

    // The resume finishes the inner workflow and only then advances the outer one; nothing that
    // already completed is re-run.
    assertThat(simplifyEvents(resumed))
        .containsAtLeast("a1_agent: a1 resumed", "a2_agent: a2 done", "b_agent: b done")
        .inOrder();
    assertEndOfAgent(resumed, "outer_agent");
  }

  // A leaf paused under a ParallelAgent resumes, and the enclosing SequentialAgent advances past
  // the completed parallel block -- the nesting must not defeat re-entering the workflow.
  @Test
  public void runAsync_resume_pausedUnderParallelAgent_advancesEnclosingSequential() {
    LlmAgent leaf =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi")),
                    createTextLlmResponse("leaf resumed")))
            .name("leaf_agent")
            .tools(pendingFunctionTool())
            .build();
    ParallelAgent parallel =
        ParallelAgent.builder().name("parallel_agent").subAgents(ImmutableList.of(leaf)).build();
    LlmAgent next =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("next ran")))
            .name("next_agent")
            .build();
    SequentialAgent root =
        SequentialAgent.builder()
            .name("seq_agent")
            .subAgents(ImmutableList.of(parallel, next))
            .build();
    Runner runner = resumableRunner(root);
    Session session = newSession(runner);

    runTurn(runner, session, "start");

    ImmutableList<Event> resumed =
        answerCall(runner, session, "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi"));

    // The leaf itself resumes and emits its post-tool response.
    assertThat(simplifyEvents(resumed)).contains("leaf_agent: leaf resumed");
    // The enclosing SequentialAgent then advances to the sub-agent after the parallel block.
    assertThat(simplifyEvents(resumed)).contains("next_agent: next ran");
  }

  // A leaf paused on two long-running calls under a ParallelAgent, answered once, stays paused
  // (parent-branch seeding keeps the other call visible).
  @Test
  public void runAsync_resume_pausedUnderParallelAgent_partiallyAnswered_staysPaused() {
    Content twoLongRunningCalls =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("c1")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "a"))
                            .build())
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("c2")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "b"))
                            .build())
                    .build())
            .build();
    LlmAgent leaf =
        createTestAgentBuilder(
                createTestLlm(
                    createLlmResponse(twoLongRunningCalls), createTextLlmResponse("leaf summary")))
            .name("leaf_agent")
            .tools(pendingFunctionTool())
            .build();
    ParallelAgent parallel =
        ParallelAgent.builder().name("parallel_agent").subAgents(ImmutableList.of(leaf)).build();
    SequentialAgent root =
        SequentialAgent.builder().name("seq_agent").subAgents(ImmutableList.of(parallel)).build();
    Runner runner = resumableRunner(root);
    Session session = newSession(runner);

    runTurn(runner, session, "start");

    // Answer only c1; c2 remains unanswered.
    ImmutableList<Event> resumed =
        answerCall(runner, session, "c1", "pendingTool", ImmutableMap.of("message", "a"));

    // c2 is still unanswered, so the model is not re-invoked: no "leaf summary", no new events.
    assertThat(simplifyEvents(resumed)).doesNotContain("leaf_agent: leaf summary");
    assertThat(resumed).isEmpty();
  }

  // Default (shim off): a plain-text continuation after a pause starts a NEW invocation, not a
  // resume; resuming is explicit (a function response or runAsync with an invocation id).
  @Test
  public void runAsync_plainTextContinuation_autoResumeFlagOff_startsNewInvocation() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("re-planned"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "draft the note");
    String invocationId = turn1.get(0).invocationId();

    ImmutableList<Event> turn2 = runTurn(runner, session, "Proceed");

    assertThat(testLlm.getRequests()).hasSize(2); // new invocation re-invoked the model
    assertThat(turn2.get(0).invocationId()).isNotEqualTo(invocationId);
  }

  // Gating: with resumability OFF (default) a completed LlmAgent emits no end-of-agent checkpoint,
  // keeping the event stream identical to before. Pairs with the resumable test above.
  @Test
  public void runAsync_resumabilityDisabled_completedLlmAgent_emitsNoEndOfAgent() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("all done")))
            .name("agent")
            .build();
    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(agent).build()).build();
    Session session = newSession(runner);

    ImmutableList<Event> events = runTurn(runner, session, "from user");

    assertThat(events.stream().anyMatch(event -> event.actions().endOfAgent())).isFalse();
  }

  // A resumable LlmAgent that completes normally emits a trailing end-of-agent checkpoint, and
  // resuming past it is a no-op: the active agent already ended, so nothing re-runs.
  @Test
  public void resume_completedInvocation_emitsEndOfAgentThenIsNoOp() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("all done")))
            .name("agent")
            .build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> firstTurn = runTurn(runner, session, "from user");

    // The completed agent closes with an end-of-agent checkpoint, so a later run can tell the
    // invocation finished.
    Event last = Iterables.getLast(firstTurn);
    assertThat(last.author()).isEqualTo("agent");
    assertThat(last.actions().endOfAgent()).isTrue();

    ImmutableList<Event> resumed =
        resume(
            runner,
            session,
            firstTurn.get(0).invocationId(),
            /* newMessage= */ null,
            /* stateDelta= */ null);

    assertThat(resumed).isEmpty();
  }

  // A function-response resume continues the SAME invocation that issued the matching call rather
  // than minting a new one, and merges a non-null stateDelta into the session as the
  // new-invocation path does.
  @Test
  public void resume_withFunctionResponseAndStateDelta_resumesSameInvocationAndMergesState() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("resumed answer"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> pausedTurn = runTurn(runner, session, "from user");
    String pausedInvocationId = pausedTurn.get(0).invocationId();
    assertThat(simplifyEvents(pausedTurn)).doesNotContain("agent: resumed answer");

    ImmutableMap<String, Object> stateDelta = ImmutableMap.of("key1", "value1", "key2", 42);
    ImmutableList<Event> resumed =
        ImmutableList.copyOf(
            runner
                .runAsync(
                    "user",
                    session.id(),
                    /* invocationId= */ null,
                    Content.fromParts(
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id("lro_call_id")
                                    .name("pendingTool")
                                    .response(ImmutableMap.of("message", "hello")))
                            .build()),
                    RunConfig.builder().build(),
                    stateDelta)
                .toList()
                .blockingGet());

    // The resumed events belong to the original (paused) invocation, not a fresh one.
    assertThat(resumed).isNotEmpty();
    assertThat(resumed.stream().allMatch(event -> event.invocationId().equals(pausedInvocationId)))
        .isTrue();
    assertThat(simplifyEvents(resumed)).contains("agent: resumed answer");
    assertThat(resumed.stream().anyMatch(event -> event.actions().endOfAgent())).isTrue();
    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(finalSession.state()).containsAtLeastEntriesIn(stateDelta);
    // The delta is also stamped on the appended (function-response) event, for history rehydration.
    Event lastUserEvent =
        Streams.findLast(
                finalSession.events().stream()
                    .filter(event -> Objects.equals(event.author(), "user")))
            .orElseThrow();
    assertThat(lastUserEvent.actions().stateDelta()).containsAtLeastEntriesIn(stateDelta);
  }

  // Python parity (REPLAY_CALLS): a branch whose last event carries a call the previous run never
  // executed runs that call on resume, instead of asking the model again.
  @Test
  public void resume_unexecutedFunctionCall_replaysCallInsteadOfCallingModel() {
    TestLlm testLlm = createTestLlm(createTextLlmResponse("should not be reached"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .tools(FunctionTool.create(Tools.class, "echoTool"))
            .build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);
    String invocationId = "inv-replay";
    var unusedUser =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id("u1")
                    .invocationId(invocationId)
                    .author("user")
                    .content(Content.fromParts(Part.fromText("go")))
                    .build())
            .blockingGet();
    // The agent's call was persisted but never executed: no function response follows it.
    var unusedCall =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id("c1")
                    .invocationId(invocationId)
                    .author("agent")
                    .content(
                        Content.fromParts(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .id("call_1")
                                        .name("echoTool")
                                        .args(ImmutableMap.of("message", "hi"))
                                        .build())
                                .build()))
                    .build())
            .blockingGet();

    ImmutableList<Event> resumed =
        resume(runner, session, invocationId, /* newMessage= */ null, /* stateDelta= */ null);

    // The persisted call was executed rather than re-requested: the scripted model only ever
    // returns text, so an echoTool response can only come from replaying the stored call.
    assertThat(
            resumed.stream()
                .flatMap(event -> event.functionResponses().stream())
                .map(response -> response.name().orElse(""))
                .collect(toImmutableList()))
        .contains("echoTool");
    // Replay happens before the model is consulted, so the first request already carries the
    // tool's response rather than asking the model to produce the call again.
    assertThat(testLlm.getRequests().get(0).contents().toString()).contains("echoTool");
  }

  // Python parity: resuming with a stateDelta but no message has no user event to carry the delta,
  // so it is persisted as a content-less event instead of being kept in memory only.
  @Test
  public void resume_withStateDeltaAndNoMessage_persistsStateDeltaEvent() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("done"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);
    ImmutableList<Event> turn1 = runTurn(runner, session, "start");
    String invocationId = turn1.get(0).invocationId();

    ImmutableMap<String, Object> stateDelta = ImmutableMap.of("key1", "value1");
    var unused = resume(runner, session, invocationId, /* newMessage= */ null, stateDelta);

    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(reloaded.state()).containsAtLeastEntriesIn(stateDelta);
    assertThat(
            reloaded.events().stream()
                .anyMatch(
                    event ->
                        event.content().isEmpty()
                            && event.actions().stateDelta().containsKey("key1")))
        .isTrue();
  }

  // ResumeInvocationTest parity: resume an OLDER paused invocation (not the latest) via its
  // long-running function response; the resumed run belongs to that older invocation.
  @Test
  public void resume_resumesAnyInvocation_notJustTheLatest() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "call-1", "pendingTool", ImmutableMap.of("message", "hi")),
            createTextLlmResponse("llm response in invocation 2"),
            createFunctionCallLlmResponse(
                "call-3", "pendingTool", ImmutableMap.of("message", "hi")),
            createTextLlmResponse("llm response after resuming invocation 1"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    // Invocation 1 pauses on the long-running call.
    ImmutableList<Event> inv1 = runTurn(runner, session, "q1");
    String inv1Id = inv1.get(0).invocationId();
    // Invocation 2 finishes; invocation 3 pauses again.
    runTurn(runner, session, "q2");
    runTurn(runner, session, "q3");

    // Resume invocation 1 (the oldest, not the latest) via its function response.
    ImmutableList<Event> resumed =
        ImmutableList.copyOf(
            runner
                .runAsync(
                    "user",
                    session.id(),
                    inv1Id,
                    Content.fromParts(
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id("call-1")
                                    .name("pendingTool")
                                    .response(ImmutableMap.of("message", "hi")))
                            .build()),
                    RunConfig.builder().build(),
                    /* stateDelta= */ null)
                .toList()
                .blockingGet());

    assertThat(simplifyEvents(resumed)).contains("agent: llm response after resuming invocation 1");
    assertThat(resumed.stream().allMatch(event -> event.invocationId().equals(inv1Id))).isTrue();
  }

  // InMemoryRunnerTest parity: resume by invocationId rehydrates the agent's checkpoint state from
  // history so the running agent observes it.
  @Test
  public void resume_restoresAgentStateFromHistory() {
    TestBaseAgent agent =
        new TestBaseAgent(
            "test_agent",
            "desc",
            () -> Flowable.<Event>empty(),
            /* subAgents= */ null,
            /* beforeAgentCallbacks= */ null,
            /* afterAgentCallbacks= */ null);
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);
    Object unusedUser =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id("u1")
                    .invocationId("test-inv")
                    .author("user")
                    .content(Content.fromParts(Part.fromText("hi")))
                    .build())
            .blockingGet();
    Object unusedState =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id("s1")
                    .invocationId("test-inv")
                    .author("test_agent")
                    .actions(
                        EventActions.builder()
                            .agentState(ImmutableMap.of("saved", "state"))
                            .build())
                    .content(Content.fromParts(Part.fromText("previous response")))
                    .build())
            .blockingGet();

    Object unused =
        runner
            .runAsync(
                "user",
                session.id(),
                "test-inv",
                /* newMessage= */ null,
                RunConfig.builder().build(),
                /* stateDelta= */ null)
            .toList()
            .blockingGet();

    assertThat(agent.getLastInvocationContext().agentStates())
        .containsEntry("test_agent", ImmutableMap.of("saved", "state"));
  }

  // InMemoryRunnerTest parity: resume by invocationId with a new user message appends that content
  // under the resumed invocation.
  @Test
  public void resume_withNewMessage_appendsUserContentUnderResumedInvocation() {
    TestBaseAgent agent =
        new TestBaseAgent("test_agent", "desc", () -> Flowable.<Event>empty(), null, null, null);
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);
    Object unusedUser =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id("u1")
                    .invocationId("test-inv")
                    .author("user")
                    .content(Content.fromParts(Part.fromText("hi")))
                    .build())
            .blockingGet();

    Object unused =
        runner
            .runAsync(
                "user",
                session.id(),
                "test-inv",
                Content.fromParts(Part.fromText("New message")),
                RunConfig.builder().build(),
                /* stateDelta= */ null)
            .toList()
            .blockingGet();

    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(reloaded.events()).hasSize(2);
    assertThat(
            Iterables.getLast(reloaded.events())
                .content()
                .flatMap(Content::parts)
                .get()
                .get(0)
                .text())
        .hasValue("New message");
  }

  // RunnerTest parity (disabled counterpart): resuming a non-resumable app throws.
  @Test
  public void resume_notResumable_throwsException() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("x"))).name("agent").build();
    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(agent).build()).build();
    Session session = newSession(runner);
    String sessionId = session.id();

    RunConfig runConfig = RunConfig.builder().build();
    assertThrows(
        IllegalStateException.class,
        () ->
            runner.runAsync(
                "user",
                sessionId,
                "some-inv",
                /* newMessage= */ null,
                runConfig,
                /* stateDelta= */ null));
  }

  // Resuming with a function response whose id matches no call in history is a caller error:
  // With NO invocation id, the runner would otherwise resolve the message to a new invocation;
  // it must reject the orphan function response instead of feeding it to the model. Paired with
  // resume_orphanFunctionResponseWithProvidedInvocationId_throwsIllegalArgument, which covers the
  // other entry point -- the two reach the check by different routes.
  @Test
  public void resume_functionResponseWithNoMatchingCall_throwsIllegalArgument() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("x"))).name("agent").build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    Content orphanResponse =
        Content.fromParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("no_such_call")
                        .name("pendingTool")
                        .response(ImmutableMap.of("status", "done")))
                .build());

    runner
        .runAsync(
            "user",
            session.id(),
            /* invocationId= */ null,
            orphanResponse,
            RunConfig.builder().build(),
            /* stateDelta= */ null)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  // A message mixing text with a function response is ambiguous -- the response resumes an
  // invocation while the text would start a new one -- so the runner rejects it, as Python does.
  @Test
  public void resume_functionResponseMixedWithText_throwsIllegalArgument() {
    LlmAgent agent =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi")),
                    createTextLlmResponse("done")))
            .name("agent")
            .tools(pendingFunctionTool())
            .build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);
    runTurn(runner, session, "start");

    Content mixed =
        Content.fromParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("lro_call_id")
                        .name("pendingTool")
                        .response(ImmutableMap.of("message", "hi")))
                .build(),
            Part.fromText("and also this"));

    runner
        .runAsync(
            "user",
            session.id(),
            /* invocationId= */ null,
            mixed,
            RunConfig.builder().build(),
            /* stateDelta= */ null)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  // Function responses answering calls from two different invocations cannot resume either one, so
  // the runner rejects the message instead of silently resuming just the newest invocation.
  @Test
  public void resume_functionResponsesSpanningTwoInvocations_throwsIllegalArgument() {
    LlmAgent agent =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "call_a", "pendingTool", ImmutableMap.of("message", "a")),
                    createFunctionCallLlmResponse(
                        "call_b", "pendingTool", ImmutableMap.of("message", "b"))))
            .name("agent")
            .tools(pendingFunctionTool())
            .build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "start");
    // Plain text starts a second invocation, which pauses on a long-running call of its own.
    ImmutableList<Event> turn2 = runTurn(runner, session, "another");
    assertThat(turn2.get(0).invocationId()).isNotEqualTo(turn1.get(0).invocationId());

    Content answersBoth =
        Content.fromParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("call_a")
                        .name("pendingTool")
                        .response(ImmutableMap.of("message", "a")))
                .build(),
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("call_b")
                        .name("pendingTool")
                        .response(ImmutableMap.of("message", "b")))
                .build());

    runner
        .runAsync(
            "user",
            session.id(),
            /* invocationId= */ null,
            answersBoth,
            RunConfig.builder().build(),
            /* stateDelta= */ null)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  // Resuming by invocation id alone carries no message, so the runner recovers the invocation's
  // original user message: callbacks and the model must not see empty user content.
  @Test
  public void resume_withNoNewMessage_restoresOriginalUserContent() {
    LlmAgent agent =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi")),
                    createTextLlmResponse("done")))
            .name("agent")
            .tools(pendingFunctionTool())
            .build();
    UserContentCapturingPlugin plugin = new UserContentCapturingPlugin();
    Runner runner = resumableRunner(agent, plugin);
    Session session = newSession(runner);

    ImmutableList<Event> pausedTurn = runTurn(runner, session, "the original ask");
    String pausedInvocationId = pausedTurn.get(0).invocationId();

    resume(runner, session, pausedInvocationId, /* newMessage= */ null, /* stateDelta= */ null);

    assertThat(plugin.lastUserText).isEqualTo("the original ask");
  }

  // A leaf paused on two long-running calls under a SequentialAgent, answered once, stays paused:
  // the leaf pauses without emitting anything, and the sequence must not read that as completion.
  @Test
  public void runAsync_resume_pausedUnderSequential_partiallyAnswered_doesNotAdvance() {
    Content twoLongRunningCalls =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("c1")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "a"))
                            .build())
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("c2")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "b"))
                            .build())
                    .build())
            .build();
    LlmAgent leaf =
        createTestAgentBuilder(
                createTestLlm(
                    createLlmResponse(twoLongRunningCalls), createTextLlmResponse("leaf summary")))
            .name("leaf_agent")
            .tools(pendingFunctionTool())
            .build();
    LlmAgent next =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("next ran")))
            .name("next_agent")
            .build();
    SequentialAgent root =
        SequentialAgent.builder().name("seq_agent").subAgents(ImmutableList.of(leaf, next)).build();
    Runner runner = resumableRunner(root);
    Session session = newSession(runner);

    runTurn(runner, session, "start");

    // Answer only c1; c2 remains unanswered.
    ImmutableList<Event> resumed =
        answerCall(runner, session, "c1", "pendingTool", ImmutableMap.of("message", "a"));

    // The sequence holds: neither the leaf's summary nor the following sub-agent runs.
    assertThat(simplifyEvents(resumed)).doesNotContain("leaf_agent: leaf summary");
    assertThat(simplifyEvents(resumed)).doesNotContain("next_agent: next ran");
  }

  // An LlmAgent whose transferred-to ParallelAgent holds the paused leaf: the HITL answer comes
  // back on the parallel branch, which the LlmAgent's own branch cannot see. It must still resume.
  @Test
  public void runAsync_resume_hitlAnswerOnSubBranch_resumesInsteadOfStalling() {
    LlmAgent leaf =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi")),
                    createTextLlmResponse("leaf resumed")))
            .name("leaf_agent")
            .tools(pendingFunctionTool())
            .build();
    ParallelAgent parallel =
        ParallelAgent.builder().name("parallel_agent").subAgents(ImmutableList.of(leaf)).build();
    LlmAgent root =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "transfer_call",
                        "transfer_to_agent",
                        ImmutableMap.of("agent_name", "parallel_agent"))))
            .name("root_agent")
            .subAgents(parallel)
            .build();
    Runner runner = resumableRunner(root);
    Session session = newSession(runner);

    ImmutableList<Event> firstTurn = runTurn(runner, session, "start");
    assertThat(simplifyEvents(firstTurn)).isNotEmpty();

    ImmutableList<Event> resumed =
        answerCall(runner, session, "lro_call_id", "pendingTool", ImmutableMap.of("message", "hi"));

    // The paused leaf resumes: the answer's branch does not strand the invocation.
    assertThat(simplifyEvents(resumed)).contains("leaf_agent: leaf resumed");
  }

  /** Records the user content each run is started with, so a resumed run can be checked. */
  private static final class UserContentCapturingPlugin extends BasePlugin {
    private @Nullable String lastUserText;

    UserContentCapturingPlugin() {
      super("user-content-capturing");
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
      lastUserText =
          invocationContext.userContent().flatMap(Content::parts).stream()
              .flatMap(List::stream)
              .map(part -> part.text().orElse(""))
              .findFirst()
              .orElse(null);
      return Maybe.empty();
    }
  }

  // Resuming a non-existent invocation with no new message has nothing to resume: runAsync surfaces
  // IllegalArgumentException rather than running an empty model call.
  @Test
  public void resume_nonExistentInvocationId_throwsIllegalArgument() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("x"))).name("agent").build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    runner
        .runAsync(
            "user",
            session.id(),
            "does-not-exist",
            /* newMessage= */ null,
            RunConfig.builder().build(),
            /* stateDelta= */ null)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  // With an explicit invocation id the runner skips message-based resolution entirely, so the
  // orphan check has to fire on this route too. Paired with
  // resume_functionResponseWithNoMatchingCall_throwsIllegalArgument, which covers the null-id
  // route; a regression could plausibly skip one and not the other.
  @Test
  public void resume_orphanFunctionResponseWithProvidedInvocationId_throwsIllegalArgument() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("x"))).name("agent").build();
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);

    Content orphanResponse =
        Content.fromParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("no_such_call")
                        .name("pendingTool")
                        .response(ImmutableMap.of("status", "done")))
                .build());

    runner
        .runAsync(
            "user",
            session.id(),
            "some-inv",
            orphanResponse,
            RunConfig.builder().build(),
            /* stateDelta= */ null)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  // InMemoryRunnerTest parity: the appended function-response inherits the branch of the function
  // call it answers.
  @Test
  public void resume_withFunctionResponse_copiesBranchFromMatchingCall() {
    TestBaseAgent agent =
        new TestBaseAgent("test_agent", "desc", () -> Flowable.<Event>empty(), null, null, null);
    Runner runner = resumableRunner(agent);
    Session session = newSession(runner);
    Object unusedFc =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id("fc1")
                    .invocationId("test-inv")
                    .author("test_agent")
                    .branch("my_special_branch")
                    .content(
                        Content.fromParts(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder().id("call_abc").name("test_func").build())
                                .build()))
                    .build())
            .blockingGet();

    Object unused =
        runner
            .runAsync(
                "user",
                session.id(),
                /* invocationId= */ null,
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("call_abc")
                                .name("test_func")
                                .response(ImmutableMap.of("result", "ok")))
                        .build()),
                RunConfig.builder().build(),
                /* stateDelta= */ null)
            .toList()
            .blockingGet();

    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    Event lastUser =
        Streams.findLast(reloaded.events().stream().filter(event -> event.author().equals("user")))
            .get();
    assertThat(lastUser.branch()).hasValue("my_special_branch");
  }

  // A pending long-running call must stop a resumable LoopAgent after the current iteration rather
  // than looping again (re-calling the model every iteration), matching Python ADK v1.
  @Test
  public void runAsync_loopAgentWithLongRunningSubAgent_resumable_stopsAfterFirstIteration() {
    AtomicInteger calls = new AtomicInteger();
    TestLlm loopLlm =
        createTestLlm(
            () ->
                calls.incrementAndGet() <= 5
                    ? Flowable.just(
                        createFunctionCallLlmResponse(
                            "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")))
                    : Flowable.just(createTextLlmResponse("stop")));
    LlmAgent inner =
        createTestAgentBuilder(loopLlm).name("inner").tools(pendingFunctionTool()).build();
    LoopAgent loop =
        LoopAgent.builder()
            .name("loop")
            .subAgents(ImmutableList.of(inner))
            .maxIterations(3)
            .build();
    Runner runner = resumableRunner(loop);
    Session session = newSession(runner);

    ImmutableList<Event> unused = runTurn(runner, session, "from user");

    // Paused after the first iteration: one model call, not maxIterations.
    assertThat(loopLlm.getRequests()).hasSize(1);
  }

  // In a resumable ParallelAgent, a pending long-running call pauses only its own branch (via the
  // flow); other branches still complete. ParallelAgent needs no special handling, matching Python
  // ADK v1 (cancelling siblings would diverge).
  @Test
  public void runAsync_parallelAgentWithLongRunningBranch_resumable_otherBranchCompletes() {
    TestLlm longRunningLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("unexpected"));
    LlmAgent longRunningBranch =
        createTestAgentBuilder(longRunningLlm)
            .name("long_running_branch")
            .tools(pendingFunctionTool())
            .build();
    LlmAgent plainBranch =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("plain branch done")))
            .name("plain_branch")
            .build();
    ParallelAgent parallel =
        ParallelAgent.builder()
            .name("parallel")
            .subAgents(ImmutableList.of(longRunningBranch, plainBranch))
            .build();
    Runner runner = resumableRunner(parallel);
    Session session = newSession(runner);

    ImmutableList<Event> events = runTurn(runner, session, "from user");

    // The long-running branch paused after one model call; the other branch still completed.
    assertThat(longRunningLlm.getRequests()).hasSize(1);
    assertThat(simplifyEvents(events)).contains("plain_branch: plain branch done");
  }
}
