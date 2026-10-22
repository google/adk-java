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
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.adk.agents.Context;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for workflow nodes that run child nodes with {@link Context#runNode}, run end to end
 * through a {@link Runner} and checked on the events it streams and the session it stores.
 */
@RunWith(JUnit4.class)
public final class DynamicWorkflowTest {

  private static final String APP = "dynamic_app";
  private static final String USER = "user";
  private static final Start START = Start.INSTANCE;
  private static final RunNodeConfig USE_AS_OUTPUT =
      RunNodeConfig.builder().useAsOutput(true).build();
  private static final RunNodeConfig RAISE_ON_WAIT =
      RunNodeConfig.builder().raiseOnWait(true).build();

  @Test
  public void runNode_withAnInput_runsTheChildOnItAndReturnsItsOutput() {
    Node dispatcher =
        dispatcher(
            "dispatcher", (ctx, input) -> ctx.runNode(echo("child", "echo"), "in").toFlowable());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    Event outputEvent = result.outputEventAt("wf@1/dispatcher@1");
    assertThat(outputEvent.output()).hasValue("echo:in");
    assertThat(outputFor(outputEvent)).containsExactly("wf@1/dispatcher@1", "wf@1").inOrder();
  }

  @Test
  public void runNode_withoutAnInput_runsTheChildWithoutOne() {
    Node dispatcher =
        dispatcher("dispatcher", (ctx, input) -> ctx.runNode(echo("child", "echo")).toFlowable());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output()).hasValue("echo:null");
  }

  @Test
  public void runNode_withARunId_namesTheChildsRun() {
    Node dispatcher =
        returning(
            "dispatcher", echo("child", "echo"), RunNodeConfig.builder().runId("custom").build());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/dispatcher@1/child@custom").output())
        .hasValue("echo:null");
  }

  @Test
  public void runNode_withoutARunId_numbersTheRunsOfEachChildNameSeparately() {
    Node a = echo("a", "a");
    Node b = echo("b", "b");
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                Maybe.concat(ImmutableList.of(ctx.runNode(a), ctx.runNode(b), ctx.runNode(a)))
                    .toList()
                    .toFlowable());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.pathsContaining("/dispatcher@1/"))
        .containsExactly("wf@1/dispatcher@1/a@1", "wf@1/dispatcher@1/b@1", "wf@1/dispatcher@1/a@2")
        .inOrder();
  }

  @Test
  public void runNode_concurrentChildren_getDistinctRunIdsAndTheirOwnOutputs() {
    int count = 16;
    Node child = echo("child", "echo");
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                Flowable.range(1, count)
                    .concatMapEager(
                        i -> ctx.runNode(child, i).subscribeOn(Schedulers.io()).toFlowable())
                    .toList()
                    .toFlowable());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.pathsContaining("/child@"))
        .containsExactlyElementsIn(
            IntStream.rangeClosed(1, count)
                .mapToObj(i -> "wf@1/dispatcher@1/child@" + i)
                .collect(toImmutableList()));
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output())
        .hasValue(
            IntStream.rangeClosed(1, count).mapToObj(i -> "echo:" + i).collect(toImmutableList()));
  }

  @Test
  public void runNode_childWithTheNameOfAGraphNode_continuesItsRunNumbering() {
    Node coordinator = echo("coordinator", "graph");
    Node helper =
        new TestNode(
            "helper",
            (ctx, input) ->
                workflowContext(ctx).runNode(echo("coordinator", "dynamic"), input).toFlowable());

    RunResult result = run(workflow(new Edge(START, coordinator), new Edge(coordinator, helper)));

    result.assertCompleted();
    assertThat(
            result.pathsContaining("/coordinator@").stream().distinct().collect(toImmutableList()))
        .containsExactly("wf@1/coordinator@1", "wf@1/coordinator@2")
        .inOrder();
  }

  @Test
  public void runNode_fromARootNodeWithoutAWorkflow_runsTheChild() {
    Node dispatcher =
        dispatcher(
            "dispatcher", (ctx, input) -> ctx.runNode(echo("child", "echo"), "in").toFlowable());

    RunResult result = run(dispatcher);

    result.assertCompleted();
    assertThat(result.outputEventAt("dispatcher@1/child@1").output()).hasValue("echo:in");
    assertThat(result.outputEventAt("dispatcher@1").output()).hasValue("echo:in");
  }

  @Test
  public void runNode_useSubBranch_runsTheChildOnASubBranch() {
    Node dispatcher =
        returning(
            "dispatcher",
            emitter("child", "C"),
            RunNodeConfig.builder().useSubBranch(true).build());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.eventAt("wf@1/dispatcher@1/child@1").branch()).hasValue("child@1");
  }

  @Test
  public void runNode_overrideBranch_replacesTheChildsBranch() {
    Node dispatcher =
        returning(
            "dispatcher",
            emitter("child", "C"),
            RunNodeConfig.builder().overrideBranch("tool.call@1").build());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.eventAt("wf@1/dispatcher@1/child@1").branch()).hasValue("tool.call@1");
  }

  @Test
  public void runNode_overrideBranchWithUseSubBranch_runsTheChildOnASubBranchOfIt() {
    Node dispatcher =
        returning(
            "dispatcher",
            emitter("child", "C"),
            RunNodeConfig.builder().overrideBranch("tool.call@1").useSubBranch(true).build());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.eventAt("wf@1/dispatcher@1/child@1").branch())
        .hasValue("tool.call@1.child@1");
  }

  @Test
  public void runNode_useAsOutput_storesOnlyTheChildsOutputEventAndHandsItToTheSuccessor() {
    Node dispatcher = delegating("dispatcher", echo("child", "echo"), "in");
    Node after = echo("after", "after");

    RunResult result = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    result.assertCompleted();
    ImmutableList<Event> outputEvents = result.storedOutputEvents();
    assertThat(paths(outputEvents))
        .containsExactly("wf@1/dispatcher@1/child@1", "wf@1/after@1")
        .inOrder();
    assertThat(outputFor(outputEvents.get(0)))
        .containsExactly("wf@1/dispatcher@1/child@1", "wf@1/dispatcher@1")
        .inOrder();
    assertThat(outputEvents.get(1).output()).hasValue("after:echo:in");
  }

  @Test
  public void runNode_useAsOutputChildThatProducesItsOutputOnItsState_countsItForTheCaller() {
    Node child =
        new TestNode(
            "child",
            (ctx, input) -> {
              ctx.requireNodeState().produceOutput("CHILD_OUT");
              return Flowable.empty();
            });
    Node dispatcher = delegating("dispatcher", child, null);

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    Event childEvent = result.outputEventAt("wf@1/dispatcher@1/child@1");
    assertThat(childEvent.output()).hasValue("CHILD_OUT");
    assertThat(outputFor(childEvent))
        .containsExactly("wf@1/dispatcher@1/child@1", "wf@1/dispatcher@1", "wf@1")
        .inOrder();
  }

  @Test
  public void runNode_useAsOutputThroughANestedWorkflow_reachesTheCallersSuccessor() {
    Node step1 = emitter("step_1", "step_1_done");
    Node step2 = echo("step_2", "final");
    Workflow inner = workflow("inner_wf", new Edge(START, step1), new Edge(step1, step2));
    Node nodeA = delegating("node_a", inner, null);
    Node after = echo("after", "after");

    RunResult result = run(workflow(new Edge(START, nodeA), new Edge(nodeA, after)));

    result.assertCompleted();
    ImmutableList<Event> outputEvents = result.storedOutputEvents();
    assertThat(paths(outputEvents))
        .containsExactly(
            "wf@1/node_a@1/inner_wf@1/step_1@1",
            "wf@1/node_a@1/inner_wf@1/step_2@1",
            "wf@1/after@1")
        .inOrder();
    assertThat(outputFor(outputEvents.get(1)))
        .containsExactly(
            "wf@1/node_a@1/inner_wf@1/step_2@1", "wf@1/node_a@1/inner_wf@1", "wf@1/node_a@1")
        .inOrder();
    assertThat(outputEvents.get(2).output()).hasValue("after:final:step_1_done");
  }

  @Test
  public void runNode_useAsOutputThenCallerEmitsTheChildsOutput_failsTheRun() {
    Node dispatcher = returning("dispatcher", emitter("child", "CHILD_OUT"), USE_AS_OUTPUT);

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    assertThat(result.error()).isInstanceOf(IllegalStateException.class);
    assertThat(result.error()).hasMessageThat().contains("delegated its output");
  }

  @Test
  public void runNode_useAsOutputChildWithoutAnOutputThenCallerEmits_failsTheRun() {
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(TestNode.of("child"), null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .andThen(Flowable.just("OWN")));

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    assertThat(result.error()).isInstanceOf(IllegalStateException.class);
    assertThat(result.error()).hasMessageThat().contains("delegated its output");
  }

  @Test
  public void runNode_useAsOutputThenCallerEmitsAMessageAsOutput_failsTheRun() {
    Event ownMessage =
        newEvent()
            .content(Content.fromParts(Part.fromText("own text")))
            .nodeInfo(NodeInfo.builder().messageAsOutput(true).build())
            .build();
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(emitter("child", "CHILD_OUT"), null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .andThen(Flowable.just(ownMessage)));

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    assertThat(result.error()).isInstanceOf(IllegalStateException.class);
    assertThat(result.error()).hasMessageThat().contains("delegated its output");
  }

  @Test
  public void runNode_useAsOutputThenCallerWritesState_storesTheStateChange() {
    Event stateChange =
        newEvent()
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("written", "yes")).build())
            .build();
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(emitter("child", "CHILD_OUT"), null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .andThen(Flowable.just(stateChange)));

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.session().state()).containsEntry("written", "yes");
    assertThat(result.eventAt("wf@1/dispatcher@1").output()).isEmpty();
  }

  @Test
  public void runNode_delegateThatFails_letsTheCallerFallBackForItsSuccessor() {
    Node dispatcher =
        handling(
            "dispatcher",
            throwing("child", new IllegalStateException("child boom")),
            USE_AS_OUTPUT,
            "FALLBACK");
    Node after = echo("after", "after");

    RunResult result = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/after@1").output()).hasValue("after:FALLBACK");
  }

  @Test
  public void runNode_delegateThatFailsAfterEmittingItsOutput_handsThatOutputToTheSuccessor() {
    Node dispatcher =
        handling("dispatcher", emitThenFail("child", "CHILD_OUT"), USE_AS_OUTPUT, null);
    Node after = echo("after", "after");

    RunResult result = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/after@1").output()).hasValue("after:CHILD_OUT");
  }

  @Test
  public void runNode_delegateWorkflowFailingAfterItsOutput_handsThatOutputToTheSuccessor() {
    Workflow inner = workflow("inner_wf", new Edge(START, emitThenFail("term", "CHILD_OUT")));
    Node dispatcher = handling("dispatcher", inner, USE_AS_OUTPUT, null);
    Node after = echo("after", "after");

    RunResult result = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/after@1").output()).hasValue("after:CHILD_OUT");
  }

  @Test
  public void runNode_failingChild_failsTheRunWithTheChildsErrorAndOnlyItsErrorEvent() {
    IllegalStateException childFailure = new IllegalStateException("child boom");
    Node dispatcher = returning("dispatcher", throwing("child", childFailure), null);

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    assertThat(result.error()).isSameInstanceAs(childFailure);
    assertThat(
            paths(
                result.events().stream()
                    .filter(event -> event.errorCode().isPresent())
                    .collect(toImmutableList())))
        .containsExactly("wf@1/dispatcher@1/child@1");
  }

  @Test
  public void runNode_callerCatchingTheChildsFailure_getsItsErrorAndPath() {
    Node reporter =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(throwing("child", new IllegalStateException("child boom")), null)
                    .toFlowable()
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException failed
                                ? Flowable.just(
                                    failed.errorNodePath() + ": " + failed.error().getMessage())
                                : Flowable.error(error)));

    RunResult result = run(workflow(new Edge(START, reporter)));

    result.assertCompleted();
    assertThat(result.eventAt("wf@1/dispatcher@1/child@1").errorCode()).isPresent();
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output())
        .hasValue("wf@1/dispatcher@1/child@1: child boom");
  }

  @Test
  public void runNode_workflowChildWhoseNodeFails_reportsThatNodesPath() {
    Workflow inner =
        workflow(
            "inner_wf", new Edge(START, throwing("leaf", new IllegalStateException("leaf boom"))));
    Node reporter =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(inner, null)
                    .toFlowable()
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException failed
                                ? Flowable.just(failed.errorNodePath())
                                : Flowable.error(error)));

    RunResult result = run(workflow(new Edge(START, reporter)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output())
        .hasValue("wf@1/dispatcher@1/inner_wf@1/leaf@1");
  }

  @Test
  public void runNode_callerThatDoesNotRerunOnResume_failsTheRun() {
    Node dispatcher =
        new TestNode("dispatcher", (ctx, input) -> ctx.runNode(emitter("child", "C")).toFlowable());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    assertThat(result.error()).isInstanceOf(IllegalStateException.class);
    assertThat(result.error()).hasMessageThat().contains("must rerun on resume");
  }

  @Test
  public void runNode_childWithAnEmptyName_failsTheRun() {
    assertChildNameFailsTheRun("");
  }

  @Test
  public void runNode_childNameWithASlash_failsTheRun() {
    assertChildNameFailsTheRun("a/b");
  }

  @Test
  public void runNode_childNameWithAnAtSign_failsTheRun() {
    assertChildNameFailsTheRun("a@b");
  }

  @Test
  public void runNode_childNameWithADot_failsTheRun() {
    assertChildNameFailsTheRun("a.b");
  }

  @Test
  public void runNode_childWaitingOnAnInterrupt_pausesTheRunBeforeTheCallersSuccessor() {
    Node dispatcher = returning("dispatcher", interrupting("child", "ask"), null);
    Node after = emitter("after", "AFTER");

    RunResult result = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, after)));

    result.assertCompleted();
    assertThat(result.eventAt("wf@1/dispatcher@1/child@1").longRunningToolIds())
        .hasValue(ImmutableSet.of("ask"));
    assertThat(result.pathsContaining("/after@")).isEmpty();
    assertThat(result.outputEventsAt("wf@1/dispatcher@1")).isEmpty();
  }

  @Test
  public void runNode_waitForOutputChildWithoutAnOutput_completesEmpty() {
    Node dispatcher =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(waitingForOutput("child"), null)
                    .map(output -> "OUTPUT")
                    .defaultIfEmpty("EMPTY")
                    .toFlowable());

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output()).hasValue("EMPTY");
  }

  @Test
  public void runNode_raiseOnWaitWithAWorkflowChildWithoutAnOutput_endsTheCallersRun() {
    Workflow inner = workflow("inner_wf", new Edge(START, TestNode.of("silent")));
    Node dispatcher = continuing("dispatcher", inner, RAISE_ON_WAIT);

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.outputs()).doesNotContain("AFTER_WAIT");
    assertThat(paths(result.storedOutputEvents())).doesNotContain("wf@1/dispatcher@1");
  }

  @Test
  public void runNode_raiseOnWaitWithAWaitForOutputChildWithoutAnOutput_endsTheCallersRun() {
    Node dispatcher = continuing("dispatcher", waitingForOutput("child"), RAISE_ON_WAIT);

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.outputs()).doesNotContain("AFTER_WAIT");
    assertThat(paths(result.storedOutputEvents())).doesNotContain("wf@1/dispatcher@1");
  }

  @Test
  public void runNode_raiseOnWaitWithAChildThatHasAnOutput_letsTheCallerCarryOn() {
    Node dispatcher = continuing("dispatcher", emitter("child", "C"), RAISE_ON_WAIT);

    RunResult result = run(workflow(new Edge(START, dispatcher)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output()).hasValue("AFTER_WAIT");
  }

  @Test
  public void runNode_childAskingToSkipSummarization_setsItOnTheCallersContext() {
    Node child =
        new TestNode(
            "child",
            (ctx, input) -> {
              ctx.eventActions().setSkipSummarization(true);
              return Flowable.just("C");
            });
    Node probe =
        dispatcher(
            "dispatcher",
            (ctx, input) ->
                ctx.runNode(child, null)
                    .ignoreElement()
                    .andThen(
                        Flowable.defer(
                            () ->
                                Flowable.just(
                                    ctx.eventActions().skipSummarization().orElse(false)))));

    RunResult result = run(workflow(new Edge(START, probe)));

    result.assertCompleted();
    assertThat(result.outputEventAt("wf@1/dispatcher@1").output()).hasValue(true);
  }

  @Test
  public void runNode_childStateChange_isStoredAndReachesTheCallersSuccessor() {
    Node writer =
        new TestNode(
            "writer",
            (ctx, input) -> {
              ctx.state().put("color", "v");
              return Flowable.just("done");
            });
    Node dispatcher = returning("dispatcher", writer, null);
    Node reader =
        new TestNode(
            "reader",
            (ctx, input) -> Flowable.fromOptional(Optional.ofNullable(ctx.state().get("color"))));

    RunResult result = run(workflow(new Edge(START, dispatcher), new Edge(dispatcher, reader)));

    result.assertCompleted();
    assertThat(result.session().state()).containsEntry("color", "v");
    assertThat(result.storedEventAt("wf@1/dispatcher@1/writer@1").actions().stateDelta())
        .containsEntry("color", "v");
    assertThat(result.outputEventAt("wf@1/reader@1").output()).hasValue("v");
  }

  /** Runs a child named {@code name} and checks that the node-name check fails the run. */
  private static void assertChildNameFailsTheRun(String name) {
    RunResult result =
        run(workflow(new Edge(START, returning("dispatcher", emitter(name, "C"), null))));

    assertThat(result.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(result.error()).hasMessageThat().contains("node name");
  }

  /** Runs {@code root} as an app's root node through a {@link Runner}. */
  private static RunResult run(Node root) {
    Runner runner = Runner.builder().app(App.builder().name(APP).rootNode(root).build()).build();
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    List<Event> events = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    runner
        .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
        .timeout(30, TimeUnit.SECONDS)
        .blockingSubscribe(events::add, error::set);
    Session stored =
        runner.sessionService().getSession(APP, USER, session.id(), Optional.empty()).blockingGet();
    return new RunResult(ImmutableList.copyOf(events), error.get(), stored);
  }

  /** The events a run streamed, the error it failed with, if any, and the session it stored. */
  private record RunResult(
      ImmutableList<Event> events, @Nullable Throwable error, Session session) {

    void assertCompleted() {
      assertWithMessage("the run's error").that(error).isNull();
    }

    Event eventAt(String nodePath) {
      return events.stream().filter(event -> pathOf(event).equals(nodePath)).collect(onlyElement());
    }

    Event outputEventAt(String nodePath) {
      return outputEventsAt(nodePath).stream().collect(onlyElement());
    }

    ImmutableList<Event> outputEventsAt(String nodePath) {
      return events.stream()
          .filter(event -> pathOf(event).equals(nodePath) && event.output().isPresent())
          .collect(toImmutableList());
    }

    /** Returns the paths of the streamed events that contain {@code fragment}, in event order. */
    ImmutableList<String> pathsContaining(String fragment) {
      return events.stream()
          .map(DynamicWorkflowTest::pathOf)
          .filter(path -> path.contains(fragment))
          .collect(toImmutableList());
    }

    ImmutableList<Object> outputs() {
      return events.stream().flatMap(event -> event.output().stream()).collect(toImmutableList());
    }

    Event storedEventAt(String nodePath) {
      return session.immutableEvents().stream()
          .filter(event -> pathOf(event).equals(nodePath))
          .collect(onlyElement());
    }

    ImmutableList<Event> storedOutputEvents() {
      return session.immutableEvents().stream()
          .filter(event -> event.output().isPresent())
          .collect(toImmutableList());
    }
  }

  private static String pathOf(Event event) {
    return event.nodeInfo().map(NodeInfo::path).orElse("");
  }

  private static ImmutableList<String> paths(ImmutableList<Event> events) {
    return events.stream().map(DynamicWorkflowTest::pathOf).collect(toImmutableList());
  }

  private static ImmutableList<String> outputFor(Event event) {
    return event.nodeInfo().map(NodeInfo::outputFor).orElse(ImmutableList.of());
  }

  /** Returns the context of the workflow that runs the node whose context is {@code context}. */
  private static Context workflowContext(Context context) {
    return context.requireNodeState().parent().orElseThrow();
  }

  private static Event.Builder newEvent() {
    return Event.builder().id(Event.generateEventId()).author("");
  }

  /** Returns a node that runs {@code child} with {@code config} and emits the child's output. */
  private static Node returning(String name, Node child, @Nullable RunNodeConfig config) {
    return dispatcher(
        name,
        (ctx, input) ->
            (config == null ? ctx.runNode(child, null) : ctx.runNode(child, null, config))
                .toFlowable());
  }

  /** Returns a node that delegates its output to {@code child} and emits nothing itself. */
  private static Node delegating(String name, Node child, @Nullable Object childInput) {
    return dispatcher(
        name,
        (ctx, input) -> ctx.runNode(child, childInput, USE_AS_OUTPUT).ignoreElement().toFlowable());
  }

  /** Returns a node that runs {@code child} with {@code config}, then emits {@code AFTER_WAIT}. */
  private static Node continuing(String name, Node child, RunNodeConfig config) {
    return dispatcher(
        name,
        (ctx, input) ->
            ctx.runNode(child, null, config).ignoreElement().andThen(Flowable.just("AFTER_WAIT")));
  }

  /**
   * Returns a node that runs {@code child} with {@code config} and emits the child's output, or
   * {@code fallback}, if not null, when the child fails.
   */
  private static Node handling(
      String name, Node child, RunNodeConfig config, @Nullable Object fallback) {
    return dispatcher(
        name,
        (ctx, input) ->
            ctx.runNode(child, null, config)
                .onErrorResumeNext(
                    error ->
                        error instanceof DynamicNodeFailedException
                            ? Maybe.fromOptional(Optional.ofNullable(fallback))
                            : Maybe.error(error))
                .toFlowable());
  }
}
