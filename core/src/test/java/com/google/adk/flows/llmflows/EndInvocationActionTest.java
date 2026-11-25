/*
 * Copyright 2025 Google LLC
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

package com.google.adk.flows.llmflows;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EndInvocationActionTest {

  private static class EndInvocationTool extends BaseTool {
    public EndInvocationTool() {
      super("end_invocation", "Ends the current invocation.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder()
              .name(name())
              .description(description())
              .parameters(Schema.builder().type("OBJECT").build()) // No parameters needed
              .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      toolContext.setActions(toolContext.actions().toBuilder().endInvocation(true).build());
      return Single.just(ImmutableMap.of());
    }
  }

  @Test
  public void endInvocationTool_stopsFlow() {
    Content endInvocationCallContent =
        Content.fromParts(Part.fromFunctionCall("end_invocation", ImmutableMap.of()));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));

    var testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(endInvocationCallContent)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)));

    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .tools(ImmutableList.of(new EndInvocationTool()))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());

    List<Event> actualEvents = new ArrayList<>();
    runRunner(runner, invocationContext, actualEvents);

    assertThat(simplifyEvents(actualEvents))
        .containsExactly(
            "root_agent: FunctionCall(name=end_invocation, args={})",
            "root_agent: FunctionResponse(name=end_invocation, response={})")
        .inOrder();
  }

  @Test
  public void runLive_endInvocationTool_keepsCallUp() throws Exception {
    UnicastProcessor<LlmResponse> responses = UnicastProcessor.create();
    TestLlm testLlm = createTestLlm(() -> responses);
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .executor(Runnable::run)
            .tools(ImmutableList.of(new EndInvocationTool()))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);
    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner
            .runLive(invocationContext.session(), liveRequestQueue, RunConfig.builder().build())
            .test();
    responses.onNext(
        createLlmResponse(
            Content.fromParts(Part.fromFunctionCall("end_invocation", ImmutableMap.of()))));
    responses.onNext(createLlmResponse(Content.fromParts(Part.fromText("still here"))));
    liveRequestQueue.content(Content.fromParts(Part.fromText("next turn")));
    // The call is still up: the next turn reached the model and no connection was closed.
    assertThat(testLlm.waitForLiveContentText("next turn", Duration.ofSeconds(5))).isTrue();
    assertThat(testLlm.getLiveRequestHistory().stream().filter(LiveRequest::shouldClose)).isEmpty();
    responses.onComplete();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "root_agent: FunctionCall(name=end_invocation, args={})",
            "root_agent: FunctionResponse(name=end_invocation, response={})",
            "root_agent: still here")
        .inOrder();
    assertThat(functionResponsesSent(testLlm)).containsExactly("end_invocation");
  }

  @Test
  public void runLive_endInvocationAfterTransfer_keepsCallUp() throws Exception {
    CompletableSubject agentXDone = CompletableSubject.create();
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(
                createLlmResponse(
                    Content.fromParts(
                        Part.fromFunctionCall(
                            "transfer_to_agent", ImmutableMap.of("agent_name", "x"))))),
            Flowable.just(
                    createLlmResponse(
                        Content.fromParts(
                            Part.fromFunctionCall("end_invocation", ImmutableMap.of()))),
                    createLlmResponse(Content.fromParts(Part.fromText("still here"))))
                .concatWith(agentXDone.toFlowable()));
    LlmAgent agentX =
        createTestAgentBuilder(testLlm)
            .name("x")
            .executor(Runnable::run)
            .tools(ImmutableList.of(new EndInvocationTool()))
            .build();
    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm).name("a").executor(Runnable::run).subAgents(agentX).build();
    Runner runner = new InMemoryRunner(rootAgent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, new LiveRequestQueue(), RunConfig.builder().build()).test();
    testSubscriber.awaitCount(5).assertValueCount(5);
    // Only the transfer closes a connection; x's call is still up.
    assertThat(testLlm.getLiveRequestHistory().stream().filter(LiveRequest::shouldClose))
        .hasSize(1);
    agentXDone.onComplete();

    assertThat(testSubscriber.await(5, SECONDS)).isTrue();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "a: FunctionCall(name=transfer_to_agent, args={agent_name=x})",
            "a: FunctionResponse(name=transfer_to_agent, response={})",
            "x: FunctionCall(name=end_invocation, args={})",
            "x: FunctionResponse(name=end_invocation, response={})",
            "x: still here")
        .inOrder();
    assertThat(functionResponsesSent(testLlm))
        .containsExactly("transfer_to_agent", "end_invocation")
        .inOrder();
    // x's call closes when its model stream ends.
    assertThat(testLlm.getLiveRequestHistory().stream().filter(LiveRequest::shouldClose))
        .hasSize(2);
  }

  @Test
  public void runLive_endInvocationToolInSequentialAgent_doesNotEndStep() throws Exception {
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(
                createLlmResponse(
                    Content.fromParts(Part.fromFunctionCall("end_invocation", ImmutableMap.of()))),
                createLlmResponse(Content.fromParts(Part.fromText("after end")))),
            Flowable.just(createLlmResponse(Content.fromParts(Part.fromText("from b")))));
    LlmAgent agentA =
        createTestAgentBuilder(testLlm)
            .name("a")
            .executor(Runnable::run)
            .tools(ImmutableList.of(new EndInvocationTool()))
            .build();
    LlmAgent agentB = createTestAgentBuilder(testLlm).name("b").executor(Runnable::run).build();
    SequentialAgent sequentialAgent =
        SequentialAgent.builder().name("seq").subAgents(agentA, agentB).build();
    Runner runner = new InMemoryRunner(sequentialAgent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, new LiveRequestQueue(), RunConfig.builder().build()).test();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    // endOfAgent does not end the step: its later output still arrives.
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "a: FunctionCall(name=end_invocation, args={})",
            "a: FunctionResponse(name=end_invocation, response={})",
            "a: after end",
            "b: from b")
        .inOrder();
    assertThat(functionResponsesSent(testLlm)).containsExactly("end_invocation");
  }

  private static ImmutableList<String> functionResponsesSent(TestLlm testLlm) {
    return testLlm.getLiveRequestHistory().stream()
        .flatMap(request -> request.content().stream())
        .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
        .flatMap(part -> part.functionResponse().stream())
        .map(response -> response.name().get())
        .collect(toImmutableList());
  }

  private Runner getRunnerAndCreateSession(LlmAgent agent, Session session) {
    Runner runner = new InMemoryRunner(agent, session.appName());

    var unused =
        runner
            .sessionService()
            .createSession(session.appName(), session.userId(), session.state(), session.id())
            .blockingGet();

    return runner;
  }

  private void runRunner(
      Runner runner, InvocationContext invocationContext, List<Event> actualEvents) {
    Session session = invocationContext.session();
    runner
        .runAsync(session.userId(), session.id(), invocationContext.userContent().orElse(null))
        .blockingForEach(actualEvents::add);
  }
}
