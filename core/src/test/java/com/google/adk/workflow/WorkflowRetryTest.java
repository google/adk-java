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
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.throwing;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FinishReason;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the retry policy and timeout that a node's {@link NodeConfig} sets, run end to end
 * through a {@link Runner}.
 */
@RunWith(JUnit4.class)
public final class WorkflowRetryTest {

  private static final RunNodeConfig NAMED_RUN = RunNodeConfig.builder().runId("n").build();
  private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(1);
  private static final String SCHEDULER_THREAD = "invocation-scheduler";

  @Test
  public void aFailingNode_isRetriedUntilAnAttemptSucceeds() {
    CountingNode flaky =
        new CountingNode(
            "flaky", retrying(3), (ctx, run) -> run < 3 ? fail() : Flowable.just("recovered"));

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, flaky))).turn(go());

    assertThat(flaky.runs()).isEqualTo(3);
    assertThat(errorPaths(events)).containsExactly("wf@1/flaky@1", "wf@1/flaky@1");
    assertThat(outputEventAt(events, "wf@1/flaky@1").output()).hasValue("recovered");
  }

  @Test
  public void aRetriedNode_readsItsAttemptCountFromItsContext() {
    List<Integer> attemptCounts = new CopyOnWriteArrayList<>();
    CountingNode flaky =
        new CountingNode(
            "flaky",
            retrying(3),
            (ctx, run) -> {
              attemptCounts.add(ctx.attemptCount());
              return run < 3 ? fail() : Flowable.just("recovered");
            });

    new WorkflowSession(workflow(new Edge(START, flaky))).advance(go());

    assertThat(attemptCounts).containsExactly(1, 2, 3).inOrder();
  }

  @Test
  public void aNodeThatKeepsFailing_failsTheTurnWithItsLastFailure() {
    CountingNode doomed =
        new CountingNode(
            "doomed",
            retrying(3),
            (ctx, run) -> Flowable.error(new IllegalStateException("attempt " + run)));

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, doomed))).failingTurn(go());

    assertThat(doomed.runs()).isEqualTo(3);
    assertThat(errorPaths(turn.events())).hasSize(3);
    assertThat(turn.error()).hasMessageThat().isEqualTo("attempt 3");
  }

  @Test
  public void aFailureOfATypeTheExceptionsDoNotList_isNotRetried() {
    RetryConfig onlyTimeouts =
        instantRetry(3).toBuilder().exceptions(ImmutableList.of("NodeTimeoutException")).build();
    CountingNode flaky =
        new CountingNode(
            "flaky", NodeConfig.builder().retryConfig(onlyTimeouts).build(), (ctx, run) -> fail());

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, flaky))).failingTurn(go());

    assertThat(flaky.runs()).isEqualTo(1);
    assertThat(turn.error()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void aFailureOfASubtypeOfAListedExceptionClass_isRetried() {
    RetryConfig onRuntimeExceptions =
        instantRetry(3).toBuilder().exceptionClasses(RuntimeException.class).build();
    CountingNode flaky =
        new CountingNode(
            "flaky",
            NodeConfig.builder().retryConfig(onRuntimeExceptions).build(),
            (ctx, run) -> run == 1 ? fail() : Flowable.just("recovered"));

    new WorkflowSession(workflow(new Edge(START, flaky))).advance(go());

    assertThat(flaky.runs()).isEqualTo(2);
  }

  @Test
  public void aRootNode_isRetried() {
    CountingNode flaky =
        new CountingNode(
            "flaky", retrying(2), (ctx, run) -> run == 1 ? fail() : Flowable.just("recovered"));

    ImmutableList<Event> events = new WorkflowSession(flaky).turn(go());

    assertThat(flaky.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "flaky@1").output()).hasValue("recovered");
  }

  @Test
  public void aRetry_waitsOutTheBackoffDelayFirst() {
    RetryConfig policy =
        RetryConfig.builder().maxAttempts(2).initialDelay(Duration.ofMillis(200)).jitter(0).build();
    Stopwatch sinceFirstRun = Stopwatch.createUnstarted();
    AtomicReference<Duration> gap = new AtomicReference<>();
    CountingNode flaky =
        new CountingNode(
            "flaky",
            NodeConfig.builder().retryConfig(policy).build(),
            (ctx, run) -> {
              if (run == 1) {
                sinceFirstRun.start();
                return fail();
              }
              gap.set(sinceFirstRun.elapsed());
              return Flowable.just("recovered");
            });

    new WorkflowSession(workflow(new Edge(START, flaky))).advance(go());

    assertThat(gap.get()).isAtLeast(Duration.ofMillis(200));
  }

  @Test
  public void aTimedAttemptAndARetry_keepTheCallersTraceContext() {
    ContextKey<String> key = ContextKey.named("caller");
    List<String> seen = new CopyOnWriteArrayList<>();
    CountingNode timed =
        new CountingNode(
            "timed",
            NodeConfig.builder().timeout(Duration.ofSeconds(10)).build(),
            (ctx, run) -> {
              seen.add("timed: " + io.opentelemetry.context.Context.current().get(key));
              return Flowable.just("in time");
            });
    CountingNode flaky =
        new CountingNode(
            "flaky",
            retrying(2),
            (ctx, run) -> {
              seen.add("flaky " + run + ": " + io.opentelemetry.context.Context.current().get(key));
              return run == 1 ? fail() : Flowable.just("recovered");
            });

    try (Scope unused = io.opentelemetry.context.Context.current().with(key, "set").makeCurrent()) {
      new WorkflowSession(workflow(new Edge(START, timed), new Edge(timed, flaky))).advance(go());
    }

    assertThat(seen).containsExactly("timed: set", "flaky 1: set", "flaky 2: set").inOrder();
  }

  @Test
  public void aTimeout_reportsTheFailureInTheCallersTraceContext() {
    ContextKey<String> key = ContextKey.named("caller");
    List<String> seen = new CopyOnWriteArrayList<>();
    BasePlugin recorder =
        new BasePlugin("recorder") {
          @Override
          public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
            if (event.errorCode().isPresent()) {
              seen.add(String.valueOf(io.opentelemetry.context.Context.current().get(key)));
            }
            return Maybe.empty();
          }
        };
    CountingNode slow =
        new CountingNode(
            "slow", NodeConfig.builder().timeout(SHORT_TIMEOUT).build(), (ctx, run) -> late());
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, slow)), /* resumable= */ true, recorder);

    Throwable error;
    try (Scope unused = io.opentelemetry.context.Context.current().with(key, "set").makeCurrent()) {
      error = session.failingTurn(go()).error();
    }

    assertThat(error).isInstanceOf(NodeTimeoutException.class);
    assertThat(seen).containsExactly("set");
  }

  @Test
  public void aNodeOverrunningItsTimeout_failsWithANodeTimeoutExceptionAndIsCanceled() {
    AtomicBoolean canceled = new AtomicBoolean();
    CountingNode slow =
        new CountingNode(
            "slow",
            NodeConfig.builder().timeout(SHORT_TIMEOUT).build(),
            (ctx, run) -> late().doOnCancel(() -> canceled.set(true)));

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, slow))).failingTurn(go());

    assertThat(turn.error()).isInstanceOf(NodeTimeoutException.class);
    assertThat(turn.error()).isInstanceOf(NodeExecutionException.class);
    NodeTimeoutException error = (NodeTimeoutException) turn.error();
    assertThat(error.nodeName()).isEqualTo("slow");
    assertThat(error.timeout()).isEqualTo(SHORT_TIMEOUT);
    assertThat(error).hasMessageThat().contains("'slow'");
    assertThat(errorCodes(turn.events())).containsExactly(new FinishReason("NodeTimeoutException"));
    assertThat(canceled.get()).isTrue();
  }

  @Test
  public void aTimedOutAttempt_isRetried() {
    RetryConfig onlyTimeouts =
        instantRetry(2).toBuilder().exceptions(ImmutableList.of("NodeTimeoutException")).build();
    NodeConfig config =
        NodeConfig.builder().retryConfig(onlyTimeouts).timeout(SHORT_TIMEOUT).build();
    CountingNode slow =
        new CountingNode(
            "slow", config, (ctx, run) -> run == 1 ? late() : Flowable.just("in time"));

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, slow))).turn(go());

    assertThat(slow.runs()).isEqualTo(2);
    assertThat(errorCodes(events)).containsExactly(new FinishReason("NodeTimeoutException"));
    assertThat(outputEventAt(events, "wf@1/slow@1").output()).hasValue("in time");
  }

  @Test
  public void aTimedOutAttempt_isRetriedWhenNodeExecutionExceptionIsAListedClass() {
    RetryConfig onNodeFailures =
        instantRetry(2).toBuilder().exceptionClasses(NodeExecutionException.class).build();
    NodeConfig config =
        NodeConfig.builder().retryConfig(onNodeFailures).timeout(SHORT_TIMEOUT).build();
    CountingNode slow =
        new CountingNode(
            "slow", config, (ctx, run) -> run == 1 ? late() : Flowable.just("in time"));

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, slow))).turn(go());

    assertThat(slow.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/slow@1").output()).hasValue("in time");
  }

  @Test
  public void aRetry_startsOnTheInvocationsScheduler() {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, SCHEDULER_THREAD));
    List<String> threads = new CopyOnWriteArrayList<>();
    // The first attempt fails on a timer's thread, which the retry must not start on.
    CountingNode flaky =
        new CountingNode(
            "flaky",
            retrying(2),
            (ctx, run) -> {
              threads.add(Thread.currentThread().getName());
              return run == 1
                  ? Flowable.timer(10, MILLISECONDS).flatMap(unused -> fail())
                  : Flowable.just("recovered");
            });

    List<Event> events;
    try {
      events =
          turnOn(Schedulers.from(executor), workflow(new Edge(START, flaky)))
              .toList()
              .blockingGet();
    } finally {
      executor.shutdownNow();
    }

    assertThat(outputEventAt(events, "wf@1/flaky@1").output()).hasValue("recovered");
    assertThat(threads).hasSize(2);
    assertThat(threads.get(1)).isEqualTo(SCHEDULER_THREAD);
  }

  @Test
  public void aTimeout_failsANodeThatHoldsTheInvocationsOnlyThread() {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, SCHEDULER_THREAD));
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean returned = new AtomicBoolean();
    // The node blocks the thread it runs on, the invocation's only one, until released.
    CountingNode stuck =
        new CountingNode(
            "stuck",
            NodeConfig.builder().timeout(SHORT_TIMEOUT).build(),
            (ctx, run) ->
                Flowable.fromCallable(
                    () -> {
                      boolean unused = release.await(10, SECONDS);
                      returned.set(true);
                      return "late";
                    }));

    AtomicReference<Throwable> error = new AtomicReference<>();
    boolean heldWhenTheTurnFailed;
    try {
      turnOn(Schedulers.from(executor), workflow(new Edge(START, stuck)))
          .blockingSubscribe(event -> {}, error::set);
      heldWhenTheTurnFailed = !returned.get();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertThat(error.get()).isInstanceOf(NodeTimeoutException.class);
    assertThat(error.get()).hasMessageThat().contains("'stuck'");
    assertThat(heldWhenTheTurnFailed).isTrue();
  }

  @Test
  public void aTimeout_doesNotInterruptTheHandlingOfTheNodesEvent() {
    CountingNode quick =
        new CountingNode(
            "quick",
            NodeConfig.builder().timeout(Duration.ofMillis(300)).build(),
            (ctx, run) -> Flowable.just("out"));
    AtomicBoolean interrupted = new AtomicBoolean();
    AtomicReference<Throwable> error = new AtomicReference<>();

    turnOn(Schedulers.io(), workflow(new Edge(START, quick)))
        .doOnNext(
            event -> {
              if (WorkflowResumeTesting.pathOf(event).equals("wf@1/quick@1")) {
                try {
                  // Outlasts the timeout on the thread that delivers the node's event.
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  interrupted.set(true);
                  Thread.currentThread().interrupt();
                }
              }
            })
        .blockingSubscribe(event -> {}, error::set);

    assertThat(error.get()).isInstanceOf(NodeTimeoutException.class);
    assertThat(interrupted.get()).isFalse();
  }

  @Test
  public void anOuterTimeout_isNeitherBlamedOnNorRetriedByTheNodeItCancels() {
    CountingNode inner =
        new CountingNode(
            "inner",
            NodeConfig.builder()
                .retryConfig(instantRetry(3))
                .timeout(Duration.ofSeconds(10))
                .build(),
            (ctx, run) -> late());
    Workflow outer =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(START, inner))
            .config(NodeConfig.builder().timeout(SHORT_TIMEOUT).build())
            .build();

    FailedTurn turn = new WorkflowSession(outer).failingTurn(go());

    assertThat(turn.error()).isInstanceOf(NodeTimeoutException.class);
    assertThat(((NodeTimeoutException) turn.error()).nodeName()).isEqualTo("wf");
    assertThat(inner.runs()).isEqualTo(1);
    assertThat(errorPaths(turn.events())).containsExactly("wf@1");
  }

  @Test
  public void aWorkflowTimeout_cancelsANodeThatBlocksAsItStarts() {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch firstRunEnded = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    // First run blocks on start, like an LlmAgent calling a model, until the retry releases it.
    CountingNode blocking =
        new CountingNode(
            "blocking",
            (ctx, run) ->
                run > 1
                    ? Flowable.fromCallable(
                        () -> {
                          release.countDown();
                          checkState(firstRunEnded.await(10, SECONDS));
                          return "in time";
                        })
                    : Flowable.fromCallable(
                            () -> {
                              try {
                                checkState(release.await(10, SECONDS));
                                return "late";
                              } catch (InterruptedException e) {
                                interrupted.set(true);
                                throw e;
                              }
                            })
                        // Once canceled or done emitting, so the retry ends after any late output.
                        .doFinally(firstRunEnded::countDown));
    Workflow outer =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(START, blocking))
            .config(
                NodeConfig.builder().retryConfig(instantRetry(2)).timeout(SHORT_TIMEOUT).build())
            .build();

    ImmutableList<Event> events = new WorkflowSession(outer).turn(go());

    // Canceled rather than interrupted, so the first run reports neither a failure nor its output.
    assertThat(interrupted.get()).isFalse();
    assertThat(errorCodes(events)).containsExactly(new FinishReason("NodeTimeoutException"));
    assertThat(outputEventAt(events, "wf@1/blocking@1").output()).hasValue("in time");
  }

  @Test
  public void aWorkflowWithTwoTerminalOutputs_failsWithoutARetry() {
    Workflow root =
        Workflow.builder()
            .name("wf")
            .edges(
                new Edge(START, emitter("first", "first_out")),
                new Edge(START, emitter("second", "second_out")))
            .config(retrying(3))
            .build();

    FailedTurn turn = new WorkflowSession(root).failingTurn(go());

    assertThat(turn.error()).isInstanceOf(WorkflowConfigurationException.class);
    assertThat(errorPaths(turn.events())).containsExactly("wf@1");
  }

  @Test
  public void aWorkflowRetriedAfterANodeFailed_replaysTheNodesThatFinished() {
    CountingNode first = new CountingNode("first", (ctx, run) -> Flowable.just("first_out"));
    CountingNode flaky =
        new CountingNode("flaky", (ctx, run) -> run == 1 ? fail() : Flowable.just("recovered"));
    Workflow root =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(START, first), new Edge(first, flaky))
            .config(retrying(2))
            .build();

    ImmutableList<Event> events = new WorkflowSession(root).turn(go());

    assertThat(first.runs()).isEqualTo(1);
    assertThat(flaky.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/flaky@1").output()).hasValue("recovered");
  }

  @Test
  public void aRetriedWorkflow_rerunsANodeThatTheFailureCanceledMidRun() {
    CountingNode done = new CountingNode("done", (ctx, run) -> Flowable.just("done_out"));
    CountingNode stalled = stalledOnItsFirstRun("stalled");

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, forkWithAFlakyBranch("sub", done, stalled))))
            .turn(go());

    assertThat(done.runs()).isEqualTo(1);
    assertThat(stalled.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/sub@1/join@1").output())
        .hasValue(
            ImmutableMap.of("done", "done_out", "stalled", "stalled_out", "flaky", "recovered"));
  }

  @Test
  public void aRetriedRootWorkflow_rerunsANodeThatTheFailureCanceledMidRun() {
    CountingNode done = new CountingNode("done", (ctx, run) -> Flowable.just("done_out"));
    CountingNode stalled = stalledOnItsFirstRun("stalled");

    ImmutableList<Event> events =
        new WorkflowSession(forkWithAFlakyBranch("wf", done, stalled)).turn(go());

    assertThat(done.runs()).isEqualTo(1);
    assertThat(stalled.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/join@1").output())
        .hasValue(
            ImmutableMap.of("done", "done_out", "stalled", "stalled_out", "flaky", "recovered"));
  }

  @Test
  public void aTimedOutRootNode_rerunsANamedChildThatItsTimeoutCanceledMidRun() {
    CountingNode child = stalledOnItsFirstRun("child");
    CountingNode caller =
        new CountingNode(
            "caller",
            NodeConfig.builder().retryConfig(instantRetry(2)).timeout(SHORT_TIMEOUT).build(),
            (ctx, run) -> ctx.runNode(child, null, NAMED_RUN).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(caller).turn(go());

    assertThat(child.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "caller@1").output()).hasValue("child_out");
  }

  @Test
  public void aRetriedWorkflow_rerunsANodeThatTookOnAChildFailureAfterReportingProgress() {
    CountingNode child =
        new CountingNode("child", (ctx, run) -> run == 1 ? fail() : Flowable.just("child_out"));
    CountingNode caller =
        new CountingNode(
            "caller",
            (ctx, run) ->
                Flowable.<Object>just(progress())
                    .concatWith(ctx.runNode(child, null).toFlowable()));
    Workflow root =
        Workflow.builder().name("wf").edges(new Edge(START, caller)).config(retrying(2)).build();

    ImmutableList<Event> events = new WorkflowSession(root).turn(go());

    assertThat(caller.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("child_out");
  }

  @Test
  public void aRetriedWorkflow_rerunsANodeThatTookOnAChildFailureAfterItsOutput() {
    CountingNode child =
        new CountingNode("child", (ctx, run) -> run == 1 ? fail() : Flowable.just("child_out"));
    CountingNode caller =
        new CountingNode(
            "caller",
            (ctx, run) ->
                Flowable.<Object>just("caller_out_" + run)
                    .concatWith(ctx.runNode(child, null).ignoreElement()));
    Workflow root =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(START, caller), new Edge(caller, captureInput("reply")))
            .config(retrying(2))
            .build();

    ImmutableList<Event> events = new WorkflowSession(root).turn(go());

    assertThat(caller.runs()).isEqualTo(2);
    assertThat(replyInput(events)).isEqualTo("caller_out_2");
  }

  @Test
  public void aRetriedNode_rerunsANamedChildThatTookOnAFailureAfterReportingProgress() {
    CountingNode grandchild =
        new CountingNode(
            "grandchild", (ctx, run) -> run == 1 ? fail() : Flowable.just("grandchild_out"));
    CountingNode child =
        new CountingNode(
            "child",
            (ctx, run) ->
                Flowable.<Object>just(progress())
                    .concatWith(ctx.runNode(grandchild, null).toFlowable()));
    CountingNode caller =
        new CountingNode(
            "caller",
            retrying(2),
            (ctx, run) ->
                ctx.runNode(child, null, NAMED_RUN)
                    .onErrorResumeNext(
                        error -> Maybe.error(new IllegalStateException("child failed")))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(child.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("grandchild_out");
  }

  @Test
  public void aChildWorkflowRunAgainUnderItsRunId_rerunsANodeThatTookOnAFailure() {
    CountingNode grandchild =
        new CountingNode(
            "grandchild", (ctx, run) -> run == 1 ? fail() : Flowable.just("grandchild_out"));
    CountingNode inner =
        new CountingNode(
            "inner",
            (ctx, run) ->
                Flowable.<Object>just(progress())
                    .concatWith(ctx.runNode(grandchild, null).toFlowable()));
    Workflow child = Workflow.builder().name("child").edges(new Edge(START, inner)).build();
    CountingNode caller =
        new CountingNode(
            "caller",
            (ctx, run) ->
                ctx.runNode(child, null, NAMED_RUN)
                    .onErrorResumeNext(error -> ctx.runNode(child, null, NAMED_RUN))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(inner.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("grandchild_out");
  }

  @Test
  public void resume_aNodeThatSucceededOnItsRetry_replaysItsOutputWithoutRunningAgain() {
    CountingNode flaky =
        new CountingNode(
            "flaky", retrying(2), (ctx, run) -> run == 1 ? fail() : Flowable.just("recovered"));
    Node ask = longRunningAsk("ask", "approve_1");
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, flaky),
                new Edge(flaky, ask),
                new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("approve_1", ImmutableMap.of("result", "yes")));

    assertThat(flaky.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/flaky@1").output()).hasValue("recovered");
    assertThat(replyInput(events)).isEqualTo("yes");
  }

  @Test
  public void aChildWithARetryPolicy_isRetriedWithinItsCallersRun() {
    CountingNode child =
        new CountingNode(
            "child", retrying(2), (ctx, run) -> run == 1 ? fail() : Flowable.just("child_out"));
    Node caller = dispatcher("caller", (ctx, input) -> ctx.runNode(child, null).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(child.runs()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("child_out");
  }

  @Test
  public void aNodeWhoseChildFails_isNotRetriedForThatFailure() {
    Node child = throwing("child", new IllegalStateException("child failed"));
    CountingNode caller =
        new CountingNode(
            "caller", retrying(3), (ctx, run) -> ctx.runNode(child, null).toFlowable());

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, caller))).failingTurn(go());

    assertThat(caller.runs()).isEqualTo(1);
    assertThat(errorPaths(turn.events())).containsExactly("wf@1/caller@1/child@1");
  }

  @Test
  public void aRetriedNode_reusesItsNamedChildAndRunsAnUnnamedOneAgain() {
    CountingNode named = new CountingNode("named", (ctx, run) -> Flowable.just("named_out"));
    CountingNode unnamed = new CountingNode("unnamed", (ctx, run) -> Flowable.just("unnamed_out"));
    CountingNode caller =
        new CountingNode(
            "caller",
            retrying(2),
            (ctx, run) ->
                ctx.runNode(named, null, NAMED_RUN)
                    .flatMap(
                        namedOutput ->
                            ctx.runNode(unnamed, null)
                                .map(unnamedOutput -> ImmutableList.of(namedOutput, unnamedOutput)))
                    .flatMap(
                        outputs ->
                            run == 1
                                ? Maybe.<ImmutableList<Object>>error(
                                    new IllegalStateException("first attempt"))
                                : Maybe.just(outputs))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(named.runs()).isEqualTo(1);
    assertThat(unnamed.runs()).isEqualTo(2);
    assertThat(pathsOf(events)).contains("wf@1/caller@1/unnamed@2");
    assertThat(outputEventAt(events, "wf@1/caller@1").output())
        .hasValue(ImmutableList.of("named_out", "unnamed_out"));
  }

  @Test
  public void aRetriedNode_reusesANamedChildThatFinishedWithoutAnOutput() {
    CountingNode named = new CountingNode("named", (ctx, run) -> Flowable.empty());
    CountingNode caller =
        new CountingNode(
            "caller",
            retrying(2),
            (ctx, run) ->
                ctx.runNode(named, null, NAMED_RUN)
                    .ignoreElement()
                    .andThen(run == 1 ? fail() : Flowable.just("done")));

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(named.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("done");
  }

  /**
   * Returns a workflow with a retry policy that forks to {@code done}, {@code stalled} and a node
   * that fails its first run, and joins the three; the failure cancels {@code stalled}.
   */
  private static Workflow forkWithAFlakyBranch(String name, Node done, Node stalled) {
    Node flaky =
        new CountingNode("flaky", (ctx, run) -> run == 1 ? fail() : Flowable.just("recovered"));
    Node join = JoinNode.builder().name("join").build();
    return Workflow.builder()
        .name(name)
        .edges(
            new Edge(START, done),
            new Edge(START, stalled),
            new Edge(START, flaky),
            new Edge(done, join),
            new Edge(stalled, join),
            new Edge(flaky, join))
        .config(retrying(2))
        .build();
  }

  /**
   * Returns a node that reports progress and never finishes on its first run, so the session shows
   * it started, and on later runs outputs its name followed by {@code _out}.
   */
  private static CountingNode stalledOnItsFirstRun(String name) {
    return new CountingNode(
        name,
        (ctx, run) ->
            run == 1
                ? Flowable.<Object>just(progress()).concatWith(Flowable.never())
                : Flowable.just(name + "_out"));
  }

  /** Returns an event that reports progress without an output, so the session shows a run. */
  private static Event progress() {
    return newEvent().content(modelText("working")).build();
  }

  /** Runs one turn of an app rooted on {@code root} whose invocations use {@code scheduler}. */
  private static Flowable<Event> turnOn(Scheduler scheduler, Node root) {
    Runner runner =
        Runner.builder()
            .app(App.builder().name("retry_app").rootNode(root).build())
            .scheduler(scheduler)
            .build();
    String sessionId =
        runner.sessionService().createSession("retry_app", "user").blockingGet().id();
    return runner.runAsync("user", sessionId, go()).timeout(30, SECONDS);
  }

  /** Returns a retry policy of {@code maxAttempts} attempts that never waits between them. */
  private static RetryConfig instantRetry(int maxAttempts) {
    return RetryConfig.builder()
        .maxAttempts(maxAttempts)
        .initialDelay(Duration.ZERO)
        .jitter(0)
        .build();
  }

  private static NodeConfig retrying(int maxAttempts) {
    return NodeConfig.builder().retryConfig(instantRetry(maxAttempts)).build();
  }

  private static Flowable<Object> fail() {
    return Flowable.error(new IllegalStateException("flaky"));
  }

  /** Returns a run that outputs well after any timeout these tests set. */
  private static Flowable<Object> late() {
    return Flowable.timer(5, SECONDS).map(unused -> "late");
  }

  private static ImmutableList<String> errorPaths(List<Event> events) {
    return events.stream()
        .filter(event -> event.errorCode().isPresent())
        .map(WorkflowResumeTesting::pathOf)
        .collect(toImmutableList());
  }

  private static ImmutableList<FinishReason> errorCodes(List<Event> events) {
    return events.stream().flatMap(event -> event.errorCode().stream()).collect(toImmutableList());
  }

  /**
   * A node with {@code config} whose body also receives the number of the run, counting from 1, so
   * a test can vary its runs and count them. It reruns on resume, so it can run child nodes.
   */
  private static final class CountingNode implements Node {
    private final String name;
    private final NodeConfig config;
    private final BiFunction<Context, Integer, Flowable<?>> body;
    private final AtomicInteger runs = new AtomicInteger();

    CountingNode(String name, BiFunction<Context, Integer, Flowable<?>> body) {
      this(name, NodeConfig.builder().build(), body);
    }

    CountingNode(String name, NodeConfig config, BiFunction<Context, Integer, Flowable<?>> body) {
      this.name = name;
      this.config = config;
      this.body = body;
    }

    int runs() {
      return runs.get();
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public NodeConfig config() {
      return config;
    }

    @Override
    public boolean rerunOnResume() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return body.apply(context, runs.incrementAndGet());
    }
  }
}
