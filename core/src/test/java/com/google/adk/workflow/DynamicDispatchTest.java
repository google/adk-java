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
import static com.google.adk.testing.WorkflowTestNodes.echo;
import static com.google.adk.testing.WorkflowTestNodes.emitThenFail;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.interrupting;
import static com.google.adk.testing.WorkflowTestNodes.throwing;
import static com.google.adk.testing.WorkflowTestNodes.waitingForOutput;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Context;
import com.google.adk.events.Event;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.MaybeSubject;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the runtime that runs a child node from a workflow node ({@link NodeRunner#runChild}):
 * output delegation, failure paths and interrupts, checked on the events and node state of each
 * run.
 */
@RunWith(JUnit4.class)
public final class DynamicDispatchTest {

  private static final Start START = Start.INSTANCE;
  private static final RunNodeConfig DEFAULTS = RunNodeConfig.builder().build();
  private static final RunNodeConfig USE_AS_OUTPUT =
      RunNodeConfig.builder().useAsOutput(true).build();
  private static final RunNodeConfig RAISE_ON_WAIT =
      RunNodeConfig.builder().raiseOnWait(true).build();

  // Children that run concurrently send their events on several threads.
  private final Queue<Event> events = new ConcurrentLinkedQueue<>();
  private final NodeRunner.EventSink recordingSink =
      (event, nodeFailure) -> Completable.fromAction(() -> events.add(event));

  @Test
  public void runChild_concurrentRuns_getDistinctRunIdsAndTheirOwnOutputs() {
    int count = 64;
    Node child = echo("child", "echo");
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                Flowable.range(1, count)
                    .concatMapEager(
                        i ->
                            NodeRunner.runChild(ctx, child, i, DEFAULTS)
                                .subscribeOn(Schedulers.io())
                                .toFlowable())
                    .toList()
                    .toFlowable());

    run(workflow(new Edge(START, dispatcher)));

    assertThat(pathsContaining("/child@"))
        .containsExactlyElementsIn(
            IntStream.rangeClosed(1, count)
                .mapToObj(i -> "wf@1/dispatcher@1/child@" + i)
                .collect(toImmutableList()));
    assertThat(outputEventAt("wf@1/dispatcher@1").output())
        .hasValue(
            IntStream.rangeClosed(1, count).mapToObj(i -> "echo:" + i).collect(toImmutableList()));
  }

  @Test
  public void runChild_useAsOutputOnATerminalNode_countsTheOutputForEveryLevel() {
    Node step1 = emitter("step_1", "step_1_done");
    Node step2 = echo("step_2", "final");
    Workflow inner = workflow("inner_wf", new Edge(START, step1), new Edge(step1, step2));
    Node nodeA = delegating("node_a", inner);

    Context context = run(workflow(new Edge(START, nodeA)));

    ImmutableList<Event> outputEvents =
        recorded().stream().filter(event -> event.output().isPresent()).collect(toImmutableList());
    assertThat(paths(outputEvents))
        .containsExactly("wf@1/node_a@1/inner_wf@1/step_1@1", "wf@1/node_a@1/inner_wf@1/step_2@1")
        .inOrder();
    assertThat(outputFor(outputEvents.get(0))).containsExactly("wf@1/node_a@1/inner_wf@1/step_1@1");
    assertThat(outputFor(outputEvents.get(1)))
        .containsExactly(
            "wf@1/node_a@1/inner_wf@1/step_2@1",
            "wf@1/node_a@1/inner_wf@1",
            "wf@1/node_a@1",
            "wf@1")
        .inOrder();
    assertThat(context.requireNodeState().output()).hasValue("final:step_1_done");
  }

  @Test
  public void runChild_useAsOutputFromAWorkflowsContext_delegatesNothing() {
    Node helper =
        new TestNode(
            "helper",
            (ctx, input) ->
                NodeRunner.runChild(
                        workflowContext(ctx), emitter("first", "A"), null, USE_AS_OUTPUT)
                    .zipWith(
                        NodeRunner.runChild(
                            workflowContext(ctx), emitter("second", "B"), null, USE_AS_OUTPUT),
                        ImmutableList::of)
                    .toFlowable());

    run(workflow(new Edge(START, helper)));

    assertThat(outputEventAt("wf@1/helper@1").output()).hasValue(ImmutableList.of("A", "B"));
  }

  @Test
  public void runChild_secondUseAsOutput_failsTheCaller() {
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                NodeRunner.runChild(ctx, emitter("first", "A"), null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .andThen(
                        NodeRunner.runChild(ctx, emitter("second", "B"), null, USE_AS_OUTPUT)
                            .ignoreElement())
                    .toFlowable());

    Exception failure = runToFailure(workflow(new Edge(START, dispatcher)));

    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(failure).hasMessageThat().contains("already delegated its output");
  }

  @Test
  public void runChild_useAsOutputThenCallerProducesOutputOnItsState_dropsIt() {
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                NodeRunner.runChild(ctx, emitter("child", "CHILD_OUT"), null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .andThen(
                        Completable.fromAction(() -> ctx.requireNodeState().produceOutput("OWN")))
                    .toFlowable());

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().output()).hasValue("CHILD_OUT");
    assertThat(outputs()).doesNotContain("OWN");
  }

  @Test
  public void runChild_useAsOutputAfterTheCallerProducedAnOutput_failsTheCaller() {
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                Completable.fromAction(() -> ctx.requireNodeState().produceOutput("OWN"))
                    .andThen(
                        NodeRunner.runChild(
                            ctx, emitter("child", "CHILD_OUT"), null, USE_AS_OUTPUT))
                    .ignoreElement()
                    .toFlowable());

    Exception failure = runToFailure(workflow(new Edge(START, dispatcher)));

    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(failure)
        .hasMessageThat()
        .contains("already produced an output and cannot delegate it");
  }

  @Test
  public void runChild_failingChild_recordsTheChildsPathAsWhereTheCallerFailed() {
    IllegalStateException childFailure = new IllegalStateException("child boom");
    Node dispatcher = returning("dispatcher", throwing("child", childFailure), DEFAULTS);

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).hasValue(childFailure);
    assertThat(context.requireNodeState().failureNodePath()).hasValue("wf@1/dispatcher@1/child@1");
  }

  @Test
  public void runChild_delegateWithARejectedName_letsTheCallerProduceItsOwnOutput() {
    Node dispatcher =
        handling(
            "dispatcher",
            emitter("a/b", "C"),
            USE_AS_OUTPUT,
            IllegalArgumentException.class,
            "FALLBACK");

    run(workflow(new Edge(START, dispatcher)));

    assertThat(outputEventAt("wf@1/dispatcher@1").output()).hasValue("FALLBACK");
  }

  @Test
  public void runChild_delegateThatFailsBeforeEmittingItsOutput_letsTheCallerProduceItsOwn() {
    Node child =
        new TestNode(
            "child",
            (ctx, input) -> {
              ctx.requireNodeState().produceOutput("CHILD_OUT");
              return Flowable.error(new IllegalStateException("failed before emitting"));
            });
    Node dispatcher =
        handling("dispatcher", child, USE_AS_OUTPUT, DynamicNodeFailedException.class, "FALLBACK");
    Node after = echo("after", "after");

    run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    assertThat(outputEventAt("wf@1/after@1").output()).hasValue("after:FALLBACK");
    assertThat(outputs()).doesNotContain("CHILD_OUT");
  }

  @Test
  public void runChild_delegateThatFailsBeforeEmitting_letsTheCallerDelegateAgain() {
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                NodeRunner.runChild(
                        ctx,
                        throwing("first", new IllegalStateException("first failed")),
                        null,
                        USE_AS_OUTPUT)
                    .ignoreElement()
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException
                                ? NodeRunner.runChild(
                                        ctx, emitter("second", "SECOND"), null, USE_AS_OUTPUT)
                                    .ignoreElement()
                                : Completable.error(error))
                    .toFlowable());
    Node after = echo("after", "after");

    run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    assertThat(outputEventAt("wf@1/after@1").output()).hasValue("after:SECOND");
  }

  @Test
  public void runChild_delegateCanceledBeforeEmitting_keepsTheDelegationAndDropsTheFallback() {
    MaybeSubject<Boolean> cancel = MaybeSubject.create();
    Node child =
        new TestNode(
            "child",
            (ctx, input) ->
                Completable.fromAction(() -> cancel.onSuccess(true)).andThen(Flowable.never()));
    Node dispatcher = cancelingAndFallingBack("dispatcher", child, cancel);

    Context context = run(workflow(new Edge(START, dispatcher)));

    // Only a failed delegate releases the delegation, so the caller ends without an output.
    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().output()).isEmpty();
    assertThat(outputs()).doesNotContain("FALLBACK");
  }

  @Test
  public void runChild_delegateCanceledAfterEmittingItsOutput_keepsThatOutputAsTheCallers() {
    MaybeSubject<Boolean> cancel = MaybeSubject.create();
    Node child =
        new TestNode(
            "child",
            (ctx, input) ->
                Flowable.<Object>just("CHILD_OUT")
                    .concatWith(Completable.fromAction(() -> cancel.onSuccess(true)))
                    .concatWith(Flowable.never()));
    Node dispatcher = cancelingAndFallingBack("dispatcher", child, cancel);

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().output()).hasValue("CHILD_OUT");
    assertThat(eventAt("wf@1/dispatcher@1/child@1").output()).hasValue("CHILD_OUT");
    assertThat(outputs()).doesNotContain("FALLBACK");
  }

  @Test
  public void runChild_delegateThatFailsAfterEmittingItsOutput_dropsTheCallersFallback() {
    Node dispatcher =
        handling(
            "dispatcher",
            emitThenFail("child", "CHILD_OUT"),
            USE_AS_OUTPUT,
            DynamicNodeFailedException.class,
            "FALLBACK");

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().output()).hasValue("CHILD_OUT");
    assertThat(outputs()).doesNotContain("FALLBACK");
  }

  @Test
  public void runChild_delegateWorkflowFailingAfterItsOutput_dropsTheCallersFallback() {
    Workflow inner = workflow("inner_wf", new Edge(START, emitThenFail("term", "CHILD_OUT")));
    Node dispatcher =
        handling("dispatcher", inner, USE_AS_OUTPUT, DynamicNodeFailedException.class, "FALLBACK");

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().output()).hasValue("CHILD_OUT");
    assertThat(outputs()).doesNotContain("FALLBACK");
  }

  @Test
  public void runChild_workflowChildWhoseNodeFails_recordsThatNodesPathAsWhereTheCallerFailed() {
    Workflow inner =
        workflow(
            "inner_wf", new Edge(START, throwing("leaf", new IllegalStateException("leaf boom"))));
    Node dispatcher = returning("dispatcher", inner, DEFAULTS);

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failureNodePath())
        .hasValue("wf@1/dispatcher@1/inner_wf@1/leaf@1");
  }

  @Test
  public void runChild_delegateThatFailsAfterEmittingItsOutput_keepsThatOutputAsTheCallers() {
    Node dispatcher =
        handling(
            "dispatcher",
            emitThenFail("child", "CHILD_OUT"),
            USE_AS_OUTPUT,
            DynamicNodeFailedException.class,
            /* fallback= */ null);

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().output()).hasValue("CHILD_OUT");
  }

  @Test
  public void runChild_childWaitingOnAnInterrupt_leavesTheCallerWaiting() {
    Node dispatcher = returning("dispatcher", interrupting("child", "ask"), DEFAULTS);
    Node after = emitter("after", "AFTER");

    Context context = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    assertThat(eventAt("wf@1/dispatcher@1/child@1").longRunningToolIds())
        .hasValue(ImmutableSet.of("ask"));
    assertThat(context.requireNodeState().interruptIds()).containsExactly("ask");
    assertThat(eventsAt("wf@1/after@1")).isEmpty();
  }

  @Test
  public void runChild_callerCatchingTheWaitSignal_keepsRunningButStillWaits() {
    Node dispatcher =
        handling("dispatcher", interrupting("child", "ask"), DEFAULTS, RuntimeException.class, "X");
    Node after = emitter("after", "AFTER");

    Context context = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    assertThat(outputEventAt("wf@1/dispatcher@1").output()).hasValue("X");
    assertThat(context.requireNodeState().interruptIds()).containsExactly("ask");
    assertThat(eventsAt("wf@1/after@1")).isEmpty();
  }

  @Test
  public void runChild_childInterruptingOnTheWorkflowsContext_keepsTheWorkflowWithoutAnOutput() {
    Node helper =
        new TestNode(
            "helper",
            (ctx, input) ->
                NodeRunner.runChild(
                        workflowContext(ctx), interrupting("child", "ask"), null, DEFAULTS)
                    .toFlowable());
    Node after = emitter("after", "AFTER");

    Context context = run(workflow(new Edge(START, helper), new Edge(helper, after)));

    assertThat(outputEventAt("wf@1/after@1").output()).hasValue("AFTER");
    assertThat(context.requireNodeState().interruptIds()).containsExactly("ask");
    assertThat(context.requireNodeState().output()).isEmpty();
  }

  @Test
  public void runChild_raiseOnWaitWithAWaitForOutputChildWithoutAnOutput_endsTheCallersRun() {
    Node dispatcher = continuing("dispatcher", waitingForOutput("child"), RAISE_ON_WAIT);

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().failure()).isEmpty();
    assertThat(context.requireNodeState().interruptIds()).isEmpty();
    assertThat(context.requireNodeState().output()).isEmpty();
    assertThat(outputs()).doesNotContain("AFTER_WAIT");
  }

  @Test
  public void runChild_raiseOnWaitWithAChildThatHasAnOutput_letsTheCallerCarryOn() {
    Node dispatcher = continuing("dispatcher", emitter("child", "C"), RAISE_ON_WAIT);

    Context context = run(workflow(new Edge(START, dispatcher)));

    assertThat(context.requireNodeState().output()).hasValue("AFTER_WAIT");
  }

  @Test
  public void runChild_onAContextThatIsNotAWorkflowNodes_fails() {
    Context context =
        new CallbackContext(
            TestUtils.createInvocationContext(TestUtils.createRootAgent()),
            /* eventActions= */ null);

    Maybe<Object> result = NodeRunner.runChild(context, emitter("child", "C"), null, DEFAULTS);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, result::blockingGet);

    assertThat(thrown).hasMessageThat().contains("workflow node");
  }

  /** Runs {@code root} at the root of an invocation and returns its context. */
  @CanIgnoreReturnValue
  private Context run(Node root) {
    return NodeRunner.builder()
        .node(root)
        .invocationContext(TestUtils.createInvocationContext(TestUtils.createRootAgent()))
        .eventSink(recordingSink)
        .build()
        .run(/* nodeInput= */ null)
        .timeout(30, TimeUnit.SECONDS)
        .blockingGet();
  }

  /** Runs {@code root} like {@link #run} and returns the exception it failed with. */
  private Exception runToFailure(Node root) {
    Optional<Exception> failure = run(root).requireNodeState().failure();
    assertThat(failure).isPresent();
    return failure.get();
  }

  private ImmutableList<Event> recorded() {
    return ImmutableList.copyOf(events);
  }

  private Event eventAt(String nodePath) {
    return eventsAt(nodePath).stream().collect(onlyElement());
  }

  private ImmutableList<Event> eventsAt(String nodePath) {
    return recorded().stream()
        .filter(event -> pathOf(event).equals(nodePath))
        .collect(toImmutableList());
  }

  private Event outputEventAt(String nodePath) {
    return outputEventsAt(nodePath).stream().collect(onlyElement());
  }

  private ImmutableList<Event> outputEventsAt(String nodePath) {
    return eventsAt(nodePath).stream()
        .filter(event -> event.output().isPresent())
        .collect(toImmutableList());
  }

  /** Returns the paths of the recorded events that contain {@code fragment}, in event order. */
  private ImmutableList<String> pathsContaining(String fragment) {
    return recorded().stream()
        .map(DynamicDispatchTest::pathOf)
        .filter(path -> path.contains(fragment))
        .collect(toImmutableList());
  }

  private ImmutableList<Object> outputs() {
    return recorded().stream().flatMap(event -> event.output().stream()).collect(toImmutableList());
  }

  private static String pathOf(Event event) {
    return event.nodeInfo().map(NodeInfo::path).orElse("");
  }

  private static ImmutableList<String> paths(List<Event> events) {
    return events.stream().map(DynamicDispatchTest::pathOf).collect(toImmutableList());
  }

  private static ImmutableList<String> outputFor(Event event) {
    return event.nodeInfo().map(NodeInfo::outputFor).orElse(ImmutableList.of());
  }

  /** Returns the context of the workflow that runs the node whose context is {@code context}. */
  private static Context workflowContext(Context context) {
    return context.requireNodeState().parent().orElseThrow();
  }

  /** Returns a node that runs {@code child} with {@code config} and emits the child's output. */
  private static Node returning(String name, Node child, RunNodeConfig config) {
    return dispatcher(
        name, (ctx, input) -> NodeRunner.runChild(ctx, child, null, config).toFlowable());
  }

  /** Returns a node that delegates its output to {@code child} and emits nothing itself. */
  private static Node delegating(String name, Node child) {
    return dispatcher(
        name,
        (ctx, input) ->
            NodeRunner.runChild(ctx, child, null, USE_AS_OUTPUT).ignoreElement().toFlowable());
  }

  /** Returns a node that runs {@code child} with {@code config}, then emits {@code AFTER_WAIT}. */
  private static Node continuing(String name, Node child, RunNodeConfig config) {
    return dispatcher(
        name,
        (ctx, input) ->
            NodeRunner.runChild(ctx, child, null, config)
                .ignoreElement()
                .andThen(Flowable.just("AFTER_WAIT")));
  }

  /**
   * Returns a node that runs {@code child} with {@code config} and emits the child's output, or
   * emits {@code fallback}, if not null, when the run fails with a {@code handled} exception.
   */
  private static Node handling(
      String name,
      Node child,
      RunNodeConfig config,
      Class<? extends Exception> handled,
      @Nullable Object fallback) {
    return dispatcher(
        name,
        (ctx, input) ->
            NodeRunner.runChild(ctx, child, null, config)
                .onErrorResumeNext(
                    error ->
                        handled.isInstance(error)
                            ? Maybe.fromOptional(Optional.ofNullable(fallback))
                            : Maybe.error(error))
                .toFlowable());
  }

  /**
   * Returns a node that delegates its output to {@code child}, stops waiting for it once {@code
   * cancel} succeeds, and then emits {@code FALLBACK}.
   */
  private static Node cancelingAndFallingBack(String name, Node child, MaybeSubject<?> cancel) {
    return dispatcher(
        name,
        (ctx, input) ->
            NodeRunner.runChild(ctx, child, null, USE_AS_OUTPUT)
                .takeUntil(cancel)
                .defaultIfEmpty("FALLBACK")
                .toFlowable());
  }
}
