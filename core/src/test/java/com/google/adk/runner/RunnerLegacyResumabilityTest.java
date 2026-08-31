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
import static com.google.adk.testing.ResumabilityTestUtils.newSession;
import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.ResumabilityTestUtils.runTurn;
import static com.google.adk.testing.ResumabilityTestUtils.shimRunner;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.sessions.Session;
import com.google.adk.telemetry.Tracing;
import com.google.adk.testing.ResumabilityTestUtils.Tools;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Runner tests for the deprecated plain-text continuation shim.
 *
 * <p>Includes a copy of every resumability test that predates durable checkpoints, re-run under the
 * shim, so the shim keeps behaving exactly as resumability did before it was split in two.
 */
@RunWith(JUnit4.class)
@SuppressWarnings("deprecation") // The class exists to exercise the deprecated shim flag.
public final class RunnerLegacyResumabilityTest {
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private Tracer originalTracer;

  @Before
  public void setUp() {
    this.originalTracer = Tracing.getTracer();
    Tracing.setTracerForTesting(
        openTelemetryRule.getOpenTelemetry().getTracer("RunnerLegacyResumabilityTest"));
  }

  @After
  public void tearDown() {
    Tracing.setTracerForTesting(originalTracer);
  }

  // Regression: with the plain-text auto-resume shim, a transferred invocation must not look
  // unfinished forever. After a transfer the root closes and later turns run the sub-agent, so a
  // finished-check keyed on the root wedged every plain-text turn from turn 3 on.
  @Test
  public void runAsync_resumableTransferWithPlainTextAutoResume_laterTurnsRunSubAgent() {
    Content transferCall =
        Content.fromParts(
            Part.fromFunctionCall(
                "transfer_to_agent", ImmutableMap.of("agent_name", "sub_agent_1")));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(transferCall),
            createTextLlmResponse("r1"),
            createTextLlmResponse("r2"),
            createTextLlmResponse("r3"),
            createTextLlmResponse("r4"),
            createTextLlmResponse("r5"));
    LlmAgent subAgent1 = createTestAgentBuilder(testLlm).name("sub_agent_1").build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(subAgent1))
            .build();
    Runner runner = shimRunner(rootAgent);
    Session session = newSession(runner);

    // Turn 1 transfers to the sub-agent; turns 2-5 (plain text) must each be answered by the
    // sub-agent rather than wedging to an empty stream.
    var unused = runTurn(runner, session, "m1");
    for (String expected : new String[] {"r2", "r3", "r4", "r5"}) {
      ImmutableList<Event> turn = runTurn(runner, session, "m");
      assertThat(simplifyEvents(turn)).contains("sub_agent_1: " + expected);
    }
  }

  // Rollout guard: a completed checkpoint-less session (created before checkpoints existed) has no
  // end-of-agent signal, so the plain-text auto-resume shim must not re-attach to it; it starts a
  // new invocation, keeping the per-turn invocation scoping downstream callbacks rely on.
  @Test
  public void runAsync_plainTextAutoResume_checkpointlessSession_startsNewInvocation() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("second answer")))
            .name("agent")
            .build();
    Runner runner = shimRunner(agent);
    Session session = newSession(runner);

    // Seed a completed prior turn with NO resumability checkpoints (no endOfAgent / agentState), as
    // a pre-checkpoint session looks on the wire.
    String priorInvocationId = "pre_checkpoint_invocation";
    Event unusedUserEvent =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id(Event.generateEventId())
                    .invocationId(priorInvocationId)
                    .author("user")
                    .content(Content.fromParts(Part.fromText("first turn")))
                    .build())
            .blockingGet();
    Event unusedModelEvent =
        runner
            .sessionService()
            .appendEvent(
                session,
                Event.builder()
                    .id(Event.generateEventId())
                    .invocationId(priorInvocationId)
                    .author("agent")
                    .content(Content.fromParts(Part.fromText("first answer")))
                    .build())
            .blockingGet();

    ImmutableList<Event> secondTurn = runTurn(runner, session, "second turn");

    // The shim must not re-attach to the checkpoint-less prior invocation: the agent runs under a
    // fresh invocation id.
    assertThat(simplifyEvents(secondTurn)).contains("agent: second answer");
    assertThat(
            secondTurn.stream()
                .map(Event::invocationId)
                .filter(priorInvocationId::equals)
                .collect(toImmutableList()))
        .isEmpty();
  }

  // The shim selects the legacy resumption flow but does NOT resume on a plain-text turn: that
  // starts a new invocation, as it did before the flag was honored.
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_plainTextContinuation_shimOn_startsNewInvocation() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("continued"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = shimRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "draft the note");
    String invocationId = turn1.get(0).invocationId();
    assertThat(testLlm.getRequests()).hasSize(1); // paused after a single model call

    ImmutableList<Event> turn2 = runTurn(runner, session, "Proceed");

    // The shim does not resume on plain text: the continuation opens a NEW invocation and the
    // agent plans again, exactly as it did before the shim was honored.
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(turn2).isNotEmpty();
    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    Event lastUserEvent =
        Streams.findLast(
                reloaded.events().stream().filter(event -> Objects.equals(event.author(), "user")))
            .orElse(null);
    assertThat(lastUserEvent).isNotNull();
    assertThat(lastUserEvent.invocationId()).isNotEqualTo(invocationId);
    assertThat(turn2.stream().noneMatch(event -> invocationId.equals(event.invocationId())))
        .isTrue();
  }

  // Guard: even with the auto-resume shim on, a plain-text message after a finished turn starts a
  // NEW invocation (the shim resumes only unfinished invocations, so it never swallows a new turn).
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_plainText_autoResumeFlagOn_afterCompletedTurn_startsNewInvocation() {
    TestLlm testLlm =
        createTestLlm(
            createTextLlmResponse("first answer"), createTextLlmResponse("second answer"));
    LlmAgent agent = createTestAgentBuilder(testLlm).name("agent").build();
    Runner runner = shimRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "hi");
    String invocationId = turn1.get(0).invocationId();
    assertThat(simplifyEvents(turn1)).contains("agent: first answer");

    ImmutableList<Event> turn2 = runTurn(runner, session, "again");

    assertThat(simplifyEvents(turn2)).contains("agent: second answer");
    assertThat(turn2.get(0).invocationId()).isNotEqualTo(invocationId);
  }

  // Nested topology: a paused long-running call inside an LlmAgent nested in a SequentialAgent is
  // treated like the flat case -- the shim does not resume on plain text.
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_plainTextContinuation_inSequentialAgent_shimOn_startsNewInvocation() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("should not re-plan"));
    LlmAgent childAgent =
        createTestAgentBuilder(testLlm).name("child_agent").tools(pendingFunctionTool()).build();
    SequentialAgent workflowAgent =
        SequentialAgent.builder()
            .name("workflow_agent")
            .subAgents(ImmutableList.of(childAgent))
            .build();
    Runner runner = shimRunner(workflowAgent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "draft the note");
    String invocationId = turn1.get(0).invocationId();
    assertThat(testLlm.getRequests()).hasSize(1); // paused inside the workflow after one model call

    Object unused = runTurn(runner, session, "Proceed");

    // Nested under a SequentialAgent the shim behaves as it does for a flat agent: the plain-text
    // turn opens a new invocation rather than resuming the paused one.
    assertThat(testLlm.getRequests()).hasSize(2);
    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    Event lastUserEvent =
        Streams.findLast(
                reloaded.events().stream().filter(event -> Objects.equals(event.author(), "user")))
            .orElse(null);
    assertThat(lastUserEvent).isNotNull();
    assertThat(lastUserEvent.invocationId()).isNotEqualTo(invocationId);
  }

  // Shim-only: a long-running tool that returns a value in the same turn still pauses the flow
  // after one model call, as it did before checkpoints. Guards BaseLlmFlow's post-step pause, which
  // is now reachable only from the shim -- a value-returning tool is the case that distinguishes it
  // from the flow simply ending on a final response.
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_shimOnly_valueReturningLongRunningCall_pausesAfterSingleModelCall() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "echoTool", ImmutableMap.of("message", "hi")),
            createTextLlmResponse("should not be reached"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .tools(
                FunctionTool.create(
                    Tools.class,
                    "echoTool",
                    /* requireConfirmation= */ false,
                    /* isLongRunning= */ true))
            .build();
    Runner runner = shimRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> events = runTurn(runner, session, "go");

    // The tool answered in-turn, but the flow still pauses rather than summarizing.
    assertThat(testLlm.getRequests()).hasSize(1);
    assertThat(simplifyEvents(events)).doesNotContain("agent: should not be reached");
  }

  // Shim-only: the LoopAgent legacy body stops looping once a sub-agent emits a pending
  // long-running call, and writes no checkpoint.
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_shimOnlyInLoopAgent_stopsOnPendingCallAndWritesNoCheckpoints() {
    TestLlm llm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("second iteration"));
    LlmAgent child =
        createTestAgentBuilder(llm).name("looped_agent").tools(pendingFunctionTool()).build();
    LoopAgent loopAgent =
        LoopAgent.builder().name("loop").maxIterations(3).subAgents(child).build();
    Runner runner = shimRunner(loopAgent);
    Session session = newSession(runner);

    var unused = runTurn(runner, session, "start");

    // The pending call stops the loop after the first iteration, and nothing is checkpointed.
    assertThat(llm.getRequests()).hasSize(1);
    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(
            reloaded.events().stream()
                .filter(
                    event ->
                        event.actions().endOfAgent() || event.actions().agentState().isPresent())
                .collect(toImmutableList()))
        .isEmpty();
  }

  // Shim-only (resumable off, shim on): a long-running pause in the middle sub-agent holds the
  // workflow, and the plain-text continuation restarts the sequence -- the pre-checkpoint behavior
  // the shim reproduces. The point of the test is that no checkpoint is ever persisted.
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_shimOnlyInSequentialAgent_restartsSequenceAndWritesNoCheckpoints() {
    TestLlm llmA = createTestLlm(createTextLlmResponse("A ran"), createTextLlmResponse("A replay"));
    TestLlm llmB =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("B done"));
    TestLlm llmC = createTestLlm(createTextLlmResponse("C ran"));
    LlmAgent agentA = createTestAgentBuilder(llmA).name("agent_a").build();
    LlmAgent agentB =
        createTestAgentBuilder(llmB).name("agent_b").tools(pendingFunctionTool()).build();
    LlmAgent agentC = createTestAgentBuilder(llmC).name("agent_c").build();
    SequentialAgent workflowAgent =
        SequentialAgent.builder()
            .name("workflow_agent")
            .subAgents(ImmutableList.of(agentA, agentB, agentC))
            .build();
    Runner runner = shimRunner(workflowAgent);
    Session session = newSession(runner);

    ImmutableList<Event> turn1 = runTurn(runner, session, "draft the note");

    // Turn 1 pauses at agent_b: agent_c never runs.
    assertThat(llmA.getRequests()).hasSize(1);
    assertThat(llmB.getRequests()).hasSize(1);
    assertThat(llmC.getRequests()).isEmpty();
    assertThat(simplifyEvents(turn1)).doesNotContain("agent_c: C ran");

    ImmutableList<Event> turn2 = runTurn(runner, session, "Proceed");

    // No durable state to resume from, so the sequence restarts at agent_a and runs through.
    assertThat(llmA.getRequests()).hasSize(2);
    assertThat(llmB.getRequests()).hasSize(2);
    assertThat(llmC.getRequests()).hasSize(1);
    assertThat(simplifyEvents(turn2)).contains("agent_c: C ran");

    // The shim never writes durable state: nothing was checkpointed into the session.
    Session reloaded =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(
            reloaded.events().stream()
                .filter(
                    event ->
                        event.actions().endOfAgent() || event.actions().agentState().isPresent())
                .collect(toImmutableList()))
        .isEmpty();
  }

  // Shim-only, nested workflow: a grandchild-authored pause behaves like the flat case -- the
  // plain-text continuation restarts the sequence rather than resuming into the paused sub-agent.
  @Test
  @SuppressWarnings(
      "deprecation") // Resumability + the auto-resume shim are intentionally deprecated.
  public void runAsync_shimOnlyInNestedSequentialAgent_restartsSequence() {
    TestLlm llmA = createTestLlm(createTextLlmResponse("A ran"), createTextLlmResponse("A replay"));
    TestLlm llmB =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "draft")),
            createTextLlmResponse("B done"));
    LlmAgent agentA = createTestAgentBuilder(llmA).name("agent_a").build();
    LlmAgent agentB =
        createTestAgentBuilder(llmB).name("agent_b").tools(pendingFunctionTool()).build();
    SequentialAgent innerWorkflow =
        SequentialAgent.builder()
            .name("inner_workflow")
            .subAgents(ImmutableList.of(agentB))
            .build();
    SequentialAgent workflowAgent =
        SequentialAgent.builder()
            .name("workflow_agent")
            .subAgents(ImmutableList.of(agentA, innerWorkflow))
            .build();
    Runner runner = shimRunner(workflowAgent);
    Session session = newSession(runner);

    var unused = runTurn(runner, session, "draft the note");
    ImmutableList<Event> turn2 = runTurn(runner, session, "Proceed");

    assertThat(llmA.getRequests()).hasSize(2); // the sequence restarts at agent_a
    assertThat(llmB.getRequests()).hasSize(2);
    assertThat(simplifyEvents(turn2)).contains("agent_b: B done");
  }

  // With the deprecated shim, a plain-text continuation starts a new invocation and a non-null
  // stateDelta is still merged into the session.
  @Test
  public void runAsync_plainTextWithShim_withStateDelta_mergesStateIntoSession() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "pendingTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("should not be reached"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm).name("agent").tools(pendingFunctionTool()).build();
    Runner runner = shimRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> pausedTurn = runTurn(runner, session, "start");
    String pausedInvocationId = pausedTurn.get(0).invocationId();
    assertThat(testLlm.getRequests()).hasSize(1); // paused after a single model call

    // Plain-text "Proceed" via the non-resume overload; the flag resumes the paused invocation.
    ImmutableMap<String, Object> stateDelta = ImmutableMap.of("key1", "value1", "key2", 42);
    ImmutableList<Event> resumed =
        ImmutableList.copyOf(
            runner
                .runAsync(
                    "user",
                    session.id(),
                    Content.fromParts(Part.fromText("Proceed")),
                    RunConfig.builder().build(),
                    stateDelta)
                .toList()
                .blockingGet());

    // The shim starts a new invocation for the plain-text turn; the stateDelta still lands.
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(resumed).isNotEmpty();
    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    Event continuation =
        Streams.findLast(
                finalSession.events().stream()
                    .filter(event -> Objects.equals(event.author(), "user")))
            .orElseThrow();
    assertThat(continuation.invocationId()).isNotEqualTo(pausedInvocationId);
    assertThat(continuation.actions().stateDelta()).containsAtLeastEntriesIn(stateDelta);
    assertThat(finalSession.state()).containsAtLeastEntriesIn(stateDelta);
  }

  // ===== CL1-parity: every CL1 resumable(true) test, re-run under the text-only shim =====

  @Test
  public void
      runAsync_withToolConfirmation_inSequentialAgent_runsLaterSubAgentsAfterResume_legacyShim() {
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
            .tools(FunctionTool.create(Tools.class, "echoTool", /* requireConfirmation= */ true))
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
    Runner runner = shimRunner(workflowAgent);
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

    // Turn 2: B resumes and executes the tool, then C runs. A is not re-run.
    assertThat(simplifyEvents(eventsAfterConfirmation))
        .containsExactly(
            "b_agent: FunctionResponse(name=echoTool, response={message=hello})",
            "b_agent: Response after user confirmed.",
            "c_agent: agent C done")
        .inOrder();
  }

  @Test
  public void
      runAsync_withLongRunningCall_inSequentialAgent_runsLaterSubAgentsAfterResume_legacyShim() {
    LlmAgent agentA =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("agent A done")))
            .name("a_agent")
            .build();
    // With resumability on, B pauses right after the long-running call (no extra model call), so a
    // single follow-up response covers the resume.
    TestLlm bTestLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("agent B resumed"));
    LlmAgent agentB =
        createTestAgentBuilder(bTestLlm)
            .name("b_agent")
            .tools(
                FunctionTool.create(
                    Tools.class,
                    "echoTool",
                    /* requireConfirmation= */ false,
                    /* isLongRunning= */ true))
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
    Runner runner = shimRunner(workflowAgent);
    Session session = newSession(runner);

    ImmutableList<Event> eventsBeforeResume = runTurn(runner, session, "from user");

    // Turn 1: A runs, B issues the long-running call and pauses; C must not run yet. B must not
    // make
    // a further model call after the pending call.
    assertThat(simplifyEvents(eventsBeforeResume)).contains("a_agent: agent A done");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("b_agent: agent B resumed");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("c_agent: agent C done");

    ImmutableList<Event> eventsAfterResume =
        answerCall(runner, session, "lro_call_id", "echoTool", ImmutableMap.of("message", "hello"));

    // Turn 2: B resumes from the long-running response, then C runs. A is not re-run.
    assertThat(simplifyEvents(eventsAfterResume))
        .containsExactly("b_agent: agent B resumed", "c_agent: agent C done")
        .inOrder();
  }

  @Test
  public void runAsync_withLongRunningCall_resumable_pausesAfterSingleModelCall_legacyShim() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            // Extra responses the flow must NOT consume; reaching them means it looped.
            createFunctionCallLlmResponse(
                "lro_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("should not be reached"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .tools(
                FunctionTool.create(
                    Tools.class,
                    "echoTool",
                    /* requireConfirmation= */ false,
                    /* isLongRunning= */ true))
            .build();
    Runner runner = shimRunner(agent);
    Session session = newSession(runner);

    ImmutableList<Event> events = runTurn(runner, session, "from user");

    // The flow paused after the single long-running call instead of re-calling the model.
    assertThat(testLlm.getRequests()).hasSize(1);
    assertThat(simplifyEvents(events)).doesNotContain("agent: should not be reached");
  }

  @Test
  public void
      runAsync_loopAgentWithLongRunningSubAgent_resumable_stopsAfterFirstIteration_legacyShim() {
    AtomicInteger calls = new AtomicInteger();
    TestLlm loopLlm =
        createTestLlm(
            () ->
                calls.incrementAndGet() <= 5
                    ? Flowable.just(
                        createFunctionCallLlmResponse(
                            "lro_call_id", "echoTool", ImmutableMap.of("message", "hello")))
                    : Flowable.just(createTextLlmResponse("stop")));
    LlmAgent inner =
        createTestAgentBuilder(loopLlm)
            .name("inner")
            .tools(
                FunctionTool.create(
                    Tools.class,
                    "echoTool",
                    /* requireConfirmation= */ false,
                    /* isLongRunning= */ true))
            .build();
    LoopAgent loop =
        LoopAgent.builder()
            .name("loop")
            .subAgents(ImmutableList.of(inner))
            .maxIterations(3)
            .build();
    Runner runner = shimRunner(loop);
    Session session = newSession(runner);

    ImmutableList<Event> unused = runTurn(runner, session, "from user");

    // Paused after the first iteration: one model call, not maxIterations.
    assertThat(loopLlm.getRequests()).hasSize(1);
  }

  @Test
  public void
      runAsync_parallelAgentWithLongRunningBranch_resumable_otherBranchCompletes_legacyShim() {
    TestLlm longRunningLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "lro_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("unexpected"));
    LlmAgent longRunningBranch =
        createTestAgentBuilder(longRunningLlm)
            .name("long_running_branch")
            .tools(
                FunctionTool.create(
                    Tools.class,
                    "echoTool",
                    /* requireConfirmation= */ false,
                    /* isLongRunning= */ true))
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
    Runner runner = shimRunner(parallel);
    Session session = newSession(runner);

    ImmutableList<Event> events = runTurn(runner, session, "from user");

    // The long-running branch paused after one model call; the other branch still completed.
    assertThat(longRunningLlm.getRequests()).hasSize(1);
    assertThat(simplifyEvents(events)).contains("plain_branch: plain branch done");
  }
}
