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
import com.google.adk.events.EventActions;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LoopPlanner}. */
class LoopPlannerTest {

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
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    LoopPlanner planner = new LoopPlanner(3);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) action).agents().get(0).name()).isEqualTo("agentA");
  }

  @Test
  void nextAction_cyclesThroughAgents() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    LoopPlanner planner = new LoopPlanner(2);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());
    planner.init(context);

    List<String> executionOrder = new ArrayList<>();

    PlannerAction action = planner.firstAction(context).blockingGet();
    while (action instanceof PlannerAction.RunAgents runAgents) {
      executionOrder.add(runAgents.agents().get(0).name());
      action = planner.nextAction(context).blockingGet();
    }

    // 2 agents x 2 cycles = 4 executions: A, B, A, B
    assertThat(executionOrder).containsExactly("agentA", "agentB", "agentA", "agentB").inOrder();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_stopsAtMaxCycles() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    LoopPlanner planner = new LoopPlanner(1);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());
    planner.init(context);

    // First cycle: runs agentA
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    // Second cycle would exceed maxCycles=1
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_stopsOnEscalateEvent() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    LoopPlanner planner = new LoopPlanner(10);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());
    planner.init(context);

    // First action runs normally
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    // Inject an escalate event into the session
    Event escalateEvent =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId("test-invocation")
            .author("test")
            .actions(EventActions.builder().escalate(true).build())
            .build();
    context.events().add(escalateEvent);

    // Next action should detect escalate and stop
    PlannerAction next = planner.nextAction(context).blockingGet();
    assertThat(next).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void firstAction_withNoAgents_returnsDone() {
    LoopPlanner planner = new LoopPlanner(3);
    PlanningContext context = createPlanningContext(ImmutableList.of(), new ConcurrentHashMap<>());
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_withSingleAgentCycles() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    LoopPlanner planner = new LoopPlanner(3);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());
    planner.init(context);

    int runCount = 0;
    PlannerAction action = planner.firstAction(context).blockingGet();
    while (action instanceof PlannerAction.RunAgents) {
      runCount++;
      action = planner.nextAction(context).blockingGet();
    }

    // 1 agent x 3 cycles = 3 executions
    assertThat(runCount).isEqualTo(3);
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
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
