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
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.lookalikeFinishTask;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TaskContinuationTest {

  @Test
  public void waitingTaskNode_continuesWithThePlainReplyInItsInvocation() {
    assertWaitingTaskNodeContinuesWithThePlainReply(/* resumable= */ true);
  }

  @Test
  public void waitingTaskNodeWithoutResumability_continuesWithThePlainReplyInItsInvocation() {
    assertWaitingTaskNodeContinuesWithThePlainReply(/* resumable= */ false);
  }

  private static void assertWaitingTaskNodeContinuesWithThePlainReply(boolean resumable) {
    TestLlm model =
        TestLlm.create(createTextLlmResponse("Which date?"), finishTask("Booked for Tuesday."));
    LlmAgent task = task("task", model);
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, task), new Edge(task, captureInput("after"))), resumable);
    ImmutableList<Event> first = session.turn(go());

    ImmutableList<Event> second = session.turn(userText("Tuesday"));

    assertThat(model.getLastRequest().contents()).contains(userText("Tuesday"));
    assertThat(outputEventAt(second, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "Booked for Tuesday.")));
    assertThat(second.get(0).invocationId()).isEqualTo(first.get(0).invocationId());
    Event reply =
        session.storedEvents().stream()
            .filter(event -> event.content().filter(userText("Tuesday")::equals).isPresent())
            .collect(onlyElement());
    assertThat(reply.isolationScope()).hasValue("wf@1/task@1");
  }

  @Test
  public void waitingTaskNode_takesAReplyThatNamesItsInvocation() {
    TestLlm model =
        TestLlm.create(createTextLlmResponse("Which date?"), finishTask("Booked for Tuesday."));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    String invocationId = session.turn(go()).get(0).invocationId();

    var unused = session.resume(invocationId, userText("Tuesday"));

    assertThat(model.getLastRequest().contents()).contains(userText("Tuesday"));
    assertThat(session.invocationIds()).containsExactly(invocationId);
  }

  @Test
  public void afterTheTaskFinishes_aNewMessageStartsAFreshInvocation() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which date?"),
            finishTask("Booked."),
            createTextLlmResponse("What now?"));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    session.advance(go());
    session.advance(userText("Tuesday"));

    session.advance(userText("Something else"));

    assertThat(session.invocationIds()).hasSize(2);
    Event message =
        session.storedEvents().stream()
            .filter(event -> event.content().filter(userText("Something else")::equals).isPresent())
            .collect(onlyElement());
    assertThat(message.isolationScope()).isEmpty();
  }

  @Test
  public void taskNodePausingAgainInALaterInvocation_takesTheNextReplyThere() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which date?"),
            finishTask("Booked."),
            createTextLlmResponse("Which date this time?"),
            finishTask("Booked again."));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    session.advance(go());
    session.advance(userText("Tuesday"));
    ImmutableList<Event> third = session.turn(userText("Book another"));

    ImmutableList<Event> fourth = session.turn(userText("Friday"));

    assertThat(fourth.get(0).invocationId()).isEqualTo(third.get(0).invocationId());
    assertThat(session.invocationIds()).hasSize(2);
  }

  @Test
  public void failedTaskNode_doesNotTakeLaterMessages() {
    LlmAgent task = task("task", TestLlm.create(null, new IllegalStateException("model down")));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task)));
    var unused = session.failingTurn(go());

    var unusedAgain = session.failingTurn(userText("Try again"));

    assertThat(session.invocationIds()).hasSize(2);
  }

  @Test
  public void taskNodeThatWaitsAfterARetriedError_takesTheNextReply() {
    AtomicInteger calls = new AtomicInteger();
    TestLlm model =
        new TestLlm(() ->
            calls.getAndIncrement() == 0
                ? Flowable.error(new IllegalStateException("model down"))
                : Flowable.just(createTextLlmResponse("Which date?")));
    LlmAgent task =
        new LlmAgent(LlmAgent.builder().name("task").model(model).mode(AgentMode.TASK)) {
          @Override
          public NodeConfig config() {
            return NodeConfig.builder()
                .retryConfig(
                    RetryConfig.builder().maxAttempts(2).initialDelay(Duration.ZERO).build())
                .build();
          }
        };
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task)));
    session.advance(go());

    session.advance(userText("Tuesday"));

    assertThat(session.invocationIds()).hasSize(1);
  }

  @Test
  public void taskNodeWhoseModelReturnsAnEmptyResponse_takesTheNextReply() {
    TestLlm model =
        TestLlm.create(
            LlmResponse.create(GenerateContentResponse.builder().build()),
            createTextLlmResponse("Which date?"));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    session.advance(go());

    session.advance(userText("Tuesday"));

    assertThat(session.invocationIds()).hasSize(1);
  }

  @Test
  public void taskNodeWhoseModelReturnsAnErrorCode_takesTheNextReply() {
    TestLlm model =
        TestLlm.create(
            LlmResponse.builder()
                .errorCode(new FinishReason(FinishReason.Known.SAFETY))
                .errorMessage("blocked")
                .build(),
            createTextLlmResponse("Which date?"));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    session.advance(go());

    session.advance(userText("Tuesday"));

    assertThat(session.invocationIds()).hasSize(1);
  }

  @Test
  public void waitingDelegatedTask_continuesWithThePlainReply() {
    assertWaitingDelegatedTaskContinuesWithThePlainReply(/* resumable= */ true);
  }

  @Test
  public void waitingDelegatedTaskWithoutResumability_continuesWithThePlainReply() {
    assertWaitingDelegatedTaskContinuesWithThePlainReply(/* resumable= */ false);
  }

  private static void assertWaitingDelegatedTaskContinuesWithThePlainReply(boolean resumable) {
    TestLlm workerModel =
        TestLlm.create(createTextLlmResponse("Which day?"), finishTask("Booked for Friday."));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("t1", "worker", ImmutableMap.of("request", "Book.")),
            createTextLlmResponse("Done!"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(task("worker", workerModel))
            .build();
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, coordinator)), resumable);
    session.advance(go());

    session.advance(userText("Friday"));

    assertThat(workerModel.getLastRequest().contents()).contains(userText("Friday"));
    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void delegatedTaskThatHandedOff_doesNotTakeLaterMessages() {
    TestLlm otherModel = TestLlm.create(createTextLlmResponse("Other here."));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "x1", "transfer_to_agent", ImmutableMap.of("agent_name", "other"))))
            .mode(AgentMode.TASK)
            .subAgents(
                LlmAgent.builder().name("other").model(otherModel).mode(AgentMode.CHAT).build())
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("t1", "worker", ImmutableMap.of("request", "Help.")),
            createTextLlmResponse("Done!"),
            createTextLlmResponse("Hi again."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(worker)
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    session.advance(userText("Thanks"));

    // The delegation was answered, so the reply starts a new turn for the coordinator.
    assertThat(session.invocationIds()).hasSize(2);
    assertThat(model.getLastRequest().contents()).contains(userText("Thanks"));
  }

  @Test
  public void nodeInACallerPickedScope_doesNotTakeLaterMessages() {
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(
                        emitter("child", "C"),
                        input,
                        RunNodeConfig.builder().overrideIsolationScope("picked").build())
                    .toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(userText("Again"));

    assertThat(session.invocationIds()).hasSize(2);
  }

  @Test
  public void nodeWhoseScopeIsItsRunId_doesNotTakeLaterMessages() {
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(
                        emitter("child", "C"),
                        input,
                        RunNodeConfig.builder().runId("r1").overrideIsolationScope("r1").build())
                    .toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, caller)));
    session.advance(go());

    session.advance(userText("Again"));

    assertThat(session.invocationIds()).hasSize(2);
  }

  @Test
  public void replyToAWaitingTask_doesNotRunASiblingChatAgentAgain() {
    TestLlm chatModel = TestLlm.create(createTextLlmResponse("Hi!"), createTextLlmResponse("x"));
    LlmAgent chat = LlmAgent.builder().name("chat").model(chatModel).mode(AgentMode.CHAT).build();
    LlmAgent task =
        task("task", TestLlm.create(createTextLlmResponse("Which date?"), finishTask("Booked.")));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, task), new Edge(START, chat)));
    session.advance(go());

    session.advance(userText("Tuesday"));

    assertThat(chatModel.getRequests()).hasSize(1);
  }

  @Test
  public void failedDelegatedTask_letsTheCoordinatorAnswerLaterMessages() {
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("t1", "worker", ImmutableMap.of("request", "Book.")),
            createTextLlmResponse("Sorry, try again."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(
                task("worker", TestLlm.create(null, new IllegalStateException("model down"))))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    var unused = session.failingTurn(go());

    session.advance(userText("again"));

    // The failed task ended, so the coordinator answers instead of waiting on it.
    assertThat(model.getRequests()).hasSize(2);
  }

  @Test
  public void coordinatorInALaterInvocation_waitsOnItsTaskThatWaitsInAnEarlierOne() {
    TestLlm workerModel =
        TestLlm.create(createTextLlmResponse("Which day?"), createTextLlmResponse("unused"));
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse("t1", "worker", ImmutableMap.of("request", "Book.")),
            createTextLlmResponse("unused"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(model)
            .mode(AgentMode.CHAT)
            .subAgents(task("worker", workerModel))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    var unused = session.resume("later_invocation", userText("Anything else?"));

    // The coordinator ran in the later invocation, but its task waits in the first one.
    assertThat(session.invocationIds()).contains("later_invocation");
    assertThat(workerModel.getRequests()).hasSize(1);
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void singleTurnToolsLookalikeFinishTask_leavesTheDelegatedTaskWaiting() {
    BaseTool lookalike = lookalikeFinishTask();
    // The helper runs in the task's call-ID scope, but it is not the task.
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse("f1", "finish_task", ImmutableMap.of()),
                    createTextLlmResponse("Helped.")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(lookalike)
            .build();
    TestLlm workerModel =
        TestLlm.create(
            createFunctionCallLlmResponse("h1", "helper", ImmutableMap.of("request", "help")),
            createTextLlmResponse("Which day?"),
            createTextLlmResponse("Which time?"));
    LlmAgent worker =
        LlmAgent.builder()
            .name("worker")
            .model(workerModel)
            .mode(AgentMode.TASK)
            .subAgents(helper)
            .build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "t1", "worker", ImmutableMap.of("request", "Book.")),
                    createTextLlmResponse("Done.")))
            .mode(AgentMode.CHAT)
            .subAgents(worker)
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    session.advance(userText("Tuesday"));

    // The helper's lookalike did not end the task, so the reply continues it.
    assertThat(session.storedEvents().stream().filter(LlmAgent::finishesTask)).isEmpty();
    assertThat(session.invocationIds()).hasSize(1);
    assertThat(workerModel.getLastRequest().contents()).contains(userText("Tuesday"));
  }

  @Test
  public void resumingAnInvocationWhoseDelegatedTaskFailed_runsTheTaskAgain() {
    AtomicInteger workerCalls = new AtomicInteger();
    TestLlm workerModel =
        new TestLlm(() ->
            workerCalls.incrementAndGet() == 1
                ? Flowable.error(new IllegalStateException("model down"))
                : Flowable.just(finishTask("Booked.")));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse(
                        "t1", "worker", ImmutableMap.of("request", "Book.")),
                    createTextLlmResponse("Done.")))
            .mode(AgentMode.CHAT)
            .subAgents(task("worker", workerModel))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    var unused = session.failingTurn(go());
    String invocationId = session.storedEvents().get(0).invocationId();

    ImmutableList<Event> resumed = session.resume(invocationId);

    // Resuming the failed invocation retries the task rather than reporting its failure.
    assertThat(workerCalls.get()).isEqualTo(2);
    assertThat(resumed.stream().map(Event::stringifyContent)).contains("Done.");
  }

  @Test
  public void transferTargetsLookalikeFinishTask_leavesTheTaskNodeWaitingForTheReply() {
    BaseTool lookalike = lookalikeFinishTask();
    LlmAgent other =
        LlmAgent.builder()
            .name("other")
            .model(
                TestLlm.create(
                    createFunctionCallLlmResponse("f1", "finish_task", ImmutableMap.of()),
                    createTextLlmResponse("Which day?")))
            .tools(lookalike)
            .build();
    TestLlm taskModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "x1", "transfer_to_agent", ImmutableMap.of("agent_name", "other")),
            finishTask("Booked."));
    LlmAgent task =
        LlmAgent.builder()
            .name("task")
            .model(taskModel)
            .mode(AgentMode.TASK)
            .subAgents(other)
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task)));
    session.advance(go());

    session.advance(userText("Tuesday"));

    // The lookalike did not end the task, so the reply reruns it in its invocation.
    assertThat(session.invocationIds()).hasSize(1);
    assertThat(taskModel.getRequests()).hasSize(2);
    assertThat(taskModel.getLastRequest().contents()).contains(userText("Tuesday"));
  }

  @Test
  public void userSentFinishTaskResponse_doesNotEndTheWaitingTask() {
    TestLlm model =
        TestLlm.create(
            createTextLlmResponse("Which day?"),
            createTextLlmResponse("That is not a day."),
            createTextLlmResponse("Which time?"));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    session.advance(go());
    session.advance(
        Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .name("finish_task")
                            .response(ImmutableMap.of("result", "Task completed.")))
                    .build())
            .build());

    session.advance(userText("Tuesday"));

    assertThat(session.invocationIds()).hasSize(1);
  }

  @Test
  public void responseWithoutACallId_continuesTheWaitingTask() {
    TestLlm model =
        TestLlm.create(createTextLlmResponse("Which day?"), createTextLlmResponse("Got it."));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, task("task", model))));
    session.advance(go());

    session.advance(
        Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .name("pick_day")
                            .response(ImmutableMap.of("day", "Tuesday")))
                    .build())
            .build());

    // A response that answers no call reaches the task as its reply, but not its model.
    assertThat(model.getRequests()).hasSize(2);
    assertThat(session.invocationIds()).hasSize(1);
    assertThat(
            model.getRequests().get(1).contents().stream()
                .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
                .filter(part -> part.functionResponse().isPresent()))
        .isEmpty();
  }

  @Test
  public void replyToADelegatedTask_reachesItOnceTheCoordinatorsOtherCallIsAnswered() {
    TestLlm workerModel =
        TestLlm.create(createTextLlmResponse("Which day?"), finishTask("Booked."));
    Content callAndDelegate =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("p1")
                            .name("pendingTool")
                            .args(ImmutableMap.of("message", "approve?")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("t1")
                            .name("worker")
                            .args(ImmutableMap.of("request", "Book.")))
                    .build())
            .build();
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(
                TestLlm.create(createLlmResponse(callAndDelegate), createTextLlmResponse("Done.")))
            .mode(AgentMode.CHAT)
            .tools(pendingFunctionTool())
            .subAgents(task("worker", workerModel))
            .build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    var unused = session.turn(userText("Friday"));
    int workerCallsWhileWaiting = workerModel.getRequests().size();
    ImmutableList<Event> answered =
        session.turn(answer("p1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The coordinator still waits on p1, so the task gets the reply once p1 is answered.
    assertThat(workerCallsWhileWaiting).isEqualTo(1);
    assertThat(workerModel.getRequests()).hasSize(2);
    assertThat(workerModel.getLastRequest().contents()).contains(userText("Friday"));
    assertThat(answered.stream().map(Event::stringifyContent)).contains("Done.");
  }

  private static LlmAgent task(String name, TestLlm model) {
    return LlmAgent.builder().name(name).model(model).mode(AgentMode.TASK).build();
  }

  private static LlmResponse finishTask(String result) {
    return createFunctionCallLlmResponse(
        "finish_" + result.hashCode(), "finish_task", ImmutableMap.of("result", result));
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }
}
