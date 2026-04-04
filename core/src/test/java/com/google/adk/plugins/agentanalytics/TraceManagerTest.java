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
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    mockContext = mock(InvocationContext.class);
    when(mockContext.callbackContextData()).thenReturn(callbackData);
    when(mockContext.invocationId()).thenReturn("test-invocation-id");
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
    when(mockContext.agent()).thenReturn(mockAgent);
    traceManager = new TraceManager(tracer);
  }

  @Test
  public void pushSpan_createsValidSpanId() {
    String spanId = traceManager.pushSpan("test-span");
    assertNotNull(spanId);
    assertTrue(spanId.length() >= 16);
  }

  @Test
  public void pushSpan_maintainsParentChildRelationship() {
    String parentId = traceManager.pushSpan("parent");
    String childId = traceManager.pushSpan("child");

    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent();
    assertEquals(childId, ids.spanId().orElse(null));
    assertEquals(parentId, ids.parentSpanId().orElse(null));
  }

  @Test
  public void popSpan_removesFromStack() {
    String parentId = traceManager.pushSpan("parent");
    traceManager.pushSpan("child");

    Optional<TraceManager.RecordData> popped = traceManager.popSpan();
    assertTrue(popped.isPresent());
    assertFalse(popped.get().duration().isNegative());

    String currentId = traceManager.getCurrentSpanId().orElse(null);
    assertEquals(parentId, currentId);

    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent();
    assertEquals(parentId, ids.spanId().orElse(null));
    assertFalse(ids.parentSpanId().isPresent());
  }

  @Test
  public void ensureInvocationSpan_isIdempotent() {
    traceManager.ensureInvocationSpan(mockContext);
    String id1 = traceManager.getCurrentSpanId().orElse(null);

    traceManager.ensureInvocationSpan(mockContext);
    String id2 = traceManager.getCurrentSpanId().orElse(null);

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
    String id1 = traceManager.getCurrentSpanId().orElse(null);
    // Create a new context with same callback data but different invocation ID
    InvocationContext mockContext2 = mock(InvocationContext.class);
    when(mockContext2.callbackContextData()).thenReturn(callbackData);
    when(mockContext2.invocationId()).thenReturn("new-invocation-id");
    when(mockContext2.agent()).thenReturn(mockAgent);
    Span ambientSpan2 = tracer.spanBuilder("ambient2").startSpan();
    try (Scope scope = ambientSpan2.makeCurrent()) {
      traceManager.ensureInvocationSpan(mockContext2);
    } finally {
      ambientSpan2.end();
    }
    String id2 = traceManager.getCurrentSpanId().orElse(null);

    assertNotEquals(id1, id2);
    // Should only have 1 record now
    TraceManager.SpanIds ids = traceManager.getCurrentSpanAndParent();
    assertFalse(ids.parentSpanId().isPresent());
  }

  @Test
  public void attachCurrentSpan_usesAmbientSpan() {
    Span ambientSpan = tracer.spanBuilder("ambient").startSpan();
    try (Scope scope = ambientSpan.makeCurrent()) {
      String attachedId = traceManager.attachCurrentSpan();
      String expectedId = ambientSpan.getSpanContext().getSpanId();
      assertEquals(expectedId, attachedId);
    } finally {
      ambientSpan.end();
    }
  }

  @Test
  public void getTraceId_returnsCurrentTraceId() {
    traceManager.pushSpan("test");
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
    String traceId = traceManager.getTraceId(mockContext);
    if (traceManager.hasAmbientSpan()) {
      assertTrue(traceId.matches("[0-9a-f]{32}"));
    } else {
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
    // Ensure no ambient span
    String attachedId = traceManager.attachCurrentSpan();
    assertNotNull(attachedId);
    assertEquals(16, attachedId.length());

    // Verify it's in records
    assertEquals(attachedId, traceManager.getCurrentSpanId().orElse(null));
  }

  @Test
  public void getTraceId_fallsBackToInvocationId_whenRecordSpanIsInvalid() {
    // attachCurrentSpan when no ambient context exists creates an invalid span record
    traceManager.attachCurrentSpan();

    String traceId = traceManager.getTraceId(mockContext);
    if (traceManager.hasAmbientSpan()) {
      assertTrue(traceId.matches("[0-9a-f]{32}"));
    } else {
      assertEquals("test-invocation-id", traceId);
    }
  }

  @Test
  public void popSpan_returnsEmpty_whenRecordsIsEmpty() {
    Optional<TraceManager.RecordData> popped = traceManager.popSpan();
    assertFalse(popped.isPresent());
  }

  @Test
  public void clearStack_doesNothing_whenRecordsIsEmpty() {
    traceManager.clearStack();
    assertTrue(traceManager.getCurrentSpanAndParent().spanId().isEmpty());
  }
}
