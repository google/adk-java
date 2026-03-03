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

package com.google.adk.planner.goap;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GoalOrientedPlanner}, {@link DependencyGraphSearch}, and related classes.
 */
class GoalOrientedPlannerTest {

  /** Simple test agent for planner tests. */
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
  void dependencyGraphSearch_resolvesLinearChain() {
    // A → B → C (linear chain)
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputB"), "outputC"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<String> path = DependencyGraphSearch.search(graph, List.of(), "outputC");

    assertThat(path).containsExactly("agentA", "agentB", "agentC").inOrder();
  }

  @Test
  void dependencyGraphSearch_resolvesMultipleInputs() {
    // A and B have no deps; C needs both A and B outputs
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of(), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA", "outputB"), "outputC"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<String> path = DependencyGraphSearch.search(graph, List.of(), "outputC");

    assertThat(path).containsExactly("agentA", "agentB", "agentC");
    // C must come after both A and B
    assertThat(path.indexOf("agentC")).isGreaterThan(path.indexOf("agentA"));
    assertThat(path.indexOf("agentC")).isGreaterThan(path.indexOf("agentB"));
  }

  @Test
  void dependencyGraphSearch_handlesDiamondDependencies() {
    // A → B, A → C, B+C → D (diamond)
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA"), "outputC"),
            new AgentMetadata("agentD", ImmutableList.of("outputB", "outputC"), "outputD"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<String> path = DependencyGraphSearch.search(graph, List.of(), "outputD");

    assertThat(path).containsExactly("agentA", "agentB", "agentC", "agentD");
    assertThat(path.indexOf("agentA")).isLessThan(path.indexOf("agentB"));
    assertThat(path.indexOf("agentA")).isLessThan(path.indexOf("agentC"));
    assertThat(path.indexOf("agentD")).isGreaterThan(path.indexOf("agentB"));
    assertThat(path.indexOf("agentD")).isGreaterThan(path.indexOf("agentC"));
  }

  @Test
  void dependencyGraphSearch_skipsSatisfiedPreconditions() {
    // A and B produce outputs; C needs both.
    // But outputA is already available as a precondition.
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of(), "outputB"),
            new AgentMetadata("agentC", ImmutableList.of("outputA", "outputB"), "outputC"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<String> path = DependencyGraphSearch.search(graph, List.of("outputA"), "outputC");

    // agentA should be skipped since outputA is already available
    assertThat(path).containsExactly("agentB", "agentC").inOrder();
  }

  @Test
  void dependencyGraphSearch_throwsOnUnresolvableDependency() {
    // agentB needs "missing" which no agent produces
    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentB", ImmutableList.of("missing"), "outputB"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);

    assertThrows(
        IllegalStateException.class,
        () -> DependencyGraphSearch.search(graph, List.of(), "outputB"));
  }

  @Test
  void goalOrientedPlanner_producesCorrectExecutionPath() {
    // Horoscope example: person + sign → horoscope → writeup
    SimpleTestAgent personExtractor = new SimpleTestAgent("personExtractor");
    SimpleTestAgent signExtractor = new SimpleTestAgent("signExtractor");
    SimpleTestAgent horoscopeGenerator = new SimpleTestAgent("horoscopeGenerator");
    SimpleTestAgent writer = new SimpleTestAgent("writer");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("personExtractor", ImmutableList.of("prompt"), "person"),
            new AgentMetadata("signExtractor", ImmutableList.of("prompt"), "sign"),
            new AgentMetadata(
                "horoscopeGenerator", ImmutableList.of("person", "sign"), "horoscope"),
            new AgentMetadata("writer", ImmutableList.of("person", "horoscope"), "writeup"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("writeup", metadata);
    ImmutableList<BaseAgent> agents =
        ImmutableList.of(personExtractor, signExtractor, horoscopeGenerator, writer);

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("prompt", "My name is Mario and my zodiac sign is pisces");

    PlanningContext context = createPlanningContext(agents, state);
    planner.init(context);

    // Collect the full execution path
    List<String> executionOrder = new ArrayList<>();
    PlannerAction action = planner.firstAction(context).blockingGet();
    while (action instanceof PlannerAction.RunAgents runAgents) {
      for (BaseAgent agent : runAgents.agents()) {
        executionOrder.add(agent.name());
      }
      action = planner.nextAction(context).blockingGet();
    }

    assertThat(executionOrder)
        .containsExactly("personExtractor", "signExtractor", "horoscopeGenerator", "writer");
    // horoscopeGenerator must come after both extractors
    assertThat(executionOrder.indexOf("horoscopeGenerator"))
        .isGreaterThan(executionOrder.indexOf("personExtractor"));
    assertThat(executionOrder.indexOf("horoscopeGenerator"))
        .isGreaterThan(executionOrder.indexOf("signExtractor"));
    // writer must come after horoscopeGenerator
    assertThat(executionOrder.indexOf("writer"))
        .isGreaterThan(executionOrder.indexOf("horoscopeGenerator"));
  }

  @Test
  void goalOrientedPlanner_returnsDoneWhenPathComplete() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");

    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of(), "outputA"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputA", metadata);
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.Done.class);
  }

  private static PlanningContext createPlanningContext(
      ImmutableList<BaseAgent> agents, ConcurrentHashMap<String, Object> state) {
    // Create a minimal InvocationContext for testing
    com.google.adk.sessions.InMemorySessionService sessionService =
        new com.google.adk.sessions.InMemorySessionService();
    com.google.adk.sessions.Session session =
        sessionService.createSession("test-app", "test-user").blockingGet();
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
