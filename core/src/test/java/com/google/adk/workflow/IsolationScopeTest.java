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

import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
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

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class IsolationScopeTest {

  private static final RunNodeConfig TASK_SCOPE =
      RunNodeConfig.builder().overrideIsolationScope("task-1").build();
  private static final String SINGLE_TURN_NOTE =
      "Important: You will not receive any user replies or clarifications."
          + " Complete the task using only the information provided above.";

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

  @Test
  public void scopedAgent_doesNotSeeUnscopedEvents() {
    Node earlier = emitting("earlier", modelText("earlier reply"));
    TestLlm model = TestLlm.create("done");
    Node caller = runningInScope("caller", agent(model), TASK_SCOPE);

    new WorkflowSession(workflow(new Edge(START, earlier), new Edge(earlier, caller)))
        .advance(go());

    assertThat(model.getLastRequest().contents()).containsExactly(go());
  }

  @Test
  public void unscopedAgent_doesNotSeeScopedEvents() {
    Node caller = runningInScope("caller", emitting("child", modelText("task reply")), TASK_SCOPE);
    TestLlm model = TestLlm.create("done");
    Node after = agent(model);

    new WorkflowSession(workflow(new Edge(START, caller), new Edge(caller, after))).advance(go());

    assertThat(model.getLastRequest().contents()).containsExactly(go());
  }

  @Test
  public void scopedAgent_opensWithTheArgsOfTheCallWhoseIdIsTheScope() {
    TestLlm model = TestLlm.create("done");

    runAfterDelegation(ImmutableMap.of("request", "book a flight"), agent(model), null);

    assertThat(model.getLastRequest().contents())
        .containsExactly(userTurn(Part.fromText("{\"request\":\"book a flight\"}")));
  }

  @Test
  public void scopedSingleTurnAgent_afterADelegatingCall_getsOnlyItsInput() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Paris"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).mode(AgentMode.SINGLE_TURN).build();

    runAfterDelegation(ImmutableMap.of("request", "book a flight"), agent, "Capital?");

    assertThat(model.getLastRequest().contents())
        .containsExactly(userTurn(Part.fromText("Capital?")));
  }

  @Test
  public void scopedSingleTurnAgentWithoutInput_opensWithTheCallsArgsAndANoReplyNote() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Booked."));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).mode(AgentMode.SINGLE_TURN).build();

    runAfterDelegation(ImmutableMap.of("request", "book a flight"), agent, null);

    assertThat(model.getLastRequest().contents())
        .containsExactly(
            userTurn(
                Part.fromText("{\"request\":\"book a flight\"}"), Part.fromText(SINGLE_TURN_NOTE)));
  }

  @Test
  public void scopedAgent_callWithoutArgs_opensWithTheUsersMessage() {
    TestLlm model = TestLlm.create("done");

    runAfterDelegation(ImmutableMap.of(), agent(model), null);

    assertThat(model.getLastRequest().contents()).containsExactly(go());
  }

  /** Runs {@code agent} on {@code input} in scope {@code task-1} after a call to {@code task-1}. */
  private static void runAfterDelegation(
      ImmutableMap<String, Object> args, LlmAgent agent, @Nullable Object input) {
    Content delegation =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder().id("task-1").name("delegate").args(args).build())
                    .build())
            .build();
    Node delegator = emitting("delegator", delegation);
    Node caller =
        dispatcher("caller", (ctx, unused) -> ctx.runNode(agent, input, TASK_SCOPE).toFlowable());
    new WorkflowSession(workflow(new Edge(START, delegator), new Edge(delegator, caller)))
        .advance(go());
  }

  @Test
  public void scopedSingleTurnAgent_getsOnlyItsInputNotTheUsersMessage() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Paris"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).mode(AgentMode.SINGLE_TURN).build();
    Node caller =
        dispatcher(
            "caller", (ctx, input) -> ctx.runNode(agent, "Capital?", TASK_SCOPE).toFlowable());

    new WorkflowSession(workflow(new Edge(START, caller))).advance(go());

    assertThat(model.getLastRequest().contents())
        .containsExactly(userTurn(Part.fromText("Capital?")));
  }

  @Test
  public void scopedSingleTurnAgent_inputEqualToTheUsersMessage_stillGetsItInItsScope() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Paris"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).mode(AgentMode.SINGLE_TURN).build();
    Node caller =
        dispatcher("caller", (ctx, input) -> ctx.runNode(agent, go(), TASK_SCOPE).toFlowable());

    new WorkflowSession(workflow(new Edge(START, caller))).advance(go());

    assertThat(model.getLastRequest().contents()).containsExactly(go());
  }

  @Test
  public void scopedAgentWithoutHistoryOrATurnInItsScope_opensWithTheUsersMessage() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .includeContents(LlmAgent.IncludeContents.NONE)
            .build();
    Node caller = runningInScope("caller", agent, TASK_SCOPE);

    new WorkflowSession(workflow(new Edge(START, caller))).advance(go());

    assertThat(model.getLastRequest().contents()).containsExactly(go());
  }

  @Test
  public void scopedAgentWithoutHistoryOrATurnInItsScope_opensWithTheDelegatingCallsArgs() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .includeContents(LlmAgent.IncludeContents.NONE)
            .build();

    runAfterDelegation(ImmutableMap.of("request", "book a flight"), agent, null);

    assertThat(model.getLastRequest().contents())
        .containsExactly(userTurn(Part.fromText("{\"request\":\"book a flight\"}")));
  }

  @Test
  public void scopedAgentWithoutHistoryOrATurnInItsScope_seesItsOwnToolCallAndResponse() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "echo_tool", ImmutableMap.of("x", 1)),
            createTextLlmResponse("done"));
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .includeContents(LlmAgent.IncludeContents.NONE)
            .tools(new EchoTool())
            .build();

    runAfterDelegation(ImmutableMap.of("request", "book a flight"), agent, null);

    List<Content> contents = model.getLastRequest().contents();
    assertThat(contents).hasSize(3);
    assertThat(contents.get(0))
        .isEqualTo(userTurn(Part.fromText("{\"request\":\"book a flight\"}")));
    assertThat(contents.get(1).parts().orElseThrow().get(0).functionCall()).isPresent();
    assertThat(contents.get(2).parts().orElseThrow().get(0).functionResponse()).isPresent();
  }

  @Test
  public void scopedAgent_seesTheUsersAnswerToItsCall() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "pendingTool", ImmutableMap.of("message", "hi")),
            createTextLlmResponse("done"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).tools(pendingFunctionTool()).build();
    Node caller = runningInScope("caller", agent, TASK_SCOPE);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(answer("call_1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(
            model.getLastRequest().contents().stream()
                .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
                .flatMap(part -> part.functionResponse().stream())
                .map(response -> response.response().orElse(ImmutableMap.of())))
        .contains(ImmutableMap.of("status", "approved"));
  }

  /** Returns a node that runs {@code child} with {@code config} and emits the child's output. */
  private static Node runningInScope(String name, Node child, RunNodeConfig config) {
    return dispatcher(name, (ctx, input) -> ctx.runNode(child, null, config).toFlowable());
  }

  private static Node emitting(String name, Content content) {
    return new TestNode(name, (ctx, input) -> Flowable.just(newEvent().content(content).build()));
  }

  private static LlmAgent agent(TestLlm model) {
    return LlmAgent.builder().name("agent").model(model).build();
  }

  private static Content userTurn(Part... parts) {
    return Content.builder().role("user").parts(parts).build();
  }

  private static Optional<String> scopeAt(List<Event> events, String nodePath) {
    return events.stream()
        .filter(event -> pathOf(event).equals(nodePath))
        .collect(onlyElement())
        .isolationScope();
  }
}
