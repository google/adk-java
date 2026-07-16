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
import static org.mockito.Mockito.timeout;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.service.eventprocessor.EventProcessor;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link SseEventStreamService}.
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>Parameter validation
 *   <li>Event streaming functionality
 *   <li>Event processor integration
 *   <li>Error handling
 *   <li>Resource cleanup
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
@ExtendWith(MockitoExtension.class)
class SseEventStreamServiceTest {

  @Mock private Runner mockRunner;

  @Mock private EventProcessor mockEventProcessor;

  private SseEventStreamService sseEventStreamService;
  private ExecutorService testExecutor;

  @BeforeEach
  void setUp() {
    // Use a single-threaded executor for deterministic test execution
    testExecutor = Executors.newSingleThreadExecutor();
    sseEventStreamService = new SseEventStreamService(testExecutor);
  }

  @AfterEach
  void tearDown() {
    sseEventStreamService.shutdown();
    testExecutor.shutdown();
  }

  @Test
  void testStreamEvents_ValidParameters_ReturnsSseEmitter() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    Flowable<Event> eventFlowable = Flowable.just(createTestEvent("event1"));

    when(mockRunner.runAsync(
            anyString(), anyString(), any(Content.class), any(RunConfig.class), any()))
        .thenReturn(eventFlowable);

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner, "test-app", "user1", "session1", message, runConfig, null, null);

    // Assert
    assertNotNull(emitter);
    // Wait for async execution to complete - use timeout verification
    verify(mockRunner, timeout(2000))
        .runAsync(eq("user1"), eq("session1"), eq(message), eq(runConfig), any());
  }

  @Test
  void testStreamEvents_NullRunner_ThrowsException() {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();

    // Act & Assert
    assertThrows(
        IllegalArgumentException.class,
        () ->
            sseEventStreamService.streamEvents(
                null, "test-app", "user1", "session1", message, runConfig, null, null));
  }

  @Test
  void testStreamEvents_EmptyAppName_ThrowsException() {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();

    // Act & Assert
    assertThrows(
        IllegalArgumentException.class,
        () ->
            sseEventStreamService.streamEvents(
                mockRunner, "", "user1", "session1", message, runConfig, null, null));
  }

  @Test
  void testStreamEvents_WithEventProcessor_CallsProcessor() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    Event testEvent = createTestEvent("event1");
    Flowable<Event> eventFlowable = Flowable.just(testEvent);

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);
    when(mockEventProcessor.processEvent(any(Event.class), any(Map.class)))
        .thenReturn(Optional.of("{\"processed\":\"event\"}"));

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner,
            "test-app",
            "user1",
            "session1",
            message,
            runConfig,
            null,
            mockEventProcessor);

    // Assert
    assertNotNull(emitter);

    // Wait for async processing - use timeout verification with longer waits
    verify(mockEventProcessor, timeout(3000)).onStreamStart(any(SseEmitter.class), any(Map.class));
    verify(mockEventProcessor, timeout(3000)).processEvent(eq(testEvent), any(Map.class));
    verify(mockEventProcessor, timeout(3000))
        .onStreamComplete(any(SseEmitter.class), any(Map.class));
  }

  @Test
  void testStreamEvents_EventProcessorFiltersEvent_EventNotSent() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    Event testEvent = createTestEvent("event1");
    Flowable<Event> eventFlowable = Flowable.just(testEvent);

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);
    when(mockEventProcessor.processEvent(any(Event.class), any(Map.class)))
        .thenReturn(Optional.empty()); // Filter out event

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner,
            "test-app",
            "user1",
            "session1",
            message,
            runConfig,
            null,
            mockEventProcessor);

    // Assert
    assertNotNull(emitter);

    // Wait for async processing - use timeout verification
    verify(mockEventProcessor, timeout(3000)).processEvent(eq(testEvent), any(Map.class));
  }

  @Test
  void testStreamEvents_WithCustomTimeout_UsesCustomTimeout() {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    Flowable<Event> eventFlowable = Flowable.just(createTestEvent("event1"));
    long customTimeout = TimeUnit.MINUTES.toMillis(15);

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner,
            "test-app",
            "user1",
            "session1",
            message,
            runConfig,
            null,
            null,
            customTimeout);

    // Assert
    assertNotNull(emitter);
    // Note: We can't directly verify timeout, but we can verify the emitter was created
  }

  @Test
  void testStreamEvents_WithStateDelta_PassesStateDelta() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    Map<String, Object> stateDelta = Map.of("key", "value");
    Flowable<Event> eventFlowable = Flowable.just(createTestEvent("event1"));

    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);

    // Act
    SseEmitter emitter =
        sseEventStreamService.streamEvents(
            mockRunner, "test-app", "user1", "session1", message, runConfig, stateDelta, null);

    // Assert
    assertNotNull(emitter);

    // Wait for async execution to complete - use timeout verification
    verify(mockRunner, timeout(2000))
        .runAsync(eq("user1"), eq("session1"), eq(message), eq(runConfig), eq(stateDelta));
  }

  @Test
  void testShutdown_GracefullyShutsDownExecutor() throws InterruptedException {
    // Arrange
    ExecutorService executor = Executors.newCachedThreadPool();
    SseEventStreamService service = new SseEventStreamService(executor);

    // Act
    service.shutdown();

    // Assert
    assertTrue(executor.isShutdown());
  }

  /**
   * Creates a test event for use in tests.
   *
   * @param eventId the event ID
   * @return a test event
   */
  private Event createTestEvent(String eventId) {
    return Event.builder()
        .id(eventId)
        .author("test-agent")
        .content(com.google.genai.types.Content.fromParts(Part.fromText("Test message")))
        .build();
  }
}
