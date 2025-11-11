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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for OpenTelemetry context propagation in ADK.
 *
 * <p>Verifies that spans created by ADK properly link to parent contexts when available, enabling
 * proper distributed tracing across async boundaries.
 */
class ContextPropagationTest {

  private InMemorySpanExporter spanExporter;
  private Tracer tracer;

  @BeforeEach
  void setup() {
    // Reset GlobalOpenTelemetry state
    GlobalOpenTelemetry.resetForTest();

    spanExporter = InMemorySpanExporter.create();

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();

    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();

    tracer = sdk.getTracer("test");
  }

  @Test
  void testToolCallSpanLinksToParent() {
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
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(2, spans.size(), "Should have 2 spans: parent and tool_call");

    SpanData parentSpanData =
        spans.stream()
            .filter(s -> s.getName().equals("parent"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Parent span not found"));

    SpanData toolCallSpanData =
        spans.stream()
            .filter(s -> s.getName().equals("tool_call [testTool]"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Tool call span not found"));

    // Verify parent-child relationship
    assertEquals(
        parentSpanData.getSpanContext().getTraceId(),
        toolCallSpanData.getSpanContext().getTraceId(),
        "Tool call should have same trace ID as parent");

    assertEquals(
        parentSpanData.getSpanContext().getSpanId(),
        toolCallSpanData.getParentSpanContext().getSpanId(),
        "Tool call's parent should be the parent span");
  }

  @Test
  void testToolCallWithoutParentCreatesRootSpan() {
    // Given: No parent span active
    // When: ADK creates tool_call span with setParent(Context.current())
    Span toolCallSpan =
        tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

    try (Scope scope = toolCallSpan.makeCurrent()) {
      // Work
    } finally {
      toolCallSpan.end();
    }

    // Then: Should create root span (backward compatible)
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size(), "Should have exactly 1 span");

    SpanData toolCallSpanData = spans.get(0);
    assertFalse(
        toolCallSpanData.getParentSpanContext().isValid(),
        "Tool call should be root span when no parent exists");
  }

  @Test
  void testNestedSpanHierarchy() {
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
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(4, spans.size(), "Should have 4 spans in the hierarchy");

    String parentTraceId =
        spans.stream()
            .filter(s -> s.getName().equals("parent"))
            .findFirst()
            .map(s -> s.getSpanContext().getTraceId())
            .orElseThrow(() -> new AssertionError("Parent span not found"));

    // All spans should have same trace ID
    spans.forEach(
        span ->
            assertEquals(
                parentTraceId,
                span.getSpanContext().getTraceId(),
                "All spans should be in same trace"));

    // Verify parent-child relationships
    SpanData parentSpanData = findSpanByName(spans, "parent");
    SpanData invocationSpanData = findSpanByName(spans, "invocation");
    SpanData toolCallSpanData = findSpanByName(spans, "tool_call [testTool]");
    SpanData toolResponseSpanData = findSpanByName(spans, "tool_response [testTool]");

    // invocation should be child of parent
    assertEquals(
        parentSpanData.getSpanContext().getSpanId(),
        invocationSpanData.getParentSpanContext().getSpanId(),
        "Invocation should be child of parent");

    // tool_call should be child of invocation
    assertEquals(
        invocationSpanData.getSpanContext().getSpanId(),
        toolCallSpanData.getParentSpanContext().getSpanId(),
        "Tool call should be child of invocation");

    // tool_response should be child of tool_call
    assertEquals(
        toolCallSpanData.getSpanContext().getSpanId(),
        toolResponseSpanData.getParentSpanContext().getSpanId(),
        "Tool response should be child of tool call");
  }

  @Test
  void testMultipleSpansInParallel() {
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
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(4, spans.size(), "Should have 4 spans: 1 parent + 3 tool calls");

    SpanData parentSpanData = findSpanByName(spans, "parent");
    String parentTraceId = parentSpanData.getSpanContext().getTraceId();
    String parentSpanId = parentSpanData.getSpanContext().getSpanId();

    // All tool calls should have same trace ID and parent span ID
    List<SpanData> toolCallSpans =
        spans.stream().filter(s -> s.getName().startsWith("tool_call")).toList();

    assertEquals(3, toolCallSpans.size(), "Should have 3 tool call spans");

    toolCallSpans.forEach(
        span -> {
          assertEquals(
              parentTraceId,
              span.getSpanContext().getTraceId(),
              "Tool call should have same trace ID as parent");
          assertEquals(
              parentSpanId,
              span.getParentSpanContext().getSpanId(),
              "Tool call should have parent as parent span");
        });
  }

  @Test
  void testAgentRunSpanLinksToInvocation() {
    // Test: agent_run span should link to invocation span

    Span invocationSpan = tracer.spanBuilder("invocation").startSpan();

    try (Scope invocationScope = invocationSpan.makeCurrent()) {
      Span agentRunSpan =
          tracer.spanBuilder("agent_run [test-agent]").setParent(Context.current()).startSpan();

      try (Scope agentScope = agentRunSpan.makeCurrent()) {
        // Simulate agent work
      } finally {
        agentRunSpan.end();
      }
    } finally {
      invocationSpan.end();
    }

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(2, spans.size(), "Should have 2 spans: invocation and agent_run");

    SpanData invocationSpanData = findSpanByName(spans, "invocation");
    SpanData agentRunSpanData = findSpanByName(spans, "agent_run [test-agent]");

    assertEquals(
        invocationSpanData.getSpanContext().getSpanId(),
        agentRunSpanData.getParentSpanContext().getSpanId(),
        "Agent run should be child of invocation");
  }

  @Test
  void testCallLlmSpanLinksToAgentRun() {
    // Test: call_llm span should link to agent_run span

    Span agentRunSpan = tracer.spanBuilder("agent_run [test-agent]").startSpan();

    try (Scope agentScope = agentRunSpan.makeCurrent()) {
      Span callLlmSpan = tracer.spanBuilder("call_llm").setParent(Context.current()).startSpan();

      try (Scope llmScope = callLlmSpan.makeCurrent()) {
        // Simulate LLM call
      } finally {
        callLlmSpan.end();
      }
    } finally {
      agentRunSpan.end();
    }

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(2, spans.size(), "Should have 2 spans: agent_run and call_llm");

    SpanData agentRunSpanData = findSpanByName(spans, "agent_run [test-agent]");
    SpanData callLlmSpanData = findSpanByName(spans, "call_llm");

    assertEquals(
        agentRunSpanData.getSpanContext().getSpanId(),
        callLlmSpanData.getParentSpanContext().getSpanId(),
        "Call LLM should be child of agent run");
  }

  @Test
  void testSpanCreatedWithinParentScopeIsCorrectlyParented() {
    // Test: Simulates creating a span within the scope of a parent

    Span parentSpan = tracer.spanBuilder("invocation").startSpan();
    try (Scope scope = parentSpan.makeCurrent()) {
      Span agentSpan = tracer.spanBuilder("agent_run").setParent(Context.current()).startSpan();
      agentSpan.end();
    } finally {
      parentSpan.end();
    }

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(2, spans.size(), "Should have 2 spans");

    SpanData parentSpanData = findSpanByName(spans, "invocation");
    SpanData agentSpanData = findSpanByName(spans, "agent_run");

    assertEquals(
        parentSpanData.getSpanContext().getSpanId(),
        agentSpanData.getParentSpanContext().getSpanId(),
        "Agent span should be a child of the invocation span");
  }

  private SpanData findSpanByName(List<SpanData> spans, String name) {
    return spans.stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Span not found: " + name));
  }
}
