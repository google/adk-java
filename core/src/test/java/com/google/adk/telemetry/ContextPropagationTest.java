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

package com.google.adk.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for OpenTelemetry context propagation in ADK.
 *
 * <p>Verifies that spans created by ADK properly link to parent contexts when available, enabling
 * proper distributed tracing across async boundaries.
 */
@RunWith(JUnit4.class)
public class ContextPropagationTest {
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private Tracer tracer;
  private Tracer originalTracer;
  private LlmAgent agent;
  private InMemorySessionService sessionService;

  @Before
  public void setup() {
    this.originalTracer = Tracing.getTracer();
    Tracing.setTracerForTesting(
        openTelemetryRule.getOpenTelemetry().getTracer("ContextPropagationTest"));
    tracer = openTelemetryRule.getOpenTelemetry().getTracer("test");
    agent = LlmAgent.builder().name("test_agent").description("test-description").build();
    sessionService = new InMemorySessionService();
  }

  @After
  public void tearDown() {
    Tracing.setTracerForTesting(originalTracer);
  }

  @Test
  public void testToolCallSpanLinksToParent() {
    // Given: Parent span is active
    Span parentSpan = tracer.spanBuilder("parent").startSpan();

    try (Scope scope = parentSpan.makeCurrent()) {
      // When: ADK creates tool_call span with setParent(Context.current())
      Span toolCallSpan =
          tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

      try (Scope toolScope = toolCallSpan.makeCurrent()) {
        // Simulate tool execution
      } finally {
        toolCallSpan.end();
      }
    } finally {
      parentSpan.end();
    }

    // Then: tool_call should be child of parent
    SpanData parentSpanData = findSpanByName("parent");
    SpanData toolCallSpanData = findSpanByName("tool_call [testTool]");

    // Verify parent-child relationship
    assertEquals(
        "Tool call should have same trace ID as parent",
        parentSpanData.getSpanContext().getTraceId(),
        toolCallSpanData.getSpanContext().getTraceId());

    assertParent(parentSpanData, toolCallSpanData);
  }

  @Test
  public void testToolCallWithoutParentCreatesRootSpan() {
    // Given: No parent span active
    // When: ADK creates tool_call span with setParent(Context.current())
    try (Scope s = Context.root().makeCurrent()) {
      Span toolCallSpan =
          tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

      try (Scope scope = toolCallSpan.makeCurrent()) {
        // Work
      } finally {
        toolCallSpan.end();
      }
    }

    // Then: Should create root span (backward compatible)
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);

    SpanData toolCallSpanData = spans.get(0);
    assertFalse(
        "Tool call should be root span when no parent exists",
        toolCallSpanData.getParentSpanContext().isValid());
  }

  @Test
  public void testNestedSpanHierarchy() {
    // Test: parent → invocation → tool_call → tool_response hierarchy

    Span parentSpan = tracer.spanBuilder("parent").startSpan();

    try (Scope parentScope = parentSpan.makeCurrent()) {

      Span invocationSpan =
          tracer.spanBuilder("invocation").setParent(Context.current()).startSpan();

      try (Scope invocationScope = invocationSpan.makeCurrent()) {

        Span toolCallSpan =
            tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

        try (Scope toolScope = toolCallSpan.makeCurrent()) {

          Span toolResponseSpan =
              tracer
                  .spanBuilder("tool_response [testTool]")
                  .setParent(Context.current())
                  .startSpan();

          toolResponseSpan.end();
        } finally {
          toolCallSpan.end();
        }
      } finally {
        invocationSpan.end();
      }
    } finally {
      parentSpan.end();
    }

    // Verify complete hierarchy
    List<SpanData> spans = openTelemetryRule.getSpans();
    // The 4 spans are: "parent", "invocation", "tool_call [testTool]", and "tool_response
    // [testTool]".
    assertThat(spans).hasSize(4);

    SpanData parentSpanData = findSpanByName("parent");
    String parentTraceId = parentSpanData.getSpanContext().getTraceId();

    // All spans should have same trace ID
    for (SpanData span : openTelemetryRule.getSpans()) {
      assertEquals(
          "All spans should be in same trace", parentTraceId, span.getSpanContext().getTraceId());
    }

    // Verify parent-child relationships
    SpanData invocationSpanData = findSpanByName("invocation");
    SpanData toolCallSpanData = findSpanByName("tool_call [testTool]");
    SpanData toolResponseSpanData = findSpanByName("tool_response [testTool]");

    // invocation should be child of parent
    assertParent(parentSpanData, invocationSpanData);

    // tool_call should be child of invocation
    assertParent(invocationSpanData, toolCallSpanData);

    // tool_response should be child of tool_call
    assertParent(toolCallSpanData, toolResponseSpanData);
  }

  @Test
  public void testMultipleSpansInParallel() {
    // Test: Multiple tool calls in parallel should all link to same parent

    Span parentSpan = tracer.spanBuilder("parent").startSpan();

    try (Scope parentScope = parentSpan.makeCurrent()) {
      // Simulate parallel tool calls
      Span toolCall1 =
          tracer.spanBuilder("tool_call [tool1]").setParent(Context.current()).startSpan();
      Span toolCall2 =
          tracer.spanBuilder("tool_call [tool2]").setParent(Context.current()).startSpan();
      Span toolCall3 =
          tracer.spanBuilder("tool_call [tool3]").setParent(Context.current()).startSpan();

      toolCall1.end();
      toolCall2.end();
      toolCall3.end();
    } finally {
      parentSpan.end();
    }

    // Verify all tool calls link to same parent
    SpanData parentSpanData = findSpanByName("parent");
    String parentTraceId = parentSpanData.getSpanContext().getTraceId();

    // All tool calls should have same trace ID and parent span ID
    List<SpanData> toolCallSpans =
        openTelemetryRule.getSpans().stream()
            .filter(s -> s.getName().startsWith("tool_call"))
            .toList();

    assertThat(toolCallSpans).hasSize(3);

    toolCallSpans.forEach(
        span -> {
          assertEquals(
              "Tool call should have same trace ID as parent",
              parentTraceId,
              span.getSpanContext().getTraceId());
          assertParent(parentSpanData, span);
        });
  }

  @Test
  public void testInvokeAgentSpanLinksToInvocation() {
    // Test: invoke_agent span should link to invocation span

    Span invocationSpan = tracer.spanBuilder("invocation").startSpan();

    try (Scope invocationScope = invocationSpan.makeCurrent()) {
      Span invokeAgentSpan =
          tracer.spanBuilder("invoke_agent test-agent").setParent(Context.current()).startSpan();

      try (Scope agentScope = invokeAgentSpan.makeCurrent()) {
        // Simulate agent work
      } finally {
        invokeAgentSpan.end();
      }
    } finally {
      invocationSpan.end();
    }

    SpanData invocationSpanData = findSpanByName("invocation");
    SpanData invokeAgentSpanData = findSpanByName("invoke_agent test-agent");

    assertParent(invocationSpanData, invokeAgentSpanData);
  }

  @Test
  public void testCallLlmSpanLinksToAgentRun() {
    // Test: call_llm span should link to agent_run span

    Span invokeAgentSpan = tracer.spanBuilder("invoke_agent test-agent").startSpan();

    try (Scope agentScope = invokeAgentSpan.makeCurrent()) {
      Span callLlmSpan = tracer.spanBuilder("call_llm").setParent(Context.current()).startSpan();

      try (Scope llmScope = callLlmSpan.makeCurrent()) {
        // Simulate LLM call
      } finally {
        callLlmSpan.end();
      }
    } finally {
      invokeAgentSpan.end();
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(2);

    SpanData invokeAgentSpanData = findSpanByName("invoke_agent test-agent");
    SpanData callLlmSpanData = findSpanByName("call_llm");

    assertParent(invokeAgentSpanData, callLlmSpanData);
  }

  @Test
  public void testSpanCreatedWithinParentScopeIsCorrectlyParented() {
    // Test: Simulates creating a span within the scope of a parent

    Span parentSpan = tracer.spanBuilder("invocation").startSpan();
    try (Scope scope = parentSpan.makeCurrent()) {
      Span agentSpan = tracer.spanBuilder("invoke_agent").setParent(Context.current()).startSpan();
      agentSpan.end();
    } finally {
      parentSpan.end();
    }

    SpanData parentSpanData = findSpanByName("invocation");
    SpanData agentSpanData = findSpanByName("invoke_agent");

    assertParent(parentSpanData, agentSpanData);
  }

  @Test
  public void testTraceFlowable() throws InterruptedException {
    Span parentSpan = tracer.spanBuilder("parent").startSpan();
    try (Scope s = parentSpan.makeCurrent()) {
      Span flowableSpan = tracer.spanBuilder("flowable").setParent(Context.current()).startSpan();
      Flowable<Integer> flowable =
          Tracing.traceFlowable(
              Context.current().with(flowableSpan),
              flowableSpan,
              () ->
                  Flowable.just(1, 2, 3)
                      .map(
                          i -> {
                            assertEquals(
                                flowableSpan.getSpanContext().getSpanId(),
                                Span.current().getSpanContext().getSpanId());
                            return i * 2;
                          }));
      flowable.test().await().assertComplete();
    } finally {
      parentSpan.end();
    }

    SpanData parentSpanData = findSpanByName("parent");
    SpanData flowableSpanData = findSpanByName("flowable");
    assertParent(parentSpanData, flowableSpanData);
    assertTrue(flowableSpanData.hasEnded());
  }

  @Test
  public void testWithContextFlowable() throws InterruptedException {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.root().with(testKey, "test-value");

    Flowable<Integer> flowable =
        Flowable.just(1, 2, 3)
            .compose(Tracing.withContext(testContext))
            .subscribeOn(Schedulers.computation())
            .doOnNext(
                i -> {
                  assertEquals("test-value", Context.current().get(testKey));
                });
    flowable.test().await().assertComplete();
  }

  @Test
  public void testWithContextSingle() throws InterruptedException {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.root().with(testKey, "test-value");

    Single<Integer> single =
        Single.just(1)
            .compose(Tracing.withContext(testContext))
            .subscribeOn(Schedulers.computation())
            .doOnSuccess(
                i -> {
                  assertEquals("test-value", Context.current().get(testKey));
                });
    single.test().await().assertComplete();
  }

  @Test
  public void testWithContextMaybe() throws InterruptedException {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.root().with(testKey, "test-value");

    Maybe<Integer> maybe =
        Maybe.just(1)
            .compose(Tracing.withContext(testContext))
            .subscribeOn(Schedulers.computation())
            .doOnSuccess(
                i -> {
                  assertEquals("test-value", Context.current().get(testKey));
                });
    maybe.test().await().assertComplete();
  }

  @Test
  public void testWithContextCompletable() throws InterruptedException {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.root().with(testKey, "test-value");

    Completable completable =
        Completable.complete()
            .compose(Tracing.withContext(testContext))
            .subscribeOn(Schedulers.computation())
            .doOnComplete(
                () -> {
                  assertEquals("test-value", Context.current().get(testKey));
                });
    completable.test().await().assertComplete();
  }

  @Test
  public void testTraceTransformer() throws InterruptedException {
    Span parentSpan = tracer.spanBuilder("parent").startSpan();
    try (Scope s = parentSpan.makeCurrent()) {
      Flowable<Integer> flowable =
          Flowable.just(1, 2, 3)
              .map(
                  i -> {
                    assertTrue(Span.current().getSpanContext().isValid());
                    return i * 2;
                  })
              .compose(Tracing.trace("transformer"));
      flowable.test().await().assertComplete();
    } finally {
      parentSpan.end();
    }

    SpanData parentSpanData = findSpanByName("parent");
    SpanData transformerSpanData = findSpanByName("transformer");
    assertParent(parentSpanData, transformerSpanData);
    assertTrue(transformerSpanData.hasEnded());
  }

  @Test
  public void testTraceAgentInvocation() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Tracing.traceAgentInvocation(
          span, "test-agent", "test-description", buildInvocationContext());
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("invoke_agent", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("test-agent", attrs.get(AttributeKey.stringKey("gen_ai.agent.name")));
    assertEquals("test-description", attrs.get(AttributeKey.stringKey("gen_ai.agent.description")));
    assertEquals("test-session", attrs.get(AttributeKey.stringKey("gen_ai.conversation.id")));
  }

  @Test
  public void testTraceToolCall() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Tracing.traceToolCall(
          "tool-name", "tool-description", "tool-type", ImmutableMap.of("arg1", "value1"));
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("execute_tool", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("tool-name", attrs.get(AttributeKey.stringKey("gen_ai.tool.name")));
    assertEquals("tool-description", attrs.get(AttributeKey.stringKey("gen_ai.tool.description")));
    assertEquals("tool-type", attrs.get(AttributeKey.stringKey("gen_ai.tool.type")));
    assertEquals(
        "{\"arg1\":\"value1\"}",
        attrs.get(AttributeKey.stringKey("gcp.vertex.agent.tool_call_args")));
    assertEquals("{}", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_request")));
    assertEquals("{}", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_response")));
  }

  @Test
  public void testTraceToolResponse() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Event functionResponseEvent =
          Event.builder()
              .id("event-1")
              .content(
                  Content.fromParts(
                      Part.builder()
                          .functionResponse(
                              FunctionResponse.builder()
                                  .name("tool-name")
                                  .id("tool-call-id")
                                  .response(ImmutableMap.of("result", "tool-result"))
                                  .build())
                          .build()))
              .build();
      Tracing.traceToolResponse("event-1", functionResponseEvent);
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("execute_tool", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("event-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.event_id")));
    assertEquals("tool-call-id", attrs.get(AttributeKey.stringKey("gen_ai.tool_call.id")));
    assertEquals(
        "{\"result\":\"tool-result\"}",
        attrs.get(AttributeKey.stringKey("gcp.vertex.agent.tool_response")));
  }

  @Test
  public void testTraceCallLlm() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      LlmRequest llmRequest =
          LlmRequest.builder()
              .model("gemini-pro")
              .contents(ImmutableList.of(Content.fromParts(Part.fromText("hello"))))
              .config(GenerateContentConfig.builder().topP(0.9f).maxOutputTokens(100).build())
              .build();
      LlmResponse llmResponse =
          LlmResponse.builder()
              .content(Content.builder().parts(Part.fromText("world")).build())
              .finishReason(new FinishReason(FinishReason.Known.STOP))
              .usageMetadata(
                  GenerateContentResponseUsageMetadata.builder()
                      .promptTokenCount(10)
                      .candidatesTokenCount(20)
                      .totalTokenCount(30)
                      .build())
              .build();
      Tracing.traceCallLlm(span, buildInvocationContext(), "event-1", llmRequest, llmResponse);
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("gcp.vertex.agent", attrs.get(AttributeKey.stringKey("gen_ai.system")));
    assertEquals("gemini-pro", attrs.get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals(
        "test-invocation-id", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.invocation_id")));
    assertEquals("event-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.event_id")));
    assertEquals("test-session", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.session_id")));
    assertEquals(0.9d, attrs.get(AttributeKey.doubleKey("gen_ai.request.top_p")), 0.01);
    assertEquals(100L, (long) attrs.get(AttributeKey.longKey("gen_ai.request.max_tokens")));
    assertEquals(10L, (long) attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(20L, (long) attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertEquals(
        ImmutableList.of("stop"),
        attrs.get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")));
    assertTrue(
        attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_request")).contains("gemini-pro"));
    assertTrue(attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_response")).contains("STOP"));
  }

  @Test
  public void testTraceSendData() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Tracing.traceSendData(
          buildInvocationContext(),
          "event-1",
          ImmutableList.of(Content.fromParts(Part.fromText("hello"))));
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals(
        "test-invocation-id", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.invocation_id")));
    assertEquals("event-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.event_id")));
    assertEquals("test-session", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.session_id")));
    assertTrue(attrs.get(AttributeKey.stringKey("gcp.vertex.agent.data")).contains("hello"));
  }

  // Agent that emits one event on a computation thread.
  private static class TestAgent extends BaseAgent {
    TestAgent() {
      super("test-agent", "test-description", null, null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {
      return Flowable.just(
              Event.builder().content(Content.fromParts(Part.fromText("test"))).build())
          .subscribeOn(Schedulers.computation());
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.just(
              Event.builder().content(Content.fromParts(Part.fromText("test"))).build())
          .subscribeOn(Schedulers.computation());
    }
  }

  @Test
  public void baseAgentRunAsync_propagatesContext() throws InterruptedException {
    BaseAgent agent = new TestAgent();
    Span parentSpan = tracer.spanBuilder("parent").startSpan();
    try (Scope s = parentSpan.makeCurrent()) {
      agent.runAsync(buildInvocationContext()).test().await().assertComplete();
    } finally {
      parentSpan.end();
    }
    SpanData parent = findSpanByName("parent");
    SpanData agentSpan = findSpanByName("invoke_agent test-agent");
    assertParent(parent, agentSpan);
  }

  @Test
  public void runnerRunAsync_propagatesContext() throws InterruptedException {
    BaseAgent agent = new TestAgent();
    Span parentSpan = tracer.spanBuilder("parent").startSpan();
    try (Scope s = parentSpan.makeCurrent()) {
      runAgent(agent);
    } finally {
      parentSpan.end();
    }
    SpanData parent = findSpanByName("parent");
    SpanData invocation = findSpanByName("invocation");
    SpanData agentSpan = findSpanByName("invoke_agent test-agent");
    assertParent(parent, invocation);
    assertParent(invocation, agentSpan);
  }

  @Test
  public void runnerRunLive_propagatesContext() throws InterruptedException {
    BaseAgent agent = new TestAgent();
    Runner runner =
        Runner.builder().agent(agent).appName("test_app").sessionService(sessionService).build();
    Span parentSpan = tracer.spanBuilder("parent").startSpan();
    try (Scope s = parentSpan.makeCurrent()) {
      Session session =
          sessionService
              .createSession("test_app", "test-user", (Map<String, Object>) null, "test-session")
              .blockingGet();
      Content newMessage = Content.fromParts(Part.fromText("hi"));
      RunConfig runConfig = RunConfig.builder().build();
      LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
      liveRequestQueue.content(newMessage);
      liveRequestQueue.close();
      runner
          .runLive(session.userId(), session.id(), liveRequestQueue, runConfig)
          .test()
          .await()
          .assertComplete();
    } finally {
      parentSpan.end();
    }
    SpanData parent = findSpanByName("parent");
    SpanData invocation = findSpanByName("invocation");
    SpanData agentSpan = findSpanByName("invoke_agent test-agent");
    assertParent(parent, invocation);
    assertParent(invocation, agentSpan);
  }

  @Test
  public void testAgentWithToolCallTraceHierarchy() throws InterruptedException {
    // This test verifies the trace hierarchy created when an agent calls an LLM,
    // which then invokes a tool. The expected hierarchy is:
    // invocation
    // └── invoke_agent test_agent
    //     ├── call_llm
    //     │   ├── tool_call [search_flights]
    //     │   └── tool_response [search_flights]
    //     └── call_llm

    SearchFlightsTool searchFlightsTool = new SearchFlightsTool();

    TestLlm testLlm =
        TestUtils.createTestLlm(
            TestUtils.createLlmResponse(
                Content.builder()
                    .role("model")
                    .parts(
                        Part.fromFunctionCall(
                            searchFlightsTool.name(), ImmutableMap.of("destination", "SFO")))
                    .build()),
            TestUtils.createLlmResponse(Content.fromParts(Part.fromText("done"))));

    LlmAgent agentWithTool =
        LlmAgent.builder()
            .name("test_agent")
            .description("description")
            .model(testLlm)
            .tools(ImmutableList.of(searchFlightsTool))
            .build();

    runAgent(agentWithTool);

    SpanData invocation = findSpanByName("invocation");
    SpanData invokeAgent = findSpanByName("invoke_agent test_agent");
    SpanData toolCall = findSpanByName("tool_call [search_flights]");
    SpanData toolResponse = findSpanByName("tool_response [search_flights]");
    List<SpanData> callLlmSpans =
        openTelemetryRule.getSpans().stream()
            .filter(s -> s.getName().equals("call_llm"))
            .sorted(Comparator.comparing(SpanData::getStartEpochNanos))
            .toList();
    assertThat(callLlmSpans).hasSize(2);
    SpanData callLlm1 = callLlmSpans.get(0);
    SpanData callLlm2 = callLlmSpans.get(1);

    // Assert hierarchy:
    // invocation
    // └── invoke_agent test_agent
    assertParent(invocation, invokeAgent);
    //     ├── call_llm 1
    assertParent(invokeAgent, callLlm1);
    //     │   ├── tool_call [search_flights]
    assertParent(callLlm1, toolCall);
    //     │   └── tool_response [search_flights]
    assertParent(callLlm1, toolResponse);
    //     └── call_llm 2
    assertParent(invokeAgent, callLlm2);
  }

  @Test
  public void testNestedAgentTraceHierarchy() throws InterruptedException {
    // This test verifies the trace hierarchy created when AgentA transfers to AgentB.
    // The expected hierarchy is:
    // invocation
    // └── invoke_agent AgentA
    //     ├── call_llm
    //     │   ├── tool_call [transfer_to_agent]
    //     │   └── tool_response [transfer_to_agent]
    //     └── invoke_agent AgentB
    //         └── call_llm
    TestLlm llm =
        TestUtils.createTestLlm(
            TestUtils.createLlmResponse(
                Content.builder()
                    .role("model")
                    .parts(
                        Part.fromFunctionCall(
                            "transfer_to_agent", ImmutableMap.of("agent_name", "AgentB")))
                    .build()),
            TestUtils.createLlmResponse(Content.fromParts(Part.fromText("agent b response"))));
    LlmAgent agentB = LlmAgent.builder().name("AgentB").description("Agent B").model(llm).build();

    LlmAgent agentA =
        LlmAgent.builder()
            .name("AgentA")
            .description("Agent A")
            .model(llm)
            .subAgents(ImmutableList.of(agentB))
            .build();

    runAgent(agentA);

    SpanData invocation = findSpanByName("invocation");
    SpanData agentASpan = findSpanByName("invoke_agent AgentA");
    SpanData toolCall = findSpanByName("tool_call [transfer_to_agent]");
    SpanData agentBSpan = findSpanByName("invoke_agent AgentB");
    SpanData toolResponse = findSpanByName("tool_response [transfer_to_agent]");

    List<SpanData> callLlmSpans =
        openTelemetryRule.getSpans().stream()
            .filter(s -> s.getName().equals("call_llm"))
            .sorted(Comparator.comparing(SpanData::getStartEpochNanos))
            .toList();
    assertThat(callLlmSpans).hasSize(2);

    SpanData agentACallLlm1 = callLlmSpans.get(0);
    SpanData agentBCallLlm = callLlmSpans.get(1);

    assertParent(invocation, agentASpan);
    assertParent(agentASpan, agentACallLlm1);
    assertParent(agentACallLlm1, toolCall);
    assertParent(agentACallLlm1, toolResponse);
    assertParent(agentASpan, agentBSpan);
    assertParent(agentBSpan, agentBCallLlm);
  }

  private void runAgent(BaseAgent agent) throws InterruptedException {
    Runner runner =
        Runner.builder().agent(agent).appName("test_app").sessionService(sessionService).build();
    Session session =
        sessionService.createSession("test_app", "test-user", null, "test-session").blockingGet();
    Content newMessage = Content.fromParts(Part.fromText("hi"));
    RunConfig runConfig = RunConfig.builder().build();
    runner
        .runAsync(session.sessionKey(), newMessage, runConfig, null)
        .test()
        .await()
        .assertComplete();
  }

  /** Tool for testing. */
  public static class SearchFlightsTool extends BaseTool {
    public SearchFlightsTool() {
      super("search_flights", "Search for flights tool");
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
      return Single.just(ImmutableMap.of("result", args));
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(
          FunctionDeclaration.builder()
              .name("search_flights")
              .description("Search for flights tool")
              .build());
    }
  }

  /**
   * Asserts that the parent span is the parent of the child span.
   *
   * @param parent The parent span.
   * @param child The child span.
   */
  private void assertParent(SpanData parent, SpanData child) {
    assertEquals(parent.getSpanContext().getSpanId(), child.getParentSpanContext().getSpanId());
  }

  /**
   * Finds a span by name, polling multiple times.
   *
   * <p>This is necessary because spans might be created in separate threads, and we cannot always
   * rely on `.await()` to ensure all spans are available immediately.
   */
  private SpanData findSpanByName(String name) {
    for (int i = 0; i < 15; i++) {
      Optional<SpanData> span =
          openTelemetryRule.getSpans().stream().filter(s -> s.getName().equals(name)).findFirst();
      if (span.isPresent()) {
        return span.get();
      }
      try {
        Thread.sleep(10 * i);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("Span not found after polling: " + name);
  }

  private InvocationContext buildInvocationContext() {
    Session session =
        sessionService
            .createSession("test_app", "test-user", (Map<String, Object>) null, "test-session")
            .blockingGet();
    return InvocationContext.builder()
        .sessionService(sessionService)
        .session(session)
        .agent(agent)
        .invocationId("test-invocation-id")
        .build();
  }
}
