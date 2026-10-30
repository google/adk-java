/*
 * Copyright 2026 Google LLC
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

package com.google.adk.workflow;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.FinishReason;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SchedulerTest {

  private static final Start START = Start.INSTANCE;
  private static final Route YES = new Route.Tag("yes");
  private static final Route NO = new Route.Tag("no");

  private final List<Event> sentEvents = new ArrayList<>();
  private final NodeRunner.EventSink recordingSink =
      (event, nodeFailure) -> Completable.fromAction(() -> sentEvents.add(event));
  private final AtomicInteger activeRuns = new AtomicInteger();
  private final AtomicInteger peakRuns = new AtomicInteger();

  @Test
  public void aSingleSuccessor_keepsItsPredecessorsBranch() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");

    run(workflow(new Edge(START, a), new Edge(a, b)));

    assertThat(eventAt("wf@1/a@1").branch()).hasValue("root");
    assertThat(eventAt("wf@1/b@1").branch()).hasValue("root");
  }

  @Test
  public void aSingleSuccessor_keepsItsPredecessorsSubBranch() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");

    run(workflow(new Edge(START, a), new Edge(START, TestNode.of("idle")), new Edge(a, b)));

    assertThat(eventAt("wf@1/a@1").branch()).hasValue("root.a@1");
    assertThat(eventAt("wf@1/b@1").branch()).hasValue("root.a@1");
  }

  @Test
  public void aJoin_receivesEveryPredecessorOutputKeyedByNodeName() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");
    Node join = JoinNode.builder().name("join").build();

    run(workflow(new Edge(START, a), new Edge(START, b), new Edge(a, join), new Edge(b, join)));

    assertThat(eventAt("wf@1/join@1").output()).hasValue(ImmutableMap.of("a", "A", "b", "B"));
  }

  @Test
  public void aJoinWithOnePredecessor_keepsThatPredecessorsBranch() {
    Node a = emitter("a", "A");
    Node join = JoinNode.builder().name("join").build();

    run(workflow(new Edge(START, a), new Edge(a, join)));

    assertThat(eventAt("wf@1/join@1").output()).hasValue(ImmutableMap.of("a", "A"));
    assertThat(eventAt("wf@1/join@1").branch()).hasValue("root");
  }

  @Test
  public void aJoin_reMergesItsForkedPredecessorsToTheirCommonBranch() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");
    Node join = JoinNode.builder().name("join").build();

    run(workflow(new Edge(START, a), new Edge(START, b), new Edge(a, join), new Edge(b, join)));

    assertThat(eventAt("wf@1/a@1").branch()).hasValue("root.a@1");
    assertThat(eventAt("wf@1/b@1").branch()).hasValue("root.b@1");
    assertThat(eventAt("wf@1/join@1").branch()).hasValue("root");
  }

  @Test
  public void aJoin_reMergesToTheBranchItsPredecessorsForkedFromBelowTheRoot() {
    Node fork = emitter("fork", "F");
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");
    Node join = JoinNode.builder().name("join").build();

    run(
        workflow(
            new Edge(START, fork),
            new Edge(START, TestNode.of("idle")),
            new Edge(fork, a),
            new Edge(fork, b),
            new Edge(a, join),
            new Edge(b, join)));

    assertThat(eventAt("wf@1/a@1").branch()).hasValue("root.fork@1.a@1");
    assertThat(eventAt("wf@1/b@1").branch()).hasValue("root.fork@1.b@1");
    assertThat(eventAt("wf@1/join@1").branch()).hasValue("root.fork@1");
  }

  @Test
  public void aJoinFedByTheStartNode_receivesTheWorkflowInputBesideItsOtherPredecessors() {
    Node x = emitter("x", "X");
    Node join = JoinNode.builder().name("join").build();

    runner(workflow(new Edge(START, x), new Edge(START, join), new Edge(x, join)), "root")
        .run("IN")
        .blockingSubscribe();

    assertThat(eventAt("wf@1/join@1").output())
        .hasValue(ImmutableMap.of(Start.NAME, "IN", "x", "X"));
    assertThat(eventAt("wf@1/join@1").branch()).hasValue("root");
  }

  @Test
  public void aJoinWithoutARootBranch_reMergesToNoBranch() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");
    Node join = JoinNode.builder().name("join").build();

    run(
        workflow(new Edge(START, a), new Edge(START, b), new Edge(a, join), new Edge(b, join)),
        /* branch= */ null);

    assertThat(eventAt("wf@1/a@1").branch()).hasValue("a@1");
    assertThat(eventAt("wf@1/join@1").branch()).isEmpty();
  }

  @Test
  public void theSoleTerminalNodesOutput_becomesTheWorkflowsOutputWithoutAnotherEvent() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");

    Context context = run(workflow(new Edge(START, a), new Edge(a, b)));

    assertThat(context.requireNodeState().output()).hasValue("B");
    assertThat(eventAt("wf@1/b@1").nodeInfo().map(NodeInfo::outputFor))
        .hasValue(ImmutableList.of("wf@1/b@1", "wf@1"));
    assertThat(sentEvents).hasSize(2);
  }

  @Test
  public void twoTerminalNodesProducingOutput_failTheWorkflow() {
    Workflow workflow =
        workflow(new Edge(START, emitter("a", "A")), new Edge(START, emitter("b", "B")));

    Context context = runner(workflow, "root").run(/* nodeInput= */ null).blockingGet();

    assertThat(context.requireNodeState().failure().orElseThrow())
        .isInstanceOf(WorkflowConfigurationException.class);
    Event errorEvent = getLast(sentEvents);
    assertThat(errorEvent.nodeInfo().map(NodeInfo::path)).hasValue("wf@1");
    assertThat(errorEvent.errorCode()).hasValue(new FinishReason("WorkflowConfigurationException"));
  }

  @Test
  public void theEventsOfTheNodesInside_areAttributedToTheWorkflow() {
    run(workflow(new Edge(START, emitter("a", "A"))));

    assertThat(eventAt("wf@1/a@1").author()).isEqualTo("wf");
  }

  @Test
  public void anAgentNode_keepsItsAuthorAndFeedsItsSuccessor() {
    Node agent =
        TestUtils.createSubAgent(
            "worker", Event.builder().id("e1").author("worker").output("FROM_AGENT").build());
    List<Object> received = new ArrayList<>();

    run(workflow(new Edge(START, agent), new Edge(agent, recorder("next", received))));

    assertThat(eventAt("wf@1/worker@1").author()).isEqualTo("worker");
    assertThat(received).containsExactly("FROM_AGENT");
  }

  @Test
  public void aNestedWorkflow_handsItsTerminalOutputToTheOuterSuccessor() {
    Workflow inner =
        Workflow.builder().name("inner").edges(new Edge(START, emitter("leaf", "INNER"))).build();
    List<Object> received = new ArrayList<>();

    run(workflow(new Edge(START, inner), new Edge(inner, recorder("next", received))));

    assertThat(received).containsExactly("INNER");
    assertThat(eventAt("wf@1/inner@1/leaf@1").nodeInfo().map(NodeInfo::outputFor))
        .hasValue(ImmutableList.of("wf@1/inner@1/leaf@1", "wf@1/inner@1"));
  }

  @Test
  public void anEmittedRoute_triggersOnlyTheMatchingSuccessor() {
    Node router = router("router", YES);

    run(
        workflow(
            new Edge(START, router),
            new Edge(router, emitter("taken", "T"), YES),
            new Edge(router, emitter("skipped", "S"), NO)));

    assertThat(eventsAt("wf@1/taken@1")).hasSize(1);
    assertThat(eventsAt("wf@1/skipped@1")).isEmpty();
  }

  @Test
  public void anUnmatchedRoute_triggersEveryDefaultSuccessor() {
    Node router = router("router", new Route.Tag("unmatched"));
    Node b = emitter("b", "B");
    Node c = emitter("c", "C");
    Node join = JoinNode.builder().name("join").build();

    run(
        workflow(
            new Edge(START, router),
            new Edge(router, b, Route.Default.INSTANCE),
            new Edge(router, c, Route.Default.INSTANCE),
            new Edge(b, join),
            new Edge(c, join)));

    assertThat(eventAt("wf@1/join@1").output()).hasValue(ImmutableMap.of("b", "B", "c", "C"));
  }

  @Test
  public void maxConcurrency_capsHowManyNodesRunAtOnce() {
    CompletableSubject gate = CompletableSubject.create();
    Workflow workflow =
        Workflow.builder()
            .name("wf")
            .maxConcurrency(1)
            .edges(
                new Edge(START, gated("a", gate)),
                new Edge(START, gated("b", gate)),
                new Edge(START, gated("c", gate)))
            .build();

    TestObserver<Context> observer = runner(workflow, "root").run(/* nodeInput= */ null).test();
    gate.onComplete();

    observer.assertComplete();
    assertThat(peakRuns.get()).isEqualTo(1);
  }

  @Test
  public void withoutAMaxConcurrency_forkedNodesRunTogether() {
    CompletableSubject gate = CompletableSubject.create();
    Workflow workflow =
        workflow(
            new Edge(START, gated("a", gate)),
            new Edge(START, gated("b", gate)),
            new Edge(START, gated("c", gate)));

    TestObserver<Context> observer = runner(workflow, "root").run(/* nodeInput= */ null).test();
    gate.onComplete();

    observer.assertComplete();
    assertThat(peakRuns.get()).isEqualTo(3);
  }

  @Test
  public void aRunningNode_takesItsNextTriggerOnlyAfterItFinishes() {
    CompletableSubject gate = CompletableSubject.create();
    CompletableSubject slowGate = CompletableSubject.create();
    AtomicInteger runs = new AtomicInteger();
    Node a = emitter("a", "A");
    Node g = new TestNode("g", (ctx, input) -> gate.andThen(Flowable.just("G")));
    Node slow =
        new TestNode(
            "slow",
            (ctx, input) ->
                Flowable.defer(
                    () -> {
                      runs.incrementAndGet();
                      peakRuns.accumulateAndGet(activeRuns.incrementAndGet(), Math::max);
                      return slowGate.doOnComplete(activeRuns::decrementAndGet).toFlowable();
                    }));

    TestObserver<Context> observer =
        runner(
                workflow(
                    new Edge(START, a), new Edge(START, g), new Edge(a, slow), new Edge(g, slow)),
                "root")
            .run(/* nodeInput= */ null)
            .test();
    gate.onComplete();
    slowGate.onComplete();

    observer.assertComplete();
    assertThat(runs.get()).isEqualTo(2);
    assertThat(peakRuns.get()).isEqualTo(1);
  }

  @Test
  public void aNodeWaitingOnAnswers_keepsItsLaterTriggersQueued() {
    AtomicInteger runs = new AtomicInteger();
    Node asker =
        new TestNode(
            "asker",
            (ctx, input) -> {
              int run = runs.incrementAndGet();
              return Flowable.just(
                  Event.builder()
                      .id("e" + run)
                      .author("")
                      .longRunningToolIds(ImmutableSet.of("call-" + run))
                      .build());
            });
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");

    Context context =
        run(
            Workflow.builder()
                .name("wf")
                .maxConcurrency(1)
                .edges(
                    new Edge(START, a), new Edge(START, b), new Edge(a, asker), new Edge(b, asker))
                .build());

    assertThat(runs.get()).isEqualTo(1);
    assertThat(context.requireNodeState().interruptIds()).containsExactly("call-1");
  }

  @Test
  public void aJoinUnderAConcurrencyCap_runsOnceWithEveryPredecessorOutput() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");
    Node c = emitter("c", "C");
    Node join = JoinNode.builder().name("join").build();

    run(
        Workflow.builder()
            .name("wf")
            .maxConcurrency(1)
            .edges(
                new Edge(START, a),
                new Edge(START, b),
                new Edge(START, c),
                new Edge(a, join),
                new Edge(b, join),
                new Edge(c, join))
            .build());

    assertThat(eventAt("wf@1/join@1").output())
        .hasValue(ImmutableMap.of("a", "A", "b", "B", "c", "C"));
  }

  @Test
  public void aWaitForOutputNode_waitsUntilATriggerProducesItsOutput() {
    Node a = emitter("a", "A");
    Node b = emitter("b", "B");
    LateProducer late = new LateProducer(/* readyOn= */ 2);
    List<Object> received = new ArrayList<>();

    run(
        workflow(
            new Edge(START, a),
            new Edge(START, b),
            new Edge(a, late),
            new Edge(b, late),
            new Edge(late, recorder("next", received))));

    assertThat(late.runs).isEqualTo(2);
    assertThat(received).containsExactly("ready");
  }

  @Test
  public void aLoopThatRetriggersAJoinsPredecessors_givesTheJoinOneSetOfOutputsPerIteration() {
    Node a = new CountingEmitter("a");
    Node b = new CountingEmitter("b");
    LoopingJoin join = new LoopingJoin();

    run(
        Workflow.builder()
            .name("wf")
            .maxConcurrency(1)
            .edges(
                new Edge(START, a),
                new Edge(START, b),
                new Edge(a, join),
                new Edge(b, join),
                new Edge(join, a, YES),
                new Edge(join, b, YES))
            .build());

    assertThat(join.seen)
        .containsExactly(
            ImmutableMap.of("a", "a1", "b", "b1"), ImmutableMap.of("a", "a2", "b", "b2"))
        .inOrder();
    assertThat(eventsAt("wf@1/a@2")).hasSize(1);
  }

  @Test
  public void aFailedNode_failsTheWorkflowAndCancelsTheNodesStillRunning() {
    IllegalStateException failure = new IllegalStateException("node failed");
    AtomicBoolean canceled = new AtomicBoolean();
    Node slow =
        new TestNode(
            "slow",
            (ctx, input) ->
                CompletableSubject.create().toFlowable().doOnCancel(() -> canceled.set(true)));
    Node boom = new TestNode("boom", (ctx, input) -> Flowable.error(failure));
    List<Object> received = new ArrayList<>();

    TestObserver<Context> observer =
        runner(
                workflow(
                    new Edge(START, slow),
                    new Edge(START, boom),
                    new Edge(boom, recorder("next", received))),
                "root")
            .run(/* nodeInput= */ null)
            .test();

    observer.assertComplete();
    assertThat(observer.values().get(0).requireNodeState().failure()).hasValue(failure);
    assertThat(eventAt("wf@1/boom@1").errorCode())
        .hasValue(new FinishReason("IllegalStateException"));
    assertThat(canceled.get()).isTrue();
    assertThat(received).isEmpty();
  }

  @Test
  public void aNodeFinishingAfterAFailure_doesNotStartItsSuccessor() {
    CompletableSubject gateB = CompletableSubject.create();
    CompletableSubject gateC = CompletableSubject.create();
    // Canceling either waiting node finishes the other, so one finishes during failure handling.
    Node b =
        new TestNode(
            "b", (ctx, input) -> gateB.andThen(Flowable.just("B")).doOnCancel(gateC::onComplete));
    Node c =
        new TestNode(
            "c", (ctx, input) -> gateC.andThen(Flowable.just("C")).doOnCancel(gateB::onComplete));
    IllegalStateException failure = new IllegalStateException("boom");
    Node failer = new TestNode("failer", (ctx, input) -> Flowable.error(failure));
    List<Object> received = new ArrayList<>();
    Workflow workflow =
        workflow(
            new Edge(START, failer),
            new Edge(START, b),
            new Edge(START, c),
            new Edge(b, recorder("afterB", received)),
            new Edge(c, recorder("afterC", received)));

    Context context = runner(workflow, "root").run(/* nodeInput= */ null).blockingGet();

    assertThat(context.requireNodeState().failure()).hasValue(failure);
    assertThat(received).isEmpty();
  }

  @Test
  public void disposingTheRun_cancelsTheNodesStillRunning() {
    AtomicBoolean canceled = new AtomicBoolean();
    Node slow =
        new TestNode(
            "slow",
            (ctx, input) ->
                CompletableSubject.create().toFlowable().doOnCancel(() -> canceled.set(true)));

    runner(workflow(new Edge(START, slow)), "root").run(/* nodeInput= */ null).test().dispose();

    assertThat(canceled.get()).isTrue();
  }

  @Test
  public void disposingTheRunWhileANodeBlocksAsItStarts_startsNoOtherNode()
      throws InterruptedException {
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean secondRan = new AtomicBoolean();
    Node blocking =
        new TestNode(
            "blocking",
            (ctx, input) ->
                Flowable.fromCallable(
                    () -> {
                      blocked.countDown();
                      release.await();
                      return "late";
                    }));
    Node second =
        new TestNode("second", (ctx, input) -> Flowable.fromAction(() -> secondRan.set(true)));
    List<Event> sent = new CopyOnWriteArrayList<>();
    NodeRunner runner =
        NodeRunner.builder()
            .node(workflow(new Edge(START, blocking), new Edge(START, second)))
            .invocationContext(
                TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
                    .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
                    .build())
            // Records each event when sent, as the root's sink does, so a late checkpoint shows.
            .eventSink(
                (event, nodeFailure) -> {
                  sent.add(event);
                  return Completable.complete();
                })
            .build();
    TestObserver<Context> observer = new TestObserver<>();
    // The run starts its nodes on this thread, which the blocking node then holds.
    Thread thread = new Thread(() -> runner.run(/* nodeInput= */ null).subscribe(observer));
    thread.start();

    assertThat(blocked.await(10, SECONDS)).isTrue();
    observer.dispose();
    release.countDown();
    thread.join(SECONDS.toMillis(10));

    assertThat(thread.isAlive()).isFalse();
    assertThat(sent.stream().filter(event -> event.output().isPresent())).isEmpty();
    assertThat(secondRan.get()).isFalse();
    assertThat(checkpointedNodes(sent)).containsExactly("blocking");
  }

  @Test
  public void anErrorFromANode_failsTheWorkflowRun() {
    AssertionError fatal = new AssertionError("fatal");

    runner(
            workflow(new Edge(START, new TestNode("fatal", (ctx, input) -> Flowable.error(fatal)))),
            "root")
        .run(/* nodeInput= */ null)
        .test()
        .assertError(fatal);
  }

  @Test
  public void aNodeWaitingOnAnswers_endsItsBranchAndSurfacesItsInterruptsOnTheWorkflow() {
    Node asker =
        new TestNode(
            "asker",
            (ctx, input) ->
                Flowable.just(
                    Event.builder()
                        .id("e1")
                        .author("")
                        .longRunningToolIds(ImmutableSet.of("call-1"))
                        .build()));
    List<Object> received = new ArrayList<>();

    Context context =
        run(workflow(new Edge(START, asker), new Edge(asker, recorder("next", received))));

    assertThat(context.requireNodeState().interruptIds()).containsExactly("call-1");
    assertThat(received).isEmpty();
  }

  @CanIgnoreReturnValue
  private Context run(Workflow workflow) {
    return run(workflow, "root");
  }

  /** Runs {@code workflow} at the root on {@code branch} and asserts that it did not fail. */
  @CanIgnoreReturnValue
  private Context run(Workflow workflow, @Nullable String branch) {
    Context context = runner(workflow, branch).run(/* nodeInput= */ null).blockingGet();
    assertThat(context.requireNodeState().failure()).isEmpty();
    return context;
  }

  /** Returns a runner of {@code workflow} at the root of a run on {@code branch}. */
  private NodeRunner runner(Workflow workflow, @Nullable String branch) {
    InvocationContext invocationContext =
        TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
            .branch(branch)
            .build();
    return NodeRunner.builder()
        .node(workflow)
        .invocationContext(invocationContext)
        .eventSink(recordingSink)
        .build();
  }

  private Event eventAt(String nodePath) {
    return eventsAt(nodePath).stream().collect(onlyElement());
  }

  /** Returns the names of the nodes that the checkpoints among {@code events} record. */
  private static ImmutableSet<Object> checkpointedNodes(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.actions().agentState().stream())
        .flatMap(state -> ((Map<?, ?>) state.get(NodeState.NODES_KEY)).keySet().stream())
        .collect(toImmutableSet());
  }

  private ImmutableList<Event> eventsAt(String nodePath) {
    return sentEvents.stream()
        .filter(event -> event.nodeInfo().map(NodeInfo::path).equals(Optional.of(nodePath)))
        .collect(ImmutableList.toImmutableList());
  }

  /** Returns a node that stays running until {@code gate} completes, counting overlapping runs. */
  private Node gated(String name, CompletableSubject gate) {
    return new TestNode(
        name,
        (ctx, input) ->
            Flowable.defer(
                () -> {
                  peakRuns.accumulateAndGet(activeRuns.incrementAndGet(), Math::max);
                  // Leaves before completing, since the scheduler may start the next node then.
                  return gate.toFlowable().doOnComplete(activeRuns::decrementAndGet);
                }));
  }

  private static Workflow workflow(Edge... edges) {
    return Workflow.builder().name("wf").edges(edges).build();
  }

  private static Node emitter(String name, Object output) {
    return new TestNode(name, (ctx, input) -> Flowable.just(output));
  }

  private static Node recorder(String name, List<Object> received) {
    return new TestNode(
        name,
        (ctx, input) -> {
          received.add(input);
          return Flowable.empty();
        });
  }

  private static Node router(String name, Route route) {
    return new TestNode(
        name,
        (ctx, input) -> {
          ctx.requireNodeState().selectRoutes(ImmutableList.of(route));
          return Flowable.empty();
        });
  }

  /** A node that waits for an output and produces one only from its {@code readyOn}-th run. */
  private static final class LateProducer implements Node {
    private final int readyOn;
    private int runs;

    LateProducer(int readyOn) {
      this.readyOn = readyOn;
    }

    @Override
    public String name() {
      return "late";
    }

    @Override
    public boolean waitForOutput() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return ++runs >= readyOn ? Flowable.just("ready") : Flowable.empty();
    }
  }

  /** A node whose output names its run, so the iterations of a loop are distinguishable. */
  private static final class CountingEmitter implements Node {
    private final String name;
    private int runs;

    CountingEmitter(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(name + ++runs);
    }
  }

  /** A join that routes back to its predecessors once, recording every input it receives. */
  private static final class LoopingJoin implements Node {
    private final List<@Nullable Object> seen = new ArrayList<>();

    @Override
    public String name() {
      return "join";
    }

    @Override
    public boolean requiresAllPredecessors() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      seen.add(nodeInput);
      if (seen.size() == 1) {
        context.requireNodeState().selectRoutes(ImmutableList.of(YES));
        return Flowable.empty();
      }
      return Flowable.just(nodeInput);
    }
  }
}
