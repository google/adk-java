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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * OpenTelemetry plumbing an application contributes to the providers {@link TelemetrySetup}
 * installs.
 *
 * <p>The three signals are independent. A caller that only exports metrics sets only {@link
 * #metricReaders()}; the signals it left out are empty, and {@link TelemetrySetup} builds no
 * provider of its own for them.
 */
@AutoValue
public abstract class OtelHooks {

  /** Span processors to add to the tracer provider. */
  public abstract ImmutableList<SpanProcessor> spanProcessors();

  /** Metric readers to register with the meter provider. */
  public abstract ImmutableList<MetricReader> metricReaders();

  /** Log record processors to add to the logger provider. */
  public abstract ImmutableList<LogRecordProcessor> logRecordProcessors();

  /** Returns a builder whose three signals all start out empty. */
  public static Builder builder() {
    return new AutoValue_OtelHooks.Builder()
        .spanProcessors(ImmutableList.of())
        .metricReaders(ImmutableList.of())
        .logRecordProcessors(ImmutableList.of());
  }

  /** Builder for {@link OtelHooks}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder spanProcessors(Iterable<? extends SpanProcessor> spanProcessors);

    @CanIgnoreReturnValue
    public abstract Builder metricReaders(Iterable<? extends MetricReader> metricReaders);

    @CanIgnoreReturnValue
    public abstract Builder logRecordProcessors(
        Iterable<? extends LogRecordProcessor> logRecordProcessors);

    public abstract OtelHooks build();
  }
}
