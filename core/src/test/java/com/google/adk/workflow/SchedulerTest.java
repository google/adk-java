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

import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.instantRetry;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.LiveEventSessionService;
import com.google.adk.workflow.WorkflowResumeTesting.MessageOutputNode;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SchedulerTest {

  private static final Start START = Start.INSTANCE;
  private static final Route YES = new Route.Tag("yes");
  private static final Route NO = new Route.Tag("no");
  private static final Schema COUNT_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(ImmutableMap.of("count", Schema.builder().type(Type.Known.INTEGER).build()))
          .build();
  private static final Schema INTEGER_SCHEMA = Schema.builder().type(Type.Known.INTEGER).build();
  private static final RunNodeConfig USE_AS_OUTPUT =
      RunNodeConfig.builder().runId("c").useAsOutput(true).build();

  // Nodes run on other threads, so their events can arrive concurrently.
  private final List<Event> sentEvents = new CopyOnWriteArrayList<>();
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

    TestObserver<Context> observer = inlineRunner(workflow).run(/* nodeInput= */ null).test();
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

    TestObserver<Context> observer = inlineRunner(workflow).run(/* nodeInput= */ null).test();
    gate.onComplete();

    observer.assertComplete();
    assertThat(peakRuns.get()).isEqualTo(3);
  }

  @Test
  public void forkedNodesThatBlockTheirThreads_runAtOnce() {
    CountDownLatch allRunning = new CountDownLatch(3);
    Workflow workflow =
        workflow(
            new Edge(START, blocksUntil("a", allRunning)),
            new Edge(START, blocksUntil("b", allRunning)),
            new Edge(START, blocksUntil("c", allRunning)));

    Context context = runner(workflow, "root").run(/* nodeInput= */ null).blockingGet();

    assertThat(context.requireNodeState().failure()).isEmpty();
  }

  @Test
  public void forkedAgentsBlockedOnTheirModel_callItAtOnce() {
    CountDownLatch allCalling = new CountDownLatch(2);
    // Each call blocks its thread until both are in flight, like Gemini's non-streaming call.
    TestLlm model =
        new TestLlm(() ->
            Flowable.fromCallable(() -> {
              allCalling.countDown();
              awaitRelease(allCalling);
              return LlmResponse.builder()
                  .content(Content.fromParts(Part.fromText("done")))
                  .build();
            }));
    Workflow workflow =
        workflow(
            new Edge(START, LlmAgent.builder().name("first").model(model).build()),
            new Edge(START, LlmAgent.builder().name("second").model(model).build()));
    Runner runner =
        Runner.builder().app(App.builder().name("app").rootNode(workflow).build()).build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();

    ImmutableList<Event> events =
        ImmutableList.copyOf(
            runner
                .runAsync("user", session.id(), Content.fromParts(Part.fromText("go")))
                .timeout(30, SECONDS)
                .toList()
                .blockingGet());

    assertThat(model.getRequests()).hasSize(2);
    assertThat(events.stream().filter(event -> event.errorCode().isPresent())).isEmpty();
  }

  @Test
  public void aFailingNode_doesNotInterruptASiblingThatBlocksItsThread() throws Exception {
    CountDownLatch blockedStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blockedDone = new CountDownLatch(1);
    AtomicBoolean siblingInterrupted = new AtomicBoolean();
    Node blocked =
        new TestNode(
            "blocked",
            (ctx, input) ->
                Flowable.fromCallable(() -> {
                  blockedStarted.countDown();
                  try {
                    boolean unused = release.await(10, SECONDS);
                  } catch (InterruptedException e) {
                    siblingInterrupted.set(true);
                    Thread.currentThread().interrupt();
                  } finally {
                    blockedDone.countDown();
                  }
                  return "late";
                }));
    Node failing =
        new TestNode(
            "failing",
            (ctx, input) ->
                Flowable.defer(() -> {
                  awaitRelease(blockedStarted);
                  return Flowable.error(new IllegalStateException("boom"));
                }));

    Context context =
        runner(workflow(new Edge(START, blocked), new Edge(START, failing)), "root")
            .run(/* nodeInput= */ null)
            .blockingGet();
    release.countDown();

    assertThat(context.requireNodeState().failure().orElseThrow())
        .hasMessageThat()
        .isEqualTo("boom");
    assertThat(blockedDone.await(10, SECONDS)).isTrue();
    assertThat(siblingInterrupted.get()).isFalse();
  }

  @Test
  public void nodesStartedOnOtherThreads_keepTheCallersTraceContext() {
    ContextKey<String> key = ContextKey.named("caller");
    Map<String, String> seenByNode = new ConcurrentHashMap<>();
    Node a = traceRecorder("a", key, seenByNode);
    Node b = traceRecorder("b", key, seenByNode);

    try (Scope unused =
        io.opentelemetry.context.Context.current().with(key, "parent").makeCurrent()) {
      run(
          workflow(
              new Edge(START, a),
              new Edge(START, b),
              new Edge(a, traceRecorder("c", key, seenByNode))));
    }

    assertThat(seenByNode).containsExactly("a", "parent", "b", "parent", "c", "parent");
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
        inlineRunner(
                workflow(
                    new Edge(START, a), new Edge(START, g), new Edge(a, slow), new Edge(g, slow)))
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
        inlineRunner(
                workflow(
                    new Edge(START, slow),
                    new Edge(START, boom),
                    new Edge(boom, recorder("next", received))))
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

    Context context = inlineRunner(workflow).run(/* nodeInput= */ null).blockingGet();

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

    inlineRunner(workflow(new Edge(START, slow))).run(/* nodeInput= */ null).test().dispose();

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
                    .scheduler(Schedulers.trampoline())
                    .build())
            // Records each event when sent, as the root's sink does, so a late checkpoint shows.
            .eventSink((event, nodeFailure) -> {
              sent.add(event);
              return Completable.complete();
            })
            .build();
    TestObserver<Context> observer = new TestObserver<>();
    // With the trampoline scheduler, the run starts nodes on the new thread, which the node blocks.
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

    inlineRunner(
            workflow(new Edge(START, new TestNode("fatal", (ctx, input) -> Flowable.error(fatal)))))
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

  @Test
  public void aNestedWorkflowsOutputSchema_handsTheCheckedValueToTheNextNode() {
    // Arrange: the schema reads {"count": "5"} as {"count": 5}.
    Workflow root = innerToReply(emitter("n", ImmutableMap.of("count", "5")), COUNT_SCHEMA);

    ImmutableList<Event> events = new WorkflowSession(root, /* resumable= */ false).turn(go());

    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("count", 5));
  }

  @Test
  public void aWorkflowsOutputOutsideItsSchemasEnum_failsTheWorkflowWithoutQuotingIt() {
    Schema colorSchema =
        Schema.builder().type(Type.Known.STRING).enum_(ImmutableList.of("red")).build();
    Workflow inEnum = innerToReply(emitter("n", "red"), colorSchema);
    Workflow outOfEnum = innerToReply(emitter("n", "blue"), colorSchema);

    ImmutableList<Event> passed = new WorkflowSession(inEnum, /* resumable= */ false).turn(go());
    FailedTurn failed = new WorkflowSession(outOfEnum, /* resumable= */ false).failingTurn(go());

    assertThat(replyInput(passed)).isEqualTo("red");
    assertThat(failed.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(failed.error())
        .hasMessageThat()
        .startsWith("validation error: output of workflow 'inner' does not match its schema: ");
    assertThat(failed.error()).hasMessageThat().doesNotContain("blue");
    Event errorEvent =
        failed.events().stream()
            .filter(event -> event.errorCode().isPresent())
            .collect(onlyElement());
    assertThat(pathOf(errorEvent)).isEqualTo("wf@1/inner@1");
    assertThat(errorEvent.errorCode()).hasValue(new FinishReason("IllegalArgumentException"));
  }

  @Test
  public void aDelegatedWorkflowOutput_isTheCheckedValueLiveAndAfterAResume() {
    // Arrange: d delegates its output to a workflow whose schema reads {"count": "5"} as
    // {"count": 5}. A sibling of d finishes at once (live) or asks for input (resumed). The store
    // keeps each live event but reads the session back in JSON form.
    Workflow live = delegatingFork(delegator(countInner()), emitter("ask", "x"));
    WorkflowSession session =
        new WorkflowSession(
            delegatingFork(delegator(countInner()), longRunningAsk("ask", "ask_1")),
            new LiveEventSessionService());
    session.advance(go());

    ImmutableList<Event> liveEvents =
        new WorkflowSession(live, new LiveEventSessionService()).turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    Map<?, ?> liveOutput = (Map<?, ?>) joinedOutputOfD(liveEvents);
    assertThat(liveOutput).isEqualTo(ImmutableMap.of("count", 5));
    assertThrows(UnsupportedOperationException.class, liveOutput::clear);
    assertThat(joinedOutputOfD(resumed)).isEqualTo(liveOutput);
  }

  @Test
  public void aRecordOutputUnderAnObjectSchema_isItsJsonFormLiveAndAfterAResume() {
    Node terminal = emitter("n", new Count(3));

    ImmutableList<Object> outputs = liveAndResumedReplyInputs(terminal, COUNT_SCHEMA);

    assertThat(outputs).containsExactly(ImmutableMap.of("count", 3), ImmutableMap.of("count", 3));
  }

  @Test
  public void anEnumOutputUnderAStringEnumSchema_isItsNameLiveAndAfterAResume() {
    Schema colorSchema =
        Schema.builder().type(Type.Known.STRING).enum_(ImmutableList.of("RED")).build();
    Node terminal = emitter("n", Color.RED);

    ImmutableList<Object> outputs = liveAndResumedReplyInputs(terminal, colorSchema);

    assertThat(outputs).containsExactly("RED", "RED");
  }

  @Test
  public void aWorkflowOutputWithNoJsonForm_failsTheWorkflowWithAValidationError() {
    Workflow root = innerToReply(emitter("n", new Object()), COUNT_SCHEMA);

    FailedTurn failed = new WorkflowSession(root, /* resumable= */ false).failingTurn(go());

    assertThat(failed.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(failed.error())
        .hasMessageThat()
        .startsWith("validation error: output of workflow 'inner' has no JSON form");
  }

  @Test
  public void aJoinHoldingAMessageUnderAStringProperty_readsItsTextLiveAndAfterAResume() {
    // Arrange: inner ends in a join of a node outputting a message and of the sibling, which asks
    // for input in the resumed run, so there the join runs after the resume.
    Schema joinSchema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ImmutableMap.of("agent", Schema.builder().type(Type.Known.STRING).build()))
            .build();
    Node agent = new MessageOutputNode("agent", "hi", /* schema= */ null);

    ImmutableList<Object> outputs =
        liveAndResumedReplyInputs(sibling -> innerJoining(agent, sibling, joinSchema));

    assertThat(
            outputs.stream()
                .map(output -> ((Map<?, ?>) output).get("agent"))
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("hi", "hi");
  }

  @Test
  public void aDelegatedWorkflow_sendsItsCheckedOutputBeforeItsEnd() {
    WorkflowSession session =
        new WorkflowSession(
            delegatingFork(delegator(countInner()), longRunningAsk("ask", "ask_1")));

    ImmutableList<Event> events = session.turn(go());

    int output =
        Iterables.indexOf(
            events,
            event -> event.output().isPresent() && pathOf(event).equals("wf@1/d@1/inner@c"));
    int end =
        Iterables.indexOf(
            events, event -> event.author().equals("inner") && event.actions().endOfAgent());
    assertThat(output).isAtLeast(0);
    assertThat(output).isLessThan(end);
  }

  @Test
  public void aDelegatedWorkflowOutput_isTheCheckedValueAfterAnInTurnRetry() {
    // Arrange: the schema reads "5" as 5.
    Node d = delegator(schemaWorkflow("inner", emitter("n", "5"), INTEGER_SCHEMA));

    ImmutableList<Event> events =
        new WorkflowSession(retriedOnceAfter(d), /* resumable= */ false).turn(go());

    // Assert: n ran once, so d replayed, and reply got the checked value rather than the raw one.
    assertThat(outputEventAt(events, "wf@1/d@1/inner@c/n@1").output()).hasValue("5");
    assertThat(replyInput(events)).isEqualTo(5);
  }

  @Test
  public void aFunctionNodeBodyDelegatingToACheckedWorkflow_outputsTheCheckedValue() {
    // Arrange: the body delegates to a workflow whose schema reads "5" as 5.
    FunctionNode<Void, Object> d =
        FunctionNodes.builder(Void.class, Object.class)
            .name("d")
            .rerunOnResume(true)
            .body((ctx, unused) -> {
              Object unusedOutput =
                  ctx.runNode(
                          schemaWorkflow("inner", emitter("n", "5"), INTEGER_SCHEMA),
                          null,
                          USE_AS_OUTPUT)
                      .blockingGet();
              return null;
            })
            .build();
    Workflow root = workflow(new Edge(START, d), new Edge(d, captureInput("reply")));

    ImmutableList<Event> events = new WorkflowSession(root, /* resumable= */ false).turn(go());

    assertThat(replyInput(events)).isEqualTo(5);
  }

  @Test
  public void aDelegatorOverATypedFunctionNodeDelegatingToALeaf_receivesTheLeafsOutput() {
    // Arrange: d delegates to a typed function node f, which delegates to a leaf outputting "ok".
    FunctionNode<Void, String> f =
        FunctionNodes.builder(Void.class, String.class)
            .name("f")
            .rerunOnResume(true)
            .body((ctx, unused) -> {
              Object unusedOutput =
                  ctx.runNode(emitter("leaf", "ok"), null, USE_AS_OUTPUT).blockingGet();
              return null;
            })
            .build();
    Node d = delegator(f);
    Workflow root = workflow(new Edge(START, d), new Edge(d, captureInput("reply")));

    ImmutableList<Event> events = new WorkflowSession(root, /* resumable= */ false).turn(go());

    assertThat(replyInput(events)).isEqualTo("ok");
  }

  @Test
  public void aDelegatedWorkflowOutputThatTheCheckKeeps_isRecordedForTheDelegatingNode() {
    // Arrange: the schema keeps "same" as it is.
    Workflow inner =
        schemaWorkflow(
            "inner", emitter("n", "same"), Schema.builder().type(Type.Known.STRING).build());
    WorkflowSession session =
        new WorkflowSession(delegatingFork(delegator(inner), longRunningAsk("ask", "ask_1")));

    ImmutableList<Event> first = session.turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    // Assert: the workflow's own event, not the terminal node's, records d's output.
    Event innerOutput = outputEventAt(first, "wf@1/d@1/inner@c");
    assertThat(innerOutput.output()).hasValue("same");
    assertThat(innerOutput.nodeInfo().orElseThrow().outputFor()).contains("wf@1/d@1");
    assertThat(outputEventAt(first, "wf@1/d@1/inner@c/n@1").nodeInfo().orElseThrow().outputFor())
        .containsExactly("wf@1/d@1/inner@c/n@1", "wf@1/d@1/inner@c");
    assertThat(joinedOutputOfD(resumed)).isEqualTo("same");
  }

  @Test
  public void aCheckedWorkflowInsideAnUncheckedOne_isTheDelegatingNodesOutputWithoutExtraEvents() {
    // Arrange: d delegates to outer, which has no schema, over inner, which reads "5" as 5.
    Workflow outer =
        Workflow.builder()
            .name("outer")
            .edges(new Edge(START, schemaWorkflow("inner", emitter("n", "5"), INTEGER_SCHEMA)))
            .build();
    Workflow live = delegatingFork(delegator(outer), emitter("ask", "x"));
    WorkflowSession session =
        new WorkflowSession(delegatingFork(delegator(outer), longRunningAsk("ask", "ask_1")));

    ImmutableList<Event> liveEvents = new WorkflowSession(live, /* resumable= */ false).turn(go());
    ImmutableList<Event> first = session.turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(joinedOutputOfD(liveEvents)).isEqualTo(5);
    assertThat(joinedOutputOfD(resumed)).isEqualTo(5);
    assertThat(outputPathsUnder(first, "wf@1/d@1/"))
        .containsExactly("wf@1/d@1/outer@c/inner@1/n@1", "wf@1/d@1/outer@c/inner@1");
  }

  @Test
  public void aCheckedWorkflowInsideACheckedOne_sendsOnlyTheOuterCheckedOutput() {
    // Arrange: d delegates to w2 over w1, and both schemas read "5" as 5.
    Workflow w2 =
        schemaWorkflow(
            "w2", schemaWorkflow("w1", emitter("n", "5"), INTEGER_SCHEMA), INTEGER_SCHEMA);
    Workflow live = delegatingFork(delegator(w2), emitter("ask", "x"));
    WorkflowSession session =
        new WorkflowSession(delegatingFork(delegator(w2), longRunningAsk("ask", "ask_1")));

    ImmutableList<Event> liveEvents = new WorkflowSession(live, /* resumable= */ false).turn(go());
    ImmutableList<Event> first = session.turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(joinedOutputOfD(liveEvents)).isEqualTo(5);
    assertThat(joinedOutputOfD(resumed)).isEqualTo(5);
    assertThat(outputPathsUnder(first, "wf@1/d@1/"))
        .containsExactly("wf@1/d@1/w2@c/w1@1/n@1", "wf@1/d@1/w2@c");
  }

  @Test
  public void anUncheckedWorkflowInsideACheckedOne_sendsNoUncheckedOutputWhenItReruns() {
    // Arrange: d delegates to w2, which reads "5" as 5, over w1, which has no schema. d asks after
    // delegating, so the resumed turn reruns d, w2 and w1, and w1 replays n.
    Workflow w1 = Workflow.builder().name("w1").edges(new Edge(START, emitter("n", "5"))).build();
    Workflow w2 = schemaWorkflow("w2", w1, INTEGER_SCHEMA);
    Node d =
        dispatcher(
            "d",
            (ctx, input) ->
                ctx.runNode(w2, null, USE_AS_OUTPUT)
                    .flatMap(result ->
                        ctx.runNode(
                                longRunningAsk("ask", "ask_1"),
                                null,
                                RunNodeConfig.builder().runId("a").build())
                            .map(unused -> result))
                    .toFlowable());
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, d), new Edge(d, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(replyInput(resumed)).isEqualTo(5);
    assertThat(outputPathsUnder(resumed, "wf@1/d@1/")).doesNotContain("wf@1/d@1/w2@c/w1@1");
  }

  @Test
  public void aRootWorkflowsOutputSchema_sendsNoOutputEventOfTheWorkflowsOwn() {
    // Arrange: the schema reads {"count": "5"} as {"count": 5}.
    Workflow root = schemaWorkflow("wf", emitter("n", ImmutableMap.of("count", "5")), COUNT_SCHEMA);

    ImmutableList<Event> events = new WorkflowSession(root, /* resumable= */ false).turn(go());

    assertThat(outputPathsUnder(events, "")).containsExactly("wf@1/n@1");
  }

  @Test
  public void aCheckedWorkflowsEndOfAgent_waitsForTheCheckedOutputEventToComplete() {
    CompletableSubject checkedOutputGate = CompletableSubject.create();
    NodeRunner.EventSink gatingSink =
        (event, nodeFailure) -> {
          sentEvents.add(event);
          if (event.nodeInfo().map(NodeInfo::path).equals(Optional.of("wf@1/d@1/inner@c"))
              && event.output().isPresent()) {
            return checkedOutputGate;
          }
          return Completable.complete();
        };
    Workflow root = workflow(new Edge(START, delegator(countInner())));
    InvocationContext invocationContext =
        TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
            .branch("root")
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .scheduler(Schedulers.trampoline())
            .build();
    var run =
        NodeRunner.builder()
            .node(root)
            .invocationContext(invocationContext)
            .eventSink(gatingSink)
            .build()
            .run(/* nodeInput= */ null)
            .test();

    run.dispose();

    assertThat(
            sentEvents.stream()
                .filter(e -> e.author().equals("inner") && e.actions().endOfAgent())
                .toList())
        .isEmpty();
    assertThat(
            sentEvents.stream()
                .filter(e ->
                    e.nodeInfo().map(NodeInfo::path).equals(Optional.of("wf@1/d@1/inner@c"))
                        && e.output().isPresent())
                .toList())
        .hasSize(1);
  }

  @Test
  public void aWorkflowOutputReadAsNull_isNoOutputForTheDelegatingNodeLiveAndAfterAResume() {
    // Arrange: inner's terminal message "null" reads as JSON null under a nullable schema.
    Workflow live = delegatingFork(delegator(nullInner()), emitter("ask", "x"));
    WorkflowSession session =
        new WorkflowSession(delegatingFork(delegator(nullInner()), longRunningAsk("ask", "ask_1")));
    session.advance(go());

    ImmutableList<Event> liveEvents = new WorkflowSession(live, /* resumable= */ false).turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(joinedOutputOfD(liveEvents)).isNull();
    assertThat(joinedOutputOfD(resumed)).isNull();
  }

  @Test
  public void aWorkflowOutputReadAsNull_isNoOutputForTheDelegatingNodeAfterAnInTurnRetry() {
    Workflow root = retriedOnceAfter(delegator(nullInner()));

    ImmutableList<Event> events = new WorkflowSession(root, /* resumable= */ false).turn(go());

    assertThat(replyInput(events)).isNull();
  }

  @Test
  public void aRejectedWorkflowOutput_leavesTheDelegatingNodeFreeToOutputItsFallback() {
    // Arrange: d catches the failure of a workflow whose schema rejects "abc" and outputs
    // "fallback".
    Workflow rejecting = schemaWorkflow("inner", emitter("n", "abc"), INTEGER_SCHEMA);
    Workflow live = delegatingFork(fallbackDelegator(rejecting), emitter("ask", "x"));
    WorkflowSession session =
        new WorkflowSession(
            delegatingFork(fallbackDelegator(rejecting), longRunningAsk("ask", "ask_1")));
    session.advance(go());

    ImmutableList<Event> liveEvents = new WorkflowSession(live, /* resumable= */ false).turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(joinedOutputOfD(liveEvents)).isEqualTo("fallback");
    assertThat(joinedOutputOfD(resumed)).isEqualTo("fallback");
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
    return runner(workflow, branch, /* scheduler= */ null);
  }

  private NodeRunner runner(
      Workflow workflow, @Nullable String branch, @Nullable Scheduler scheduler) {
    InvocationContext invocationContext =
        TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
            .branch(branch)
            .scheduler(scheduler)
            .build();
    return NodeRunner.builder()
        .node(workflow)
        .invocationContext(invocationContext)
        .eventSink(recordingSink)
        .build();
  }

  /**
   * Returns a runner of {@code workflow} at the root of a run on the "root" branch. It uses the
   * trampoline scheduler, so each node starts on the thread that starts or wakes the loop.
   */
  private NodeRunner inlineRunner(Workflow workflow) {
    return runner(workflow, "root", Schedulers.trampoline());
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

  /** Returns a node that blocks its running thread until {@code latch} reaches zero. */
  private static Node blocksUntil(String name, CountDownLatch latch) {
    return new TestNode(
        name,
        (ctx, input) ->
            Flowable.fromAction(() -> {
              latch.countDown();
              awaitRelease(latch);
            }));
  }

  /** Returns a node that records the value of {@code key} from the trace context it starts with. */
  private static Node traceRecorder(
      String name, ContextKey<String> key, Map<String, String> seenByNode) {
    return new TestNode(
        name,
        (ctx, input) -> {
          seenByNode.put(name, String.valueOf(io.opentelemetry.context.Context.current().get(key)));
          return Flowable.empty();
        });
  }

  /** Waits for {@code latch} to be released, or fails the node if it times out. */
  private static void awaitRelease(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(10, SECONDS)) {
      throw new IllegalStateException("The latch was not released.");
    }
  }

  private static Workflow workflow(Edge... edges) {
    return Workflow.builder().name("wf").edges(edges).build();
  }

  /** Returns a workflow named {@code name} that runs {@code terminal} under {@code schema}. */
  private static Workflow schemaWorkflow(String name, Node terminal, Schema schema) {
    return Workflow.builder()
        .name(name)
        .edges(new Edge(START, terminal))
        .outputSchema(schema)
        .build();
  }

  /**
   * Returns a workflow that runs {@code terminal} in a workflow named {@code inner}, then reply.
   */
  private static Workflow innerToReply(Node terminal, Schema innerOutputSchema) {
    Workflow inner = schemaWorkflow("inner", terminal, innerOutputSchema);
    return workflow(new Edge(START, inner), new Edge(inner, captureInput("reply")));
  }

  /** Returns a workflow whose schema reads {"count": "5"} as {"count": 5}. */
  private static Workflow countInner() {
    return schemaWorkflow("inner", emitter("n", ImmutableMap.of("count", "5")), COUNT_SCHEMA);
  }

  /**
   * Returns what reply received live and after a resume from a workflow inner that runs {@code
   * terminal} under {@code schema}, beside a sibling.
   */
  private static ImmutableList<Object> liveAndResumedReplyInputs(Node terminal, Schema schema) {
    return liveAndResumedReplyInputs(sibling -> innerBeside(terminal, sibling, schema));
  }

  /**
   * Returns what reply received live and after a resume from the workflow {@code rootWith} builds
   * around a sibling node. The sibling finishes at once (live) or asks for input (resumed); the
   * store reads the session back in JSON form.
   */
  private static ImmutableList<Object> liveAndResumedReplyInputs(
      Function<Node, Workflow> rootWith) {
    WorkflowSession session =
        new WorkflowSession(
            rootWith.apply(longRunningAsk("ask", "ask_1")), new LiveEventSessionService());
    session.advance(go());

    ImmutableList<Event> live =
        new WorkflowSession(rootWith.apply(emitter("ask", "x")), new LiveEventSessionService())
            .turn(go());
    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));
    return ImmutableList.of(replyInput(live), replyInput(resumed));
  }

  /**
   * Returns a workflow that runs inner, then reply. Inner, under {@code schema}, runs {@code
   * terminal} beside {@code sibling}, whose successor outputs nothing.
   */
  private static Workflow innerBeside(Node terminal, Node sibling, Schema schema) {
    Node sink = new TestNode("sink", (ctx, input) -> Flowable.empty());
    Workflow inner =
        Workflow.builder()
            .name("inner")
            .outputSchema(schema)
            .edges(new Edge(START, terminal), new Edge(START, sibling), new Edge(sibling, sink))
            .build();
    return workflow(new Edge(START, inner), new Edge(inner, captureInput("reply")));
  }

  /**
   * Returns a workflow that runs inner, then reply. Inner, under {@code schema}, joins the outputs
   * of {@code first} and {@code sibling} in j.
   */
  private static Workflow innerJoining(Node first, Node sibling, Schema schema) {
    Node join = JoinNode.builder().name("j").build();
    Workflow inner =
        Workflow.builder()
            .name("inner")
            .outputSchema(schema)
            .edges(
                new Edge(START, first),
                new Edge(START, sibling),
                new Edge(first, join),
                new Edge(sibling, join))
            .build();
    return workflow(new Edge(START, inner), new Edge(inner, captureInput("reply")));
  }

  /** Returns a workflow whose terminal message "null" a nullable schema reads as no output. */
  private static Workflow nullInner() {
    Schema nullableInteger = Schema.builder().type(Type.Known.INTEGER).nullable(true).build();
    return schemaWorkflow(
        "inner", new MessageOutputNode("n", "null", /* schema= */ null), nullableInteger);
  }

  /** Returns a node named d that delegates its output to {@code child}. */
  private static Node delegator(Node child) {
    return dispatcher(
        "d", (ctx, input) -> ctx.runNode(child, null, USE_AS_OUTPUT).ignoreElement().toFlowable());
  }

  /** Returns a node named d that delegates its output to {@code child}, or outputs "fallback". */
  private static Node fallbackDelegator(Node child) {
    return dispatcher(
        "d",
        (ctx, input) ->
            ctx.runNode(child, null, USE_AS_OUTPUT)
                .onErrorResumeNext(error -> Maybe.just("fallback"))
                .toFlowable());
  }

  /** Returns a workflow that forks to {@code d} and {@code sibling} and joins both in j. */
  private static Workflow delegatingFork(Node d, Node sibling) {
    Node join = JoinNode.builder().name("j").build();
    return workflow(
        new Edge(START, d), new Edge(START, sibling), new Edge(d, join), new Edge(sibling, join));
  }

  /**
   * Returns a workflow that runs {@code d}, then reply, which fails its first run, so the workflow
   * retries in the same turn and replays {@code d} from the session.
   */
  private static Workflow retriedOnceAfter(Node d) {
    AtomicInteger runs = new AtomicInteger();
    Node reply =
        new TestNode(
            "reply",
            (ctx, input) ->
                runs.incrementAndGet() == 1
                    ? Flowable.error(new IllegalStateException("flaky"))
                    : Flowable.just(Collections.singletonMap("received", input)));
    return Workflow.builder()
        .name("wf")
        .edges(new Edge(START, d), new Edge(d, reply))
        .config(NodeConfig.builder().retryConfig(instantRetry(2)).build())
        .build();
  }

  /** Returns what the join j of {@link #delegatingFork} received from d. */
  private static @Nullable Object joinedOutputOfD(List<Event> events) {
    return ((Map<?, ?>) outputEventAt(events, "wf@1/j@1").output().orElseThrow()).get("d");
  }

  /** Returns the paths of the output events whose path starts with {@code prefix}, in order. */
  private static ImmutableList<String> outputPathsUnder(List<Event> events, String prefix) {
    return events.stream()
        .filter(event -> event.output().isPresent())
        .map(WorkflowResumeTesting::pathOf)
        .filter(path -> path.startsWith(prefix))
        .collect(ImmutableList.toImmutableList());
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

  private record Count(int count) {}

  private enum Color {
    RED
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
