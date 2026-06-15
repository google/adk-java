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

package com.google.adk.plugins.agentanalytics;

import com.google.adk.agents.InvocationContext;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Manages OpenTelemetry-style trace and span context using InvocationContext callback data.
 *
 * <p>Uses a stack of SpanRecord objects to keep span, ID, ownership, and timing in sync.
 */
public final class TraceManager {
  private static final Logger logger = Logger.getLogger(TraceManager.class.getName());

  private final ConcurrentLinkedDeque<SpanRecord> records = new ConcurrentLinkedDeque<>();
  private String rootAgentName = "_bq_analytics_root_agent_name";
  private String activeInvocationId = "_bq_analytics_active_invocation_id";

  private final Tracer tracer;

  TraceManager() {
    this(GlobalOpenTelemetry.getTracer("google.adk.plugins.bigquery_agent_analytics"));
  }

  TraceManager(Tracer tracer) {
    this.tracer = tracer;
  }

  @AutoValue
  abstract static class SpanRecord {
    abstract Span span();

    abstract String spanId();

    abstract boolean ownsSpan();

    abstract Instant startTime();

    abstract AtomicReference<Instant> firstTokenTime();

    static SpanRecord create(Span span, String spanId, boolean ownsSpan, Instant startTime) {
      return new AutoValue_TraceManager_SpanRecord(
          span, spanId, ownsSpan, startTime, new AtomicReference<>());
    }
  }

  @AutoValue
  abstract static class RecordData {
    abstract String spanId();

    abstract Duration duration();

    static RecordData create(String spanId, Duration duration) {
      return new AutoValue_TraceManager_RecordData(spanId, duration);
    }
  }

  @AutoValue
  abstract static class SpanIds {
    abstract Optional<String> spanId();

    abstract Optional<String> parentSpanId();

    static SpanIds create(String spanId, String parentSpanId) {
      return new AutoValue_TraceManager_SpanIds(
          Optional.ofNullable(spanId), Optional.ofNullable(parentSpanId));
    }
  }

  public String getRootAgentName() {
    return rootAgentName;
  }

  public void initTrace(InvocationContext context) {
    String rootAgentName = context.agent().rootAgent().name();
    this.rootAgentName = rootAgentName;
  }

  public String getTraceId(InvocationContext context) {
    if (!records.isEmpty()) {
      Span currentSpan = records.peekLast().span();
      if (currentSpan.getSpanContext().isValid()) {
        return currentSpan.getSpanContext().getTraceId();
      }
    }
    // Fallback to the ambient span.
    SpanContext ambient = Span.current().getSpanContext();
    if (ambient.isValid()) {
      return ambient.getTraceId();
    }
    // Fallback to the invocation ID.
    return context.invocationId();
  }

  public boolean hasAmbientSpan() {
    return Span.current().getSpanContext().isValid();
  }

  @CanIgnoreReturnValue
  public String pushSpan(String spanName) {
    Context parentContext = Context.current();
    if (!records.isEmpty()) {
      Span parentSpan = records.peekLast().span();
      if (parentSpan.getSpanContext().isValid()) {
        parentContext = parentContext.with(parentSpan);
      }
    }

    Span span = tracer.spanBuilder(spanName).setParent(parentContext).startSpan();
    String spanIdStr;
    if (span.getSpanContext().isValid()) {
      spanIdStr = span.getSpanContext().getSpanId();
    } else {
      // This span id aligns with the OpenTelemetry Span ID format.
      spanIdStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    SpanRecord record = SpanRecord.create(span, spanIdStr, true, Instant.now());
    records.add(record);
    return spanIdStr;
  }

  @CanIgnoreReturnValue
  public String attachCurrentSpan() {
    Span span = Span.current();
    String spanIdStr;
    if (span.getSpanContext().isValid()) {
      spanIdStr = span.getSpanContext().getSpanId();
    } else {
      spanIdStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    SpanRecord record = SpanRecord.create(span, spanIdStr, false, Instant.now());
    records.add(record);
    return spanIdStr;
  }

  public void ensureInvocationSpan(InvocationContext context) {
    String currentInv = context.invocationId();

    if (!records.isEmpty()) {
      if (currentInv.equals(activeInvocationId)) {
        return;
      }
      logger.info("Clearing stale span records from previous invocation.");
      clearStack();
    }

    activeInvocationId = currentInv;

    Span ambient = Span.current();
    if (ambient.getSpanContext().isValid()) {
      attachCurrentSpan();
    } else {
      pushSpan("invocation");
    }
  }

  @CanIgnoreReturnValue
  public Optional<RecordData> popSpan() {
    if (records.isEmpty()) {
      return Optional.empty();
    }
    SpanRecord record = records.pollLast();
    if (record == null) {
      return Optional.empty();
    }
    Duration duration = Duration.between(record.startTime(), Instant.now());
    if (record.ownsSpan()) {
      record.span().end();
    }
    return Optional.of(RecordData.create(record.spanId(), duration));
  }

  public void clearStack() {
    for (SpanRecord record : records) {
      if (record.ownsSpan()) {
        record.span().end();
      }
    }
    records.clear();
  }

  public SpanIds getCurrentSpanAndParent() {
    if (records.isEmpty()) {
      return SpanIds.create(null, null);
    }

    String spanId = records.peekLast().spanId();
    String parentId =
        findRecord(records.descendingIterator(), record -> !record.spanId().equals(spanId))
            .map(SpanRecord::spanId)
            .orElse(null);
    return SpanIds.create(spanId, parentId);
  }

  Optional<SpanIds> getAmbientSpanAndParent() {
    Span ambient = Span.current();
    if (!ambient.getSpanContext().isValid()) {
      return Optional.empty();
    }
    String spanId = ambient.getSpanContext().getSpanId();
    String parentSpanId = null;
    if (ambient instanceof ReadableSpan readableSpan) {
      SpanContext parentCtx = readableSpan.getParentSpanContext();
      if (parentCtx != null && parentCtx.isValid()) {
        parentSpanId = parentCtx.getSpanId();
      }
    }
    return Optional.of(SpanIds.create(spanId, parentSpanId));
  }

  public Optional<String> getCurrentSpanId() {
    if (records.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(records.peekLast().spanId());
  }

  private Optional<SpanRecord> findRecord(
      Iterator<SpanRecord> iterator, Predicate<SpanRecord> predicate) {
    while (iterator.hasNext()) {
      SpanRecord record = iterator.next();
      if (predicate.test(record)) {
        return Optional.of(record);
      }
    }
    return Optional.empty();
  }

  private Optional<SpanRecord> findSpanRecord(String spanId) {
    // Search from newest to oldest for efficiency.
    return findRecord(records.descendingIterator(), record -> record.spanId().equals(spanId));
  }

  public void recordFirstToken(String spanId) {
    findSpanRecord(spanId)
        .ifPresent(record -> record.firstTokenTime().compareAndSet(null, Instant.now()));
  }

  public Optional<Instant> getStartTime(String spanId) {
    return findSpanRecord(spanId).map(SpanRecord::startTime);
  }

  public Optional<Instant> getFirstTokenTime(String spanId) {
    return findSpanRecord(spanId).map(record -> record.firstTokenTime().get());
  }
}
