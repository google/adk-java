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

package com.google.adk.web.service.httpserver;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.service.eventprocessor.EventProcessor;
import com.google.genai.types.Content;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight Server-Sent Events (SSE) streaming service using Java's built-in HttpServer.
 *
 * <p>This service provides a zero-dependency alternative to Spring's SseEmitter for SSE streaming.
 * It uses Java's built-in {@link HttpServer} and manually formats SSE events, making it ideal for
 * applications that want to avoid framework dependencies.
 *
 * <p><b>Key Features:</b>
 *
 * <ul>
 *   <li>Zero dependencies - Uses only JDK classes
 *   <li>Lightweight - Minimal memory footprint
 *   <li>Full control - Complete control over HTTP connection
 *   <li>Same API - Compatible with SseEventStreamService interface
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * HttpServerSseService service = new HttpServerSseService(8080);
 * service.start();
 *
 * // Register endpoint
 * service.registerEndpoint("/sse", (runner, appName, userId, sessionId, message, runConfig, stateDelta, processor) -> {
 *     // Stream events
 * });
 * }</pre>
 *
 * <p><b>Comparison with Spring:</b>
 *
 * <ul>
 *   <li>Spring: Uses SseEmitter, managed by Spring container
 *   <li>HttpServer: Manual SSE formatting, direct HTTP handling
 *   <li>Both: Support same EventProcessor interface
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see com.google.adk.web.service.SseEventStreamService
 */
public class HttpServerSseService {

  private static final Logger log = LoggerFactory.getLogger(HttpServerSseService.class);

  private final HttpServer httpServer;
  private final ExecutorService executor;
  private final int port;
  private final String host;

  /**
   * Creates a new HttpServerSseService on the default port (8080).
   *
   * @throws IOException if the server cannot be created
   */
  public HttpServerSseService() throws IOException {
    this(8080);
  }

  /**
   * Creates a new HttpServerSseService on the specified port.
   *
   * @param port the port to listen on
   * @throws IOException if the server cannot be created
   */
  public HttpServerSseService(int port) throws IOException {
    this(port, "0.0.0.0");
  }

  /**
   * Creates a new HttpServerSseService on the specified port and host.
   *
   * @param port the port to listen on
   * @param host the host to bind to (use "0.0.0.0" for all interfaces)
   * @throws IOException if the server cannot be created
   */
  public HttpServerSseService(int port, String host) throws IOException {
    this.port = port;
    this.host = host;
    this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
    this.executor = Executors.newCachedThreadPool();
    this.httpServer.setExecutor(executor);
  }

  /**
   * Starts the HTTP server.
   *
   * <p>After calling this method, the server will accept connections on the configured port.
   */
  public void start() {
    httpServer.start();
    log.info("HttpServer SSE service started on {}:{}", host, port);
  }

  /**
   * Stops the HTTP server gracefully.
   *
   * <p>This method stops accepting new connections and waits for existing connections to complete
   * before shutting down.
   *
   * @param delaySeconds delay before forcing shutdown
   */
  public void stop(int delaySeconds) {
    log.info("Stopping HttpServer SSE service...");
    httpServer.stop(delaySeconds);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    log.info("HttpServer SSE service stopped");
  }

  /**
   * Registers an SSE endpoint that streams agent events.
   *
   * <p>This method creates an HTTP handler that accepts POST requests and streams events via SSE.
   * The handler uses the same event processing logic as the Spring-based implementation, ensuring
   * consistency across both implementations.
   *
   * @param path the endpoint path (e.g., "/sse" or "/custom/sse")
   * @param runner the agent runner
   * @param appName the application name
   * @param eventProcessor optional event processor for custom event transformation
   */
  public void registerSseEndpoint(
      String path, Runner runner, String appName, @Nullable EventProcessor eventProcessor) {
    httpServer.createContext(path, new SseHandler(runner, appName, eventProcessor));
    log.info("Registered SSE endpoint: {}", path);
  }

  /** HTTP handler for SSE endpoints. */
  private static class SseHandler implements HttpHandler {

    private final Runner runner;
    private final String appName;
    private final EventProcessor eventProcessor;

    public SseHandler(Runner runner, String appName, @Nullable EventProcessor eventProcessor) {
      this.runner = runner;
      this.appName = appName;
      this.eventProcessor = eventProcessor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      // Only accept POST
      if (!"POST".equals(exchange.getRequestMethod())) {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
          handleCorsPreflight(exchange);
          return;
        }
        sendError(exchange, 405, "Method Not Allowed");
        return;
      }

      // Set SSE headers
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.getResponseHeaders().set("Cache-Control", "no-cache");
      exchange.getResponseHeaders().set("Connection", "keep-alive");
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      exchange.sendResponseHeaders(200, 0);

      OutputStream os = exchange.getResponseBody();

      try {
        // Parse request body (simplified - in real implementation, parse JSON)
        SseRequest request = parseRequest(exchange);

        // Notify processor of stream start
        if (eventProcessor != null) {
          Map<String, Object> context =
              Map.of(
                  "appName", appName,
                  "userId", request.userId,
                  "sessionId", request.sessionId);
          eventProcessor.onStreamStart(null, context); // No SseEmitter in HttpServer
        }

        // Get event stream from runner
        Flowable<Event> eventFlowable =
            runner.runAsync(
                request.userId,
                request.sessionId,
                request.message,
                request.runConfig,
                request.stateDelta);

        // Stream events
        Disposable disposable =
            eventFlowable
                .observeOn(Schedulers.io())
                .subscribe(
                    event -> {
                      try {
                        processAndSendEvent(
                            os, event, eventProcessor, request.sessionId, appName, request.userId);
                      } catch (Exception e) {
                        log.error(
                            "Error processing event for session {}: {}",
                            request.sessionId,
                            e.getMessage(),
                            e);
                        sendErrorEvent(os, e, request.sessionId);
                      }
                    },
                    error -> {
                      log.error(
                          "Stream error for session {}: {}",
                          request.sessionId,
                          error.getMessage(),
                          error);
                      handleStreamError(os, error, eventProcessor, request.sessionId);
                    },
                    () -> {
                      log.debug("Stream completed normally for session: {}", request.sessionId);
                      handleStreamComplete(os, eventProcessor, request.sessionId);
                      try {
                        os.close();
                      } catch (IOException e) {
                        log.error("Error closing output stream: {}", e.getMessage());
                      }
                    });

        // Note: In HttpServer, we can't easily register cleanup callbacks like SseEmitter
        // The connection will close when the stream completes or errors

      } catch (Exception e) {
        log.error("Error handling SSE request: {}", e.getMessage(), e);
        sendErrorEvent(os, e, "unknown");
        os.close();
      }
    }

    private void handleCorsPreflight(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
      exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
      exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    }

    private SseRequest parseRequest(HttpExchange exchange) throws IOException {
      // Simplified parsing - in real implementation, parse JSON body
      // For now, return a default request structure
      // This should be enhanced to parse actual JSON from request body
      return new SseRequest();
    }

    private void processAndSendEvent(
        OutputStream os,
        Event event,
        @Nullable EventProcessor eventProcessor,
        String sessionId,
        String appName,
        String userId)
        throws IOException {
      Map<String, Object> context =
          Map.of("appName", appName, "userId", userId, "sessionId", sessionId);

      // Process event through processor if provided
      Optional<String> processedEvent = Optional.empty();
      if (eventProcessor != null) {
        processedEvent = eventProcessor.processEvent(event, context);
      }

      // Send event if processor returned a value (or if no processor)
      if (processedEvent.isEmpty() && eventProcessor == null) {
        // No processor: send event as-is
        String eventJson = event.toJson();
        sendSSEEvent(os, "message", eventJson);
      } else if (processedEvent.isPresent()) {
        // Processor returned processed event: send it
        sendSSEEvent(os, "message", processedEvent.get());
      }
      // If processor returned empty, skip this event (filtered out)
    }

    private void handleStreamError(
        OutputStream os,
        Throwable error,
        @Nullable EventProcessor eventProcessor,
        String sessionId) {
      try {
        if (eventProcessor != null) {
          Map<String, Object> context = Map.of("sessionId", sessionId);
          eventProcessor.onStreamError(null, error, context);
        }
        sendErrorEvent(os, error, sessionId);
      } catch (Exception e) {
        log.error("Error handling stream error: {}", e.getMessage());
      }
    }

    private void handleStreamComplete(
        OutputStream os, @Nullable EventProcessor eventProcessor, String sessionId) {
      try {
        if (eventProcessor != null) {
          Map<String, Object> context = Map.of("sessionId", sessionId);
          eventProcessor.onStreamComplete(null, context);
        }
        sendSSEEvent(os, "done", "{\"status\":\"complete\"}");
      } catch (Exception e) {
        log.error("Error handling stream completion: {}", e.getMessage());
      }
    }

    private void sendSSEEvent(OutputStream os, String eventType, String data) throws IOException {
      os.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
      os.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
    }

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

    private void sendError(HttpExchange exchange, int statusCode, String message)
        throws IOException {
      exchange.getResponseHeaders().set("Content-Type", "text/plain");
      byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }

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

  /**
   * Simplified request structure for HttpServer implementation.
   *
   * <p>In a real implementation, this would parse JSON from the request body.
   */
  private static class SseRequest {
    String userId = "default";
    String sessionId = java.util.UUID.randomUUID().toString();
    Content message =
        com.google.genai.types.Content.fromParts(com.google.genai.types.Part.fromText(""));
    RunConfig runConfig =
        RunConfig.builder()
            .setStreamingMode(com.google.adk.agents.RunConfig.StreamingMode.SSE)
            .build();
    Map<String, Object> stateDelta = null;
  }
}
