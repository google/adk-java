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
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
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
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
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
  public void runAsync_singleTurnNode_seesOnlyItsInputByDefault() {
    LlmAgent first = singleTurnAgent("first", modelAnswering("Paris"));
    TestLlm secondModel = TestLlm.create("done");
    LlmAgent second = singleTurnAgent("second", secondModel);

    run(chain(first, second), "Capital of France?");

    assertThat(secondModel.getLastRequest().contents()).containsExactly(userTurn("Paris"));
  }

  @Test
  public void runAsync_singleTurnNodeWithIncludeContentsSet_keepsTheHistory() {
    LlmAgent first = singleTurnAgent("first", modelAnswering("Paris"));
    TestLlm secondModel = TestLlm.create("done");
    LlmAgent second =
        LlmAgent.builder()
            .name("second")
            .model(secondModel)
            .mode(AgentMode.SINGLE_TURN)
            .includeContents(LlmAgent.IncludeContents.DEFAULT)
            .build();

    run(chain(first, second), "Capital of France?");

    assertThat(secondModel.getLastRequest().contents()).contains(userTurn("Capital of France?"));
  }

  @Test
  public void runAsync_agentWithoutAModeAfterASingleTurnNode_keepsTheHistory() {
    LlmAgent first = singleTurnAgent("first", modelAnswering("Paris"));
    TestLlm laterModel = TestLlm.create("done");
    LlmAgent later = LlmAgent.builder().name("later").model(laterModel).build();

    run(chain(first, later), "Capital of France?");

    assertThat(laterModel.getLastRequest().contents()).contains(userTurn("Capital of France?"));
  }

  @Test
  public void runAsync_subAgentReachedFromASingleTurnNode_keepsTheHistory() {
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

    Node source = new TestNode("source", (context, input) -> Flowable.just("route this"));

    run(chain(source, router), "Help me, please.");

    assertThat(helperModel.getLastRequest().contents()).contains(userTurn("Help me, please."));
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
  public void runAsync_secondSingleTurnNode_getsTheFirstNodesAnswerAsItsUserTurn() {
    LlmAgent first = singleTurnAgent("first", modelAnswering("Paris"));
    TestLlm secondModel = TestLlm.create("done");
    LlmAgent second = singleTurnAgent("second", secondModel);

    run(
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, first), new Edge(first, second))
            .build(),
        "Capital of France?");

    assertThat(getLast(secondModel.getLastRequest().contents())).isEqualTo(userTurn("Paris"));
  }

  @Test
  public void runAsync_singleTurnNode_givesItsTrimmedAnswerOnTheAnswerEventOnly() {
    NodeRun nodeRun = runWithSuccessor(singleTurnAgent("worker", modelAnswering("  the answer\n")));

    assertThat(nodeRun.output()).isEqualTo("the answer");
    Event outputEvent =
        nodeRun.events().stream()
            .filter(event -> !outputFor(event).isEmpty())
            .collect(onlyElement());
    assertThat(outputEvent.nodeInfo().map(NodeInfo::messageAsOutput)).hasValue(true);
    assertThat(outputEvent.output()).isEmpty();
  }

  @Test
  public void runAsync_singleTurnNode_leavesThoughtsOutOfItsOutputAndOutputKey() {
    Content answer =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("internal reasoning").thought(true).build(),
                Part.fromText("visible answer"))
            .build();
    LlmAgent agent =
        LlmAgent.builder()
            .name("worker")
            .model(TestLlm.create(createLlmResponse(answer)))
            .mode(AgentMode.SINGLE_TURN)
            .outputKey("summary")
            .build();

    NodeRun nodeRun = runWithSuccessor(agent);

    assertThat(nodeRun.output()).isEqualTo("visible answer");
    assertThat(stateDeltaValues(nodeRun.events(), "summary")).containsExactly("visible answer");
  }

  @Test
  public void runAsync_singleTurnNodeThatCallsATool_givesItsAnswerTurnAsItsOutput() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "echo_tool", ImmutableMap.of()),
            createTextLlmResponse("It is 72"));
    LlmAgent agent =
        LlmAgent.builder()
            .name("worker")
            .model(model)
            .mode(AgentMode.SINGLE_TURN)
            .tools(new EchoTool())
            .build();

    NodeRun nodeRun = runWithSuccessor(agent);

    assertThat(nodeRun.output()).isEqualTo("It is 72");
    assertThat(nodeRun.events().stream().filter(event -> !outputFor(event).isEmpty())).hasSize(1);
  }

  @Test
  public void runAsync_textBesideALongRunningCall_isSavedButIsNotTheOutput() {
    Content turn =
        Content.builder()
            .role("model")
            .parts(
                Part.fromText("Checking with the user."),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call_1")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "hello")))
                    .build())
            .build();
    LlmAgent agent =
        LlmAgent.builder()
            .name("worker")
            .model(TestLlm.create(createLlmResponse(turn)))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .outputKey("answer")
            .build();

    List<Event> events =
        run(Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, agent)).build(), "go");

    assertThat(stateDeltaValues(events, "answer")).containsExactly("Checking with the user.");
    assertThat(events.stream().filter(event -> !outputFor(event).isEmpty())).isEmpty();
  }

  @Test
  public void runAsync_contentAfterTheAnswer_isNotASecondOutput() {
    LlmAgent agent =
        LlmAgent.builder()
            .name("worker")
            .model(modelAnswering("answer"))
            .mode(AgentMode.SINGLE_TURN)
            .afterAgentCallback(
                context ->
                    Maybe.just(
                        Content.builder().role("model").parts(Part.fromText("note")).build()))
            .build();

    NodeRun nodeRun = runWithSuccessor(agent);

    assertThat(nodeRun.output()).isEqualTo("answer");
  }

  @Test
  public void runAsync_thoughtOnlyAnswer_givesNoOutput() {
    Content thoughtOnly =
        Content.builder()
            .role("model")
            .parts(Part.builder().text("internal reasoning").thought(true).build())
            .build();

    NodeRun nodeRun =
        runWithSuccessor(singleTurnAgent("worker", TestLlm.create(createLlmResponse(thoughtOnly))));

    assertThat(nodeRun.output()).isNull();
  }

  @Test
  public void runAsync_answerWithoutTheModelRole_givesNoOutput() {
    // As in Python ADK, only a model turn is the output; the model adapters all set the role.
    NodeRun nodeRun = runWithSuccessor(singleTurnAgent("worker", TestLlm.create("done")));

    assertThat(nodeRun.output()).isNull();
  }

  @Test
  public void runAsync_singleTurnNodeWithAnOutputSchema_givesAndSavesTheParsedValue() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "title",
                    Schema.builder().type("STRING").build(),
                    "count",
                    Schema.builder().type("INTEGER").build()))
            .build();
    LlmAgent agent =
        schemaAgent(schema, "{\"title\": \"Story\", \"count\": 3}").outputKey("meta").build();

    NodeRun nodeRun = runWithSuccessor(agent);

    ImmutableMap<String, Object> expected = ImmutableMap.of("title", "Story", "count", 3);
    assertThat(nodeRun.output()).isEqualTo(expected);
    assertThat(stateDeltaValues(nodeRun.events(), "meta")).containsExactly(expected);
    Event outputEvent =
        nodeRun.events().stream()
            .filter(event -> !outputFor(event).isEmpty())
            .collect(onlyElement());
    assertThat(outputEvent.output()).hasValue(expected);
    assertThat(outputEvent.nodeInfo().map(NodeInfo::messageAsOutput)).hasValue(false);
  }

  @Test
  public void runAsync_singleTurnNodeWithAStringSchema_givesItsPlainText() {
    LlmAgent agent =
        schemaAgent(Schema.builder().type("STRING").build(), "The answer is 42").build();

    NodeRun nodeRun = runWithSuccessor(agent);

    assertThat(nodeRun.output()).isEqualTo("The answer is 42");
  }

  @Test
  public void runAsync_singleTurnNodeWithAnArraySchema_givesAndSavesAList() {
    Schema schema =
        Schema.builder().type("ARRAY").items(Schema.builder().type("INTEGER").build()).build();

    NodeRun nodeRun =
        runWithSuccessor(schemaAgent(schema, "[1, 2, 3]").outputKey("numbers").build());

    assertThat(nodeRun.output()).isEqualTo(ImmutableList.of(1, 2, 3));
    assertThat(stateDeltaValues(nodeRun.events(), "numbers"))
        .containsExactly(ImmutableList.of(1, 2, 3));
  }

  @Test
  public void runAsync_blankAnswerWithAnOutputSchema_givesNoOutput() {
    Schema schema = Schema.builder().type("INTEGER").build();

    NodeRun nodeRun = runWithSuccessor(schemaAgent(schema, "   ").build());

    assertThat(nodeRun.output()).isNull();
  }

  @Test
  public void runAsync_answerNotMatchingTheOutputSchema_failsWithoutQuotingIt() {
    Schema schema = Schema.builder().type("INTEGER").build();
    LlmAgent agent = schemaAgent(schema, "sorry, I do not know").build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> runWithSuccessor(agent));

    assertThat(thrown).hasMessageThat().contains("validation error");
    assertThat(thrown).hasMessageThat().doesNotContain("sorry");
  }

  @Test
  public void runAsync_singleTurnNodeThatTransfers_givesNoOutputAndSavesNoReply() {
    LlmAgent helper = LlmAgent.builder().name("helper").model(modelAnswering("help")).build();
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
            .outputKey("route")
            .build();

    NodeRun nodeRun = runWithSuccessor(router);

    assertThat(nodeRun.output()).isNull();
    assertThat(stateDeltaValues(nodeRun.events(), "route")).isEmpty();
  }

  @Test
  public void runAsync_agentWithoutAModeThatTransfers_stillSavesTheReplyToItsOutputKey() {
    LlmAgent helper = LlmAgent.builder().name("helper").model(modelAnswering("help")).build();
    TestLlm routerModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "transfer_to_agent", ImmutableMap.of("agent_name", "helper")));
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(routerModel)
            .subAgents(helper)
            .outputKey("route")
            .build();

    NodeRun nodeRun = runWithSuccessor(router);

    assertThat(stateDeltaValues(nodeRun.events(), "route")).contains("help");
  }

  @Test
  public void runAsync_agentWithoutAMode_givesNoOutput() {
    LlmAgent agent = LlmAgent.builder().name("worker").model(modelAnswering("done")).build();

    NodeRun nodeRun = runWithSuccessor(agent);

    assertThat(nodeRun.output()).isNull();
  }

  @Test
  public void runAsync_singleTurnNodeBeforeAPause_isNotRunAgainOnResume() {
    TestLlm model = modelAnswering("summary");
    LlmAgent summarizer = singleTurnAgent("summarizer", model);
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder()
                .name("wf")
                .edges(new Edge(Start.INSTANCE, summarizer), new Edge(summarizer, ask))
                .build());
    session.advance(go());

    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(model.getRequests()).hasSize(1);
    assertThat(resumed.stream().map(Event::author)).doesNotContain("summarizer");
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

  private static Workflow chain(Node first, Node second) {
    return Workflow.builder()
        .name("wf")
        .edges(new Edge(Start.INSTANCE, first), new Edge(first, second))
        .build();
  }

  private static LlmAgent singleTurnAgent(String name, TestLlm model) {
    return LlmAgent.builder().name(name).model(model).mode(AgentMode.SINGLE_TURN).build();
  }

  /** Returns a model that answers once with {@code text} in a model turn, as Gemini does. */
  private static TestLlm modelAnswering(String text) {
    return TestLlm.create(createTextLlmResponse(text));
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

  @CanIgnoreReturnValue
  private static List<Event> run(Node root, String message) {
    Runner runner =
        Runner.builder().app(App.builder().name(APP_NAME).rootNode(root).build()).build();
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
    return runner.runAsync(USER_ID, session.id(), userTurn(message)).toList().blockingGet();
  }

  /** The events of a run and the input the agent's successor got, which is the agent's output. */
  private record NodeRun(ImmutableList<Event> events, @Nullable Object output) {}

  private static NodeRun runWithSuccessor(Node agent) {
    List<@Nullable Object> inputs = new ArrayList<>();
    Node successor =
        new TestNode(
            "successor",
            (context, input) -> {
              inputs.add(input);
              return Flowable.empty();
            });
    List<Event> events =
        run(
            Workflow.builder()
                .name("wf")
                .edges(new Edge(Start.INSTANCE, agent), new Edge(agent, successor))
                .build(),
            "go");
    return new NodeRun(ImmutableList.copyOf(events), getOnlyElement(inputs));
  }

  private static LlmAgent.Builder schemaAgent(Schema schema, String answer) {
    return LlmAgent.builder()
        .name("worker")
        .model(modelAnswering(answer))
        .mode(AgentMode.SINGLE_TURN)
        .outputSchema(schema);
  }

  private static ImmutableList<String> outputFor(Event event) {
    return event.nodeInfo().map(NodeInfo::outputFor).orElse(ImmutableList.of());
  }

  private static ImmutableList<Object> stateDeltaValues(List<Event> events, String key) {
    return events.stream()
        .filter(event -> event.actions().stateDelta().containsKey(key))
        .map(event -> event.actions().stateDelta().get(key))
        .collect(toImmutableList());
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
