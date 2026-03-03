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

import static com.google.adk.flows.llmflows.Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RequestConfirmationLlmRequestProcessorTest {
  private static final String ECHO_TOOL_NAME = "echo_tool";
  private static final String ORIGINAL_FUNCTION_CALL_ID = "original_fc_id";
  private static final ImmutableMap<String, Object> ORIGINAL_FUNCTION_CALL_ARGS =
      ImmutableMap.of("say", "hello");
  private static final String FUNCTION_CALL_ID = "fc_id";
  private static final ImmutableMap<String, Object> ARGS =
      ImmutableMap.of(
          "originalFunctionCall",
          ImmutableMap.of( // original function call as a map
              "id",
              Optional.of("original_fc_id"),
              "name",
              Optional.of(ECHO_TOOL_NAME),
              "args",
              Optional.of(ORIGINAL_FUNCTION_CALL_ARGS)));
  private static final FunctionCall FUNCTION_CALL =
      FunctionCall.builder().id(FUNCTION_CALL_ID).name(ECHO_TOOL_NAME).args(ARGS).build();
  private static final InMemorySessionService sessionService = new InMemorySessionService();

  private static final Event REQUEST_CONFIRMATION_EVENT =
      Event.builder()
          .author("model")
          .content(Content.fromParts(Part.builder().functionCall(FUNCTION_CALL).build()))
          .build();

  private static final Event USER_CONFIRMATION_EVENT =
      Event.builder()
          .author("user")
          .content(
              Content.fromParts(
                  Part.builder()
                      .functionResponse(
                          FunctionResponse.builder()
                              .id(FUNCTION_CALL_ID)
                              .name(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
                              .response(ImmutableMap.of("confirmed", true))
                              .build())
                      .build()))
          .build();

  private static final RequestConfirmationLlmRequestProcessor processor =
      new RequestConfirmationLlmRequestProcessor();

  @Test
  public void runAsync_withConfirmation_callsOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(ImmutableList.of(REQUEST_CONFIRMATION_EVENT, USER_CONFIRMATION_EVENT))
            .build();

    InvocationContext context = buildInvocationContext(agent, session);

    RequestProcessor.RequestProcessingResult result =
        processor.processRequest(context, LlmRequest.builder().build()).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result.events()).hasSize(1);
    Event event = result.events().iterator().next();
    assertThat(event.functionResponses()).hasSize(1);
    FunctionResponse fr = event.functionResponses().get(0);
    assertThat(fr.id()).hasValue(ORIGINAL_FUNCTION_CALL_ID);
    assertThat(fr.name()).hasValue(ECHO_TOOL_NAME);
    assertThat(fr.response()).hasValue(ImmutableMap.of("result", ORIGINAL_FUNCTION_CALL_ARGS));
  }

  @Test
  public void runAsync_withConfirmationAndToolAlreadyCalled_doesNotCallOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Event toolResponseEvent =
        Event.builder()
            .author("model")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id(ORIGINAL_FUNCTION_CALL_ID)
                                .name(ECHO_TOOL_NAME)
                                .response(ImmutableMap.of("result", ORIGINAL_FUNCTION_CALL_ARGS))
                                .build())
                        .build()))
            .build();
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.of(
                    REQUEST_CONFIRMATION_EVENT, USER_CONFIRMATION_EVENT, toolResponseEvent))
            .build();

    InvocationContext context = buildInvocationContext(agent, session);

    RequestProcessor.RequestProcessingResult result =
        processor.processRequest(context, LlmRequest.builder().build()).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void runAsync_noEvents_empty() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(ImmutableList.of()).build();

    assertThat(
            processor
                .processRequest(
                    buildInvocationContext(agent, session), LlmRequest.builder().build())
                .blockingGet()
                .events())
        .isEmpty();
  }

  @Test
  public void runAsync_noUserConfirmationEvent_empty() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id").events(ImmutableList.of(REQUEST_CONFIRMATION_EVENT)).build();

    assertThat(
            processor
                .processRequest(
                    buildInvocationContext(agent, session), LlmRequest.builder().build())
                .blockingGet()
                .events())
        .isEmpty();
  }

  private static InvocationContext buildInvocationContext(LlmAgent agent, Session session) {
    return InvocationContext.builder()
        .pluginManager(new PluginManager())
        .invocationId(InvocationContext.newInvocationContextId())
        .agent(agent)
        .session(session)
        .sessionService(sessionService)
        .build();
  }

  private static LlmAgent createAgentWithEchoTool() {
    Content contentWithFunctionCall =
        Content.fromParts(
            Part.fromText("text"),
            Part.fromFunctionCall(ECHO_TOOL_NAME, ImmutableMap.of("arg", "value")));
    Content unreachableContent = Content.fromParts(Part.fromText("This should never be returned."));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(contentWithFunctionCall), createLlmResponse(unreachableContent));
    return createTestAgentBuilder(testLlm).tools(new EchoTool()).maxSteps(2).build();
  }
}
