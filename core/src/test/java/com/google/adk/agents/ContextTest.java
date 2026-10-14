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
import static org.junit.Assert.assertThrows;

import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
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
  public void runAsync_agentCallbackTakingContext_persistsItsStateWrite() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("done")))
            .beforeAgentCallback(ContextTest::recordAgentName)
            .build();

    Session session = runOnce(agent);

    assertThat(session.state()).containsEntry("before_agent", agent.name());
  }

  // FunctionTool needs a public method; it injects the context by the parameter name toolContext.
  public static ImmutableMap<String, Object> rememberColor(String color, Context toolContext) {
    toolContext.state().put("color", color);
    return ImmutableMap.of("functionCallId", toolContext.functionCallId().orElse(""));
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
    Session session = runner.sessionService().createSession(runner.appName(), "user").blockingGet();
    runner
        .runAsync("user", session.id(), Content.fromParts(Part.fromText("hi")))
        .blockingSubscribe();
    return runner
        .sessionService()
        .getSession(runner.appName(), "user", session.id(), Optional.empty())
        .blockingGet();
  }

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
