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

import static com.google.adk.testing.ResumabilityTestUtils.assertEndOfAgent;
import static com.google.adk.testing.ResumabilityTestUtils.pendingFunctionTool;
import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
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
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgentTransferLoopTest {

  @Test
  public void chatNodeHandoff_runsTheSubAgentOnceAsAChildNode() {
    TestLlm childModel = says("from child");
    LlmAgent child = chat("child", childModel);
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    Event reply = contentOf("child", events);
    assertThat(reply.stringifyContent()).isEqualTo("from child");
    assertThat(pathOf(reply)).isEqualTo("wf@1/coordinator@1/child@1");
    assertThat(childModel.getRequests()).hasSize(1);
  }

  @Test
  public void chatNodeHandoff_runsTheNodesAfterAgentCallback() {
    AtomicInteger afterRuns = new AtomicInteger();
    LlmAgent child = chat("child", says("from child"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("child")))
            .mode(AgentMode.CHAT)
            .subAgents(child)
            .afterAgentCallbackSync(callbackContext -> {
              afterRuns.incrementAndGet();
              return Optional.empty();
            })
            .build();

    var unused = new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    // As in Kotlin, the node's after-agent callback runs after its handoff.
    assertThat(afterRuns.get()).isEqualTo(1);
  }

  @Test
  public void taskNodeHandoff_runsTheSubAgentOnceAsAChildNode() {
    TestLlm childModel = says("from child");
    LlmAgent child = chat("child", childModel);
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("child")))
            .mode(AgentMode.TASK)
            .subAgents(child)
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    Event reply = contentOf("child", events);
    assertThat(pathOf(reply)).isEqualTo("wf@1/coordinator@1/child@1");
    assertThat(childModel.getRequests()).hasSize(1);
  }

  @Test
  public void inlineChatAgentBelowASingleTurnNode_stillRunsItsHandoffTarget() {
    TestLlm refundsModel = says("Refund sent.");
    LlmAgent refunds = chat("refunds", refundsModel);
    TestLlm billingModel = TestLlm.create(transferTo("refunds"));
    LlmAgent billing = chat("billing", billingModel, refunds);
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("billing")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(billing)
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, router), new Edge(router, captureInput("reply"))))
            .turn(go());

    // billing runs inline in the router's node, so its own handoff to refunds runs inline too,
    // and, as in Python, the last agent's reply is the node's output.
    assertThat(refundsModel.getRequests()).hasSize(1);
    assertThat(billingModel.getRequests()).hasSize(1);
    assertThat(replyInput(events)).isEqualTo("Refund sent.");
  }

  @Test
  public void singleTurnNodesInlineTarget_callsItsOwnSingleTurnTool() {
    TestLlm translatorModel = says("Bonjour");
    LlmAgent translator =
        LlmAgent.builder()
            .name("translator")
            .model(translatorModel)
            .mode(AgentMode.SINGLE_TURN)
            .build();
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "h1", "translator", ImmutableMap.of("request", "good morning")),
                    createTextLlmResponse("It is Bonjour.")))
            .subAgents(translator)
            .build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(helper)
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, router), new Edge(router, captureInput("reply"))))
            .turn(go());

    // As in Python, the target the router runs inline can call its own single-turn agent tool.
    assertThat(translatorModel.getRequests()).hasSize(1);
    assertThat(replyInput(events)).isEqualTo("It is Bonjour.");
  }

  @Test
  public void singleTurnNodesTwoHopInlineTarget_callsItsOwnSingleTurnTool() {
    TestLlm translatorModel = says("Bonjour");
    LlmAgent translator =
        LlmAgent.builder()
            .name("translator")
            .model(translatorModel)
            .mode(AgentMode.SINGLE_TURN)
            .build();
    LlmAgent specialist =
        LlmAgent.builder()
            .name("specialist")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "s1", "translator", ImmutableMap.of("request", "good morning")),
                    createTextLlmResponse("It is Bonjour.")))
            .subAgents(translator)
            .build();
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(TestLlm.create(transferTo("specialist")))
            .subAgents(specialist)
            .build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(helper)
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, router), new Edge(router, captureInput("reply"))))
            .turn(go());

    // A target two hops below the router still runs in its node, so it can call its own tool.
    assertThat(translatorModel.getRequests()).hasSize(1);
    assertThat(replyInput(events)).isEqualTo("It is Bonjour.");
  }

  @Test
  public void singleTurnNodeWithAnOutputSchema_checksItsTargetsReplyAgainstIt() {
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(says("{\"answer\": \"Paris\"}")).build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.SINGLE_TURN)
            .outputSchema(answerSchema())
            .subAgents(helper)
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, router), new Edge(router, captureInput("reply"))))
            .turn(go());

    // As in Python, the router's schema applies to its target's reply, which becomes the output.
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("answer", "Paris"));
  }

  @Test
  public void singleTurnNodeWithAnOutputSchema_failsOnItsTargetsFreeTextReply() {
    LlmAgent helper = LlmAgent.builder().name("helper").model(says("Plain text.")).build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.SINGLE_TURN)
            .outputSchema(answerSchema())
            .subAgents(helper)
            .build();

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, router))).failingTurn(go());

    // As in Python, a target's free-text reply does not match the router's schema.
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void singleTurnNodeWithAnOutputSchema_replaysItsTargetsReplyOnResume() {
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(says("{\"answer\": \"Paris\"}")).build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.SINGLE_TURN)
            .outputSchema(answerSchema())
            .subAgents(helper)
            .build();
    Node ask = longRunningAsk("ask", "ask_1");
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, router),
                new Edge(router, ask),
                new Edge(ask, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    // The stored reply keeps its output, so the resume replays it instead of parsing it again.
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("v", 1));
  }

  @Test
  public void singleTurnNodeHandoffToATaskSubAgent_failsTheWorkflow() {
    TestLlm bookerModel = says("unused");
    LlmAgent booker =
        LlmAgent.builder().name("booker").model(bookerModel).mode(AgentMode.TASK).build();
    // The chat sibling makes transfer_to_agent available to the router.
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("booker")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(booker, chat("helper", says("unused")))
            .build();

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, router))).failingTurn(go());

    // A task sub-agent is a tool, not a transfer target, so the transfer fails and runs nothing.
    assertThat(turn.error()).isInstanceOf(IllegalStateException.class);
    assertThat(turn.error()).hasMessageThat().contains("which runs only as a tool");
    assertThat(bookerModel.getRequests()).isEmpty();
  }

  @Test
  public void singleTurnNodeWhoseTargetPaused_resumesTheTargetWithoutTheRouter() {
    TestLlm routerModel = TestLlm.create(transferTo("approver"));
    LlmAgent approver =
        LlmAgent.builder()
            .name("approver")
            .model(TestLlm.create(askApproval(), createTextLlmResponse("approved")))
            .tools(pendingFunctionTool())
            .build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(routerModel)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(approver)
            .build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, router), new Edge(router, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(approval());

    // The saved transfer lets the router resume into its target, so its model is not called again.
    assertThat(routerModel.getRequests()).hasSize(1);
    assertThat(replyInput(events)).isEqualTo("approved");
  }

  @Test
  public void singleTurnNodeWhoseTargetPausedInANonResumableApp_resumesTheTargetWithoutTheRouter() {
    TestLlm routerModel =
        TestLlm.create(transferTo("approver"), createTextLlmResponse("router answers itself"));
    TestLlm approverModel = TestLlm.create(askApproval(), createTextLlmResponse("approved"));
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(routerModel)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(
                LlmAgent.builder()
                    .name("approver")
                    .model(approverModel)
                    .tools(pendingFunctionTool())
                    .build())
            .build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, router), new Edge(router, captureInput("reply"))),
            /* resumable= */ false);
    session.advance(go());

    ImmutableList<Event> events = session.turn(approval());

    // The router replays its transfer into the target, so its own reply never reaches the next
    // node.
    assertThat(routerModel.getRequests()).hasSize(1);
    assertThat(approverModel.getRequests()).hasSize(2);
    assertThat(replyInput(events)).isEqualTo("approved");
  }

  @Test
  public void singleTurnNodeWhoseTargetPausedOnTwoCallsAnsweredOverTwoTurns_resumesTheTarget() {
    TestLlm routerModel =
        TestLlm.create(transferTo("approver"), createTextLlmResponse("router answers itself"));
    TestLlm approverModel =
        TestLlm.create(
            createLlmResponse(
                Content.builder()
                    .role("model")
                    .parts(
                        askApproval("ask_1").content().get().parts().get().get(0),
                        askApproval("ask_2").content().get().parts().get().get(0))
                    .build()),
            createTextLlmResponse("approved"));
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(routerModel)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(
                LlmAgent.builder()
                    .name("approver")
                    .model(approverModel)
                    .tools(pendingFunctionTool())
                    .build())
            .build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, router), new Edge(router, captureInput("reply"))));
    session.advance(go());
    session.advance(approval());

    ImmutableList<Event> events =
        session.turn(answer("ask_2", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The target gets both answers, and the router's own reply never reaches the next node.
    assertThat(routerModel.getRequests()).hasSize(1);
    assertThat(approverModel.getRequests()).hasSize(2);
    assertThat(approverModel.getLastRequest().contents().toString()).contains("ask_2");
    assertThat(replyInput(events)).isEqualTo("approved");
  }

  @Test
  public void singleTurnNodeWhoseCallbackHandsOffAfterAText_passesOnTheTargetsReply() {
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(says("Transferring you..."))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(chat("billing", says("Billing answer.")))
            .afterModelCallback((callbackContext, response) -> {
              callbackContext.eventActions().setTransferToAgent("billing");
              return Maybe.empty();
            })
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, router), new Edge(router, captureInput("reply"))))
            .turn(go());

    // The router's text carries the handoff, so the target's reply is the node's output.
    assertThat(replyInput(events)).isEqualTo("Billing answer.");
  }

  @Test
  public void chatNodeWhoseCallbackHandsOffWithAToolCall_callsItsModelOnce() {
    TestLlm coordinatorModel =
        TestLlm.create(
            createFunctionCallLlmResponse("e1", "echo_tool", ImmutableMap.of()),
            createTextLlmResponse("unused"));
    TestLlm childModel = says("from child");
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(coordinatorModel)
            .mode(AgentMode.CHAT)
            .tools(new EchoTool())
            .subAgents(chat("child", childModel))
            .afterModelCallback((callbackContext, response) -> {
              callbackContext.eventActions().setTransferToAgent("child");
              return Maybe.empty();
            })
            .build();

    var unused = new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    // The handoff sits on the model's event, not the tool's, and still stops the node's flow.
    assertThat(coordinatorModel.getRequests()).hasSize(1);
    assertThat(childModel.getRequests()).hasSize(1);
  }

  @Test
  public void streamedReplyWhoseCallbackHandsOff_isSavedBeforeTheTargetRuns() {
    TestLlm childModel = says("from child");
    LlmAgent child = chat("child", childModel);
    // The reply streams as two chunks, then a complete event, as SSE does.
    TestLlm coordinatorModel =
        new TestLlm(() ->
            Flowable.just(
                LlmResponse.builder()
                    .content(
                        Content.builder().role("model").parts(Part.fromText("Let me ")).build())
                    .partial(true)
                    .build(),
                LlmResponse.builder()
                    .content(Content.builder().role("model").parts(Part.fromText("check.")).build())
                    .partial(true)
                    .build(),
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            .role("model")
                            .parts(Part.fromText("Let me check."))
                            .build())
                    .build()));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(coordinatorModel)
            .mode(AgentMode.CHAT)
            .subAgents(child)
            .afterModelCallback((callbackContext, response) -> {
              callbackContext.eventActions().setTransferToAgent("child");
              return Maybe.empty();
            })
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator)))
            .withRunConfig(RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build())
            .turn(go());

    // The complete reply is saved, and then the target runs once.
    assertThat(
            events.stream()
                .filter(event -> event.author().equals("coordinator"))
                .filter(event -> !event.partial().orElse(false))
                .map(Event::stringifyContent))
        .contains("Let me check.");
    assertThat(childModel.getRequests()).hasSize(1);
  }

  @Test
  public void replyToATaskBelowAHandoffTarget_reachesTheTask() {
    TestLlm workerModel =
        TestLlm.create(
            createTextLlmResponse("Which day?"),
            createFunctionCallLlmResponse(
                "f1", "finish_task", ImmutableMap.of("result", "Booked.")));
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(workerModel).mode(AgentMode.TASK).build();
    LlmAgent specialist =
        chat(
            "specialist",
            TestLlm.create(
                createFunctionCallLlmResponse("t1", "worker", ImmutableMap.of("request", "Book.")),
                createTextLlmResponse("All booked.")),
            worker);
    LlmAgent coordinator =
        chat("coordinator", TestLlm.create(transferTo("specialist")), specialist);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    ImmutableList<Event> second = session.turn(userText("Friday"));

    // The coordinator replays its handoff, so the specialist resumes and the task gets the reply.
    assertThat(workerModel.getLastRequest().contents()).contains(userText("Friday"));
    assertThat(contentOf("specialist", second).stringifyContent()).isEqualTo("All booked.");
  }

  @Test
  public void failedHandoffTargetOnAWorkflowRetry_runsAgain() {
    OutputAgent child = new OutputAgent("child", "from child", /* failures= */ 1);
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    var unused = session.failingTurn(go());
    String invocationId = session.storedEvents().get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    // The coordinator replays its handoff, so the failed target runs again.
    assertThat(child.runs.get()).isEqualTo(2);
    assertThat(outputAt("wf@1/coordinator@1/child@1", resumed)).isEqualTo("from child");
  }

  @Test
  public void chatNodeHandoffChain_runsEachTargetOnceAsANestedNode() {
    TestLlm leafModel = says("from leaf");
    LlmAgent leaf = chat("leaf", leafModel);
    TestLlm middleModel = TestLlm.create(transferTo("leaf"));
    LlmAgent middle = chat("middle", middleModel, leaf);
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("middle")), middle);

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(middleModel.getRequests()).hasSize(1);
    assertThat(leafModel.getRequests()).hasSize(1);
    assertThat(pathOf(contentOf("leaf", events))).isEqualTo("wf@1/coordinator@1/middle@1/leaf@1");
  }

  @Test
  public void nextMessageAfterAChatNodeHandoff_isAnsweredByTheCoordinator() {
    TestLlm coordinatorModel =
        TestLlm.create(transferTo("child"), createTextLlmResponse("coordinator again"));
    TestLlm childModel = says("from child");
    LlmAgent coordinator = chat("coordinator", coordinatorModel, chat("child", childModel));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    session.advance(Content.builder().role("user").parts(Part.fromText("more")).build());

    // As before handoffs ran as nodes, the next message reaches the coordinator, not the child.
    assertThat(coordinatorModel.getRequests()).hasSize(2);
    assertThat(childModel.getRequests()).hasSize(1);
  }

  @Test
  public void singleTurnNodeHandoff_runsTheTargetInTheNodeWithItsInputAndPassesItsReplyOn() {
    TestLlm billingModel = says("Billing answer.");
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("billing")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(chat("billing", billingModel))
            .build();
    Node summarize =
        new TestNode("summarize", (ctx, input) -> Flowable.just("Card was charged twice."));
    Node next = captureInput("next");

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(
                    new Edge(START, summarize),
                    new Edge(summarize, router),
                    new Edge(router, next)))
            .turn(go());

    // As in Python, the target runs once, inside the router's node, and sees the router's input.
    assertThat(billingModel.getRequests()).hasSize(1);
    assertThat(billingModel.getLastRequest().contents().toString())
        .contains("Card was charged twice.");
    assertThat(outputEventAt(events, "wf@1/next@1").output())
        .hasValue(ImmutableMap.of("received", "Billing answer."));
  }

  @Test
  public void agentInATreeWithoutModes_stillRunsTheAgentItTransfersToInItsOwnNode() {
    LlmAgent child = LlmAgent.builder().name("child").model(says("from child")).build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("child")))
            .subAgents(child)
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(pathOf(contentOf("child", events))).isEqualTo("wf@1/coordinator@1");
  }

  @Test
  public void dynamicHandoff_givesTheCallerTheSubAgentsOutput() {
    OutputAgent child = new OutputAgent("child", "from child");
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);
    Node driver = dispatcher("driver", (ctx, input) -> ctx.runNode(coordinator).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, driver), new Edge(driver, captureInput("reply"))))
            .turn(go());

    assertThat(child.runs.get()).isEqualTo(1);
    assertThat(replyInput(events)).isEqualTo("from child");
    assertThat(outputAt("wf@1/driver@1/coordinator@1/child@1", events)).isEqualTo("from child");
  }

  @Test
  public void sameRunIdAgainAfterAHandoffToAChatAgent_runsNeitherAgain() {
    TestLlm coordinatorModel = TestLlm.create(transferTo("child"));
    TestLlm childModel = says("from child");
    LlmAgent coordinator = chat("coordinator", coordinatorModel, chat("child", childModel));
    RunNodeConfig config = RunNodeConfig.builder().runId("t").build();
    Node driver =
        dispatcher(
            "driver",
            (ctx, input) ->
                ctx.runNode(coordinator, null, config)
                    .isEmpty()
                    .flatMapMaybe(unused -> ctx.runNode(coordinator, null, config))
                    .toFlowable());

    new WorkflowSession(workflow(new Edge(START, driver))).advance(go());

    assertThat(coordinatorModel.getRequests()).hasSize(1);
    assertThat(childModel.getRequests()).hasSize(1);
  }

  @Test
  public void sameRunIdAgainAfterAHandoffTargetFailed_runsTheHandoffAgain() {
    OutputAgent child = new OutputAgent("child", "from child", /* failures= */ 1);
    LlmAgent coordinator =
        chat("coordinator", TestLlm.create(transferTo("child"), transferTo("child")), child);
    RunNodeConfig config = RunNodeConfig.builder().runId("t").build();
    Node driver =
        dispatcher(
            "driver",
            (ctx, input) ->
                ctx.runNode(coordinator, null, config)
                    .onErrorComplete(DynamicNodeFailedException.class::isInstance)
                    .isEmpty()
                    .flatMapMaybe(unused -> ctx.runNode(coordinator, null, config))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    assertThat(child.runs.get()).isEqualTo(2);
    assertThat(outputAt("wf@1/driver@1", events)).isEqualTo("from child");
  }

  @Test
  public void handoffToATaskSubAgent_failsTheWorkflow() {
    TestLlm workerModel = says("unused");
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(workerModel).mode(AgentMode.TASK).build();
    LlmAgent coordinator =
        chat(
            "coordinator",
            TestLlm.create(transferTo("worker")),
            worker,
            chat("helper", says("unused")));

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, coordinator))).failingTurn(go());

    assertThat(turn.error()).isInstanceOf(WorkflowDataException.class);
    assertThat(turn.error()).hasMessageThat().contains("Cannot transfer to 'worker'");
    assertThat(workerModel.getRequests()).isEmpty();
  }

  @Test
  public void nonLlmAgentHandoffToATaskSubAgent_failsTheWorkflow() {
    TestLlm workerModel = says("unused");
    LlmAgent worker =
        LlmAgent.builder().name("worker").model(workerModel).mode(AgentMode.TASK).build();
    HandingOffAgent coordinator = new HandingOffAgent("coordinator", "worker", worker);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, coordinator))).failingTurn(go());

    // No LLM flow checks this handoff, so the workflow rejects it before the target runs.
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error()).hasMessageThat().contains("runs as a tool");
    assertThat(workerModel.getRequests()).isEmpty();
  }

  @Test
  public void handoffToAnAgentWithADottedName_failsBeforeTheTargetRuns() {
    TestLlm deskModel = says("unused");
    LlmAgent coordinator =
        chat(
            "coordinator",
            TestLlm.create(transferTo("billing.desk")),
            chat("billing.desk", deskModel));

    FailedTurn turn = new WorkflowSession(workflow(new Edge(START, coordinator))).failingTurn(go());

    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(deskModel.getRequests()).isEmpty();
  }

  @Test
  public void handoffTargetThatPausedAndWasAnswered_resumesUnderTheAgentThatHandedOff() {
    LlmAgent child = pausingChat("child", "approved");
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    ImmutableList<Event> paused = session.turn(go());

    ImmutableList<Event> events = session.turn(approval());

    assertEndOfAgent(paused, "coordinator");
    Event reply = contentOf("child", events);
    assertThat(reply.stringifyContent()).isEqualTo("approved");
    assertThat(pathOf(reply)).isEqualTo("wf@1/coordinator@1/child@1");
  }

  @Test
  public void handoffTargetPausedOnTwoCallsAnsweredOverTwoTurns_resumesWithoutTheCoordinator() {
    AtomicInteger coordinatorEnds = new AtomicInteger();
    TestLlm coordinatorModel =
        TestLlm.create(transferTo("child"), createTextLlmResponse("coordinator again"));
    TestLlm childModel = asksTwiceThenSays("approved");
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(coordinatorModel)
            .mode(AgentMode.CHAT)
            .subAgents(pausingChat("child", childModel))
            .afterAgentCallbackSync(context -> {
              coordinatorEnds.incrementAndGet();
              return Optional.empty();
            })
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());
    session.advance(approval());

    ImmutableList<Event> events =
        session.turn(answer("ask_2", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(coordinatorModel.getRequests()).hasSize(1);
    assertThat(coordinatorEnds.get()).isEqualTo(1);
    assertThat(childModel.getRequests()).hasSize(2);
    assertThat(contentOf("child", events).stringifyContent()).isEqualTo("approved");
  }

  @Test
  public void handoffTargetThatPausedInANonResumableApp_resumesWithoutTheCoordinator() {
    TestLlm coordinatorModel =
        TestLlm.create(transferTo("child"), createTextLlmResponse("coordinator again"));
    TestLlm childModel = TestLlm.create(askApproval(), createTextLlmResponse("approved"));
    LlmAgent coordinator = chat("coordinator", coordinatorModel, pausingChat("child", childModel));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false);
    session.advance(go());

    ImmutableList<Event> events = session.turn(approval());

    assertThat(coordinatorModel.getRequests()).hasSize(1);
    assertThat(childModel.getRequests()).hasSize(2);
    assertThat(contentOf("child", events).stringifyContent()).isEqualTo("approved");
  }

  @Test
  public void handoffTargetPausedOnTwoCallsInANonResumableApp_resumesWithoutTheCoordinator() {
    TestLlm coordinatorModel =
        TestLlm.create(transferTo("child"), createTextLlmResponse("coordinator again"));
    // Without resumability, the target reruns on each answer, as any agent node does.
    TestLlm childModel = asksTwiceThenSays("waiting", "approved");
    LlmAgent coordinator = chat("coordinator", coordinatorModel, pausingChat("child", childModel));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, coordinator)), /* resumable= */ false);
    session.advance(go());
    session.advance(approval());

    ImmutableList<Event> events =
        session.turn(answer("ask_2", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(coordinatorModel.getRequests()).hasSize(1);
    assertThat(childModel.getRequests()).hasSize(3);
    assertThat(contentOf("child", events).stringifyContent()).isEqualTo("approved");
  }

  @Test
  public void handoffTargetOnASubBranch_resumesOnTheBranchItPausedOn() {
    LlmAgent coordinator =
        chat("coordinator", TestLlm.create(transferTo("child")), pausingChat("child", "approved"));
    LlmAgent other =
        LlmAgent.builder()
            .name("other")
            .model(TestLlm.create(askApproval("other_1")))
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .build();
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, coordinator), new Edge(START, other)));
    ImmutableList<Event> paused = session.turn(go());
    Event ask =
        paused.stream()
            .filter(event -> event.author().equals("child") && !event.functionCalls().isEmpty())
            .collect(onlyElement());

    ImmutableList<Event> events = session.turn(approval());

    Event reply = contentOf("child", events);
    assertThat(pathOf(reply)).isEqualTo("wf@1/coordinator@1/child@1");
    assertThat(ask.branch()).hasValue("coordinator@1.child@1");
    assertThat(reply.branch()).isEqualTo(ask.branch());
  }

  @Test
  public void dynamicHandoffTargetOnASubBranch_resumesOnTheBranchItPausedOn() {
    LlmAgent coordinator =
        chat("coordinator", TestLlm.create(transferTo("child")), pausingChat("child", "approved"));
    RunNodeConfig subBranch = RunNodeConfig.builder().useSubBranch(true).build();
    Node driver =
        dispatcher(
            "driver", (ctx, input) -> ctx.runNode(coordinator, null, subBranch).toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, driver)));
    ImmutableList<Event> paused = session.turn(go());
    Event ask =
        paused.stream()
            .filter(event -> event.author().equals("child") && !event.functionCalls().isEmpty())
            .collect(onlyElement());

    ImmutableList<Event> events = session.turn(approval());

    assertThat(ask.branch()).hasValue("coordinator@1.child@1");
    assertThat(contentOf("child", events).branch()).isEqualTo(ask.branch());
  }

  @Test
  public void dynamicHandoffWithASubBranchAndAnOverrideBranch_keepsTheCoordinatorsSegment() {
    LlmAgent child = chat("child", says("from child"));
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);
    RunNodeConfig config =
        RunNodeConfig.builder().useSubBranch(true).overrideBranch("forkA@1").build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(coordinator, null, config).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    // The target's branch extends the coordinator's, so it sees the coordinator's history.
    assertThat(contentOf("child", events).branch()).hasValue("forkA@1.coordinator@1.child@1");
  }

  @Test
  public void outputAndSubAgentHandoffAsOutput_givesTheDriverTheTargetsOutput() {
    OutputAgent child = new OutputAgent("child", "from child");
    HandingOffAgent coordinator =
        new HandingOffAgent("coordinator", "child", "from coordinator", child);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(coordinator, null, asOutput).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, driver), new Edge(driver, captureInput("reply"))))
            .turn(go());

    // The target's output replaces the one the coordinator already sent for the driver.
    assertThat(replyInput(events)).isEqualTo("from child");
  }

  @Test
  public void terminalNodeHandoffInANestedWorkflow_carriesTheOutputForTheCaller() {
    // A single-turn node runs its transfer target inside the node, so the target is an LLM agent.
    LlmAgent child = LlmAgent.builder().name("child").model(says("from child")).build();
    // A chat agent cannot run in a nested workflow, so this one is single-turn.
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(TestLlm.create(transferTo("child")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(child)
            .build();
    Workflow inner = workflow("inner", new Edge(START, coordinator));
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(inner, null, asOutput).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    // The child's reply is a message-as-output event that is also the caller's output.
    Event reply = contentOf("child", events);
    assertThat(reply.stringifyContent()).isEqualTo("from child");
    assertThat(reply.nodeInfo().orElseThrow().outputFor()).contains("wf@1/driver@1");
  }

  @Test
  public void handoffInANestedWorkflow_carriesTheTargetsOutputForTheCaller() {
    OutputAgent child = new OutputAgent("child", "from child");
    // A non-LLM agent hands off as a chat node would, so the workflow runs its target as a node.
    HandingOffAgent coordinator = new HandingOffAgent("coordinator", "child", child);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);
    Workflow inner = workflow("inner", new Edge(START, coordinator));
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(inner, null, asOutput).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    // The target's output is not the caller's, so the nested workflow sends it once for the caller.
    assertThat(
            events.stream()
                .filter(event -> event.output().isPresent())
                .filter(
                    event -> event.nodeInfo().orElseThrow().outputFor().contains("wf@1/driver@1")))
        .hasSize(1);
    assertThat(outputEventAt(events, "wf@1/driver@1/inner@1").output()).hasValue("from child");
    assertThat(child.runs.get()).isEqualTo(1);
  }

  /**
   * An agent that hands off to {@code target}, as a model's {@code transfer_to_agent} does, with
   * {@code output} on the same event if set.
   */
  private static final class HandingOffAgent extends BaseAgent {
    private final String target;
    private final @Nullable String output;

    HandingOffAgent(String name, String target, BaseAgent... subAgents) {
      this(name, target, /* output= */ null, subAgents);
    }

    HandingOffAgent(String name, String target, @Nullable String output, BaseAgent... subAgents) {
      super(name, "", ImmutableList.copyOf(subAgents), null, null);
      this.target = target;
      this.output = output;
    }

    @Override
    public Flowable<Event> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(
          Event.builder()
              .id(Event.generateEventId())
              .author(name())
              .output(output)
              .actions(EventActions.builder().transferToAgent(target).build())
              .build());
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }

  @Test
  public void peerHandoff_runsThePeerUnderTheCaller() {
    LlmAgent first = chat("first", TestLlm.create(transferTo("second")));
    OutputAgent second = new OutputAgent("second", "from second");
    LlmAgent unusedRoot = chat("root", says("unused"), first, second);
    Node driver = dispatcher("driver", (ctx, input) -> ctx.runNode(first).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, driver), new Edge(driver, captureInput("reply"))))
            .turn(go());

    assertThat(second.runs.get()).isEqualTo(1);
    assertThat(outputAt("wf@1/driver@1/second@1", events)).isEqualTo("from second");
    assertThat(replyInput(events)).isEqualTo("from second");
  }

  @Test
  public void terminalNodePeerHandoff_carriesTheOutputForTheWorkflow() {
    LlmAgent first = chat("first", TestLlm.create(transferTo("second")));
    OutputAgent second = new OutputAgent("second", "from second");
    LlmAgent unusedRoot = chat("root", says("unused"), first, second);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, first))).turn(go());

    assertThat(outputEventAt(events, "wf@1/second@1").nodeInfo().orElseThrow().outputFor())
        .contains("wf@1");
  }

  @Test
  public void parentHandoff_runsTheParentAgainWhereItRanBefore() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent coordinator =
        chat(
            "coordinator",
            TestLlm.create(transferTo("child"), createTextLlmResponse("back again")),
            child);

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    Event answer = contentOf("coordinator", events);
    assertThat(answer.stringifyContent()).isEqualTo("back again");
    assertThat(pathOf(answer)).isEqualTo("wf@1/coordinator@2");
  }

  @Test
  public void handoffToAParentThatNeverRan_runsItAtTheTopOfTheInvocation() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent unusedCoordinator = chat("coordinator", says("coordinator here"), child);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, child))).turn(go());

    assertThat(pathOf(contentOf("coordinator", events))).isEqualTo("coordinator@1");
  }

  @Test
  public void handoffToAParentThatNeverRan_keepsTheCallersBranch() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent unusedCoordinator = chat("coordinator", says("coordinator here"), child);
    Node driver =
        dispatcher(
            "driver",
            (ctx, input) ->
                ctx.runNode(child, null, RunNodeConfig.builder().overrideBranch("b1").build())
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    // As in Python, a top-level hop keeps the branch settings of the run that handed off.
    assertThat(contentOf("coordinator", events).branch()).hasValue("b1");
  }

  @Test
  public void handoffToAParentWithItsOwnParent_runsItAtTheTopWithoutItsTreePath() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent coordinator = chat("coordinator", says("coordinator here"), child);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, child))).turn(go());

    // Python derives "root@1/coordinator@1" from the agent tree; Java runs the hop at the top.
    assertThat(pathOf(contentOf("coordinator", events))).isEqualTo("coordinator@1");
  }

  @Test
  public void topLevelHandoffTargetThatPauses_resumesWhenAnswered() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    TestLlm coordinatorModel =
        TestLlm.create(
            createFunctionCallLlmResponse("ask_1", "pendingTool", ImmutableMap.of("message", "?")),
            createTextLlmResponse("coordinator done"));
    LlmAgent unusedCoordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(coordinatorModel)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .subAgents(child)
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, child)));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("ask_1", ImmutableMap.of("ok", true), "pendingTool"));

    // As in Python, the answer resumes the target that paused.
    assertThat(coordinatorModel.getRequests()).hasSize(2);
    Event reply = contentOf("coordinator", events);
    assertThat(reply.stringifyContent()).isEqualTo("coordinator done");
    assertThat(pathOf(reply)).isEqualTo("coordinator@1");
  }

  @Test
  public void dynamicPeerHandoffAsOutput_givesTheDriverThePeersOutput() {
    LlmAgent first = chat("first", TestLlm.create(transferTo("second")));
    OutputAgent second = new OutputAgent("second", "from second");
    LlmAgent unusedRoot = chat("root", says("unused"), first, second);
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(first, null, asOutput).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, driver), new Edge(driver, captureInput("reply"))))
            .turn(go());

    assertThat(second.runs.get()).isEqualTo(1);
    assertThat(replyInput(events)).isEqualTo("from second");
  }

  @Test
  public void outputAndTopLevelHandoffAsOutput_givesTheDriverTheTargetsOutput() {
    HandingOffAgent child = new HandingOffAgent("child", "coordinator", "from child");
    OutputAgent coordinator = new OutputAgent("coordinator", "from coordinator", child);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(child, null, asOutput).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, driver), new Edge(driver, captureInput("reply"))))
            .turn(go());

    // The target's output replaces the one the child already sent for the driver.
    assertThat(replyInput(events)).isEqualTo("from coordinator");
  }

  @Test
  public void topLevelHandoffNamedLikeTheRootNode_takesTheNextRunId() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent unusedCoordinator = chat("coordinator", says("coordinator here"), child);

    ImmutableList<Event> events =
        new WorkflowSession(workflow("coordinator", new Edge(START, child))).turn(go());

    assertThat(pathOf(contentOf("coordinator", events))).isEqualTo("coordinator@2");
  }

  @Test
  public void parentHandoffBelowANodeNamedLikeTheParent_runsTheParentAtTheTop() {
    HandingOffAgent child = new HandingOffAgent("child", "coordinator");
    OutputAgent coordinator = new OutputAgent("coordinator", "from coordinator", child);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("coordinator", (ctx, input) -> ctx.runNode(child, null, asOutput).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, driver), new Edge(driver, captureInput("reply"))))
            .turn(go());

    // Only an earlier run of the parent agent itself places the parent's next run.
    assertThat(outputAt("coordinator@1", events)).isEqualTo("from coordinator");
    assertThat(replyInput(events)).isEqualTo("from coordinator");
  }

  @Test
  public void topLevelHandoffThatFailedOnAWorkflowRetry_sendsEachOutputOnce() {
    HandingOffAgent child = new HandingOffAgent("child", "coordinator");
    OutputAgent coordinator =
        new OutputAgent("coordinator", "from coordinator", /* failures= */ 1, child);
    LlmAgent unusedRoot = chat("root", says("unused"), coordinator);
    Node driver = dispatcher("driver", (ctx, input) -> ctx.runNode(child).toFlowable());

    ImmutableList<Event> events =
        new WorkflowSession(retriedWorkflow(new Edge(START, driver))).turn(go());

    // The target sends its own output and the driver sends the result it returned, once each.
    assertThat(coordinator.runs.get()).isEqualTo(2);
    assertThat(
            events.stream().filter(event -> event.output().isPresent()).map(event -> pathOf(event)))
        .containsExactly("coordinator@1", "wf@1/driver@1");
  }

  @Test
  public void topLevelHandoffThatHandsOffAgain_runsItsTargetOnce() {
    LlmAgent child =
        chat(
            "child",
            TestLlm.create(
                transferTo("coordinator"),
                createTextLlmResponse("child again"),
                createTextLlmResponse("child again")));
    LlmAgent unusedCoordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, child))).turn(go());

    assertThat(pathOf(contentOf("child", events))).isEqualTo("coordinator@1/child@1");
  }

  @Test
  public void peerHandoffAfterASubAgentHandoff_runsThePeerUnderTheSameAgent() {
    OutputAgent peer = new OutputAgent("peer", "from peer");
    LlmAgent coordinator =
        chat(
            "coordinator",
            TestLlm.create(transferTo("helper")),
            chat("helper", TestLlm.create(transferTo("peer"))),
            peer);

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    assertThat(outputAt("wf@1/coordinator@1/peer@1", events)).isEqualTo("from peer");
  }

  @Test
  public void taskNodeWithAnInvalidHandoff_endsItsTaskSoLaterMessagesStartFresh() {
    LlmAgent booker =
        LlmAgent.builder()
            .name("booker")
            .model(TestLlm.create(transferTo("nobody")))
            .mode(AgentMode.TASK)
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), booker);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, booker)));
    Throwable error = session.failingTurn(go()).error();

    var unused = session.failingTurn(userText("again")).error();

    // The failed handoff ended the task, so the next message does not join its invocation.
    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(session.invocationIds()).hasSize(2);
  }

  @Test
  public void taskNodeHandoffToAPeer_runsThePeerInTheTasksScope() {
    TestLlm peerModel = says("from peer");
    LlmAgent peer = chat("peer", peerModel);
    LlmAgent booker =
        LlmAgent.builder()
            .name("booker")
            .model(TestLlm.create(transferTo("peer")))
            .mode(AgentMode.TASK)
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), booker, peer);

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, booker))).turn(go());

    // As in Python, the peer works in the task's scope, so it sees the task's history.
    assertThat(contentOf("peer", events).isolationScope()).hasValue("wf@1/booker@1");
  }

  @Test
  public void peerOfATaskNodeWithALookalikeFinishTask_leavesTheTaskOpen() {
    BaseTool lookalike =
        new BaseTool("finish_task", "Looks like finish_task.") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(FunctionDeclaration.builder().name(name()).build());
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(ImmutableMap.of("result", "Task completed."));
          }
        };
    LlmAgent peer =
        LlmAgent.builder()
            .name("peer")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse("f1", "finish_task", ImmutableMap.of()),
                    createTextLlmResponse("Which day?")))
            .tools(lookalike)
            .build();
    LlmAgent booker =
        LlmAgent.builder()
            .name("booker")
            .model(TestLlm.create(transferTo("peer")))
            .mode(AgentMode.TASK)
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), booker, peer);
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, booker)));

    session.advance(go());

    // The peer runs in the task's scope, but only the task's own finish_task ends it.
    assertThat(session.storedEvents().stream().filter(LlmAgent::finishesTask)).isEmpty();
  }

  @Test
  public void topLevelHopThatHandsBack_takesTheNextRunIdBesideTheRoot() {
    // child hands off to its parent at the top; the parent hands back, and child hands up again.
    LlmAgent child =
        chat("child", TestLlm.create(transferTo("coordinator"), transferTo("coordinator")));
    LlmAgent unusedCoordinator =
        chat(
            "coordinator",
            TestLlm.create(transferTo("child"), createTextLlmResponse("coordinator done")),
            child);

    ImmutableList<Event> events =
        new WorkflowSession(workflow("coordinator", new Edge(START, child))).turn(go());

    // The root counts as coordinator@1 and each top-level hop takes the next run ID beside it.
    assertThat(pathOf(contentOf("coordinator", events))).isEqualTo("coordinator@3");
  }

  @Test
  public void finishedTopLevelHandoffTarget_isNotSentAgainOnEachLaterTurn() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    TestLlm coordinatorModel = says("coordinator done");
    LlmAgent unusedCoordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(coordinatorModel)
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(child)
            .build();
    Node ask = longRunningAsk("ask", "ask_1");
    Node join = captureInput("reply");
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, child),
                new Edge(START, ask),
                new Edge(child, join),
                new Edge(ask, join)));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    // The target's output was sent in the first turn, so the resume does not store it again.
    assertThat(
            events.stream()
                .filter(event -> pathOf(event).equals("coordinator@1"))
                .filter(event -> event.output().isPresent()))
        .isEmpty();
    assertThat(coordinatorModel.getRequests()).hasSize(1);
  }

  @Test
  public void handoffToAParentThatTheFlagsForbid_failsTheWorkflow() {
    LlmAgent child =
        LlmAgent.builder()
            .name("child")
            .model(TestLlm.create(transferTo("root")))
            .mode(AgentMode.CHAT)
            .disallowTransferToParent(true)
            .build();
    // A peer keeps the transfer tool offered, so the model can name the parent anyway.
    LlmAgent unusedRoot = chat("root", says("unused"), child, chat("peer", says("unused")));

    Throwable error = handoffError(child);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error).hasMessageThat().contains("disallowTransferToParent is set");
  }

  @Test
  public void handoffToAPeerThatTheFlagsForbid_failsTheWorkflow() {
    LlmAgent child =
        LlmAgent.builder()
            .name("child")
            .model(TestLlm.create(transferTo("peer")))
            .mode(AgentMode.CHAT)
            .disallowTransferToPeers(true)
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), child, chat("peer", says("unused")));

    Throwable error = handoffError(child);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error).hasMessageThat().contains("disallowTransferToPeers is set");
  }

  @Test
  public void handoffToATaskPeer_failsWithoutRunningThePeer() {
    TestLlm bookerModel = says("unused");
    LlmAgent booker =
        LlmAgent.builder().name("booker").model(bookerModel).mode(AgentMode.TASK).build();
    // A callback names the peer, since a task peer is not offered as a transfer target.
    LlmAgent child =
        LlmAgent.builder()
            .name("child")
            .model(says("handing off"))
            .mode(AgentMode.CHAT)
            .afterModelCallback((callbackContext, response) -> {
              callbackContext.eventActions().setTransferToAgent("booker");
              return Maybe.empty();
            })
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), child, booker);

    Throwable error = handoffError(child);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error).hasMessageThat().contains("a task or single-turn agent runs as a tool");
    assertThat(bookerModel.getRequests()).isEmpty();
  }

  @Test
  public void handoffToAParentThatNeverRan_keepsTheCallersSubBranchAndScope() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent unusedCoordinator = chat("coordinator", says("coordinator here"), child);
    Node driver =
        dispatcher(
            "driver",
            (ctx, input) ->
                ctx.runNode(
                        child,
                        null,
                        RunNodeConfig.builder()
                            .useSubBranch(true)
                            .overrideIsolationScope("scope1")
                            .build())
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    // A top-level hop keeps the caller's settings and branches from the run that handed off.
    Event reply = contentOf("coordinator", events);
    assertThat(reply.branch()).hasValue("child@1.coordinator@1");
    assertThat(reply.isolationScope()).hasValue("scope1");
  }

  @Test
  public void replayedHandoffs_keepTheCallersScopeForTheNextHop() {
    LlmAgent first = chat("first", TestLlm.create(transferTo("second")));
    LlmAgent second = chat("second", TestLlm.create(transferTo("third")));
    LlmAgent third = chat("third", says("third here"));
    LlmAgent unusedRoot = chat("root", says("unused"), first, second, third);
    Node driver =
        dispatcher(
            "driver",
            (ctx, input) ->
                ctx.runNode(
                        first, null, RunNodeConfig.builder().overrideIsolationScope("s1").build())
                    .toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, driver)));
    var unused =
        session.turnUntil(
            go(),
            event ->
                event.author().equals("second") && event.actions().transferToAgent().isPresent());
    String invocationId = session.storedEvents().get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    // The first two hops replay in the scope they recorded, so the last one runs there too.
    assertThat(contentOf("third", resumed).isolationScope()).hasValue("s1");
  }

  @Test
  public void dynamicPeerHandoffWithAnOverrideBranch_runsThePeerOnTheCallersBranch() {
    LlmAgent first = chat("first", TestLlm.create(transferTo("second")));
    LlmAgent second = chat("second", says("from second"));
    LlmAgent unusedRoot = chat("root", says("unused"), first, second);
    RunNodeConfig config =
        RunNodeConfig.builder().useSubBranch(true).overrideBranch("forkA@1").build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(first, null, config).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    // The peer branches from the agent that handed off, which ran on the caller's override.
    assertThat(contentOf("second", events).branch()).hasValue("forkA@1.first@1.second@1");
  }

  @Test
  public void handoffToItself_failsTheWorkflow() {
    LlmAgent leaf = chat("leaf", TestLlm.create(transferTo("leaf")));
    LlmAgent unusedRoot = chat("root", says("unused"), leaf);

    Throwable error = handoffError(leaf);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error).hasMessageThat().isEqualTo("Agent 'leaf' cannot transfer to itself.");
  }

  @Test
  public void handoffToAnUnknownAgent_failsWithoutNamingIt() {
    LlmAgent leaf = chat("leaf", TestLlm.create(transferTo("nobody")));
    LlmAgent unusedRoot = chat("root", says("unused"), leaf);

    Throwable error = handoffError(leaf);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Agent 'leaf' requested a transfer to an agent that is not in the agent tree.");
  }

  @Test
  public void handoffToAnUnrelatedAgent_failsAndListsTheAgents() {
    LlmAgent leaf = chat("leaf", TestLlm.create(transferTo("cousin")));
    LlmAgent left = chat("left", says("unused"), leaf);
    LlmAgent right = chat("right", says("unused"), chat("cousin", says("unused")));
    LlmAgent unusedRoot = chat("root", says("unused"), left, right);

    Throwable error = handoffError(leaf);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "Cannot transfer from 'leaf' to unrelated agent 'cousin'.\n"
                + "Available agents: root, left, leaf, right, cousin");
  }

  @Test
  public void delegatedTaskHandoffToAPeer_letsThePeerSeeTheTasksHistory() {
    TestLlm peerModel = says("I see the flight");
    LlmAgent booker =
        LlmAgent.builder()
            .name("booker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse("c1", "lookup", ImmutableMap.of()),
                    transferTo("peer")))
            .mode(AgentMode.TASK)
            .tools(lookupTool())
            .build();
    LlmAgent coordinator =
        chat(
            "coordinator",
            TestLlm.create(
                createFunctionCallLlmResponse("t1", "booker", ImmutableMap.of("request", "book")),
                createTextLlmResponse("done")),
            booker,
            chat("peer", peerModel));

    var unused = new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    // The task runs on a sub-branch, which the peer branches from rather than beside.
    assertThat(peerModel.getLastRequest().contents().toString()).contains("LH123");
  }

  @Test
  public void fannedOutPeerHandoff_letsThePeerSeeTheHistoryOfTheAgentThatHandedOff() {
    TestLlm peerModel = says("I see it");
    LlmAgent first =
        LlmAgent.builder()
            .name("first")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse("c1", "lookup", ImmutableMap.of()),
                    transferTo("peer")))
            .mode(AgentMode.CHAT)
            .tools(lookupTool())
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), first, chat("peer", peerModel));

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, first), new Edge(START, TestNode.of("other"))))
            .turn(go());

    assertThat(peerModel.getLastRequest().contents().toString()).contains("LH123");
    assertThat(contentOf("peer", events).branch()).hasValue("first@1.peer@1");
  }

  @Test
  public void fannedOutParentRoundTrip_letsTheParentSeeTheChildsHistory() {
    LlmAgent child =
        LlmAgent.builder()
            .name("child")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse("c1", "lookup", ImmutableMap.of()),
                    transferTo("coordinator")))
            .mode(AgentMode.CHAT)
            .tools(lookupTool())
            .build();
    TestLlm coordinatorModel =
        TestLlm.create(transferTo("child"), createTextLlmResponse("back again"));
    LlmAgent coordinator = chat("coordinator", coordinatorModel, child);

    var unused =
        new WorkflowSession(
                workflow(new Edge(START, coordinator), new Edge(START, TestNode.of("other"))))
            .turn(go());

    assertThat(coordinatorModel.getLastRequest().contents().toString()).contains("LH123");
  }

  @Test
  public void delegatedTaskHandoffToItsParent_callsTheParentsModelInsteadOfTheTaskAgain() {
    TestLlm bookerModel =
        TestLlm.create(transferTo("coordinator"), createTextLlmResponse("booker again"));
    LlmAgent booker =
        LlmAgent.builder().name("booker").model(bookerModel).mode(AgentMode.TASK).build();
    TestLlm coordinatorModel =
        TestLlm.create(
            createFunctionCallLlmResponse("t1", "booker", ImmutableMap.of("request", "book")),
            createTextLlmResponse("coordinator took over"));
    LlmAgent coordinator = chat("coordinator", coordinatorModel, booker);

    var unused = new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    // The delegation belongs to the coordinator run that is still waiting on it.
    assertThat(bookerModel.getRequests()).hasSize(1);
    assertThat(coordinatorModel.getRequests()).hasSize(2);
  }

  @Test
  public void topLevelHopThatRoutedEarlier_doesNotResendItsRouteOnResume() {
    HandingOffAgent child = new HandingOffAgent("child", "router");
    ScriptedAgent unusedRouter =
        new ScriptedAgent(
            "router",
            /* rerunOnResume= */ false,
            (context, run) ->
                Flowable.just(
                    Event.builder()
                        .id(Event.generateEventId())
                        .author("router")
                        .actions(
                            EventActions.builder()
                                .route(ImmutableList.of(new Route.Tag("go_route")))
                                .build())
                        .build()),
            child,
            modeMarker());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, child), new Edge(START, longRunningAsk("ask", "ask_1"))));
    session.advance(go());

    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(
            resumed.stream()
                .filter(event -> pathOf(event).equals("router@1"))
                .filter(event -> event.actions().route().isPresent()))
        .isEmpty();
  }

  @Test
  public void answeredTopLevelHopThatDoesNotRerun_sendsTheAnswerAsItsOutput() {
    HandingOffAgent child = new HandingOffAgent("child", "asker");
    ScriptedAgent unusedAsker =
        new ScriptedAgent(
            "asker",
            /* rerunOnResume= */ false,
            (context, run) -> Flowable.just(WorkflowResumeTesting.interrupt("ask_1")),
            child,
            modeMarker());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, child)));
    session.advance(go());

    ImmutableList<Event> resumed = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    // No earlier event carried the answer, so the resume sends it.
    assertThat(outputAt("asker@1", resumed)).isEqualTo(ImmutableMap.of("ok", true));
  }

  @Test
  public void topLevelHopThatFailedThroughAChildOnAWorkflowRetry_runsAgain() {
    AtomicInteger flakyRuns = new AtomicInteger();
    Node flaky =
        new TestNode(
            "flaky",
            (ctx, input) ->
                flakyRuns.incrementAndGet() == 1
                    ? Flowable.error(new IllegalStateException("not yet"))
                    : Flowable.just("ok"));
    HandingOffAgent child = new HandingOffAgent("child", "coordinator");
    ScriptedAgent coordinator =
        new ScriptedAgent(
            "coordinator",
            /* rerunOnResume= */ true,
            (context, run) ->
                Flowable.just(
                        Event.builder()
                            .id(Event.generateEventId())
                            .author("coordinator")
                            .output("out-" + run)
                            .build())
                    .concatWith(context.runNode(flaky).ignoreElement().toFlowable()),
            child,
            modeMarker());

    var unused = new WorkflowSession(retriedWorkflow(new Edge(START, child))).turn(go());

    // The failure came from a child, so no error event of its own shows that the hop failed.
    assertThat(coordinator.runs.get()).isEqualTo(2);
  }

  @Test
  public void topLevelHopCanceledByAFailingSiblingOnAWorkflowRetry_runsAgain() {
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger siblingRuns = new AtomicInteger();
    HandingOffAgent child = new HandingOffAgent("child", "coordinator");
    ScriptedAgent coordinator =
        new ScriptedAgent(
            "coordinator",
            /* rerunOnResume= */ true,
            (context, run) ->
                run == 1
                    ? Flowable.just(textEvent("coordinator", "working"))
                        .concatWith(
                            Flowable.<Event>never().doOnSubscribe(unused -> started.countDown()))
                    : Flowable.just(
                        Event.builder()
                            .id(Event.generateEventId())
                            .author("coordinator")
                            .output("finished on retry")
                            .build()),
            child,
            modeMarker());
    Node sibling =
        new TestNode(
            "sibling",
            (ctx, input) -> {
              if (siblingRuns.incrementAndGet() > 1) {
                return Flowable.empty();
              }
              return Flowable.defer(() -> {
                started.await(5, SECONDS);
                return Flowable.error(new IllegalStateException("sibling failed"));
              });
            });

    var unused =
        new WorkflowSession(retriedWorkflow(new Edge(START, child), new Edge(START, sibling)))
            .turn(go());

    assertThat(coordinator.runs.get()).isEqualTo(2);
  }

  @Test
  public void topLevelHopWithTwoOpenCallsOneAnswered_waitsForTheOther() {
    TestLlm coordinatorModel =
        TestLlm.create(
            createLlmResponse(
                Content.builder()
                    .role("model")
                    .parts(pendingCallPart("p1"), pendingCallPart("p2"))
                    .build()),
            createTextLlmResponse("all done"));
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent unusedCoordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(coordinatorModel)
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .subAgents(child)
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, child)));
    session.advance(go());
    session.advance(answer("p1", ImmutableMap.of("ok", true), "pendingTool"));
    int requestsAfterFirstAnswer = coordinatorModel.getRequests().size();

    ImmutableList<Event> events =
        session.turn(answer("p2", ImmutableMap.of("ok", true), "pendingTool"));

    assertThat(requestsAfterFirstAnswer).isEqualTo(1);
    assertThat(contentOf("coordinator", events).stringifyContent()).isEqualTo("all done");
  }

  @Test
  public void handoffsAfterATopLevelTaskHop_keepTheTasksScope() {
    LlmAgent child = chat("child", TestLlm.create(transferTo("booker")));
    LlmAgent booker =
        LlmAgent.builder()
            .name("booker")
            .model(TestLlm.create(transferTo("peer")))
            .mode(AgentMode.TASK)
            .subAgents(child)
            .build();
    LlmAgent unusedRoot = chat("root", says("unused"), booker, chat("peer", says("from peer")));

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, child))).turn(go());

    // booker runs at the top in a scope of its own, which the next hop takes on.
    assertThat(contentOf("peer", events).isolationScope()).hasValue("booker@1");
  }

  @Test
  public void handoffToAParentWithAPeerOfTheSameName_runsTheParent() {
    LlmAgent current =
        LlmAgent.builder()
            .name("current")
            .model(TestLlm.create(transferTo("shared")))
            .mode(AgentMode.CHAT)
            .disallowTransferToPeers(true)
            .build();
    TestLlm peerModel = says("from peer");
    TestLlm parentModel = says("from parent");
    LlmAgent unusedParent = chat("shared", parentModel, current, chat("shared", peerModel));

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, current))).turn(go());

    // As in Python, the parent is checked before the peers.
    assertThat(peerModel.getRequests()).isEmpty();
    assertThat(parentModel.getRequests()).hasSize(1);
    assertThat(pathOf(contentOf("shared", events))).isEqualTo("shared@1");
  }

  /** An agent whose node run is {@code body}, given the run's context and its 1-based count. */
  private static final class ScriptedAgent extends BaseAgent {
    final AtomicInteger runs = new AtomicInteger();
    private final boolean rerunOnResume;
    private final BiFunction<Context, Integer, Flowable<Event>> body;

    ScriptedAgent(
        String name,
        boolean rerunOnResume,
        BiFunction<Context, Integer, Flowable<Event>> body,
        BaseAgent... subAgents) {
      super(name, "", ImmutableList.copyOf(subAgents), null, null);
      this.rerunOnResume = rerunOnResume;
      this.body = body;
    }

    @Override
    public boolean rerunOnResume() {
      return rerunOnResume;
    }

    @Override
    public Flowable<Event> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.defer(() -> body.apply(context, runs.incrementAndGet()));
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }

  /** An agent whose node run fails its first {@code failures} times, then outputs {@code value}. */
  private static final class OutputAgent extends BaseAgent {
    final AtomicInteger runs = new AtomicInteger();
    private final String value;
    private final int failures;

    OutputAgent(String name, String value, BaseAgent... subAgents) {
      this(name, value, /* failures= */ 0, subAgents);
    }

    OutputAgent(String name, String value, int failures, BaseAgent... subAgents) {
      super(name, "", ImmutableList.copyOf(subAgents), null, null);
      this.value = value;
      this.failures = failures;
    }

    @Override
    public Flowable<Event> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.fromCallable(() -> {
        if (runs.incrementAndGet() <= failures) {
          throw new IllegalStateException("not yet");
        }
        return Event.builder().id(Event.generateEventId()).author(name()).output(value).build();
      });
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }

  /** Returns a workflow that its retry policy runs twice if it fails. */
  private static Workflow retriedWorkflow(Edge... edges) {
    return Workflow.builder()
        .name("wf")
        .edges(edges)
        .config(
            NodeConfig.builder()
                .retryConfig(
                    RetryConfig.builder().maxAttempts(2).initialDelay(Duration.ZERO).build())
                .build())
        .build();
  }

  /** Returns a chat agent that only gives its parent's tree modes. */
  private static LlmAgent modeMarker() {
    return chat("marker", says("unused"));
  }

  /** Returns a tool that looks up flight LH123. */
  private static BaseTool lookupTool() {
    return new BaseTool("lookup", "Looks up a flight.") {
      @Override
      public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder().name(name()).build());
      }

      @Override
      public Single<Map<String, Object>> runAsync(
          Map<String, Object> args, ToolContext toolContext) {
        return Single.just(ImmutableMap.of("flight", "LH123"));
      }
    };
  }

  private static Part pendingCallPart(String callId) {
    return Part.builder()
        .functionCall(
            FunctionCall.builder()
                .name("pendingTool")
                .id(callId)
                .args(ImmutableMap.of("message", "ok?")))
        .build();
  }

  private static Event textEvent(String author, String text) {
    return Event.builder()
        .id(Event.generateEventId())
        .author(author)
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  /** Returns the failure of a turn of a workflow that runs {@code node} after its start. */
  private static Throwable handoffError(Node node) {
    return new WorkflowSession(workflow(new Edge(START, node))).failingTurn(go()).error();
  }

  private static LlmAgent chat(String name, TestLlm model, BaseAgent... subAgents) {
    return LlmAgent.builder()
        .name(name)
        .model(model)
        .mode(AgentMode.CHAT)
        .subAgents(subAgents)
        .build();
  }

  /** Returns a chat agent that waits on an approval and then says {@code text}. */
  private static LlmAgent pausingChat(String name, String text) {
    return LlmAgent.builder()
        .name(name)
        .model(TestLlm.create(askApproval(), createTextLlmResponse(text)))
        .mode(AgentMode.CHAT)
        .tools(pendingFunctionTool())
        .build();
  }

  /** Returns a chat agent with {@code model} and a long-running tool. */
  private static LlmAgent pausingChat(String name, TestLlm model) {
    return LlmAgent.builder()
        .name(name)
        .model(model)
        .mode(AgentMode.CHAT)
        .tools(pendingFunctionTool())
        .build();
  }

  /** Returns a model that asks for two approvals in one turn and then says {@code texts}. */
  private static TestLlm asksTwiceThenSays(String... texts) {
    ImmutableList.Builder<LlmResponse> responses = ImmutableList.builder();
    responses.add(
        createLlmResponse(
            Content.builder()
                .role("model")
                .parts(
                    askApproval("ask_1").content().get().parts().get().get(0),
                    askApproval("ask_2").content().get().parts().get().get(0))
                .build()));
    for (String text : texts) {
      responses.add(createTextLlmResponse(text));
    }
    return TestLlm.create(responses.build());
  }

  private static TestLlm says(String text) {
    return TestLlm.create(createTextLlmResponse(text));
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  private static LlmResponse transferTo(String agent) {
    return createFunctionCallLlmResponse(
        "to_" + agent, "transfer_to_agent", ImmutableMap.of("agent_name", agent));
  }

  private static LlmResponse askApproval() {
    return askApproval("ask_1");
  }

  private static LlmResponse askApproval(String id) {
    return createFunctionCallLlmResponse(id, "pendingTool", ImmutableMap.of("message", "ok?"));
  }

  private static Content approval() {
    return answer("ask_1", ImmutableMap.of("status", "approved"), "pendingTool");
  }

  /** Returns the only event of {@code author} with text content. */
  private static Event contentOf(String author, List<Event> events) {
    return events.stream()
        .filter(event -> event.author().equals(author) && !event.stringifyContent().isEmpty())
        .filter(event -> event.functionCalls().isEmpty() && event.functionResponses().isEmpty())
        .collect(onlyElement());
  }

  private static Object outputAt(String path, List<Event> events) {
    return outputEventAt(events, path).output().orElseThrow();
  }

  /** Returns an object schema with one string property, {@code answer}. */
  private static Schema answerSchema() {
    return Schema.builder()
        .type("OBJECT")
        .properties(ImmutableMap.of("answer", Schema.builder().type("STRING").build()))
        .build();
  }
}
