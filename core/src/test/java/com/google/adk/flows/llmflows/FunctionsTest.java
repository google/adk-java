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

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.ToolExecutionMode;
import com.google.adk.events.Event;
import com.google.adk.platform.UuidProvider;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
  public void generateClientFunctionCallId_withProvider_derivesFromProvider() {
    UuidProvider provider = () -> "deterministic-uuid";

    String id = Functions.generateClientFunctionCallId(provider);

    assertThat(id).isEqualTo("adk-deterministic-uuid");
  }

  @Test
  public void populateClientFunctionCallId_withProvider_usesProvider() {
    Event event =
        Event.builder()
            .id("event1")
            .invocationId("invocation1")
            .author("agent")
            .content(Content.fromParts(Part.fromFunctionCall("some_function", ImmutableMap.of())))
            .build();

    Functions.populateClientFunctionCallId(event, () -> "deterministic-uuid");

    Part populatedPart = event.content().get().parts().get().get(0);
    assertThat(populatedPart.functionCall().get().id()).hasValue("adk-deterministic-uuid");
  }

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

    Event functionResponseEvent =
        Functions.handleFunctionCalls(invocationContext, event, /* tools= */ ImmutableMap.of())
            .blockingGet();

    assertThat(functionResponseEvent).isNull();
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

  // Default ToolExecutionMode.NONE behaves like PARALLEL: blocking tools still execute serially
  // on the caller thread (no worker scheduler is used), preserving the historical default.
  @Test
  public void handleFunctionCalls_defaultMode_blockingTools_runSerially() {
    long sleepMillis = 300L;
    int toolCount = 2;
    InvocationContext invocationContext =
        createInvocationContext(createRootAgent(), RunConfig.builder().build());

    Map<String, BaseTool> tools = new LinkedHashMap<>();
    List<Part> callParts = new ArrayList<>();
    for (int i = 1; i <= toolCount; i++) {
      String toolName = "slow_tool_" + i;
      tools.put(toolName, new SleepingTool(toolName, sleepMillis));
      callParts.add(
          Part.builder()
              .functionCall(
                  FunctionCall.builder()
                      .id("call_" + i)
                      .name(toolName)
                      .args(ImmutableMap.of())
                      .build())
              .build());
    }
    Event event =
        createEvent("event").toBuilder()
            .content(Content.fromParts(callParts.toArray(new Part[0])))
            .build();

    long start = System.currentTimeMillis();
    Event functionResponseEvent =
        Functions.handleFunctionCalls(invocationContext, event, tools).blockingGet();
    long durationMillis = System.currentTimeMillis() - start;

    assertThat(functionResponseEvent).isNotNull();
    assertThat(durationMillis).isAtLeast((long) toolCount * sleepMillis);
  }

  // PARALLEL mode does NOT introduce worker threads; blocking tools still run serially on the
  // caller thread. PARALLEL_SUBSCRIBE is the mode that runs blocking tools concurrently.
  @Test
  public void handleFunctionCalls_parallel_blockingTools_runSerially() {
    long sleepMillis = 300L;
    int toolCount = 2;
    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    Map<String, BaseTool> tools = new LinkedHashMap<>();
    List<Part> callParts = new ArrayList<>();
    for (int i = 1; i <= toolCount; i++) {
      String toolName = "slow_tool_" + i;
      tools.put(toolName, new SleepingTool(toolName, sleepMillis));
      callParts.add(
          Part.builder()
              .functionCall(
                  FunctionCall.builder()
                      .id("call_" + i)
                      .name(toolName)
                      .args(ImmutableMap.of())
                      .build())
              .build());
    }
    Event event =
        createEvent("event").toBuilder()
            .content(Content.fromParts(callParts.toArray(new Part[0])))
            .build();

    long start = System.currentTimeMillis();
    Event functionResponseEvent =
        Functions.handleFunctionCalls(invocationContext, event, tools).blockingGet();
    long durationMillis = System.currentTimeMillis() - start;

    assertThat(functionResponseEvent).isNotNull();
    assertThat(durationMillis).isAtLeast((long) toolCount * sleepMillis);
  }

  @Test
  public void handleFunctionCalls_parallelSubscribe_blockingTools_runConcurrently_twoTools() {
    runParallelSubscribeBlockingToolsTest(/* toolCount= */ 2);
  }

  @Test
  public void handleFunctionCalls_parallelSubscribe_blockingTools_runConcurrently_threeTools() {
    runParallelSubscribeBlockingToolsTest(/* toolCount= */ 3);
  }

  @Test
  public void handleFunctionCalls_parallelSubscribe_blockingTools_runConcurrently_fiveTools() {
    runParallelSubscribeBlockingToolsTest(/* toolCount= */ 5);
  }

  /** Single-tool case bypasses the parallel scheduler path; must still return the correct event. */
  @Test
  public void handleFunctionCalls_parallelSubscribe_blockingTool_singleTool() {
    long sleepMillis = 200L;
    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL_SUBSCRIBE).build());
    SleepingTool tool = new SleepingTool("slow_tool_1", sleepMillis);
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
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("slow_tool_1", tool))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("call_1")
                        .name("slow_tool_1")
                        .response(ImmutableMap.of("tool", "slow_tool_1"))
                        .build())
                .build());
  }

  @Test
  public void hasPendingLongRunningCall_eventWithLongRunningCall_returnsTrue() {
    assertThat(Functions.hasPendingLongRunningCall(longRunningCallEvent("call1"))).isTrue();
  }

  @Test
  public void hasPendingLongRunningCall_eventWithoutLongRunningIds_returnsFalse() {
    assertThat(Functions.hasPendingLongRunningCall(functionCallEvent("call1", null))).isFalse();
  }

  @Test
  public void hasPendingLongRunningCall_callIdNotMarkedLongRunning_returnsFalse() {
    assertThat(Functions.hasPendingLongRunningCall(functionCallEvent("call1", "other_id")))
        .isFalse();
  }

  @Test
  public void hasPendingLongRunningCall_list_callInSecondToLastEvent_returnsTrue() {
    ImmutableList<Event> events =
        ImmutableList.of(createEvent("first"), longRunningCallEvent("call1"), createEvent("last"));
    assertThat(Functions.hasPendingLongRunningCall(events)).isTrue();
  }

  @Test
  public void hasPendingLongRunningCall_list_callOlderThanLastTwoEvents_returnsFalse() {
    ImmutableList<Event> events =
        ImmutableList.of(longRunningCallEvent("call1"), createEvent("middle"), createEvent("last"));
    assertThat(Functions.hasPendingLongRunningCall(events)).isFalse();
  }

  @Test
  public void hasPendingLongRunningCall_emptyList_returnsFalse() {
    assertThat(Functions.hasPendingLongRunningCall(ImmutableList.<Event>of())).isFalse();
  }

  private static Event longRunningCallEvent(String callId) {
    return functionCallEvent(callId, callId);
  }

  // Event with a function call; longRunningId, when non-null, is marked long-running.
  private static Event functionCallEvent(String callId, String longRunningId) {
    Event.Builder builder =
        Event.builder()
            .id("event_" + callId)
            .invocationId("invocation1")
            .author("agent")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id(callId).name("tool").build())
                        .build()));
    if (longRunningId != null) {
      builder.longRunningToolIds(ImmutableSet.of(longRunningId));
    }
    return builder.build();
  }

  /**
   * Asserts that {@code toolCount} blocking tools in PARALLEL_SUBSCRIBE mode run faster than
   * sequential, since each tool is subscribed on a worker thread.
   */
  private static void runParallelSubscribeBlockingToolsTest(int toolCount) {
    long sleepMillis = 500L;
    InvocationContext invocationContext =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL_SUBSCRIBE).build());

    Map<String, BaseTool> tools = new LinkedHashMap<>();
    List<Part> callParts = new ArrayList<>();
    List<Part> expectedResponseParts = new ArrayList<>();
    for (int i = 1; i <= toolCount; i++) {
      String toolName = "slow_tool_" + i;
      String callId = "call_" + i;
      tools.put(toolName, new SleepingTool(toolName, sleepMillis));
      callParts.add(
          Part.builder()
              .functionCall(
                  FunctionCall.builder().id(callId).name(toolName).args(ImmutableMap.of()).build())
              .build());
      expectedResponseParts.add(
          Part.builder()
              .functionResponse(
                  FunctionResponse.builder()
                      .id(callId)
                      .name(toolName)
                      .response(ImmutableMap.of("tool", toolName))
                      .build())
              .build());
    }
    Event event =
        createEvent("event").toBuilder()
            .content(Content.fromParts(callParts.toArray(new Part[0])))
            .build();

    long start = System.currentTimeMillis();
    Event functionResponseEvent =
        Functions.handleFunctionCalls(invocationContext, event, tools).blockingGet();
    long durationMillis = System.currentTimeMillis() - start;

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactlyElementsIn(expectedResponseParts)
        .inOrder();
    // Sequential would be ~toolCount * sleepMillis; parallel is ~sleepMillis + fixed overhead.
    assertThat(durationMillis).isLessThan((long) toolCount * sleepMillis);
  }

  /** Tool that blocks the executing thread for {@code sleepMillis} before returning. */
  private static final class SleepingTool extends BaseTool {
    private final long sleepMillis;

    SleepingTool(String name, long sleepMillis) {
      super(name, "Blocking tool used to verify parallel execution.");
      this.sleepMillis = sleepMillis;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.fromCallable(
          () -> {
            Thread.sleep(sleepMillis);
            return ImmutableMap.<String, Object>of("tool", name());
          });
    }
  }
}
