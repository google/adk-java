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

import static com.google.adk.testing.TestUtils.createEvent;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createRootAgent;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.ToolExecutionMode;
import com.google.adk.events.Event;
import com.google.adk.testing.TestUtils;
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

/** Unit tests for {@link Functions}. */
@RunWith(JUnit4.class)
public final class FunctionsTest {

  private static final Event EVENT_WITH_NO_CONTENT =
      Event.builder()
          .id("event1")
          .invocationId("invocation1")
          .author("agent")
          .content(Optional.empty())
          .build();

  private static final Event EVENT_WITH_NO_PARTS =
      Event.builder()
          .id("event1")
          .invocationId("invocation1")
          .author("agent")
          .content(Content.builder().role("model").parts(ImmutableList.of()).build())
          .build();

  private static final Event EVENT_WITH_NO_FUNCTION_CALLS =
      Event.builder()
          .id("event1")
          .invocationId("invocation1")
          .author("agent")
          .content(Content.fromParts(Part.fromText("hello")))
          .build();

  private static final Event EVENT_WITH_NON_CONFIRMATION_FUNCTION_CALL =
      Event.builder()
          .id("event1")
          .invocationId("invocation1")
          .author("agent")
          .content(Content.fromParts(Part.fromFunctionCall("other_function", ImmutableMap.of())))
          .build();

  @Test
  public void handleFunctionCalls_noFunctionCalls() {
    InvocationContext invocationContext = createInvocationContext(createRootAgent());
    Event event = createEvent("event");

    Event functionResponseEvent =
        Functions.handleFunctionCalls(invocationContext, event, /* tools= */ ImmutableMap.of())
            .blockingGet();

    assertThat(functionResponseEvent).isNull();
  }

  @Test
  public void handleFunctionCalls_missingTool() {
    InvocationContext invocationContext = createInvocationContext(createRootAgent());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."), Part.fromFunctionCall("missing_tool", ImmutableMap.of())))
            .build();

    assertThrows(
        RuntimeException.class,
        () ->
            Functions.handleFunctionCalls(
                invocationContext, event, /* tools= */ ImmutableMap.of()));
  }

  @Test
  public void handleFunctionCalls_singleFunctionCall() {
    InvocationContext invocationContext = createInvocationContext(createRootAgent());
    ImmutableMap<String, Object> args = ImmutableMap.<String, Object>of("key", "value");
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(args)
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.toBuilder().id("").timestamp(0).build())
        .isEqualTo(
            Event.builder()
                .id("")
                .timestamp(0)
                .invocationId(invocationContext.invocationId())
                .author(invocationContext.agent().name())
                .content(
                    Content.builder()
                        .role("user")
                        .parts(
                            ImmutableList.of(
                                Part.builder()
                                    .functionResponse(
                                        FunctionResponse.builder()
                                            .id("function_call_id")
                                            .name("echo_tool")
                                            .response(ImmutableMap.of("result", args))
                                            .build())
                                    .build()))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_multipleFunctionCalls_parallel() {
    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());
    ImmutableMap<String, Object> args1 = ImmutableMap.<String, Object>of("key1", "value2");
    ImmutableMap<String, Object> args2 = ImmutableMap.<String, Object>of("key2", "value2");
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id1")
                                .name("echo_tool")
                                .args(args1)
                                .build())
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id2")
                                .name("echo_tool")
                                .args(args2)
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id1")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", args1))
                        .build())
                .build(),
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id2")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", args2))
                        .build())
                .build())
        .inOrder();
  }

  @Test
  public void handleFunctionCalls_multipleFunctionCalls_sequential() {
    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.SEQUENTIAL).build());
    ImmutableMap<String, Object> args1 = ImmutableMap.<String, Object>of("key1", "value2");
    ImmutableMap<String, Object> args2 = ImmutableMap.<String, Object>of("key2", "value2");
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id1")
                                .name("echo_tool")
                                .args(args1)
                                .build())
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id2")
                                .name("echo_tool")
                                .args(args2)
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id1")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", args1))
                        .build())
                .build(),
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id2")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", args2))
                        .build())
                .build())
        .inOrder();
  }

  @Test
  public void populateClientFunctionCallId_withMissingId_populatesId() {
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Functions.populateClientFunctionCallId(event);
    FunctionCall functionCall = event.content().get().parts().get().get(0).functionCall().get();
    assertThat(functionCall.id()).isPresent();
    assertThat(functionCall.id().get()).isNotEmpty();
  }

  @Test
  public void populateClientFunctionCallId_withEmptyId_populatesId() {
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("echo_tool")
                                .id("")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Functions.populateClientFunctionCallId(event);
    FunctionCall functionCall = event.content().get().parts().get().get(0).functionCall().get();
    assertThat(functionCall.id()).isPresent();
    assertThat(functionCall.id().get()).isNotEmpty();
  }

  @Test
  public void populateClientFunctionCallId_withExistingId_noChange() {
    String id = "some_id";
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("echo_tool")
                                .id(id)
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Functions.populateClientFunctionCallId(event);

    assertThat(event.content().get().parts().get().get(0).functionCall().get().id()).hasValue(id);
  }

  @Test
  public void getAskUserConfirmationFunctionCalls_eventWithNoContent_returnsEmptyList() {
    assertThat(Functions.getAskUserConfirmationFunctionCalls(EVENT_WITH_NO_CONTENT)).isEmpty();
  }

  @Test
  public void getAskUserConfirmationFunctionCalls_eventWithNoParts_returnsEmptyList() {
    assertThat(Functions.getAskUserConfirmationFunctionCalls(EVENT_WITH_NO_PARTS)).isEmpty();
  }

  @Test
  public void getAskUserConfirmationFunctionCalls_eventWithNoFunctionCalls_returnsEmptyList() {
    assertThat(Functions.getAskUserConfirmationFunctionCalls(EVENT_WITH_NO_FUNCTION_CALLS))
        .isEmpty();
  }

  @Test
  public void
      getAskUserConfirmationFunctionCalls_eventWithNonConfirmationFunctionCall_returnsEmptyList() {
    assertThat(
            Functions.getAskUserConfirmationFunctionCalls(
                EVENT_WITH_NON_CONFIRMATION_FUNCTION_CALL))
        .isEmpty();
  }

  @Test
  public void getAskUserConfirmationFunctionCalls_eventWithConfirmationFunctionCall_returnsCall() {
    FunctionCall confirmationCall =
        FunctionCall.builder().name(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME).build();
    Event event =
        Event.builder()
            .id("event1")
            .invocationId("invocation1")
            .author("agent")
            .content(Content.fromParts(Part.builder().functionCall(confirmationCall).build()))
            .build();
    ImmutableList<FunctionCall> result = Functions.getAskUserConfirmationFunctionCalls(event);
    assertThat(result).containsExactly(confirmationCall);
  }

  @Test
  public void
      getAskUserConfirmationFunctionCalls_eventWithMixedParts_returnsOnlyConfirmationCalls() {
    FunctionCall confirmationCall1 =
        FunctionCall.builder().name(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME).build();
    FunctionCall confirmationCall2 =
        FunctionCall.builder().name(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME).build();
    Event event =
        Event.builder()
            .id("event1")
            .invocationId("invocation1")
            .author("agent")
            .content(
                Content.fromParts(
                    Part.fromText("hello"),
                    Part.builder().functionCall(confirmationCall1).build(),
                    Part.fromFunctionCall("other_function", ImmutableMap.of()),
                    Part.builder().functionCall(confirmationCall2).build()))
            .build();
    ImmutableList<FunctionCall> result = Functions.getAskUserConfirmationFunctionCalls(event);
    assertThat(result).containsExactly(confirmationCall1, confirmationCall2);
  }
}
