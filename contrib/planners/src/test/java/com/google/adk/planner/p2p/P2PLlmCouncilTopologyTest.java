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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link P2PPlanner} against the same realistic council-like 9-agent pipeline used by the
 * GOAP {@code CouncilTopologyTest}. Validates P2P-specific behaviors: reactive wave activation,
 * iterative refinement via value-change detection, exit conditions, and termination semantics.
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
class P2PLlmCouncilTopologyTest {

  // ── Council-like metadata (identical to CouncilTopologyTest) ──────────

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

  private static List<String> agentNames(PlannerAction.RunAgents action) {
    return action.agents().stream().map(BaseAgent::name).toList();
  }

  private static String outputKeyFor(String agentName) {
    return COUNCIL_METADATA.stream()
        .filter(m -> m.agentName().equals(agentName))
        .findFirst()
        .orElseThrow()
        .outputKey();
  }

  private static void simulateSuccess(PlanningContext context, List<String> agentNames) {
    for (String name : agentNames) {
      context.state().put(outputKeyFor(name), "done_by_" + name);
    }
  }

  /**
   * Walks firstAction/nextAction until Done, simulating success at each wave. Unlike GOAP's
   * collectAllGroups, P2P requires outputs to appear in state to trigger downstream activation.
   */
  private static List<List<String>> collectAllWaves(P2PPlanner planner, PlanningContext context) {
    List<List<String>> waves = new ArrayList<>();
    PlannerAction action = planner.firstAction(context).blockingGet();
    while (action instanceof PlannerAction.RunAgents run) {
      List<String> names = agentNames(run);
      waves.add(names);
      simulateSuccess(context, names);
      action = planner.nextAction(context).blockingGet();
    }
    return waves;
  }

  // ── Part 1: ReactiveWaveActivation ────────────────────────────────────

  @Nested
  class ReactiveWaveActivation {

    @Test
    void fullCouncilProducesFourWaves() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> waves = collectAllWaves(planner, context);

      assertThat(waves).hasSize(4);
      assertThat(waves.get(0)).hasSize(1);
      assertThat(waves.get(1)).hasSize(3);
      assertThat(waves.get(2)).hasSize(4);
      assertThat(waves.get(3)).hasSize(1);
    }

    @Test
    void wave1_onlyInitialResponseActivates() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();

      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("initial_response");
    }

    @Test
    void wave2_threeAgentsActivateInParallel() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      PlannerAction second = planner.nextAction(context).blockingGet();

      assertThat(second).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) second))
          .containsExactly("peer_ranking", "agreement_analysis", "disagreement_analysis");
    }

    @Test
    void wave3_fourAgentsActivateInParallel() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");
      // Wave 2
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      PlannerAction third = planner.nextAction(context).blockingGet();

      assertThat(third).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) third))
          .containsExactly(
              "final_synthesis",
              "aggregate_rankings",
              "aggregate_agreements",
              "aggregate_disagreements");
    }

    @Test
    void wave4_councilSummaryActivatesLast() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Waves 1-3
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");
      planner.nextAction(context).blockingGet();
      context.state().put("final_synthesis", "done");
      context.state().put("aggregate_rankings", "done");
      context.state().put("aggregate_agreements", "done");
      context.state().put("aggregate_disagreements", "done");

      PlannerAction fourth = planner.nextAction(context).blockingGet();

      assertThat(fourth).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) fourth)).containsExactly("council_summary");
    }

    @Test
    void completesWithDoneAfterAllWaves() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> waves = collectAllWaves(planner, context);
      assertThat(waves).hasSize(4);

      // collectAllWaves already consumed the final Done; verify by calling nextAction again
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void preExistingState_activatesAllSatisfiedAgents() {
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("individual_responses", "pre-existing");

      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();

      // P2P activates ALL agents whose inputs are satisfied — unlike GOAP which skips agents
      // whose output already exists. initial_response (no inputs) + 3 wave-2 agents all fire.
      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> names = agentNames((PlannerAction.RunAgents) first);
      assertThat(names).hasSize(4);
      assertThat(names)
          .containsExactly(
              "initial_response", "peer_ranking", "agreement_analysis", "disagreement_analysis");
    }

    @Test
    void preExistingState_multipleKeys_compressesWaves() {
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("individual_responses", "pre-existing");
      state.put("peer_rankings", "pre-existing");

      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();

      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> names = agentNames((PlannerAction.RunAgents) first);
      // initial_response (no inputs) + peer_ranking, agreement_analysis, disagreement_analysis
      // (need individual_responses) + final_synthesis, aggregate_rankings (need peer_rankings)
      assertThat(names).hasSize(6);
      assertThat(names)
          .containsExactly(
              "initial_response",
              "peer_ranking",
              "agreement_analysis",
              "disagreement_analysis",
              "final_synthesis",
              "aggregate_rankings");
    }

    @Test
    void p2pWaveGroupingMatchesGoapGrouping() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> waves = collectAllWaves(planner, context);

      // GOAP produces: [initial_response], [peer_ranking, agreement_analysis,
      // disagreement_analysis], [final_synthesis, aggregate_rankings, aggregate_agreements,
      // aggregate_disagreements], [council_summary]
      assertThat(waves).hasSize(4);
      assertThat(waves.get(0)).containsExactly("initial_response");
      assertThat(waves.get(1))
          .containsExactly("peer_ranking", "agreement_analysis", "disagreement_analysis");
      assertThat(waves.get(2))
          .containsExactly(
              "final_synthesis",
              "aggregate_rankings",
              "aggregate_agreements",
              "aggregate_disagreements");
      assertThat(waves.get(3)).containsExactly("council_summary");
    }

    @Test
    void agentsWithNoInputsAlwaysActivateFirst() {
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("unrelated_key", "irrelevant");
      state.put("another_key", "also_irrelevant");

      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();

      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) first)).contains("initial_response");
    }
  }

  // ── Part 2: IterativeRefinement ───────────────────────────────────────

  @Nested
  class IterativeRefinement {

    @Test
    void outputChangeTriggersDownstreamReactivation() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 30);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Run full pipeline
      collectAllWaves(planner, context);

      // Change individual_responses to a new value
      context.state().put("individual_responses", "revised_responses");

      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> reactivated = agentNames((PlannerAction.RunAgents) action);
      // final_synthesis also re-activates because individual_responses is one of its inputs
      // and peer_rankings (its other input) is already in state
      assertThat(reactivated)
          .containsExactly(
              "peer_ranking", "agreement_analysis", "disagreement_analysis", "final_synthesis");
    }

    @Test
    void unchangedOutputDoesNotTriggerReactivation() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 30);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Run full pipeline — collectAllWaves puts "done_by_<name>" for each output
      collectAllWaves(planner, context);

      // Put individual_responses back with the SAME value
      // (collectAllWaves used "done_by_initial_response")
      context.state().put("individual_responses", "done_by_initial_response");

      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void cascadingRefinementThroughMultipleWaves() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 30);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Run full pipeline
      collectAllWaves(planner, context);

      // Change top-level output
      context.state().put("individual_responses", "revised_v2");

      // Wave 5: peer_ranking, agreement_analysis, disagreement_analysis + final_synthesis
      // (final_synthesis also has individual_responses as input, and peer_rankings is in state)
      PlannerAction wave5 = planner.nextAction(context).blockingGet();
      assertThat(wave5).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) wave5)).hasSize(4);

      // Simulate wave 5 producing new values
      context.state().put("peer_rankings", "revised_rankings");
      context.state().put("agreement_analyses", "revised_agreements");
      context.state().put("disagreement_analyses", "revised_disagreements");
      context.state().put("final_synthesis", "revised_synthesis_wave5");

      // Wave 6: broad re-activation because P2P broadcasts all changes:
      // - peer_rankings changed → final_synthesis + aggregate_rankings
      // - agreement_analyses changed → aggregate_agreements
      // - disagreement_analyses changed → aggregate_disagreements
      // - final_synthesis changed → council_summary
      PlannerAction wave6 = planner.nextAction(context).blockingGet();
      assertThat(wave6).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) wave6))
          .containsExactly(
              "final_synthesis",
              "aggregate_rankings",
              "aggregate_agreements",
              "aggregate_disagreements",
              "council_summary");
    }

    @Test
    void refinementOnlyAffectsAgentsWithChangedInputs() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 30);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      collectAllWaves(planner, context);

      // Only change peer_rankings
      context.state().put("peer_rankings", "new_rankings");

      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> reactivated = agentNames((PlannerAction.RunAgents) action);
      // Only agents with peer_rankings as input: final_synthesis and aggregate_rankings
      assertThat(reactivated).containsExactly("final_synthesis", "aggregate_rankings");
      assertThat(reactivated).doesNotContain("aggregate_agreements");
      assertThat(reactivated).doesNotContain("aggregate_disagreements");
    }

    @Test
    void multipleOutputChangesInSingleWave() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 30);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      collectAllWaves(planner, context);

      // Change two outputs simultaneously
      context.state().put("peer_rankings", "new_rankings");
      context.state().put("agreement_analyses", "new_agreements");

      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> reactivated = agentNames((PlannerAction.RunAgents) action);
      // Union of agents affected by either change
      assertThat(reactivated)
          .containsAtLeast("final_synthesis", "aggregate_rankings", "aggregate_agreements");
    }

    @Test
    void agentDoesNotReactivateFromItsOwnOutput() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1: initial_response activates
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Wave 2: downstream agents activate
      PlannerAction wave2 = planner.nextAction(context).blockingGet();
      assertThat(wave2).isInstanceOf(PlannerAction.RunAgents.class);
      // initial_response should NOT re-activate (it has no inputs, so onStateChanged is a no-op)
      assertThat(agentNames((PlannerAction.RunAgents) wave2)).doesNotContain("initial_response");
    }

    @Test
    void refinementWithMaxInvocationsLimit() {
      // 9 agents in full pipeline + 4 re-activations = 13
      // (final_synthesis also re-activates because individual_responses is one of its inputs)
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 13);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Full pipeline: 9 invocations
      collectAllWaves(planner, context);

      // Trigger refinement
      context.state().put("individual_responses", "revised");

      // 4 more agents activate (total 13 = maxInvocations)
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) action)).hasSize(4);

      // Simulate their success
      context.state().put("peer_rankings", "revised_rankings");
      context.state().put("agreement_analyses", "revised_agreements");
      context.state().put("disagreement_analyses", "revised_disagreements");
      context.state().put("final_synthesis", "revised_synthesis");

      // maxInvocations reached — no more agents can activate
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void refinementWithExitCondition() {
      P2PPlanner planner =
          new P2PPlanner(
              COUNCIL_METADATA, 30, (state, count) -> "final".equals(state.get("council_summary")));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Full pipeline produces council_summary = "done_by_council_summary"
      collectAllWaves(planner, context);

      // Trigger refinement
      context.state().put("individual_responses", "revised");

      // Exit condition not yet met (council_summary != "final")
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);

      // Now set council_summary to "final" to trigger exit
      context.state().put("council_summary", "final");

      // Finish current wave
      context.state().put("peer_rankings", "revised");
      context.state().put("agreement_analyses", "revised");
      context.state().put("disagreement_analyses", "revised");

      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void noRefinementWhenAgentProducesNothing() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1: initial_response activates
      planner.firstAction(context).blockingGet();
      // Agent "fails" — does NOT put individual_responses in state

      // No output value changed, so no downstream agents get shouldExecute=true
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }
  }

  // ── Part 3: TerminationBehavior ───────────────────────────────────────

  @Nested
  class TerminationBehavior {

    @Test
    void naturalTermination_noMoreActivatableAgents() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      collectAllWaves(planner, context);

      // No value changes → no agents can activate
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void maxInvocations_stopsBeforeWave2() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 1);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1: initial_response activates (count=1)
      PlannerAction first = planner.firstAction(context).blockingGet();
      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      context.state().put("individual_responses", "done");

      // maxInvocations=1 reached → Done, even though wave-2 agents could activate
      PlannerAction action = planner.nextAction(context).blockingGet();
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void maxInvocations_stopsAfterPartialPipeline() {
      // Waves 1 (1 agent) + wave 2 (3 agents) = 4 invocations
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 4);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Wave 2: 3 agents (total count=4)
      PlannerAction wave2 = planner.nextAction(context).blockingGet();
      assertThat(wave2).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) wave2)).hasSize(3);

      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Count=4 >= maxInvocations=4 → Done
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void maxInvocations_exactlyCoversFullPipeline() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 9);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      List<List<String>> waves = collectAllWaves(planner, context);

      // All 9 agents execute across 4 waves
      assertThat(waves).hasSize(4);
      int totalAgents = waves.stream().mapToInt(List::size).sum();
      assertThat(totalAgents).isEqualTo(9);
    }

    @Test
    void exitCondition_checksStateAndCount() {
      P2PPlanner planner =
          new P2PPlanner(
              COUNCIL_METADATA,
              20,
              (state, count) -> count >= 4 && state.containsKey("peer_rankings"));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1: count=1
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Wave 2: count=4, peer_rankings will be produced
      PlannerAction wave2 = planner.nextAction(context).blockingGet();
      assertThat(wave2).isInstanceOf(PlannerAction.RunAgents.class);
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Exit condition: count>=4 AND peer_rankings present → Done
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void exitCondition_checkedBeforeActivation() {
      P2PPlanner planner =
          new P2PPlanner(
              COUNCIL_METADATA, 20, (state, count) -> state.containsKey("individual_responses"));
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Exit condition fires before wave-2 agents are scanned
      PlannerAction done = planner.nextAction(context).blockingGet();
      assertThat(done).isInstanceOf(PlannerAction.Done.class);
    }
  }

  // ── Part 4: EdgeCasesAndBoundaries ────────────────────────────────────

  @Nested
  class EdgeCasesAndBoundaries {

    @Test
    void emptyState_noAgentsCanActivateExceptNoInputAgent() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();

      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("initial_response");
    }

    @Test
    void allOutputsPrePopulated_allAgentsActivateInOneWave() {
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("individual_responses", "pre");
      state.put("peer_rankings", "pre");
      state.put("agreement_analyses", "pre");
      state.put("disagreement_analyses", "pre");
      state.put("final_synthesis", "pre");
      state.put("aggregate_rankings", "pre");
      state.put("aggregate_agreements", "pre");
      state.put("aggregate_disagreements", "pre");

      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), state);
      planner.init(context);

      PlannerAction first = planner.firstAction(context).blockingGet();

      // All 9 agents fire simultaneously — P2P doesn't enforce topological order
      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) first)).hasSize(9);
    }

    @Test
    void partialWave2Failure_onlySuccessfulOutputsTriggerWave3() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Wave 1 succeeds
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");

      // Wave 2 runs
      planner.nextAction(context).blockingGet();
      // Only peer_ranking succeeds
      context.state().put("peer_rankings", "done");
      // agreement_analysis and disagreement_analysis fail (no output)

      PlannerAction action = planner.nextAction(context).blockingGet();

      assertThat(action).isInstanceOf(PlannerAction.RunAgents.class);
      List<String> activated = agentNames((PlannerAction.RunAgents) action);
      // Only agents whose inputs are fully satisfied
      assertThat(activated).containsAtLeast("final_synthesis", "aggregate_rankings");
      assertThat(activated).doesNotContain("aggregate_agreements");
      assertThat(activated).doesNotContain("aggregate_disagreements");
      assertThat(activated).doesNotContain("council_summary");
    }

    @Test
    void councilSummaryBlockedUntilAllFourInputsPresent() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context);

      // Run waves 1-2
      planner.firstAction(context).blockingGet();
      context.state().put("individual_responses", "done");
      planner.nextAction(context).blockingGet();
      context.state().put("peer_rankings", "done");
      context.state().put("agreement_analyses", "done");
      context.state().put("disagreement_analyses", "done");

      // Wave 3 runs
      planner.nextAction(context).blockingGet();
      // Produce only 3 of 4 outputs — omit aggregate_disagreements
      context.state().put("final_synthesis", "done");
      context.state().put("aggregate_rankings", "done");
      context.state().put("aggregate_agreements", "done");

      PlannerAction action = planner.nextAction(context).blockingGet();

      // council_summary needs all 4 inputs but only 3 are present → stays blocked
      assertThat(action).isInstanceOf(PlannerAction.Done.class);
    }

    @Test
    void reinitResetsAllActivatorState() {
      P2PPlanner planner = new P2PPlanner(COUNCIL_METADATA, 20);
      PlanningContext context1 = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context1);

      // Run partial pipeline (waves 1-2)
      planner.firstAction(context1).blockingGet();
      context1.state().put("individual_responses", "done");
      planner.nextAction(context1).blockingGet();

      // Re-init with fresh state
      PlanningContext context2 = createPlanningContext(councilAgents(), new ConcurrentHashMap<>());
      planner.init(context2);

      // Should start fresh: initial_response activates
      PlannerAction first = planner.firstAction(context2).blockingGet();
      assertThat(first).isInstanceOf(PlannerAction.RunAgents.class);
      assertThat(agentNames((PlannerAction.RunAgents) first)).containsExactly("initial_response");
    }
  }
}
