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
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.AGE_SCHEMA;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.callContent;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.interrupt;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.MessageOutputNode;
import com.google.adk.workflow.WorkflowResumeTesting.WaitingNode;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for pausing a workflow until the user answers and resuming it on a later turn, which
 * rebuilds each node's progress from the invocation's session events.
 */
@RunWith(JUnit4.class)
public final class WorkflowResumeTest {
  private static final RunNodeConfig USE_AS_OUTPUT =
      RunNodeConfig.builder().runId("c").useAsOutput(true).build();

  @Test
  public void resume_withoutResumability_completesTheNodeAndRunsItsSuccessor() {
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))),
            /* resumable= */ false);
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
    assertThat(events.stream().map(Event::invocationId).distinct()).hasSize(1);
    assertThat(session.invocationIds()).hasSize(1);
  }

  @Test
  public void resume_nodeCompletedBeforeTheInterrupt_replaysItsOutputWithoutRunning() {
    CountingNode prep = new CountingNode("prep", "prep_out");
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, prep), new Edge(prep, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(prep.runs()).isEqualTo(1);
    // The replayed output is emitted again but counts for no node, since prep did not run.
    Event prepEvent = outputEventAt(events, "wf@1/prep@1");
    assertThat(prepEvent.output()).hasValue("prep_out");
    assertThat(prepEvent.nodeInfo().orElseThrow().outputFor()).isEmpty();
  }

  @Test
  public void resume_routingNodeCompletedBeforeTheInterrupt_replaysItsRouteWithoutRunning() {
    AtomicInteger routerRuns = new AtomicInteger();
    Route yes = new Route.Tag("yes");
    Node router =
        new TestNode(
            "router",
            (ctx, input) -> {
              routerRuns.incrementAndGet();
              ctx.routes(ImmutableList.of(yes));
              return Flowable.empty();
            });
    Node ask = longRunningAsk("ask", "ask_1");
    CountingNode unreached = new CountingNode("unreached", "no_out");
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, router),
                new Edge(router, ask, yes),
                new Edge(router, unreached, new Route.Tag("no")),
                new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(routerRuns.get()).isEqualTo(1);
    assertThat(unreached.runs()).isEqualTo(0);
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
  }

  @Test
  public void resume_twoParallelInterruptsAnsweredOnSeparateTurns_joinsOnlyOnceBothAreAnswered() {
    Node askA = longRunningAsk("ask_a", "a_1");
    Node askB = longRunningAsk("ask_b", "b_1");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, askA),
                new Edge(START, askB),
                new Edge(askA, join),
                new Edge(askB, join)));
    session.advance(go());

    ImmutableList<Event> second = session.turn(answer("a_1", ImmutableMap.of("a", 1)));
    ImmutableList<Event> third = session.turn(answer("b_1", ImmutableMap.of("b", 2)));

    assertThat(pathsOf(second)).doesNotContain("wf@1/j@1");
    assertThat(outputEventAt(third, "wf@1/j@1").output())
        .hasValue(
            ImmutableMap.of("ask_a", ImmutableMap.of("a", 1), "ask_b", ImmutableMap.of("b", 2)));
  }

  @Test
  public void resumableWorkflow_run_checkpointsEachNodeAndEndsWithEndOfAgent() {
    Node prep = emitter("prep", "prep_out");

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, prep))).turn(go());

    ImmutableList<Map<String, Object>> checkpoints =
        events.stream()
            .flatMap(event -> event.actions().agentState().stream())
            .collect(toImmutableList());
    assertThat(checkpoints)
        .containsExactly(
            ImmutableMap.of(
                NodeState.NODES_KEY, ImmutableMap.of("prep", checkpoint(NodeStatus.RUNNING))),
            ImmutableMap.of(
                NodeState.NODES_KEY, ImmutableMap.of("prep", checkpoint(NodeStatus.COMPLETED))))
        .inOrder();
    Event last = Iterables.getLast(events);
    assertThat(last.actions().endOfAgent()).isTrue();
    assertThat(last.author()).isEqualTo("wf");
  }

  @Test
  public void workflowWithoutResumability_run_emitsNoCheckpointOrEndOfAgent() {
    Node prep = emitter("prep", "prep_out");

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, prep)), /* resumable= */ false).turn(go());

    assertThat(events.stream().filter(event -> event.actions().agentState().isPresent())).isEmpty();
    assertThat(events.stream().filter(event -> event.actions().endOfAgent())).isEmpty();
  }

  @Test
  public void resumableWorkflow_pausedRun_checkpointsTheWaitingNodeWithoutEndOfAgent() {
    Node ask = longRunningAsk("ask", "ask_1");

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, ask))).turn(go());

    Map<String, Object> lastCheckpoint =
        Streams.findLast(events.stream().flatMap(event -> event.actions().agentState().stream()))
            .orElseThrow();
    assertThat(lastCheckpoint)
        .containsExactly(
            NodeState.NODES_KEY,
            ImmutableMap.of(
                "ask",
                ImmutableMap.of(
                    "status", NodeStatus.WAITING.code(), "interrupts", ImmutableList.of("ask_1"))));
    assertThat(events.stream().filter(event -> event.actions().endOfAgent())).isEmpty();
  }

  @Test
  public void resumableWorkflow_nodeWaitingForAnOutputItDidNotProduce_isCheckpointedAsWaiting() {
    Node waiter =
        new WaitingNode("waiter", (ctx, input) -> Flowable.empty(), /* rerunOnResume= */ false);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, waiter))).turn(go());

    Map<String, Object> lastCheckpoint =
        Streams.findLast(events.stream().flatMap(event -> event.actions().agentState().stream()))
            .orElseThrow();
    assertThat(lastCheckpoint)
        .containsExactly(
            NodeState.NODES_KEY, ImmutableMap.of("waiter", checkpoint(NodeStatus.WAITING)));
  }

  @Test
  public void resumableWorkflow_canceledOnANodesCheckpoint_doesNotStartTheNode() {
    CountingNode first = new CountingNode("first", "out");
    CountingNode second = new CountingNode("second", "out");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, first), new Edge(first, second)));

    ImmutableList<Event> events = session.turnUntil(go(), event -> marksRunning(event, "second"));

    assertThat(Iterables.getLast(events).actions().agentState()).isPresent();
    assertThat(first.runs()).isEqualTo(1);
    assertThat(second.runs()).isEqualTo(0);
  }

  @Test
  public void resumableWorkflow_node_startsOnceItsCheckpointIsProcessed() {
    CountingNode first = new CountingNode("first", "out");
    CountingNode second = new CountingNode("second", "out");
    AtomicInteger secondRunsAtItsCheckpoint = new AtomicInteger(-1);
    Plugin asyncCheckpoints =
        new BasePlugin("async_checkpoints") {
          @Override
          public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
            if (!marksRunning(event, "second")) {
              return Maybe.empty();
            }
            // Processed on another thread, so a node that did not wait could start meanwhile.
            return Maybe.<Event>fromAction(() -> secondRunsAtItsCheckpoint.set(second.runs()))
                .subscribeOn(Schedulers.single());
          }
        };
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, first), new Edge(first, second)),
            /* resumable= */ true,
            asyncCheckpoints);

    ImmutableList<Event> events = session.turn(go());

    assertThat(secondRunsAtItsCheckpoint.get()).isEqualTo(0);
    assertThat(outputEventAt(events, "wf@1/second@1").output()).hasValue("out");
  }

  @Test
  public void resume_finishedWorkflowInvocation_runsNothing() {
    CountingNode prep = new CountingNode("prep", "prep_out");
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, prep)));
    String invocationId = session.turn(go()).get(0).invocationId();

    ImmutableList<Event> events = session.resume(invocationId);

    assertThat(events).isEmpty();
    assertThat(prep.runs()).isEqualTo(1);
  }

  @Test
  public void resume_textAnswerUnderResult_isReadAsJson() {
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("ask_1", ImmutableMap.of("result", "{\"v\": 1}")));

    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
  }

  @Test
  public void resume_textAnswerUnderResultThatIsNotJson_staysText() {
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("ask_1", ImmutableMap.of("result", "not json")));

    assertThat(replyInput(events)).isEqualTo("not json");
  }

  @Test
  public void resume_scalarAnswerUnderResult_isUnwrapped() {
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("result", 5)));

    assertThat(replyInput(events)).isEqualTo(5);
  }

  @Test
  public void resume_nestedWorkflow_outputsItsTerminalNodesResult() {
    Node step1 = emitter("step1", "X");
    Node ask = longRunningAsk("ask", "ask_1");
    Node done = emitter("done", "DONE");
    Workflow sub =
        workflow("sub", new Edge(START, step1), new Edge(step1, ask), new Edge(ask, done));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, sub), new Edge(sub, captureInput("after"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", "DONE"));
  }

  @Test
  public void resume_valueEmittedBeforeTheInterrupt_isReplacedByTheAnswer() {
    Node ask = new TestNode("ask", (ctx, input) -> Flowable.just("stale", interrupt("ask_1")));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, ask), new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
  }

  @Test
  public void resume_dynamicChildThatAsked_completesWithTheAnswer() {
    Node dispatcher =
        dispatcher(
            "dispatch",
            (ctx, input) ->
                ctx.runNode(longRunningAsk("child", "ask_1"), null, runId("c")).toFlowable());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, dispatcher), new Edge(dispatcher, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
  }

  @Test
  public void resume_dynamicChildFinishedEarlier_replaysItsOutputWithoutRunning() {
    CountingNode first = new CountingNode("first", "a_out");
    Node second = longRunningAsk("second", "ask_1");
    Node dispatcher =
        dispatcher(
            "dispatch",
            (ctx, input) ->
                ctx.runNode(first, null, runId("a"))
                    .flatMap(
                        a -> ctx.runNode(second, null, runId("b")).map(b -> ImmutableList.of(a, b)))
                    .toFlowable());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, dispatcher), new Edge(dispatcher, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(first.runs()).isEqualTo(1);
    assertThat(replyInput(events)).isEqualTo(ImmutableList.of("a_out", ImmutableMap.of("v", 1)));
  }

  @Test
  public void resume_delegatedChildCompletedByTheAnswer_becomesTheCallersOutput() {
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(longRunningAsk("child", "ask_1"), null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .toFlowable());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, caller), new Edge(caller, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(events.stream().filter(event -> event.errorCode().isPresent())).isEmpty();
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue(ImmutableMap.of("v", 1));
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
  }

  @Test
  public void resume_callerEmittingItsReplayedDelegatesOutput_sendsThatOutputOnce() {
    CountingNode child = new CountingNode("child", "child_out");
    // The caller asks after delegating, so the next turn reruns it and replays the delegate.
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(child, null, USE_AS_OUTPUT)
                    .flatMap(
                        result ->
                            ctx.runNode(longRunningAsk("ask", "ask_1"), null, runId("a"))
                                .map(answer -> result))
                    .toFlowable());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, caller), new Edge(caller, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(child.runs()).isEqualTo(1);
    assertThat(events.stream().filter(event -> event.errorCode().isPresent())).isEmpty();
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("child_out");
    assertThat(replyInput(events)).isEqualTo("child_out");
  }

  @Test
  public void runNode_delegatingCallWhoseChildNoLongerRehydrates_letsTheCallerProduceItsOutput() {
    Node chat = new MessageOutputNode("chat", "not json", AGE_SCHEMA);
    Node ask = longRunningAsk("ask", "ask_1");
    // On resume, the replayed output of chat no longer parses, and the caller falls back.
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(chat, null, USE_AS_OUTPUT)
                    .ignoreElement()
                    .onErrorComplete()
                    .andThen(ctx.runNode(ask, null, runId("a")))
                    .map(answer -> "fallback")
                    .toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("fallback");
  }

  @Test
  public void resume_rootWorkflowWhoseTerminalNodeReplayed_sendsNoOutputOfItsOwn() {
    CountingNode done = new CountingNode("done", "DONE");
    Node sink = new TestNode("sink", (ctx, input) -> Flowable.empty());
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, done), new Edge(START, ask), new Edge(ask, sink)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(done.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/done@1").output()).hasValue("DONE");
    assertThat(events.stream().filter(event -> pathOf(event).equals("wf@1"))).isEmpty();
  }

  @Test
  public void resume_callerRerunOverAReplayingChildWorkflow_isReplayedOnTheNextTurn() {
    AtomicInteger callerRuns = new AtomicInteger();
    Workflow child = workflow("sub", new Edge(START, emitter("done", "DONE")));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) -> {
              callerRuns.incrementAndGet();
              return ctx.runNode(child, null, USE_AS_OUTPUT)
                  .ignoreElement()
                  .andThen(ctx.runNode(longRunningAsk("ask", "ask_1"), null, runId("a")))
                  .ignoreElement()
                  .toFlowable();
            });
    Node other = longRunningAsk("other", "o_1");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, caller),
                new Edge(START, other),
                new Edge(caller, join),
                new Edge(other, join)));
    session.advance(go());
    session.advance(answer("ask_1", ImmutableMap.of("v", 1)));

    ImmutableList<Event> events = session.turn(answer("o_1", ImmutableMap.of("v", 2)));

    assertThat(callerRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/j@1").output())
        .hasValue(ImmutableMap.of("caller", "DONE", "other", ImmutableMap.of("v", 2)));
  }

  @Test
  public void resume_interruptRaisedByADescendant_reachesTheNodesResumeInputs() {
    Node child = longRunningAsk("child", "ask_1");
    Node node =
        dispatcher(
            "node",
            (ctx, input) ->
                ctx.resumeInputs().isEmpty()
                    ? ctx.runNode(child, null, runId("c")).toFlowable()
                    : Flowable.just(ImmutableMap.of("resumed", ctx.resumeInputs())));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, node), new Edge(node, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(replyInput(events))
        .isEqualTo(ImmutableMap.of("resumed", ImmutableMap.of("ask_1", ImmutableMap.of("v", 1))));
  }

  @Test
  public void resume_concurrentDynamicChildren_replaysFinishedOnesAndCompletesTheAnsweredOne() {
    Map<Object, Integer> runsByItem = new ConcurrentHashMap<>();
    CompletableSubject othersFinished1 = CompletableSubject.create();
    CompletableSubject othersFinished3 = CompletableSubject.create();
    Node worker =
        new TestNode(
            "worker",
            (ctx, input) -> {
              runsByItem.merge(input, 1, Integer::sum);
              if (input.equals(2)) {
                // Asks only once the other items have finished, so they have a result to replay.
                return Completable.mergeArray(othersFinished1, othersFinished3)
                    .andThen(Flowable.just(interrupt("ask_2")));
              }
              CompletableSubject finished = input.equals(1) ? othersFinished1 : othersFinished3;
              return Flowable.just("done-" + input).doOnComplete(finished::onComplete);
            });
    Node fanOut =
        dispatcher(
            "fan",
            (ctx, input) ->
                Flowable.fromIterable((List<?>) input)
                    .concatMapEager(
                        item ->
                            ctx.runNode(worker, item, runId("item" + item))
                                .subscribeOn(Schedulers.io())
                                .toFlowable())
                    .toList()
                    .toFlowable());
    Node items = emitter("items", ImmutableList.of(1, 2, 3));
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, items),
                new Edge(items, fanOut),
                new Edge(fanOut, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_2", ImmutableMap.of("result", "two")));

    assertThat(runsByItem).containsExactly(1, 1, 2, 1, 3, 1);
    assertThat(replyInput(events)).isEqualTo(ImmutableList.of("done-1", "two", "done-3"));
  }

  @Test
  public void resume_rerunOnResumeNodeThatFinishedAfterItsAnswer_doesNotRunAgain() {
    AtomicInteger writerRuns = new AtomicInteger();
    Node writer =
        dispatcher(
            "writer",
            (ctx, input) -> {
              writerRuns.incrementAndGet();
              if (ctx.resumeInputs().isEmpty()) {
                return Flowable.just(interrupt("ask_w"));
              }
              ctx.state().put("writer", "done");
              return Flowable.empty();
            });
    Node other = longRunningAsk("other", "ask_o");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, writer),
                new Edge(START, other),
                new Edge(writer, join),
                new Edge(other, join)));
    session.advance(go());
    session.advance(answer("ask_w", ImmutableMap.of("v", 1)));

    ImmutableList<Event> events = session.turn(answer("ask_o", ImmutableMap.of("v", 2)));

    assertThat(writerRuns.get()).isEqualTo(2);
    assertThat(pathsOf(events)).contains("wf@1/j@1");
  }

  @Test
  public void resume_rerunOnResumeNodeThatFinishedWithoutAnOutput_doesNotRunAgain() {
    AtomicInteger writerRuns = new AtomicInteger();
    Node writer =
        dispatcher(
            "writer",
            (ctx, input) -> {
              writerRuns.incrementAndGet();
              ctx.state().put("writer", "done");
              return Flowable.empty();
            });
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, writer), new Edge(START, ask)));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(writerRuns.get()).isEqualTo(1);
  }

  @Test
  public void resume_nodeAfterStartThatRunsAgain_getsTheOriginalUserMessage() {
    List<@Nullable Object> inputs = Collections.synchronizedList(new ArrayList<>());
    Node reader =
        dispatcher(
            "reader",
            (ctx, input) -> {
              inputs.add(input);
              return ctx.resumeInputs().isEmpty()
                  ? Flowable.just(interrupt("ask_r"))
                  : Flowable.just("done");
            });
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, reader)));
    session.advance(go());

    session.advance(answer("ask_r", ImmutableMap.of("v", 1)));

    assertThat(inputs).containsExactly(go(), go());
  }

  @Test
  public void resume_afterAnEarlierInvocation_nodeAfterStartGetsItsInvocationsMessage() {
    Content first = Content.builder().role("user").parts(Part.fromText("first")).build();
    Content second = Content.builder().role("user").parts(Part.fromText("second")).build();
    List<@Nullable Object> inputs = Collections.synchronizedList(new ArrayList<>());
    Node reader =
        dispatcher(
            "reader",
            (ctx, input) -> {
              inputs.add(input);
              return Objects.equals(input, second) && ctx.resumeInputs().isEmpty()
                  ? Flowable.just(interrupt("ask_r"))
                  : Flowable.just("done");
            });
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, reader)));
    session.advance(first);
    session.advance(second);

    session.advance(answer("ask_r", ImmutableMap.of("v", 1)));

    assertThat(inputs).containsExactly(first, second, second).inOrder();
  }

  @Test
  public void resume_nodeThatRunsAgain_seesTheOriginalUserMessageAsItsUserContent() {
    List<@Nullable Content> userContents = Collections.synchronizedList(new ArrayList<>());
    Node reader =
        dispatcher(
            "reader",
            (ctx, input) -> {
              userContents.add(ctx.userContent().orElse(null));
              return ctx.resumeInputs().isEmpty()
                  ? Flowable.just(interrupt("ask_r"))
                  : Flowable.just("done");
            });
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, reader)));
    session.advance(go());

    session.advance(answer("ask_r", ImmutableMap.of("v", 1)));

    assertThat(userContents).containsExactly(go(), go());
  }

  @Test
  public void resume_standaloneRootNodeAnswered_completesWithTheAnswer() {
    WorkflowSession session = new WorkflowSession(longRunningAsk("ask", "ask_1"));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(outputEventAt(events, "ask@1").output()).hasValue(ImmutableMap.of("v", 1));
    assertThat(interruptEvents(events)).isEmpty();
  }

  @Test
  public void resume_llmAgentNodeAnsweredLongRunningCall_callsTheModelAgain() {
    TestLlm model =
        TestLlm.create(
            TestUtils.createFunctionCallLlmResponse("call_1", "approve", ImmutableMap.of()),
            TestUtils.createTextLlmResponse("approved"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).tools(new PendingTool("approve")).build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, agent)));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("call_1", ImmutableMap.of("ok", true), "approve"));

    ImmutableList<LlmRequest> requests = model.getRequests();
    assertThat(requests).hasSize(2);
    assertThat(
            requests.get(1).contents().stream()
                .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
                .flatMap(part -> part.functionResponse().stream())
                .map(response -> response.id().orElse("")))
        .contains("call_1");
    assertThat(events.stream().map(Event::stringifyContent)).contains("approved");
  }

  @Test
  public void resume_rolelessAnswerWithoutResumability_reachesTheModel() {
    TestLlm model =
        TestLlm.create(
            TestUtils.createFunctionCallLlmResponse("call_1", "approve", ImmutableMap.of()),
            TestUtils.createTextLlmResponse("approved"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).tools(new PendingTool("approve")).build();
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, agent)), /* resumable= */ false);
    session.advance(go());
    Content withRole = answer("call_1", ImmutableMap.of("ok", true), "approve");

    ImmutableList<Event> events =
        session.turn(Content.builder().parts(withRole.parts().orElseThrow()).build());

    ImmutableList<LlmRequest> requests = model.getRequests();
    assertThat(requests).hasSize(2);
    assertThat(
            requests.get(1).contents().stream()
                .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
                .flatMap(part -> part.functionResponse().stream())
                .map(response -> response.id().orElse("")))
        .contains("call_1");
    assertThat(events.stream().map(Event::stringifyContent)).contains("approved");
  }

  @Test
  public void resume_messageOutputNode_isRehydratedToItsText() {
    Node chat = new MessageOutputNode("chat", "agent said hi", /* schema= */ null);
    Node ask = longRunningAsk("ask", "ask_1");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, chat),
                new Edge(START, ask),
                new Edge(chat, join),
                new Edge(ask, join)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(outputEventAt(events, "wf@1/j@1").output())
        .hasValue(ImmutableMap.of("chat", "agent said hi", "ask", ImmutableMap.of("v", 1)));
  }

  @Test
  public void resume_messageOutputNodeWithAnOutputSchema_isRehydratedToTheParsedValue() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ImmutableMap.of("city", Schema.builder().type(Type.Known.STRING).build()))
            .build();
    Node chat = new MessageOutputNode("chat", "{\"city\": \"Paris\"}", schema);
    Node ask = longRunningAsk("ask", "ask_1");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, chat),
                new Edge(START, ask),
                new Edge(chat, join),
                new Edge(ask, join)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(outputEventAt(events, "wf@1/j@1").output())
        .hasValue(
            ImmutableMap.of(
                "chat", ImmutableMap.of("city", "Paris"), "ask", ImmutableMap.of("v", 1)));
  }

  @Test
  public void resume_nodeThatFailedInAnEarlierTurn_runsAgain() {
    AtomicInteger flakyRuns = new AtomicInteger();
    Node flaky =
        new TestNode(
            "flaky",
            (ctx, input) ->
                flakyRuns.incrementAndGet() == 1
                    ? Flowable.error(new IllegalStateException("first run fails"))
                    : Flowable.just("recovered"));
    Node ask = longRunningAsk("ask", "ask_1");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, ask),
                new Edge(START, flaky),
                new Edge(ask, join),
                new Edge(flaky, join)));
    FailedTurn first = session.failingTurn(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(first.error()).isInstanceOf(IllegalStateException.class);
    assertThat(outputEventAt(events, "wf@1/j@1").output())
        .hasValue(ImmutableMap.of("ask", ImmutableMap.of("v", 1), "flaky", "recovered"));
  }

  @Test
  public void resume_nodeCompletingATransfer_checkpointsItAsRunning() {
    Node node =
        new TestNode(
            "node",
            (ctx, input) ->
                Flowable.just(
                    newEvent()
                        .content(callContent("approve", "t_1"))
                        .longRunningToolIds(ImmutableSet.of("t_1"))
                        .actions(EventActions.builder().transferToAgent("other").build())
                        .build()));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, node)));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("t_1", ImmutableMap.of("result", "ok"), "approve"));

    assertThat(events.stream().flatMap(event -> event.actions().agentState().stream()))
        .contains(
            ImmutableMap.of(
                NodeState.NODES_KEY, ImmutableMap.of("node", checkpoint(NodeStatus.RUNNING))));
  }

  @Test
  public void resume_fannedOutNodeReplayed_keepsItsSubBranchOnTheEventItEmitsAgain() {
    CountingNode done = new CountingNode("done", "done_out");
    Node ask = longRunningAsk("ask", "ask_1");
    JoinNode join = JoinNode.builder().name("j").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, done),
                new Edge(START, ask),
                new Edge(done, join),
                new Edge(ask, join)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(done.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/done@1").branch()).hasValue("done@1");
  }

  @Test
  public void resume_nodeEnteredAgainAfterTheResume_takesTheNextRunId() {
    AtomicInteger askRuns = new AtomicInteger();
    Node ask =
        new TestNode(
            "ask", (ctx, input) -> Flowable.just(interrupt("ask_" + askRuns.incrementAndGet())));
    AtomicInteger againRuns = new AtomicInteger();
    Route yes = new Route.Tag("yes");
    Node again =
        new TestNode(
            "again",
            (ctx, input) -> {
              if (againRuns.incrementAndGet() == 1) {
                ctx.routes(ImmutableList.of(yes));
              }
              return Flowable.empty();
            });
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, ask), new Edge(ask, again), new Edge(again, ask, yes)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(outputEventAt(events, "wf@1/ask@1").output()).hasValue(ImmutableMap.of("v", 1));
    Event request = interruptEvents(events).stream().collect(onlyElement());
    assertThat(pathOf(request)).isEqualTo("wf@1/ask@2");
    assertThat(request.functionCalls().get(0).id()).hasValue("ask_2");
  }

  @Test
  public void runNode_sameExplicitRunIdTwiceInOneRun_replaysTheFirstResult() {
    CountingNode child = new CountingNode("child", "child_out");
    Node dispatcher =
        dispatcher(
            "dispatch",
            (ctx, input) ->
                ctx.runNode(child, null, runId("stable-id"))
                    .flatMap(
                        first ->
                            ctx.runNode(child, null, runId("stable-id"))
                                .map(second -> ImmutableList.of(first, second)))
                    .toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, dispatcher))).turn(go());

    assertThat(child.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/dispatch@1").output())
        .hasValue(ImmutableList.of("child_out", "child_out"));
  }

  @Test
  public void runNode_sameExplicitRunIdTwiceForAChildWithoutAnOutput_runsItOnce() {
    CountingNode child = new CountingNode("child", /* output= */ null);
    Node dispatcher =
        dispatcher(
            "dispatch",
            (ctx, input) ->
                ctx.runNode(child, null, runId("stable-id"))
                    .isEmpty()
                    .flatMap(unused -> ctx.runNode(child, null, runId("stable-id")).isEmpty())
                    .map(secondEmpty -> "done")
                    .toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, dispatcher))).turn(go());

    assertThat(child.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/dispatch@1").output()).hasValue("done");
  }

  @Test
  public void runNode_sameExplicitRunIdConcurrently_runsTheChildOnceForBoth() {
    CompletableSubject secondJoined = CompletableSubject.create();
    CountingNode fetch = new CountingNode("fetch", "fetched", secondJoined);
    Node caller = overlappingCalls("caller", fetch, runId("f"), runId("f"), secondJoined);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(fetch.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/caller@1").output())
        .hasValue(ImmutableList.of("fetched", "fetched"));
  }

  @Test
  public void runNode_sameExplicitRunIdFromManyThreads_runsTheChildOnce() {
    for (int i = 0; i < 20; i++) {
      CountingNode fetch = new CountingNode("fetch", "fetched");
      Node caller =
          dispatcher(
              "caller",
              (ctx, input) ->
                  Flowable.range(0, 8)
                      .flatMapMaybe(
                          unused ->
                              ctx.runNode(fetch, null, runId("f")).subscribeOn(Schedulers.io()))
                      .toList()
                      .toFlowable());

      new WorkflowSession(workflow(new Edge(START, caller))).advance(go());

      assertThat(fetch.runs()).isEqualTo(1);
    }
  }

  @Test
  public void runNode_sameExplicitRunIdConcurrentlyOnAnAskingChild_asksOnce() {
    AtomicInteger askRuns = new AtomicInteger();
    CompletableSubject secondJoined = CompletableSubject.create();
    Node ask =
        new TestNode(
            "ask",
            (ctx, input) -> {
              askRuns.incrementAndGet();
              return secondJoined.andThen(Flowable.just(interrupt("ask_1")));
            });
    Node caller = overlappingCalls("caller", ask, runId("f"), runId("f"), secondJoined);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(askRuns.get()).isEqualTo(1);
    assertThat(interruptEvents(events)).hasSize(1);
  }

  @Test
  public void runNode_sameExplicitRunIdConcurrentlyOnAFailingChild_failsBothCalls() {
    AtomicInteger brokenRuns = new AtomicInteger();
    CompletableSubject secondJoined = CompletableSubject.create();
    Node broken =
        new TestNode(
            "broken",
            (ctx, input) -> {
              brokenRuns.incrementAndGet();
              return secondJoined.andThen(Flowable.error(new IllegalStateException("broken")));
            });
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                Single.zip(
                        failedCall(ctx, broken),
                        failedCall(ctx, broken).doOnSubscribe(unused -> secondJoined.onComplete()),
                        ImmutableList::of)
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(brokenRuns.get()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/caller@1").output())
        .hasValue(ImmutableList.of(true, true));
  }

  @Test
  public void runNode_sameExplicitRunIdAfterAFailure_runsTheChildAgain() {
    AtomicInteger flakyRuns = new AtomicInteger();
    Node flaky =
        new TestNode(
            "flaky",
            (ctx, input) ->
                flakyRuns.incrementAndGet() == 1
                    ? Flowable.error(new IllegalStateException("first run fails"))
                    : Flowable.just("recovered"));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(flaky, null, runId("f"))
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException
                                ? ctx.runNode(flaky, null, runId("f"))
                                : Maybe.error(error))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(flakyRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("recovered");
  }

  @Test
  public void runNode_sameExplicitRunIdAfterAWaitForOutputChildLeftNoOutput_runsTheChildAgain() {
    AtomicInteger waitRuns = new AtomicInteger();
    Node wait =
        new WaitingNode(
            "wait",
            (ctx, input) ->
                waitRuns.incrementAndGet() == 1 ? Flowable.empty() : Flowable.just("ready"));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(wait, null, runId("w"))
                    .defaultIfEmpty("none")
                    .flatMapMaybe(
                        first ->
                            ctx.runNode(wait, null, runId("w"))
                                .map(second -> ImmutableList.of(first, second)))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(waitRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output())
        .hasValue(ImmutableList.of("none", "ready"));
  }

  @Test
  public void runNode_sameExplicitRunIdAfterAnInterrupt_replaysTheWaitingChild() {
    AtomicInteger askRuns = new AtomicInteger();
    Node ask =
        new TestNode(
            "ask",
            (ctx, input) -> {
              askRuns.incrementAndGet();
              return Flowable.just(interrupt("ask_1"));
            });
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(ask, null, runId("a"))
                    // Catching the wait signal keeps the caller running, as Context.runNode warns.
                    .onErrorResumeNext(error -> ctx.runNode(ask, null, runId("a")))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(askRuns.get()).isEqualTo(1);
    assertThat(interruptEvents(events)).hasSize(1);
  }

  @Test
  public void runNode_concurrentCallDelegatingASharedChild_emitsTheOutputForTheCaller() {
    CompletableSubject secondJoined = CompletableSubject.create();
    CountingNode fetch = new CountingNode("fetch", "fetched", secondJoined);
    // The first call runs fetch without delegating; the second, which delegates, shares that run.
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                Maybe.merge(
                        ctx.runNode(fetch, null, runId("f")),
                        ctx.runNode(
                                fetch,
                                null,
                                RunNodeConfig.builder().runId("f").useAsOutput(true).build())
                            .doOnSubscribe(unused -> secondJoined.onComplete()))
                    .ignoreElements()
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(fetch.runs()).isEqualTo(1);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("fetched");
  }

  @Test
  public void runNode_firstCallOfASharedRunCanceled_waitingCallRunsTheChild() {
    AtomicInteger stalledRuns = new AtomicInteger();
    CompletableSubject secondJoined = CompletableSubject.create();
    Node stalled =
        new TestNode(
            "stalled",
            (ctx, input) ->
                stalledRuns.incrementAndGet() == 1
                    ? Flowable.never()
                    : Flowable.just("stalled done"));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) -> {
              // The second call joining the run cancels the first call.
              Maybe<Object> first =
                  ctx.runNode(stalled, null, runId("s"))
                      .takeUntil(secondJoined.toMaybe())
                      .switchIfEmpty(Maybe.just("canceled"));
              Maybe<Object> second =
                  ctx.runNode(stalled, null, runId("s"))
                      .doOnSubscribe(unused -> secondJoined.onComplete());
              return Maybe.zip(first, second, ImmutableList::of).toFlowable();
            });

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(stalledRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output())
        .hasValue(ImmutableList.of("canceled", "stalled done"));
  }

  @Test
  public void runNode_failedChildWorkflowRunAgain_rerunsTheSiblingThatItsFailureCanceled() {
    AtomicInteger aRuns = new AtomicInteger();
    AtomicInteger bRuns = new AtomicInteger();
    CompletableSubject bStarted = CompletableSubject.create();
    Node a =
        new TestNode(
            "a",
            (ctx, input) ->
                aRuns.incrementAndGet() == 1
                    ? bStarted.andThen(Flowable.error(new IllegalStateException("a fails")))
                    : Flowable.empty());
    // On its first run, b reports progress and then works until the failure of a cancels it.
    Node b =
        new TestNode(
            "b",
            (ctx, input) ->
                bRuns.incrementAndGet() == 1
                    ? Flowable.just(newEvent().content(modelText("working")).build())
                        .concatWith(Completable.fromAction(bStarted::onComplete))
                        .concatWith(Flowable.never())
                    : Flowable.just("b"));
    Workflow inner = workflow("inner", new Edge(START, a), new Edge(START, b));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(inner, null, runId("w"))
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException
                                ? ctx.runNode(inner, null, runId("w"))
                                : Maybe.error(error))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(aRuns.get()).isEqualTo(2);
    assertThat(bRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("b");
  }

  @Test
  public void runNode_failedChildWorkflowRunAgain_doesNotRerunANodeThatFinishedWithoutAnOutput() {
    AtomicInteger cRuns = new AtomicInteger();
    AtomicInteger aRuns = new AtomicInteger();
    // c emits no output; only being marked finished keeps it from counting as canceled mid-run.
    Node c =
        new TestNode(
            "c",
            (ctx, input) -> {
              cRuns.incrementAndGet();
              return Flowable.just(newEvent().content(modelText("working")).build());
            });
    Node a =
        new TestNode(
            "a",
            (ctx, input) ->
                aRuns.incrementAndGet() == 1
                    ? Flowable.error(new IllegalStateException("a fails"))
                    : Flowable.just("a"));
    Workflow inner = workflow("inner", new Edge(START, c), new Edge(c, a));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(inner, null, runId("w"))
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException
                                ? ctx.runNode(inner, null, runId("w"))
                                : Maybe.error(error))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(cRuns.get()).isEqualTo(1);
    assertThat(aRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("a");
  }

  @Test
  public void runNode_failedCallerRunAgain_rerunsTheChildItsFailureCanceled() {
    AtomicInteger aRuns = new AtomicInteger();
    AtomicInteger bRuns = new AtomicInteger();
    CompletableSubject bStarted = CompletableSubject.create();
    Node a =
        new TestNode(
            "a",
            (ctx, input) ->
                aRuns.incrementAndGet() == 1
                    ? bStarted.andThen(Flowable.error(new IllegalStateException("a fails")))
                    : Flowable.just("a"));
    // b reruns on resume, so its progress event alone would read as a finished run.
    Node b =
        dispatcher(
            "b",
            (ctx, input) ->
                bRuns.incrementAndGet() == 1
                    ? Flowable.just(newEvent().content(modelText("working")).build())
                        .concatWith(Completable.fromAction(bStarted::onComplete))
                        .concatWith(Flowable.never())
                    : Flowable.just("b"));
    Node inner =
        dispatcher(
            "inner",
            (ctx, input) ->
                Maybe.zip(
                        ctx.runNode(b, null, runId("b")),
                        ctx.runNode(a, null, runId("a")),
                        (bOutput, aOutput) -> bOutput)
                    .toFlowable());
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(inner, null, runId("w"))
                    .onErrorResumeNext(
                        error ->
                            error instanceof DynamicNodeFailedException
                                ? ctx.runNode(inner, null, runId("w"))
                                : Maybe.error(error))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(aRuns.get()).isEqualTo(2);
    assertThat(bRuns.get()).isEqualTo(2);
    assertThat(outputEventAt(events, "wf@1/caller@1").output()).hasValue("b");
  }

  @Test
  public void runNode_childWhoseRecordedOutputNoLongerParses_failsEveryCallWithoutHanging() {
    Node chat = new MessageOutputNode("chat", "not json", AGE_SCHEMA);
    Node ask = longRunningAsk("ask", "ask_1");
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(chat, null, runId("c"))
                    .onErrorResumeNext(error -> ctx.runNode(chat, null, runId("c")))
                    .flatMap(unused -> ctx.runNode(ask, null, runId("a")))
                    .toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    FailedTurn turn = session.failingTurn(answer("ask_1", ImmutableMap.of("v", 1)));

    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error()).hasMessageThat().contains("rehydrated output");
  }

  private static ImmutableList<Event> interruptEvents(List<Event> events) {
    return events.stream()
        .filter(event -> !event.longRunningToolIds().orElse(ImmutableSet.of()).isEmpty())
        .collect(toImmutableList());
  }

  /** Returns whether {@code event} is a checkpoint that marks {@code nodeName} as running. */
  private static boolean marksRunning(Event event, String nodeName) {
    return event
        .actions()
        .agentState()
        .map(state -> (Map<?, ?>) state.get(NodeState.NODES_KEY))
        .filter(nodes -> checkpoint(NodeStatus.RUNNING).equals(nodes.get(nodeName)))
        .isPresent();
  }

  private static ImmutableMap<String, Object> checkpoint(NodeStatus status) {
    return ImmutableMap.of("status", status.code(), "interrupts", ImmutableList.of());
  }

  private static RunNodeConfig runId(String runId) {
    return RunNodeConfig.builder().runId(runId).build();
  }

  /**
   * Returns a node that runs {@code child} with {@code first} and then with {@code second}, whose
   * call completes {@code secondJoined} once it has joined a run or started its own.
   */
  private static Node overlappingCalls(
      String name,
      Node child,
      RunNodeConfig first,
      RunNodeConfig second,
      CompletableSubject secondJoined) {
    return dispatcher(
        name,
        (ctx, input) ->
            Maybe.zip(
                    ctx.runNode(child, null, first),
                    ctx.runNode(child, null, second)
                        .doOnSubscribe(unused -> secondJoined.onComplete()),
                    ImmutableList::of)
                .toFlowable());
  }

  /** Returns whether running {@code child} under run ID {@code f} fails. */
  private static Single<Boolean> failedCall(Context ctx, Node child) {
    return ctx.runNode(child, null, runId("f"))
        .map(output -> false)
        .defaultIfEmpty(false)
        .onErrorReturn(error -> error instanceof DynamicNodeFailedException);
  }

  /** A node that emits {@code output}, if any, once {@code gate} completes, and counts its runs. */
  private static final class CountingNode implements Node {
    private final String name;
    private final @Nullable Object output;
    private final Completable gate;
    private final AtomicInteger runs = new AtomicInteger();

    CountingNode(String name, @Nullable Object output) {
      this(name, output, Completable.complete());
    }

    CountingNode(String name, @Nullable Object output, Completable gate) {
      this.name = name;
      this.output = output;
      this.gate = gate;
    }

    int runs() {
      return runs.get();
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      runs.incrementAndGet();
      return gate.andThen(output == null ? Flowable.empty() : Flowable.just(output));
    }
  }

  /** A long-running tool that answers nothing, so the call stays pending until the user answers. */
  private static final class PendingTool extends BaseTool {
    PendingTool(String name) {
      super(name, "Asks the user to approve.", /* isLongRunning= */ true);
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder().name(name()).description(description()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.just(ImmutableMap.of());
    }
  }
}
