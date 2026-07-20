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

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.service.eventprocessor.PassThroughEventProcessor;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration tests for {@link HttpServerSseController}.
 *
 * <p>These tests verify end-to-end behavior including:
 *
 * <ul>
 *   <li>HTTP server startup and shutdown
 *   <li>SSE event streaming
 *   <li>Multiple events handling
 *   <li>Error handling
 *   <li>Connection management
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
@ExtendWith(MockitoExtension.class)
class HttpServerSseControllerIntegrationTest {

  @Mock private RunnerService mockRunnerService;

  @Mock private Runner mockRunner;

  private HttpServer httpServer;
  private HttpServerSseController controller;
  private PassThroughEventProcessor processor;
  private int testPort = 18080; // Use different port to avoid conflicts

  @BeforeEach
  void setUp() throws Exception {
    processor = new PassThroughEventProcessor();
    controller = new HttpServerSseController(mockRunnerService, processor);

    httpServer = HttpServer.create(new InetSocketAddress("localhost", testPort), 0);
    httpServer.createContext("/run_sse", controller);
    httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    httpServer.start();
  }

  @AfterEach
  void tearDown() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  @Test
  void testSseEndpoint_MultipleEvents_AllEventsReceived() throws Exception {
    // Arrange
    List<Event> testEvents =
        List.of(createTestEvent("event1"), createTestEvent("event2"), createTestEvent("event3"));

    when(mockRunnerService.getRunner("test-app")).thenReturn(mockRunner);
    when(mockRunner.runAsync(anyString(), anyString(), any(), any(), any()))
        .thenReturn(Flowable.fromIterable(testEvents));

    // Act
    List<String> receivedEvents = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(3);

    Thread clientThread =
        new Thread(
            () -> {
              try {
                URL url = new URL("http://localhost:" + testPort + "/run_sse");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                // Send request
                String requestBody =
                    "{\"appName\":\"test-app\",\"userId\":\"user1\",\"sessionId\":\"session1\","
                        + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]},\"streaming\":true}";
                conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

                // Read SSE stream
                try (BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                      receivedEvents.add(line.substring(6));
                      latch.countDown();
                    }
                  }
                }
              } catch (Exception e) {
                e.printStackTrace();
              }
            });

    clientThread.start();

    // Wait for events (with timeout)
    boolean completed = latch.await(5, TimeUnit.SECONDS);

    // Assert
    assertTrue(completed, "Should receive events within timeout");
    assertTrue(receivedEvents.size() >= 2, "Should receive at least 2 events");
  }

  @Test
  void testSseEndpoint_InvalidRequest_ReturnsError() throws Exception {
    // Arrange
    URL url = new URL("http://localhost:" + testPort + "/run_sse");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");

    // Send invalid request (missing appName)
    String requestBody =
        "{\"userId\":\"user1\",\"sessionId\":\"session1\","
            + "\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}}";
    conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

    // Act
    int responseCode = conn.getResponseCode();

    // Assert
    assertEquals(400, responseCode, "Should return 400 Bad Request");
  }

  @Test
  void testSseEndpoint_OptionsRequest_HandlesCors() throws Exception {
    // Arrange
    URL url = new URL("http://localhost:" + testPort + "/run_sse");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("OPTIONS");

    // Act
    int responseCode = conn.getResponseCode();

    // Assert
    assertEquals(200, responseCode, "Should return 200 OK for OPTIONS");
    String allowOrigin = conn.getHeaderField("Access-Control-Allow-Origin");
    assertEquals("*", allowOrigin, "Should allow all origins");
  }

  /**
   * Creates a test event.
   *
   * @param eventId the event ID
   * @return a test event
   */
  private Event createTestEvent(String eventId) {
    return Event.builder()
        .id(eventId)
        .author("test-agent")
        .content(Content.fromParts(Part.fromText("Test message: " + eventId)))
        .build();
  }
}
