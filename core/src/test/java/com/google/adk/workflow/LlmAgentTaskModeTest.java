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
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.AgentMode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
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
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LlmAgentTaskModeTest {

  @Test
  public void taskNode_givesTheFinishTaskArgsToItsSuccessorAndOutputKey() {
    TestLlm model = TestLlm.create(finishTask(ImmutableMap.of("result", "Booked.")));
    LlmAgent task = task(model).outputKey("booking").build();
    WorkflowSession session = new WorkflowSession(withSuccessor(task));

    ImmutableList<Event> events = session.turn(go());

    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "Booked.")));
    assertThat(stateValues(session.storedEvents(), "booking"))
        .containsExactly(ImmutableMap.of("result", "Booked."));
    assertThat(model.getRequests()).hasSize(1);
    assertThat(String.join("\n", model.getLastRequest().getSystemInstructions()))
        .contains("Do NOT call `finish_task` prematurely.");
  }

  @Test
  public void taskNode_finishTaskWithInvalidArgs_getsAnErrorAndCanRetry() {
    TestLlm model =
        TestLlm.create(
            finishTask(ImmutableMap.of()), finishTask(ImmutableMap.of("result", "Booked.")));
    WorkflowSession session = new WorkflowSession(withSuccessor(task(model).build()));

    ImmutableList<Event> events = session.turn(go());

    assertThat(errorsIn(model.getLastRequest().contents()))
        .containsExactly(
            "Invoking `finish_task()` failed due to validation errors:\n"
                + "finish_task arguments: required property 'result' is missing\n"
                + "You could retry calling this tool, but it is IMPORTANT for you to provide all"
                + " the mandatory parameters with correct types.");
    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "Booked.")));
  }

  @Test
  public void taskNode_answeringInText_waitsWithoutSettingItsOutputKey() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Which date?"));
    LlmAgent task = task(model).outputKey("booking").build();
    WorkflowSession session = new WorkflowSession(withSuccessor(task));

    ImmutableList<Event> events = session.turn(go());

    assertThat(pathsOf(events)).doesNotContain("wf@1/after@1");
    assertThat(stateValues(session.storedEvents(), "booking")).isEmpty();
  }

  @Test
  public void taskNode_runsInAScopeNamedByItsNodePath() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Which date?"));

    ImmutableList<Event> events =
        new WorkflowSession(workflow(new Edge(START, task(model).build()))).turn(go());

    assertThat(scopesAt(events, "wf@1/task@1")).containsExactly(Optional.of("wf@1/task@1"));
  }

  @Test
  public void dynamicTaskChild_runsInItsOwnScopeUnlessTheCallerPicksOne() {
    LlmAgent first = task(TestLlm.create(createTextLlmResponse("A?"))).name("first").build();
    LlmAgent second = task(TestLlm.create(createTextLlmResponse("B?"))).name("second").build();
    Node caller =
        dispatcher(
            "caller",
            (ctx, input) ->
                ctx.runNode(first, input)
                    .ignoreElement()
                    .andThen(
                        ctx.runNode(
                            second,
                            input,
                            RunNodeConfig.builder().overrideIsolationScope("picked").build()))
                    .toFlowable());

    ImmutableList<Event> events = new WorkflowSession(workflow(new Edge(START, caller))).turn(go());

    assertThat(scopesAt(events, "wf@1/caller@1/first@1"))
        .containsExactly(Optional.of("wf@1/caller@1/first@1"));
    assertThat(scopesAt(events, "wf@1/caller@1/second@1")).containsExactly(Optional.of("picked"));
  }

  @Test
  public void taskAgentWithAnOutputSchema_declaresItAndGetsNoResponseSchema() {
    Schema city =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("city", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("city"))
            .build();
    TestLlm model = TestLlm.create(finishTask(ImmutableMap.of("city", "Paris")));
    LlmAgent task = task(model).outputSchema(city).build();

    ImmutableList<Event> events = new WorkflowSession(withSuccessor(task)).turn(go());

    assertThat(finishTaskDeclaration(model).parameters()).hasValue(city);
    assertThat(model.getLastRequest().config().flatMap(config -> config.responseSchema()))
        .isEmpty();
    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("city", "Paris")));
  }

  @Test
  public void taskAgentWithANonObjectOutputSchema_takesItsOutputUnderResult() {
    Schema text = Schema.builder().type("STRING").build();
    TestLlm model = TestLlm.create(finishTask(ImmutableMap.of("result", "Paris")));
    LlmAgent task = task(model).outputSchema(text).build();

    ImmutableList<Event> events = new WorkflowSession(withSuccessor(task)).turn(go());

    assertThat(finishTaskDeclaration(model).parameters().flatMap(Schema::properties))
        .hasValue(ImmutableMap.of("result", text));
    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", "Paris"));
  }

  @Test
  public void taskAgentWithANonObjectOutputSchema_missingResult_getsAnError() {
    TestLlm model =
        TestLlm.create(
            finishTask(ImmutableMap.of()), finishTask(ImmutableMap.of("result", "Paris")));
    LlmAgent task = task(model).outputSchema(Schema.builder().type("STRING").build()).build();

    var unused = new WorkflowSession(withSuccessor(task)).turn(go());

    assertThat(errorsIn(model.getLastRequest().contents()))
        .containsExactly(
            "Invoking `finish_task()` failed due to validation errors:\n"
                + "finish_task args does not contain required result\n"
                + "You could retry calling this tool, but it is IMPORTANT for you to provide all"
                + " the mandatory parameters with correct types.");
  }

  @Test
  public void taskNodeAfterAnotherNode_opensWithThatNodesOutput() {
    TestLlm model = TestLlm.create(finishTask(ImmutableMap.of("result", "Booked.")));
    Node source = emitter("source", "Book for Friday.");
    LlmAgent task = task(model).build();

    var unused =
        new WorkflowSession(workflow(new Edge(START, source), new Edge(source, task))).turn(go());

    assertThat(model.getLastRequest().contents())
        .containsExactly(Content.fromParts(Part.fromText("Book for Friday.")));
  }

  @Test
  public void taskAgentWithANullableOutput_finishingWithNull_endsWithoutAnOutput() {
    Schema nullableText = Schema.builder().type("STRING").nullable(true).build();
    TestLlm model =
        TestLlm.create(
            finishTask(Collections.singletonMap("result", null)), createTextLlmResponse("more"));
    LlmAgent task = task(model).outputSchema(nullableText).outputKey("booking").build();
    WorkflowSession session = new WorkflowSession(withSuccessor(task));

    var unused = session.turn(go());

    assertThat(model.getRequests()).hasSize(1);
    assertThat(stateValues(session.storedEvents(), "booking")).isEmpty();
  }

  @Test
  public void taskNode_twoFinishTaskCallsWithAFailingFirst_doesNotFinishOnTheSecond() {
    Content twoFinishes =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("finish_1")
                            .name("finish_task")
                            .args(ImmutableMap.of()))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("finish_2")
                            .name("finish_task")
                            .args(ImmutableMap.of("result", "Second.")))
                    .build())
            .build();
    TestLlm model =
        TestLlm.create(
            createLlmResponse(twoFinishes), finishTask(ImmutableMap.of("result", "Booked.")));

    ImmutableList<Event> events =
        new WorkflowSession(withSuccessor(task(model).build())).turn(go());

    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "Booked.")));
  }

  @Test
  public void taskNode_transferTargetsSuccessfulFinishTask_doesNotEndTheNodesTask() {
    // The target's own tool answers like a successful finish_task.
    BaseTool lookalike =
        new BaseTool("finish_task", "Looks like finish_task.") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(FunctionDeclaration.builder().name("finish_task").build());
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(ImmutableMap.of("result", "Task completed."));
          }
        };
    LlmAgent other =
        LlmAgent.builder()
            .name("other")
            .model(
                TestLlm.create(
                    finishTask(ImmutableMap.of("result", "Not yours.")),
                    createTextLlmResponse("Done.")))
            .tools(lookalike)
            .build();
    TestLlm model =
        TestLlm.create(
            createFunctionCallLlmResponse(
                "t1", "transfer_to_agent", ImmutableMap.of("agent_name", "other")));
    LlmAgent task = task(model).outputKey("result").subAgents(other).build();

    ImmutableList<Event> events = new WorkflowSession(withSuccessor(task)).turn(go());

    assertThat(pathsOf(events)).doesNotContain("wf@1/after@1");
    assertThat(stateValues(events, "result")).isEmpty();
  }

  @Test
  public void taskAgentOutsideATaskNode_isNotOfferedFinishTaskAndKeepsItsOutputKey() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Booked."));
    LlmAgent task = task(model).outputKey("result").build();
    Runner runner = Runner.builder().app(App.builder().name("app").rootAgent(task).build()).build();
    Session session = runner.sessionService().createSession("app", "user").blockingGet();

    List<Event> events = runner.runAsync("user", session.id(), go()).toList().blockingGet();

    assertThat(model.getLastRequest().tools()).doesNotContainKey("finish_task");
    assertThat(stateValues(events, "result")).containsExactly("Booked.");
  }

  @Test
  public void taskNodeFinishingBesideAWaitingSingleTurnTool_finishesWithThoseArgsOnResume() {
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
    Content finishAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("finish_1")
                            .name("finish_task")
                            .args(ImmutableMap.of("result", "Done.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call_1")
                            .name("helper")
                            .args(ImmutableMap.of("request", "q")))
                    .build())
            .build();
    TestLlm model = TestLlm.create(createLlmResponse(finishAndAsk));
    WorkflowSession session =
        new WorkflowSession(withSuccessor(task(model).subAgents(helper).build()));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("ask_1", ImmutableMap.of("status", "Paris"), "pendingTool"));

    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "Done.")));
    assertThat(model.getRequests()).hasSize(1);
  }

  @Test
  public void taskNodeInANestedWorkflow_failsTheRunBeforeTheOuterSuccessorRuns() {
    TestLlm model = TestLlm.create(createTextLlmResponse("Which day?"));
    Node inner = workflow("inner", new Edge(START, task(model).build()));
    Node after = captureInput("after");

    var failed =
        new WorkflowSession(workflow(new Edge(START, inner), new Edge(inner, after)))
            .failingTurn(go());

    assertThat(failed.error()).hasMessageThat().contains("AgentMode.TASK");
    assertThat(pathsOf(failed.events())).doesNotContain("wf@1/after@1");
  }

  @Test
  public void taskNodeFinishingOnResume_ignoresAnEarlierCallThatReusedTheFinishTaskId() {
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
    Content finishAndAsk =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("finish_1")
                            .name("finish_task")
                            .args(ImmutableMap.of("result", "New.")))
                    .build(),
                Part.builder()
                    .functionCall(
                        FunctionCall.builder()
                            .id("call_1")
                            .name("helper")
                            .args(ImmutableMap.of("request", "q")))
                    .build())
            .build();
    // The first call reuses finish_1 and fails validation; the second one finishes on resume.
    TestLlm model = TestLlm.create(finishTask(ImmutableMap.of()), createLlmResponse(finishAndAsk));
    WorkflowSession session =
        new WorkflowSession(withSuccessor(task(model).subAgents(helper).build()));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(answer("ask_1", ImmutableMap.of("status", "Paris"), "pendingTool"));

    assertThat(outputEventAt(events, "wf@1/after@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("result", "New.")));
  }

  @Test
  public void taskNode_canTransferButIsNotToldTo() {
    TestLlm model = TestLlm.create(finishTask(ImmutableMap.of("result", "Booked.")));
    LlmAgent other = LlmAgent.builder().name("other").model(TestLlm.create("ok")).build();
    LlmAgent task = task(model).subAgents(other).build();

    var unused = new WorkflowSession(withSuccessor(task)).turn(go());

    assertThat(model.getLastRequest().tools().keySet()).contains("transfer_to_agent");
    assertThat(String.join("\n", model.getLastRequest().getSystemInstructions()))
        .doesNotContain("Agent name: other");
  }

  private static LlmAgent.Builder task(TestLlm model) {
    return LlmAgent.builder().name("task").model(model).mode(AgentMode.TASK);
  }

  private static Workflow withSuccessor(LlmAgent task) {
    Node after = captureInput("after");
    return workflow(new Edge(START, task), new Edge(task, after));
  }

  private static LlmResponse finishTask(Map<String, Object> args) {
    return createFunctionCallLlmResponse("finish_1", "finish_task", args);
  }

  private static FunctionDeclaration finishTaskDeclaration(TestLlm model) {
    return model.getLastRequest().config().orElseThrow().tools().orElseThrow().stream()
        .flatMap(tool -> tool.functionDeclarations().orElse(ImmutableList.of()).stream())
        .filter(declaration -> declaration.name().filter("finish_task"::equals).isPresent())
        .findFirst()
        .orElseThrow();
  }

  private static ImmutableList<Object> errorsIn(List<Content> contents) {
    return contents.stream()
        .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
        .flatMap(part -> part.functionResponse().stream())
        .flatMap(response -> response.response().stream())
        .filter(response -> response.containsKey("error"))
        .map(response -> response.get("error"))
        .collect(toImmutableList());
  }

  private static ImmutableList<Object> stateValues(List<Event> events, String key) {
    return events.stream()
        .filter(event -> event.actions().stateDelta().containsKey(key))
        .map(event -> event.actions().stateDelta().get(key))
        .collect(toImmutableList());
  }

  private static ImmutableList<Optional<String>> scopesAt(List<Event> events, String nodePath) {
    return events.stream()
        .filter(event -> pathOf(event).equals(nodePath))
        .map(Event::isolationScope)
        .collect(toImmutableSet())
        .asList();
  }
}
