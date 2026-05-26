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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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

  // ---------------------------------------------------------------------------
  // SlowTool: simulates blocking I/O (e.g. HTTP, JDBC) by sleeping on the
  // subscribing thread. Single.fromCallable ensures the sleep is deferred
  // until subscription time, so subscribeOn(Schedulers.io()) can move it
  // onto a background thread.
  // ---------------------------------------------------------------------------
  private static final class SlowTool extends BaseTool {
    private final long sleepMillis;
    // Records the wall-clock time at which runAsync actually starts executing,
    // so tests can verify that multiple tools started at roughly the same time.
    private final AtomicLong startedAtMillis = new AtomicLong(-1);

    SlowTool(String name, long sleepMillis) {
      super(name, "Slow tool for parallel-execution tests");
      this.sleepMillis = sleepMillis;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext ctx) {
      return Single.fromCallable(
          () -> {
            startedAtMillis.set(System.currentTimeMillis());
            Thread.sleep(sleepMillis);
            return ImmutableMap.<String, Object>of("tool", name(), "status", "done");
          });
    }
  }

  // Builds an Event that contains one FunctionCall part per tool name.
  private static Event buildFunctionCallEvent(String... toolNames) {
    List<Part> parts = new java.util.ArrayList<>();
    for (int i = 0; i < toolNames.length; i++) {
      parts.add(
          Part.builder()
              .functionCall(
                  FunctionCall.builder()
                      .id("call_" + (i + 1))
                      .name(toolNames[i])
                      .args(ImmutableMap.of())
                      .build())
              .build());
    }
    return createEvent("event").toBuilder()
        .content(Content.builder().role("model").parts(parts).build())
        .build();
  }

  // ---------------------------------------------------------------------------
  // Test 1 — PARALLEL mode: wall-clock time ≈ max(latencies), not sum.
  //
  // Two tools each sleeping 500 ms. Sequential would take ~1 000 ms.
  // With real parallelism, both run on IO threads simultaneously → ~500 ms.
  // We assert duration < 800 ms (generous CI slack) AND >= 500 ms.
  // ---------------------------------------------------------------------------
  @Test
  public void handleFunctionCalls_parallelMode_runsToolsConcurrently() {
    long sleepMs = 500;
    SlowTool tool1 = new SlowTool("slow_tool_1", sleepMs);
    SlowTool tool2 = new SlowTool("slow_tool_2", sleepMs);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    long start = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("slow_tool_1", "slow_tool_2"),
                ImmutableMap.of("slow_tool_1", tool1, "slow_tool_2", tool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - start;

    // Must have waited at least one sleep cycle (both tools ran).
    assertThat(duration).isAtLeast(sleepMs);
    // Must NOT have waited two full sleep cycles (they ran in parallel).
    assertThat(duration).isLessThan(2 * sleepMs - 100);
    // Both function responses are present.
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
  }

  // ---------------------------------------------------------------------------
  // Test 2 — PARALLEL mode: result ORDER matches INPUT order,
  // even when the slower tool is listed first.
  //
  // tool_slow (500 ms) is call_1, tool_fast (100 ms) is call_2.
  // concatMapEager eagerly subscribes to both but emits results in input order.
  // So result[0] must be tool_slow's response even though it finishes later.
  // ---------------------------------------------------------------------------
  @Test
  public void handleFunctionCalls_parallelMode_preservesInputOrder() {
    SlowTool slowFirst = new SlowTool("tool_slow", 500);
    SlowTool fastSecond = new SlowTool("tool_fast", 100);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("tool_slow", "tool_fast"),
                ImmutableMap.of("tool_slow", slowFirst, "tool_fast", fastSecond))
            .blockingGet();

    assertThat(result).isNotNull();
    List<Part> parts = result.content().get().parts().get();
    assertThat(parts).hasSize(2);
    // First result belongs to the first-listed tool (tool_slow), not the faster one.
    assertThat(parts.get(0).functionResponse().get().name()).hasValue("tool_slow");
    assertThat(parts.get(1).functionResponse().get().name()).hasValue("tool_fast");
  }

  // ---------------------------------------------------------------------------
  // Test 3 — NONE mode: Javadoc says "defaults to PARALLEL".
  // Must behave identically to PARALLEL (concurrent, not serial).
  // ---------------------------------------------------------------------------
  @Test
  public void handleFunctionCalls_noneModeDefaultsToParallel_runsToolsConcurrently() {
    long sleepMs = 500;
    SlowTool tool1 = new SlowTool("slow_tool_1", sleepMs);
    SlowTool tool2 = new SlowTool("slow_tool_2", sleepMs);

    // RunConfig.builder().build() leaves toolExecutionMode as NONE by default.
    InvocationContext ctx = createInvocationContext(createRootAgent());

    long start = System.currentTimeMillis();
    Functions.handleFunctionCalls(
            ctx,
            buildFunctionCallEvent("slow_tool_1", "slow_tool_2"),
            ImmutableMap.of("slow_tool_1", tool1, "slow_tool_2", tool2))
        .blockingGet();
    long duration = System.currentTimeMillis() - start;

    assertThat(duration).isAtLeast(sleepMs);
    assertThat(duration).isLessThan(2 * sleepMs - 100);
  }

  // ---------------------------------------------------------------------------
  // Test 4 — SEQUENTIAL mode: must be serial (duration ≥ sum of latencies).
  // ---------------------------------------------------------------------------
  @Test
  public void handleFunctionCalls_sequentialMode_runsToolsSerially() {
    long sleepMs = 300;
    SlowTool tool1 = new SlowTool("slow_tool_1", sleepMs);
    SlowTool tool2 = new SlowTool("slow_tool_2", sleepMs);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.SEQUENTIAL).build());

    long start = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("slow_tool_1", "slow_tool_2"),
                ImmutableMap.of("slow_tool_1", tool1, "slow_tool_2", tool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - start;

    // Sequential: must take at least the sum of both sleep times.
    assertThat(duration).isAtLeast(2 * sleepMs);
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
  }

  // ---------------------------------------------------------------------------
  // Test 5 — handleFunctionCallsLive PARALLEL path.
  //
  // This is the Live/streaming code path that PR #1127 did NOT fix
  // (it only changed callTool, not the concatMapEager in handleFunctionCallsLive).
  // Our fix adds subscribeOn to both code paths, so this must also be concurrent.
  //
  // SlowTool is a plain BaseTool (not FunctionTool with isStreaming=true), so
  // handleFunctionCallsLive falls through to Case 3 → callTool.
  // ---------------------------------------------------------------------------
  @Test
  public void handleFunctionCallsLive_parallelMode_runsToolsConcurrently() {
    long sleepMs = 500;
    SlowTool tool1 = new SlowTool("slow_tool_1", sleepMs);
    SlowTool tool2 = new SlowTool("slow_tool_2", sleepMs);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    long start = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCallsLive(
                ctx,
                buildFunctionCallEvent("slow_tool_1", "slow_tool_2"),
                ImmutableMap.of("slow_tool_1", tool1, "slow_tool_2", tool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - start;

    assertThat(duration).isAtLeast(sleepMs);
    assertThat(duration).isLessThan(2 * sleepMs - 100);
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
  }

  // ---------------------------------------------------------------------------
  // Test 6 — Three tools in PARALLEL: all three start at nearly the same time.
  //
  // Uses startedAtMillis to verify that all three tools actually began
  // executing concurrently (all started within 150 ms of each other),
  // not one after another.
  // ---------------------------------------------------------------------------
  @Test
  public void handleFunctionCalls_parallelMode_threeTools_allStartConcurrently() {
    long sleepMs = 500;
    SlowTool tool1 = new SlowTool("tool_1", sleepMs);
    SlowTool tool2 = new SlowTool("tool_2", sleepMs);
    SlowTool tool3 = new SlowTool("tool_3", sleepMs);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    Functions.handleFunctionCalls(
            ctx,
            buildFunctionCallEvent("tool_1", "tool_2", "tool_3"),
            ImmutableMap.of("tool_1", tool1, "tool_2", tool2, "tool_3", tool3))
        .blockingGet();

    long t1 = tool1.startedAtMillis.get();
    long t2 = tool2.startedAtMillis.get();
    long t3 = tool3.startedAtMillis.get();
    // All three tools must have started (startedAtMillis was set).
    assertThat(t1).isGreaterThan(0L);
    assertThat(t2).isGreaterThan(0L);
    assertThat(t3).isGreaterThan(0L);
    // All three started within 150 ms of each other → truly concurrent.
    long spread = Math.max(Math.max(t1, t2), t3) - Math.min(Math.min(t1, t2), t3);
    assertThat(spread).isLessThan(150L);
  }

  // ---------------------------------------------------------------------------
  // BlockingTool-based tests
  //
  // These use TestUtils.BlockingTool which also records the executing thread
  // name, enabling stricter thread-level concurrency assertions.
  // ---------------------------------------------------------------------------

  // Test B1 — 3 blocking tools in PARALLEL: total wall-clock ≈ max, not sum.
  @Test
  public void handleFunctionCalls_blockingTools_parallel_executesInParallel() {
    long sleepMs = 1000;
    TestUtils.BlockingTool tool1 = new TestUtils.BlockingTool("blocking_tool_1", sleepMs);
    TestUtils.BlockingTool tool2 = new TestUtils.BlockingTool("blocking_tool_2", sleepMs);
    TestUtils.BlockingTool tool3 = new TestUtils.BlockingTool("blocking_tool_3", sleepMs);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    long start = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("blocking_tool_1", "blocking_tool_2", "blocking_tool_3"),
                ImmutableMap.of(
                    "blocking_tool_1", tool1, "blocking_tool_2", tool2, "blocking_tool_3", tool3))
            .blockingGet();
    long duration = System.currentTimeMillis() - start;

    // All 3 tools run concurrently → duration ≈ 1 sleep, not 3 sleeps.
    assertThat(duration).isAtLeast(sleepMs);
    assertThat(duration).isLessThan((long) (1.5 * sleepMs));
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(3);
  }

  // Test B2 — NONE mode (default) must also behave as PARALLEL.
  @Test
  public void handleFunctionCalls_blockingTools_defaultNoneMode_executesInParallel() {
    long sleepMs = 1000;
    TestUtils.BlockingTool tool1 = new TestUtils.BlockingTool("blocking_tool_1", sleepMs);
    TestUtils.BlockingTool tool2 = new TestUtils.BlockingTool("blocking_tool_2", sleepMs);

    // RunConfig.builder().build() leaves ToolExecutionMode as NONE.
    InvocationContext ctx = createInvocationContext(createRootAgent());

    long start = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("blocking_tool_1", "blocking_tool_2"),
                ImmutableMap.of("blocking_tool_1", tool1, "blocking_tool_2", tool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - start;

    assertThat(duration).isAtLeast(sleepMs);
    assertThat(duration).isLessThan((long) (1.5 * sleepMs));
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
  }

  // Test B3 — SEQUENTIAL mode must be serial: duration ≥ sum of latencies.
  @Test
  public void handleFunctionCalls_blockingTools_sequential_executesSerially() {
    long sleepMs = 500;
    TestUtils.BlockingTool tool1 = new TestUtils.BlockingTool("blocking_tool_1", sleepMs);
    TestUtils.BlockingTool tool2 = new TestUtils.BlockingTool("blocking_tool_2", sleepMs);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.SEQUENTIAL).build());

    long start = System.currentTimeMillis();
    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("blocking_tool_1", "blocking_tool_2"),
                ImmutableMap.of("blocking_tool_1", tool1, "blocking_tool_2", tool2))
            .blockingGet();
    long duration = System.currentTimeMillis() - start;

    // Sequential execution: must wait for both tools back-to-back.
    assertThat(duration).isAtLeast(2 * sleepMs);
    assertThat(result).isNotNull();
    assertThat(result.content().get().parts().get()).hasSize(2);
  }

  // Test B4 — PARALLEL mode must preserve input order even when slower tool is listed first.
  @Test
  public void handleFunctionCalls_blockingTools_parallel_preservesOrder() {
    TestUtils.BlockingTool slowTool = new TestUtils.BlockingTool("slow_blocking_tool", 800);
    TestUtils.BlockingTool fastTool = new TestUtils.BlockingTool("fast_blocking_tool", 100);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    // slow_blocking_tool listed first → its result must be first in the response.
    Event result =
        Functions.handleFunctionCalls(
                ctx,
                buildFunctionCallEvent("slow_blocking_tool", "fast_blocking_tool"),
                ImmutableMap.of("slow_blocking_tool", slowTool, "fast_blocking_tool", fastTool))
            .blockingGet();

    assertThat(result).isNotNull();
    List<Part> parts = result.content().get().parts().get();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionResponse().get().name()).hasValue("slow_blocking_tool");
    assertThat(parts.get(1).functionResponse().get().name()).hasValue("fast_blocking_tool");
  }

  // Test B5 — PARALLEL mode dispatches each tool to a distinct IO thread.
  @Test
  public void handleFunctionCalls_blockingTools_parallel_usesMultipleThreads() {
    TestUtils.BlockingTool tool1 = new TestUtils.BlockingTool("thread_tool_1", 500);
    TestUtils.BlockingTool tool2 = new TestUtils.BlockingTool("thread_tool_2", 500);

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    Functions.handleFunctionCalls(
            ctx,
            buildFunctionCallEvent("thread_tool_1", "thread_tool_2"),
            ImmutableMap.of("thread_tool_1", tool1, "thread_tool_2", tool2))
        .blockingGet();

    // Both tools must have recorded a thread name (i.e. they actually executed).
    assertThat(tool1.getExecutionThreadName()).isNotNull();
    assertThat(tool2.getExecutionThreadName()).isNotNull();
    // True concurrency: the two tools ran on different IO scheduler threads.
    assertThat(tool1.getExecutionThreadName()).isNotEqualTo(tool2.getExecutionThreadName());
  }

  // Test B6 — Regression: non-blocking tools still work correctly in PARALLEL mode.
  @Test
  public void handleFunctionCalls_nonBlockingTools_parallel_stillWorksCorrectly() {
    ImmutableMap<String, Object> args1 = ImmutableMap.<String, Object>of("key1", "value1");
    ImmutableMap<String, Object> args2 = ImmutableMap.<String, Object>of("key2", "value2");
    ImmutableMap<String, Object> args3 = ImmutableMap.<String, Object>of("key3", "value3");

    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_1")
                                .name("echo_tool")
                                .args(args1)
                                .build())
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_2")
                                .name("echo_tool")
                                .args(args2)
                                .build())
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call_3")
                                .name("echo_tool")
                                .args(args3)
                                .build())
                        .build()))
            .build();

    InvocationContext ctx =
        createInvocationContext(
            createRootAgent(),
            RunConfig.builder().setToolExecutionMode(ToolExecutionMode.PARALLEL).build());

    Event result =
        Functions.handleFunctionCalls(
                ctx, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(result).isNotNull();
    List<Part> parts = result.content().get().parts().get();
    assertThat(parts).hasSize(3);
    // All three responses present in input order with correct echoed args.
    assertThat(parts.get(0).functionResponse().get().name()).hasValue("echo_tool");
    assertThat(parts.get(0).functionResponse().get().response())
        .hasValue(ImmutableMap.of("result", args1));
    assertThat(parts.get(1).functionResponse().get().response())
        .hasValue(ImmutableMap.of("result", args2));
    assertThat(parts.get(2).functionResponse().get().response())
        .hasValue(ImmutableMap.of("result", args3));
  }
}
