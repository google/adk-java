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
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.modelText;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.ToolExecutionMode;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LlmAgentModeTest {

  private static final Schema CITY_SCHEMA =
      Schema.builder()
          .type("OBJECT")
          .properties(ImmutableMap.of("city", Schema.builder().type("STRING").build()))
          .required(ImmutableList.of("city"))
          .build();

  @Test
  public void chatAgentNode_continuesTheConversationNextTurnWithoutRunningItsSuccessor() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which city?"), createTextLlmResponse("Paris is lovely."));
    LlmAgent guide = LlmAgent.builder().name("guide").model(model).mode(AgentMode.CHAT).build();
    Node after = emitter("after", "AFTER");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, guide), new Edge(guide, after)));

    var firstTurn = session.turn(userText("Plan a trip"));
    var secondTurn = session.turn(userText("Paris"));

    assertThat(model.getLastRequest().contents())
        .containsExactly(userText("Plan a trip"), modelText("Which city?"), userText("Paris"))
        .inOrder();
    assertThat(pathsOf(firstTurn)).doesNotContain("wf@1/after@1");
    assertThat(pathsOf(secondTurn)).doesNotContain("wf@1/after@1");
  }

  @Test
  public void chatAgentInANestedWorkflow_failsTheRun() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);
    Node inner = workflow("inner", new Edge(START, guide));
    Node after = emitter("after", "AFTER");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, inner), new Edge(inner, after)));

    var failed = session.failingTurn(userText("Plan a trip"));

    assertThat(failed.error()).isInstanceOf(GraphValidationException.class);
    assertThat(failed.error()).hasMessageThat().contains("runs inside another workflow");
    assertThat(pathsOf(failed.events())).doesNotContain("wf@1/after@1");
  }

  @Test
  public void chatAgentInAWorkflowThatARootNodeRuns_stillRuns() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);
    Node inner = workflow("inner", new Edge(START, guide));
    Node root = dispatcher("root", (ctx, input) -> ctx.runNode(inner, input).toFlowable());

    var events = new WorkflowSession(root).turn(userText("Hi"));

    assertThat(pathsOf(events)).contains("root@1/inner@1/guide@1");
  }

  @Test
  public void finishedChatAgent_doesNotRunAgainWhenASiblingResumesFromAnInterrupt() {
    TestLlm model = TestLlm.create("Hello!", "Hello again!");
    LlmAgent guide = LlmAgent.builder().name("guide").model(model).mode(AgentMode.CHAT).build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, guide), new Edge(START, longRunningAsk("ask", "i1"))));
    session.advance(go());

    session.advance(answer("i1", ImmutableMap.of("ok", true)));

    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void chatAgentInATopLevelWorkflow_stillRuns() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);

    var events = new WorkflowSession(workflow(new Edge(START, guide))).turn(userText("Hi"));

    assertThat(pathsOf(events)).contains("wf@1/guide@1");
  }

  @Test
  public void chatAgentFollowingANodeOtherThanStart_failsValidation() {
    LlmAgent guide = agent("guide", AgentMode.CHAT);
    Node source = emitter("source", "S");
    Edge first = new Edge(START, source);
    Edge second = new Edge(source, guide);

    GraphValidationException e =
        assertThrows(GraphValidationException.class, () -> workflow(first, second));

    assertThat(e).hasMessageThat().contains("AgentMode.CHAT following node 'source'");
  }

  @Test
  public void agentWithoutModeInATreeWithModes_isAChatAgent() {
    LlmAgent helper = agent("helper", AgentMode.SINGLE_TURN);
    LlmAgent worker = LlmAgent.builder().name("worker").model(TestLlm.create("ok")).build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create("ok"))
            .subAgents(helper, worker)
            .build();

    assertThat(coordinator.waitForOutput()).isTrue();
    assertThat(worker.waitForOutput()).isTrue();
    assertThat(helper.waitForOutput()).isFalse();
  }

  @Test
  public void agentWithoutModeInATreeWithoutModes_keepsRunningAsBefore() {
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create("ok"))
            .subAgents(LlmAgent.builder().name("worker").model(TestLlm.create("ok")).build())
            .build();
    Node source = emitter("source", "S");

    Workflow unused = workflow(new Edge(START, source), new Edge(source, coordinator));

    assertThat(coordinator.waitForOutput()).isFalse();
  }

  @Test
  public void agentMadeAChatAgentAfterTheGraphWasBuilt_failsTheRun() {
    LlmAgent worker = LlmAgent.builder().name("worker").model(TestLlm.create("ok")).build();
    Node source = emitter("source", "S");
    Workflow graph = workflow(new Edge(START, source), new Edge(source, worker));
    LlmAgent unused =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create("ok"))
            .subAgents(worker, agent("helper", AgentMode.SINGLE_TURN))
            .build();

    Throwable error = new WorkflowSession(graph).failingTurn(go()).error();

    assertThat(error).isInstanceOf(GraphValidationException.class);
    assertThat(error).hasMessageThat().contains("AgentMode.CHAT following node 'source'");
    assertThat(error).hasMessageThat().contains("The agent has no mode");
  }

  @Test
  public void chatAgent_callsASingleTurnSubAgentAsAToolOnItsOwnBranch() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Paris"));
    LlmAgent helper = singleTurn("helper", helperModel);
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "helper", ImmutableMap.of("request", "Capital of France?")),
            createTextLlmResponse("It is Paris."));
    LlmAgent coordinator = chat("coordinator", model, helper);

    var events = new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(helperModel.getLastRequest().contents())
        .containsExactly(userText("Capital of France?"));
    assertThat(functionResponses(model)).containsExactly(ImmutableMap.of("result", "Paris"));
    Event helperAnswer =
        events.stream()
            .filter(event -> event.author().equals("helper") && event.content().isPresent())
            .collect(onlyElement());
    assertThat(pathOf(helperAnswer)).isEqualTo("wf@1/coordinator@1/helper@call_1");
    assertThat(helperAnswer.branch()).hasValue("helper@call_1");
  }

  @Test
  public void singleTurnSubAgentThatWaitsOnTheUser_resumesWithTheAnswer() {
    TestLlm helperModel =
        TestLlm.create(
            createFunctionCallLlmResponse("ask_1", "pendingTool", ImmutableMap.of("message", "?")),
            createTextLlmResponse("Paris"));
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(helperModel)
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "helper", ImmutableMap.of("request", "Capital of France?")),
            createTextLlmResponse("It is Paris."));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("status", "Paris"), "pendingTool"));

    assertThat(functionResponses(model)).containsExactly(ImmutableMap.of("result", "Paris"));
    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void toolBesideASingleTurnSubAgentThatWaits_runsOnceAcrossTheResume() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?")),
                    createTextLlmResponse("Paris")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    Content bookAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder().id("b1").name("book").args(ImmutableMap.of()))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call_1")
                            .name("helper")
                            .args(ImmutableMap.of("request", "Capital?")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(bookAndAsk), createTextLlmResponse("Booked, Paris."));
    AtomicInteger bookings = new AtomicInteger();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(countingTool("book", bookings))
            .subAgents(helper)
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("status", "Paris"), "pendingTool"));

    assertThat(bookings.get()).isEqualTo(1);
    assertThat(functionResponses(model))
        .containsExactly(ImmutableMap.of("result", "booked"), ImmutableMap.of("result", "Paris"));
  }

  @Test
  public void
      confirmedToolBesideAConfirmedSingleTurnSubAgentThatWaits_keepsItsAnswerAndResumesTheAgent() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?")),
                    createTextLlmResponse("Paris")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    Content bookAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder().id("b1").name("book").args(ImmutableMap.of()))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call_1")
                            .name("helper")
                            .args(ImmutableMap.of("request", "Capital?")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(bookAndAsk), createTextLlmResponse("Booked, Paris."));
    AtomicInteger bookings = new AtomicInteger();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(countingTool("book", bookings))
            .subAgents(helper)
            .beforeToolCallback((invocationContext, tool, args, toolContext) -> {
              if (toolContext.toolConfirmation().isPresent()) {
                return Maybe.empty();
              }
              toolContext.requestConfirmation("Approve?");
              return Maybe.just(ImmutableMap.of("error", "Needs approval."));
            })
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    ImmutableList<Event> first = session.turn(go());
    ImmutableList<FunctionCall> confirmations =
        first.stream()
            .flatMap(event -> Functions.getAskUserConfirmationFunctionCalls(event).stream())
            .collect(toImmutableList());
    assertThat(confirmations).hasSize(2);
    session.advance(
        Content.builder()
            .role("user")
            .parts(
                confirmations.stream()
                    .map(call ->
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id(call.id().get())
                                    .name(call.name().get())
                                    .response(ImmutableMap.of("confirmed", true)))
                            .build())
                    .collect(toImmutableList()))
            .build());

    session.advance(answer("ask_1", ImmutableMap.of("status", "Paris"), "pendingTool"));

    assertThat(bookings.get()).isEqualTo(1);
    assertThat(functionResponses(model))
        .containsAtLeast(ImmutableMap.of("result", "booked"), ImmutableMap.of("result", "Paris"));
  }

  @Test
  public void
      sequentialConfirmedToolAfterAConfirmedSingleTurnSubAgentThatWaits_runsOnceAndResumes() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?")),
                    createTextLlmResponse("Paris")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    Content bookAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder().id("b1").name("book").args(ImmutableMap.of()))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call_1")
                            .name("helper")
                            .args(ImmutableMap.of("request", "Capital?")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(bookAndAsk), createTextLlmResponse("Booked, Paris."));
    AtomicInteger bookings = new AtomicInteger();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(countingTool("book", bookings))
            .subAgents(helper)
            .beforeToolCallback((invocationContext, tool, args, toolContext) -> {
              if (toolContext.toolConfirmation().isPresent()) {
                return Maybe.empty();
              }
              toolContext.requestConfirmation("Approve?");
              return Maybe.just(ImmutableMap.of("error", "Needs approval."));
            })
            .build();
    // Confirmed calls run in the confirmation request's order, which follows hash order and may put
    // helper first; book must still run exactly once.
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, coordinator)))
            .withRunConfig(
                RunConfig.builder().setToolExecutionMode(ToolExecutionMode.SEQUENTIAL).build());
    ImmutableList<Event> first = session.turn(go());
    ImmutableList<FunctionCall> confirmations =
        first.stream()
            .flatMap(event -> Functions.getAskUserConfirmationFunctionCalls(event).stream())
            .collect(toImmutableList());
    session.advance(
        Content.builder()
            .role("user")
            .parts(
                confirmations.stream()
                    .map(call ->
                        Part.builder()
                            .functionResponse(
                                FunctionResponse.builder()
                                    .id(call.id().get())
                                    .name(call.name().get())
                                    .response(ImmutableMap.of("confirmed", true)))
                            .build())
                    .collect(toImmutableList()))
            .build());

    session.advance(answer("ask_1", ImmutableMap.of("status", "Paris"), "pendingTool"));

    assertThat(bookings.get()).isEqualTo(1);
    assertThat(functionResponses(model))
        .containsAtLeast(ImmutableMap.of("result", "booked"), ImmutableMap.of("result", "Paris"));
  }

  @Test
  public void singleTurnSubAgentThatWaits_isNotHandledByAToolErrorCallback() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?"))))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("done"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(helper)
            .onToolErrorCallback((context, tool, args, toolContext, error) ->
                Maybe.just(ImmutableMap.of("error", "handled")))
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void transferTarget_doesNotSeeASingleTurnToolsSubBranch() {
    TestLlm otherModel = TestLlm.create(createTextLlmResponse("Hi."));
    LlmAgent other = LlmAgent.builder().name("other").model(otherModel).build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createFunctionCallLlmResponse(
                "call_2", "transfer_to_agent", ImmutableMap.of("agent_name", "other")));
    LlmAgent coordinator =
        chat(
            "coordinator",
            model,
            singleTurn("helper", TestLlm.create(createTextLlmResponse("Paris"))),
            other);

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(otherModel.getLastRequest().contents().toString()).doesNotContain("[helper]");
  }

  @Test
  public void nodeFromAnotherAgentTree_doesNotSeeASingleTurnToolsSubBranch() {
    TestLlm step1Model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("Done."));
    LlmAgent step1 =
        LlmAgent.builder()
            .name("step1")
            .model(step1Model)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(singleTurn("helper", TestLlm.create(createTextLlmResponse("Paris"))))
            .build();
    TestLlm step2Model = TestLlm.create(createTextLlmResponse("Hi."));
    LlmAgent step2 = LlmAgent.builder().name("step2").model(step2Model).build();

    new WorkflowSession(workflow(new Edge(START, step1), new Edge(step1, step2))).advance(go());

    assertThat(step2Model.getLastRequest().contents().toString()).doesNotContain("[helper]");
  }

  @Test
  public void chatAgent_offersASingleTurnSubAgentAsAToolRatherThanATransferTarget() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Hi."));
    LlmAgent coordinator =
        chat(
            "coordinator",
            model,
            singleTurn("helper", TestLlm.create("ok")),
            LlmAgent.builder().name("other").model(TestLlm.create("ok")).build());

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    LlmRequest request = model.getLastRequest();
    assertThat(request.tools().keySet()).containsAtLeast("helper", "transfer_to_agent");
    assertThat(String.join("\n", request.getSystemInstructions())).contains("Agent name: other");
    assertThat(String.join("\n", request.getSystemInstructions()))
        .doesNotContain("Agent name: helper");
  }

  @Test
  public void chatAgent_cannotTransferToASingleTurnSubAgent() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Paris"));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "transfer_to_agent", ImmutableMap.of("agent_name", "helper")),
            createTextLlmResponse("done"));
    LlmAgent coordinator = chat("coordinator", model, singleTurn("helper", helperModel));

    var unused = new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(helperModel.getRequests()).isEmpty();
  }

  @Test
  public void chatAgentInANonResumableApp_cannotTransferToASingleTurnSubAgent() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Paris"));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "transfer_to_agent", ImmutableMap.of("agent_name", "helper")),
            createTextLlmResponse("done"));
    // A regular peer makes the agent offer transfer_to_agent at all.
    LlmAgent coordinator =
        chat(
            "coordinator",
            model,
            singleTurn("helper", helperModel),
            LlmAgent.builder().name("other").model(TestLlm.create("ok")).build());

    var failed =
        new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false)
            .failingTurn(go());

    assertThat(helperModel.getRequests()).isEmpty();
    assertThat(failed.error()).hasMessageThat().contains("Cannot transfer to 'helper'");
  }

  @Test
  public void singleTurnSubAgentWithAnInputSchema_getsTheArgsAsItsInput() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Sunny"));
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(helperModel)
            .mode(AgentMode.SINGLE_TURN)
            .inputSchema(CITY_SCHEMA)
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("city", "Paris")),
            createTextLlmResponse("done"));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))))
        .advance(go());

    assertThat(helperModel.getLastRequest().contents())
        .containsExactly(userText("{\"city\":\"Paris\"}"));
  }

  @Test
  public void singleTurnSubAgentWithAnInputSchema_getsTheDefaultsOfOmittedArgs() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Sunny"));
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(helperModel)
            .mode(AgentMode.SINGLE_TURN)
            .inputSchema(
                CITY_SCHEMA.toBuilder()
                    .properties(
                        ImmutableMap.of(
                            "city",
                            Schema.builder().type("STRING").build(),
                            "unit",
                            Schema.builder().type("STRING").default_("celsius").build()))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("city", "Paris")),
            createTextLlmResponse("done"));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))))
        .advance(go());

    assertThat(helperModel.getLastRequest().contents())
        .containsExactly(userText("{\"city\":\"Paris\",\"unit\":\"celsius\"}"));
  }

  @Test
  public void agent_doesNotListASingleTurnPeerAsATransferTarget() {
    LlmAgent agent = LlmAgent.builder().name("agent").model(TestLlm.create("ok")).build();
    LlmAgent peer = LlmAgent.builder().name("peer").model(TestLlm.create("ok")).build();
    LlmAgent helper = singleTurn("helper", TestLlm.create("ok"));
    LlmAgent unused =
        LlmAgent.builder()
            .name("parent")
            .model(TestLlm.create("ok"))
            .subAgents(agent, peer, helper)
            .build();

    assertThat(agent.transferTargets().stream().map(BaseAgent::name))
        .containsExactly("peer", "parent");
  }

  @Test
  public void singleTurnSubAgentWithAMapOutput_answersWithThatMap() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create(createTextLlmResponse("{\"city\": \"Paris\"}")))
            .mode(AgentMode.SINGLE_TURN)
            .outputSchema(CITY_SCHEMA)
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("done"));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))))
        .advance(go());

    assertThat(functionResponses(model)).containsExactly(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void singleTurnSubAgentWithoutOutput_answersWithANullResult() {
    LlmAgent helper =
        singleTurn(
            "helper",
            TestLlm.create(createFunctionCallLlmResponse("x", "missing", ImmutableMap.of())));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("done"));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))))
        .advance(go());

    assertThat(onlyResult(model)).isNull();
  }

  @Test
  public void singleTurnSubAgent_argsThatDoNotMatchItsInputSchema_answerWithAnError() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create("ok"))
            .mode(AgentMode.SINGLE_TURN)
            .inputSchema(CITY_SCHEMA)
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("town", "Paris")),
            createTextLlmResponse("done"));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))))
        .advance(go());

    assertThat(onlyResult(model)).startsWith("Error validating input:");
  }

  @Test
  public void failingSingleTurnSubAgent_answersWithAnError() {
    LlmAgent helper =
        singleTurn("helper", TestLlm.create(null, new IllegalStateException("model down")));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("done"));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, helper))))
        .advance(go());

    assertThat(onlyResult(model)).startsWith("Error running sub-agent:");
  }

  @Test
  public void singleTurnSubAgentOutsideAWorkflow_isATransferTargetAndNotATool() {
    TestLlm helperModel = TestLlm.create("Paris");
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "transfer_to_agent", ImmutableMap.of("agent_name", "helper")));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .subAgents(singleTurn("helper", helperModel))
            .build();

    runAsRootAgent(coordinator);

    assertThat(declaredTools(model)).containsExactly("transfer_to_agent");
    assertThat(helperModel.getRequests()).hasSize(1);
  }

  @Test
  public void singleTurnToolOfATransferTarget_runsInTheTargetsNode() {
    TestLlm specialistModel =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("done"));
    LlmAgent specialist =
        chat(
            "specialist",
            specialistModel,
            singleTurn("helper", TestLlm.create(createTextLlmResponse("ok"))));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "t_1", "transfer_to_agent", ImmutableMap.of("agent_name", "specialist")));

    new WorkflowSession(workflow(new Edge(START, chat("coordinator", model, specialist))))
        .advance(go());

    // The workflow runs a transfer target as its own node, so its single-turn tool can run.
    assertThat(onlyResult(specialistModel)).isEqualTo("ok");
  }

  @Test
  public void singleTurnSubAgent_callIdThatCannotBeARunId_answersWithAnError() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("1", "helper", ImmutableMap.of("request", "hi")),
            createTextLlmResponse("done"));
    LlmAgent coordinator = chat("coordinator", model, singleTurn("helper", TestLlm.create("ok")));

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(onlyResult(model))
        .isEqualTo("Error running sub-agent: runId \"1\" must contain a non-digit character.");
  }

  /** Returns a tool named {@code name} that counts its runs in {@code runs}. */
  private static BaseTool countingTool(String name, AtomicInteger runs) {
    return new BaseTool(name, "Counts its runs.") {
      @Override
      public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder().name(name).build());
      }

      @Override
      public Single<Map<String, Object>> runAsync(
          Map<String, Object> args, ToolContext toolContext) {
        runs.incrementAndGet();
        return Single.just(ImmutableMap.of("result", "booked"));
      }
    };
  }

  private static LlmAgent chat(String name, TestLlm model, BaseAgent... subAgents) {
    return LlmAgent.builder()
        .name(name)
        .model(model)
        .mode(AgentMode.CHAT)
        .subAgents(subAgents)
        .build();
  }

  private static LlmAgent singleTurn(String name, TestLlm model) {
    return LlmAgent.builder().name(name).model(model).mode(AgentMode.SINGLE_TURN).build();
  }

  /** Returns the function responses in the last request {@code model} got. */
  private static ImmutableList<Map<String, Object>> functionResponses(TestLlm model) {
    return model.getLastRequest().contents().stream()
        .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
        .flatMap(part -> part.functionResponse().stream())
        .map(response -> response.response().orElse(ImmutableMap.of()))
        .collect(toImmutableList());
  }

  private static ImmutableList<String> declaredTools(TestLlm model) {
    return model
        .getRequests()
        .get(0)
        .config()
        .flatMap(c -> c.tools())
        .orElse(ImmutableList.of())
        .stream()
        .flatMap(tool -> tool.functionDeclarations().orElse(ImmutableList.of()).stream())
        .map(declaration -> declaration.name().orElse(""))
        .collect(toImmutableList());
  }


  private static String onlyResult(TestLlm model) {
    return (String) functionResponses(model).stream().collect(onlyElement()).get("result");
  }

  private static void runAsRootAgent(LlmAgent agent) {
    Runner runner =
        Runner.builder().app(App.builder().name("app").rootAgent(agent).build()).build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();
    runner.runAsync("user", session.id(), go()).blockingSubscribe();
  }

  private static LlmAgent agent(String name, AgentMode mode) {
    return LlmAgent.builder().name(name).model(TestLlm.create("ok")).mode(mode).build();
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }
}
