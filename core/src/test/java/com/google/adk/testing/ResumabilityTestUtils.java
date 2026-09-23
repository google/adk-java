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

package com.google.adk.testing;

import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyResumableEvents;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Arrays.stream;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Helpers shared by the resumability tests: building a runner in each resumption mode, driving
 * turns and resumes through it, and asserting on the checkpoints it emits.
 *
 * <p>Separate from {@link TestUtils} because these reach the {@link Runner} and {@link App} layer,
 * which the agent-level helpers there do not.
 */
public final class ResumabilityTestUtils {

  /** Tools the resumability tests pause on. */
  public static final class Tools {
    private Tools() {}

    /** Returns its argument, so a call to it completes within the turn. */
    public static ImmutableMap<String, Object> echoTool(String message) {
      return ImmutableMap.of("message", message);
    }

    /** Awaits a result supplied later, so a call to it leaves the invocation paused. */
    public static @Nullable ImmutableMap<String, Object> pendingTool(String message) {
      return null;
    }
  }

  /** {@code pendingTool} wired long-running: the shape that pauses an invocation. */
  public static FunctionTool pendingFunctionTool() {
    return FunctionTool.create(
        Tools.class, "pendingTool", /* requireConfirmation= */ false, /* isLongRunning= */ true);
  }

  /** {@code echoTool} wired long-running but returning in-turn, so it does not pause. */
  public static FunctionTool longRunningEchoFunctionTool() {
    return FunctionTool.create(
        Tools.class, "echoTool", /* requireConfirmation= */ false, /* isLongRunning= */ true);
  }

  /** {@code echoTool} requiring confirmation: the human-in-the-loop shape. */
  public static FunctionTool confirmingEchoFunctionTool() {
    return FunctionTool.create(Tools.class, "echoTool", /* requireConfirmation= */ true);
  }

  /** An {@link LlmAgent} named {@code name} whose model returns {@code texts}, one per call. */
  public static LlmAgent textAgent(String name, String... texts) {
    return createTestAgentBuilder(
            createTestLlm(
                stream(texts).map(TestUtils::createTextLlmResponse).toArray(LlmResponse[]::new)))
        .name(name)
        .build();
  }

  /** An {@link LlmAgent} named {@code name} carrying the long-running {@code pendingTool}. */
  public static LlmAgent pausingAgent(String name, TestLlm llm) {
    return createTestAgentBuilder(llm).name(name).tools(pendingFunctionTool()).build();
  }

  /** A model script that calls {@code pendingTool} once, then says {@code then}. */
  public static TestLlm pauseThenSay(String callId, String then) {
    return createTestLlm(
        TestUtils.createFunctionCallLlmResponse(
            callId, "pendingTool", ImmutableMap.of("message", "hello")),
        createTextLlmResponse(then));
  }

  /** A {@link Runner} over {@code rootAgent} with durable resumability enabled. */
  @SuppressWarnings("deprecation") // ResumabilityConfig is @Experimental, not deprecated, here.
  public static Runner resumableRunner(BaseAgent rootAgent, BasePlugin... plugins) {
    return Runner.builder()
        .app(
            App.builder()
                .name("test")
                .rootAgent(rootAgent)
                .plugins(ImmutableList.copyOf(plugins))
                .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
                .build())
        .build();
  }

  /** A {@link Runner} over {@code rootAgent} with only the deprecated plain-text shim enabled. */
  @SuppressWarnings("deprecation") // The plain-text continuation shim is deprecated by design.
  public static Runner shimRunner(BaseAgent rootAgent) {
    return Runner.builder()
        .app(
            App.builder()
                .name("test")
                .rootAgent(rootAgent)
                .resumabilityConfig(
                    ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build())
                .build())
        .build();
  }

  /** A fresh session on {@code runner}'s session service. */
  public static Session newSession(Runner runner) {
    return runner.sessionService().createSession("test", "user").blockingGet();
  }

  /** Runs one new plain-text turn and returns its events. */
  @CanIgnoreReturnValue
  public static ImmutableList<Event> runTurn(Runner runner, Session session, String text) {
    return ImmutableList.copyOf(
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText(text)))
            .toList()
            .blockingGet());
  }

  /** Content answering a pending function call, as a user turn carries it. */
  public static Content functionResponseContent(
      String callId, String toolName, Map<String, Object> response) {
    return Content.fromParts(
        Part.builder()
            .functionResponse(
                FunctionResponse.builder().id(callId).name(toolName).response(response))
            .build());
  }

  /** Resumes by answering the pending call {@code callId}; the runner resolves the invocation. */
  @CanIgnoreReturnValue
  public static ImmutableList<Event> answerCall(
      Runner runner,
      Session session,
      String callId,
      String toolName,
      Map<String, Object> response) {
    return ImmutableList.copyOf(
        runner
            .runAsync("user", session.id(), functionResponseContent(callId, toolName, response))
            .toList()
            .blockingGet());
  }

  /** The un-subscribed resume stream, for tests asserting on the error rather than the events. */
  public static Flowable<Event> resumeFlowable(
      Runner runner,
      Session session,
      @Nullable String invocationId,
      @Nullable Content newMessage,
      @Nullable Map<String, Object> stateDelta) {
    return runner.runAsync(
        "user", session.id(), invocationId, newMessage, RunConfig.builder().build(), stateDelta);
  }

  /** The six-arg resume overload, for tests that vary the invocation id or the state delta. */
  @CanIgnoreReturnValue
  public static ImmutableList<Event> resume(
      Runner runner,
      Session session,
      @Nullable String invocationId,
      @Nullable Content newMessage,
      @Nullable Map<String, Object> stateDelta) {
    return ImmutableList.copyOf(
        resumeFlowable(runner, session, invocationId, newMessage, stateDelta)
            .toList()
            .blockingGet());
  }

  /** Resumes {@code invocationId} with no new message. */
  @CanIgnoreReturnValue
  public static ImmutableList<Event> resumeById(
      Runner runner, Session session, @Nullable String invocationId) {
    return resume(runner, session, invocationId, /* newMessage= */ null, /* stateDelta= */ null);
  }

  /** Reloads {@code session} from the runner's session service, to assert on persisted state. */
  public static Session reloadSession(Runner runner, Session session) {
    return runner
        .sessionService()
        .getSession("test", "user", session.id(), Optional.empty())
        .blockingGet();
  }

  /**
   * A model turn issuing two long-running calls at once: the shape that pauses until both answer.
   */
  public static Content twoPendingCalls(String firstId, String secondId) {
    return Content.builder()
        .role("model")
        .parts(pendingCallPart(firstId, "a"), pendingCallPart(secondId, "b"))
        .build();
  }

  private static Part pendingCallPart(String callId, String message) {
    return Part.builder()
        .functionCall(
            FunctionCall.builder()
                .id(callId)
                .name("pendingTool")
                .args(ImmutableMap.of("message", message))
                .build())
        .build();
  }

  /** Asserts {@code author} emitted an end-of-agent checkpoint. */
  public static void assertEndOfAgent(List<Event> events, String author) {
    assertWithMessage(
            "end-of-agent checkpoint for %s in %s", author, simplifyResumableEvents(events))
        .that(
            events.stream()
                .anyMatch(event -> Objects.equals(event.author(), author) && endsAgent(event)))
        .isTrue();
  }

  /** Asserts {@code author} emitted no end-of-agent checkpoint. */
  public static void assertNoEndOfAgent(List<Event> events, String author) {
    assertWithMessage(
            "unexpected end-of-agent checkpoint for %s in %s",
            author, simplifyResumableEvents(events))
        .that(
            events.stream()
                .anyMatch(event -> Objects.equals(event.author(), author) && endsAgent(event)))
        .isFalse();
  }

  /** Asserts whether {@code author} emitted an agent-state checkpoint. */
  public static void assertAgentStateCheckpoint(
      List<Event> events, String author, boolean expected) {
    assertWithMessage(
            "agent-state checkpoint for %s in %s", author, simplifyResumableEvents(events))
        .that(
            events.stream()
                .anyMatch(
                    event ->
                        Objects.equals(event.author(), author)
                            && event.actions().agentState().isPresent()))
        .isEqualTo(expected);
  }

  /** Asserts {@code session} holds no resumability checkpoints at all. */
  public static void assertNoCheckpoints(Session session) {
    assertThat(
            session.events().stream()
                .filter(event -> endsAgent(event) || event.actions().agentState().isPresent())
                .collect(toImmutableList()))
        .isEmpty();
  }

  private static boolean endsAgent(Event event) {
    return event.actions().endOfAgent();
  }

  private ResumabilityTestUtils() {}
}
