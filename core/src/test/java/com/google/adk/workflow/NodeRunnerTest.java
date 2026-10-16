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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestCallback;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeRunnerTest {

  private static final Content MESSAGE = Content.fromParts(Part.fromText("hi"));
  private static final FinishReason ILLEGAL_STATE = new FinishReason("IllegalStateException");

  private final List<Event> sentEvents = new ArrayList<>();
  private final NodeRunner.EventSink recordingSink =
      event -> Completable.fromAction(() -> sentEvents.add(event));
  private final List<CompletableSubject> acknowledgements = new ArrayList<>();
  private final NodeRunner.EventSink acknowledgingSink =
      event -> {
        sentEvents.add(event);
        CompletableSubject acknowledgement = CompletableSubject.create();
        acknowledgements.add(acknowledgement);
        return acknowledgement;
      };
  private final List<Event> eventsInProcessing = new ArrayList<>();
  private final List<SingleSubject<Event>> processings = new ArrayList<>();
  private final Function<Event, Single<Event>> heldProcessing =
      event -> {
        eventsInProcessing.add(event);
        SingleSubject<Event> processing = SingleSubject.create();
        processings.add(processing);
        return processing;
      };

  @Test
  public void run_stampsTheEventWithTheNodeNamePathAndInvocation() {
    runAtRoot(new TestNode("greet", (ctx, input) -> Flowable.just("hi")));

    Event event = getOnlyElement(sentEvents);
    assertThat(event.author()).isEqualTo("greet");
    assertThat(event.invocationId()).isEqualTo("invocationId");
    assertThat(event.nodeInfo().map(NodeInfo::path)).hasValue("greet@1");
  }

  @Test
  public void run_yieldedValue_becomesTheEventAndNodeStateOutput() {
    Context context = runAtRoot(new TestNode("greet", (ctx, input) -> Flowable.just("hi")));

    Event event = getOnlyElement(sentEvents);
    assertThat(event.output()).hasValue("hi");
    assertThat(event.nodeInfo().map(NodeInfo::outputFor)).hasValue(ImmutableList.of("greet@1"));
    assertThat(context.requireNodeState().output()).hasValue("hi");
  }

  @Test
  public void run_passesTheInputToTheNode() {
    List<Object> inputs = new ArrayList<>();
    Node node =
        new TestNode(
            "echo",
            (ctx, input) -> {
              inputs.add(input);
              return Flowable.empty();
            });

    rootRunner(node, recordingSink).run("question").blockingSubscribe();

    assertThat(inputs).containsExactly("question");
  }

  @Test
  public void run_withoutABranchDirective_keepsTheParentBranch() {
    Context context =
        runUnderParent(
            new TestNode("greet", (ctx, input) -> Flowable.just("hi")),
            /* useSubBranch= */ false,
            /* overrideBranch= */ null);

    assertThat(context.branch()).hasValue("root");
    assertThat(getOnlyElement(sentEvents).branch()).hasValue("root");
  }

  @Test
  public void run_onASubBranch_derivesItFromTheParentBranch() {
    Context context =
        runUnderParent(
            new TestNode("greet", (ctx, input) -> Flowable.just("hi")),
            /* useSubBranch= */ true,
            /* overrideBranch= */ null);

    assertThat(context.branch()).hasValue("root.greet@1");
    assertThat(getOnlyElement(sentEvents).branch()).hasValue("root.greet@1");
  }

  @Test
  public void run_withAnOverrideBranch_replacesTheParentBranch() {
    Context context =
        runUnderParent(
            new TestNode("greet", (ctx, input) -> Flowable.just("hi")),
            /* useSubBranch= */ false,
            /* overrideBranch= */ "merged");

    assertThat(context.branch()).hasValue("merged");
    assertThat(getOnlyElement(sentEvents).branch()).hasValue("merged");
  }

  @Test
  public void run_underAParent_extendsItsPathWithTheRunId() {
    Context context = runWorkerUnder(parentContext());

    assertThat(getOnlyElement(sentEvents).nodeInfo().map(NodeInfo::path))
        .hasValue("root@1/worker@2");
    assertThat(context.requireNodeState().runId()).isEqualTo("2");
  }

  @Test
  public void run_underAParent_linksTheContextToTheParent() {
    Context parent = parentContext();

    Context context = runWorkerUnder(parent);

    assertThat(context.requireNodeState().parent()).hasValue(parent);
  }

  @Test
  public void run_underAParent_inheritsItsEventAuthor() {
    Context parent = parentContext();
    parent.requireNodeState().eventAuthor("custom-agent");

    runWorkerUnder(parent);

    assertThat(getOnlyElement(sentEvents).author()).isEqualTo("custom-agent");
  }

  @Test
  public void run_messageAsOutputEvent_makesItsContentTheOutputWithoutAnotherEvent() {
    Context context =
        runAtRoot(
            new TestNode(
                "greet", (ctx, input) -> Flowable.just(messageAsOutput(/* output= */ null))));

    Event event = getOnlyElement(sentEvents);
    assertThat(context.requireNodeState().output()).hasValue(MESSAGE);
    assertThat(event.content()).hasValue(MESSAGE);
    assertThat(event.output()).isEmpty();
    assertThat(event.nodeInfo().map(NodeInfo::outputFor)).hasValue(ImmutableList.of("greet@1"));
  }

  @Test
  public void run_messageAsOutputEventWithAnOutputField_dropsTheField() {
    runAtRoot(new TestNode("greet", (ctx, input) -> Flowable.just(messageAsOutput("hi"))));

    Event event = getOnlyElement(sentEvents);
    assertThat(event.content()).hasValue(MESSAGE);
    assertThat(event.output()).isEmpty();
  }

  @Test
  public void run_routeOnTheNodesEvent_isRecordedWithoutAnotherEvent() {
    ImmutableList<Route> route = ImmutableList.of(new Route.Tag("next"));

    Context context =
        runAtRoot(
            new TestNode(
                "router",
                (ctx, input) ->
                    Flowable.just(
                        newEvent().actions(EventActions.builder().route(route).build()).build())));

    assertThat(context.requireNodeState().selectedRoutes()).hasValue(route);
    assertThat(getOnlyElement(sentEvents).actions().route()).hasValue(route);
  }

  @Test
  public void run_routeSelectedOnTheNodeState_isSentOnALastEvent() {
    ImmutableList<Route> route = ImmutableList.of(new Route.Tag("next"));

    runAtRoot(
        new TestNode(
            "router",
            (ctx, input) -> {
              ctx.requireNodeState().selectRoutes(route);
              return Flowable.empty();
            }));

    assertThat(getOnlyElement(sentEvents).actions().route()).hasValue(route);
  }

  @Test
  public void run_outputProducedOnTheNodeState_isSentOnALastEvent() {
    runAtRoot(
        new TestNode(
            "worker",
            (ctx, input) -> {
              ctx.requireNodeState().produceOutput("result");
              return Flowable.empty();
            }));

    Event event = getOnlyElement(sentEvents);
    assertThat(event.output()).hasValue("result");
    assertThat(event.nodeInfo().map(NodeInfo::outputFor)).hasValue(ImmutableList.of("worker@1"));
  }

  @Test
  public void run_stateChangeWithoutAnEvent_isSentOnALastEvent() {
    runAtRoot(
        new TestNode(
            "writer",
            (ctx, input) -> {
              ctx.state().put("key", "value");
              return Flowable.empty();
            }));

    assertThat(getOnlyElement(sentEvents).actions().stateDelta()).containsExactly("key", "value");
  }

  @Test
  public void run_stateChange_ridesTheNextFinalEventOnly() {
    runAtRoot(
        new TestNode(
            "writer",
            (ctx, input) ->
                Flowable.defer(
                    () -> {
                      ctx.state().put("key", "value");
                      return Flowable.just(
                          newEvent().content(MESSAGE).partial(true).build(),
                          newEvent().content(MESSAGE).build());
                    })));

    assertThat(sentEvents).hasSize(2);
    assertThat(sentEvents.get(0).actions().stateDelta()).isEmpty();
    assertThat(sentEvents.get(1).actions().stateDelta()).containsExactly("key", "value");
  }

  @Test
  public void run_failure_sendsAnErrorEventWithThePendingStateChangeOnly() {
    IllegalStateException failure = new IllegalStateException("node failed");

    Context context =
        runAtRoot(
            new TestNode(
                "boom",
                (ctx, input) -> {
                  ctx.requireNodeState().produceOutput("unsent");
                  ctx.requireNodeState().selectRoutes(ImmutableList.of(new Route.Tag("unsent")));
                  ctx.state().put("key", "value");
                  return Flowable.error(failure);
                }));

    Event errorEvent = getOnlyElement(sentEvents);
    assertThat(errorEvent.author()).isEqualTo("boom");
    assertThat(errorEvent.nodeInfo().map(NodeInfo::path)).hasValue("boom@1");
    assertThat(errorEvent.errorCode()).hasValue(ILLEGAL_STATE);
    assertThat(errorEvent.errorMessage()).hasValue("node failed");
    assertThat(errorEvent.actions().stateDelta()).containsExactly("key", "value");
    assertThat(errorEvent.output()).isEmpty();
    assertThat(errorEvent.actions().route()).isEmpty();
    assertThat(context.requireNodeState().failure()).hasValue(failure);
  }

  @Test
  public void run_artifactChangeWithoutAnEvent_isSentOnALastEvent() {
    runAtRoot(
        new TestNode(
            "writer",
            (ctx, input) -> {
              ctx.eventActions().artifactDelta().put("report.txt", 1);
              return Flowable.empty();
            }));

    assertThat(getOnlyElement(sentEvents).actions().artifactDelta())
        .containsExactly("report.txt", 1);
  }

  @Test
  public void run_artifactChange_ridesTheNextFinalEventOnly() {
    runAtRoot(
        new TestNode(
            "writer",
            (ctx, input) ->
                Flowable.defer(
                    () -> {
                      ctx.eventActions().artifactDelta().put("report.txt", 1);
                      return Flowable.just(
                          newEvent().content(MESSAGE).partial(true).build(),
                          newEvent().content(MESSAGE).build());
                    })));

    assertThat(sentEvents).hasSize(2);
    assertThat(sentEvents.get(0).actions().artifactDelta()).isEmpty();
    assertThat(sentEvents.get(1).actions().artifactDelta()).containsExactly("report.txt", 1);
  }

  @Test
  public void run_failure_sendsAnErrorEventWithThePendingArtifactChange() {
    runAtRoot(
        new TestNode(
            "boom",
            (ctx, input) -> {
              ctx.eventActions().artifactDelta().put("report.txt", 1);
              return Flowable.error(new IllegalStateException("node failed"));
            }));

    Event errorEvent = getOnlyElement(sentEvents);
    assertThat(errorEvent.errorCode()).hasValue(ILLEGAL_STATE);
    assertThat(errorEvent.actions().artifactDelta()).containsExactly("report.txt", 1);
  }

  @Test
  public void run_failureAfterEvents_sendsTheErrorEventOnceTheEventsAreProcessed() {
    Node node =
        new TestNode(
            "boom",
            (ctx, input) ->
                Flowable.just(
                        newEvent().content(MESSAGE).build(), newEvent().content(MESSAGE).build())
                    .concatWith(Flowable.error(new IllegalStateException("node failed"))));

    TestObserver<Context> observer =
        rootRunner(node, acknowledgingSink).run(/* nodeInput= */ null).test();
    int sentBeforeAcknowledgements = sentEvents.size();
    acknowledgements.get(0).onComplete();
    acknowledgements.get(1).onComplete();
    acknowledgements.get(2).onComplete();

    assertThat(sentBeforeAcknowledgements).isEqualTo(1);
    assertThat(sentEvents).hasSize(3);
    assertThat(sentEvents.get(2).errorCode()).hasValue(ILLEGAL_STATE);
    observer.assertComplete();
  }

  @Test
  public void run_secondOutput_failsTheNode() {
    Context context = runAtRoot(new TestNode("twice", (ctx, input) -> Flowable.just("one", "two")));

    assertThat(sentEvents).hasSize(2);
    assertThat(sentEvents.get(0).output()).hasValue("one");
    assertThat(sentEvents.get(1).errorCode()).hasValue(ILLEGAL_STATE);
    assertThat(context.requireNodeState().failure()).isPresent();
  }

  @Test
  public void run_error_propagatesWithoutAnErrorEvent() {
    rootRunner(
            new TestNode("fatal", (ctx, input) -> Flowable.error(new AssertionError("fatal"))),
            recordingSink)
        .run(/* nodeInput= */ null)
        .test()
        .assertError(AssertionError.class);

    assertThat(sentEvents).isEmpty();
  }

  @Test
  public void run_transferOnTheNodesEvent_isRecordedOnItsActions() {
    Context context =
        runAtRoot(
            new TestNode(
                "router",
                (ctx, input) ->
                    Flowable.just(
                        newEvent()
                            .actions(EventActions.builder().transferToAgent("specialist").build())
                            .build())));

    assertThat(context.eventActions().transferToAgent()).hasValue("specialist");
  }

  @Test
  public void run_eventOfANestedNode_neitherRoutesNorTransfersTheNode() {
    Context context =
        runAtRoot(
            new TestNode(
                "outer",
                (ctx, input) ->
                    Flowable.just(
                        newEvent()
                            .author("inner")
                            .actions(
                                EventActions.builder()
                                    .route(ImmutableList.of(new Route.Tag("next")))
                                    .transferToAgent("specialist")
                                    .build())
                            .build())));

    assertThat(context.requireNodeState().selectedRoutes()).isEmpty();
    assertThat(context.eventActions().transferToAgent()).isEmpty();
  }

  @Test
  public void run_routeSelectedAfterAnEventCarriedOne_isSentOnALastEvent() {
    ImmutableList<Route> second = ImmutableList.of(new Route.Tag("second"));
    runAtRoot(
        new TestNode(
            "router",
            (ctx, input) ->
                Flowable.just(
                        newEvent()
                            .actions(
                                EventActions.builder()
                                    .route(ImmutableList.of(new Route.Tag("first")))
                                    .build())
                            .build())
                    .concatWith(
                        Flowable.defer(
                            () -> {
                              ctx.requireNodeState().selectRoutes(second);
                              return Flowable.empty();
                            }))));

    assertThat(sentEvents).hasSize(2);
    assertThat(sentEvents.get(1).actions().route()).hasValue(second);
  }

  @Test
  public void run_eventWithItsOwnBranch_keepsItsBranch() {
    Context context =
        runUnderParent(
            new TestNode(
                "worker",
                (ctx, input) -> Flowable.just(newEvent().branch("own").content(MESSAGE).build())),
            /* useSubBranch= */ false,
            /* overrideBranch= */ null);

    assertThat(context.invocationContext().branch()).hasValue("root");
    assertThat(getOnlyElement(sentEvents).branch()).hasValue("own");
  }

  @Test
  public void run_longRunningToolCalls_becomeInterruptIds() {
    Context context =
        runAtRoot(
            new TestNode(
                "asker",
                (ctx, input) ->
                    Flowable.just(
                        newEvent().longRunningToolIds(ImmutableSet.of("call-1")).build())));

    assertThat(context.requireNodeState().interruptIds()).containsExactly("call-1");
  }

  @Test
  public void run_sendsEachEventAfterThePreviousOneIsProcessed() {
    Node node =
        new TestNode(
            "pair",
            (ctx, input) ->
                Flowable.just(
                    newEvent().content(MESSAGE).build(), newEvent().content(MESSAGE).build()));

    TestObserver<Context> observer =
        rootRunner(node, acknowledgingSink).run(/* nodeInput= */ null).test();
    int sentBeforeFirstAcknowledgement = sentEvents.size();
    acknowledgements.get(0).onComplete();
    int sentBeforeSecondAcknowledgement = sentEvents.size();
    observer.assertNotComplete();
    acknowledgements.get(1).onComplete();

    assertThat(sentBeforeFirstAcknowledgement).isEqualTo(1);
    assertThat(sentBeforeSecondAcknowledgement).isEqualTo(2);
    observer.assertComplete();
  }

  @Test
  public void run_agentNode_keepsTheAuthorOfTheAgentsEvents() {
    // Agent name differs from emitted author, so stamping with the node name would fail.
    Node agent =
        TestUtils.createSubAgent(
            "router", Event.builder().id("e1").author("worker").content(MESSAGE).build());

    runAtRoot(agent);

    Event event = getOnlyElement(sentEvents);
    assertThat(event.author()).isEqualTo("worker");
    assertThat(event.nodeInfo().map(NodeInfo::path)).hasValue("router@1");
  }

  @Test
  public void run_agentNodeWithSeveralAuthors_stampsEachEventWithItsOwnAuthor() {
    Node agent =
        TestUtils.createSubAgent(
            "team",
            Event.builder().id("e1").author("planner").content(MESSAGE).build(),
            Event.builder().id("e2").author("worker").content(MESSAGE).build(),
            Event.builder().id("e3").author("critic").content(MESSAGE).build());

    TestObserver<Context> observer =
        rootRunner(agent, acknowledgingSink).run(/* nodeInput= */ null).test();
    acknowledgements.get(0).onComplete();
    acknowledgements.get(1).onComplete();
    acknowledgements.get(2).onComplete();

    observer.assertComplete();
    assertThat(sentEvents.stream().map(Event::author).collect(toImmutableList()))
        .containsExactly("planner", "worker", "critic")
        .inOrder();
  }

  @Test
  public void run_agentNode_runsTheAgentWithItsCallbacks() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content afterCallbackContent = Content.fromParts(Part.fromText("after_callback_output"));
    var beforeCallback = TestCallback.<Content>returningEmpty();
    var afterCallback = TestCallback.returning(afterCallbackContent);
    TestBaseAgent agent =
        new TestBaseAgent(
            "agent",
            "An agent.",
            ImmutableList.of(beforeCallback.asBeforeAgentCallback()),
            ImmutableList.of(afterCallback.asAfterAgentCallback()),
            runAsyncImpl.asRunAsyncImplSupplier(MESSAGE));

    runAtRoot(agent);

    assertThat(sentEvents).hasSize(2);
    assertThat(sentEvents.get(0).content()).hasValue(MESSAGE);
    assertThat(sentEvents.get(1).content()).hasValue(afterCallbackContent);
    assertThat(beforeCallback.wasCalled()).isTrue();
    assertThat(runAsyncImpl.wasCalled()).isTrue();
  }

  @Test
  public void run_agentNodeEventWithoutAnAuthor_keepsThePreviousAuthor() {
    Node agent =
        TestUtils.createSubAgent(
            "router",
            Event.builder().id("e1").author("worker").content(MESSAGE).build(),
            Event.builder().id("e2").author("").content(MESSAGE).build());

    runAtRoot(agent);

    assertThat(sentEvents.stream().map(Event::author).collect(toImmutableList()))
        .containsExactly("worker", "worker")
        .inOrder();
  }

  @Test
  public void build_withoutANode_throwsIllegalStateException() {
    NodeRunner.Builder builder =
        NodeRunner.builder()
            .invocationContext(invocationContext(/* branch= */ null))
            .eventSink(recordingSink);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_atTheRootWithoutAnEventSink_throwsIllegalStateException() {
    NodeRunner.Builder builder =
        NodeRunner.builder()
            .node(TestNode.of("worker"))
            .invocationContext(invocationContext(/* branch= */ null));

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_underAParentWithAnInvocationContext_throwsIllegalStateException() {
    NodeRunner.Builder builder =
        NodeRunner.builder()
            .node(TestNode.of("worker"))
            .parent(parentContext())
            .invocationContext(invocationContext(/* branch= */ null));

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void runRoot_asksForTheNextEventOnlyAfterTheCallerReceivesTheProcessedEvent() {
    List<Event> emitted = new ArrayList<>();
    Node node =
        new TestNode(
            "pair",
            (ctx, input) ->
                Flowable.just(
                        newEvent().content(MESSAGE).build(), newEvent().content(MESSAGE).build())
                    .doOnNext(emitted::add));

    TestSubscriber<Event> subscriber = TestSubscriber.create(/* initialRequested= */ 0);

    NodeRunner.runRoot(node, invocationContext(/* branch= */ null), heldProcessing)
        .subscribe(subscriber);
    finishProcessing(0);
    int emittedBeforeTheCallerReceivesTheFirstEvent = emitted.size();
    subscriber.request(1);
    int emittedAfterTheCallerReceivesTheFirstEvent = emitted.size();
    finishProcessing(1);
    subscriber.request(1);

    assertThat(emittedBeforeTheCallerReceivesTheFirstEvent).isEqualTo(1);
    assertThat(emittedAfterTheCallerReceivesTheFirstEvent).isEqualTo(2);
    subscriber.assertValueCount(2).assertComplete();
  }

  @Test
  public void runRoot_signalsTheNodeFailureOnlyAfterItsErrorEventIsProcessed() {
    IllegalStateException failure = new IllegalStateException("node failed");
    Node node = new TestNode("boom", (ctx, input) -> Flowable.error(failure));

    TestSubscriber<Event> subscriber =
        NodeRunner.runRoot(node, invocationContext(/* branch= */ null), heldProcessing).test();
    subscriber.assertEmpty();
    finishProcessing(0);

    assertThat(getOnlyElement(subscriber.values()).errorCode()).hasValue(ILLEGAL_STATE);
    subscriber.assertError(failure);
  }

  @Test
  public void runRoot_emitsTheEventThatProcessingReturns() {
    Node node =
        new TestNode("solo", (ctx, input) -> Flowable.just(newEvent().content(MESSAGE).build()));
    Event replacement = newEvent().author("plugin").content(MESSAGE).build();

    TestSubscriber<Event> subscriber =
        NodeRunner.runRoot(
                node, invocationContext(/* branch= */ null), event -> Single.just(replacement))
            .test();

    subscriber.assertComplete();
    assertThat(getOnlyElement(subscriber.values())).isSameInstanceAs(replacement);
  }

  @Test
  public void runRoot_errorFromTheNode_failsTheRunWithoutAnErrorEvent() {
    AssertionError fatal = new AssertionError("fatal");
    Node node = new TestNode("fatal", (ctx, input) -> Flowable.error(fatal));

    TestSubscriber<Event> subscriber =
        NodeRunner.runRoot(node, invocationContext(/* branch= */ null), heldProcessing).test();

    subscriber.assertError(fatal);
    assertThat(eventsInProcessing).isEmpty();
  }

  @Test
  public void runRoot_partialEvent_doesNotHoldBackTheNode() {
    List<Event> emitted = new ArrayList<>();
    Node node =
        new TestNode(
            "streamer",
            (ctx, input) ->
                Flowable.just(
                        newEvent().content(MESSAGE).partial(true).build(),
                        newEvent().content(MESSAGE).build())
                    .doOnNext(emitted::add));

    TestSubscriber<Event> subscriber =
        NodeRunner.runRoot(node, invocationContext(/* branch= */ null), heldProcessing).test();
    int emittedWhileThePartialEventIsProcessed = emitted.size();
    finishProcessing(0);
    finishProcessing(1);

    assertThat(emittedWhileThePartialEventIsProcessed).isEqualTo(2);
    subscriber.assertValueCount(2).assertComplete();
  }

  @CanIgnoreReturnValue
  private Context runAtRoot(Node node) {
    return rootRunner(node, recordingSink).run(/* nodeInput= */ null).blockingGet();
  }

  private static NodeRunner rootRunner(Node node, NodeRunner.EventSink eventSink) {
    return NodeRunner.builder()
        .node(node)
        .invocationContext(invocationContext(/* branch= */ null))
        .eventSink(eventSink)
        .build();
  }

  private void finishProcessing(int index) {
    processings.get(index).onSuccess(eventsInProcessing.get(index));
  }

  private Context runUnderParent(Node node, boolean useSubBranch, @Nullable String overrideBranch) {
    return NodeRunner.builder()
        .node(node)
        .parent(parentContext())
        .useSubBranch(useSubBranch)
        .overrideBranch(overrideBranch)
        .build()
        .run(/* nodeInput= */ null)
        .blockingGet();
  }

  @CanIgnoreReturnValue
  private Context runWorkerUnder(Context parent) {
    return NodeRunner.builder()
        .node(new TestNode("worker", (ctx, input) -> Flowable.just("done")))
        .parent(parent)
        .runId("2")
        .build()
        .run(/* nodeInput= */ null)
        .blockingGet();
  }

  /** Returns the context of a node named {@code root} that runs on the {@code root} branch. */
  private Context parentContext() {
    return new Context(
        invocationContext("root"),
        NodeExecutionState.builder()
            .node(TestNode.of("root"))
            .eventSink(recordingSink)
            .runId("1")
            .build());
  }

  private static InvocationContext invocationContext(@Nullable String branch) {
    return TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
        .branch(branch)
        .build();
  }

  private static Event.Builder newEvent() {
    return Event.builder().id(Event.generateEventId()).author("");
  }

  private static Event messageAsOutput(@Nullable Object output) {
    return newEvent()
        .content(MESSAGE)
        .output(output)
        .nodeInfo(NodeInfo.builder().messageAsOutput(true).build())
        .build();
  }
}
