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

package com.google.adk.runner;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.NodeViewAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for running a workflow node, rather than an agent, at the root of an invocation. */
@RunWith(JUnit4.class)
public final class RunnerRootNodeTest {

  private static final String APP_NAME = "node_app";
  private static final String USER_ID = "user";
  private static final Content USER_MESSAGE = Content.fromParts(Part.fromText("hello"));
  private static final FunctionResponse IDENTIFIED_RESPONSE =
      FunctionResponse.builder().id("fc-1").name("tool").response(ImmutableMap.of("v", 1)).build();

  @Test
  public void node_ofARunnerForANodeApp_isThatNode() {
    Node node = TestNode.of("solo");

    Runner runner = runner(App.builder().name(APP_NAME).rootNode(node).build());

    assertThat(runner.node()).hasValue(node);
  }

  @Test
  public void build_withANodeViewAgentAsTheAgent_throwsIllegalStateException() {
    Runner.Builder builder =
        Runner.builder().agent(new NodeViewAgent(TestNode.of("solo"))).appName(APP_NAME);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  @SuppressWarnings("deprecation") // The deprecated constructors must reject it as well.
  public void constructor_withANodeViewAgent_throwsIllegalArgumentException() {
    NodeViewAgent agent = new NodeViewAgent(TestNode.of("solo"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Runner(
                agent, APP_NAME, new InMemoryArtifactService(), new InMemorySessionService()));
  }

  @Test
  public void runAsync_nodeRoot_runsTheNodeOnTheUserMessage() {
    List<Object> inputs = new ArrayList<>();
    Node node =
        new TestNode(
            "solo",
            (context, input) -> {
              inputs.add(input);
              return Flowable.empty();
            });
    Runner runner = runner(node);
    Session session = newSession(runner);

    runner.runAsync(USER_ID, session.id(), USER_MESSAGE).blockingSubscribe();

    assertThat(inputs).containsExactly(USER_MESSAGE);
  }

  @Test
  public void runAsync_nodeRoot_persistsItsStampedEventAndState() {
    Node node =
        new TestNode(
            "solo",
            (context, input) -> {
              context.state().put("durable", "saved");
              return Flowable.just("echoed");
            });
    Runner runner = runner(node);
    Session session = newSession(runner);

    List<Event> events =
        runner.runAsync(USER_ID, session.id(), USER_MESSAGE).toList().blockingGet();

    Event event = getOnlyElement(events);
    Session saved = reload(runner, session);
    assertThat(event.author()).isEqualTo("solo");
    assertThat(event.nodeInfo().map(NodeInfo::path)).hasValue("solo@1");
    assertThat(event.output()).hasValue("echoed");
    assertThat(getLast(saved.immutableEvents()).id()).isEqualTo(event.id());
    assertThat(saved.state()).containsEntry("durable", "saved");
  }

  @Test
  public void runAsync_workflowRoot_runsItsGraphAndPersistsEveryEvent() {
    Node first = new TestNode("first", (context, input) -> Flowable.just("one"));
    Node second = new TestNode("second", (context, input) -> Flowable.just("two"));
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(new Edge(Start.INSTANCE, first), new Edge(first, second))
            .build();
    Runner runner = runner(workflow);
    Session session = newSession(runner);

    List<Event> events =
        runner.runAsync(USER_ID, session.id(), USER_MESSAGE).toList().blockingGet();

    Session saved = reload(runner, session);
    assertThat(
            events.stream()
                .map(event -> event.nodeInfo().map(NodeInfo::path))
                .collect(toImmutableList()))
        .containsExactly(Optional.of("flow@1/first@1"), Optional.of("flow@1/second@1"))
        .inOrder();
    assertThat(events.stream().map(Event::author).collect(toImmutableList()))
        .containsExactly("flow", "flow");
    assertThat(getLast(saved.immutableEvents()).id()).isEqualTo(getLast(events).id());
  }

  @Test
  public void close_workflowRoot_closesTheAgentsInItsGraphAndNestedWorkflows() {
    List<String> closed = new ArrayList<>();
    BaseAgent outerAgent = closeRecordingAgent("outer_agent", closed);
    BaseAgent innerAgent = closeRecordingAgent("inner_agent", closed);
    Workflow inner =
        Workflow.builder().name("inner").edges(new Edge(Start.INSTANCE, innerAgent)).build();
    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .edges(new Edge(Start.INSTANCE, outerAgent), new Edge(outerAgent, inner))
            .build();

    runner(workflow).close().blockingAwait();

    assertThat(closed).containsExactly("outer_agent", "inner_agent");
  }

  @Test
  public void runAsync_failingNodeRoot_persistsItsErrorEventBeforeFailing() {
    IllegalStateException failure = new IllegalStateException("node failed");
    Runner runner = runner(failingNode(failure));
    Session session = newSession(runner);

    TestSubscriber<Event> subscriber = runner.runAsync(USER_ID, session.id(), USER_MESSAGE).test();
    subscriber.awaitDone(5, SECONDS);

    subscriber.assertError(failure);
    Event errorEvent = getOnlyElement(subscriber.values());
    Session saved = reload(runner, session);
    assertThat(errorEvent.errorCode()).hasValue(new FinishReason("IllegalStateException"));
    assertThat(errorEvent.nodeInfo().map(NodeInfo::path)).hasValue("boom@1");
    assertThat(getLast(saved.immutableEvents()).id()).isEqualTo(errorEvent.id());
    assertThat(saved.state()).containsEntry("partial_step", "done");
  }

  @Test
  public void runAsync_failingNodeRoot_reportsTheErrorToPluginsWithoutAfterRun() {
    IllegalStateException failure = new IllegalStateException("node failed");
    RecordingPlugin plugin = new RecordingPlugin();
    Runner runner = runner(failingNode(failure), plugin);
    Session session = newSession(runner);

    TestSubscriber<Event> subscriber = runner.runAsync(USER_ID, session.id(), USER_MESSAGE).test();
    subscriber.awaitDone(5, SECONDS);

    Event errorEvent = getOnlyElement(subscriber.values());
    assertThat(ids(plugin.events)).containsExactly(errorEvent.id());
    assertThat(plugin.runErrors).containsExactly(failure);
    assertThat(plugin.afterRunCount).isEqualTo(0);
  }

  @Test
  public void runAsync_onEventCallbackFailure_cancelsTheNodeRootAndFails() {
    IllegalStateException callbackFailure = new IllegalStateException("onEvent failed");
    AtomicBoolean cancelled = new AtomicBoolean();
    Node node =
        new TestNode(
            "emitter",
            (context, input) ->
                Flowable.just(contentEvent("first"))
                    .concatWith(Flowable.never())
                    .doOnCancel(() -> cancelled.set(true)));
    Plugin failingPlugin =
        new BasePlugin("failing") {
          @Override
          public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
            return Maybe.error(callbackFailure);
          }
        };
    Runner runner = runner(node, failingPlugin);
    Session session = newSession(runner);

    TestSubscriber<Event> subscriber = runner.runAsync(USER_ID, session.id(), USER_MESSAGE).test();
    subscriber.awaitDone(5, SECONDS);

    subscriber.assertError(callbackFailure);
    subscriber.assertNoValues();
    assertThat(cancelled.get()).isTrue();
  }

  @Test
  public void runAsync_beforeRunCallbackContent_skipsTheNodeRootAndRunsAfterRun() {
    Content halted = Content.fromParts(Part.fromText("halted by plugin"));
    AtomicBoolean nodeRan = new AtomicBoolean();
    Node node =
        new TestNode(
            "should_not_run",
            (context, input) -> {
              nodeRan.set(true);
              return Flowable.just("unreachable");
            });
    RecordingPlugin plugin =
        new RecordingPlugin() {
          @Override
          public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
            return Maybe.just(halted);
          }
        };
    Runner runner = runner(node, plugin);
    Session session = newSession(runner);

    List<Event> events =
        runner.runAsync(USER_ID, session.id(), USER_MESSAGE).toList().blockingGet();

    assertThat(nodeRan.get()).isFalse();
    assertThat(getOnlyElement(events).content()).hasValue(halted);
    assertThat(plugin.afterRunCount).isEqualTo(1);
  }

  @Test
  public void runAsync_onUserMessageCallback_replacesTheNodeRootsInput() {
    Content rewritten = Content.fromParts(Part.fromText("modified text"));
    List<Object> inputs = new ArrayList<>();
    Node node =
        new TestNode(
            "recorder",
            (context, input) -> {
              inputs.add(input);
              return Flowable.empty();
            });
    Plugin rewritingPlugin =
        new BasePlugin("rewriting") {
          @Override
          public Maybe<Content> onUserMessageCallback(
              InvocationContext invocationContext, Content userMessage) {
            return Maybe.just(rewritten);
          }
        };
    Runner runner = runner(node, rewritingPlugin);
    Session session = newSession(runner);

    runner.runAsync(USER_ID, session.id(), USER_MESSAGE).blockingSubscribe();

    assertThat(inputs).containsExactly(rewritten);
  }

  @Test
  public void runAsync_partialEventOfANodeRoot_isStreamedButNotPersisted() {
    Node node =
        new TestNode(
            "streamer",
            (context, input) ->
                Flowable.just(
                    contentEvent("part").toBuilder().partial(true).build(), contentEvent("final")));
    Runner runner = runner(node);
    Session session = newSession(runner);

    List<Event> events =
        runner.runAsync(USER_ID, session.id(), USER_MESSAGE).toList().blockingGet();

    assertThat(texts(events)).containsExactly("part", "final").inOrder();
    assertThat(
            texts(
                reload(runner, session).immutableEvents().stream()
                    .filter(event -> event.author().equals("streamer"))
                    .collect(toImmutableList())))
        .containsExactly("final");
  }

  @Test
  public void runAsync_takingTheFirstEventOfANodeRoot_cancelsTheNode() {
    AtomicBoolean cancelled = new AtomicBoolean();
    Node node =
        new TestNode(
            "streamer",
            (context, input) ->
                Flowable.just(contentEvent("first"))
                    .concatWith(Flowable.never())
                    .doOnCancel(() -> cancelled.set(true)));
    Runner runner = runner(node);
    Session session = newSession(runner);

    TestSubscriber<Event> subscriber =
        runner.runAsync(USER_ID, session.id(), USER_MESSAGE).take(1).test();
    subscriber.awaitDone(5, SECONDS);

    subscriber.assertComplete();
    assertThat(texts(subscriber.values())).containsExactly("first");
    assertThat(cancelled.get()).isTrue();
  }

  @Test
  public void runAsync_resumingANodeRootInvocation_failsWithoutRunningTheNodeAgain() {
    AtomicInteger runs = new AtomicInteger();
    Node node =
        new TestNode(
            "solo",
            (context, input) -> {
              runs.incrementAndGet();
              return Flowable.just("done");
            });
    Runner runner =
        runner(
            App.builder()
                .name(APP_NAME)
                .rootNode(node)
                .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
                .build());
    Session session = newSession(runner);
    String invocationId =
        runner.runAsync(USER_ID, session.id(), USER_MESSAGE).blockingFirst().invocationId();

    TestSubscriber<Event> subscriber =
        runner
            .runAsync(
                USER_ID,
                session.id(),
                invocationId,
                /* newMessage= */ null,
                RunConfig.builder().build(),
                /* stateDelta= */ null)
            .test();
    subscriber.awaitDone(5, SECONDS);

    subscriber.assertError(UnsupportedOperationException.class);
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  public void runAsync_textWithAnIdentifiedFunctionResponseToANodeRoot_failsBeforeAnyEvent() {
    AtomicInteger runs = new AtomicInteger();
    Runner runner = runner(countingNode(runs));
    Session session = newSession(runner);
    Content message = textWithFunctionResponse(IDENTIFIED_RESPONSE);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> runner.runAsync(USER_ID, session.id(), message).toList().blockingGet());

    assertThat(error).hasMessageThat().contains("both function responses and text");
    assertThat(runs.get()).isEqualTo(0);
    assertThat(reload(runner, session).immutableEvents()).isEmpty();
  }

  @Test
  public void runAsync_textWithAnIdentifiedFunctionResponseToAResumableNodeRoot_fails() {
    AtomicInteger runs = new AtomicInteger();
    Runner runner =
        runner(
            App.builder()
                .name(APP_NAME)
                .rootNode(countingNode(runs))
                .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
                .build());
    Session session = newSession(runner);
    Content message = textWithFunctionResponse(IDENTIFIED_RESPONSE);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> runner.runAsync(USER_ID, session.id(), message).toList().blockingGet());

    assertThat(error).hasMessageThat().contains("both function responses and text");
    assertThat(runs.get()).isEqualTo(0);
  }

  @Test
  public void runAsync_textWithAFunctionResponseWithoutIdToANodeRoot_runsTheNode() {
    AtomicInteger runs = new AtomicInteger();
    Runner runner = runner(countingNode(runs));
    Session session = newSession(runner);
    Content message =
        textWithFunctionResponse(
            FunctionResponse.builder().name("tool").response(ImmutableMap.of("v", 1)).build());

    runner.runAsync(USER_ID, session.id(), message).blockingSubscribe();

    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  public void runAsync_textWithAnIdentifiedFunctionResponseToAnAgentRoot_runsTheAgent() {
    TestBaseAgent agent = new TestBaseAgent("solo_agent", "An agent.", null, null, Flowable::empty);
    Runner runner = runner(App.builder().name(APP_NAME).rootAgent(agent).build());
    Session session = newSession(runner);

    runner
        .runAsync(USER_ID, session.id(), textWithFunctionResponse(IDENTIFIED_RESPONSE))
        .blockingSubscribe();

    assertThat(agent.getInvocationCount()).isEqualTo(1);
  }

  @Test
  public void runLive_nodeRoot_failsWithUnsupportedOperationExceptionBeforeAnyAgentCallback() {
    AtomicBoolean agentCallbackRan = new AtomicBoolean();
    Plugin agentCallbackRecorder =
        new BasePlugin("agent_callback_recorder") {
          @Override
          public Maybe<Content> beforeAgentCallback(
              BaseAgent agent, CallbackContext callbackContext) {
            agentCallbackRan.set(true);
            return Maybe.empty();
          }
        };
    Runner runner = runner(TestNode.of("solo"), agentCallbackRecorder);
    Session session = newSession(runner);

    TestSubscriber<Event> subscriber =
        runner.runLive(session, new LiveRequestQueue(), RunConfig.builder().build()).test();
    subscriber.awaitDone(5, SECONDS);

    subscriber.assertError(UnsupportedOperationException.class);
    assertThat(agentCallbackRan.get()).isFalse();
  }

  private static Runner runner(Node node, Plugin... plugins) {
    return runner(App.builder().name(APP_NAME).rootNode(node).plugins(plugins).build());
  }

  private static Runner runner(App app) {
    return Runner.builder().app(app).build();
  }

  private static Session newSession(Runner runner) {
    return runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
  }

  private static Session reload(Runner runner, Session session) {
    return runner
        .sessionService()
        .getSession(APP_NAME, USER_ID, session.id(), Optional.empty())
        .blockingGet();
  }

  private static BaseAgent closeRecordingAgent(String name, List<String> closed) {
    return new TestBaseAgent(name, "An agent.", null, null, Flowable::empty) {
      @Override
      public Completable close() {
        closed.add(name());
        return Completable.complete();
      }
    };
  }

  private static Node failingNode(Exception failure) {
    return new TestNode(
        "boom",
        (context, input) -> {
          context.state().put("partial_step", "done");
          return Flowable.error(failure);
        });
  }

  private static Node countingNode(AtomicInteger runs) {
    return new TestNode(
        "solo",
        (context, input) -> {
          runs.incrementAndGet();
          return Flowable.empty();
        });
  }

  private static Content textWithFunctionResponse(FunctionResponse response) {
    return Content.builder()
        .role("user")
        .parts(Part.fromText("some text"), Part.builder().functionResponse(response).build())
        .build();
  }

  private static Event contentEvent(String text) {
    return Event.builder()
        .id(Event.generateEventId())
        .author("")
        .content(Content.fromParts(Part.fromText(text)))
        .build();
  }

  private static ImmutableList<String> ids(List<Event> events) {
    return events.stream().map(Event::id).collect(toImmutableList());
  }

  private static ImmutableList<String> texts(List<Event> events) {
    return events.stream().map(Event::stringifyContent).collect(toImmutableList());
  }

  /**
   * A plugin that records the events, run errors, and completed runs reported to it by the runner.
   */
  private static class RecordingPlugin extends BasePlugin {
    final List<Event> events = new ArrayList<>();
    final List<Throwable> runErrors = new ArrayList<>();
    int afterRunCount;

    RecordingPlugin() {
      super("recording");
    }

    @Override
    public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
      events.add(event);
      return Maybe.empty();
    }

    @Override
    public Completable onRunErrorCallback(InvocationContext invocationContext, Throwable error) {
      runErrors.add(error);
      return Completable.complete();
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
      afterRunCount++;
      return Completable.complete();
    }
  }
}
