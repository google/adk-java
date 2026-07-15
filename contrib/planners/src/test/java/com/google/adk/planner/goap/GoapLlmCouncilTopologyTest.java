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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GoalOrientedPlanner} against a realistic council-like 9-agent pipeline. Models the
 * LLM Council's dependency graph using ADK's string-based {@link AgentMetadata}.
 *
 * <p>Council topology:
 *
 * <pre>
 *   initial_response → peer_ranking ──────────→ final_synthesis ─┐
 *                   → agreement_analysis ──→ aggregate_agreements │→ council_summary
 *                   → disagreement_analysis → aggregate_disagreements │
 *                      peer_ranking → aggregate_rankings ─────────┘
 * </pre>
 */
class GoapLlmCouncilTopologyTest {

  // ── Council-like metadata ──────────────────────────────────────────────

  static final List<AgentMetadata> COUNCIL_METADATA =
      List.of(
          new AgentMetadata("initial_response", ImmutableList.of(), "individual_responses"),
          new AgentMetadata(
              "peer_ranking", ImmutableList.of("individual_responses"), "peer_rankings"),
          new AgentMetadata(
              "agreement_analysis", ImmutableList.of("individual_responses"), "agreement_analyses"),
          new AgentMetadata(
              "disagreement_analysis",
              ImmutableList.of("individual_responses"),
              "disagreement_analyses"),
          new AgentMetadata(
              "final_synthesis",
              ImmutableList.of("individual_responses", "peer_rankings"),
              "final_synthesis"),
          new AgentMetadata(
              "aggregate_rankings", ImmutableList.of("peer_rankings"), "aggregate_rankings"),
          new AgentMetadata(
              "aggregate_agreements",
              ImmutableList.of("agreement_analyses"),
              "aggregate_agreements"),
          new AgentMetadata(
              "aggregate_disagreements",
              ImmutableList.of("disagreement_analyses"),
              "aggregate_disagreements"),
          new AgentMetadata(
              "council_summary",
              ImmutableList.of(
                  "final_synthesis",
                  "aggregate_rankings",
                  "aggregate_agreements",
                  "aggregate_disagreements"),
              "council_summary"));

  static final ImmutableList<String> ALL_AGENT_NAMES =
      ImmutableList.of(
          "initial_response",
          "peer_ranking",
          "agreement_analysis",
          "disagreement_analysis",
          "final_synthesis",
          "aggregate_rankings",
          "aggregate_agreements",
          "aggregate_disagreements",
          "council_summary");

  // ── Test infrastructure ────────────────────────────────────────────────

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

  private static ImmutableList<BaseAgent> councilAgents() {
    return ALL_AGENT_NAMES.stream()
        .map(SimpleTestAgent::new)
        .collect(ImmutableList.toImmutableList());
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

  private static List<String> agentNames(PlannerAction.RunAgents action) {
    return action.agents().stream().map(BaseAgent::name).toList();
  }

  /** Collects all execution groups by walking firstAction/nextAction until Done. */
  private static List<List<String>> collectAllGroups(
      GoalOrientedPlanner planner, PlanningContext context) {
    List<List<String>> groups = new ArrayList<>();
    PlannerAction action = planner.firstAction(context).blockingGet();
    while (action instanceof PlannerAction.RunAgents run) {
      groups.add(agentNames(run));
      action = planner.nextAction(context).blockingGet();
    }
    return groups;
  }

  // ── Part 1: GoapPlanningBehavior ───────────────────────────────────────────

  @Nested
  class GoapPlanningBehavior {

    @Test
    void fullCouncilProducesFourGroups() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> groups = collectAllGroups(planner, context);

      assertThat(groups).hasSize(4);
      assertThat(groups.get(0)).hasSize(1);
      assertThat(groups.get(1)).hasSize(3);
      assertThat(groups.get(2)).hasSize(4);
      assertThat(groups.get(3)).hasSize(1);
    }

    @Test
    void synthesisGoalProducesThreeGroups() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("final_synthesis", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> groups = collectAllGroups(planner, context);

      assertThat(groups).hasSize(3);
      assertThat(groups.get(0)).containsExactly("initial_response");
      assertThat(groups.get(1)).containsExactly("peer_ranking");
      assertThat(groups.get(2)).containsExactly("final_synthesis");
    }

    @Test
    void rankingsGoalProducesThreeGroups() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("aggregate_rankings", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> groups = collectAllGroups(planner, context);

      assertThat(groups).hasSize(3);
      assertThat(groups.get(0)).containsExactly("initial_response");
      assertThat(groups.get(1)).containsExactly("peer_ranking");
      assertThat(groups.get(2)).containsExactly("aggregate_rankings");
    }

    @Test
    void agreementGoalExcludesDisagreementPipeline() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner("aggregate_agreements", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> groups = collectAllGroups(planner, context);

      assertThat(groups).hasSize(3);
      List<String> allAgents = groups.stream().flatMap(List::stream).toList();
      assertThat(allAgents).doesNotContain("disagreement_analysis");
      assertThat(allAgents).doesNotContain("aggregate_disagreements");
      assertThat(allAgents).doesNotContain("peer_ranking");
      assertThat(allAgents).contains("agreement_analysis");
      assertThat(allAgents).contains("aggregate_agreements");
    }

    @Test
    void fullCouncilParallelGrouping() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();
      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("initial_response");

      PlannerAction second = planner.nextAction(context).blockingGet();
      assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) second))
          .containsExactly("peer_ranking", "agreement_analysis", "disagreement_analysis");

      PlannerAction third = planner.nextAction(context).blockingGet();
      assertThat(third).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) third))
          .containsExactly(
              "final_synthesis",
              "aggregate_rankings",
              "aggregate_agreements",
              "aggregate_disagreements");

      PlannerAction fourth = planner.nextAction(context).blockingGet();
      assertThat(fourth).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) fourth)).containsExactly("council_summary");
    }

    @Test
    void fullCouncilCompletesWithDone() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Walk all 4 groups
      PlannerAction action = planner.firstAction(context).blockingGet();
      for (int i = 0; i < 3; i++) {
        assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
        action = planner.nextAction(context).blockingGet();
      }
      // 4th group
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);

      // Final: Done
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void aStarAndDfsProduceEquivalentGroups() {
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

      GoalOrientedPlanner dfsPlanner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Ignore());
      PlanningContext dfsCtx = createPlanningContext(councilAgents(), state);
      dfsPlanner.init(dfsCtx);
      List<List<String>> dfsGroups = collectAllGroups(dfsPlanner, dfsCtx);

      GoalOrientedPlanner aStarPlanner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new AStarSearchStrategy(),
              new ReplanPolicy.Ignore());
      PlanningContext aStarCtx = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      aStarPlanner.init(aStarCtx);
      List<List<String>> aStarGroups = collectAllGroups(aStarPlanner, aStarCtx);

      assertThat(aStarGroups).hasSize(dfsGroups.size());
      for (int i = 0; i < dfsGroups.size(); i++) {
        assertThat(aStarGroups.get(i)).containsExactlyElementsIn(dfsGroups.get(i));
      }
    }

    @Test
    void preconditionSkipsInitialResponse() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("individual_responses", "already available");

      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      List<List<String>> groups = collectAllGroups(planner, context);

      // initial_response should be skipped
      assertThat(groups).hasSize(3);
      List<String> allAgents = groups.stream().flatMap(List::stream).toList();
      assertThat(allAgents).doesNotContain("initial_response");
    }

    @Test
    void goalAlreadySatisfied_returnsEmptyPlan() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("council_summary", "already done");

      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      PlannerAction action = planner.firstAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void defaultConstructorUsesIgnore() {
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response
      planner.firstAction(context).blockingGet();
      // Don't put "individual_responses" → agent failed

      // Ignore policy: proceeds to next group regardless
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) action)).hasSize(3);
    }
  }

  // ── Part 2: AdaptiveGoapReplanning ─────────────────────────────────────────

  @Nested
  class AdaptiveGoapReplanning {

    @Test
    void initialResponseFails_replanRetries() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response
      planner.firstAction(context).blockingGet();
      // initial_response fails — don't add output

      // Replan: from {} → same plan, runs initial_response again
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) action)).containsExactly("initial_response");
    }

    @Test
    void parallelGroupPartialFailure_replanWithPartialState() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: [peer_ranking, agreement_analysis, disagreement_analysis]
      PlannerAction group2 = planner.nextAction(context).blockingGet();
      assertThat(group2).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) group2)).hasSize(3);

      // peer_ranking + agreement succeed, disagreement fails
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");

      // Replan from {individual_responses, peer_rankings, agreement_analyses}:
      // All agents whose preconditions are now satisfied run in one group
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> replanAgents = agentNames((PlannerAction.RunAgents) replan);
      assertThat(replanAgents).contains("disagreement_analysis");
      assertThat(replanAgents).doesNotContain("initial_response");
      assertThat(replanAgents).doesNotContain("peer_ranking");
    }

    @Test
    void aggregationFails_replanOnlyAggregation() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: all succeed
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Group 3: [final_synthesis, aggregate_rankings, aggregate_agreements,
      //           aggregate_disagreements]
      PlannerAction group3 = planner.nextAction(context).blockingGet();
      assertThat(group3).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) group3)).hasSize(4);

      // All succeed except aggregate_disagreements
      context.state().put("final_synthesis", "done");
      context.state().put("aggregate_rankings", "done");
      context.state().put("aggregate_agreements", "done");

      // Replan: only aggregate_disagreements + council_summary remain
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) replan))
          .containsExactly("aggregate_disagreements");
    }

    @Test
    void multipleRetriesWithProgressiveState() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(3));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: all 3 agents fail
      planner.nextAction(context).blockingGet();

      // Replan 1: runs same 3 agents again
      PlannerAction replan1 = planner.nextAction(context).blockingGet();
      assertThat(replan1).isInstanceOf(PlannerAction.RunAgents.class);

      // Only peer_ranking succeeds this time
      context.state().put("peer_rankings", "done");

      // Replan 2: from {individual_responses, peer_rankings} →
      //           remaining: agreement_analysis, disagreement_analysis
      PlannerAction replan2 = planner.nextAction(context).blockingGet();
      assertThat(replan2).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> replan2Agents = agentNames((PlannerAction.RunAgents) replan2);
      assertThat(replan2Agents).containsAtLeast("agreement_analysis", "disagreement_analysis");
    }

    @Test
    void maxAttemptsExhaustedInCouncilPipeline() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // initial_response runs, fails repeatedly
      planner.firstAction(context).blockingGet();

      // Replan 1: retry
      PlannerAction r1 = planner.nextAction(context).blockingGet();
      assertThat(r1).isInstanceOf(PlannerAction.RunAgents.class);

      // Replan 2: retry
      PlannerAction r2 = planner.nextAction(context).blockingGet();
      assertThat(r2).isInstanceOf(PlannerAction.RunAgents.class);

      // Replan 3: exhausted (count=2 >= max=2)
      PlannerAction exhausted = planner.nextAction(context).blockingGet();
      assertThat(exhausted).isInstanceOf(PlannerAction.DoneWithResult.class);
      assertThat(((PlannerAction.DoneWithResult) exhausted).result())
          .contains("max replan attempts");
    }

    @Test
    void counterResetsAfterSuccessfulCouncilStage() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response fails
      planner.firstAction(context).blockingGet();

      // Replan (count=1): retry
      PlannerAction replan1 = planner.nextAction(context).blockingGet();
      assertThat(replan1).isInstanceOf(PlannerAction.RunAgents.class);

      // initial_response succeeds → counter resets
      context.state().put("individual_responses", "done");

      // Group 2 proceeds
      PlannerAction group2 = planner.nextAction(context).blockingGet();
      assertThat(group2).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) group2)).hasSize(3);

      // Group 2 fails → new replan allowed (count was reset to 0)
      PlannerAction replan2 = planner.nextAction(context).blockingGet();
      assertThat(replan2).isInstanceOf(PlannerAction.RunAgents.class);
    }

    @Test
    void replanWithDfsStrategy() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(1));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1 succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: partial failure (only peer_ranking succeeds)
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");

      // DFS replan from {individual_responses, peer_rankings}
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> agents = agentNames((PlannerAction.RunAgents) replan);
      assertThat(agents).containsAtLeast("agreement_analysis", "disagreement_analysis");
      assertThat(agents).doesNotContain("initial_response");
      assertThat(agents).doesNotContain("peer_ranking");
    }

    @Test
    void replanWithAStarStrategy() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new AStarSearchStrategy(),
              new ReplanPolicy.Replan(1));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1 succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: partial failure (only peer_ranking succeeds)
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");

      // A* replan from {individual_responses, peer_rankings}
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> agents = agentNames((PlannerAction.RunAgents) replan);
      assertThat(agents).containsAtLeast("agreement_analysis", "disagreement_analysis");
      assertThat(agents).doesNotContain("initial_response");
      assertThat(agents).doesNotContain("peer_ranking");
    }

    @Test
    void goalExternallySatisfiedDuringReplan() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(1));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response fails
      planner.firstAction(context).blockingGet();

      // Goal satisfied externally
      context.state().put("council_summary", "external_result");

      // Replan: goal is in preconditions → empty plan → Done
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void failStopOnCouncilPipeline() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.FailStop());
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1 succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: all 3 agents fail
      planner.nextAction(context).blockingGet();

      // FailStop: DoneWithResult
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
      String result = ((PlannerAction.DoneWithResult) action).result();
      assertThat(result).contains("peer_ranking");
      assertThat(result).contains("peer_rankings");
    }

    @Test
    void fullCouncilSuccessfulReplanThenCompletion() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1: initial_response succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: only peer_ranking succeeds
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");

      // Replan: agreement + disagreement needed
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);

      // Both succeed now
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Next group: [final_synthesis, aggregate_rankings, aggregate_agreements,
      //              aggregate_disagreements]
      PlannerAction group3 = planner.nextAction(context).blockingGet();
      assertThat(group3).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) group3)).hasSize(4);

      // All succeed
      context.state().put("final_synthesis", "done");
      context.state().put("aggregate_rankings", "done");
      context.state().put("aggregate_agreements", "done");
      context.state().put("aggregate_disagreements", "done");

      // Next: council_summary
      PlannerAction summary = planner.nextAction(context).blockingGet();
      assertThat(summary).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) summary)).containsExactly("council_summary");

      // council_summary succeeds
      context.state().put("council_summary", "done");

      // Final: Done
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }
  }

  // ── Part 3: EdgeCases ──────────────────────────────────────────────────

  @Nested
  class EdgeCases {

    @Test
    void allParallelAgentsFail_fullGroupReplan() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(1));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1 succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: [peer_ranking, agreement_analysis, disagreement_analysis]
      planner.nextAction(context).blockingGet();
      // All 3 fail — no state added

      // Replan from {individual_responses}: same 3 agents needed again
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) replan))
          .containsExactly("peer_ranking", "agreement_analysis", "disagreement_analysis");
    }

    @Test
    void policyComparison_councilFailure() {
      ImmutableList<BaseAgent> agents = councilAgents();

      // Same scenario: group 1 succeeds, group 2 all fail

      // FailStop → DoneWithResult
      GoalOrientedPlanner failStopPlanner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.FailStop());
      ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
      PlanningContext ctx1 = createPlanningContext(agents, state1);
      failStopPlanner.init(ctx1);
      failStopPlanner.firstAction(ctx1).blockingGet();
      ctx1.state().put("individual_responses", "done");
      failStopPlanner.nextAction(ctx1).blockingGet();
      PlannerAction failStopResult = failStopPlanner.nextAction(ctx1).blockingGet();
      assertThat(failStopResult).isInstanceOf(PlannerAction.DoneWithResult.class);

      // Ignore → RunAgents (proceeds to group 3)
      GoalOrientedPlanner ignorePlanner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Ignore());
      ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
      PlanningContext ctx2 = createPlanningContext(agents, state2);
      ignorePlanner.init(ctx2);
      ignorePlanner.firstAction(ctx2).blockingGet();
      ctx2.state().put("individual_responses", "done");
      ignorePlanner.nextAction(ctx2).blockingGet();
      PlannerAction ignoreResult = ignorePlanner.nextAction(ctx2).blockingGet();
      assertThat(ignoreResult).isInstanceOf(PlannerAction.RunAgents.class);
      // Proceeds to group 3 (4 agents)
      assertThat(agentNames((PlannerAction.RunAgents) ignoreResult)).hasSize(4);

      // Replan → RunAgents (retries failed agents)
      GoalOrientedPlanner replanPlanner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(1));
      ConcurrentHashMap<String, Object> state3 = new ConcurrentHashMap<>();
      PlanningContext ctx3 = createPlanningContext(agents, state3);
      replanPlanner.init(ctx3);
      replanPlanner.firstAction(ctx3).blockingGet();
      ctx3.state().put("individual_responses", "done");
      replanPlanner.nextAction(ctx3).blockingGet();
      PlannerAction replanResult = replanPlanner.nextAction(ctx3).blockingGet();
      assertThat(replanResult).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) replanResult))
          .containsExactly("peer_ranking", "agreement_analysis", "disagreement_analysis");
    }

    @Test
    void largeParallelGroupMultipleFailures() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Groups 1-2 succeed
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Group 3 (4 agents): final_synthesis + aggregate_rankings succeed,
      //   aggregate_agreements + aggregate_disagreements fail
      planner.nextAction(context).blockingGet();
      context.state().put("final_synthesis", "done");
      context.state().put("aggregate_rankings", "done");

      // Replan from state with 2 of 4 outputs missing
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> replanAgents = agentNames((PlannerAction.RunAgents) replan);
      assertThat(replanAgents).containsAtLeast("aggregate_agreements", "aggregate_disagreements");
      assertThat(replanAgents).doesNotContain("final_synthesis");
      assertThat(replanAgents).doesNotContain("aggregate_rankings");
    }

    @Test
    void replanReducesToEmptyPlanWhenGoalSatisfied() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(1));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1 fails
      planner.firstAction(context).blockingGet();

      // Goal satisfied externally before replan
      context.state().put("council_summary", "injected");

      // Replan: goal in state → empty plan → Done
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void reinitWithDifferentGoal() {
      ImmutableList<BaseAgent> agents = councilAgents();

      // First init: full council (9 agents, 4 groups)
      GoalOrientedPlanner planner = new GoalOrientedPlanner("council_summary", COUNCIL_METADATA);
      ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
      PlanningContext ctx1 = createPlanningContext(agents, state1);
      planner.init(ctx1);

      List<List<String>> fullGroups = collectAllGroups(planner, ctx1);
      assertThat(fullGroups).hasSize(4);

      // Second init: rankings only (3 agents, 3 groups)
      GoalOrientedPlanner planner2 =
          new GoalOrientedPlanner("aggregate_rankings", COUNCIL_METADATA);
      ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
      PlanningContext ctx2 = createPlanningContext(agents, state2);
      planner2.init(ctx2);

      List<List<String>> rankingGroups = collectAllGroups(planner2, ctx2);
      assertThat(rankingGroups).hasSize(3);

      List<String> allAgents = rankingGroups.stream().flatMap(List::stream).toList();
      assertThat(allAgents)
          .containsExactly("initial_response", "peer_ranking", "aggregate_rankings");
    }

    @Test
    void failStopMessageContainsSpecificMissingOutputs() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.FailStop());
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Group 1 succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Group 2: only agreement_analysis succeeds
      planner.nextAction(context).blockingGet();
      context.state().put("agreement_analyses", "done");

      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.DoneWithResult.class);
      String msg = ((PlannerAction.DoneWithResult) action).result();
      // Message should contain the failed agents and their expected output keys
      assertThat(msg).contains("peer_ranking");
      assertThat(msg).contains("peer_rankings");
      assertThat(msg).contains("disagreement_analysis");
      assertThat(msg).contains("disagreement_analyses");
      // Should NOT mention the agent that succeeded (use arrow format to avoid substring match)
      assertThat(msg).doesNotContain("agreement_analysis -> agreement_analyses");
    }

    @Test
    void deepChainPartialFailureCascade() {
      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new DfsSearchStrategy(),
              new ReplanPolicy.Replan(2));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Groups 1-2 succeed fully
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Group 3: only final_synthesis fails, rest succeed
      planner.nextAction(context).blockingGet();
      context.state().put("aggregate_rankings", "done");
      context.state().put("aggregate_agreements", "done");
      context.state().put("aggregate_disagreements", "done");

      // Replan: only final_synthesis + council_summary needed
      PlannerAction replan = planner.nextAction(context).blockingGet();
      assertThat(replan).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) replan)).containsExactly("final_synthesis");
    }

    @Test
    void replanPolicyValidation() {
      assertThrows(IllegalArgumentException.class, () -> new ReplanPolicy.Replan(0));
      assertThrows(IllegalArgumentException.class, () -> new ReplanPolicy.Replan(-1));
    }

    @Test
    void aStarReplanOnCouncilWithSatisfiedPreconditions() {
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("individual_responses", "pre-existing");
      state.put("peer_rankings", "pre-existing");

      GoalOrientedPlanner planner =
          new GoalOrientedPlanner(
              "council_summary",
              COUNCIL_METADATA,
              new AStarSearchStrategy(),
              new ReplanPolicy.Replan(1));
      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      // Group 1 should skip initial_response and peer_ranking
      PlannerAction first = planner.firstAction(context).blockingGet();
      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> firstAgents = agentNames((PlannerAction.RunAgents) first);
      assertThat(firstAgents).doesNotContain("initial_response");
      assertThat(firstAgents).doesNotContain("peer_ranking");

      // Only agreement + disagreement agents should run (they need individual_responses)
      assertThat(firstAgents).containsAtLeast("agreement_analysis", "disagreement_analysis");
    }
  }
}
