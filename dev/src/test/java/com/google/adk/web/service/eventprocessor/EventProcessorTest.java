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

package com.google.adk.web.service.eventprocessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link EventProcessor} interface and {@link PassThroughEventProcessor}.
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
@ExtendWith(MockitoExtension.class)
class EventProcessorTest {

  @Mock private SseEmitter mockEmitter;

  @Test
  void testPassThroughEventProcessor_ProcessesEvent_ReturnsEventJson() {
    // Arrange
    PassThroughEventProcessor processor = new PassThroughEventProcessor();
    Event event =
        Event.builder()
            .id("test-event")
            .author("test-agent")
            .content(Content.fromParts(Part.fromText("Test message")))
            .build();

    // Act
    Optional<String> result = processor.processEvent(event, Map.of());

    // Assert
    assertTrue(result.isPresent());
    assertTrue(result.get().contains("test-event"));
  }

  @Test
  void testEventProcessor_DefaultMethods_DoNothing() {
    // Arrange
    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            return Optional.empty();
          }
        };

    // Act & Assert - Should not throw
    assertDoesNotThrow(
        () -> {
          processor.onStreamStart(mockEmitter, Map.of());
          processor.onStreamComplete(mockEmitter, Map.of());
          processor.onStreamError(mockEmitter, new RuntimeException("test"), Map.of());
        });
  }

  @Test
  void testEventProcessor_FilterEvent_ReturnsEmpty() {
    // Arrange
    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            // Filter out all events
            return Optional.empty();
          }
        };

    Event event =
        Event.builder()
            .id("test-event")
            .author("test-agent")
            .content(Content.fromParts(Part.fromText("Test message")))
            .build();

    // Act
    Optional<String> result = processor.processEvent(event, Map.of());

    // Assert
    assertFalse(result.isPresent());
  }

  @Test
  void testEventProcessor_TransformEvent_ReturnsTransformedJson() {
    // Arrange
    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            // Transform event
            return Optional.of("{\"transformed\":\"true\",\"eventId\":\"" + event.id() + "\"}");
          }
        };

    Event event =
        Event.builder()
            .id("test-event")
            .author("test-agent")
            .content(Content.fromParts(Part.fromText("Test message")))
            .build();

    // Act
    Optional<String> result = processor.processEvent(event, Map.of());

    // Assert
    assertTrue(result.isPresent());
    assertTrue(result.get().contains("transformed"));
    assertTrue(result.get().contains("test-event"));
  }
}
