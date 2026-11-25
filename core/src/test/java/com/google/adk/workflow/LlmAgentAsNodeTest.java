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

import static com.google.adk.testing.ResumabilityTestUtils.confirmingEchoFunctionTool;
import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.callContent;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.interrupt;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.adk.workflow.WorkflowResumeTesting.retrying;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.Role;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.ResumabilityTestUtils;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  public void runAsync_singleTurnNode_getsARecordInputAsJson() {
    TestLlm model = TestLlm.create("done");

    runAfterSource(new Trip("Kraków", 3), singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents()))
        .isEqualTo(userTurn("{\"city\":\"Kraków\",\"days\":3}"));
  }

  @Test
  public void runAsync_singleTurnNode_getsANumberInputAsItsText() {
    TestLlm model = TestLlm.create("done");

    runAfterSource(42, singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents())).isEqualTo(userTurn("42"));
  }

  @Test
  public void runAsync_singleTurnNode_getsAnInputWithoutAJsonFormAsItsText() {
    TestLlm model = TestLlm.create("done");

    runAfterSource(new Opaque(), singleTurnAgent("worker", model));

    assertThat(getLast(model.getLastRequest().contents())).isEqualTo(userTurn("opaque"));
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
            .afterAgentCallback(context ->
                Maybe.just(Content.builder().role("model").parts(Part.fromText("note")).build()))
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
  public void runAsync_singleTurnNodeWithAnOutputSchema_fillsTheDefaultsOfOmittedProperties() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "title",
                    Schema.builder().type("STRING").build(),
                    "count",
                    Schema.builder().type("INTEGER").default_(1).build()))
            .build();
    LlmAgent agent = schemaAgent(schema, "{\"title\": \"Story\"}").outputKey("meta").build();

    NodeRun nodeRun = runWithSuccessor(agent);

    ImmutableMap<String, Object> expected = ImmutableMap.of("title", "Story", "count", 1);
    assertThat(nodeRun.output()).isEqualTo(expected);
    assertThat(stateDeltaValues(nodeRun.events(), "meta")).containsExactly(expected);
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

    NodeRun nodeRun = runWithSuccessor(schemaAgent(schema, "   ").outputKey("value").build());

    assertThat(nodeRun.output()).isNull();
    assertThat(stateDeltaValues(nodeRun.events(), "value")).isEmpty();
  }

  @Test
  public void runAsync_singleTurnNodeWithAToolAndAnOutputSchema_takesTheSetModelResponseAnswer() {
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
    TestLlm scripted =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "c1", "set_model_response", ImmutableMap.of("title", "Story", "count", 3)));
    // Only a model that can't combine tools with an output schema answers via set_model_response.
    BaseLlm gemini2NamedLlm =
        new BaseLlm("gemini-2.0-flash") {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return scripted.generateContent(llmRequest, stream);
          }

          @Override
          public BaseLlmConnection connect(LlmRequest llmRequest) {
            return scripted.connect(llmRequest);
          }
        };
    LlmAgent agent =
        LlmAgent.builder()
            .name("worker")
            .model(gemini2NamedLlm)
            .mode(AgentMode.SINGLE_TURN)
            .outputSchema(schema)
            .tools(new EchoTool())
            .outputKey("meta")
            .build();

    NodeRun nodeRun = runWithSuccessor(agent);

    ImmutableMap<String, Object> expected = ImmutableMap.of("title", "Story", "count", 3);
    assertThat(nodeRun.output()).isEqualTo(expected);
    assertThat(stateDeltaValues(nodeRun.events(), "meta")).containsExactly(expected);
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
  public void runAsync_agentWithoutAMode_givesNoOutput() {
    LlmAgent agent = LlmAgent.builder().name("worker").model(modelAnswering("done")).build();

    NodeRun nodeRun = runWithSuccessor(agent);

    assertThat(nodeRun.output()).isNull();
  }

  @Test
  public void runAsync_singleTurnNodeBeforeAPause_isNotRunAgainAndReplaysItsOutput() {
    TestLlm model = modelAnswering("summary");
    LlmAgent summarizer = singleTurnAgent("summarizer", model);
    List<@Nullable Object> recorderInputs = new ArrayList<>();
    Node recorder =
        new Node() {
          @Override
          public String name() {
            return "recorder";
          }

          @Override
          public boolean rerunOnResume() {
            return true;
          }

          @Override
          public Flowable<?> runNode(Context context, @Nullable Object input) {
            recorderInputs.add(input);
            return context.resumeInputs().isEmpty()
                ? Flowable.just(interrupt("ask_1"))
                : Flowable.empty();
          }
        };
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder()
                .name("wf")
                .edges(new Edge(Start.INSTANCE, summarizer), new Edge(summarizer, recorder))
                .build());
    session.advance(go());

    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(model.getRequests()).hasSize(1);
    assertThat(resumed.stream().map(Event::author)).doesNotContain("summarizer");
    // The successor runs again on the summarizer's output, replayed from its recorded event.
    assertThat(recorderInputs).containsExactly("summary", "summary").inOrder();
  }

  @Test
  public void runAsync_resumableAppRetriesAWorkflowWhoseAgentsToolFailed_callsTheModelAgain() {
    TestLlm model = modelCallingLookupUntilAnswered();
    LlmAgent worker = LlmAgent.builder().name("worker").model(model).tools(failsOnce()).build();
    Workflow retried =
        Workflow.builder()
            .name("retried")
            .edges(new Edge(Start.INSTANCE, worker))
            .config(retrying(2))
            .build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, retried)).build());

    ImmutableList<Event> events = session.turn(go());

    assertThat(model.getRequests()).hasSize(3);
    assertThat(events.stream().map(Event::stringifyContent)).contains("found it");
  }

  @Test
  public void runAsync_resumableAppRetriesATimedOutWorkflow_callsItsAgentsModelAgain() {
    TestLlm model = modelCallingLookupUntilAnswered();
    LlmAgent worker = LlmAgent.builder().name("worker").model(model).tools(stallsOnce()).build();
    Workflow retried =
        Workflow.builder()
            .name("retried")
            .edges(new Edge(Start.INSTANCE, worker))
            .config(retryingOnTimeout())
            .build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, retried)).build());

    ImmutableList<Event> events = session.turn(go());

    assertThat(model.getRequests()).hasSize(3);
    assertThat(events.stream().map(Event::stringifyContent)).contains("found it");
  }

  @Test
  public void runAsync_resumableAppRetriesATimedOutFanOut_callsItsAgentsModelAgain() {
    TestLlm model = modelCallingLookupUntilAnswered();
    LlmAgent worker = LlmAgent.builder().name("worker").model(model).tools(stallsOnce()).build();
    Workflow sub = Workflow.builder().name("sub").edges(new Edge(Start.INSTANCE, worker)).build();
    Workflow retried =
        Workflow.builder()
            .name("retried")
            .edges(new Edge(Start.INSTANCE, sub), new Edge(Start.INSTANCE, TestNode.of("other")))
            .config(retryingOnTimeout())
            .build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, retried)).build());

    ImmutableList<Event> events = session.turn(go());

    assertThat(model.getRequests()).hasSize(3);
    assertThat(events.stream().map(Event::stringifyContent)).contains("found it");
  }

  @Test
  public void runAsync_retriedFanOutWithTheFailedCallLastOnItsBranch_runsTheCallAgain() {
    TestLlm model = modelCallingLookupUntilAnswered();
    LlmAgent worker = LlmAgent.builder().name("worker").model(model).tools(stallsOnce()).build();
    Workflow retried =
        Workflow.builder()
            .name("retried")
            .edges(new Edge(Start.INSTANCE, worker), new Edge(Start.INSTANCE, TestNode.of("other")))
            .config(retryingOnTimeout())
            .build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, retried)).build());

    ImmutableList<Event> events = session.turn(go());

    // Nothing follows the call on the agent's own branch, so the retry runs it, as before.
    assertThat(
            events.stream()
                .flatMap(event -> event.functionResponses().stream())
                .map(response -> response.id().orElse("")))
        .contains("call_1");
    assertThat(events.stream().map(Event::stringifyContent)).contains("found it");
  }

  @Test
  public void runAsync_resumableAppRetriesAnAgentWhoseToolFailed_callsTheModelAgain() {
    TestLlm model = modelCallingLookupUntilAnswered();
    LlmAgent worker =
        withNodeConfig(
            LlmAgent.builder().name("worker").model(model).tools(failsOnce()), retrying(2));
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, worker)).build());

    ImmutableList<Event> events = session.turn(go());

    assertThat(model.getRequests()).hasSize(3);
    assertThat(events.stream().map(Event::stringifyContent)).contains("found it");
  }

  @Test
  public void runAsync_resumableAppRetriesAnAgentWhoseToolTimedOut_callsTheModelAgain() {
    TestLlm model = modelCallingLookupUntilAnswered();
    LlmAgent worker =
        withNodeConfig(
            LlmAgent.builder().name("worker").model(model).tools(stallsOnce()),
            retryingOnTimeout());
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, worker)).build());

    ImmutableList<Event> events = session.turn(go());

    assertThat(model.getRequests()).hasSize(3);
    assertThat(events.stream().map(Event::stringifyContent)).contains("found it");
  }

  @Test
  public void runAsync_resumableAppRetriesASingleTurnAgentWhoseToolFailed_outputsItsAnswer() {
    LlmAgent worker =
        withNodeConfig(
            LlmAgent.builder()
                .name("worker")
                .model(modelCallingLookupUntilAnswered())
                .mode(AgentMode.SINGLE_TURN)
                .tools(failsOnce()),
            retrying(2));
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder()
                .name("wf")
                .edges(new Edge(Start.INSTANCE, worker), new Edge(worker, captureInput("reply")))
                .build());

    ImmutableList<Event> events = session.turn(go());

    assertThat(replyInput(events)).isEqualTo("found it");
  }

  @Test
  public void runAsync_loopBackToAnAgentWhoseEarlierRunRetried_callsTheModelAgain() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "lookup", ImmutableMap.of()),
            createTextLlmResponse("draft"),
            createTextLlmResponse("final"));
    LlmAgent worker =
        withNodeConfig(
            LlmAgent.builder().name("worker").model(model).tools(failsOnce()), retrying(2));
    AtomicInteger reviews = new AtomicInteger();
    Node review =
        new TestNode(
            "review",
            (ctx, input) -> {
              String route = reviews.incrementAndGet() == 1 ? "again" : "done";
              ctx.routes(ImmutableList.of(new Route.Tag(route)));
              return Flowable.empty();
            });
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder()
                .name("wf")
                .edges(
                    new Edge(Start.INSTANCE, worker),
                    new Edge(worker, review),
                    new Edge(review, worker, new Route.Tag("again")))
                .build());

    session.advance(go());

    assertThat(model.getRequests()).hasSize(3);
  }

  @Test
  public void runAsync_retryOfAnAgentWhoseFailedAttemptAskedForApproval_doesNotCallTheModelAgain() {
    Content approveAndLookUp =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .name("pendingTool")
                            .id("p1")
                            .args(ImmutableMap.of("message", "approve?"))
                            .build())
                    .build(),
                Part.builder()
                    .functionCall(FunctionCall.builder().name("lookup").id("c1").build())
                    .build())
            .build();
    TestLlm firstModel =
        TestLlm.create(createLlmResponse(approveAndLookUp), createTextLlmResponse("first done"));
    LlmAgent first =
        withNodeConfig(
            LlmAgent.builder()
                .name("first")
                .model(firstModel)
                .tools(pendingFunctionTool(), failsOnce()),
            retrying(2));
    TestLlm secondModel = TestLlm.create(createTextLlmResponse("second done"));
    LlmAgent second = LlmAgent.builder().name("second").model(secondModel).build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder()
                .name("wf")
                .edges(new Edge(Start.INSTANCE, first), new Edge(first, second))
                .build());

    session.advance(go());

    // As in ADK Kotlin, an approval requested by a failed attempt still pauses the retry.
    assertThat(firstModel.getRequests()).hasSize(1);
    assertThat(secondModel.getRequests()).isEmpty();
  }

  @Test
  public void runAsync_toolAsksForConfirmationInANonResumableApp_endsTheTurnOnTheRequest() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "echoTool", ImmutableMap.of("message", "hi")),
            createTextLlmResponse("done"));
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(model).tools(confirmingEchoFunctionTool()).build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, worker)).build(),
            /* resumable= */ false);

    ImmutableList<Event> events = session.turn(go());
    int requestsBeforeAnswer = model.getRequests().size();
    FunctionCall request =
        events.stream()
            .flatMap(event -> Functions.getAskUserConfirmationFunctionCalls(event).stream())
            .collect(onlyElement());
    ImmutableList<Event> answered =
        session.turn(
            answer(
                request.id().orElseThrow(),
                ImmutableMap.of("confirmed", true),
                Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME));

    // As in ADK Kotlin and Python, the model is not called again until the user answers.
    assertThat(requestsBeforeAnswer).isEqualTo(1);
    assertThat(model.getRequests()).hasSize(2);
    assertThat(answered.stream().map(Event::stringifyContent)).contains("done");
  }

  @Test
  public void runAsync_agentWithTwoOpenCallsOneAnswered_keepsWaitingOnTheOther() {
    TestLlm model =
        TestLlm.create(createLlmResponse(twoOpenCalls()), createTextLlmResponse("all done"));
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(model).tools(pendingFunctionTool()).build();
    List<@Nullable Object> afterInputs = new ArrayList<>();
    Node after =
        new TestNode(
            "after",
            (context, input) -> {
              afterInputs.add(input);
              return Flowable.just("after ran");
            });
    WorkflowSession session = new WorkflowSession(chain(worker, after));
    session.advance(go());

    ImmutableList<Event> first =
        session.turn(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));
    int requestsAfterFirst = model.getRequests().size();
    int afterRunsAfterFirst = afterInputs.size();
    ImmutableList<Event> second =
        session.turn(answer("p2", ImmutableMap.of("ok", true), "pendingTool"));

    // As in ADK Kotlin, the node waits on p2 and calls its model once both calls are answered.
    assertThat(requestsAfterFirst).isEqualTo(1);
    assertThat(afterRunsAfterFirst).isEqualTo(0);
    assertThat(workflowEnds(first)).isEmpty();
    assertThat(model.getRequests()).hasSize(2);
    assertThat(functionResponseIds(model.getLastRequest())).containsExactly("p1", "p2");
    assertThat(second.stream().map(Event::stringifyContent)).contains("all done");
    assertThat(afterInputs).hasSize(1);
  }

  @Test
  public void runAsync_chatAgentWithTwoOpenCallsOneAnswered_keepsWaitingOnTheOther() {
    TestLlm model =
        TestLlm.create(createLlmResponse(twoOpenCalls()), createTextLlmResponse("all done"));
    LlmAgent guide =
        LlmAgent.builder()
            .name("guide")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, guide)).build());
    session.advance(go());

    ImmutableList<Event> first =
        session.turn(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));
    int requestsAfterFirst = model.getRequests().size();
    ImmutableList<Event> second =
        session.turn(answer("p2", ImmutableMap.of("ok", true), "pendingTool"));

    assertThat(requestsAfterFirst).isEqualTo(1);
    assertThat(workflowEnds(first)).isEmpty();
    assertThat(model.getRequests()).hasSize(2);
    assertThat(functionResponseIds(model.getLastRequest())).containsExactly("p1", "p2");
    assertThat(second.stream().map(Event::stringifyContent)).contains("all done");
  }

  @Test
  public void runAsync_taskAgentWithTwoOpenCallsOneAnswered_finishesOnceBothAreAnswered() {
    TestLlm model =
        TestLlm.create(
            createLlmResponse(twoOpenCalls()),
            createFunctionCallLlmResponse(
                "finish_1", "finish_task", ImmutableMap.of("result", "Done.")));
    LlmAgent task =
        LlmAgent.builder()
            .name("task")
            .model(model)
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .build();
    Node after = captureInput("after");
    WorkflowSession session = new WorkflowSession(chain(task, after));
    session.advance(go());

    ImmutableList<Event> first =
        session.turn(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));
    int requestsAfterFirst = model.getRequests().size();
    ImmutableList<Event> second =
        session.turn(answer("p2", ImmutableMap.of("ok", true), "pendingTool"));

    assertThat(requestsAfterFirst).isEqualTo(1);
    assertThat(workflowEnds(first)).isEmpty();
    assertThat(pathsOf(first)).doesNotContain("wf@1/after@1");
    assertThat(functionResponseIds(model.getLastRequest())).containsExactly("p1", "p2");
    assertThat(outputEventAt(second, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "Done.")));
  }

  @Test
  public void runAsync_singleTurnAgentWithTwoOpenCallsOneAnswered_keepsWaitingOnTheOther() {
    TestLlm model =
        TestLlm.create(createLlmResponse(twoOpenCalls()), createTextLlmResponse("all done"));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(model)
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    Node after = captureInput("after");
    WorkflowSession session = new WorkflowSession(chain(worker, after));
    session.advance(go());

    ImmutableList<Event> first =
        session.turn(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));
    int requestsAfterFirst = model.getRequests().size();
    ImmutableList<Event> second =
        session.turn(answer("p2", ImmutableMap.of("ok", true), "pendingTool"));

    assertThat(requestsAfterFirst).isEqualTo(1);
    assertThat(pathsOf(first)).doesNotContain("wf@1/after@1");
    assertThat(functionResponseIds(model.getLastRequest())).containsExactly("p1", "p2");
    assertThat(outputEventAt(second, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", "all done"));
  }

  @Test
  public void
      runAsync_singleTurnAgentWithTwoOpenCallsOneAnsweredInANonResumableApp_endsOnItsReply() {
    TestLlm model =
        TestLlm.create(createLlmResponse(twoOpenCalls()), createTextLlmResponse("all done"));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(model)
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    List<@Nullable Object> afterInputs = new ArrayList<>();
    Node after =
        new TestNode(
            "after",
            (context, input) -> {
              afterInputs.add(input);
              return Flowable.just("after ran");
            });
    WorkflowSession session = new WorkflowSession(chain(worker, after), /* resumable= */ false);
    session.advance(go());

    session.advance(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));

    // As in ADK Kotlin, the model answers before p2 does, and that reply is the node's output.
    assertThat(model.getRequests()).hasSize(2);
    assertThat(afterInputs).containsExactly("all done");
  }

  @Test
  public void runAsync_childAgentWithTwoOpenCallsOneAnswered_keepsItsParentWaiting() {
    TestLlm model =
        TestLlm.create(createLlmResponse(twoOpenCalls()), createTextLlmResponse("all done"));
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(model).tools(pendingFunctionTool()).build();
    List<@Nullable Object> afterInputs = new ArrayList<>();
    Node parent =
        dispatcher("parent", (context, input) -> context.runNode(worker, "go").toFlowable());
    Node after =
        new TestNode(
            "after",
            (context, input) -> {
              afterInputs.add(input);
              return Flowable.just("after ran");
            });
    WorkflowSession session = new WorkflowSession(chain(parent, after));
    session.advance(go());

    session.advance(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));
    int requestsAfterFirst = model.getRequests().size();
    int afterRunsAfterFirst = afterInputs.size();
    session.advance(answer("p2", ImmutableMap.of("ok", true), "pendingTool"));

    assertThat(requestsAfterFirst).isEqualTo(1);
    assertThat(afterRunsAfterFirst).isEqualTo(0);
    assertThat(model.getRequests()).hasSize(2);
    assertThat(afterInputs).hasSize(1);
  }

  @Test
  public void runAsync_singleTurnNodesRunningAtOnce_doNotSeeEachOthersInput() {
    CountDownLatch otherAsked = new CountDownLatch(1);
    // The other node's model answers only once this node asked, so both inputs exist at once.
    TestLlm otherModel =
        new TestLlm(() ->
            Flowable.fromCallable(
                    () -> {
                      assertThat(otherAsked.await(10, SECONDS)).isTrue();
                      return createTextLlmResponse("done");
                    })
                .subscribeOn(Schedulers.io()));
    TestLlm model =
        new TestLlm(() -> {
          otherAsked.countDown();
          return Flowable.just(createTextLlmResponse("done"));
        });
    LlmAgent other = singleTurnAgent("other", otherModel);
    LlmAgent agent =
        LlmAgent.builder()
            .name("worker")
            .model(model)
            .mode(AgentMode.SINGLE_TURN)
            .includeContents(LlmAgent.IncludeContents.DEFAULT)
            .build();

    run(
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, runningAtOnce(other, "other input", agent, "my input")))
            .build(),
        "go");

    assertThat(model.getLastRequest().contents()).contains(userTurn("my input"));
    assertThat(model.getLastRequest().contents()).doesNotContain(userTurn("other input"));
  }

  @Test
  public void runAsync_userEventFromACustomNode_staysVisibleToALaterAgent() {
    Node custom =
        new TestNode(
            "custom",
            (context, input) -> {
              context.eventAuthor(Role.USER);
              return Flowable.just(
                  Event.builder().id("relayed").content(userTurn("relayed message")).build());
            });
    TestLlm model = TestLlm.create("done");

    run(chain(custom, LlmAgent.builder().name("later").model(model).build()), "go");

    assertThat(model.getLastRequest().contents()).contains(userTurn("relayed message"));
  }

  @Test
  public void runAsync_singleTurnNodeWithHistory_doesNotSeeAnEarlierNodesReply() {
    LlmAgent first = singleTurnAgent("first", modelAnswering("Paris"));
    TestLlm secondModel = TestLlm.create("done");
    LlmAgent second = singleTurnAgentWithHistory("second", secondModel);

    run(chain(first, second), "Capital of France?");

    // "Paris" arrives only as this node's input, not as the first node's reply.
    assertThat(secondModel.getLastRequest().contents())
        .containsExactly(userTurn("Capital of France?"), userTurn("Paris"))
        .inOrder();
  }

  @Test
  public void runNode_singleTurnNodeWithHistory_seesItsAncestorsAndChildrenButNotItsSiblings() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgentWithHistory("worker", model);
    InvocationContext invocationContext = createInvocationContext(agent);
    Session session = invocationContext.session();
    session.addEvent(nodeEvent("parent", "parent@1", modelText("ancestor note")));
    session.addEvent(nodeEvent("helper", "parent@1/worker@1/helper@1", modelText("child note")));
    session.addEvent(nodeEvent("worker10", "parent@1/worker@10", modelText("sibling note")));

    agent
        .runNode(nodeContextUnder("parent", agent, invocationContext), null)
        .ignoreElements()
        .blockingAwait();

    String request = textsOf(model.getLastRequest());
    assertThat(request).contains("ancestor note");
    assertThat(request).contains("child note");
    assertThat(request).doesNotContain("sibling note");
  }

  @Test
  public void runNode_singleTurnNodeWithoutHistory_seesRootEventsButNotItsAncestorsOrSiblings() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgent("worker", model);
    InvocationContext invocationContext = createInvocationContext(agent);
    Session session = invocationContext.session();
    session.addEvent(
        Event.builder()
            .id("question")
            .author(Role.USER)
            .content(userTurn("root question"))
            .build());
    // Either note would start the current turn if the node saw it, leaving out the question.
    session.addEvent(nodeEvent("parent", "parent@1", modelText("ancestor note")));
    session.addEvent(nodeEvent("worker10", "parent@1/worker@10", modelText("sibling note")));

    agent
        .runNode(nodeContextUnder("parent", agent, invocationContext), null)
        .ignoreElements()
        .blockingAwait();

    String request = textsOf(model.getLastRequest());
    assertThat(request).contains("root question");
    assertThat(request).doesNotContain("ancestor note");
    assertThat(request).doesNotContain("sibling note");
  }

  @Test
  public void runNode_singleTurnNode_dropsTheAnswerToAnotherNodesCall() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgentWithHistory("worker", model);
    InvocationContext invocationContext = createInvocationContext(agent);
    Session session = invocationContext.session();
    session.addEvent(nodeEvent("sibling", "sibling@1", callContent("pendingTool", "call_1")));
    session.addEvent(nodeEvent("worker", "worker@1", modelText("earlier own note")));
    // The answer comes last, where an answer without its call fails the request.
    session.addEvent(answerTo("call_1"));

    agent.runNode(nodeContext(agent, invocationContext), null).ignoreElements().blockingAwait();

    String request = textsOf(model.getLastRequest());
    assertThat(request).contains("earlier own note");
    assertThat(request).doesNotContain("pendingTool");
  }

  @Test
  public void runNode_singleTurnNodeWithoutHistory_dropsAnAnswerThatComesBeforeItsVisibleCall() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgent("worker", model);
    InvocationContext invocationContext = createInvocationContext(agent);
    Session session = invocationContext.session();
    session.addEvent(
        Event.builder()
            .id("question")
            .author(Role.USER)
            .content(userTurn("root question"))
            .build());
    session.addEvent(nodeEvent("sibling", "sibling@1", callContent("pendingTool", "call_1")));
    session.addEvent(answerTo("call_1"));
    // A later call that reuses the ID must not adopt the answer, which would hide the question.
    session.addEvent(nodeEvent("worker", "worker@1", callContent("ownTool", "call_1")));

    agent.runNode(nodeContext(agent, invocationContext), null).ignoreElements().blockingAwait();

    String request = textsOf(model.getLastRequest());
    assertThat(request).contains("root question");
    assertThat(request).contains("ownTool");
    assertThat(request).doesNotContain("pendingTool");
  }

  @Test
  public void runNode_singleTurnNode_dropsTheAnswerToACallOnAnotherBranch() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgentWithHistory("worker", model);
    InvocationContext invocationContext =
        createInvocationContext(agent).toBuilder().branch("wf@1.a@1").build();
    Session session = invocationContext.session();
    // A root call passes the node path filter but sits on a branch this node does not see.
    session.addEvent(
        Event.builder()
            .id("call")
            .author("other")
            .branch("wf@1.b@1")
            .content(callContent("pendingTool", "call_1"))
            .build());
    session.addEvent(nodeEvent("worker", "worker@1", modelText("earlier own note")));
    session.addEvent(answerTo("call_1"));

    agent.runNode(nodeContext(agent, invocationContext), null).ignoreElements().blockingAwait();

    String request = textsOf(model.getLastRequest());
    assertThat(request).contains("earlier own note");
    assertThat(request).doesNotContain("pendingTool");
  }

  @Test
  public void runNode_singleTurnNode_keepsItsOwnAnswerFromAnEventThatAlsoAnswersAnotherNode() {
    TestLlm model = TestLlm.create("done");
    LlmAgent agent = singleTurnAgentWithHistory("worker", model);
    InvocationContext invocationContext = createInvocationContext(agent);
    Session session = invocationContext.session();
    session.addEvent(nodeEvent("sibling", "sibling@1", callContent("pendingTool", "call_1")));
    session.addEvent(nodeEvent("worker", "worker@1", callContent("ownTool", "call_2")));
    // One event answers a hidden call and a visible one, so only the visible answer stays.
    session.addEvent(
        Event.builder()
            .id("answers")
            .author(Role.USER)
            .content(
                Content.builder()
                    .role("user")
                    .parts(answerPart("call_1", "pendingTool"), answerPart("call_2", "ownTool"))
                    .build())
            .build());

    agent.runNode(nodeContext(agent, invocationContext), null).ignoreElements().blockingAwait();

    ImmutableList<String> answeredIds =
        model.getLastRequest().contents().stream()
            .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
            .flatMap(part -> part.functionResponse().flatMap(FunctionResponse::id).stream())
            .collect(toImmutableList());
    assertThat(answeredIds).containsExactly("call_2");
  }

  @Test
  public void runNode_singleTurnNode_givesItsInputTheNodeBranchAndPath() {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession(APP_NAME, USER_ID).blockingGet();
    List<Event> seenInputs = new ArrayList<>();
    TestLlm model =
        new TestLlm(() -> {
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
  @SuppressWarnings("deprecation") // eventsView lets the test check each event as it is added.
  public void runNode_singleTurnNode_tracksItsInputBeforeAddingItAndKeepsTrackingIt() {
    // The outer context has no node path, so it sees the input as another node's.
    LlmAgent agent = singleTurnAgent("worker", TestLlm.create("done"));
    AtomicReference<InvocationContext> invocationContext = new AtomicReference<>();
    List<Event> added = new ArrayList<>();
    List<Boolean> hiddenWhenAdded = new ArrayList<>();
    List<Event> events =
        new ArrayList<>() {
          @Override
          public boolean add(Event event) {
            added.add(event);
            hiddenWhenAdded.add(invocationContext.get().isOtherNodeInput(event));
            return super.add(event);
          }
        };
    Session session =
        Session.builder("session").appName(APP_NAME).userId(USER_ID).eventsView(events).build();
    invocationContext.set(createInvocationContext(agent, new InMemorySessionService(), session));

    agent
        .runNode(nodeContext(agent, invocationContext.get()), "input")
        .ignoreElements()
        .blockingAwait();

    assertThat(hiddenWhenAdded).containsExactly(true);
    assertThat(events).isEmpty();
    assertThat(invocationContext.get().isOtherNodeInput(getOnlyElement(added))).isTrue();
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

  @Test
  public void runAsync_toolFailingAfterConfirmation_failsTheNode() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "remove_file", ImmutableMap.of()),
            createTextLlmResponse("removed"));
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(model).tools(new ConfirmThenFailTool()).build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, worker)).build());
    FunctionCall request =
        session.turn(go()).stream()
            .flatMap(event -> Functions.getAskUserConfirmationFunctionCalls(event).stream())
            .collect(onlyElement());

    WorkflowResumeTesting.FailedTurn turn =
        session.failingTurn(
            answer(
                request.id().orElseThrow(),
                ImmutableMap.of("confirmed", true),
                Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME));

    // As in ADK Kotlin and Python, the tool's error fails the run before the model is called.
    assertThat(turn.error()).hasMessageThat().contains("failed after approval");
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void runAsync_confirmedToolGivingNoResult_keepsTheNodeRunning() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "pendingTool", ImmutableMap.of("message", "x")),
            createTextLlmResponse("waiting"));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(model)
            .tools(
                LongRunningFunctionTool.create(
                    ResumabilityTestUtils.Tools.class,
                    "pendingTool",
                    /* requireConfirmation= */ true))
            .build();
    WorkflowSession session =
        new WorkflowSession(
            Workflow.builder().name("wf").edges(new Edge(Start.INSTANCE, worker)).build());
    FunctionCall request =
        session.turn(go()).stream()
            .flatMap(event -> Functions.getAskUserConfirmationFunctionCalls(event).stream())
            .collect(onlyElement());

    ImmutableList<Event> events =
        session.turn(
            answer(
                request.id().orElseThrow(),
                ImmutableMap.of("confirmed", true),
                Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME));

    assertThat(model.getRequests()).hasSize(2);
    assertThat(events.stream().map(Event::stringifyContent)).contains("waiting");
  }

  /** Returns a node that runs both agents on its own branch at once, each on its own input. */
  private static Node runningAtOnce(
      LlmAgent first, Object firstInput, LlmAgent second, Object secondInput) {
    return new Node() {
      @Override
      public String name() {
        return "both";
      }

      @Override
      public boolean rerunOnResume() {
        return true;
      }

      @Override
      public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
        return Maybe.merge(context.runNode(first, firstInput), context.runNode(second, secondInput))
            .ignoreElements()
            .toFlowable();
      }
    };
  }

  /** Returns the user's answer to the {@code pendingTool} call {@code callId}. */
  private static Event answerTo(String callId) {
    return Event.builder()
        .id("answer")
        .author(Role.USER)
        .content(answer(callId, ImmutableMap.of("ok", true), "pendingTool"))
        .build();
  }

  /** Returns a model part that calls {@code pendingTool} with ID {@code callId}. */
  private static Part pendingCallPart(String callId) {
    return Part.builder()
        .functionCall(
            FunctionCall.builder()
                .name("pendingTool")
                .id(callId)
                .args(ImmutableMap.of("message", "approve?")))
        .build();
  }

  /** Returns a model turn that calls {@code pendingTool} twice, with IDs p1 and p2. */
  private static Content twoOpenCalls() {
    return Content.builder()
        .role("model")
        .parts(pendingCallPart("p1"), pendingCallPart("p2"))
        .build();
  }

  /** Returns the events of {@code events} in which the workflow {@code wf} ends its turn. */
  private static ImmutableList<Event> workflowEnds(List<Event> events) {
    return events.stream()
        .filter(event -> event.author().equals("wf") && event.actions().endOfAgent())
        .collect(toImmutableList());
  }

  /** Returns the IDs of the function responses in {@code request}, in order. */
  private static ImmutableList<String> functionResponseIds(LlmRequest request) {
    return request.contents().stream()
        .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
        .flatMap(part -> part.functionResponse().flatMap(FunctionResponse::id).stream())
        .collect(toImmutableList());
  }

  /** Returns the only part of the user's answer to the {@code name} call {@code callId}. */
  private static Part answerPart(String callId, String name) {
    return getOnlyElement(answer(callId, ImmutableMap.of("ok", true), name).parts().orElseThrow());
  }

  private static Event nodeEvent(String author, String path, Content content) {
    return Event.builder()
        .id(author + "-event")
        .author(author)
        .content(content)
        .nodeInfo(NodeInfo.builder().path(path).build())
        .build();
  }

  /** Returns a model that calls {@code lookup}, calls it again, then answers "found it". */
  private static TestLlm modelCallingLookupUntilAnswered() {
    return TestLlm.create(
        createFunctionCallLlmResponse("call_1", "lookup", ImmutableMap.of()),
        createFunctionCallLlmResponse("call_2", "lookup", ImmutableMap.of()),
        createTextLlmResponse("found it"));
  }

  /** Returns a node config that times out after 300 ms and retries once. */
  private static NodeConfig retryingOnTimeout() {
    return retrying(2).toBuilder().timeout(Duration.ofMillis(300)).build();
  }

  /** Returns the agent that {@code builder} builds, as a node with {@code config}. */
  private static LlmAgent withNodeConfig(LlmAgent.Builder builder, NodeConfig config) {
    return new LlmAgent(builder) {
      @Override
      public NodeConfig config() {
        return config;
      }
    };
  }

  /** Returns a {@code lookup} tool whose first call fails, as a flaky backend's does. */
  private static BaseTool failsOnce() {
    return new FlakyLookupTool(Single.error(new IllegalStateException("backend down")));
  }

  /** Returns a {@code lookup} tool whose first call never completes, as with a hung backend. */
  private static BaseTool stallsOnce() {
    return new FlakyLookupTool(Single.never());
  }

  /** Returns every text and function name in {@code request}'s contents, for substring checks. */
  private static String textsOf(LlmRequest request) {
    return request.contents().stream()
        .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
        .map(part ->
            part.text().orElse("")
                + part.functionCall().flatMap(FunctionCall::name).orElse("")
                + part.functionResponse().flatMap(FunctionResponse::name).orElse(""))
        .collect(joining("\n"));
  }

  /** Returns the context of {@code agent} running as a child of a node named {@code parent}. */
  private static Context nodeContextUnder(
      String parent, LlmAgent agent, InvocationContext invocationContext) {
    Context parentContext =
        new Context(
            invocationContext,
            NodeExecutionState.builder()
                .node(TestNode.of(parent))
                .eventSink(SINK)
                .runId("1")
                .build());
    return new Context(
        invocationContext,
        NodeExecutionState.builder()
            .node(agent)
            .eventSink(SINK)
            .parent(parentContext)
            .runId("1")
            .build());
  }

  private static Workflow chain(Node first, Node second) {
    return Workflow.builder()
        .name("wf")
        .edges(new Edge(Start.INSTANCE, first), new Edge(first, second))
        .build();
  }

  private static LlmAgent singleTurnAgentWithHistory(String name, TestLlm model) {
    return LlmAgent.builder()
        .name(name)
        .model(model)
        .mode(AgentMode.SINGLE_TURN)
        .includeContents(LlmAgent.IncludeContents.DEFAULT)
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

  /** A {@code lookup} tool whose first call gives {@code firstCall}; later calls succeed. */
  private static final class FlakyLookupTool extends BaseTool {
    private final Single<Map<String, Object>> firstCall;
    private final AtomicInteger calls = new AtomicInteger();

    FlakyLookupTool(Single<Map<String, Object>> firstCall) {
      super("lookup", "Looks something up.");
      this.firstCall = firstCall;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name("lookup").build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return calls.incrementAndGet() == 1
          ? firstCall
          : Single.just(ImmutableMap.of("result", "found"));
    }
  }

  /** A tool that asks for confirmation and, once approved, fails. */
  private static final class ConfirmThenFailTool extends BaseTool {
    ConfirmThenFailTool() {
      super("remove_file", "Removes a file.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name("remove_file").build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      if (toolContext.toolConfirmation().isEmpty()) {
        toolContext.requestConfirmation("Remove the file?");
        return Single.just(ImmutableMap.of("error", "needs confirmation"));
      }
      return Single.error(new IllegalStateException("failed after approval"));
    }
  }

  private record Trip(String city, int days) {}

  /** A value without properties, which JSON can't represent. */
  private static final class Opaque {
    @Override
    public String toString() {
      return "opaque";
    }
  }
}
