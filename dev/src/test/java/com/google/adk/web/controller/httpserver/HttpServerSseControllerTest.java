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

package com.google.adk.web.controller.httpserver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.service.eventprocessor.PassThroughEventProcessor;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import io.reactivex.rxjava3.core.Flowable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link HttpServerSseController}.
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>Request parsing and validation
 *   <li>SSE event formatting
 *   <li>Error handling
 *   <li>CORS preflight handling
 *   <li>Method validation
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
@ExtendWith(MockitoExtension.class)
class HttpServerSseControllerTest {

  @Mock private RunnerService mockRunnerService;

  @Mock private Runner mockRunner;

  @Mock private PassThroughEventProcessor mockProcessor;

  @Mock private HttpExchange mockExchange;

  private HttpServerSseController controller;
  private Headers responseHeaders;
  private ByteArrayOutputStream responseBody;

  @BeforeEach
  void setUp() throws IOException {
    controller = new HttpServerSseController(mockRunnerService, mockProcessor);
    responseHeaders = new Headers();
    responseBody = new ByteArrayOutputStream();

    lenient().when(mockExchange.getResponseHeaders()).thenReturn(responseHeaders);
    lenient().when(mockExchange.getResponseBody()).thenReturn(responseBody);
    lenient().when(mockExchange.getRequestURI()).thenReturn(URI.create("/run_sse"));
  }

  @Test
  void testHandle_ValidPostRequest_ProcessesRequest() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("POST");
    String requestBody =
        "{\"appName\":\"test-app\",\"userId\":\"user1\",\"sessionId\":\"session1\","
            + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]},\"streaming\":true}";
    when(mockExchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream(requestBody.getBytes()));

    Event testEvent = createTestEvent("event1");
    Flowable<Event> eventFlowable = Flowable.just(testEvent);

    when(mockRunnerService.getRunner("test-app")).thenReturn(mockRunner);
    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(eventFlowable);

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(200), anyLong());
    assertEquals("text/event-stream", responseHeaders.getFirst("Content-Type"));
  }

  @Test
  void testHandle_OptionsRequest_HandlesCorsPreflight() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("OPTIONS");

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(200), eq(-1L));
    assertEquals("*", responseHeaders.getFirst("Access-Control-Allow-Origin"));
  }

  @Test
  void testHandle_GetRequest_ReturnsMethodNotAllowed() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("GET");

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(405), anyLong());
  }

  @Test
  void testHandle_MissingAppName_ReturnsBadRequest() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("POST");
    String requestBody =
        "{\"userId\":\"user1\",\"sessionId\":\"session1\","
            + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}}";
    when(mockExchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream(requestBody.getBytes()));

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(400), anyLong());
  }

  @Test
  void testHandle_MissingSessionId_ReturnsBadRequest() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("POST");
    String requestBody =
        "{\"appName\":\"test-app\",\"userId\":\"user1\","
            + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}}";
    when(mockExchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream(requestBody.getBytes()));

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(400), anyLong());
  }

  @Test
  void testHandle_InvalidJson_ReturnsInternalServerError() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("POST");
    when(mockExchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream("invalid json".getBytes()));

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(500), anyLong());
  }

  @Test
  void testHandle_RunnerNotFound_ReturnsInternalServerError() throws IOException {
    // Arrange
    when(mockExchange.getRequestMethod()).thenReturn("POST");
    String requestBody =
        "{\"appName\":\"nonexistent\",\"userId\":\"user1\",\"sessionId\":\"session1\","
            + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}}";
    when(mockExchange.getRequestBody())
        .thenReturn(new ByteArrayInputStream(requestBody.getBytes()));

    lenient()
        .when(mockRunnerService.getRunner("nonexistent"))
        .thenThrow(new RuntimeException("Runner not found"));

    // Act
    controller.handle(mockExchange);

    // Assert
    verify(mockExchange).sendResponseHeaders(eq(500), anyLong());
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
        .content(Content.fromParts(Part.fromText("Test message")))
        .build();
  }
}
