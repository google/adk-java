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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PlannerAgent}. */
@RunWith(JUnit4.class)
public final class PlannerAgentTest {

  @Test
  public void runAsync_withDone_stopsImmediately() {
    TestBaseAgent subAgent = TestUtils.createSubAgent("sub", TestUtils.createEvent("e1"));
    Planner donePlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            return Single.just(new PlannerAction.Done());
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            return Single.just(new PlannerAction.Done());
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder().name("planner").subAgents(subAgent).planner(donePlanner).build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    assertThat(events).isEmpty();
  }

  @Test
  public void runAsync_withDoneWithResult_emitsResultEvent() {
    TestBaseAgent subAgent = TestUtils.createSubAgent("sub");
    Planner resultPlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            return Single.just(new PlannerAction.DoneWithResult("final answer"));
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            return Single.just(new PlannerAction.Done());
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder().name("planner").subAgents(subAgent).planner(resultPlanner).build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content().get().text()).isEqualTo("final answer");
  }

  @Test
  public void runAsync_withNoOp_skipsAndContinues() {
    Event event1 = TestUtils.createEvent("e1");
    TestBaseAgent subAgent = TestUtils.createSubAgent("sub", event1);

    AtomicInteger callCount = new AtomicInteger(0);
    Planner noOpThenRunPlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            return Single.just(new PlannerAction.NoOp());
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            int count = callCount.incrementAndGet();
            if (count == 1) {
              return Single.just(new PlannerAction.RunAgents(context.findAgent("sub")));
            }
            return Single.just(new PlannerAction.Done());
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder()
            .name("planner")
            .subAgents(subAgent)
            .planner(noOpThenRunPlanner)
            .build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    assertThat(events).containsExactly(event1);
  }

  @Test
  public void runAsync_withMaxIterations_stopsAtLimit() {
    TestBaseAgent subAgent =
        TestUtils.createSubAgent("sub", () -> Flowable.just(TestUtils.createEvent("e")));

    Planner alwaysRunPlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            return Single.just(new PlannerAction.RunAgents(context.findAgent("sub")));
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            return Single.just(new PlannerAction.RunAgents(context.findAgent("sub")));
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder()
            .name("planner")
            .subAgents(subAgent)
            .planner(alwaysRunPlanner)
            .maxIterations(3)
            .build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    // 3 iterations: first + 2 next calls, each producing 1 event
    assertThat(events).hasSize(3);
  }

  @Test
  public void runAsync_sequentialPlannerPattern() {
    Event event1 = TestUtils.createEvent("e1");
    Event event2 = TestUtils.createEvent("e2");
    Event event3 = TestUtils.createEvent("e3");
    TestBaseAgent agentA = TestUtils.createSubAgent("agentA", event1);
    TestBaseAgent agentB = TestUtils.createSubAgent("agentB", event2);
    TestBaseAgent agentC = TestUtils.createSubAgent("agentC", event3);

    AtomicInteger cursor = new AtomicInteger(0);
    ImmutableList<String> order = ImmutableList.of("agentA", "agentB", "agentC");
    Planner seqPlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            cursor.set(0);
            return selectNext(context);
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            return selectNext(context);
          }

          private Single<PlannerAction> selectNext(PlanningContext context) {
            int idx = cursor.getAndIncrement();
            if (idx >= order.size()) {
              return Single.just(new PlannerAction.Done());
            }
            return Single.just(new PlannerAction.RunAgents(context.findAgent(order.get(idx))));
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder()
            .name("planner")
            .subAgents(agentA, agentB, agentC)
            .planner(seqPlanner)
            .build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    assertThat(events).containsExactly(event1, event2, event3).inOrder();
  }

  @Test
  public void runAsync_withParallelRunAgents_runsMultipleAgents() {
    Event event1 = TestUtils.createEvent("e1");
    Event event2 = TestUtils.createEvent("e2");
    TestBaseAgent agentA = TestUtils.createSubAgent("agentA", event1);
    TestBaseAgent agentB = TestUtils.createSubAgent("agentB", event2);

    Planner parallelPlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            return Single.just(new PlannerAction.RunAgents(context.availableAgents()));
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            return Single.just(new PlannerAction.Done());
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder()
            .name("planner")
            .subAgents(agentA, agentB)
            .planner(parallelPlanner)
            .build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    assertThat(events).containsExactly(event1, event2);
  }

  @Test
  public void runAsync_withEmptySubAgents_returnsEmpty() {
    Planner planner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            return Single.just(new PlannerAction.Done());
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            return Single.just(new PlannerAction.Done());
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder()
            .name("planner")
            .subAgents(ImmutableList.of())
            .planner(planner)
            .build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    assertThat(events).isEmpty();
  }

  @Test(expected = IllegalStateException.class)
  public void builder_withoutPlanner_throwsIllegalState() {
    TestBaseAgent subAgent = TestUtils.createSubAgent("sub");
    PlannerAgent.builder().name("planner").subAgents(subAgent).build();
  }

  @Test
  public void runAsync_stateIsSharedAcrossAgents() {
    // Agent A writes to state, Agent B reads from state
    Event eventA =
        TestUtils.createEvent("eA").toBuilder()
            .actions(
                EventActions.builder()
                    .stateDelta(
                        new java.util.concurrent.ConcurrentHashMap<>(
                            java.util.Map.of("key1", "value1")))
                    .build())
            .build();

    TestBaseAgent agentA = TestUtils.createSubAgent("agentA", eventA);
    TestBaseAgent agentB = TestUtils.createSubAgent("agentB", TestUtils.createEvent("eB"));

    AtomicInteger cursor = new AtomicInteger(0);
    Planner seqPlanner =
        new Planner() {
          @Override
          public Single<PlannerAction> firstAction(PlanningContext context) {
            cursor.set(0);
            return nextAction(context);
          }

          @Override
          public Single<PlannerAction> nextAction(PlanningContext context) {
            int idx = cursor.getAndIncrement();
            if (idx == 0) {
              return Single.just(new PlannerAction.RunAgents(context.findAgent("agentA")));
            }
            if (idx == 1) {
              return Single.just(new PlannerAction.RunAgents(context.findAgent("agentB")));
            }
            return Single.just(new PlannerAction.Done());
          }
        };

    PlannerAgent agent =
        PlannerAgent.builder()
            .name("planner")
            .subAgents(agentA, agentB)
            .planner(seqPlanner)
            .build();

    InvocationContext ctx = TestUtils.createInvocationContext(agent);
    List<Event> events = agent.runAsync(ctx).toList().blockingGet();

    // Both events should be emitted
    assertThat(events).hasSize(2);
    // State delta from agentA's event should be present
    assertThat(events.get(0).actions().stateDelta()).containsEntry("key1", "value1");
  }
}
