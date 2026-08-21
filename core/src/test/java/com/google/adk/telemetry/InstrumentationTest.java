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

package com.google.adk.telemetry;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InstrumentationTest {

  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private Tracer originalTracer;
  private Meter originalMeter;
  private TestAgent testAgent;
  private InvocationContext invocationContext;

  private static class TestAgent extends BaseAgent {
    TestAgent() {
      super("my-agent", "my-agent-description", null, null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
      return Flowable.empty();
    }
  }

  private static class TestTool extends BaseTool {
    TestTool() {
      super("my-tool", "my-tool-description");
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
      return Single.just(args);
    }
  }

  @Before
  public void setup() {
    this.originalTracer = Tracing.getTracer();
    this.originalMeter = GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
    Tracing.setTracerForTesting(
        openTelemetryRule.getOpenTelemetry().getTracer("InstrumentationTest"));
    Metrics.setMeterForTesting(
        openTelemetryRule.getOpenTelemetry().getMeter("InstrumentationTest"));

    testAgent = new TestAgent();

    SessionKey sessionKey = new SessionKey("test-app", "test-user", "test-session");
    Session session = Session.builder(sessionKey).events(ImmutableList.of()).build();

    invocationContext =
        InvocationContext.builder()
            .sessionService(new InMemorySessionService())
            .session(session)
            .agent(testAgent)
            .invocationId("test-invocation-id")
            .build();
  }

  @After
  public void tearDown() {
    Tracing.setTracerForTesting(originalTracer);
    Metrics.setMeterForTesting(originalMeter);
  }

  @Test
  public void recordAgentInvocation_success() {
    try (Instrumentation.AgentInvocation invocation =
        Instrumentation.recordAgentInvocation(invocationContext, testAgent, Context.current())) {
      assertThat(invocation.context()).isNotNull();
      assertThat(invocation.context().otelContext()).isNotNull();
    }

    // Verify trace span
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("invoke_agent my-agent");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
        .isEqualTo("my-agent");

    // Verify metrics
    MetricData metric = findMetricByName("gen_ai.agent.invocation.duration");
    List<HistogramPointData> points =
        (List<HistogramPointData>) metric.getHistogramData().getPoints();
    assertThat(points).hasSize(1);
    HistogramPointData point = points.get(0);
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
        .isEqualTo("my-agent");
  }

  @Test
  public void recordAgentInvocation_withError() {
    RuntimeException testException = new RuntimeException("test error");
    try (Instrumentation.AgentInvocation invocation =
        Instrumentation.recordAgentInvocation(invocationContext, testAgent, Context.current())) {
      invocation.setError(testException);
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("invoke_agent my-agent");

    MetricData metric = findMetricByName("gen_ai.agent.invocation.duration");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getAttributes().get(AttributeKey.stringKey("error.type")))
        .isEqualTo("RuntimeException");
  }

  @Test
  public void recordToolExecution_success() {
    TestTool testTool = new TestTool();

    try (Instrumentation.ToolExecution execution =
        Instrumentation.recordToolExecution(
            testTool, testAgent, ImmutableMap.of("arg1", "value1"), Context.current())) {
      assertThat(execution.context()).isNotNull();
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).hasSize(1);
    SpanData span = spans.get(0);
    assertThat(span.getName()).isEqualTo("execute_tool my-tool");
    Attributes attrs = span.getAttributes();
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.tool.name"))).isEqualTo("my-tool");

    MetricData metric = findMetricByName("gen_ai.tool.execution.duration");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
        .isEqualTo("my-tool");

    metric = findMetricByName("gen_ai.tool.request.size");
    point = (HistogramPointData) metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
        .isEqualTo("my-tool");

    metric = findMetricByName("gen_ai.tool.response.size");
    point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
        .isEqualTo("my-tool");
  }

  private MetricData findMetricByName(String name) {
    return openTelemetryRule.getMetrics().stream()
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Metric not found: " + name));
  }
}
