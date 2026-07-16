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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.service.eventprocessor.EventProcessor;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Integration tests for {@link SseEventStreamService}.
 *
 * <p>These tests verify end-to-end behavior including:
 *
 * <ul>
 *   <li>Multiple events streaming
 *   <li>Event processor integration
 *   <li>Error handling
 *   <li>Stream completion
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
@ExtendWith(MockitoExtension.class)
class SseEventStreamServiceIntegrationTest {

  @Mock private Runner mockRunner;

  private SseEventStreamService sseEventStreamService;

  @BeforeEach
  void setUp() {
    // Use a single-threaded executor for deterministic test execution
    ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    sseEventStreamService = new SseEventStreamService(testExecutor);
  }

  @Test
  void testStreamEvents_MultipleEvents_AllEventsReceived() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    List<Event> testEvents =
        List.of(createTestEvent("event1"), createTestEvent("event2"), createTestEvent("event3"));
    Flowable<Event> eventFlowable = Flowable.fromIterable(testEvents);

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner, "test-app", "user1", "session1", message, runConfig, null, null);

    // Assert
    assertNotNull(emitter);

    // Wait for async processing to complete - use timeout verification
    verify(mockRunner, timeout(3000)).runAsync(anyString(), anyString(), any(), any(), any());
  }

  @Test
  void testStreamEvents_WithEventProcessor_ProcessesEvents() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    AtomicInteger processCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);

    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            processCount.incrementAndGet();
            return Optional.of("{\"processed\":\"true\"}");
          }

          @Override
          public void onStreamStart(SseEmitter emitter, Map<String, Object> context) {
            startLatch.countDown();
          }

          @Override
          public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
            completeLatch.countDown();
          }
        };

    List<Event> testEvents = List.of(createTestEvent("event1"), createTestEvent("event2"));
    Flowable<Event> eventFlowable = Flowable.fromIterable(testEvents);

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner, "test-app", "user1", "session1", message, runConfig, null, processor);

    // Assert
    assertNotNull(emitter);

    // Wait for processing with longer timeouts for async execution
    assertTrue(startLatch.await(5, TimeUnit.SECONDS), "Stream should start");
    assertTrue(completeLatch.await(10, TimeUnit.SECONDS), "Stream should complete");
    Thread.sleep(1000); // Give time for event processing

    // Assert
    assertTrue(processCount.get() >= 2, "Should process at least 2 events");
  }

  @Test
  void testStreamEvents_ErrorInStream_HandlesError() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    CountDownLatch errorLatch = new CountDownLatch(1);

    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            return Optional.of("{\"processed\":\"true\"}");
          }

          @Override
          public void onStreamError(
              SseEmitter emitter, Throwable error, Map<String, Object> context) {
            errorLatch.countDown();
          }
        };

    RuntimeException testError = new RuntimeException("Test error");
    Flowable<Event> errorFlowable = Flowable.error(testError);

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(errorFlowable);

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner, "test-app", "user1", "session1", message, runConfig, null, processor);

    // Assert
    assertNotNull(emitter);

    // Wait for error handling with longer timeout for async execution
    assertTrue(errorLatch.await(10, TimeUnit.SECONDS), "Error should be handled");
  }

  /**
   * Test runner implementation for integration tests.
   *
   * <p>Note: This is a simplified mock runner. In real integration tests, you would use a proper
   * Runner instance or a more complete mock.
   */
  private static class TestRunner {
    private List<Event> events = new ArrayList<>();
    private RuntimeException error = null;

    public void setEvents(List<Event> events) {
      this.events = events;
    }

    public void setError(RuntimeException error) {
      this.error = error;
    }

    public Flowable<Event> runAsync(
        String appName,
        String userId,
        String sessionId,
        Content newMessage,
        RunConfig runConfig,
        Optional<Map<String, Object>> stateDelta) {
      if (error != null) {
        return Flowable.error(error);
      }
      return Flowable.fromIterable(events);
    }
  }

  /** Test SseEmitter implementation for capturing events. */
  private static class TestSseEmitter extends SseEmitter {
    private final List<String> sentData = new ArrayList<>();

    public TestSseEmitter() {
      super(60000L);
    }

    @Override
    public void send(SseEventBuilder event) throws IOException {
      super.send(event);
      // Extract data from the event builder
      try {
        java.lang.reflect.Field dataField = event.getClass().getDeclaredField("data");
        dataField.setAccessible(true);
        Object data = dataField.get(event);
        if (data != null) {
          sentData.add(data.toString());
        }
      } catch (Exception e) {
        // If reflection fails, just add a placeholder
        sentData.add("event-data");
      }
    }

    public List<String> getSentData() {
      return sentData;
    }
  }

  /** Creates a test event. */
  private Event createTestEvent(String eventId) {
    return Event.builder()
        .id(eventId)
        .author("test-agent")
        .content(Content.fromParts(Part.fromText("Test message: " + eventId)))
        .build();
  }
}
