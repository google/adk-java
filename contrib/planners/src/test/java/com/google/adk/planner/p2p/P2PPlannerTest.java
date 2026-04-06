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

package com.google.adk.planner.p2p;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.events.Event;
import com.google.adk.planner.goap.AgentMetadata;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link P2PPlanner}. */
class P2PPlannerTest {

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
  void firstAction_activatesAgentsWithSatisfiedInputs() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of("topic"), "findings"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(1);
    assertThat(runAgents.agents().get(0).name()).isEqualTo("agentA");
  }

  @Test
  void firstAction_activatesMultipleAgentsInParallel() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of("topic"), "findingsA"),
            new AgentMetadata("agentB", ImmutableList.of("topic"), "findingsB"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(2);
  }

  @Test
  void firstAction_doesNotActivateAgentsWithUnsatisfiedInputs() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of("topic"), "findings"),
            new AgentMetadata("agentB", ImmutableList.of("topic", "findings"), "hypothesis"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");
    // "findings" is not yet available, so agentB should not activate

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(1);
    assertThat(runAgents.agents().get(0).name()).isEqualTo("agentA");
  }

  @Test
  void nextAction_activatesNewAgentsWhenInputsBecomeAvailable() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of("topic"), "findings"),
            new AgentMetadata("agentB", ImmutableList.of("topic", "findings"), "hypothesis"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    // First action: only agentA can activate
    planner.firstAction(context).blockingGet();

    // Simulate agentA producing "findings" — must update the session state directly
    context.state().put("findings", "some research findings");

    // Next action: agentB should now be able to activate
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents runAgents = (PlannerAction.RunAgents) action;
    assertThat(runAgents.agents()).hasSize(1);
    assertThat(runAgents.agents().get(0).name()).isEqualTo("agentB");
  }

  @Test
  void nextAction_terminatesOnExitCondition() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of("topic"), "score"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");

    P2PPlanner planner =
        new P2PPlanner(
            metadata,
            10,
            (s, count) -> {
              Object score = s.get("score");
              return score instanceof Number && ((Number) score).doubleValue() >= 0.85;
            });

    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    // First action: agentA activates
    planner.firstAction(context).blockingGet();

    // Simulate score being produced above threshold — must update session state directly
    context.state().put("score", 0.9);

    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_terminatesOnMaxInvocations() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of("topic"), "output"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");

    P2PPlanner planner = new P2PPlanner(metadata, 1); // only 1 invocation allowed

    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    // First action consumes the single allowed invocation
    planner.firstAction(context).blockingGet();

    // Next should be Done due to maxInvocations
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_returnsDoneWhenNoAgentsCanActivate() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of("missingInput"), "output"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_doesNotReactivateWhenOutputUnchanged() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of("topic"), "findings"),
            new AgentMetadata("agentB", ImmutableList.of("topic", "findings"), "hypothesis"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    // First action: only agentA can activate
    planner.firstAction(context).blockingGet();

    // Simulate agentA producing "findings"
    context.state().put("findings", "some research findings");

    // Second action: agentB should activate (its inputs are now satisfied)
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) second).agents().get(0).name()).isEqualTo("agentB");

    // Simulate agentB producing "hypothesis"
    context.state().put("hypothesis", "some hypothesis");

    // Third action: no output values changed, so no agent should re-activate
    PlannerAction third = planner.nextAction(context).blockingGet();
    assertThat(third).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void nextAction_reactivatesWhenOutputValueChanges() {
    SimpleTestAgent hypothesisAgent = new SimpleTestAgent("hypothesisAgent");
    SimpleTestAgent criticAgent = new SimpleTestAgent("criticAgent");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata(
                "hypothesisAgent", ImmutableList.of("topic", "critique"), "hypothesis"),
            new AgentMetadata("criticAgent", ImmutableList.of("topic", "hypothesis"), "critique"));

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("topic", "quantum computing");
    state.put("critique", "initial critique");

    P2PPlanner planner = new P2PPlanner(metadata, 10);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(hypothesisAgent, criticAgent), state);
    planner.init(context);

    // First action: hypothesisAgent can activate (topic + critique present)
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) first).agents().get(0).name())
        .isEqualTo("hypothesisAgent");

    // Simulate hypothesisAgent producing "hypothesis"
    context.state().put("hypothesis", "hypothesis v1");

    // Second action: criticAgent should activate (hypothesis is new)
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) second).agents().get(0).name()).isEqualTo("criticAgent");

    // Simulate criticAgent OVERWRITING "critique" with a new value
    context.state().put("critique", "revised critique based on v1");

    // Third action: hypothesisAgent should re-activate because "critique" value CHANGED
    PlannerAction third = planner.nextAction(context).blockingGet();
    assertThat(third).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) third).agents().get(0).name())
        .isEqualTo("hypothesisAgent");
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
