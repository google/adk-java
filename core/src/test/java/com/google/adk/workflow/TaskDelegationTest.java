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

import static com.google.adk.testing.ResumabilityTestUtils.longRunningEchoFunctionTool;
import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TaskDelegationTest {

  @Test
  public void chatAgent_delegatesATaskAndContinuesWithItsOutput() {
    TestLlm workerModel = TestLlm.create(finishTask("finish_1", "Booked."));
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book a table."), createTextLlmResponse("Done!"));
    LlmAgent coordinator = chat(model, task("worker", workerModel));

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(workerModel.getLastRequest().contents().get(0))
        .isEqualTo(userTurn("{\"request\":\"Book a table.\"}"));
    assertThat(responsesIn(model.getLastRequest()))
        .containsExactly(ImmutableMap.of("result", "Booked."));
    Event answer =
        events.stream()
            .filter(event ->
                !event.functionResponses().isEmpty()
                    && event.functionResponses().get(0).id().filter("t1"::equals).isPresent())
            .collect(onlyElement());
    assertThat(answer.author()).isEqualTo("user");
    Event workerCall =
        events.stream()
            .filter(event -> event.author().equals("worker") && !event.functionCalls().isEmpty())
            .collect(onlyElement());
    assertThat(pathOf(workerCall)).isEqualTo("wf@1/coordinator@1/worker@t1");
    assertThat(workerCall.isolationScope()).hasValue("t1");
    assertThat(workerCall.branch()).hasValue("worker@t1");
  }

  @Test
  public void chatAgent_offersATaskSubAgentAsAToolRatherThanATransferTarget() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Hi."));
    LlmAgent coordinator =
        chat(
            model,
            task("worker", TestLlm.create("ok")),
            LlmAgent.builder().name("other").model(TestLlm.create("ok")).build());

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    FunctionDeclaration declaration = declaration(model, "worker");
    assertThat(declaration.description().orElse(""))
        .endsWith("Do NOT call this tool in parallel with any other tools.");
    assertThat(declaration.parameters().flatMap(schema -> schema.required()))
        .hasValue(ImmutableList.of("request"));
    String instructions = String.join("\n", model.getLastRequest().getSystemInstructions());
    assertThat(instructions).contains("Agent name: other");
    assertThat(instructions).doesNotContain("Agent name: worker");
  }

  @Test
  public void finishTaskBesideADelegation_isRejectedAndTheTaskGoesOn() {
    TestLlm model =
        TestLlm.create(createLlmResponse(finishAndDelegate()), finishTask("finish_2", "All done."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(model)
            .mode(AgentMode.TASK)
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, planner))).turn(go());

    assertThat(responsesIn(model.getLastRequest()))
        .containsExactly(
            ImmutableMap.of(
                "error",
                "finish_task was ignored because a task delegation was sent in the same turn. Call"
                    + " finish_task by itself once the delegated result is available."),
            ImmutableMap.of("result", "Booked."))
        .inOrder();
    assertThat(
            events.stream()
                .filter(event -> pathOf(event).equals("wf@1/planner@1"))
                .flatMap(event -> event.output().stream()))
        .containsExactly(ImmutableMap.of("result", "All done."));
    assertThat(responsesTo("t1", events)).containsExactly(ImmutableMap.of("result", "Booked."));
  }

  @Test
  public void chatAgent_whoseTaskWaitsOnTheUser_waitsToo() {
    TestLlm model = TestLlm.create(delegate("t1", "worker", "Book."));
    LlmAgent coordinator =
        chat(model, task("worker", TestLlm.create(createTextLlmResponse("Which day?"))));

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(model.getRequests()).hasSize(1);
    assertThat(responsesTo("t1", events)).isEmpty();
  }

  @Test
  public void taskWaitingOnALongRunningCall_resumesWithTheAnswerAndAnswersItsDelegation() {
    TestLlm workerModel =
        TestLlm.create(
            createFunctionCallLlmResponse("ask_1", "pendingTool", ImmutableMap.of("message", "?")),
            finishTask("finish_1", "Booked."));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(workerModel)
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .build();
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("Done!"));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, chat(model, worker))));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("status", "ok"), "pendingTool"));

    assertThat(responsesIn(model.getLastRequest()))
        .containsExactly(ImmutableMap.of("result", "Booked."));
  }

  @Test
  public void delegationBesideAnotherCall_getsNoAnswerFromAnAfterToolCallback() {
    TestLlm model =
        TestLlm.create(createLlmResponse(finishAndDelegate()), finishTask("finish_2", "Done."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(model)
            .mode(AgentMode.TASK)
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .afterToolCallbackSync((context, tool, args, toolContext, response) ->
                tool.name().equals("finish_task")
                    ? Optional.empty()
                    : Optional.of(ImmutableMap.of("wrapped", String.valueOf(response))))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, planner))).turn(go());

    assertThat(responsesTo("t1", events)).containsExactly(ImmutableMap.of("result", "Booked."));
  }

  @Test
  public void delegationBesideAPendingLongRunningCall_waitsForThatCallBeforeTheNextRound() {
    Content delegateAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("ask_1")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "?")))
                    .build())
            .build();
    TestLlm model = TestLlm.create(createLlmResponse(delegateAndAsk), createTextLlmResponse("x"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    // Without resumability, only the round itself keeps the model from running on.
    new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false)
        .advance(go());

    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void taskNode_doesNotAnswerADelegationItMadeInAnotherScope() {
    TestLlm workerModel = TestLlm.create(finishTask("finish_w", "Booked."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(createTextLlmResponse("Which day?")))
            .mode(AgentMode.TASK)
            .subAgents(task("worker", workerModel))
            .build();
    Runner runner =
        Runner.builder()
            .app(App.builder().name("app").rootNode(workflow(new Edge(START, planner))).build())
            .build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();
    Event otherScopeDelegation =
        Event.builder()
            .id(Event.generateEventId())
            .author("planner")
            .isolationScope("other")
            .content(
                Content.builder()
                    .role("model")
                    .parts(
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder()
                                    .id("old")
                                    .name("worker")
                                    .args(ImmutableMap.of("request", "Book.")))
                            .build())
                    .build())
            .build();
    runner
        .sessionService()
        .appendEvent(session, otherScopeDelegation)
        .ignoreElement()
        .blockingAwait();

    runner.runAsync("user", session.id(), go()).blockingSubscribe();

    assertThat(workerModel.getRequests()).isEmpty();
  }

  @Test
  public void taskNode_answersAnUnansweredDelegationItMadeInItsOwnScope() {
    TestLlm workerModel = TestLlm.create(finishTask("finish_w", "Booked."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(TestLlm.create(createTextLlmResponse("Which day?")))
            .mode(AgentMode.TASK)
            .subAgents(task("worker", workerModel))
            .build();
    Runner runner =
        Runner.builder()
            .app(App.builder().name("app").rootNode(workflow(new Edge(START, planner))).build())
            .build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();
    Event ownScopeDelegation =
        Event.builder()
            .id(Event.generateEventId())
            .author("planner")
            // A task node's scope is its node path.
            .isolationScope("wf@1/planner@1")
            .content(
                Content.builder()
                    .role("model")
                    .parts(
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder()
                                    .id("old")
                                    .name("worker")
                                    .args(ImmutableMap.of("request", "Book.")))
                            .build())
                    .build())
            .build();
    runner
        .sessionService()
        .appendEvent(session, ownScopeDelegation)
        .ignoreElement()
        .blockingAwait();

    List<Event> events = runner.runAsync("user", session.id(), go()).toList().blockingGet();

    assertThat(responsesTo("old", events)).containsExactly(ImmutableMap.of("result", "Booked."));
  }

  @Test
  public void chatAgent_sharesItsMaxStepsAcrossDelegationRounds() {
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("Done!"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .maxSteps(1)
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void delegationBesideALongRunningCallAnsweredInTheSameStep_goesOnToTheNextRound() {
    Content delegateAndEcho =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("echo_1")
                            .name("echoTool")
                            .args(ImmutableMap.of("message", "hi")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(delegateAndEcho), createTextLlmResponse("Done!"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(longRunningEchoFunctionTool())
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false)
        .advance(go());

    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void chatAgent_resumeThatReplaysACallWithoutTheModel_doesNotUseUpItsMaxSteps() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?")),
                    createTextLlmResponse("Friday")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "q")),
            delegate("t1", "worker", "Book Friday."),
            createTextLlmResponse("Booked for Friday."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .maxSteps(2)
            .subAgents(helper, task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    // The resume replays the helper call without the model and delegates (step 1 of 2), so the
    // delegation round may still call the model once more.
    session.advance(answer("ask_1", ImmutableMap.of("status", "Friday"), "pendingTool"));

    assertThat(model.getRequests()).hasSize(3);
  }

  @Test
  public void delegationBesideAWaitingSingleTurnTool_resumesTheToolAfterTheTaskAnswers() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?")),
                    createTextLlmResponse("Friday")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    Content delegateAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("s1")
                            .name("helper")
                            .args(ImmutableMap.of("request", "When?")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(delegateAndAsk), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(helper, task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("status", "Friday"), "pendingTool"));

    assertThat(responsesIn(model.getLastRequest()))
        .containsExactly(ImmutableMap.of("result", "Booked."), ImmutableMap.of("result", "Friday"));
  }

  @Test
  public void transferBesideADelegation_isAnsweredWithAnErrorAndDoesNotHandOff() {
    TestLlm otherModel = TestLlm.create(createTextLlmResponse("Hi from other."));
    LlmAgent other = LlmAgent.builder().name("other").model(otherModel).build();
    Content delegateAndTransfer =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("x1")
                            .name("transfer_to_agent")
                            .args(ImmutableMap.of("agent_name", "other")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(delegateAndTransfer), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(other, task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(otherModel.getRequests()).isEmpty();
    assertThat(responsesTo("t1", events)).containsExactly(ImmutableMap.of("result", "Booked."));
    assertThat(responsesTo("x1", events)).hasSize(1);
    assertThat(responsesTo("x1", events).get(0)).containsKey("error");
  }

  @Test
  public void soloDelegationInANonResumableApp_callsTheModelOnlyAfterTheTaskAnswers() {
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        chat(model, task("worker", TestLlm.create(finishTask("finish_w", "Booked."))));

    new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false)
        .advance(go());

    assertThat(model.getRequests()).hasSize(2);
    assertThat(responsesIn(model.getRequests().get(1))).hasSize(1);
  }

  @Test
  public void soloDelegation_runsItsBeforeToolCallbackOnce() {
    AtomicInteger callbackRuns = new AtomicInteger();
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .beforeToolCallback((invocationContext, tool, args, toolContext) -> {
              callbackRuns.incrementAndGet();
              return Maybe.empty();
            })
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(callbackRuns.get()).isEqualTo(1);
    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void transferAndALongRunningCallBesideADelegation_goOnToTheNextRound() {
    Content threeCalls =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("x1")
                            .name("transfer_to_agent")
                            .args(ImmutableMap.of("agent_name", "worker")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("p1")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "?")))
                    .build())
            .build();
    LlmResponse response = createLlmResponse(threeCalls);
    TestLlm model = TestLlm.create(response, createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    // The rejected long-running call is answered, so the next round reads the task's answer.
    assertThat(model.getRequests()).hasSize(2);
    assertThat(responsesIn(model.getLastRequest())).hasSize(3);
  }

  @Test
  public void unknownToolAndAToolBesideADelegation_areBothAnsweredWithAnError() {
    Content threeCalls =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("u1")
                            .name("no_such_tool")
                            .args(ImmutableMap.of()))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("e1")
                            .name("echo_tool")
                            .args(ImmutableMap.of("x", 1)))
                    .build())
            .build();
    TestLlm model = TestLlm.create(createLlmResponse(threeCalls), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(new EchoTool())
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(model.getRequests()).hasSize(2);
    assertThat(responsesIn(model.getLastRequest())).hasSize(3);
    assertThat(getOnlyElement(responsesTo("u1", events))).containsKey("error");
    assertThat(getOnlyElement(responsesTo("e1", events))).containsKey("error");
  }

  @Test
  public void unknownToolBesideADelegation_isAnsweredWithAnErrorBeforeTheNextRound() {
    Content delegateAndUnknown =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("u1")
                            .name("no_such_tool")
                            .args(ImmutableMap.of()))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(delegateAndUnknown), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        chat(model, task("worker", TestLlm.create(finishTask("finish_w", "Booked."))));

    new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false)
        .advance(go());

    assertThat(model.getRequests()).hasSize(2);
    assertThat(responsesIn(model.getLastRequest())).hasSize(2);
  }

  @Test
  public void delegationThatABeforeToolCallbackAnswers_doesNotRunTheTask() {
    TestLlm workerModel = TestLlm.create(finishTask("finish_w", "Booked."));
    Content delegateAndEcho =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder().id("e1").name("echo_tool").args(ImmutableMap.of()))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(delegateAndEcho), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(new EchoTool())
            .subAgents(task("worker", workerModel))
            .beforeToolCallback((invocationContext, tool, args, toolContext) ->
                tool.name().equals("worker")
                    ? Maybe.just(ImmutableMap.of("blocked", true))
                    : Maybe.empty())
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(workerModel.getRequests()).isEmpty();
    assertThat(responsesTo("t1", events)).containsExactly(ImmutableMap.of("blocked", true));
  }

  @Test
  public void delegation_runsTheAgentsOwnSubAgentNotOneOfTheSameNameElsewhere() {
    TestLlm elsewhereModel = TestLlm.create(finishTask("finish_x", "Wrong."));
    TestLlm ownModel = TestLlm.create(finishTask("finish_w", "Booked."));
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("Done."));
    LlmAgent coordinator = chat(model, task("worker", ownModel));
    // An earlier branch of the tree has another task agent named worker.
    LlmAgent unusedRoot =
        LlmAgent.builder()
            .name("root")
            .model(TestLlm.create("ok"))
            .subAgents(
                LlmAgent.builder()
                    .name("first")
                    .model(TestLlm.create("ok"))
                    .subAgents(task("worker", elsewhereModel))
                    .build(),
                coordinator)
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(elsewhereModel.getRequests()).isEmpty();
    assertThat(ownModel.getRequests()).hasSize(1);
  }

  @Test
  public void taskWaitingInAnEarlierInvocation_isNotRunAgainByANewMessage() {
    TestLlm workerModel =
        TestLlm.create(createTextLlmResponse("Which day?"), createTextLlmResponse("unused"));
    TestLlm model = TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("x"));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, chat(model, task("worker", workerModel)))));
    session.advance(go());

    var unused = session.turn(userTurn("Anything else?"));

    assertThat(workerModel.getRequests()).hasSize(1);
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void chatAgent_twoDelegationsInOneTurn_runsBothAndAnswersEach() {
    Content twoDelegations =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("flights")
                            .args(ImmutableMap.of("request", "Fly.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t2")
                            .name("hotels")
                            .args(ImmutableMap.of("request", "Stay.")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(twoDelegations), createTextLlmResponse("All set."));
    LlmAgent coordinator =
        chat(
            model,
            task("flights", TestLlm.create(finishTask("f1", "Flight booked."))),
            task("hotels", TestLlm.create(finishTask("h1", "Hotel booked."))));

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(responsesIn(model.getLastRequest()))
        .containsExactly(
            ImmutableMap.of("result", "Flight booked."), ImmutableMap.of("result", "Hotel booked."))
        .inOrder();
  }

  @Test
  public void taskAgent_delegatesToItsOwnTaskSubAgent() {
    LlmAgent researcher =
        LlmAgent.builder()
            .name("researcher")
            .model(TestLlm.create(finishTask("r1", "Paris is best.")))
            .mode(AgentMode.TASK)
            .build();
    TestLlm plannerModel =
        TestLlm.create(
            delegate("t2", "researcher", "Pick a city."), finishTask("p1", "Go to Paris."));
    LlmAgent planner =
        LlmAgent.builder()
            .name("planner")
            .model(plannerModel)
            .mode(AgentMode.TASK)
            .subAgents(researcher)
            .build();
    TestLlm model =
        TestLlm.create(delegate("t1", "planner", "Plan a trip."), createTextLlmResponse("Done."));

    new WorkflowSession(workflow(new Edge(START, chat(model, planner)))).advance(go());

    assertThat(responsesIn(plannerModel.getLastRequest()))
        .contains(ImmutableMap.of("result", "Paris is best."));
    assertThat(responsesIn(model.getLastRequest()))
        .containsExactly(ImmutableMap.of("result", "Go to Paris."));
  }

  @Test
  public void taskWithANonObjectOutput_answersUnderOutput() {
    LlmAgent counter =
        LlmAgent.builder()
            .name("counter")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "c1", "finish_task", ImmutableMap.of("result", 3))))
            .mode(AgentMode.TASK)
            .outputSchema(Schema.builder().type("INTEGER").build())
            .build();
    TestLlm model =
        TestLlm.create(delegate("t1", "counter", "Count."), createTextLlmResponse("Three."));

    new WorkflowSession(workflow(new Edge(START, chat(model, counter)))).advance(go());

    assertThat(responsesIn(model.getLastRequest())).containsExactly(ImmutableMap.of("output", 3));
  }

  @Test
  public void chatAgent_runningAgain_answersADelegationLeftUnanswered() {
    TestLlm workerModel = TestLlm.create(finishTask("finish_w", "Booked."));
    TestLlm model = TestLlm.create(createTextLlmResponse("Done."));
    LlmAgent coordinator = chat(model, task("worker", workerModel));
    Runner runner =
        Runner.builder()
            .app(App.builder().name("app").rootNode(workflow(new Edge(START, coordinator))).build())
            .build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();
    Event delegation =
        Event.builder()
            .id(Event.generateEventId())
            .author("coordinator")
            .content(
                Content.builder()
                    .role("model")
                    .parts(
                        Part.builder()
                            .functionCall(
                                FunctionCall.builder()
                                    .id("old")
                                    .name("worker")
                                    .args(ImmutableMap.of("request", "Book.")))
                            .build())
                    .build())
            .build();
    runner.sessionService().appendEvent(session, delegation).ignoreElement().blockingAwait();

    List<Event> events = runner.runAsync("user", session.id(), go()).toList().blockingGet();

    assertThat(responsesTo("old", events)).containsExactly(ImmutableMap.of("result", "Booked."));
  }

  @Test
  public void taskWithAnInputSchema_getsTheCallsArgsAsItsInput() {
    Schema bookingSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("city", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("city"))
            .build();
    TestLlm workerModel = TestLlm.create(finishTask("finish_w", "Booked."));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(workerModel)
            .mode(AgentMode.TASK)
            .inputSchema(bookingSchema)
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("t1", "worker", ImmutableMap.of("city", "Paris")),
            createTextLlmResponse("Done."));

    new WorkflowSession(workflow(new Edge(START, chat(model, worker)))).advance(go());

    assertThat(declaration(model, "worker").parameters()).hasValue(bookingSchema);
    assertThat(workerModel.getLastRequest().contents().toString()).contains("Paris");
  }

  @Test
  public void chatAgentWithOneStep_resumeThatReplaysACall_stillCallsTheModelOnce() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "ask_1", "pendingTool", ImmutableMap.of("message", "?")),
                    createTextLlmResponse("Friday")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(pendingFunctionTool())
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("call_1", "helper", ImmutableMap.of("request", "q")),
            createTextLlmResponse("It is Friday."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .maxSteps(1)
            .subAgents(helper, task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    // A new invocation: the replay calls no model, so its one step is still free.
    session.advance(answer("ask_1", ImmutableMap.of("status", "Friday"), "pendingTool"));

    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void soloDelegationThatABeforeToolCallbackAnswers_doesNotRunTheTask() {
    TestLlm workerModel = TestLlm.create(finishTask("finish_w", "Booked."));
    TestLlm model =
        TestLlm.create(delegate("t1", "worker", "Book."), createTextLlmResponse("Not booked."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(task("worker", workerModel))
            .beforeToolCallback((invocationContext, tool, args, toolContext) ->
                tool.name().equals("worker")
                    ? Maybe.just(ImmutableMap.of("blocked", true))
                    : Maybe.empty())
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(workerModel.getRequests()).isEmpty();
    assertThat(responsesTo("t1", events)).containsExactly(ImmutableMap.of("blocked", true));
    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void toolsetToolBesideADelegation_runsAndAnswers() {
    BaseToolset toolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
            return Flowable.just(new EchoTool());
          }

          @Override
          public void close() {}
        };
    Content delegateAndEcho =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("e1")
                            .name("echo_tool")
                            .args(ImmutableMap.of("x", 1)))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(createLlmResponse(delegateAndEcho), createTextLlmResponse("Done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .tools(toolset)
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(responsesTo("e1", events))
        .containsExactly(ImmutableMap.of("result", ImmutableMap.of("x", 1)));
    assertThat(responsesTo("t1", events)).containsExactly(ImmutableMap.of("result", "Booked."));
  }

  @Test
  public void taskSubAgentOutsideAWorkflow_isATransferTargetAndNotATool() {
    TestLlm workerModel = TestLlm.create("Booked.");
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "call_1", "transfer_to_agent", ImmutableMap.of("agent_name", "worker")));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .subAgents(task("worker", workerModel))
            .build();
    Runner runner =
        Runner.builder().app(App.builder().name("app").rootAgent(coordinator).build()).build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();

    runner.runAsync("user", session.id(), go()).blockingSubscribe();

    assertThat(
            model
                .getRequests()
                .get(0)
                .config()
                .flatMap(c -> c.tools())
                .orElse(ImmutableList.of())
                .stream()
                .flatMap(tool -> tool.functionDeclarations().orElse(ImmutableList.of()).stream())
                .map(declaration -> declaration.name().orElse("")))
        .containsExactly("transfer_to_agent");
    assertThat(workerModel.getRequests()).hasSize(1);
  }

  @Test
  public void chatAgent_laterDelegationRoundResumesTheStepCount() {
    TestLlm model =
        TestLlm.create(
            delegate("t1", "worker", "Book."),
            createFunctionCallLlmResponse("e1", "echo_tool", ImmutableMap.of()),
            createTextLlmResponse("Done!"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .maxSteps(2)
            .tools(new EchoTool())
            .subAgents(task("worker", TestLlm.create(finishTask("finish_w", "Booked."))))
            .build();

    new WorkflowSession(workflow(new Edge(START, coordinator))).advance(go());

    assertThat(model.getRequests()).hasSize(2);
  }

  private static LlmAgent chat(TestLlm model, BaseAgent... subAgents) {
    return LlmAgent.builder()
        .name("coordinator")
        .model(model)
        .mode(AgentMode.CHAT)
        .subAgents(subAgents)
        .build();
  }

  private static LlmAgent task(String name, TestLlm model) {
    return LlmAgent.builder().name(name).model(model).mode(AgentMode.TASK).build();
  }

  private static LlmResponse delegate(String id, String agent, String request) {
    return createFunctionCallLlmResponse(id, agent, ImmutableMap.of("request", request));
  }

  private static LlmResponse finishTask(String id, String result) {
    return createFunctionCallLlmResponse(id, "finish_task", ImmutableMap.of("result", result));
  }

  private static Content userTurn(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  private static ImmutableList<Map<String, Object>> responsesIn(LlmRequest request) {
    return request.contents().stream()
        .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
        .flatMap(part -> part.functionResponse().stream())
        .map(response -> response.response().orElse(ImmutableMap.of()))
        .collect(toImmutableList());
  }

  private static Content finishAndDelegate() {
    return Content.builder()
        .role("model")
        .parts(
            Part.builder()
                .functionCall(
                    FunctionCall.builder()
                        .id("finish_1")
                        .name("finish_task")
                        .args(ImmutableMap.of("result", "Too early.")))
                .build(),
            Part.builder()
                .functionCall(
                    FunctionCall.builder()
                        .id("t1")
                        .name("worker")
                        .args(ImmutableMap.of("request", "Book.")))
                .build())
        .build();
  }

  private static ImmutableList<Map<String, Object>> responsesTo(String id, List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionResponses().stream())
        .filter(response -> response.id().filter(id::equals).isPresent())
        .map(response -> response.response().orElse(ImmutableMap.of()))
        .collect(toImmutableList());
  }

  private static FunctionDeclaration declaration(TestLlm model, String name) {
    return model.getLastRequest().config().orElseThrow().tools().orElseThrow().stream()
        .flatMap(tool -> tool.functionDeclarations().orElse(ImmutableList.of()).stream())
        .filter(declaration -> declaration.name().filter(name::equals).isPresent())
        .collect(onlyElement());
  }
}
