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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class MetricsTest {

  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private Meter originalMeter;

  @Before
  public void setup() {
    this.originalMeter = GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
    Metrics.setMeterForTesting(openTelemetryRule.getOpenTelemetry().getMeter("MetricsTest"));
  }

  @After
  public void tearDown() {
    Metrics.setMeterForTesting(originalMeter);
  }

  @Test
  public void recordAgentInvocationDuration_success() {
    Metrics.recordAgentInvocationDuration("my-agent", Duration.ofMillis(123), null);

    MetricData metric = findMetricByName("gen_ai.agent.invocation.duration");
    assertThat(metric.getUnit()).isEqualTo("ms");
    assertThat(metric.getDescription()).isEqualTo("Duration of agent invocations.");

    List<HistogramPointData> points =
        (List<HistogramPointData>) metric.getHistogramData().getPoints();
    assertThat(points).hasSize(1);
    HistogramPointData point = points.get(0);
    assertThat(point.getSum()).isEqualTo(123.0);
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
        .isEqualTo("my-agent");
  }

  @Test
  public void recordAgentInvocationDuration_withError() {
    Metrics.recordAgentInvocationDuration(
        "my-agent", Duration.ofMillis(500), new IllegalArgumentException("bad arg"));

    MetricData metric = findMetricByName("gen_ai.agent.invocation.duration");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(500.0);
    Attributes attrs = point.getAttributes();
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.agent.name"))).isEqualTo("my-agent");
    assertThat(attrs.get(AttributeKey.stringKey("error.type")))
        .isEqualTo("IllegalArgumentException");
  }

  @Test
  public void recordToolExecutionDuration_success() {
    Metrics.recordToolExecutionDuration("my-tool", "my-agent", Duration.ofMillis(12), null);

    MetricData metric = findMetricByName("gen_ai.tool.execution.duration");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(12.0);
    Attributes attrs = point.getAttributes();
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.agent.name"))).isEqualTo("my-agent");
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.tool.name"))).isEqualTo("my-tool");
  }

  @Test
  public void recordToolExecutionDuration_withError() {
    Metrics.recordToolExecutionDuration(
        "my-tool", "my-agent", Duration.ofMillis(45), new NullPointerException());

    MetricData metric = findMetricByName("gen_ai.tool.execution.duration");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(45.0);
    Attributes attrs = point.getAttributes();
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.agent.name"))).isEqualTo("my-agent");
    assertThat(attrs.get(AttributeKey.stringKey("gen_ai.tool.name"))).isEqualTo("my-tool");
    assertThat(attrs.get(AttributeKey.stringKey("error.type"))).isEqualTo("NullPointerException");
  }

  @Test
  public void recordAgentRequestSize_success() {
    Content userContent =
        Content.builder()
            .parts(
                Part.fromText("hello"),
                Part.builder()
                    .inlineData(
                        Blob.builder().data("world".getBytes(UTF_8)).mimeType("text/plain").build())
                    .build())
            .build();

    Metrics.recordAgentRequestSize("my-agent", userContent);

    MetricData metric = findMetricByName("gen_ai.agent.request.size");
    assertThat(metric.getUnit()).isEqualTo("By");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(10); // "hello" is 5, "world" is 5. Total 10.
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
        .isEqualTo("my-agent");
  }

  @Test
  public void recordAgentResponseSize_success() {
    Content responseContent = Content.fromParts(Part.fromText("response"));
    Event mockEvent1 =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("hi"))).build();
    Event mockEvent2 = Event.builder().author("my-agent").content(responseContent).build();

    Metrics.recordAgentResponseSize("my-agent", ImmutableList.of(mockEvent1, mockEvent2));

    MetricData metric = findMetricByName("gen_ai.agent.response.size");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(8); // "response" is 8.
  }

  @Test
  public void recordAgentWorkflowSteps_success() {
    Event event1 = Event.builder().author("my-agent").build();
    Event event2 = Event.builder().author("user").build();
    Event event3 = Event.builder().author("my-agent").build();

    Metrics.recordAgentWorkflowSteps("my-agent", ImmutableList.of(event1, event2, event3));

    MetricData metric = findMetricByName("gen_ai.agent.workflow.steps");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(2); // 2 events by "my-agent".
  }

  @Test
  public void recordToolRequestSize_success() {
    Metrics.recordToolRequestSize("my-tool", "my-agent", ImmutableMap.of("arg1", "value1"));

    MetricData metric = findMetricByName("gen_ai.tool.request.size");
    assertThat(metric.getUnit()).isEqualTo("By");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(6); // "value1" is 6.
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
        .isEqualTo("my-agent");
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
        .isEqualTo("my-tool");
  }

  @Test
  public void recordToolResponseSize_success() {
    Content responseContent = Content.fromParts(Part.fromText("response"));
    Event responseEvent = Event.builder().author("my-tool").content(responseContent).build();

    Metrics.recordToolResponseSize("my-tool", "my-agent", responseEvent);

    MetricData metric = findMetricByName("gen_ai.tool.response.size");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getSum()).isEqualTo(8); // "response" is 8.
    assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
        .isEqualTo("my-agent");
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
