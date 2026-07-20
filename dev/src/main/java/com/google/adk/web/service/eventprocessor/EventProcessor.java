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

import com.google.adk.events.Event;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Interface for processing and transforming events before sending them via SSE.
 *
 * <p>This interface allows applications to customize how events are processed, filtered, and
 * formatted before being sent to clients. Implementations can:
 *
 * <ul>
 *   <li>Transform event data into domain-specific formats
 *   <li>Filter events based on business logic
 *   <li>Accumulate events for consolidation
 *   <li>Add custom metadata or formatting
 * </ul>
 *
 * <p><b>Event Processing Flow:</b>
 *
 * <ol>
 *   <li>{@link #onStreamStart} - Called when SSE stream starts
 *   <li>{@link #processEvent} - Called for each event (can filter by returning empty)
 *   <li>{@link #onStreamComplete} - Called when stream completes normally
 *   <li>{@link #onStreamError} - Called when stream encounters an error
 * </ol>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * public class CustomEventProcessor implements EventProcessor {
 *   private final AtomicReference<String> finalResponse = new AtomicReference<>("");
 *
 *   @Override
 *   public Optional<String> processEvent(Event event, Map<String, Object> context) {
 *     // Only process final result events
 *     if (event.actions().stateDelta().containsKey("finalResult")) {
 *       String result = formatAsCustomResponse(event, context);
 *       finalResponse.set(result);
 *       return Optional.of(result);
 *     }
 *     // Filter out intermediate events
 *     return Optional.empty();
 *   }
 *
 *   @Override
 *   public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
 *     // Send final consolidated response
 *     if (!finalResponse.get().isEmpty()) {
 *       emitter.send(SseEmitter.event().name("message").data(finalResponse.get()));
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b> Implementations should be thread-safe if they maintain state, as
 * multiple events may be processed concurrently. Consider using thread-safe data structures like
 * {@link java.util.concurrent.ConcurrentHashMap} or {@link
 * java.util.concurrent.atomic.AtomicReference}.
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see com.google.adk.web.service.SseEventStreamService
 */
public interface EventProcessor {

  /**
   * Processes a single event and optionally transforms it.
   *
   * <p>This method is called for each event in the stream. The implementation can:
   *
   * <ul>
   *   <li>Return {@link Optional#of(String)} with transformed JSON to send to client
   *   <li>Return {@link Optional#empty()} to filter out the event (not send to client)
   * </ul>
   *
   * <p><b>Note:</b> If you return empty, the event will not be sent to the client. This is useful
   * for filtering intermediate events or accumulating events for later consolidation.
   *
   * @param event the event to process
   * @param context context map containing appName, userId, sessionId
   * @return Optional containing the JSON string to send (or empty to filter out the event)
   */
  Optional<String> processEvent(Event event, Map<String, Object> context);

  /**
   * Called when the SSE stream starts.
   *
   * <p>This method can be used to send initial connection events or set up processor state. For
   * example, you might send a "connected" event to the client.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * @Override
   * public void onStreamStart(SseEmitter emitter, Map<String, Object> context) {
   *   String sessionId = (String) context.get("sessionId");
   *   String connectedEvent = String.format(
   *     "{\"status\":\"connected\",\"sessionId\":\"%s\"}", sessionId);
   *   emitter.send(SseEmitter.event().name("connected").data(connectedEvent));
   * }
   * }</pre>
   *
   * @param emitter the SSE emitter (can be used to send initial events)
   * @param context context map containing appName, userId, sessionId
   */
  default void onStreamStart(SseEmitter emitter, Map<String, Object> context) {
    // Default implementation does nothing
  }

  /**
   * Called when the SSE stream completes normally.
   *
   * <p>This method can be used to send final consolidated responses or cleanup resources. For
   * example, you might send a "done" event or a final accumulated result.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * @Override
   * public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
   *   String finalResult = getAccumulatedResult();
   *   emitter.send(SseEmitter.event().name("message").data(finalResult));
   *   emitter.send(SseEmitter.event().name("done").data("{\"status\":\"complete\"}"));
   * }
   * }</pre>
   *
   * @param emitter the SSE emitter (can be used to send final events)
   * @param context context map containing appName, userId, sessionId
   */
  default void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
    // Default implementation does nothing
  }

  /**
   * Called when the SSE stream encounters an error.
   *
   * <p>This method can be used to send custom error events or perform error-specific cleanup. The
   * emitter will be completed with error after this method returns.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * @Override
   * public void onStreamError(SseEmitter emitter, Throwable error, Map<String, Object> context) {
   *   String errorEvent = String.format(
   *     "{\"error\":\"%s\",\"message\":\"%s\"}",
   *     error.getClass().getSimpleName(),
   *     error.getMessage());
   *   emitter.send(SseEmitter.event().name("error").data(errorEvent));
   * }
   * }</pre>
   *
   * @param emitter the SSE emitter (can be used to send error events)
   * @param error the error that occurred
   * @param context context map containing appName, userId, sessionId
   */
  default void onStreamError(SseEmitter emitter, Throwable error, Map<String, Object> context) {
    // Default implementation does nothing
  }
}
