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
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
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
    TestLlm model =
        TestLlm.create(createTextLlmResponse("Which date?"), finishTask("Booked for Tuesday."));
    LlmAgent task = task("task", model);
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, task), new Edge(task, captureInput("after"))));
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
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, coordinator)));
    session.advance(go());

    session.advance(userText("Friday"));

    assertThat(workerModel.getLastRequest().contents()).contains(userText("Friday"));
    assertThat(model.getRequests()).hasSize(2);
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
  public void waitingTaskNode_keepsOtherNodesFromStarting() {
    Node gate = emitter("gate", "G");
    Node other = emitter("other", "O");
    LlmAgent task = task("task", TestLlm.create(createTextLlmResponse("Which date?")));

    ImmutableList<Event> events =
        new WorkflowSession(
                workflow(new Edge(START, task), new Edge(START, gate), new Edge(gate, other)))
            .turn(go());

    assertThat(pathsOf(events)).doesNotContain("wf@1/other@1");
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
