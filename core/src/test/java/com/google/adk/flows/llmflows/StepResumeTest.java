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

package com.google.adk.flows.llmflows;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link StepResume}. Each history ends with the user answering another agent's
 * long-running call, so the step checks whether this agent's earlier calls still need replaying.
 */
@RunWith(JUnit4.class)
public final class StepResumeTest {

  private static final String AGENT = "agent";
  private static final String OTHER_AGENT = "other_agent";
  private static final String USER = "user";
  private static final String INVOCATION = "invocation";

  @Test
  public void decide_partlyAnsweredParallelCalls_replaysOnlyTheUnansweredCall() {
    StepResume.Decision decision =
        decide(
            event(AGENT, callPart("x", "c1"), callPart("y", "c2")),
            event(AGENT, responsePart("x", "c1")));

    assertThat(decision.action).isEqualTo(StepResume.Action.REPLAY_CALLS);
    assertThat(decision.replayEvent().functionCalls()).containsExactly(call("y", "c2"));
  }

  @Test
  public void decide_partlyAnsweredCallsWithText_keepsTheTextInTheReplay() {
    Part text = Part.fromText("Checking both.");
    StepResume.Decision decision =
        decide(
            event(AGENT, text, callPart("x", "c1"), callPart("y", "c2")),
            event(AGENT, responsePart("x", "c1")));

    assertThat(decision.action).isEqualTo(StepResume.Action.REPLAY_CALLS);
    assertThat(decision.replayEvent().content().get().parts().get())
        .containsExactly(text, callPart("y", "c2"))
        .inOrder();
  }

  @Test
  public void decide_noCallAnswered_replaysTheCallEventUnchanged() {
    Event callEvent = event(AGENT, callPart("x", "c1"), callPart("y", "c2"));

    StepResume.Decision decision = decide(callEvent);

    assertThat(decision.action).isEqualTo(StepResume.Action.REPLAY_CALLS);
    assertThat(decision.replayEvent()).isEqualTo(callEvent);
  }

  @Test
  public void decide_idlessCallAnsweredByName_doesNotReplay() {
    StepResume.Decision decision =
        decide(event(AGENT, callPart("x", null)), event(AGENT, responsePart("x", null)));

    assertThat(decision.action).isEqualTo(StepResume.Action.CONTINUE);
  }

  @Test
  public void decide_idMatch_leavesTheNameOnlyResponseForAnotherCall() {
    StepResume.Decision decision =
        decide(
            event(AGENT, callPart("x", "c1"), callPart("x", "c2")),
            event(AGENT, responsePart("x", "c1"), responsePart("x", null)));

    assertThat(decision.action).isEqualTo(StepResume.Action.CONTINUE);
  }

  @Test
  public void decide_idMatchWithAnotherName_replaysTheCall() {
    StepResume.Decision decision =
        decide(event(AGENT, callPart("x", "c1")), event(AGENT, responsePart("y", "c1")));

    assertThat(decision.action).isEqualTo(StepResume.Action.REPLAY_CALLS);
    assertThat(decision.replayEvent().functionCalls()).containsExactly(call("x", "c1"));
  }

  @Test
  public void decide_nameOnlyResponseBeforeTheCall_doesNotAnswerIt() {
    StepResume.Decision decision =
        decide(
            event(AGENT, callPart("x", null)),
            event(AGENT, responsePart("x", null)),
            event(AGENT, callPart("x", null), callPart("y", "c2")),
            event(AGENT, responsePart("y", "c2")));

    assertThat(decision.action).isEqualTo(StepResume.Action.REPLAY_CALLS);
    assertThat(decision.replayEvent().functionCalls()).containsExactly(call("x", null));
  }

  /**
   * Decides the next step for {@code history} followed by the user's answer to another agent's
   * long-running call.
   */
  private static StepResume.Decision decide(Event... history) {
    Event otherAgentsCall =
        event(OTHER_AGENT, callPart("z", "lro1")).toBuilder()
            .longRunningToolIds(ImmutableSet.of("lro1"))
            .build();
    ImmutableList<Event> events =
        ImmutableList.<Event>builder()
            .add(event(USER, Part.fromText("go")))
            .add(history)
            .add(otherAgentsCall)
            .add(event(USER, responsePart("z", "lro1")))
            .build();
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession("app", USER).blockingGet();
    session.addEvents(events);
    InvocationContext context =
        InvocationContext.builder()
            .invocationId(INVOCATION)
            .agent(LlmAgent.builder().name(AGENT).build())
            .session(session)
            .sessionService(sessionService)
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();
    ImmutableMap<String, BaseTool> tools =
        ImmutableMap.of("x", new BaseTool("x", "x") {}, "y", new BaseTool("y", "y") {});
    return StepResume.decide(context, tools);
  }

  private static Event event(String author, Part... parts) {
    return Event.builder()
        .id(Event.generateEventId())
        .invocationId(INVOCATION)
        .author(author)
        .content(
            Content.builder()
                .role(author.equals(USER) ? USER : "model")
                .parts(ImmutableList.copyOf(parts))
                .build())
        .build();
  }

  private static FunctionCall call(String name, @Nullable String id) {
    FunctionCall.Builder call = FunctionCall.builder().name(name);
    if (id != null) {
      call.id(id);
    }
    return call.build();
  }

  private static Part callPart(String name, @Nullable String id) {
    return Part.builder().functionCall(call(name, id)).build();
  }

  private static Part responsePart(String name, @Nullable String id) {
    FunctionResponse.Builder response =
        FunctionResponse.builder().name(name).response(ImmutableMap.of("ok", true));
    if (id != null) {
      response.id(id);
    }
    return Part.builder().functionResponse(response.build()).build();
  }
}
