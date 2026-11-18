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

import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.throwing;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.LONG_RUNNING_CALL;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.callContent;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.interrupt;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestNode;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class IsolationScopeTest {

  private static final RunNodeConfig TASK_SCOPE =
      RunNodeConfig.builder().overrideIsolationScope("task-1").build();

  @Test
  public void runNode_overrideIsolationScope_scopesTheChildsEventsOnly() {
    Node caller = runningInScope("caller", emitter("child", "C"), TASK_SCOPE);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopeAt(events, "wf@1/caller@1/child@1")).hasValue("task-1");
    assertThat(scopeAt(events, "wf@1/caller@1")).isEmpty();
  }

  @Test
  public void runNode_overrideIsolationScope_reachesTheChildsGraphNodes() {
    Node inner = workflow("inner", new Edge(START, emitter("step", "S")));
    Node caller = runningInScope("caller", inner, TASK_SCOPE);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopeAt(events, "wf@1/caller@1/inner@1/step@1")).hasValue("task-1");
  }

  @Test
  public void runNode_childOfAScopedCallerWithoutAnOverride_inheritsTheScope() {
    Node child =
        runningInScope("child", emitter("grandchild", "G"), RunNodeConfig.builder().build());
    Node caller = runningInScope("caller", child, TASK_SCOPE);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopeAt(events, "wf@1/caller@1/child@1/grandchild@1")).hasValue("task-1");
  }

  @Test
  public void runNode_scopedNodeThatOnlySetsState_flushesItsDeltaInTheScope() {
    Node child =
        new TestNode(
            "child",
            (ctx, input) -> {
              ctx.state().put("color", "blue");
              return Flowable.empty();
            });
    Node caller = runningInScope("caller", child, TASK_SCOPE);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopeAt(events, "wf@1/caller@1/child@1")).hasValue("task-1");
  }

  @Test
  public void runNode_failingScopedNode_reportsTheFailureInTheScope() {
    Node caller =
        runningInScope("caller", throwing("child", new IllegalStateException("boom")), TASK_SCOPE);

    var failed = new WorkflowSession(workflow(new Edge(START, caller))).failingTurn(go());

    assertThat(scopeAt(failed.events(), "wf@1/caller@1/child@1")).hasValue("task-1");
  }

  @Test
  public void runNode_overrideIsolationScopeInAScope_replacesTheInheritedScope() {
    Node child =
        runningInScope(
            "child",
            emitter("grandchild", "G"),
            RunNodeConfig.builder().overrideIsolationScope("task-2").build());
    Node caller = runningInScope("caller", child, TASK_SCOPE);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopeAt(events, "wf@1/caller@1/child@1/grandchild@1")).hasValue("task-2");
  }

  @Test
  public void runNode_childEventWithItsOwnScope_keepsIt() {
    Node child =
        new TestNode(
            "child",
            (ctx, input) ->
                Flowable.just(newEvent().content(modelText("hi")).isolationScope("own").build()));
    Node caller = runningInScope("caller", child, TASK_SCOPE);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopeAt(events, "wf@1/caller@1/child@1")).hasValue("own");
  }

  @Test
  public void answer_toACallMadeInAScope_takesTheCallsScope() {
    Node caller = runningInScope("caller", longRunningAsk("child", "ask"), TASK_SCOPE);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(answer("ask", ImmutableMap.of("approved", true)));

    assertThat(answerIn(session.storedEvents()).isolationScope()).hasValue("task-1");
  }

  @Test
  public void resumedScopedChild_keepsTheScopeInItsLaterEvents() {
    // Reruns on resume, so it emits again after the answer.
    Node child =
        dispatcher(
            "child",
            (ctx, input) ->
                ctx.resumeInputs().containsKey("ask")
                    ? Flowable.just(newEvent().content(modelText("done")).build())
                    : Flowable.just(interrupt("ask")));
    Node caller = runningInScope("caller", child, TASK_SCOPE);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(answer("ask", ImmutableMap.of("approved", true)));

    assertThat(
            session.storedEvents().stream()
                .filter(event -> pathOf(event).equals("wf@1/caller@1/child@1"))
                .filter(event -> event.content().isPresent())
                .map(Event::isolationScope))
        .containsExactly(Optional.of("task-1"), Optional.of("task-1"));
  }

  @Test
  public void resumedChildThatRanUnscoped_takesTheScopeItsCallerNowPasses() {
    AtomicInteger runs = new AtomicInteger();
    Node child =
        dispatcher(
            "child",
            (ctx, input) ->
                ctx.resumeInputs().containsKey("ask")
                    ? Flowable.just(newEvent().content(modelText("done")).build())
                    : Flowable.just(interrupt("ask")));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) -> {
              RunNodeConfig.Builder config = RunNodeConfig.builder();
              // Unscoped on the first run, scoped on the rerun.
              if (runs.incrementAndGet() > 1) {
                config.overrideIsolationScope("scope-2");
              }
              return ctx.runNode(child, null, config.build()).toFlowable();
            });
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(answer("ask", ImmutableMap.of("approved", true)));

    // As in Python, an empty recorded scope gives way to the caller's.
    assertThat(
            session.storedEvents().stream()
                .filter(event -> pathOf(event).equals("wf@1/caller@1/child@1"))
                .filter(event -> event.content().isPresent())
                .map(Event::isolationScope))
        .containsExactly(Optional.empty(), Optional.of("scope-2"))
        .inOrder();
  }

  @Test
  public void resumedChildOfACallerThatPicksANewScopeEachRun_keepsItsFirstScope() {
    AtomicInteger runs = new AtomicInteger();
    // Reruns on resume, so it emits again after the answer.
    Node child =
        dispatcher(
            "child",
            (ctx, input) ->
                ctx.resumeInputs().containsKey("ask")
                    ? Flowable.just(newEvent().content(modelText("done")).build())
                    : Flowable.just(interrupt("ask")));
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(
                        child,
                        null,
                        RunNodeConfig.builder()
                            .overrideIsolationScope("scope-" + runs.incrementAndGet())
                            .build())
                    .toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(answer("ask", ImmutableMap.of("approved", true)));

    assertThat(
            session.storedEvents().stream()
                .filter(event -> pathOf(event).equals("wf@1/caller@1/child@1"))
                .filter(event -> event.content().isPresent())
                .map(Event::isolationScope))
        .containsExactly(Optional.of("scope-1"), Optional.of("scope-1"));
  }

  @Test
  public void answer_replacedByAPluginWithAnAnswerToAnotherInvocation_isRejected() {
    AtomicInteger runs = new AtomicInteger();
    // Each run waits on a call of its own, so two turns leave two invocations waiting.
    Node caller =
        runningInScope(
            "caller",
            dispatcher(
                "child", (ctx, input) -> Flowable.just(interrupt("ask-" + runs.incrementAndGet()))),
            TASK_SCOPE);
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, caller)),
            /* resumable= */ true,
            new AnswerSwappingPlugin("ask-2", answer("ask-1", ImmutableMap.of("approved", true))));
    session.advance(go());
    session.advance(go());

    var failed = session.failingTurn(answer("ask-2", ImmutableMap.of("approved", true)));

    assertThat(session.invocationIds()).hasSize(2);
    assertThat(failed.error()).hasMessageThat().contains("another invocation");
  }

  @Test
  public void answerToCallsInTwoScopes_isRejected() {
    Node first = runningInScope("first", longRunningAsk("child", "ask-1"), TASK_SCOPE);
    Node second =
        runningInScope(
            "second",
            longRunningAsk("child", "ask-2"),
            RunNodeConfig.builder().overrideIsolationScope("task-2").build());
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, first), new Edge(START, second)));
    session.advance(go());
    Content bothAnswers =
        Content.builder()
            .role("user")
            .parts(
                answer("ask-1", ImmutableMap.of("approved", true)).parts().get().get(0),
                answer("ask-2", ImmutableMap.of("approved", true)).parts().get().get(0))
            .build();

    var failed = session.failingTurn(bothAnswers);

    assertThat(failed.error()).hasMessageThat().contains("span 2 isolation scopes");
  }

  @Test
  public void answer_replacedByAPlugin_takesTheScopeOfTheCallItNowAnswers() {
    Node first = runningInScope("first", longRunningAsk("child", "ask-1"), TASK_SCOPE);
    Node second =
        runningInScope(
            "second",
            longRunningAsk("child", "ask-2"),
            RunNodeConfig.builder().overrideIsolationScope("task-2").build());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, first), new Edge(START, second)),
            /* resumable= */ true,
            new AnswerSwappingPlugin("ask-1", answer("ask-2", ImmutableMap.of("approved", true))));
    session.advance(go());

    session.advance(answer("ask-1", ImmutableMap.of("approved", true)));

    Event stored = answerIn(session.storedEvents());
    assertThat(stored.functionResponses().get(0).id()).hasValue("ask-2");
    assertThat(stored.isolationScope()).hasValue("task-2");
    assertThat(stored.branch()).isEqualTo(callEventFor(session, "ask-2").branch());
  }

  @Test
  public void answer_toAScopedCallUnderAnAgentRoot_takesTheCallsScope() {
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(createTestLlm(createTextLlmResponse("ok"))).build();
    Runner runner =
        Runner.builder().app(App.builder().name("app").rootAgent(agent).build()).build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();
    Event scopedCall =
        newEvent()
            .author("agent")
            .content(callContent(LONG_RUNNING_CALL, "ask"))
            .isolationScope("task-1")
            .build();
    runner.sessionService().appendEvent(session, scopedCall).ignoreElement().blockingAwait();

    runner
        .runAsync("user", session.id(), answer("ask", ImmutableMap.of("approved", true)))
        .blockingSubscribe();

    Session stored =
        runner
            .sessionService()
            .getSession("app", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(answerIn(stored.immutableEvents()).isolationScope()).hasValue("task-1");
  }

  /** Replaces the user's answer to {@code callId} with {@code replacement}. */
  private static final class AnswerSwappingPlugin extends BasePlugin {
    private final String callId;
    private final Content replacement;

    AnswerSwappingPlugin(String callId, Content replacement) {
      super("answer-swapping");
      this.callId = callId;
      this.replacement = replacement;
    }

    @Override
    public Maybe<Content> onUserMessageCallback(
        InvocationContext invocationContext, Content userMessage) {
      boolean answersCall =
          userMessage.parts().orElse(ImmutableList.of()).stream()
              .anyMatch(part ->
                  part.functionResponse()
                      .flatMap(FunctionResponse::id)
                      .filter(callId::equals)
                      .isPresent());
      return answersCall ? Maybe.just(replacement) : Maybe.empty();
    }
  }

  private static Event callEventFor(WorkflowSession session, String callId) {
    return session.storedEvents().stream()
        .filter(event ->
            event.functionCalls().stream().anyMatch(c -> c.id().equals(Optional.of(callId))))
        .collect(onlyElement());
  }

  private static Event answerIn(List<Event> events) {
    return events.stream()
        .filter(event -> !event.functionResponses().isEmpty())
        .collect(onlyElement());
  }

  /** Returns a node that runs {@code child} with {@code config} and emits the child's output. */
  private static Node runningInScope(String name, Node child, RunNodeConfig config) {
    return dispatcher(name, (ctx, input) -> ctx.runNode(child, null, config).toFlowable());
  }

  private static Optional<String> scopeAt(List<Event> events, String nodePath) {
    return events.stream()
        .filter(event -> pathOf(event).equals(nodePath))
        .collect(onlyElement())
        .isolationScope();
  }
}
