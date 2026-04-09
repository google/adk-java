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
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Functions}. */
@RunWith(JUnit4.class)
public final class FunctionsTest {

  private static final Event EVENT_WITH_NO_CONTENT =
      Event.builder().id("event1").invocationId("invocation1").author("agent").build();

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

  /**
   * A tool that blocks for a specified duration, simulating a slow I/O operation. Uses
   * Single.fromCallable to ensure the sleep is deferred until subscription time.
   */
  private static class SlowTool extends BaseTool {
    private final String toolName;
    private final long sleepMillis;

    SlowTool(String name, long sleepMillis) {
      super(name, "A slow tool for testing parallel execution");
      this.toolName = name;
      this.sleepMillis = sleepMillis;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(toolName).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.fromCallable(
          () -> {
            Thread.sleep(sleepMillis);
            return ImmutableMap.<String, Object>of("tool", toolName, "status", "done");
          });
    }
  }

  @Test
  public void handleFunctionCalls_parallelMode_shouldExecuteConcurrently() {
    long sleepTime = 1000;
    SlowTool slowTool1 = new SlowTool("slow_tool_1", sleepTime);
    SlowTool slowTool2 = new SlowTool("slow_tool_2", sleepTime);

    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_1")
                                .name("slow_tool_1")
                                .args(ImmutableMap.of())
                                .build())
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_2")
                                .name("slow_tool_2")
                                .args(ImmutableMap.of())
                                .build())
                        .build()))
            .build();

    long startTime = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                invocationContext,
                event,
                ImmutableMap.of("slow_tool_1", slowTool1, "slow_tool_2", slowTool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - startTime;

    // If parallel, duration should be ~1000ms, not ~2000ms.
    assertThat(duration).isAtLeast(sleepTime);
    assertThat(duration).isLessThan((long) (1.5 * sleepTime));

    // Verify results are returned in correct order (concatMapEager preserves order).
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
    assertThat(result.content().get().parts().get().get(0).functionResponse().get().name())
        .hasValue("slow_tool_1");
    assertThat(result.content().get().parts().get().get(1).functionResponse().get().name())
        .hasValue("slow_tool_2");
  }

  @Test
  public void handleFunctionCalls_sequentialMode_shouldExecuteSerially() {
    long sleepTime = 1000;
    SlowTool slowTool1 = new SlowTool("slow_tool_1", sleepTime);
    SlowTool slowTool2 = new SlowTool("slow_tool_2", sleepTime);

    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.SEQUENTIAL).build());

    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_1")
                                .name("slow_tool_1")
                                .args(ImmutableMap.of())
                                .build())
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_2")
                                .name("slow_tool_2")
                                .args(ImmutableMap.of())
                                .build())
                        .build()))
            .build();

    long startTime = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                invocationContext,
                event,
                ImmutableMap.of("slow_tool_1", slowTool1, "slow_tool_2", slowTool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - startTime;

    // Sequential: duration should be >= 2 * sleepTime.
    assertThat(duration).isAtLeast(2 * sleepTime);

    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
  }
}
