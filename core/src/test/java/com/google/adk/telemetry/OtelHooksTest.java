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

import com.google.common.collect.ImmutableList;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class OtelHooksTest {

  @Test
  public void builder_leavesEverySignalEmpty() {
    OtelHooks hooks = OtelHooks.builder().build();

    assertThat(hooks.spanProcessors()).isEmpty();
    assertThat(hooks.metricReaders()).isEmpty();
    assertThat(hooks.logRecordProcessors()).isEmpty();
  }

  @Test
  public void builder_signalsAreIndependentOfEachOther() {
    MetricReader reader = InMemoryMetricReader.create();

    OtelHooks hooks = OtelHooks.builder().metricReaders(ImmutableList.of(reader)).build();

    assertThat(hooks.metricReaders()).containsExactly(reader);
    assertThat(hooks.spanProcessors()).isEmpty();
    assertThat(hooks.logRecordProcessors()).isEmpty();
  }

  @Test
  public void builder_copiesTheGivenIterable() {
    SpanProcessor spanProcessor = SimpleSpanProcessor.create(InMemorySpanExporter.create());
    List<SpanProcessor> mutable = new ArrayList<>();
    mutable.add(spanProcessor);

    OtelHooks hooks = OtelHooks.builder().spanProcessors(mutable).build();
    mutable.clear();

    assertThat(hooks.spanProcessors()).containsExactly(spanProcessor);
  }

  @Test
  public void builder_carriesAllThreeSignalsTogether() {
    SpanProcessor spanProcessor = SimpleSpanProcessor.create(InMemorySpanExporter.create());
    MetricReader reader = InMemoryMetricReader.create();
    LogRecordProcessor logRecordProcessor =
        SimpleLogRecordProcessor.create(InMemoryLogRecordExporter.create());

    OtelHooks hooks =
        OtelHooks.builder()
            .spanProcessors(ImmutableList.of(spanProcessor))
            .metricReaders(ImmutableList.of(reader))
            .logRecordProcessors(ImmutableList.of(logRecordProcessor))
            .build();

    assertThat(hooks.spanProcessors()).containsExactly(spanProcessor);
    assertThat(hooks.metricReaders()).containsExactly(reader);
    assertThat(hooks.logRecordProcessors()).containsExactly(logRecordProcessor);
  }
}
