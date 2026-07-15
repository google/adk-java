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

/** Tests for adaptive replanning in {@link GoalOrientedPlanner} with {@link ReplanPolicy}. */
class ReplanningTest {

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

  // ── A. Core replanning scenarios ────────────────────────────────────────

  @Test
  void replan_partialGroupFailure_recomputesShorterPlan() {
    // A:[]→a, B:[]→b, C:[a,b]→goal. Plan: [[A,B],[C]]
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");
    SimpleTestAgent agentC = new SimpleTestAgent("C");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of("a", "b"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(2));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), state);
    planner.init(context);

    // First action: [A, B] in parallel
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("A", "B");

    // A succeeds, B fails
    context.state().put("a", "value_a");

    // nextAction triggers replan from {"a"} → new plan: [[B],[C]]
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    // Replanned: B needs to run (only B, not A since "a" is already available)
    assertThat(agentNames((PlannerAction.RunAgents) second)).containsExactly("B");

    // B succeeds now
    context.state().put("b", "value_b");

    PlannerAction third = planner.nextAction(context).blockingGet();
    assertThat(third).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) third)).containsExactly("C");

    context.state().put("goal", "done");
    PlannerAction fourth = planner.nextAction(context).blockingGet();
    assertThat(fourth).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void replan_allAgentsInGroupFail_fullReplan() {
    // A:[]→a, B:[a]→goal. Plan: [[A],[B]]
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(2));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("A");

    // A fails — don't put "a" in state
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    // Replan from {}: same plan [[A],[B]], cursor reset → runs A again
    assertThat(agentNames((PlannerAction.RunAgents) second)).containsExactly("A");
  }

  @Test
  void replan_successAfterReplan_completesNormally() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");
    SimpleTestAgent agentC = new SimpleTestAgent("C");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of("a", "b"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(2));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), state);
    planner.init(context);

    // Step 1: [A,B]
    PlannerAction action = planner.firstAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);

    // A succeeds, B fails
    context.state().put("a", "value_a");

    // Step 2: replan → [B]
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");

    // B succeeds now
    context.state().put("b", "value_b");

    // Step 3: [C]
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("C");

    // C succeeds
    context.state().put("goal", "result");

    // Step 4: Done
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.Done.class);
  }

  @Test
  void replan_goalAlreadySatisfied_returnsDone() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, new SimpleTestAgent("B")), state);
    planner.init(context);

    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);

    // A fails, but goal is satisfied by external source
    context.state().put("goal", "external_result");

    // Replan from {"goal"}: search returns empty → Done
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.Done.class);
  }

  // ── B. MaxAttempts and counter behavior ─────────────────────────────────

  @Test
  void replan_maxAttemptsExhausted_returnsDoneWithResult() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(2));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, new SimpleTestAgent("B")), state);
    planner.init(context);

    // Run A
    planner.firstAction(context).blockingGet();
    // A fails → replan 1
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);

    // A fails again → replan 2
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);

    // A fails again → count=2 >= max=2 → exhausted
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
    assertThat(((PlannerAction.DoneWithResult) action).result()).contains("max replan attempts");
    assertThat(((PlannerAction.DoneWithResult) action).result()).contains("exhausted");
  }

  @Test
  void replan_counterResetsAfterSuccessfulGroup() {
    // A:[]→a, B:[a]→b, C:[b]→goal
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");
    SimpleTestAgent agentC = new SimpleTestAgent("C");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "b"),
            new AgentMetadata("C", ImmutableList.of("b"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(2));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), state);
    planner.init(context);

    // Step 1: [A]
    planner.firstAction(context).blockingGet();

    // A fails → replan (count=1)
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("A");

    // A succeeds → count resets to 0
    context.state().put("a", "value");
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");

    // B fails → replan (count=1 NOT 2, because counter was reset)
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    // Should still be allowed since count was reset
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");
  }

  @Test
  void replan_maxAttemptsOne_singleRetry() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");

    List<AgentMetadata> metadata = List.of(new AgentMetadata("A", ImmutableList.of(), "a"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner("a", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    // Run A
    planner.firstAction(context).blockingGet();

    // A fails → replan (count=1, allowed since count < max before increment)
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);

    // A fails again → count=1 >= max=1 → exhausted
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
  }

  @Test
  void replan_maxAttemptsThree_allowsThreeRetries() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");

    List<AgentMetadata> metadata = List.of(new AgentMetadata("A", ImmutableList.of(), "a"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner("a", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(3));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    // Track all RunAgents actions
    List<PlannerAction> actions = new ArrayList<>();
    PlannerAction action = planner.firstAction(context).blockingGet();
    actions.add(action); // original run

    // 3 replans (A fails each time)
    for (int i = 0; i < 3; i++) {
      action = planner.nextAction(context).blockingGet();
      actions.add(action);
    }

    // 4th failure: exhausted
    action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);

    // 4 RunAgents total: 1 original + 3 retries
    long runCount = actions.stream().filter(a -> a instanceof PlannerAction.RunAgents).count();
    assertThat(runCount).isEqualTo(4);
  }

  // ── C. Policy variant tests ─────────────────────────────────────────────

  @Test
  void failStop_missingOutput_returnsDoneWithResult() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.FailStop());
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    // A fails
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
    assertThat(((PlannerAction.DoneWithResult) action).result()).contains("A");
    assertThat(((PlannerAction.DoneWithResult) action).result()).contains("a");
  }

  @Test
  void ignore_missingOutput_proceedsToNextGroup() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Ignore());
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    // A fails — but Ignore proceeds
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");
  }

  @Test
  void policyComparison_sameFail_differentOutcomes() {
    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    ImmutableList<BaseAgent> agents =
        ImmutableList.of(new SimpleTestAgent("A"), new SimpleTestAgent("B"));

    // FailStop → DoneWithResult
    GoalOrientedPlanner failStopPlanner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.FailStop());
    PlanningContext ctx1 = createPlanningContext(agents, new ConcurrentHashMap<>());
    failStopPlanner.init(ctx1);
    failStopPlanner.firstAction(ctx1).blockingGet();
    PlannerAction failStopResult = failStopPlanner.nextAction(ctx1).blockingGet();
    assertThat(failStopResult).isInstanceOf(PlannerAction.DoneWithResult.class);

    // Ignore → RunAgents(B)
    GoalOrientedPlanner ignorePlanner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Ignore());
    PlanningContext ctx2 = createPlanningContext(agents, new ConcurrentHashMap<>());
    ignorePlanner.init(ctx2);
    ignorePlanner.firstAction(ctx2).blockingGet();
    PlannerAction ignoreResult = ignorePlanner.nextAction(ctx2).blockingGet();
    assertThat(ignoreResult).isInstanceOf(PlannerAction.RunAgents.class);

    // Replan → RunAgents(A) (retry)
    GoalOrientedPlanner replanPlanner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    PlanningContext ctx3 = createPlanningContext(agents, new ConcurrentHashMap<>());
    replanPlanner.init(ctx3);
    replanPlanner.firstAction(ctx3).blockingGet();
    PlannerAction replanResult = replanPlanner.nextAction(ctx3).blockingGet();
    assertThat(replanResult).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) replanResult)).containsExactly("A");
  }

  // ── D. Edge cases ──────────────────────────────────────────────────────

  @Test
  void replan_parallelGroup_partialSuccess_usesPartialState() {
    // A:[]→a, B:[]→b, C:[]→c, D:[a,b,c]→goal
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");
    SimpleTestAgent agentC = new SimpleTestAgent("C");
    SimpleTestAgent agentD = new SimpleTestAgent("D");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of(), "c"),
            new AgentMetadata("D", ImmutableList.of("a", "b", "c"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(2));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC, agentD), state);
    planner.init(context);

    // [A,B,C] in parallel
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(((PlannerAction.RunAgents) first).agents()).hasSize(3);

    // A,C succeed; B fails
    context.state().put("a", "va");
    context.state().put("c", "vc");

    // Replan from {"a","c"}: new plan [[B],[D]]
    PlannerAction second = planner.nextAction(context).blockingGet();
    assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) second)).containsExactly("B");
  }

  @Test
  void replan_noMissingOutputs_noReplanTriggered() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of("a"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA, agentB), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    // A succeeds
    context.state().put("a", "value");

    // No replan triggered, proceeds normally
    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");
  }

  @Test
  void replan_firstGroupNoValidation() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");

    List<AgentMetadata> metadata = List.of(new AgentMetadata("A", ImmutableList.of(), "a"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner("a", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context = createPlanningContext(ImmutableList.of(agentA), state);
    planner.init(context);

    // firstAction should return RunAgents without any validation
    PlannerAction first = planner.firstAction(context).blockingGet();
    assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
    assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("A");
  }

  // ── E. Cross-strategy replanning ───────────────────────────────────────

  @Test
  void replan_withDfsStrategy() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");
    SimpleTestAgent agentC = new SimpleTestAgent("C");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of("a", "b"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new DfsSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    context.state().put("a", "va"); // A succeeds, B fails

    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    // DFS replan: only B needed
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");
  }

  @Test
  void replan_withAStarStrategy() {
    SimpleTestAgent agentA = new SimpleTestAgent("A");
    SimpleTestAgent agentB = new SimpleTestAgent("B");
    SimpleTestAgent agentC = new SimpleTestAgent("C");

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("A", ImmutableList.of(), "a"),
            new AgentMetadata("B", ImmutableList.of(), "b"),
            new AgentMetadata("C", ImmutableList.of("a", "b"), "goal"));

    GoalOrientedPlanner planner =
        new GoalOrientedPlanner(
            "goal", metadata, new AStarSearchStrategy(), new ReplanPolicy.Replan(1));
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    PlanningContext context =
        createPlanningContext(ImmutableList.of(agentA, agentB, agentC), state);
    planner.init(context);

    planner.firstAction(context).blockingGet();
    context.state().put("a", "va"); // A succeeds, B fails

    PlannerAction action = planner.nextAction(context).blockingGet();
    assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
    // A* replan: only B needed
    assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("B");
  }

  // ── F. ReplanPolicy validation ─────────────────────────────────────────

  @Test
  void replanPolicy_maxAttemptsZero_throwsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> new ReplanPolicy.Replan(0));
  }

  @Test
  void replanPolicy_maxAttemptsNegative_throwsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> new ReplanPolicy.Replan(-1));
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  private static List<String> agentNames(PlannerAction.RunAgents action) {
    return action.agents().stream().map(BaseAgent::name).toList();
  }

  private static PlanningContext createPlanningContext(
      ImmutableList<BaseAgent> agents, ConcurrentHashMap<String, Object> state) {
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
