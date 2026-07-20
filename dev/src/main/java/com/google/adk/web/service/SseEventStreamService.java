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

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.service.eventprocessor.EventProcessor;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Generic Server-Sent Events (SSE) streaming service for agent execution.
 *
 * <p>This service provides a reusable, framework-agnostic way to stream agent events via SSE. It
 * handles the complexity of SSE connection management, event formatting, error handling, and
 * resource cleanup, allowing applications to focus on domain-specific event processing logic.
 *
 * <p><b>Key Features:</b>
 *
 * <ul>
 *   <li>Generic and reusable across all agent types
 *   <li>Configurable timeout and streaming mode
 *   <li>Extensible event processing via {@link EventProcessor}
 *   <li>Automatic resource cleanup and error handling
 *   <li>Thread-safe and concurrent-request safe
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Basic usage with default pass-through processor
 * SseEmitter emitter = sseEventStreamService.streamEvents(
 *     runner,
 *     appName,
 *     userId,
 *     sessionId,
 *     message,
 *     RunConfig.builder().setStreamingMode(StreamingMode.SSE).build(),
 *     stateDelta,
 *     null  // No custom processor
 * );
 *
 * // Advanced usage with custom event processor
 * EventProcessor processor = new CustomEventProcessor();
 * SseEmitter emitter = sseEventStreamService.streamEvents(
 *     runner,
 *     appName,
 *     userId,
 *     sessionId,
 *     message,
 *     runConfig,
 *     stateDelta,
 *     processor
 * );
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This service is thread-safe and can handle multiple concurrent requests.
 * Each SSE stream is managed independently with its own executor task and resource lifecycle.
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see EventProcessor
 * @see SseEmitter
 * @see Runner
 */
@Service
public class SseEventStreamService {

  private static final Logger log = LoggerFactory.getLogger(SseEventStreamService.class);

  /** Default timeout for SSE connections: 1 hour */
  private static final long DEFAULT_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1);

  /** Default timeout for SSE connections: 30 minutes (for shorter-lived connections) */
  private static final long DEFAULT_SHORT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

  /** Executor service for handling SSE streaming tasks asynchronously */
  private final ExecutorService sseExecutor;

  /**
   * Creates a new SseEventStreamService with a cached thread pool executor.
   *
   * <p>The executor uses a cached thread pool that creates new threads as needed and reuses
   * existing threads when available, making it efficient for handling multiple concurrent SSE
   * connections.
   */
  public SseEventStreamService() {
    this.sseExecutor = Executors.newCachedThreadPool();
  }

  /**
   * Creates a new SseEventStreamService with a custom executor service.
   *
   * <p>This constructor is useful for testing or when you need custom executor configuration.
   *
   * @param executor the executor service to use for SSE streaming tasks
   */
  public SseEventStreamService(ExecutorService executor) {
    this.sseExecutor = executor;
  }

  /**
   * Streams agent execution events via Server-Sent Events (SSE).
   *
   * <p>This method creates an SSE emitter and asynchronously streams events from the agent runner.
   * Events are processed through the optional {@link EventProcessor} before being sent to the
   * client.
   *
   * <p><b>Event Flow:</b>
   *
   * <ol>
   *   <li>Create SSE emitter with default timeout
   *   <li>Execute agent run asynchronously
   *   <li>For each event, process through EventProcessor (if provided)
   *   <li>Send processed event to client via SSE
   *   <li>Handle errors and cleanup resources
   * </ol>
   *
   * <p><b>Error Handling:</b>
   *
   * <ul>
   *   <li>If runner setup fails, emitter is completed with error
   *   <li>If event processing fails, error event is sent to client
   *   <li>If stream fails, emitter is completed with error
   *   <li>On timeout or completion, resources are automatically cleaned up
   * </ul>
   *
   * @param runner the agent runner to execute
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param message the user message content
   * @param runConfig the run configuration (must have StreamingMode.SSE for real-time streaming)
   * @param stateDelta optional state delta to merge into session state
   * @param eventProcessor optional event processor for custom event transformation/filtering
   * @return SseEmitter that will stream events to the client
   * @throws IllegalArgumentException if runner, appName, userId, sessionId, or message is null
   */
  public SseEmitter streamEvents(
      Runner runner,
      String appName,
      String userId,
      String sessionId,
      Content message,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta,
      @Nullable EventProcessor eventProcessor) {

    // Validate required parameters
    validateParameters(runner, appName, userId, sessionId, message, runConfig);

    // Create SSE emitter with default timeout
    SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);

    // Store session ID for logging
    final String logSessionId = sessionId;

    // Execute streaming asynchronously
    sseExecutor.execute(
        () -> {
          try {
            // Notify processor of stream start (if provided)
            if (eventProcessor != null) {
              eventProcessor.onStreamStart(emitter, createContext(appName, userId, sessionId));
            }

            // Get event stream from runner
            Flowable<Event> eventFlowable =
                runner.runAsync(userId, sessionId, message, runConfig, stateDelta);

            // Subscribe to events and stream them
            Disposable disposable =
                eventFlowable
                    .observeOn(Schedulers.io())
                    .subscribe(
                        event -> {
                          try {
                            processAndSendEvent(
                                event,
                                emitter,
                                eventProcessor,
                                logSessionId,
                                appName,
                                userId,
                                sessionId);
                          } catch (Exception e) {
                            log.error(
                                "Error processing event for session {}: {}",
                                logSessionId,
                                e.getMessage(),
                                e);
                            sendErrorEvent(emitter, e, logSessionId);
                          }
                        },
                        error -> {
                          log.error(
                              "Stream error for session {}: {}",
                              logSessionId,
                              error.getMessage(),
                              error);
                          handleStreamError(emitter, error, eventProcessor, logSessionId);
                        },
                        () -> {
                          log.debug("Stream completed normally for session: {}", logSessionId);
                          handleStreamComplete(emitter, eventProcessor, logSessionId);
                        });

            // Register cleanup callbacks
            registerCleanupCallbacks(emitter, disposable, eventProcessor, logSessionId);

          } catch (Exception e) {
            log.error(
                "Failed to setup SSE stream for session {}: {}", logSessionId, e.getMessage(), e);
            handleStreamError(emitter, e, eventProcessor, logSessionId);
          }
        });

    log.debug("SSE emitter created for session: {}", logSessionId);
    return emitter;
  }

  /**
   * Streams agent execution events with a custom timeout.
   *
   * <p>This method is similar to {@link #streamEvents} but allows specifying a custom timeout for
   * the SSE connection. Use this when you need shorter or longer-lived connections.
   *
   * @param runner the agent runner to execute
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param message the user message content
   * @param runConfig the run configuration
   * @param stateDelta optional state delta to merge into session state
   * @param eventProcessor optional event processor
   * @param timeoutMs custom timeout in milliseconds
   * @return SseEmitter that will stream events to the client
   */
  public SseEmitter streamEvents(
      Runner runner,
      String appName,
      String userId,
      String sessionId,
      Content message,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta,
      @Nullable EventProcessor eventProcessor,
      long timeoutMs) {

    validateParameters(runner, appName, userId, sessionId, message, runConfig);

    SseEmitter emitter = new SseEmitter(timeoutMs);
    final String logSessionId = sessionId;

    sseExecutor.execute(
        () -> {
          try {
            if (eventProcessor != null) {
              eventProcessor.onStreamStart(emitter, createContext(appName, userId, sessionId));
            }

            Flowable<Event> eventFlowable =
                runner.runAsync(userId, sessionId, message, runConfig, stateDelta);

            Disposable disposable =
                eventFlowable
                    .observeOn(Schedulers.io())
                    .subscribe(
                        event -> {
                          try {
                            processAndSendEvent(
                                event,
                                emitter,
                                eventProcessor,
                                logSessionId,
                                appName,
                                userId,
                                sessionId);
                          } catch (Exception e) {
                            log.error(
                                "Error processing event for session {}: {}",
                                logSessionId,
                                e.getMessage(),
                                e);
                            sendErrorEvent(emitter, e, logSessionId);
                          }
                        },
                        error -> {
                          log.error(
                              "Stream error for session {}: {}",
                              logSessionId,
                              error.getMessage(),
                              error);
                          handleStreamError(emitter, error, eventProcessor, logSessionId);
                        },
                        () -> {
                          log.debug("Stream completed normally for session: {}", logSessionId);
                          handleStreamComplete(emitter, eventProcessor, logSessionId);
                        });

            registerCleanupCallbacks(emitter, disposable, eventProcessor, logSessionId);

          } catch (Exception e) {
            log.error(
                "Failed to setup SSE stream for session {}: {}", logSessionId, e.getMessage(), e);
            handleStreamError(emitter, e, eventProcessor, logSessionId);
          }
        });

    log.debug("SSE emitter created for session: {} with timeout: {}ms", logSessionId, timeoutMs);
    return emitter;
  }

  /**
   * Validates required parameters for streaming.
   *
   * @param runner the runner to validate
   * @param appName the app name to validate
   * @param userId the user ID to validate
   * @param sessionId the session ID to validate
   * @param message the message to validate
   * @param runConfig the run config to validate
   * @throws IllegalArgumentException if any required parameter is null or invalid
   */
  private void validateParameters(
      Runner runner,
      String appName,
      String userId,
      String sessionId,
      Content message,
      RunConfig runConfig) {
    if (runner == null) {
      throw new IllegalArgumentException("Runner cannot be null");
    }
    if (appName == null || appName.trim().isEmpty()) {
      throw new IllegalArgumentException("App name cannot be null or empty");
    }
    if (userId == null || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("User ID cannot be null or empty");
    }
    if (sessionId == null || sessionId.trim().isEmpty()) {
      throw new IllegalArgumentException("Session ID cannot be null or empty");
    }
    if (message == null) {
      throw new IllegalArgumentException("Message cannot be null");
    }
    if (runConfig == null) {
      throw new IllegalArgumentException("Run config cannot be null");
    }
  }

  /**
   * Processes an event through the event processor (if provided) and sends it via SSE.
   *
   * @param event the event to process and send
   * @param emitter the SSE emitter to send the event through
   * @param eventProcessor the optional event processor
   * @param sessionId the session ID for logging
   * @param appName the app name for context
   * @param userId the user ID for context
   * @param sessionIdForContext the session ID for context
   */
  private void processAndSendEvent(
      Event event,
      SseEmitter emitter,
      @Nullable EventProcessor eventProcessor,
      String sessionId,
      String appName,
      String userId,
      String sessionIdForContext) {
    try {
      Map<String, Object> context = createContext(appName, userId, sessionIdForContext);

      // Process event through processor if provided
      Optional<String> processedEvent = Optional.empty();
      if (eventProcessor != null) {
        processedEvent = eventProcessor.processEvent(event, context);
      }

      // Send event if processor returned a value (or if no processor)
      if (processedEvent.isEmpty() && eventProcessor == null) {
        // No processor: send event as-is
        String eventJson = event.toJson();
        log.debug("Sending event {} for session {}", event.id(), sessionId);
        emitter.send(SseEmitter.event().data(eventJson));
      } else if (processedEvent.isPresent()) {
        // Processor returned processed event: send it
        log.debug("Sending processed event for session {}", sessionId);
        emitter.send(SseEmitter.event().data(processedEvent.get()));
      }
      // If processor returned empty, skip this event (filtered out)

    } catch (IOException e) {
      log.error("IOException sending event for session {}: {}", sessionId, e.getMessage(), e);
      throw new RuntimeException("Failed to send SSE event", e);
    } catch (Exception e) {
      log.error("Unexpected error sending event for session {}: {}", sessionId, e.getMessage(), e);
      throw new RuntimeException("Unexpected error sending SSE event", e);
    }
  }

  /**
   * Handles stream errors by notifying the processor and completing the emitter with error.
   *
   * @param emitter the SSE emitter
   * @param error the error that occurred
   * @param eventProcessor the optional event processor
   * @param sessionId the session ID for logging
   */
  private void handleStreamError(
      SseEmitter emitter,
      Throwable error,
      @Nullable EventProcessor eventProcessor,
      String sessionId) {
    try {
      if (eventProcessor != null) {
        eventProcessor.onStreamError(emitter, error, createContext(null, null, sessionId));
      }
      emitter.completeWithError(error);
    } catch (Exception ex) {
      log.warn(
          "Error completing emitter after stream error for session {}: {}",
          sessionId,
          ex.getMessage());
    }
  }

  /**
   * Handles stream completion by notifying the processor and completing the emitter.
   *
   * @param emitter the SSE emitter
   * @param eventProcessor the optional event processor
   * @param sessionId the session ID for logging
   */
  private void handleStreamComplete(
      SseEmitter emitter, @Nullable EventProcessor eventProcessor, String sessionId) {
    try {
      if (eventProcessor != null) {
        eventProcessor.onStreamComplete(emitter, createContext(null, null, sessionId));
      }
      emitter.complete();
    } catch (Exception ex) {
      log.warn(
          "Error completing emitter after normal completion for session {}: {}",
          sessionId,
          ex.getMessage());
    }
  }

  /**
   * Registers cleanup callbacks for the SSE emitter to ensure proper resource cleanup.
   *
   * @param emitter the SSE emitter
   * @param disposable the RxJava disposable to clean up
   * @param eventProcessor the optional event processor
   * @param sessionId the session ID for logging
   */
  private void registerCleanupCallbacks(
      SseEmitter emitter,
      Disposable disposable,
      @Nullable EventProcessor eventProcessor,
      String sessionId) {
    // Cleanup on completion
    emitter.onCompletion(
        () -> {
          log.debug("SSE emitter completion callback for session: {}", sessionId);
          if (!disposable.isDisposed()) {
            disposable.dispose();
          }
          if (eventProcessor != null) {
            try {
              eventProcessor.onStreamComplete(emitter, createContext(null, null, sessionId));
            } catch (Exception e) {
              log.warn("Error in processor onStreamComplete: {}", e.getMessage());
            }
          }
        });

    // Cleanup on timeout
    emitter.onTimeout(
        () -> {
          log.debug("SSE emitter timeout callback for session: {}", sessionId);
          if (!disposable.isDisposed()) {
            disposable.dispose();
          }
          emitter.complete();
        });
  }

  /**
   * Sends an error event to the client via SSE.
   *
   * @param emitter the SSE emitter
   * @param error the error to send
   * @param sessionId the session ID for logging
   */
  private void sendErrorEvent(SseEmitter emitter, Exception error, String sessionId) {
    try {
      // Create a simple error event JSON
      String errorJson =
          String.format(
              "{\"error\":\"%s\",\"message\":\"%s\"}",
              error.getClass().getSimpleName(),
              escapeJson(error.getMessage() != null ? error.getMessage() : "Unknown error"));
      emitter.send(SseEmitter.event().name("error").data(errorJson));
    } catch (Exception e) {
      log.error("Failed to send error event for session {}: {}", sessionId, e.getMessage());
    }
  }

  /**
   * Creates a context map for event processors.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @return a map containing context information
   */
  private Map<String, Object> createContext(
      @Nullable String appName, @Nullable String userId, @Nullable String sessionId) {
    return Map.of(
        "appName", appName != null ? appName : "",
        "userId", userId != null ? userId : "",
        "sessionId", sessionId != null ? sessionId : "");
  }

  /**
   * Escapes JSON string values to prevent injection attacks.
   *
   * @param value the value to escape
   * @return the escaped value
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

  /**
   * Shuts down the executor service gracefully.
   *
   * <p>This method should be called during application shutdown to ensure all SSE connections are
   * properly closed and resources are released.
   */
  public void shutdown() {
    log.info("Shutting down SSE event stream service executor");
    sseExecutor.shutdown();
    try {
      if (!sseExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("SSE executor did not terminate gracefully, forcing shutdown");
        sseExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      log.warn("Interrupted while waiting for SSE executor shutdown", e);
      sseExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
