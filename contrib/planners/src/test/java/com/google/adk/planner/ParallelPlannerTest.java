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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ParallelPlanner}. */
class ParallelPlannerTest {

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
  void firstAction_runsAllAgentsInParallel() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");
    SimpleTestAgent agentC = new SimpleTestAgent("agentC");

    ParallelPlanner planner = new ParallelPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(3);
    List<String> names = runAgents.agents().stream().map(BaseAgent::name).toList();
    assertThat(names).containsExactly("agentA", "agentB", "agentC");
  }

  @Test
  void nextAction_returnsDone() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    ParallelPlanner planner = new ParallelPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB), new ConcurrentHashMap<>());

    planner.firstAction(context).blockingGet();

    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void firstAction_withEmptyAgents_returnsDone() {
    ParallelPlanner planner = new ParallelPlanner();
    PlanningContext context = createPlanningContext(ImmutableList.of(), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void firstAction_withSingleAgent_runsIt() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    ParallelPlanner planner = new ParallelPlanner();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(1);
    assertThat(runAgents.agents().get(0).name()).isEqualTo("agentA");
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
