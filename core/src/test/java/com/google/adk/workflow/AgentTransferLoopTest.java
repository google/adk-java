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
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
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
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgentTransferLoopTest {

  @Test
  public void chatNodeHandoff_runsTheSubAgentOnceAsAChildNode() {
    LlmAgent child = chat("child", says("from child"));
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, coordinator))).turn(go());

    Event reply = contentOf("child", events);
    assertThat(reply.stringifyContent()).isEqualTo("from child");
    assertThat(pathOf(reply)).isEqualTo("wf@1/coordinator@1/child@1");
  }

  @Test
  public void singleTurnNodeHandoff_endsTheAgentAndRunsTheSubAgentOnceAsAChildNode() {
    TestLlm helperModel = says("from helper");
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .model(TestLlm.create(transferTo("helper")))
            .mode(AgentMode.SINGLE_TURN)
            .subAgents(chat("helper", helperModel))
            .build();

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, router))).turn(go());

    assertEndOfAgent(events, "router");
    assertThat(helperModel.getRequests()).hasSize(1);
    assertThat(pathOf(contentOf("helper", events))).isEqualTo("wf@1/router@1/helper@1");
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

    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error()).hasMessageThat().contains("Cannot transfer to 'worker'");
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
  public void terminalNodeHandoffInANestedWorkflow_carriesTheOutputForTheCaller() {
    OutputAgent child = new OutputAgent("child", "from child");
    LlmAgent coordinator = chat("coordinator", TestLlm.create(transferTo("child")), child);
    Workflow inner = workflow("inner", new Edge(START, coordinator));
    RunNodeConfig asOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node driver =
        dispatcher("driver", (ctx, input) -> ctx.runNode(inner, null, asOutput).toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, driver))).turn(go());

    assertThat(
            events.stream()
                .filter(event -> event.output().isPresent())
                .flatMap(event -> event.nodeInfo().stream())
                .flatMap(info -> info.outputFor().stream()))
        .contains("wf@1/driver@1");
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

  private static TestLlm says(String text) {
    return TestLlm.create(createTextLlmResponse(text));
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
}
