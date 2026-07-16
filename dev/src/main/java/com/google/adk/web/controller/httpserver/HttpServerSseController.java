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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.runner.Runner;
import com.google.adk.web.dto.AgentRunRequest;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.service.eventprocessor.PassThroughEventProcessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Handler for SSE endpoints using Java's HttpServer (zero-dependency default implementation).
 *
 * <p>This is the <b>default SSE implementation</b> providing zero-dependency Server-Sent Events
 * streaming using Java's built-in HttpServer. It provides the same functionality as the
 * Spring-based endpoint but without requiring Spring framework dependencies.
 *
 * <p><b>Default Endpoint:</b>
 *
 * <ul>
 *   <li>POST /run_sse - Default SSE endpoint (HttpServer-based, zero dependencies)
 * </ul>
 *
 * <p><b>Alternative:</b> Spring-based endpoint is available at {@code /run_sse_spring} for
 * applications that prefer Spring's SseEmitter.
 *
 * <p><b>Request Format:</b>
 *
 * <pre>{@code
 * {
 *   "appName": "my-app",
 *   "userId": "user123",
 *   "sessionId": "session456",
 *   "newMessage": {
 *     "role": "user",
 *     "parts": [{"text": "Hello"}]
 *   },
 *   "streaming": true,
 *   "stateDelta": {"key": "value"}
 * }
 * }</pre>
 *
 * <p><b>Response:</b> Server-Sent Events stream with Content-Type: text/event-stream
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see com.google.adk.web.controller.ExecutionController
 */
public class HttpServerSseController implements HttpHandler {

  private static final Logger log = LoggerFactory.getLogger(HttpServerSseController.class);

  private final RunnerService runnerService;
  private final PassThroughEventProcessor passThroughProcessor;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Creates a new HttpServerSseController.
   *
   * @param runnerService the runner service for getting agent runners
   * @param passThroughProcessor the event processor (typically PassThroughEventProcessor)
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  public HttpServerSseController(
      RunnerService runnerService, PassThroughEventProcessor passThroughProcessor) {
    this.runnerService = runnerService;
    this.passThroughProcessor = passThroughProcessor;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    // Handle CORS preflight
    if ("OPTIONS".equals(exchange.getRequestMethod())) {
      handleCorsPreflight(exchange);
      return;
    }

    // Only accept POST
    if (!"POST".equals(exchange.getRequestMethod())) {
      sendError(exchange, 405, "Method Not Allowed");
      return;
    }

    try {
      // Parse request body
      AgentRunRequest request = parseRequest(exchange);

      // Validate request
      if (request.appName == null || request.appName.trim().isEmpty()) {
        sendError(exchange, 400, "appName cannot be null or empty");
        return;
      }
      if (request.sessionId == null || request.sessionId.trim().isEmpty()) {
        sendError(exchange, 400, "sessionId cannot be null or empty");
        return;
      }

      log.info("HttpServer SSE request received for POST /run_sse, session: {}", request.sessionId);

      // Get runner
      Runner runner = runnerService.getRunner(request.appName);

      // Build run config
      RunConfig runConfig =
          RunConfig.builder()
              .setStreamingMode(request.getStreaming() ? StreamingMode.SSE : StreamingMode.NONE)
              .build();

      // Stream events
      streamEvents(exchange, runner, request, runConfig);

    } catch (Exception e) {
      log.error("Error handling HttpServer SSE request: {}", e.getMessage(), e);
      sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
    }
  }

  /**
   * Streams events via SSE using HttpServer.
   *
   * <p>Note: This method handles async streaming. The OutputStream remains open until the stream
   * completes or errors, at which point it's closed automatically.
   *
   * @param exchange the HTTP exchange
   * @param runner the agent runner
   * @param request the agent run request
   * @param runConfig the run configuration
   * @throws IOException if an I/O error occurs
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private void streamEvents(
      HttpExchange exchange, Runner runner, AgentRunRequest request, RunConfig runConfig)
      throws IOException {
    // Set SSE headers
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.getResponseHeaders().set("Connection", "keep-alive");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(200, 0);

    OutputStream os = exchange.getResponseBody();
    final String sessionId = request.sessionId;

    try {
      // Get event stream
      io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> eventFlowable =
          runner.runAsync(
              request.userId,
              request.sessionId,
              request.getNewMessage(),
              runConfig,
              request.stateDelta);

      // Use CountDownLatch to wait for stream completion
      java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicReference<Throwable> streamError =
          new java.util.concurrent.atomic.AtomicReference<>();

      // Stream events asynchronously
      io.reactivex.rxjava3.disposables.Disposable disposable =
          eventFlowable
              .observeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
              .subscribe(
                  event -> {
                    try {
                      String eventJson = event.toJson();
                      sendSSEEvent(os, "message", eventJson);
                      log.debug("Sent event {} for session {}", event.id(), sessionId);
                    } catch (Exception e) {
                      log.error(
                          "Error sending event for session {}: {}", sessionId, e.getMessage(), e);
                      try {
                        sendErrorEvent(os, e, sessionId);
                      } catch (Exception ex) {
                        log.error("Error sending error event: {}", ex.getMessage());
                      }
                    }
                  },
                  error -> {
                    log.error(
                        "Stream error for session {}: {}", sessionId, error.getMessage(), error);
                    streamError.set(error);
                    try {
                      sendErrorEvent(os, error, sessionId);
                    } catch (Exception e) {
                      log.error("Error sending error event: {}", e.getMessage());
                    } finally {
                      try {
                        os.close();
                      } catch (IOException e) {
                        log.error("Error closing stream on error: {}", e.getMessage());
                      }
                      latch.countDown();
                    }
                  },
                  () -> {
                    log.debug("Stream completed normally for session: {}", sessionId);
                    try {
                      sendSSEEvent(os, "done", "{\"status\":\"complete\"}");
                    } catch (Exception e) {
                      log.error("Error sending done event: {}", e.getMessage());
                    } finally {
                      try {
                        os.close();
                      } catch (IOException e) {
                        log.error("Error closing stream on completion: {}", e.getMessage());
                      }
                      latch.countDown();
                    }
                  });

      // Wait for stream to complete (with timeout)
      // This blocks the HttpHandler thread, which is acceptable for HttpServer
      try {
        boolean completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
          log.warn("Stream timeout for session: {}", sessionId);
          if (!disposable.isDisposed()) {
            disposable.dispose();
          }
          sendSSEEvent(os, "error", "{\"error\":\"Stream timeout\"}");
          os.close();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted while waiting for stream: {}", e.getMessage());
        if (!disposable.isDisposed()) {
          disposable.dispose();
        }
        sendErrorEvent(os, e, sessionId);
        os.close();
      }

    } catch (Exception e) {
      log.error("Error setting up stream for session {}: {}", sessionId, e.getMessage(), e);
      sendErrorEvent(os, e, sessionId);
      os.close();
    }
  }

  /**
   * Parses the request body into an AgentRunRequest.
   *
   * @param exchange the HTTP exchange containing the request body
   * @return parsed AgentRunRequest
   * @throws IOException if reading the request body fails
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private AgentRunRequest parseRequest(HttpExchange exchange) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
      StringBuilder requestBody = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        requestBody.append(line);
      }

      // Parse JSON using Jackson ObjectMapper (handles abstract classes better than Gson)
      return objectMapper.readValue(requestBody.toString(), AgentRunRequest.class);
    }
  }

  /**
   * Sends an SSE event in the standard format: "event: {type}\ndata: {data}\n\n".
   *
   * @param os the output stream to write to
   * @param eventType the event type (e.g., "message", "error", "done")
   * @param data the event data (JSON string)
   * @throws IOException if writing fails
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private void sendSSEEvent(OutputStream os, String eventType, String data) throws IOException {
    os.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
    os.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    os.flush();
  }

  /**
   * Sends an error event via SSE.
   *
   * @param os the output stream
   * @param error the error that occurred
   * @param sessionId the session ID for logging
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private void sendErrorEvent(OutputStream os, Throwable error, String sessionId) {
    try {
      String errorJson =
          String.format(
              "{\"error\":\"%s\",\"message\":\"%s\"}",
              error.getClass().getSimpleName(),
              escapeJson(error.getMessage() != null ? error.getMessage() : "Unknown error"));
      sendSSEEvent(os, "error", errorJson);
    } catch (Exception e) {
      log.error("Failed to send error event for session {}: {}", sessionId, e.getMessage());
    }
  }

  /**
   * Handles CORS preflight (OPTIONS) requests.
   *
   * @param exchange the HTTP exchange
   * @throws IOException if sending the response fails
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private void handleCorsPreflight(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  /**
   * Sends an HTTP error response.
   *
   * @param exchange the HTTP exchange
   * @param statusCode the HTTP status code
   * @param message the error message
   * @throws IOException if sending the response fails
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /**
   * Escapes JSON string values to prevent injection attacks.
   *
   * @param value the value to escape
   * @return the escaped value
   * @author Sandeep Belgavi
   * @since January 24, 2026
   */
  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
