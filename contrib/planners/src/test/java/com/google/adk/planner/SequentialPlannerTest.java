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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SequentialPlanner}. */
class SequentialPlannerTest {

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
  void firstAction_runsFirstAgent() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    SequentialPlanner planner = new SequentialPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(1);
    assertThat(runAgents.agents().get(0).name()).isEqualTo("agentA");
  }

  @Test
  void nextAction_runsAgentsInOrder() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");
    SimpleTestAgent agentC = new SimpleTestAgent("agentC");

    SequentialPlanner planner = new SequentialPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), new ConcurrentHashMap<>());
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(((PlannerAction.RunAgents) first).agents().get(0).name()).isEqualTo("agentA");

    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(((PlannerAction.RunAgents) second).agents().get(0).name()).isEqualTo("agentB");

    PlannerAction third = planner.nextAction(context).blockingGet();
    assertThat(((PlannerAction.RunAgents) third).agents().get(0).name()).isEqualTo("agentC");
  }

  @Test
  void nextAction_returnsDoneAfterAll() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    SequentialPlanner planner = new SequentialPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());
    planner.init(context);

    planner.firstAction(context).blockingGet();
    planner.nextAction(context).blockingGet();

    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void firstAction_withNoAgents_returnsDone() {
    SequentialPlanner planner = new SequentialPlanner();
    PlanningContext context = createPlanningContext(ImmutableList.of(), new ConcurrentHashMap<>());
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void init_resetsCursor() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    SequentialPlanner planner = new SequentialPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());
    planner.init(context);

    // Exhaust the planner
    planner.firstAction(context).blockingGet();
    planner.nextAction(context).blockingGet();
    PlannerAction exhausted = planner.nextAction(context).blockingGet();
    assertThat(exhausted).isInstanceOf(PlannerAction.Done.class);

    // Re-init and verify cursor resets
    planner.init(context);
    PlannerAction restarted = planner.firstAction(context).blockingGet();
    assertThat(restarted).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) restarted).agents().get(0).name()).isEqualTo("agentA");
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
