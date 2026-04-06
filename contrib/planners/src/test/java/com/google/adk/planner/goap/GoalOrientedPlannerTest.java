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

  @Test
  void dependencyGraphSearch_detectsCycle() {
    // A depends on B's output, B depends on A's output → cycle
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of("outputB"), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> DependencyGraphSearch.search(graph, List.of(), "outputA"));
    assertThat(ex.getMessage()).contains("Circular dependency");
  }

  @Test
  void dependencyGraphSearch_goalAlreadyInPreconditions() {
    List<AgentMetadata> metadata =
        List.of(new AgentMetadata("agentA", ImmutableList.of(), "outputA"));

    GoalOrientedSearchGraph graph = new GoalOrientedSearchGraph(metadata);
    ImmutableList<String> path = DependencyGraphSearch.search(graph, List.of("outputA"), "outputA");

    assertThat(path).isEmpty();
  }

  @Test
  void goalOrientedSearchGraph_rejectsDuplicateOutputKeys() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "sameOutput"),
            new AgentMetadata("agentB", ImmutableList.of(), "sameOutput"));

    assertThrows(IllegalArgumentException.class, () -> new GoalOrientedSearchGraph(metadata));
  }

  @Test
  void planningContext_findAgentThrowsOnUnknownName() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA), new ConcurrentHashMap<>());

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> context.findAgent("nonexistent"));
    assertThat(ex.getMessage()).contains("nonexistent");
  }

  @Test
  void goalOrientedPlanner_groupsIndependentAgentsInParallel() {
    // personExtractor and signExtractor are independent → should be in same RunAgents group
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

    // First action: both extractors in parallel (same group)
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents firstRun = (PlannerAction.RunAgents) first;
    assertThat(firstRun.agents()).hasSize(2);
    List<String> firstNames = firstRun.agents().stream().map(BaseAgent::name).toList();
    assertThat(firstNames).containsExactly("personExtractor", "signExtractor");

    // Second action: horoscopeGenerator alone
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents secondRun = (PlannerAction.RunAgents) second;
    assertThat(secondRun.agents()).hasSize(1);
    assertThat(secondRun.agents().get(0).name()).isEqualTo("horoscopeGenerator");

    // Third action: writer alone
    PlannerAction third = planner.nextAction(context).blockingGet();
    assertThat(third).isInstanceOf(PlannerAction.RunAgents.class);
    PlannerAction.RunAgents thirdRun = (PlannerAction.RunAgents) third;
    assertThat(thirdRun.agents()).hasSize(1);
    assertThat(thirdRun.agents().get(0).name()).isEqualTo("writer");

    // Fourth action: Done
    PlannerAction fourth = planner.nextAction(context).blockingGet();
    assertThat(fourth).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void goalOrientedPlanner_validationEnabled_outputPresent_proceeds() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputB", metadata, true);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    // Simulate agentA producing its output
    context.state().put("outputA", "some value");

    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) second).agents().get(0).name()).isEqualTo("agentB");
  }

  @Test
  void goalOrientedPlanner_validationEnabled_outputMissing_stops() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputB", metadata, true);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    // Do NOT put "outputA" into state — simulating agent failure

    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.DoneWithResult.class);
    PlannerAction.DoneWithResult result = (PlannerAction.DoneWithResult) second;
    assertThat(result.result()).contains("agentA");
    assertThat(result.result()).contains("outputA");
  }

  @Test
  void goalOrientedPlanner_validationDisabled_outputMissing_proceeds() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    // Default constructor — validateOutputs defaults to false
    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputB", metadata);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    // Do NOT put "outputA" into state

    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) second).agents().get(0).name()).isEqualTo("agentB");
  }

  @Test
  void goalOrientedPlanner_validationEnabled_parallelGroupPartialFailure() {
    SimpleTestAgent personExtractor = new SimpleTestAgent("personExtractor");
    SimpleTestAgent signExtractor = new SimpleTestAgent("signExtractor");
    SimpleTestAgent horoscopeGenerator = new SimpleTestAgent("horoscopeGenerator");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("personExtractor", ImmutableList.of("prompt"), "person"),
            new AgentMetadata("signExtractor", ImmutableList.of("prompt"), "sign"),
            new AgentMetadata(
                "horoscopeGenerator", ImmutableList.of("person", "sign"), "horoscope"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("horoscope", metadata, true);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("prompt", "My name is Mario and my zodiac sign is pisces");

    PlanningContext context =
        createPlanningContext(
            ImmutableList.of(personExtractor, signExtractor, horoscopeGenerator), state);
    planner.init(context);

    // First action: [personExtractor, signExtractor] in parallel
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) first).agents()).hasSize(2);

    // Simulate: personExtractor succeeds, signExtractor fails
    context.state().put("person", "Mario");
    // "sign" is NOT produced

    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.DoneWithResult.class);
    PlannerAction.DoneWithResult result = (PlannerAction.DoneWithResult) second;
    assertThat(result.result()).contains("signExtractor");
    assertThat(result.result()).contains("sign");
    assertThat(result.result()).doesNotContain("personExtractor");
  }

  // ── Backward compatibility and new constructor tests ────────────────────

  @Test
  void backwardCompat_defaultConstructor_usesDfsAndIgnore() {
    // Default constructor: DFS + Ignore. A fails, next group still runs.
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputB", metadata);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    // agentA fails — Ignore policy proceeds
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(((PlannerAction.RunAgents) action).agents().get(0).name()).isEqualTo("agentB");
  }

  @Test
  void backwardCompat_validateOutputsTrue_matchesFailStop() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputB", metadata, true);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    // agentA fails — FailStop stops
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
    assertThat(((PlannerAction.DoneWithResult) action).result()).contains("agentA");
  }

  @Test
  void backwardCompat_validateOutputsFalse_matchesIgnore() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedPlanner planner = new GoalOrientedPlanner("outputB", metadata, false);
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
  }

  @Test
  void newConstructor_withAStarStrategy_correctExecution() {
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

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "writeup", metadata, new AStarSearchStrategy(), new ReplanPolicy.Ignore());
    ImmutableList<BaseAgent> agents =
        ImmutableList.of(personExtractor, signExtractor, horoscopeGenerator, writer);

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("prompt", "My name is Mario and my zodiac sign is pisces");

    PlanningContext context = createPlanningContext(agents, state);
    planner.init(context);

    // Same groups as DFS: [[personExtractor,signExtractor],[horoscopeGenerator],[writer]]
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    List<String> firstNames =
        ((PlannerAction.RunAgents) first).agents().stream().map(BaseAgent::name).toList();
    assertThat(firstNames).containsExactly("personExtractor", "signExtractor");
  }

  @Test
  void newConstructor_withReplanPolicy_replansOnFailure() {
    SimpleTestAgent agentA = new SimpleTestAgent("agentA");
    SimpleTestAgent agentB = new SimpleTestAgent("agentB");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("agentA", ImmutableList.of(), "outputA"),
            new AgentMetadata("agentB", ImmutableList.of("outputA"), "outputB"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "outputB", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    // agentA fails — Replan retries instead of stopping
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    // Replanned: agentA runs again (not DoneWithResult)
    assertThat(((PlannerAction.RunAgents) action).agents().get(0).name()).isEqualTo("agentA");
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
