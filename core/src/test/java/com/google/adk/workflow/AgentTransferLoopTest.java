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
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
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
  public void chatNodeHandoffToItsParent_stillRunsTheParentInline() {
    TestLlm parentModel = says("parent here");
    LlmAgent child = chat("child", TestLlm.create(transferTo("coordinator")));
    LlmAgent unusedCoordinator = chat("coordinator", parentModel, child);

    var unused = new WorkflowSession(workflow(new Edge(START, child))).turn(go());

    // Only a sub-agent handoff runs as a node here, so the parent still runs, inside the node.
    assertThat(parentModel.getRequests()).hasSize(1);
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

    assertThat(turn.error()).isInstanceOf(IllegalStateException.class);
    assertThat(turn.error()).hasMessageThat().contains("which runs only as a tool");
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

  /** An agent whose node run fails its first {@code failures} times, then outputs {@code value}. */
  private static final class OutputAgent extends BaseAgent {
    final AtomicInteger runs = new AtomicInteger();
    private final String value;
    private final int failures;

    OutputAgent(String name, String value) {
      this(name, value, /* failures= */ 0);
    }

    OutputAgent(String name, String value, int failures) {
      super(name, "", ImmutableList.of(), null, null);
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
