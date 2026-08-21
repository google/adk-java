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

package com.google.adk.planner;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link SupervisorPlanner}. */
class SupervisorPlannerTest {

  private static final class SimpleTestAgent extends BaseAgent {
    SimpleTestAgent(String name) {
      super(name, "test agent " + name, ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
      return Flowable.empty();
    }
  }

  @Test
  void firstAction_parsesAgentNameFromLlm() {
    BaseLlm mockLlm = mock(BaseLlm.class);
    LlmResponse response = createTextResponse("agentA");
    when(mockLlm.generateContent(any(), eq(false))).thenReturn(Flowable.just(response));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm, "You are a supervisor.");
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(1);
    assertThat(runAgents.agents().get(0).name()).isEqualTo("agentA");
  }

  @Test
  void firstAction_parsesDoneFromLlm() {
    BaseLlm mockLlm = mock(BaseLlm.class);
    LlmResponse response = createTextResponse("DONE");
    when(mockLlm.generateContent(any(), eq(false))).thenReturn(Flowable.just(response));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void firstAction_parsesDoneWithResultFromLlm() {
    BaseLlm mockLlm = mock(BaseLlm.class);
    LlmResponse response = createTextResponse("DONE: Task completed successfully");
    when(mockLlm.generateContent(any(), eq(false))).thenReturn(Flowable.just(response));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
    assertThat(((PlannerAction.DoneWithResult) action).result())
        .isEqualTo("Task completed successfully");
  }

  @Test
  void nextAction_fallsToDoneOnUnrecognizedAgent() {
    BaseLlm mockLlm = mock(BaseLlm.class);
    LlmResponse response = createTextResponse("unknownAgent");
    when(mockLlm.generateContent(any(), eq(false))).thenReturn(Flowable.just(response));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void firstAction_fallsToDoneOnLlmError() {
    BaseLlm mockLlm = mock(BaseLlm.class);
    when(mockLlm.generateContent(any(), eq(false)))
        .thenReturn(Flowable.error(new RuntimeException("LLM error")));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void buildPrompt_includesDecisionHistory() {
    BaseLlm mockLlm = mock(BaseLlm.class);

    LlmResponse response1 = createTextResponse("agentA");
    LlmResponse response2 = createTextResponse("DONE");
    when(mockLlm.generateContent(any(), eq(false)))
        .thenReturn(Flowable.just(response1))
        .thenReturn(Flowable.just(response2));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm, "You are a supervisor.", 2);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    planner.firstAction(context).blockingGet();
    planner.nextAction(context).blockingGet();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockLlm, times(2)).generateContent(requestCaptor.capture(), eq(false));

    LlmRequest secondRequest = requestCaptor.getAllValues().get(1);
    String promptText = secondRequest.contents().get(0).parts().get().get(0).text().get();
    assertThat(promptText).contains("Previous decisions");
    assertThat(promptText).contains("Run: agentA");
  }

  @Test
  void decisionHistory_accumulatesAcrossCalls() {
    BaseLlm mockLlm = mock(BaseLlm.class);

    LlmResponse response1 = createTextResponse("agentA");
    LlmResponse response2 = createTextResponse("agentB");
    LlmResponse response3 = createTextResponse("DONE");
    when(mockLlm.generateContent(any(), eq(false)))
        .thenReturn(Flowable.just(response1))
        .thenReturn(Flowable.just(response2))
        .thenReturn(Flowable.just(response3));

    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    SupervisorPlanner planner = new SupervisorPlanner(mockLlm, "You are a supervisor.");
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());

    planner.firstAction(context).blockingGet();
    planner.nextAction(context).blockingGet();
    planner.nextAction(context).blockingGet();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockLlm, times(3)).generateContent(requestCaptor.capture(), eq(false));

    LlmRequest thirdRequest = requestCaptor.getAllValues().get(2);
    String promptText = thirdRequest.contents().get(0).parts().get().get(0).text().get();
    assertThat(promptText).contains("1. Run: agentA");
    assertThat(promptText).contains("2. Run: agentB");
  }

  private static LlmResponse createTextResponse(String text) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private static PlanningContext createPlanningContext(
      ImmutableList<BaseAgent> agents, ConcurrentHashMap<String, Object> state) {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession("test-app", "test-user").blockingGet();
    session.state().putAll(state);

    BaseAgent rootAgent = agents.isEmpty() ? new SimpleTestAgent("root") : agents.get(0);
    InvocationContext invocationContext =
        InvocationContext.builder()
            .sessionService(sessionService)
            .invocationId("test-invocation")
            .agent(rootAgent)
            .session(session)
            .build();

    return new PlanningContext(invocationContext, agents);
  }
}
