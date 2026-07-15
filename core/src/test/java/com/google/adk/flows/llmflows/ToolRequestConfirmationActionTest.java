/*
 * Copyright 2025 Google LLC
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

package com.google.adk.flows.llmflows;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ToolRequestConfirmationActionTest {

  private static class ToolRequestConfirmationTool extends BaseTool {
    ToolRequestConfirmationTool() {
      super("request_confirmation_tool", "Requests confirmation.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder()
              .name(name())
              .description(description())
              .parameters(Schema.builder().type("OBJECT").build()) // No parameters needed
              .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      toolContext.requestConfirmation("Please confirm this action");
      return Single.just(ImmutableMap.of());
    }
  }

  private static class NormalTool extends BaseTool {
    NormalTool() {
      super("normal_tool", "Normal tool.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder()
              .name(name())
              .description(description())
              .parameters(Schema.builder().type("OBJECT").build())
              .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.just(ImmutableMap.of("result", "success"));
    }
  }

  @Test
  public void toolRequestConfirmation_generatesConfirmationEvent() {
    Content requestConfirmationCallContent =
        Content.fromParts(Part.fromFunctionCall("request_confirmation_tool", ImmutableMap.of()));
    Content response1 = Content.fromParts(Part.fromText("response1"));
    Content response2 = Content.fromParts(Part.fromText("response2"));

    var testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(requestConfirmationCallContent)),
            Flowable.just(createLlmResponse(response1)),
            Flowable.just(createLlmResponse(response2)));

    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .tools(ImmutableList.of(new ToolRequestConfirmationTool()))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());

    ImmutableList<Event> confirmationEvents =
        runRunner(runner, invocationContext).stream()
            .filter(
                e ->
                    e.functionCalls().stream()
                        .anyMatch(
                            f ->
                                Objects.equals(
                                    f.name().orElse(""),
                                    Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)))
            .collect(toImmutableList());

    assertThat(confirmationEvents).isNotEmpty();

    Event confirmationEvent = confirmationEvents.get(0);
    assertThat(confirmationEvent.id()).isNotNull();

    FunctionCall functionCall = confirmationEvent.functionCalls().get(0);
    assertThat(functionCall.args()).isPresent();

    Map<String, Object> args = functionCall.args().get();
    assertThat(args).containsKey("toolConfirmation");
    assertThat(args).containsKey("originalFunctionCall");
  }

  @Test
  public void normalTool_doesNotGenerateConfirmationEvent() {
    Content normalCallContent =
        Content.fromParts(Part.fromFunctionCall("normal_tool", ImmutableMap.of()));
    Content response1 = Content.fromParts(Part.fromText("response1"));

    var testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(normalCallContent)),
            Flowable.just(createLlmResponse(response1)));

    LlmAgent rootAgent =
        createTestAgentBuilder(testLlm)
            .name("root_agent")
            .tools(ImmutableList.of(new NormalTool()))
            .build();
    InvocationContext invocationContext = createInvocationContext(rootAgent);

    Runner runner = getRunnerAndCreateSession(rootAgent, invocationContext.session());

    ImmutableList<Event> confirmationEvents =
        runRunner(runner, invocationContext).stream()
            .filter(
                e ->
                    e.functionCalls().stream()
                        .anyMatch(
                            f ->
                                Objects.equals(
                                    f.name().orElse(""),
                                    Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)))
            .collect(toImmutableList());

    assertThat(confirmationEvents).isEmpty();
  }

  private Runner getRunnerAndCreateSession(LlmAgent agent, Session session) {
    Runner runner = new InMemoryRunner(agent, session.appName());

    var unused =
        runner
            .sessionService()
            .createSession(session.appName(), session.userId(), session.state(), session.id())
            .blockingGet();

    return runner;
  }

  private List<Event> runRunner(Runner runner, InvocationContext invocationContext) {
    Session session = invocationContext.session();
    return runner
        .runAsync(session.userId(), session.id(), invocationContext.userContent().orElse(null))
        .toList()
        .blockingGet();
  }
}
