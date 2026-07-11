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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TraceManagerTest {
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();
  private InvocationContext mockContext;
  private BaseAgent mockAgent;
  private Map<String, Object> callbackData;
  private TraceManager traceManager;
  private Tracer tracer;

  @Before
  public void setUp() {
    tracer = openTelemetryRule.getOpenTelemetry().getTracer("test");
    callbackData = new ConcurrentHashMap<>();
    mockAgent =
        new BaseAgent("test-agent", "desc", null, null, null) {
          @Override
          protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.empty();
          }

          @Override
          protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
          }
        };
    mockContext = branchContext("test-invocation-id", null);
    traceManager = new TraceManager();
  }

  private InvocationContext branchContext(String invocationId, @Nullable String branch) {
    InvocationContext ctx = mock(InvocationContext.class);
    when(ctx.callbackContextData()).thenReturn(callbackData);
    when(ctx.invocationId()).thenReturn(invocationId);
    when(ctx.branch()).thenReturn(Optional.ofNullable(branch));
    when(ctx.agent()).thenReturn(mockAgent);
    return ctx;
  }

  @Test
  public void pushSpan_createsValidSpanId() {
    String spanId = traceManager.pushSpan(mockContext, "test-span");
    assertNotNull(spanId);
    assertTrue(spanId.length() >= 16);
  }

  @Test
  public void pushSpan_maintainsParentChildRelationship() {
    String parentId = traceManager.pushSpan(mockContext, "parent");
    String childId = traceManager.pushSpan(mockContext, "child");

    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent(mockContext);
    assertEquals(childId, ids.spanId().orElse(null));
    assertEquals(parentId, ids.parentSpanId().orElse(null));
  }

  @Test
  public void popSpan_removesFromStack() {
    String parentId = traceManager.pushSpan(mockContext, "parent");
    traceManager.pushSpan(mockContext, "child");

    Optional<TraceManager.RecordData> popped = traceManager.popSpan(mockContext, "");
    assertTrue(popped.isPresent());
    assertFalse(popped.get().duration().isNegative());

    String currentId = traceManager.getCurrentSpanId(mockContext).orElse(null);
    assertEquals(parentId, currentId);

    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent(mockContext);
    assertEquals(parentId, ids.spanId().orElse(null));
    assertFalse(ids.parentSpanId().isPresent());
  }

  @Test
  public void popSpan_kindMismatch_doesNotPop() {
    traceManager.pushSpan(mockContext, "agent:root");

    // An error callback firing without its matching push must not pop an unrelated record.
    assertFalse(traceManager.popSpan(mockContext, "llm_request").isPresent());

    Optional<TraceManager.RecordData> popped = traceManager.popSpan(mockContext, "agent:");
    assertTrue(popped.isPresent());
  }

  @Test
  public void parallelBranches_outOfOrderCompletion_preserveSpanOwnership() {
    // Same invocation ID across all branches, exactly like ParallelAgent's merged sub-agents.
    InvocationContext root = branchContext("inv", null);
    traceManager.ensureInvocationSpan(root);
    String rootSpan = traceManager.getCurrentSpanId(root).orElse(null);
    assertNotNull(rootSpan);

    InvocationContext branchA = branchContext("inv", "par.agentA");
    InvocationContext branchB = branchContext("inv", "par.agentB");
    // Branch A starts first, branch B second.
    String spanA = traceManager.pushSpan(branchA, "agent:agentA");
    String spanB = traceManager.pushSpan(branchB, "agent:agentB");

    // Rows logged concurrently in each branch see their own span, parented to the invocation root.
    TraceManager.SpanIds idsA = traceManager.getCurrentSpanAndParent(branchA);
    TraceManager.SpanIds idsB = traceManager.getCurrentSpanAndParent(branchB);
    assertEquals(spanA, idsA.spanId().orElse(null));
    assertEquals(rootSpan, idsA.parentSpanId().orElse(null));
    assertEquals(spanB, idsB.spanId().orElse(null));
    assertEquals(rootSpan, idsB.parentSpanId().orElse(null));

    // Branch A completes FIRST (out of order relative to a global LIFO): it must pop its OWN span,
    // not branch B's.
    Optional<TraceManager.RecordData> poppedA = traceManager.popSpan(branchA, "agent:");
    assertTrue(poppedA.isPresent());
    assertEquals(spanA, poppedA.get().spanId());

    // Branch B still owns its span and pops it on its own completion.
    assertEquals(spanB, traceManager.getCurrentSpanId(branchB).orElse(null));
    Optional<TraceManager.RecordData> poppedB = traceManager.popSpan(branchB, "agent:");
    assertTrue(poppedB.isPresent());
    assertEquals(spanB, poppedB.get().spanId());

    // The invocation root span remains for INVOCATION_COMPLETED.
    assertEquals(rootSpan, traceManager.getCurrentSpanId(root).orElse(null));
  }

  @Test
  public void nestedSpansWithinBranch_parentWithinOwnStackFirst() {
    InvocationContext root = branchContext("inv", null);
    traceManager.ensureInvocationSpan(root);

    InvocationContext branchA = branchContext("inv", "par.agentA");
    String agentSpan = traceManager.pushSpan(branchA, "agent:agentA");
    String llmSpan = traceManager.pushSpan(branchA, "llm_request");

    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent(branchA);
    assertEquals(llmSpan, ids.spanId().orElse(null));
    assertEquals(agentSpan, ids.parentSpanId().orElse(null));
  }

  @Test
  public void spanLifecycle_exportsZeroPluginOwnedSpans() {
    // Full push/pop lifecycle across kinds: the manager must never create real OTel spans, so a
    // host with an SDK exporter configured receives no duplicate plugin-owned span tree.
    traceManager.ensureInvocationSpan(mockContext);
    traceManager.pushSpan(mockContext, "agent:test-agent");
    traceManager.pushSpan(mockContext, "llm_request");
    traceManager.popSpan(mockContext, "llm_request");
    traceManager.pushSpan(mockContext, "tool");
    traceManager.popSpan(mockContext, "tool");
    traceManager.popSpan(mockContext, "agent:");
    traceManager.popSpan(mockContext, "invocation");
    traceManager.clearStack();

    assertTrue("BQAA must not export plugin-owned spans", openTelemetryRule.getSpans().isEmpty());
  }

  @Test
  public void ensureInvocationSpan_isIdempotent() {
    traceManager.ensureInvocationSpan(mockContext);
    String id1 = traceManager.getCurrentSpanId(mockContext).orElse(null);

    traceManager.ensureInvocationSpan(mockContext);
    String id2 = traceManager.getCurrentSpanId(mockContext).orElse(null);

    assertEquals(id1, id2);
  }

  @Test
  public void ensureInvocationSpan_clearsStaleRecords() {
    Span ambientSpan = tracer.spanBuilder("ambient").startSpan();
    try (Scope scope = ambientSpan.makeCurrent()) {
      traceManager.ensureInvocationSpan(mockContext);
    } finally {
      ambientSpan.end();
    }
    String id1 = traceManager.getCurrentSpanId(mockContext).orElse(null);
    // Create a new context with same callback data but different invocation ID
    InvocationContext mockContext2 = branchContext("new-invocation-id", null);
    Span ambientSpan2 = tracer.spanBuilder("ambient2").startSpan();
    try (Scope scope = ambientSpan2.makeCurrent()) {
      traceManager.ensureInvocationSpan(mockContext2);
    } finally {
      ambientSpan2.end();
    }
    String id2 = traceManager.getCurrentSpanId(mockContext2).orElse(null);

    assertNotEquals(id1, id2);
    // Should only have 1 record now
    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent(mockContext2);
    assertFalse(ids.parentSpanId().isPresent());
  }

  @Test
  public void ensureInvocationSpan_newInvocationWithoutAmbient_doesNotReuseOldTraceId() {
    Span ambientSpan = tracer.spanBuilder("ambient").startSpan();
    try (Scope scope = ambientSpan.makeCurrent()) {
      traceManager.ensureInvocationSpan(mockContext);
    } finally {
      ambientSpan.end();
    }
    String firstTraceId = traceManager.getTraceId(mockContext);
    assertEquals(ambientSpan.getSpanContext().getTraceId(), firstTraceId);

    // Second invocation seeds with no ambient context: it must fall back to its own invocation ID
    // rather than reusing the previous invocation's inherited trace ID.
    InvocationContext mockContext2 = branchContext("new-invocation-id", null);
    try (Scope ignored = Context.root().makeCurrent()) {
      traceManager.ensureInvocationSpan(mockContext2);
      assertEquals("new-invocation-id", traceManager.getTraceId(mockContext2));
    }
  }

  @Test
  public void attachCurrentSpan_usesAmbientSpan() {
    Span ambientSpan = tracer.spanBuilder("ambient").startSpan();
    try (Scope scope = ambientSpan.makeCurrent()) {
      String attachedId = traceManager.attachCurrentSpan(mockContext);
      String expectedId = ambientSpan.getSpanContext().getSpanId();
      assertEquals(expectedId, attachedId);
    } finally {
      ambientSpan.end();
    }
  }

  @Test
  public void getTraceId_returnsCurrentTraceId() {
    traceManager.pushSpan(mockContext, "test");
    String traceId = traceManager.getTraceId(mockContext);
    assertNotNull(traceId);
    if (traceId.equals("test-invocation-id")) {
      assertEquals("test-invocation-id", traceId);
    } else {
      assertTrue(traceId.matches("[0-9a-f]{32}"));
    }
  }

  @Test
  public void getTraceId_returnsInvocationId_whenRecordsIsEmpty() {
    // Pin to the root context so an ambient span leaked by another test sharing this JVM cannot
    // make Span.current() valid and divert getTraceId away from the invocation-id fallback.
    try (Scope ignored = Context.root().makeCurrent()) {
      String traceId = traceManager.getTraceId(mockContext);
      assertEquals("test-invocation-id", traceId);
    }
  }

  @Test
  public void getTraceId_returnsAmbientTraceId_whenRecordsIsEmpty_butAmbientIsPresent() {
    Span ambientSpan = tracer.spanBuilder("ambient").startSpan();
    try (Scope scope = ambientSpan.makeCurrent()) {
      String expectedTraceId = ambientSpan.getSpanContext().getTraceId();
      String traceId = traceManager.getTraceId(mockContext);
      assertEquals(expectedTraceId, traceId);
    } finally {
      ambientSpan.end();
    }
  }

  @Test
  public void attachCurrentSpan_worksWithoutAmbientSpan() {
    try (Scope ignored = Context.root().makeCurrent()) {
      String attachedId = traceManager.attachCurrentSpan(mockContext);
      assertNotNull(attachedId);
      assertEquals(16, attachedId.length());

      // Verify it's in records
      assertEquals(attachedId, traceManager.getCurrentSpanId(mockContext).orElse(null));
    }
  }

  @Test
  public void getTraceId_fallsBackToInvocationId_whenNoAmbientContext() {
    // Pin to the root context so a span leaked by another test sharing this JVM does not make the
    // ambient fallback valid; attachCurrentSpan with no ambient context records a locally
    // generated span ID and no trace ID, exercising the invocation-id fallback.
    try (Scope ignored = Context.root().makeCurrent()) {
      traceManager.attachCurrentSpan(mockContext);

      String traceId = traceManager.getTraceId(mockContext);
      assertEquals("test-invocation-id", traceId);
    }
  }

  @Test
  public void popSpan_returnsEmpty_whenRecordsIsEmpty() {
    Optional<TraceManager.RecordData> popped = traceManager.popSpan(mockContext, "");
    assertFalse(popped.isPresent());
  }

  @Test
  public void clearStack_doesNothing_whenRecordsIsEmpty() {
    traceManager.clearStack();
    assertTrue(traceManager.getCurrentSpanAndParent(mockContext).spanId().isEmpty());
  }

  @Test
  public void initTraceIfNeeded_setsRootAgentNameFromContext() {
    assertEquals(TraceManager.DEFAULT_ROOT_AGENT_NAME, traceManager.getRootAgentName());
    traceManager.initTraceIfNeeded(mockContext);
    assertEquals("test-agent", traceManager.getRootAgentName());
  }

  @Test
  public void initTraceIfNeeded_nullAgent_keepsSentinel() {
    InvocationContext ctx = mock(InvocationContext.class);
    when(ctx.agent()).thenReturn(null);
    traceManager.initTraceIfNeeded(ctx);
    assertEquals(TraceManager.DEFAULT_ROOT_AGENT_NAME, traceManager.getRootAgentName());
  }

  @Test
  public void initTrace_nullRootAgent_keepsSentinelWithoutThrowing() {
    // rootAgent() may be null (e.g. workflow-driven callbacks with no resolved root); initTrace
    // must
    // guard on it directly rather than NPE. Called directly (not via initTraceIfNeeded, whose
    // try/catch would otherwise mask a missing rootAgent null-check).
    BaseAgent agentWithNullRoot = mock(BaseAgent.class);
    when(agentWithNullRoot.rootAgent()).thenReturn(null);
    InvocationContext ctx = mock(InvocationContext.class);
    when(ctx.agent()).thenReturn(agentWithNullRoot);

    traceManager.initTrace(ctx);

    assertEquals(TraceManager.DEFAULT_ROOT_AGENT_NAME, traceManager.getRootAgentName());
  }

  @Test
  public void concurrentToolsInOneBranch_popByOperationIdentity() {
    // ADK executes an event's function calls concurrently by default, all under the SAME branch:
    // branch + kind alone cannot discriminate, so tool records carry an operation identity.
    InvocationContext ctx = branchContext("inv", null);
    traceManager.ensureInvocationSpan(ctx);
    String agentSpan = traceManager.pushSpan(ctx, "agent:worker");

    TraceManager.SpanRecord toolA = traceManager.pushSpanRecord(ctx, "tool", "fc-a");
    TraceManager.SpanRecord toolB = traceManager.pushSpanRecord(ctx, "tool", "fc-b");

    // Both concurrent tools parent to the enclosing agent span, not to each other.
    assertEquals(agentSpan, toolA.parentSpanId());
    assertEquals(agentSpan, toolB.parentSpanId());

    // Tool A completes FIRST even though tool B sits above it on the stack: identity pop must
    // remove A's record, not B's.
    Optional<TraceManager.RecordData> poppedA = traceManager.popSpan(ctx, "tool", "fc-a");
    assertTrue(poppedA.isPresent());
    assertEquals(toolA.spanId(), poppedA.get().spanId());
    assertEquals(agentSpan, poppedA.get().parentSpanId().orElse(null));

    Optional<TraceManager.RecordData> poppedB = traceManager.popSpan(ctx, "tool", "fc-b");
    assertTrue(poppedB.isPresent());
    assertEquals(toolB.spanId(), poppedB.get().spanId());
    assertEquals(agentSpan, poppedB.get().parentSpanId().orElse(null));

    // The agent span remains for AGENT_COMPLETED.
    assertEquals(agentSpan, traceManager.getCurrentSpanId(ctx).orElse(null));
  }

  @Test
  public void popSpan_unknownOperationId_popsNothing() {
    InvocationContext ctx = branchContext("inv", null);
    traceManager.pushSpanRecord(ctx, "tool", "fc-a");

    assertFalse(traceManager.popSpan(ctx, "tool", "fc-unknown").isPresent());
    assertTrue(traceManager.popSpan(ctx, "tool", "fc-a").isPresent());
  }
}
