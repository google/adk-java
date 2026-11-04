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
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.ResumabilityTestUtils;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.FunctionTool;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests the handoffs that delegated tasks and the agents around them make. */
@RunWith(JUnit4.class)
public final class TaskHandoffTest {

  @Test
  public void turnWithAToolATaskAndATransfer_runsTheTaskThenTransfersWithoutAnotherModelCall() {
    TestLlm workerModel = TestLlm.create(finishTask("f1", "done"));
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    TestLlm rootModel =
        TestLlm.create(
            parallelCalls(
                call("c1", "echoTool", ImmutableMap.of("message", "hi")),
                call("t1", "worker", ImmutableMap.of("request", "do it")),
                transfer("x1", "helper")),
            createTextLlmResponse("unused"));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(rootModel)
            .mode(AgentMode.CHAT)
            .tools(FunctionTool.create(ResumabilityTestUtils.Tools.class, "echoTool"))
            .subAgents(task("worker", workerModel), helper(helperModel))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(coordinator, /* resumable= */ false).turn(go());

    assertThat(withoutFinishTask(responseNames(events)))
        .containsExactly("echoTool", "worker", "transfer_to_agent")
        .inOrder();
    assertThat(lastTextAuthor(events)).isEqualTo("helper");
    assertThat(workerModel.getRequests()).hasSize(1);
    assertThat(rootModel.getRequests()).hasSize(1);
  }

  @Test
  public void transferBlockedByACallbackBesideATask_doesNotHandOff() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    TestLlm rootModel =
        TestLlm.create(
            parallelCalls(
                call("t1", "worker", ImmutableMap.of("request", "do it")),
                transfer("x1", "helper")),
            createTextLlmResponse("Root done."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(rootModel)
            .mode(AgentMode.CHAT)
            .beforeToolCallbackSync((invocationContext, tool, input, toolContext) ->
                tool.name().equals("transfer_to_agent")
                    ? Optional.of(ImmutableMap.of("error", "blocked"))
                    : Optional.empty())
            .subAgents(
                task("worker", TestLlm.create(finishTask("f1", "done"))), helper(helperModel))
            .build();

    ImmutableList<Event> events =
        new WorkflowSession(coordinator, /* resumable= */ false).turn(go());

    assertThat(withoutFinishTask(responseNames(events)))
        .containsExactly("transfer_to_agent", "worker")
        .inOrder();
    assertThat(lastText(events)).isEqualTo("Root done.");
    assertThat(helperModel.getRequests()).isEmpty();
  }

  @Test
  public void transferBesideATaskWhoseCallbackWritesState_keepsTheWrite() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(
                TestLlm.create(
                    parallelCalls(
                        call("t1", "worker", ImmutableMap.of("request", "do it")),
                        transfer("x1", "helper"))))
            .mode(AgentMode.CHAT)
            .beforeToolCallbackSync((invocationContext, tool, input, toolContext) -> {
              if (tool.name().equals("transfer_to_agent")) {
                toolContext.state().put("handed_off", true);
              }
              return Optional.empty();
            })
            .subAgents(
                task("worker", TestLlm.create(finishTask("f1", "done"))), helper(helperModel))
            .build();
    WorkflowSession session = new WorkflowSession(coordinator);

    var unused = session.turn(go());

    assertThat(
            session.storedEvents().stream()
                .anyMatch(event -> event.actions().stateDelta().containsKey("handed_off")))
        .isTrue();
    assertThat(helperModel.getRequests()).hasSize(1);
  }

  @Test
  public void transferThatWasNotOfferedBesideATask_doesNotHandOffInALaterTurn() {
    TestLlm rootModel =
        TestLlm.create(
            parallelCalls(
                call("t1", "worker", ImmutableMap.of("request", "do it")),
                transfer("x1", "nobody")),
            createTextLlmResponse("Root done."),
            createTextLlmResponse("Second."));
    WorkflowSession session =
        new WorkflowSession(
            coordinator(rootModel, task("worker", TestLlm.create(finishTask("f1", "done")))));
    session.advance(go());

    ImmutableList<Event> second = session.turn(userText("again"));

    assertThat(lastText(second)).isEqualTo("Second.");
  }

  @Test
  public void resumableTurnWithATaskAndATransfer_runsEachAgentOnce() {
    TestLlm workerModel = TestLlm.create(finishTask("f1", "done"));
    TestLlm helperModel =
        TestLlm.create(createTextLlmResponse("Helper here."), createTextLlmResponse("Again."));
    LlmAgent coordinator =
        coordinator(
            TestLlm.create(
                parallelCalls(
                    call("t1", "worker", ImmutableMap.of("request", "do it")),
                    transfer("x1", "helper")),
                createTextLlmResponse("unused")),
            task("worker", workerModel),
            helper(helperModel));

    ImmutableList<Event> events = new WorkflowSession(coordinator).turn(go());

    assertThat(workerModel.getRequests()).hasSize(1);
    assertThat(helperModel.getRequests()).hasSize(1);
    // The handoff finishes the coordinator, as one without delegations does.
    assertThat(
            events.stream()
                .anyMatch(
                    event -> event.author().equals("coordinator") && event.actions().endOfAgent()))
        .isTrue();
  }

  @Test
  public void turnWhoseTaskAsksTheUserAndTransfers_endsAtTheQuestion() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent coordinator =
        coordinator(
            TestLlm.create(
                parallelCalls(
                    call("t1", "worker", ImmutableMap.of("request", "book a trip")),
                    transfer("x1", "helper"))),
            task("worker", TestLlm.create(createTextLlmResponse("Which city?"))),
            helper(helperModel));

    ImmutableList<Event> events = new WorkflowSession(coordinator).turn(go());

    assertThat(lastText(events)).isEqualTo("Which city?");
    assertThat(helperModel.getRequests()).isEmpty();
  }

  @Test
  public void taskThatHandsBackBesideItsDelegatorsTransfer_letsTheWaitingRunHandOffOnce() {
    TestLlm helperModel =
        TestLlm.create(createTextLlmResponse("Helper here."), createTextLlmResponse("Again."));
    TestLlm rootModel =
        TestLlm.create(
            parallelCalls(
                call("t1", "worker", ImmutableMap.of("request", "do it")),
                transfer("x1", "helper")),
            createTextLlmResponse("Coordinator back."),
            createTextLlmResponse("unused"));
    LlmAgent coordinator =
        coordinator(
            rootModel,
            task("worker", TestLlm.create(transfer("w1", "coordinator"))),
            helper(helperModel));

    ImmutableList<Event> events = new WorkflowSession(coordinator).turn(go());

    // The coordinator run that the task handed back to does not take the held-back transfer.
    assertThat(responsesTo("x1", events)).hasSize(1);
    assertThat(helperModel.getRequests()).hasSize(1);
  }

  @Test
  public void replyToATaskThatAskedBesideATransfer_finishesTheTaskThenTransfers() {
    assertReplyToATaskThatAskedBesideATransferFinishesTheTaskThenTransfers(/* resumable= */ true);
  }

  @Test
  public void replyToATaskThatAskedBesideATransferWithoutResumability_finishesThenTransfers() {
    assertReplyToATaskThatAskedBesideATransferFinishesTheTaskThenTransfers(/* resumable= */ false);
  }

  private static void assertReplyToATaskThatAskedBesideATransferFinishesTheTaskThenTransfers(
      boolean resumable) {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    TestLlm rootModel =
        TestLlm.create(
            parallelCalls(
                call("t1", "worker", ImmutableMap.of("request", "book a trip")),
                transfer("x1", "helper")),
            createTextLlmResponse("unused"));
    LlmAgent coordinator =
        coordinator(
            rootModel,
            task(
                "worker",
                TestLlm.create(createTextLlmResponse("Which city?"), finishTask("f1", "Paris"))),
            helper(helperModel));
    WorkflowSession session = new WorkflowSession(coordinator, resumable);
    session.advance(go());

    ImmutableList<Event> reply = session.turn(userText("Paris"));

    assertThat(withoutFinishTask(responseNames(reply)))
        .containsExactly("worker", "transfer_to_agent")
        .inOrder();
    assertThat(textsBy(reply, "helper")).containsExactly("Helper here.");
    assertThat(rootModel.getRequests()).hasSize(1);
  }

  @Test
  public void replyToATaskThatAskedBesideATransferInAWorkflow_answersTheTransferAsTheAgent() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent coordinator =
        coordinator(
            TestLlm.create(
                parallelCalls(
                    call("t1", "worker", ImmutableMap.of("request", "book a trip")),
                    transfer("x1", "helper"))),
            task(
                "worker",
                TestLlm.create(createTextLlmResponse("Which city?"), finishTask("f1", "Paris"))),
            helper(helperModel));
    WorkflowSession session =
        new WorkflowSession(workflow("wf", new Edge(WorkflowResumeTesting.START, coordinator)));
    session.advance(go());

    ImmutableList<Event> reply = session.turn(userText("Paris"));

    assertThat(
            reply.stream()
                .filter(event -> !responsesTo("x1", ImmutableList.of(event)).isEmpty())
                .map(Event::author))
        .containsExactly("coordinator");
    assertThat(helperModel.getRequests()).hasSize(1);
  }

  @Test
  public void transferToAnUnknownAgentBesideATaskThatAsked_failsOnTheReplyAsWithoutTheQuestion() {
    TestLlm rootModel =
        TestLlm.create(
            parallelCalls(
                call("t1", "worker", ImmutableMap.of("request", "book a trip")),
                transfer("x1", "nobody")),
            createTextLlmResponse("unused"));
    LlmAgent coordinator =
        coordinator(
            rootModel,
            task(
                "worker",
                TestLlm.create(createTextLlmResponse("Which city?"), finishTask("f1", "Paris"))),
            helper(TestLlm.create(createTextLlmResponse("Helper here."))));
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    Content reply = userText("Paris");

    WorkflowDataException error =
        assertThrows(WorkflowDataException.class, () -> session.turn(reply));

    assertThat(error).hasMessageThat().contains("not in the agent tree");
    assertThat(rootModel.getRequests()).hasSize(1);
  }

  @Test
  public void transferBesideATaskAndALongRunningCall_waitsForTheCallsAnswer() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent coordinator =
        LlmAgent.builder()
            .name("coordinator")
            .model(
                TestLlm.create(
                    parallelCalls(
                        call("t1", "worker", ImmutableMap.of("request", "do it")),
                        call("l1", "pendingTool", ImmutableMap.of("message", "approve?")),
                        transfer("x1", "helper"))))
            .mode(AgentMode.CHAT)
            .tools(ResumabilityTestUtils.pendingFunctionTool())
            .subAgents(
                task("worker", TestLlm.create(finishTask("f1", "done"))), helper(helperModel))
            .build();
    WorkflowSession session = new WorkflowSession(coordinator);

    ImmutableList<Event> first = session.turn(go());
    ImmutableList<Event> second =
        session.turn(answer("l1", ImmutableMap.of("status", "approved"), "pendingTool"));

    assertThat(responsesTo("x1", first)).isEmpty();
    assertThat(responsesTo("x1", second)).hasSize(1);
    assertThat(helperModel.getRequests()).hasSize(1);
  }

  @Test
  public void transferBesideATaskAndASingleTurnAgentThatAsks_waitsForItsAnswer() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent asker =
        LlmAgent.builder()
            .name("asker")
            .description("Asks.")
            .model(
                TestLlm.create(
                    call("ask_1", "pendingTool", ImmutableMap.of("message", "ok?")),
                    createTextLlmResponse("Approved.")))
            .mode(AgentMode.SINGLE_TURN)
            .tools(ResumabilityTestUtils.pendingFunctionTool())
            .build();
    LlmAgent coordinator =
        coordinator(
            TestLlm.create(
                parallelCalls(
                    call("t1", "worker", ImmutableMap.of("request", "do it")),
                    call("s1", "asker", ImmutableMap.of("request", "check")),
                    transfer("x1", "helper"))),
            task("worker", TestLlm.create(finishTask("f1", "done"))),
            asker,
            helper(helperModel));
    WorkflowSession session = new WorkflowSession(coordinator);
    session.advance(go());

    ImmutableList<Event> reply =
        session.turn(answer("ask_1", ImmutableMap.of("status", "approved"), "pendingTool"));

    // The single-turn agent answers its call before the handoff answers the transfer.
    assertThat(withoutFinishTask(responseNames(reply)))
        .containsAtLeast("asker", "transfer_to_agent")
        .inOrder();
    assertThat(helperModel.getRequests()).hasSize(1);
  }

  @Test
  public void taskWhoseInnerTaskAsksBesideATransfer_waitsThenTransfers() {
    TestLlm innerModel = TestLlm.create(createTextLlmResponse("Which one?"), finishTask("f1", "b"));
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    TestLlm outerModel =
        TestLlm.create(
            parallelCalls(
                call("t2", "inner_task", ImmutableMap.of("request", "pick")),
                transfer("x1", "helper")));
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(outerModel)
            .mode(AgentMode.TASK)
            .subAgents(task("inner_task", innerModel), helper(helperModel))
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));
    WorkflowSession session = new WorkflowSession(coordinator(rootModel, outer));

    ImmutableList<Event> first = session.turn(userText("start"));
    int rootRequestsAfterTheQuestion = rootModel.getRequests().size();
    ImmutableList<Event> reply = session.turn(userText("b"));

    assertThat(lastText(first)).isEqualTo("Which one?");
    assertThat(rootRequestsAfterTheQuestion).isEqualTo(1);
    assertThat(withoutFinishTask(responseNames(reply)))
        .containsExactly("inner_task", "transfer_to_agent", "outer_task")
        .inOrder();
    assertThat(helperModel.getRequests()).hasSize(1);
    assertThat(outerModel.getRequests()).hasSize(1);
    assertThat(lastText(reply)).isEqualTo("Root done.");
  }

  @Test
  public void delegatedTaskThatHandsOffToAChatHelper_letsItsDelegatorGoOn() {
    TestLlm helperModel = TestLlm.create(createTextLlmResponse("Helper here."));
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(TestLlm.create(transfer("x1", "helper")))
            .mode(AgentMode.TASK)
            .subAgents(helper(helperModel))
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));

    ImmutableList<Event> events =
        new WorkflowSession(coordinator(rootModel, outer)).turn(userText("start"));

    // The helper gives no output, as a chat agent does, so the delegation answers empty.
    assertThat(textsBy(events, "helper")).containsExactly("Helper here.");
    assertThat(responseNames(events)).contains("outer_task");
    assertThat(lastText(events)).isEqualTo("Root done.");
  }

  @Test
  public void taskHandingOffToAHelperWhoseTaskAsks_endsTheTurnAtTheQuestion() {
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .description("Helps.")
            .model(
                TestLlm.create(
                    call("t2", "inner_task", ImmutableMap.of("request", "pick")),
                    createTextLlmResponse("ok")))
            .subAgents(task("inner_task", TestLlm.create(createTextLlmResponse("Which one?"))))
            .build();
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(TestLlm.create(transfer("x1", "helper")))
            .mode(AgentMode.TASK)
            .subAgents(helper)
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));

    ImmutableList<Event> events =
        new WorkflowSession(coordinator(rootModel, outer)).turn(userText("start"));

    assertThat(lastText(events)).isEqualTo("Which one?");
    assertThat(rootModel.getRequests()).hasSize(1);
  }

  @Test
  public void replyToTheHelpersTask_letsTheDelegatorGoOn() {
    assertReplyToTheHelpersTaskLetsTheDelegatorGoOn(/* resumable= */ true);
  }

  @Test
  public void replyToTheHelpersTaskWithoutResumability_letsTheDelegatorGoOn() {
    assertReplyToTheHelpersTaskLetsTheDelegatorGoOn(/* resumable= */ false);
  }

  private static void assertReplyToTheHelpersTaskLetsTheDelegatorGoOn(boolean resumable) {
    TestLlm innerModel =
        TestLlm.create(
            createTextLlmResponse("Which one?"),
            createFunctionCallLlmResponse(
                "f1", "finish_task", ImmutableMap.of("result", "Picked blue.")));
    LlmAgent helper =
        LlmAgent.builder()
            .name("helper")
            .description("Helps.")
            .model(
                TestLlm.create(
                    call("t2", "inner_task", ImmutableMap.of("request", "pick")),
                    createTextLlmResponse("Helper done.")))
            .subAgents(task("inner_task", innerModel))
            .build();
    LlmAgent outer =
        LlmAgent.builder()
            .name("outer_task")
            .description("Does outer.")
            .model(TestLlm.create(transfer("x1", "helper")))
            .mode(AgentMode.TASK)
            .subAgents(helper)
            .build();
    TestLlm rootModel =
        TestLlm.create(
            call("t1", "outer_task", ImmutableMap.of("request", "go")),
            createTextLlmResponse("Root done."));
    WorkflowSession session = new WorkflowSession(coordinator(rootModel, outer), resumable);
    session.advance(userText("start"));

    ImmutableList<Event> reply = session.turn(userText("Blue"));

    // The inner task finishes and the helper answers, so the root's delegation answers too.
    assertThat(textsBy(reply, "helper")).contains("Helper done.");
    assertThat(lastText(reply)).isEqualTo("Root done.");
    assertThat(rootModel.getRequests()).hasSize(2);
  }

  private static LlmAgent coordinator(TestLlm model, BaseAgent... subAgents) {
    return LlmAgent.builder()
        .name("coordinator")
        .model(model)
        .mode(AgentMode.CHAT)
        .subAgents(subAgents)
        .build();
  }

  private static LlmAgent task(String name, TestLlm model) {
    return LlmAgent.builder()
        .name(name)
        .description("Does " + name + ".")
        .model(model)
        .mode(AgentMode.TASK)
        .build();
  }

  private static LlmAgent helper(TestLlm model) {
    return LlmAgent.builder().name("helper").description("Helps.").model(model).build();
  }

  private static LlmResponse call(String id, String name, Map<String, Object> args) {
    return createFunctionCallLlmResponse(id, name, args);
  }

  private static LlmResponse parallelCalls(LlmResponse... calls) {
    return createLlmResponse(
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.copyOf(calls).stream()
                    .flatMap(call -> call.content().orElseThrow().parts().orElseThrow().stream())
                    .collect(toImmutableList()))
            .build());
  }

  private static LlmResponse transfer(String id, String agentName) {
    return call(id, "transfer_to_agent", ImmutableMap.of("agent_name", agentName));
  }

  private static LlmResponse finishTask(String id, String result) {
    return call(id, "finish_task", ImmutableMap.of("result", result));
  }

  private static ImmutableList<String> withoutFinishTask(List<String> names) {
    return names.stream().filter(name -> !name.equals("finish_task")).collect(toImmutableList());
  }

  private static ImmutableList<FunctionResponse> responsesTo(String id, List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionResponses().stream())
        .filter(response -> response.id().filter(id::equals).isPresent())
        .collect(toImmutableList());
  }

  private static ImmutableList<String> responseNames(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionResponses().stream())
        .flatMap(response -> response.name().stream())
        .collect(toImmutableList());
  }

  private static ImmutableList<Event> withText(List<Event> events) {
    return events.stream()
        .filter(event -> event.functionCalls().isEmpty() && event.functionResponses().isEmpty())
        .filter(event -> !event.stringifyContent().isEmpty())
        .collect(toImmutableList());
  }

  private static String lastText(List<Event> events) {
    ImmutableList<Event> texts = withText(events);
    return texts.isEmpty() ? "" : texts.get(texts.size() - 1).stringifyContent();
  }

  private static String lastTextAuthor(List<Event> events) {
    ImmutableList<Event> texts = withText(events);
    return texts.isEmpty() ? "" : texts.get(texts.size() - 1).author();
  }

  private static ImmutableList<String> textsBy(List<Event> events, String author) {
    return withText(events).stream()
        .filter(event -> event.author().equals(author))
        .map(Event::stringifyContent)
        .collect(toImmutableList());
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }
}
