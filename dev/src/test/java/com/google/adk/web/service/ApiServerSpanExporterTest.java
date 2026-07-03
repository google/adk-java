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

package com.google.adk.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ApiServerSpanExporterTest {

  private static final AtomicLong ID_COUNTER = new AtomicLong();

  private SpanData mockSpan() {
    return mockSpan("some-span", null, null);
  }

  private SpanData mockSpan(String name, String eventId, String sessionId) {
    SpanData span = mock(SpanData.class);
    Attributes attrs = mock(Attributes.class);
    SpanContext spanContext = mock(SpanContext.class);

    long id = ID_COUNTER.incrementAndGet();
    when(span.getName()).thenReturn(name);
    when(span.getAttributes()).thenReturn(attrs);
    when(span.getSpanContext()).thenReturn(spanContext);
    when(spanContext.getTraceId()).thenReturn("trace-" + id);
    when(spanContext.getSpanId()).thenReturn("span-" + id);

    when(attrs.get(any()))
        .thenAnswer(
            invocation -> {
              AttributeKey<?> key = invocation.getArgument(0);
              if ("gcp.vertex.agent.event_id".equals(key.getKey())) {
                return eventId;
              }
              if ("gcp.vertex.agent.session_id".equals(key.getKey())) {
                return sessionId;
              }
              return null;
            });

    return span;
  }

  @Test
  void standardUsage_shouldStoreAndRetrieveData() {
    ApiServerSpanExporter exporter = new ApiServerSpanExporter();
    String eventId = "test-event";
    String sessionId = "test-session";

    SpanData callLlm = mockSpan("call_llm", eventId, sessionId);
    exporter.export(Collections.singletonList(callLlm));

    assertEquals(1, exporter.getAllExportedSpans().size());
    Map<String, Object> eventAttrs = exporter.getEventTraceAttributes(eventId);
    assertEquals(eventId, eventAttrs.get("gcp.vertex.agent.event_id"));
    assertEquals(callLlm.getSpanContext().getTraceId(), eventAttrs.get("trace_id"));

    Map<String, List<String>> sessionMap = exporter.getSessionToTraceIdsMap();
    assertTrue(sessionMap.containsKey(sessionId));
    assertTrue(sessionMap.get(sessionId).contains(callLlm.getSpanContext().getTraceId()));
  }

  @Test
  void eviction_shouldRespectRefCount() {
    // Limit to 2 spans
    ApiServerSpanExporter exporter =
        new ApiServerSpanExporter(
            ApiServerSpanExporterConfig.builder().maxSpansToKeep(Optional.of(2)).build());
    String eventId = "shared-event";

    // Export two spans with same eventId
    SpanData span1 = mockSpan("call_llm", eventId, "session1");
    SpanData span2 = mockSpan("tool_response", eventId, null);
    exporter.export(Collections.singletonList(span1));
    exporter.export(Collections.singletonList(span2));

    // Verify storage is present
    assertNotNull(exporter.getEventTraceAttributes(eventId));

    // Export 3rd span, triggering eviction of span1
    exporter.export(Collections.singletonList(mockSpan("other", null, null)));

    // eventId should still be there because span2 is still in memory (refCount=1)
    assertNotNull(exporter.getEventTraceAttributes(eventId));

    // Export 4th span, triggering eviction of span2
    exporter.export(Collections.singletonList(mockSpan("another", null, null)));

    // Now eventId storage should be gone
    assertNull(exporter.getEventTraceAttributes(eventId));
  }

  @Test
  void noArgConstructor_shouldKeepAllSpansByDefault() {
    ApiServerSpanExporter exporter = new ApiServerSpanExporter();
    // Default is unlimited; verify no eviction occurs.
    for (int i = 0; i < 1000; i++) {
      exporter.export(Collections.singletonList(mockSpan()));
    }
    assertEquals(1000, exporter.getAllExportedSpans().size());
  }

  @Test
  void export_shouldLimitSpans() {
    int maxSpans = 5;
    ApiServerSpanExporter exporter =
        new ApiServerSpanExporter(
            ApiServerSpanExporterConfig.builder().maxSpansToKeep(Optional.of(maxSpans)).build());

    for (int i = 0; i < 10; i++) {
      exporter.export(Collections.singletonList(mockSpan()));
    }

    assertEquals(maxSpans, exporter.getAllExportedSpans().size());
  }

  @Test
  void export_noLimit_shouldKeepAllSpans() {
    ApiServerSpanExporter exporter =
        new ApiServerSpanExporter(ApiServerSpanExporterConfig.builder().build());

    for (int i = 0; i < 100; i++) {
      exporter.export(Collections.singletonList(mockSpan()));
    }

    assertEquals(100, exporter.getAllExportedSpans().size());
  }

  @Test
  void configConstructor_shouldUseConfiguredLimits() {
    int maxSpans = 3;
    ApiServerSpanExporterConfig config =
        ApiServerSpanExporterConfig.builder().maxSpansToKeep(Optional.of(maxSpans)).build();
    ApiServerSpanExporter exporter = new ApiServerSpanExporter(config);

    for (int i = 0; i < 10; i++) {
      exporter.export(Collections.singletonList(mockSpan()));
    }

    assertEquals(maxSpans, exporter.getAllExportedSpans().size());
  }

  @Test
  void configBuilder_shouldRejectNonPositiveMaxSpans() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiServerSpanExporterConfig.builder().maxSpansToKeep(Optional.of(0)).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiServerSpanExporterConfig.builder().maxSpansToKeep(Optional.of(-1)).build());
  }

  @Test
  void shutdown_shouldClearStorage() {
    ApiServerSpanExporter exporter =
        new ApiServerSpanExporter(
            ApiServerSpanExporterConfig.builder().maxSpansToKeep(Optional.of(10)).build());
    exporter.export(Collections.singletonList(mockSpan()));

    assertEquals(1, exporter.getAllExportedSpans().size());

    exporter.shutdown();

    assertTrue(exporter.getAllExportedSpans().isEmpty());
    assertTrue(exporter.getSessionToTraceIdsMap().isEmpty());
    // eventIdTraceStorage is private but we can infer it should be cleared if we had access or
    // tests for it
  }
}
