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
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.lookalikeFinishTask;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    TestLlm otherModel =
        TestLlm.create(createTextLlmResponse("Other here."), createTextLlmResponse("Other again."));
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

    // The target answered, so the delegation was answered and the reply starts a new turn.
    assertThat(session.invocationIds()).hasSize(2);
    assertThat(model.getLastRequest().contents()).contains(userText("Thanks"));
    assertThat(otherModel.getRequests()).hasSize(1);
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
    TestLlm otherModel =
        TestLlm.create(
            createFunctionCallLlmResponse("f1", "finish_task", ImmutableMap.of()),
            createTextLlmResponse("Which day?"),
            createTextLlmResponse("Tuesday it is."));
    LlmAgent other = LlmAgent.builder().name("other").model(otherModel).tools(lookalike).build();
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

    // The lookalike did not end the task, so the reply continues it in its invocation, where
    // the agent it handed off to answers.
    assertThat(session.invocationIds()).hasSize(1);
    assertThat(otherModel.getLastRequest().contents()).contains(userText("Tuesday"));
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

  @Test
  public void waitingTaskNode_keepsOtherNodesFromStarting() {
    AtomicInteger otherRuns = new AtomicInteger();
    // The gate ends only once the task waits on its question.
    Node gate = gateAfterWait("gate", "task", "Which date?", "G");
    Workflow workflow =
        workflow(
            new Edge(START, askingTask()),
            new Edge(START, gate),
            new Edge(gate, countingNode("other", otherRuns)));

    assertOtherNodeStartsOnlyOnceTheTaskIsAnswered(workflow, /* resumable= */ true, otherRuns);
  }

  @Test
  public void waitingTaskNodeWithoutResumability_keepsOtherNodesFromStarting() {
    AtomicInteger otherRuns = new AtomicInteger();
    // Without checkpoints to wait for, one node at a time queues the other node behind the task.
    Workflow workflow =
        Workflow.builder()
            .name("wf")
            .maxConcurrency(1)
            .edges(new Edge(START, askingTask()), new Edge(START, countingNode("other", otherRuns)))
            .build();

    assertOtherNodeStartsOnlyOnceTheTaskIsAnswered(workflow, /* resumable= */ false, otherRuns);
  }

  private static void assertOtherNodeStartsOnlyOnceTheTaskIsAnswered(
      Workflow workflow, boolean resumable, AtomicInteger otherRuns) {
    WorkflowSession session = new WorkflowSession(workflow, resumable);

    session.advance(go());
    int otherRunsWhileTaskWaited = otherRuns.get();
    session.advance(userText("Tuesday"));

    // As in Python, the other node starts only once the task is answered.
    assertThat(otherRunsWhileTaskWaited).isEqualTo(0);
    assertThat(otherRuns.get()).isEqualTo(1);
  }

  @Test
  public void replyToATaskWhileAnotherTaskWaits_reachesItsTaskAsInPython() {
    CountDownLatch t2Started = new CountDownLatch(1);
    TestLlm t1Model =
        TestLlm.create(createTextLlmResponse("T1 asks?"), createTextLlmResponse("T1 again?"));
    // t1 asks only once t2 has started, so both tasks run and ask in turn 1.
    LlmAgent t1 =
        LlmAgent.builder()
            .name("t1")
            .model(t1Model)
            .mode(AgentMode.TASK)
            .beforeModelCallback((callbackContext, request) ->
                Completable.fromAction(() -> awaitOrFail(t2Started))
                    .andThen(Maybe.<LlmResponse>empty()))
            .build();
    TestLlm t2Model = TestLlm.create(createTextLlmResponse("T2 asks?"), finishTask("Booked."));
    // t2 asks only after t1 has, so the reply goes to t2.
    LlmAgent t2 =
        LlmAgent.builder()
            .name("t2")
            .model(t2Model)
            .mode(AgentMode.TASK)
            .beforeModelCallback((callbackContext, request) -> {
              t2Started.countDown();
              return Flowable.interval(5, TimeUnit.MILLISECONDS)
                  .filter(unused ->
                      taskWaitRecorded(
                          callbackContext.invocationContext().session(), "t1", "T1 asks?"))
                  .take(1)
                  .timeout(10, TimeUnit.SECONDS)
                  .ignoreElements()
                  .andThen(Maybe.<LlmResponse>empty());
            })
            .build();
    Node a = emitter("a", "A");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, t1), new Edge(START, a), new Edge(a, t2)));
    session.advance(go());

    session.advance(userText("Tuesday"));
    int t1RequestsAfterFirstReply = t1Model.getRequests().size();
    session.advance(userText("Friday"));

    // As in Python ADK, t2 asked last, so it takes the first reply; the next one goes to t1.
    assertThat(t2Model.getRequests()).hasSize(2);
    assertThat(t2Model.getLastRequest().contents()).contains(userText("Tuesday"));
    assertThat(t1RequestsAfterFirstReply).isEqualTo(1);
    assertThat(t1Model.getLastRequest().contents()).contains(userText("Friday"));
  }

  @Test
  public void answerToANodeBehindAPlainNodeWhileATaskWaits_reachesThatNode() {
    CountDownLatch t2CalledModel = new CountDownLatch(1);
    LlmAgent task =
        LlmAgent.builder()
            .name("task")
            .model(TestLlm.create(createTextLlmResponse("Which date?")))
            .mode(AgentMode.TASK)
            // Calls its model only once t2 has, so t2 starts before the task waits.
            .beforeModelCallback((ctx, req) ->
                Completable.fromAction(() -> awaitOrFail(t2CalledModel))
                    .andThen(Maybe.<LlmResponse>empty()))
            .build();
    TestLlm t2Model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "ask_1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("T2 got it."));
    LlmAgent t2 =
        LlmAgent.builder()
            .name("t2")
            .model(t2Model)
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .beforeModelCallback((ctx, req) -> {
              t2CalledModel.countDown();
              return Maybe.empty();
            })
            .build();
    Node g = emitter("g", "G");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, task), new Edge(START, g), new Edge(g, t2)));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The task only replayed its wait in turn 2, so the answer still reaches t2, as in Python.
    assertThat(t2Model.getRequests()).hasSize(2);
  }

  @Test
  public void answerToAnotherNodeWhileATaskWaits_keepsANestedOpenCallWaiting() {
    LlmAgent task = task("task", TestLlm.create(createTextLlmResponse("Which date?")));
    Node sub = workflow("sub", new Edge(START, longRunningAsk("asker", "ask_1")));
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, task),
                new Edge(START, sub),
                new Edge(START, longRunningAsk("other", "ask_2"))));
    session.advance(go());

    ImmutableList<Event> second = session.turn(answer("ask_2", ImmutableMap.of("ok", true)));

    // Nobody answered ask_1, so the workflow keeps waiting on it rather than finishing.
    assertThat(
            second.stream()
                .anyMatch(event -> event.actions().endOfAgent() && pathOf(event).isEmpty()))
        .isFalse();
  }

  @Test
  public void taskThatRunsAgainAfterReplayingItsWait_holdsNodesQueuedAfterItsNewWait() {
    TestLlm t1Model =
        TestLlm.create(createTextLlmResponse("T1 asks?"), createTextLlmResponse("T1 again?"));
    LlmAgent t1 = task("t1", t1Model);
    Node other = longRunningAsk("other", "ask_1");
    // Waits on t1's new question, as the one it replayed is followed by checkpoints already.
    Node gate2 = gateAfterWait("gate2", "t1", "T1 again?", "G2");
    AtomicInteger heldRuns = new AtomicInteger();
    Node held =
        new TestNode(
            "held",
            (ctx, input) -> {
              heldRuns.incrementAndGet();
              return Flowable.just("H");
            });
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, t1),
                new Edge(START, other),
                new Edge(other, t1),
                new Edge(other, gate2),
                new Edge(gate2, held)));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("ok", true)));

    // t1 replayed its first wait, then ran again and waits, so held stays held.
    assertThat(t1Model.getRequests()).hasSize(2);
    assertThat(heldRuns.get()).isEqualTo(0);
  }

  @Test
  public void answerToANodeBehindAPlainNodeWhileATaskWaitsOnACall_reachesThatNode() {
    CountDownLatch t2CalledModel = new CountDownLatch(1);
    TestLlm taskModel =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "ask_task", "pendingTool", ImmutableMap.of("message", "task approve?")));
    LlmAgent task =
        LlmAgent.builder()
            .name("task")
            .model(taskModel)
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            // Calls its model only once t2 has, so t2 starts before the task waits.
            .beforeModelCallback((ctx, req) ->
                Completable.fromAction(() -> awaitOrFail(t2CalledModel))
                    .andThen(Maybe.<LlmResponse>empty()))
            .build();
    TestLlm t2Model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "ask_1", "pendingTool", ImmutableMap.of("message", "approve?")),
            createTextLlmResponse("T2 got it."));
    LlmAgent t2 =
        LlmAgent.builder()
            .name("t2")
            .model(t2Model)
            .mode(AgentMode.TASK)
            .tools(pendingFunctionTool())
            .beforeModelCallback((ctx, req) -> {
              t2CalledModel.countDown();
              return Maybe.empty();
            })
            .build();
    Node g = emitter("g", "G");
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, task), new Edge(START, g), new Edge(g, t2)));
    session.advance(go());

    session.advance(answer("ask_1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The task only replayed its open call in turn 2, so the answer still reaches t2, as in Python.
    assertThat(t2Model.getRequests()).hasSize(2);
  }

  /** Returns a node that emits {@code output} once {@code task} waits on {@code question}. */
  private static Node gateAfterWait(String name, String task, String question, String output) {
    return new TestNode(
        name,
        (ctx, input) ->
            Flowable.interval(5, TimeUnit.MILLISECONDS)
                .filter(
                    unused -> taskWaitRecorded(ctx.invocationContext().session(), task, question))
                .take(1)
                .map(unused -> output)
                .timeout(10, TimeUnit.SECONDS));
  }

  /**
   * Returns whether a workflow checkpoint in which {@code task} waits follows its last {@code
   * question} in {@code session}.
   */
  private static boolean taskWaitRecorded(Session session, String task, String question) {
    ImmutableList<Event> events = session.immutableEvents();
    int asked = -1;
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).author().equals(task)
          && events.get(i).stringifyContent().equals(question)) {
        asked = i;
      }
    }
    return asked >= 0
        && events.subList(asked + 1, events.size()).stream()
            .anyMatch(event -> isWaiting(event, task));
  }

  /** Returns whether {@code event} is a workflow checkpoint in which {@code node} waits. */
  private static boolean isWaiting(Event event, String node) {
    return event
        .actions()
        .agentState()
        .map(state -> state.get(NodeState.NODES_KEY))
        .filter(Map.class::isInstance)
        .map(nodes -> ((Map<?, ?>) nodes).get(node))
        .filter(Map.class::isInstance)
        .map(state -> ((Map<?, ?>) state).get("status"))
        .filter(
            status -> status instanceof Number code && code.intValue() == NodeStatus.WAITING.code())
        .isPresent();
  }

  /** Waits for {@code latch}, failing fast rather than letting the test go on after a timeout. */
  private static void awaitOrFail(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for the latch.");
    }
  }

  /** Returns a task node that asks once and finishes with the reply. */
  private static LlmAgent askingTask() {
    return task(
        "task", TestLlm.create(createTextLlmResponse("Which date?"), finishTask("Booked.")));
  }

  /** Returns a node that counts its runs in {@code runs} and ends without an output. */
  private static Node countingNode(String name, AtomicInteger runs) {
    return new TestNode(
        name, (ctx, input) -> Flowable.empty().doOnSubscribe(unused -> runs.incrementAndGet()));
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
