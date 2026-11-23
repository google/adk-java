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

package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.adk.testing.TestNode;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.RunNodeConfig;
import com.google.adk.workflow.ToolNodes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Context}. */
@RunWith(JUnit4.class)
public final class ContextTest {

  @Test
  public void toolContext_usedAsCallbackContextOrContext_keepsFunctionCallId() {
    ToolContext toolContext =
        ToolContext.builder(newInvocationContext()).functionCallId("call-1").build();

    // Compile-time guard: code written against CallbackContext keeps accepting a ToolContext.
    CallbackContext callbackContext = toolContext;
    Context context = callbackContext;

    assertThat(context.functionCallId()).hasValue("call-1");
  }

  @Test
  public void callbackContextSubclassOverridingState_recordsWritesInEventActions() {
    LegacyCallbackContext context = new LegacyCallbackContext(newInvocationContext());

    context.state().put("color", "blue");

    assertThat(context.eventActions().stateDelta()).containsExactly("color", "blue");
  }

  @Test
  public void requestConfirmation_onCallbackContext_throwsIllegalStateException() {
    CallbackContext callbackContext = new CallbackContext(newInvocationContext(), null);

    assertThrows(IllegalStateException.class, callbackContext::requestConfirmation);
  }

  @Test
  public void requireNodeState_onCallbackContext_throwsIllegalStateException() {
    CallbackContext context = new CallbackContext(newInvocationContext(), null);

    assertThrows(IllegalStateException.class, context::requireNodeState);
  }

  @Test
  public void nodeMethods_onCallbackContext_throwIllegalStateException() {
    CallbackContext context = new CallbackContext(newInvocationContext(), null);

    assertThrows(IllegalStateException.class, context::eventAuthor);
    assertThrows(IllegalStateException.class, () -> context.eventAuthor("author"));
    assertThrows(IllegalStateException.class, context::output);
    assertThrows(IllegalStateException.class, context::routes);
    assertThrows(IllegalStateException.class, () -> context.routes(ImmutableList.of()));
    assertThrows(IllegalStateException.class, context::attemptCount);
  }

  @Test
  public void runNode_onCallbackContext_failsWithIllegalStateException() {
    CallbackContext context = new CallbackContext(newInvocationContext(), null);

    context.runNode(TestNode.of("child")).test().assertError(IllegalStateException.class);
  }

  @Test
  public void emit_fromAToolContext_throwsNamingTheAlternative() {
    ToolContext tool = ToolContext.builder(newInvocationContext()).build();

    Content message = Content.fromParts(Part.fromText("x"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> tool.emit(message));

    assertThat(error).hasMessageThat().contains("a tool or callback returns its result");
  }

  @Test
  public void runNode_configPassedAsTheInput_throwsIllegalArgumentException() {
    CallbackContext context = new CallbackContext(newInvocationContext(), null);
    Node child = TestNode.of("child");
    RunNodeConfig config = RunNodeConfig.builder().useAsOutput(true).build();

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> context.runNode(child, config));

    assertThat(thrown).hasMessageThat().contains("third argument");
  }

  @Test
  public void searchMemory_onCallbackContext_findsRememberedEvents() {
    InvocationContext invocationContext = newInvocationContext();
    Event rememberedEvent =
        Event.builder()
            .id("e1")
            .author("user")
            .content(Content.fromParts(Part.fromText("My favorite color is teal.")))
            .build();
    InMemoryMemoryService memoryService = new InMemoryMemoryService();
    memoryService
        .addSessionToMemory(
            Session.builder("earlier")
                .appName(invocationContext.appName())
                .userId(invocationContext.userId())
                .events(ImmutableList.of(rememberedEvent))
                .build())
        .blockingAwait();
    CallbackContext callbackContext =
        new CallbackContext(
            invocationContext.toBuilder().memoryService(memoryService).build(), null);

    SearchMemoryResponse response = callbackContext.searchMemory("teal").blockingGet();

    assertThat(response.memories()).hasSize(1);
  }

  @Test
  public void runAsync_functionToolWithContextParameter_receivesTheToolContext() {
    LlmAgent agent =
        createTestAgentBuilder(
                createTestLlm(
                    createFunctionCallLlmResponse(
                        "call-1", "rememberColor", ImmutableMap.of("color", "blue")),
                    createTextLlmResponse("done")))
            .tools(FunctionTool.create(ContextTest.class, "rememberColor"))
            .build();

    Session session = runOnce(agent);

    assertThat(session.state()).containsEntry("color", "blue");
    FunctionResponse functionResponse =
        session.immutableEvents().stream()
            .flatMap(e -> e.functionResponses().stream())
            .findFirst()
            .orElseThrow();
    assertThat(functionResponse.response().orElseThrow()).containsEntry("functionCallId", "call-1");
  }

  @Test
  public void state_outsideAWorkflowNode_viewWritesChangeTheStateAndRecordDelta() {
    InvocationContext invocationContext = newInvocationContext();
    invocationContext.session().state().putAll(ImmutableMap.of("a", 1, "b", 2));
    CallbackContext context = new CallbackContext(invocationContext, /* eventActions= */ null);

    context.state().keySet().remove("a");
    context.state().entrySet().iterator().next().setValue(3);

    assertThat(context.state()).containsExactly("b", 3);
    assertThat(context.eventActions().stateDelta()).containsExactly("a", State.REMOVED, "b", 3);
  }

  @Test
  public void state_outsideAWorkflowNode_clearEmptiesTheStateAndRecordsDelta() {
    InvocationContext invocationContext = newInvocationContext();
    invocationContext.session().state().put("a", 1);
    CallbackContext context = new CallbackContext(invocationContext, /* eventActions= */ null);

    context.state().clear();

    assertThat(context.state()).isEmpty();
    assertThat(context.eventActions().stateDelta()).containsExactly("a", State.REMOVED);
  }

  @Test
  public void state_outsideAWorkflowNode_equalsSessionStateBothWays() {
    InvocationContext invocationContext = newInvocationContext();
    invocationContext.session().state().put("a", 1);
    State contextState = new CallbackContext(invocationContext, /* eventActions= */ null).state();
    Map<String, Object> sessionState = invocationContext.session().state();
    State differentState = new State(ImmutableMap.of("a", 2));

    assertWithMessage("context equals session").that(contextState.equals(sessionState)).isTrue();
    assertWithMessage("session equals context").that(sessionState.equals(contextState)).isTrue();
    assertThat(contextState.hashCode()).isEqualTo(sessionState.hashCode());
    assertWithMessage("context equals different state")
        .that(contextState.equals(differentState))
        .isFalse();
    assertWithMessage("different state equals context")
        .that(differentState.equals(contextState))
        .isFalse();
  }

  @Test
  public void runAsync_agentCallbackTakingContext_persistsItsStateWrite() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("done")))
            .beforeAgentCallback(ContextTest::recordAgentName)
            .build();

    Session session = runOnce(agent);

    assertThat(session.state()).containsEntry("before_agent", agent.name());
  }

  @Test
  public void runAsync_agentCallbackWritingThroughViews_recordsDeltaInEmittedEvent() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("done")))
            .beforeAgentCallback(ContextTest::mutateStateThroughViews)
            .build();
    Runner runner = Runner.builder().agent(agent).appName("test_app").build();

    RunOutcome outcome = runOnce(runner, ImmutableMap.of("drop", 1, "bump", 2, "keep", 3), "hi");

    assertThat(outcome.combinedDelta()).containsExactly("drop", State.REMOVED, "bump", 20);
    assertThat(outcome.savedSession().state()).containsExactly("bump", 20, "keep", 3);
  }

  @Test
  public void runAsync_toolNodeClearingAndRemovingThroughViews_recordsDeltaInNodeEvent() {
    Node toolNode = ToolNodes.asNode(FunctionTool.create(ContextTest.class, "clearAndRemoveState"));
    Runner runner =
        Runner.builder().app(App.builder().name("test_app").rootNode(toolNode).build()).build();

    RunOutcome outcome = runOnce(runner, ImmutableMap.of("a", 1, "b", 2), "{}");

    assertThat(outcome.combinedDelta()).containsExactly("a", State.REMOVED, "b", State.REMOVED);
    assertThat(outcome.savedSession().state()).isEmpty();
  }

  // FunctionTool needs a public method; it injects the context by the parameter name toolContext.
  public static ImmutableMap<String, Object> rememberColor(String color, Context toolContext) {
    toolContext.state().put("color", color);
    return ImmutableMap.of("functionCallId", toolContext.functionCallId().orElse(""));
  }

  public static ImmutableMap<String, Object> clearAndRemoveState(Context toolContext) {
    toolContext.state().keySet().remove("a");
    toolContext.state().clear();
    return ImmutableMap.of("ok", true);
  }

  private static Maybe<Content> mutateStateThroughViews(Context context) {
    context.state().keySet().remove("drop");
    for (Map.Entry<String, Object> entry : context.state().entrySet()) {
      if (entry.getKey().equals("bump")) {
        entry.setValue(20);
      }
    }
    return Maybe.empty();
  }

  private static Maybe<Content> recordAgentName(Context context) {
    context.state().put("before_agent", context.agentName());
    return Maybe.empty();
  }

  private static InvocationContext newInvocationContext() {
    return createInvocationContext(createTestAgent(createTestLlm(createTextLlmResponse("unused"))));
  }

  private static Session runOnce(LlmAgent agent) {
    Runner runner = Runner.builder().agent(agent).appName("test_app").build();
    return runOnce(runner, ImmutableMap.of(), "hi").savedSession();
  }

  private static RunOutcome runOnce(
      Runner runner, Map<String, Object> initialState, String messageText) {
    Session session =
        runner
            .sessionService()
            .createSession(runner.appName(), "user", initialState, /* sessionId= */ null)
            .blockingGet();
    List<Event> events =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText(messageText)))
            .toList()
            .blockingGet();
    Map<String, Object> combinedDelta = new LinkedHashMap<>();
    events.forEach(event -> combinedDelta.putAll(event.actions().stateDelta()));
    Session saved =
        runner
            .sessionService()
            .getSession(runner.appName(), "user", session.id(), Optional.empty())
            .blockingGet();
    return new RunOutcome(combinedDelta, saved);
  }

  private record RunOutcome(Map<String, Object> combinedDelta, Session savedSession) {}

  /** A subclass in the style of code written before Context existed. */
  private static final class LegacyCallbackContext extends CallbackContext {
    LegacyCallbackContext(InvocationContext invocationContext) {
      super(invocationContext, /* eventActions= */ null);
    }

    /** Compile-time guard: this override stops compiling if {@code Context.state()} turns final. */
    @Override
    public State state() {
      return super.state();
    }
  }
}
