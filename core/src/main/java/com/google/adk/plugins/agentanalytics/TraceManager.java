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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Manages the BQAA-internal execution tree of span IDs for one invocation.
 *
 * <p>No OpenTelemetry spans are created: records are ID-only, so a host with an SDK exporter
 * configured never receives a duplicate plugin-owned span tree next to ADK's framework spans.
 * Ambient OpenTelemetry context is still consulted for the {@code trace_id} (and the invocation
 * root's {@code span_id}) so BigQuery rows stay joinable to Cloud Trace.
 *
 * <p>Span records are kept in per-branch stacks keyed by {@link InvocationContext#branch()}.
 * Concurrently scheduled {@code ParallelAgent} branches (which share an invocation ID but carry
 * distinct branch strings) never touch each other's stacks, so a branch completing first can no
 * longer pop another branch's span. Within one branch, agent and model spans execute sequentially
 * and use top-of-stack semantics, but ADK executes an event's function calls CONCURRENTLY by
 * default: tool spans therefore carry an operation identity (the function-call ID) plus a parent
 * captured at push time, and are popped by identity rather than stack position. Pops additionally
 * verify the record's {@code kind}, so an error callback firing without its matching push cannot
 * pop an unrelated record.
 */
public final class TraceManager {
  private static final Logger logger = Logger.getLogger(TraceManager.class.getName());

  static final String DEFAULT_ROOT_AGENT_NAME = "_bq_analytics_root_agent_name";
  private static final String ROOT_BRANCH = "";

  // Span records keyed by ADK branch string ("" for the invocation root / unbranched flows).
  private final ConcurrentHashMap<String, Deque<SpanRecord>> stacksByBranch =
      new ConcurrentHashMap<>();
  private volatile String rootAgentName = DEFAULT_ROOT_AGENT_NAME;
  private volatile String activeInvocationId = "_bq_analytics_active_invocation_id";
  // Trace ID inherited from the ambient OpenTelemetry span at invocation-root seeding time; null
  // when no ambient context existed (getTraceId then falls back to the invocation ID).
  private volatile @Nullable String traceId;

  TraceManager() {}

  @AutoValue
  abstract static class SpanRecord {
    abstract String spanId();

    /** Span kind ("invocation", "agent:NAME", "llm_request", "tool") for ownership-checked pops. */
    abstract String kind();

    /**
     * Identity of the operation this span belongs to (the tool's function-call ID), or null for
     * spans whose kind executes sequentially within a branch. ADK runs an event's function calls
     * concurrently by default, all under the same branch, so tool spans must be popped by operation
     * identity rather than stack position.
     */
    abstract @Nullable String operationId();

    /** The enclosing span at push time, so concurrent siblings do not corrupt parent linkage. */
    abstract @Nullable String parentSpanId();

    abstract Instant startTime();

    abstract AtomicReference<Instant> firstTokenTime();

    static SpanRecord create(
        String spanId,
        String kind,
        @Nullable String operationId,
        @Nullable String parentSpanId,
        Instant startTime) {
      return new AutoValue_TraceManager_SpanRecord(
          spanId, kind, operationId, parentSpanId, startTime, new AtomicReference<>());
    }
  }

  @AutoValue
  abstract static class RecordData {
    abstract String spanId();

    abstract Optional<String> parentSpanId();

    abstract Duration duration();

    static RecordData create(String spanId, @Nullable String parentSpanId, Duration duration) {
      return new AutoValue_TraceManager_RecordData(
          spanId, Optional.ofNullable(parentSpanId), duration);
    }
  }

  @AutoValue
  abstract static class SpanIds {
    abstract Optional<String> spanId();

    abstract Optional<String> parentSpanId();

    static SpanIds create(@Nullable String spanId, @Nullable String parentSpanId) {
      return new AutoValue_TraceManager_SpanIds(
          Optional.ofNullable(spanId), Optional.ofNullable(parentSpanId));
    }
  }

  public String getRootAgentName() {
    return rootAgentName;
  }

  public void initTrace(InvocationContext context) {
    var rootAgent = context.agent().rootAgent();
    if (rootAgent != null && rootAgent.name() != null) {
      this.rootAgentName = rootAgent.name();
    }
  }

  /**
   * Sets the root agent name from the invocation context if it is still the sentinel default.
   * Null-safe: workflow-driven callbacks with no current agent leave the sentinel in place for a
   * later event to resolve.
   */
  public void initTraceIfNeeded(InvocationContext context) {
    if (!Objects.equals(rootAgentName, DEFAULT_ROOT_AGENT_NAME)) {
      return;
    }
    try {
      initTrace(context);
    } catch (RuntimeException e) {
      // Leave the sentinel; a subsequent event may be able to resolve the root agent.
    }
  }

  public String getTraceId(InvocationContext context) {
    String tid = this.traceId;
    if (tid != null) {
      return tid;
    }
    // Fallback to the ambient span.
    SpanContext ambient = Span.current().getSpanContext();
    if (ambient.isValid()) {
      return ambient.getTraceId();
    }
    // Fallback to the invocation ID.
    return context.invocationId();
  }

  private static String newSpanId() {
    // Aligns with the OpenTelemetry span ID format (16 hex chars).
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private static String branchKey(InvocationContext context) {
    try {
      return context.branch().orElse(ROOT_BRANCH);
    } catch (RuntimeException e) {
      return ROOT_BRANCH;
    }
  }

  private Deque<SpanRecord> stackFor(String branch) {
    return stacksByBranch.computeIfAbsent(branch, b -> new ConcurrentLinkedDeque<>());
  }

  /**
   * Returns the stacks from the given branch up its ancestor chain to the root branch, most
   * specific first. A branch "p.a" resolves to ["p.a", "p", ""] (existing stacks only).
   */
  private List<Deque<SpanRecord>> branchChain(String branch) {
    List<Deque<SpanRecord>> chain = new ArrayList<>();
    String key = branch;
    while (true) {
      Deque<SpanRecord> stack = stacksByBranch.get(key);
      if (stack != null) {
        chain.add(stack);
      }
      if (key.isEmpty()) {
        break;
      }
      int lastDot = key.lastIndexOf('.');
      key = lastDot >= 0 ? key.substring(0, lastDot) : ROOT_BRANCH;
    }
    return chain;
  }

  /** Pushes an ID-only span record onto the calling branch's stack. No OTel span is created. */
  @CanIgnoreReturnValue
  public String pushSpan(InvocationContext context, String spanName) {
    return pushSpanRecord(context, spanName, null).spanId();
  }

  /**
   * Pushes an ID-only span record with an optional operation identity (the tool's function-call
   * ID). The parent span is resolved and stored at push time; when an operation identity is given,
   * concurrent sibling records of the same kind are skipped so a second tool starting while the
   * first is still running parents to the enclosing agent span, not to its sibling.
   */
  @CanIgnoreReturnValue
  SpanRecord pushSpanRecord(
      InvocationContext context, String spanName, @Nullable String operationId) {
    String branch = branchKey(context);
    String parentSpanId = findParentSpanId(branch, operationId == null ? null : spanName);
    SpanRecord record =
        SpanRecord.create(newSpanId(), spanName, operationId, parentSpanId, Instant.now());
    stackFor(branch).addLast(record);
    return record;
  }

  /**
   * Newest record in the branch chain to serve as a new span's parent. When {@code
   * skipConcurrentKind} is set, records of that kind carrying an operation identity are skipped:
   * they are concurrent siblings of the span being pushed, not its ancestors.
   */
  private @Nullable String findParentSpanId(String branch, @Nullable String skipConcurrentKind) {
    for (Deque<SpanRecord> stack : branchChain(branch)) {
      Iterator<SpanRecord> descending = stack.descendingIterator();
      while (descending.hasNext()) {
        SpanRecord record = descending.next();
        if (skipConcurrentKind != null
            && record.kind().equals(skipConcurrentKind)
            && record.operationId() != null) {
          continue;
        }
        return record.spanId();
      }
    }
    return null;
  }

  /**
   * Records the ambient OpenTelemetry span's IDs as the invocation root without creating or owning
   * any span, so plugin-emitted rows correlate with the host's existing tracing.
   */
  @CanIgnoreReturnValue
  public String attachCurrentSpan(InvocationContext context) {
    SpanContext ambient = Span.current().getSpanContext();
    String spanId;
    if (ambient.isValid()) {
      spanId = ambient.getSpanId();
      this.traceId = ambient.getTraceId();
    } else {
      spanId = newSpanId();
    }
    stackFor(branchKey(context))
        .addLast(SpanRecord.create(spanId, "invocation", null, null, Instant.now()));
    return spanId;
  }

  public void ensureInvocationSpan(InvocationContext context) {
    String currentInv = context.invocationId();

    if (hasAnyRecords()) {
      if (currentInv.equals(activeInvocationId)) {
        return;
      }
      logger.fine("Clearing stale span records from previous invocation.");
      clearStack();
    }

    activeInvocationId = currentInv;
    // Reset the inherited trace ID so a new invocation without ambient context does not reuse the
    // previous invocation's trace ID (attachCurrentSpan re-captures it when ambient is valid).
    this.traceId = null;

    if (Span.current().getSpanContext().isValid()) {
      attachCurrentSpan(context);
    } else {
      pushSpan(context, "invocation");
    }
  }

  private boolean hasAnyRecords() {
    for (Deque<SpanRecord> stack : stacksByBranch.values()) {
      if (!stack.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Pops the calling branch's top span record if its kind matches {@code expectedKindPrefix}.
   *
   * <p>The branch scoping prevents a concurrently completing {@code ParallelAgent} branch from
   * popping another branch's span; the kind check prevents a mismatched pop (e.g. an error callback
   * firing without its corresponding push) from corrupting the stack.
   */
  @CanIgnoreReturnValue
  public Optional<RecordData> popSpan(InvocationContext context, String expectedKindPrefix) {
    return popSpan(context, expectedKindPrefix, null);
  }

  /**
   * Pops the calling branch's matching span record.
   *
   * <p>With an {@code operationId}, the record is located by kind AND operation identity
   * (newest-first) rather than stack position: ADK executes an event's function calls concurrently
   * by default within one branch, so a completion must remove its own record even when a sibling
   * tool's record sits above it. Without an {@code operationId}, only the branch's top record is
   * popped, and only when its kind matches.
   */
  @CanIgnoreReturnValue
  public Optional<RecordData> popSpan(
      InvocationContext context, String expectedKindPrefix, @Nullable String operationId) {
    Deque<SpanRecord> stack = stacksByBranch.get(branchKey(context));
    if (stack == null || stack.isEmpty()) {
      return Optional.empty();
    }
    if (operationId != null) {
      Iterator<SpanRecord> descending = stack.descendingIterator();
      while (descending.hasNext()) {
        SpanRecord record = descending.next();
        if (record.kind().startsWith(expectedKindPrefix)
            && operationId.equals(record.operationId())) {
          descending.remove();
          return Optional.of(
              RecordData.create(
                  record.spanId(),
                  record.parentSpanId(),
                  Duration.between(record.startTime(), Instant.now())));
        }
      }
      logger.fine(
          "No span with kind prefix '"
              + expectedKindPrefix
              + "' and operation ID '"
              + operationId
              + "' to pop.");
      return Optional.empty();
    }
    SpanRecord top = stack.peekLast();
    if (top == null) {
      return Optional.empty();
    }
    if (!top.kind().startsWith(expectedKindPrefix)) {
      logger.fine(
          "Not popping span of kind '"
              + top.kind()
              + "': expected kind prefix '"
              + expectedKindPrefix
              + "'.");
      return Optional.empty();
    }
    SpanRecord record = stack.pollLast();
    if (record == null) {
      return Optional.empty();
    }
    return Optional.of(
        RecordData.create(
            record.spanId(),
            record.parentSpanId(),
            Duration.between(record.startTime(), Instant.now())));
  }

  public void clearStack() {
    // Records are ID-only; there are no OTel spans to end.
    stacksByBranch.clear();
  }

  public SpanIds getCurrentSpanAndParent(InvocationContext context) {
    List<Deque<SpanRecord>> chain = branchChain(branchKey(context));

    SpanRecord current = null;
    int currentChainIndex = -1;
    for (int i = 0; i < chain.size(); i++) {
      SpanRecord top = chain.get(i).peekLast();
      if (top != null) {
        current = top;
        currentChainIndex = i;
        break;
      }
    }
    if (current == null) {
      return SpanIds.create(null, null);
    }

    // Parent: the record below the current one in its own stack, else the nearest non-empty
    // ancestor branch's top (a branch's first span parents to the invocation root).
    SpanRecord parent = null;
    Iterator<SpanRecord> descending = chain.get(currentChainIndex).descendingIterator();
    if (descending.hasNext()) {
      descending.next(); // Skip the current record.
      if (descending.hasNext()) {
        parent = descending.next();
      }
    }
    if (parent == null) {
      for (int i = currentChainIndex + 1; i < chain.size(); i++) {
        SpanRecord top = chain.get(i).peekLast();
        if (top != null) {
          parent = top;
          break;
        }
      }
    }
    return SpanIds.create(current.spanId(), parent == null ? null : parent.spanId());
  }

  public Optional<String> getCurrentSpanId(InvocationContext context) {
    for (Deque<SpanRecord> stack : branchChain(branchKey(context))) {
      SpanRecord top = stack.peekLast();
      if (top != null) {
        return Optional.of(top.spanId());
      }
    }
    return Optional.empty();
  }

  private Optional<SpanRecord> findSpanRecord(String spanId) {
    for (Deque<SpanRecord> stack : stacksByBranch.values()) {
      // Search from newest to oldest for efficiency.
      Iterator<SpanRecord> iterator = stack.descendingIterator();
      while (iterator.hasNext()) {
        SpanRecord record = iterator.next();
        if (record.spanId().equals(spanId)) {
          return Optional.of(record);
        }
      }
    }
    return Optional.empty();
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
