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

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for running an {@link LlmAgent} as a workflow node. */
@RunWith(JUnit4.class)
public final class LlmAgentAsNodeTest {

  private static final String APP_NAME = "app";
  private static final String USER_ID = "user";
  private static final NodeRunner.EventSink SINK = (event, nodeFailure) -> Completable.complete();

  @Test
  public void runAsync_singleTurnNode_getsItsNodeInputAsItsUserTurn() {
    TestLlm model = TestLlm.create("done");

    runAfterSource("translate this", singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents())).isEqualTo(userTurn("translate this"));
  }

  @Test
  public void runAsync_singleTurnNode_getsAContentInputAsAUserTurn() {
    TestLlm model = TestLlm.create("done");
    Content input = Content.builder().role("model").parts(Part.fromText("from upstream")).build();

    runAfterSource(input, singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents())).isEqualTo(userTurn("from upstream"));
  }

  @Test
  public void runAsync_singleTurnNode_getsAMapInputAsJson() {
    TestLlm model = TestLlm.create("done");

    runAfterSource(ImmutableMap.of("city", "Kraków", "days", 3), singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents()))
        .isEqualTo(userTurn("{\"city\":\"Kraków\",\"days\":3}"));
  }

  @Test
  public void runAsync_singleTurnNode_getsAListInputAsJson() {
    TestLlm model = TestLlm.create("done");

    runAfterSource(
        ImmutableList.of(1, ImmutableMap.of("tags", ImmutableList.of("a", "b"))),
        singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents()))
        .isEqualTo(userTurn("[1,{\"tags\":[\"a\",\"b\"]}]"));
  }

  @Test
  public void runAsync_firstSingleTurnNode_seesTheUserMessageOnce() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgent("worker", model);

    run(Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, agent)).build(), "go");

    assertThat(model.getLastRequest().contents()).containsExactly(userTurn("go"));
  }

  @Test
  public void runAsync_singleTurnNodeWithoutInput_addsNoUserTurn() {
    TestLlm model = TestLlm.create("done");
    Node silent = new TestNode("source", (context, input) -> Flowable.empty());
    LlmAgent agent = singleTurnAgent("worker", model);

    run(
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, silent), new Edge(silent, agent))
            .build(),
        "go");

    assertThat(model.getLastRequest().contents()).containsExactly(userTurn("go"));
  }

  @Test
  public void runAsync_nodeAfterASingleTurnNode_doesNotSeeItsInput() {
    TestLlm laterModel = TestLlm.create("later");
    Node source = new TestNode("source", (context, input) -> Flowable.just("one-off input"));
    LlmAgent first = singleTurnAgent("first", TestLlm.create("first reply"));
    LlmAgent later = LlmAgent.builder().name("later").model(laterModel).build();

    run(
        Workflow.builder()
            .name("wf")
            .edges(
                new Edge(Start.INSTANCE, source), new Edge(source, first), new Edge(first, later))
            .build(),
        "go");

    assertThat(laterModel.getLastRequest().contents()).doesNotContain(userTurn("one-off input"));
  }

  @Test
  public void runAsync_agentWithoutAMode_doesNotGetItsNodeInput() {
    TestLlm model = TestLlm.create("done");

    runAfterSource("current task", LlmAgent.builder().name("worker").model(model).build());

    assertThat(model.getLastRequest().contents()).doesNotContain(userTurn("current task"));
  }

  @Test
  public void runAsync_agentWithoutAMode_keepsItsIdentity() {
    TestLlm model = TestLlm.create("done");

    runAfterSource("go", LlmAgent.builder().name("worker").model(model).build());

    assertThat(String.join("\n", model.getLastRequest().getSystemInstructions()))
        .contains("Your internal name is \"worker\"");
  }

  @Test
  public void runAsync_singleTurnNode_getsNoIdentity() {
    TestLlm model = TestLlm.create("done");

    runAfterSource("go", singleTurnAgent("worker", model));

    assertThat(String.join("\n", model.getLastRequest().getSystemInstructions()))
        .doesNotContain("Your internal name is");
  }

  @Test
  public void runAsync_singleTurnNodeWithASubAgent_getsTheTransferToolWithoutInstructions() {
    TestLlm model = TestLlm.create("done");
    LlmAgent helper = LlmAgent.builder().name("helper").model(TestLlm.create("help")).build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(model)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(helper)
            .build();

    runAfterSource("go", router);

    LlmRequest request = model.getLastRequest();
    assertThat(request.tools()).containsKey("transfer_to_agent");
    assertThat(String.join("\n", request.getSystemInstructions()))
        .doesNotContain("transfer_to_agent");
  }

  @Test
  public void runAsync_subAgentReachedFromASingleTurnNode_keepsItsIdentity() {
    TestLlm helperModel = TestLlm.create("help");
    LlmAgent helper = LlmAgent.builder().name("helper").model(helperModel).build();
    TestLlm routerModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "transfer_to_agent", ImmutableMap.of("agent_name", "helper")));
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(routerModel)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(helper)
            .build();

    runAfterSource("go", router);

    assertThat(String.join("\n", helperModel.getLastRequest().getSystemInstructions()))
        .contains("Your internal name is \"helper\"");
  }

  @Test
  public void runNode_singleTurnNode_givesItsInputTheNodeBranchAndPath() {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession(APP_NAME, USER_ID).blockingGet();
    List<Event> seenInputs = new ArrayList<>();
    TestLlm model =
        new TestLlm(
            () -> {
              seenInputs.add(getLast(session.immutableEvents()));
              return Flowable.just(createTextLlmResponse("done"));
            });
    LlmAgent agent = singleTurnAgent("worker", model);
    InvocationContext invocationContext =
        createInvocationContext(agent, sessionService, session).toBuilder()
            .branch("wf@1.a@1")
            .build();

    agent
        .runNode(nodeContext(agent, invocationContext), "scoped input")
        .ignoreElements()
        .blockingAwait();

    Event input = getLast(seenInputs);
    assertThat(input.content()).hasValue(userTurn("scoped input"));
    assertThat(input.branch()).hasValue("wf@1.a@1");
    assertThat(input.nodeInfo().map(NodeInfo::path)).hasValue("worker@1");
  }

  @Test
  public void runNode_singleTurnNode_removesItsInputBeforeItCompletes() {
    // A successor can start on completion, so the input must already be gone.
    LlmAgent agent = singleTurnAgent("worker", TestLlm.create("done"));
    InvocationContext invocationContext = createInvocationContext(agent);
    List<ImmutableList<Event>> eventsAtCompletion = new ArrayList<>();

    agent
        .runNode(nodeContext(agent, invocationContext), "input")
        .doOnComplete(() -> eventsAtCompletion.add(invocationContext.session().immutableEvents()))
        .ignoreElements()
        .blockingAwait();

    assertThat(getOnlyElement(eventsAtCompletion)).isEmpty();
  }

  @Test
  public void runNode_singleTurnNodeWhoseModelFails_stillRemovesItsInput() {
    IllegalStateException failure = new IllegalStateException("model failed");
    LlmAgent agent = singleTurnAgent("worker", TestLlm.create(ImmutableList.of(), failure));
    InvocationContext invocationContext = createInvocationContext(agent);
    Single<List<Event>> run =
        agent.runNode(nodeContext(agent, invocationContext), "input").toList();

    var thrown = assertThrows(IllegalStateException.class, run::blockingGet);

    assertThat(thrown).isSameInstanceAs(failure);

    assertThat(invocationContext.session().immutableEvents()).isEmpty();
  }

  @Test
  public void runNode_canceledSingleTurnNode_removesItsInput() {
    LlmAgent agent = singleTurnAgent("worker", new TestLlm(Flowable::never));
    InvocationContext invocationContext = createInvocationContext(agent);
    TestSubscriber<Event> subscriber =
        agent.runNode(nodeContext(agent, invocationContext), "input").test();
    assertThat(invocationContext.session().immutableEvents()).hasSize(1);

    subscriber.cancel();

    assertThat(invocationContext.session().immutableEvents()).isEmpty();
  }

  @Test
  public void runNode_resumingSingleTurnNode_addsNoUserTurn() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgent("worker", model);
    Context context =
        nodeContext(agent, createInvocationContext(agent), ImmutableMap.of("ask_1", "yes"));

    agent.runNode(context, "input").ignoreElements().blockingAwait();

    assertThat(model.getLastRequest().contents()).doesNotContain(userTurn("input"));
  }

  @Test
  public void runNode_mapInputHoldingAValueJsonCannotRepresent_failsWithoutQuotingIt() {
    LlmAgent agent = singleTurnAgent("worker", TestLlm.create("done"));
    Context context = nodeContext(agent, createInvocationContext(agent));
    // Jackson has no JSON form for an object without properties.
    Object secret =
        new Object() {
          @Override
          public String toString() {
            return "secret-day";
          }
        };
    Single<List<Event>> run = agent.runNode(context, ImmutableMap.of("slot", secret)).toList();

    var thrown = assertThrows(IllegalArgumentException.class, run::blockingGet);

    assertThat(thrown).hasMessageThat().contains("node input");
    assertThat(thrown).hasMessageThat().doesNotContain("secret-day");
  }

  private static LlmAgent singleTurnAgent(String name, TestLlm model) {
    return LlmAgent.builder().name(name).model(model).mode(AgentMode.SINGLE_TURN).build();
  }

  private static Content userTurn(String text) {
    return Content.fromParts(Part.fromText(text));
  }

  /** Runs {@code agent} after a node whose output is {@code input}. */
  private static void runAfterSource(Object input, Node agent) {
    Node source = new TestNode("source", (context, unused) -> Flowable.just(input));
    run(
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, source), new Edge(source, agent))
            .build(),
        "go");
  }

  private static void run(Node root, String message) {
    Runner runner =
        Runner.builder().app(App.builder().name(APP_NAME).rootNode(root).build()).build();
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
    runner.runAsync(USER_ID, session.id(), userTurn(message)).ignoreElements().blockingAwait();
  }

  private static Context nodeContext(LlmAgent agent, InvocationContext invocationContext) {
    return nodeContext(agent, invocationContext, ImmutableMap.of());
  }

  private static Context nodeContext(
      LlmAgent agent, InvocationContext invocationContext, Map<String, @Nullable Object> answers) {
    return new Context(
        invocationContext,
        NodeExecutionState.builder()
            .node(agent)
            .eventSink(SINK)
            .runId("1")
            .resumeInputs(answers)
            .build());
  }
}
