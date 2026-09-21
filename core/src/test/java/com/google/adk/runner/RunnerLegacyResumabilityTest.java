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

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.sessions.Session;
import com.google.adk.telemetry.Tracing;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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

  public static class Tools {
    private Tools() {}

    public static ImmutableMap<String, Object> echoTool(String message) {
      return ImmutableMap.of("message", message);
    }

    // A long-running tool awaiting an external result has nothing to return yet; FunctionTool
    // coerces the absent return into an empty response.
    @SuppressWarnings("unused") // Invoked reflectively by FunctionTool.
    public static @Nullable ImmutableMap<String, Object> pendingTool(String message) {
      return null;
    }

    static final AtomicInteger pendingProgressToolCalls = new AtomicInteger(0);

    // A long-running tool that reports progress: it returns a non-empty "pending" status on the
    // initial call. Counts executions so a test can assert it runs exactly once across turns.
    @SuppressWarnings("unused") // Invoked reflectively by FunctionTool.
    public static ImmutableMap<String, Object> pendingProgressTool(String message) {
      pendingProgressToolCalls.incrementAndGet();
      return ImmutableMap.of("status", "pending");
    }
  }

  @Test
  @SuppressWarnings("deprecation") // Resumability flag is intentionally deprecated (partial).
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
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(workflowAgent)
                    .resumabilityConfig(
                        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build())
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> eventsBeforeConfirmation =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();

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
    List<Event> eventsAfterConfirmation =
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
            .blockingGet();

    // Turn 2: B resumes and executes the tool, then C runs. A is not re-run.
    assertThat(simplifyEvents(eventsAfterConfirmation))
        .containsExactly(
            "b_agent: FunctionResponse(name=echoTool, response={message=hello})",
            "b_agent: Response after user confirmed.",
            "c_agent: agent C done")
        .inOrder();
  }

  @Test
  @SuppressWarnings("deprecation") // Resumability flag is intentionally deprecated (partial).
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
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(workflowAgent)
                    .resumabilityConfig(
                        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build())
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> eventsBeforeResume =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();

    // Turn 1: A runs, B issues the long-running call and pauses; C must not run yet. B must not
    // make
    // a further model call after the pending call.
    assertThat(simplifyEvents(eventsBeforeResume)).contains("a_agent: agent A done");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("b_agent: agent B resumed");
    assertThat(simplifyEvents(eventsBeforeResume)).doesNotContain("c_agent: agent C done");

    List<Event> eventsAfterResume =
        runner
            .runAsync(
                "user",
                session.id(),
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("lro_call_id")
                                .name("echoTool")
                                .response(ImmutableMap.of("message", "hello")))
                        .build()))
            .toList()
            .blockingGet();

    // Turn 2: B resumes from the long-running response, then C runs. A is not re-run.
    assertThat(simplifyEvents(eventsAfterResume))
        .containsExactly("b_agent: agent B resumed", "c_agent: agent C done")
        .inOrder();
  }

  @Test
  @SuppressWarnings("deprecation") // Resumability flag is intentionally deprecated (partial).
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
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .resumabilityConfig(
                        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build())
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> events =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();

    // The flow paused after the single long-running call instead of re-calling the model.
    assertThat(testLlm.getRequests()).hasSize(1);
    assertThat(simplifyEvents(events)).doesNotContain("agent: should not be reached");
  }

  @Test
  @SuppressWarnings("deprecation") // Resumability flag is intentionally deprecated (partial).
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
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(loop)
                    .resumabilityConfig(
                        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build())
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> unused =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();

    // Paused after the first iteration: one model call, not maxIterations.
    assertThat(loopLlm.getRequests()).hasSize(1);
  }

  @Test
  @SuppressWarnings("deprecation") // Resumability flag is intentionally deprecated (partial).
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
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(parallel)
                    .resumabilityConfig(
                        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build())
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> events =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();

    // The long-running branch paused after one model call; the other branch still completed.
    assertThat(longRunningLlm.getRequests()).hasSize(1);
    assertThat(simplifyEvents(events)).contains("plain_branch: plain branch done");
  }
}
