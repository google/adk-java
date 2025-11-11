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

package com.google.adk.web.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.web.service.RunnerService;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Modality;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * WebSocket Handler for the /run_live endpoint.
 *
 * <p>Manages bidirectional communication for live agent interactions. Assumes the
 * com.google.adk.runner.Runner class has a method: {@code public Flowable<Event> runLive(Session
 * session, Flowable<LiveRequest> liveRequests, List<String> modalities)}
 */
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

  // WebSocket constants
  private static final String LIVE_REQUEST_QUEUE_ATTR = "liveRequestQueue";
  private static final String LIVE_SUBSCRIPTION_ATTR = "liveSubscription";
  private static final int WEBSOCKET_MAX_BYTES_FOR_REASON = 123;
  private static final int WEBSOCKET_PROTOCOL_ERROR = 1002;
  private static final int WEBSOCKET_INTERNAL_SERVER_ERROR = 1011;

  private final ObjectMapper objectMapper;
  private final BaseSessionService sessionService;
  private final RunnerService runnerService;

  @Autowired
  public LiveWebSocketHandler(
      ObjectMapper objectMapper, BaseSessionService sessionService, RunnerService runnerService) {
    this.objectMapper = objectMapper;
    this.sessionService = sessionService;
    this.runnerService = runnerService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
    URI uri = wsSession.getUri();
    if (uri == null) {
      log.warn("WebSocket session URI is null, cannot establish connection.");
      wsSession.close(CloseStatus.SERVER_ERROR.withReason("Invalid URI"));
      return;
    }
    String path = uri.getPath();
    log.info("WebSocket connection established: {} from {}", wsSession.getId(), uri);

    MultiValueMap<String, String> queryParams =
        UriComponentsBuilder.fromUri(uri).build().getQueryParams();
    String appName = queryParams.getFirst("app_name");
    String userId = queryParams.getFirst("user_id");
    String sessionId = queryParams.getFirst("session_id");

    if (appName == null || appName.trim().isEmpty()) {
      log.warn(
          "WebSocket connection for session {} rejected: app_name query parameter is required and"
              + " cannot be empty. URI: {}",
          wsSession.getId(),
          uri);
      wsSession.close(
          CloseStatus.POLICY_VIOLATION.withReason(
              "app_name query parameter is required and cannot be empty"));
      return;
    }
    if (sessionId == null || sessionId.trim().isEmpty()) {
      log.warn(
          "WebSocket connection for session {} rejected: session_id query parameter is required"
              + " and cannot be empty. URI: {}",
          wsSession.getId(),
          uri);
      wsSession.close(
          CloseStatus.POLICY_VIOLATION.withReason(
              "session_id query parameter is required and cannot be empty"));
      return;
    }

    log.debug(
        "Extracted params for WebSocket session {}: appName={}, userId={}, sessionId={},",
        wsSession.getId(),
        appName,
        userId,
        sessionId);

    RunConfig runConfig =
        RunConfig.builder()
            .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .setStreamingMode(StreamingMode.BIDI)
            .build();

    Session session;
    try {
      session =
          sessionService.getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
      if (session == null) {
        log.warn(
            "Session not found for WebSocket: app={}, user={}, id={}. Closing connection.",
            appName,
            userId,
            sessionId);
        wsSession.close(new CloseStatus(WEBSOCKET_PROTOCOL_ERROR, "Session not found"));
        return;
      }
    } catch (Exception e) {
      log.error(
          "Error retrieving session for WebSocket: app={}, user={}, id={}",
          appName,
          userId,
          sessionId,
          e);
      wsSession.close(CloseStatus.SERVER_ERROR.withReason("Failed to retrieve session"));
      return;
    }

    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    wsSession.getAttributes().put(LIVE_REQUEST_QUEUE_ATTR, liveRequestQueue);

    Runner runner;
    try {
      runner = this.runnerService.getRunner(appName);
    } catch (ResponseStatusException e) {
      log.error(
          "Failed to get runner for app {} during WebSocket connection: {}",
          appName,
          e.getMessage());
      wsSession.close(CloseStatus.SERVER_ERROR.withReason("Runner unavailable: " + e.getReason()));
      return;
    }

    Flowable<Event> eventStream = runner.runLive(session, liveRequestQueue, runConfig);

    Disposable disposable =
        eventStream
            .subscribeOn(Schedulers.io()) // Offload runner work
            .observeOn(Schedulers.io()) // Send messages on I/O threads
            .subscribe(
                event -> {
                  try {
                    String jsonEvent = objectMapper.writeValueAsString(event);
                    log.debug(
                        "Sending event via WebSocket session {}: {}", wsSession.getId(), jsonEvent);
                    wsSession.sendMessage(new TextMessage(jsonEvent));
                  } catch (JsonProcessingException e) {
                    log.error(
                        "Error serializing event to JSON for WebSocket session {}",
                        wsSession.getId(),
                        e);
                    // Decide if to close session or just log
                  } catch (IOException e) {
                    log.error(
                        "IOException sending message via WebSocket session {}",
                        wsSession.getId(),
                        e);
                    // This might mean the session is already closed or problematic
                    // Consider closing/disposing here
                    try {
                      wsSession.close(CloseStatus.SERVER_ERROR.withReason("Error sending message"));
                    } catch (IOException closeException) {
                      log.warn(
                          "Failed to close WebSocket connection after send error: {}",
                          closeException.getMessage());
                    }
                  }
                },
                error -> {
                  log.error(
                      "Error in run_live stream for WebSocket session {}: {}",
                      wsSession.getId(),
                      error.getMessage(),
                      error);
                  String reason = error.getMessage() != null ? error.getMessage() : "Unknown error";
                  try {
                    wsSession.close(
                        new CloseStatus(
                            WEBSOCKET_INTERNAL_SERVER_ERROR,
                            reason.substring(
                                0, Math.min(reason.length(), WEBSOCKET_MAX_BYTES_FOR_REASON))));
                  } catch (IOException closeException) {
                    log.warn(
                        "Failed to close WebSocket connection after stream error: {}",
                        closeException.getMessage());
                  }
                },
                () -> {
                  log.debug(
                      "run_live stream completed for WebSocket session {}", wsSession.getId());
                  try {
                    wsSession.close(CloseStatus.NORMAL);
                  } catch (IOException closeException) {
                    log.warn(
                        "Failed to close WebSocket connection normally: {}",
                        closeException.getMessage());
                  }
                });
    wsSession.getAttributes().put(LIVE_SUBSCRIPTION_ATTR, disposable);
    log.debug("Live run started for WebSocket session {}", wsSession.getId());
  }

  @Override
  protected void handleTextMessage(WebSocketSession wsSession, TextMessage message)
      throws Exception {
    LiveRequestQueue liveRequestQueue =
        (LiveRequestQueue) wsSession.getAttributes().get(LIVE_REQUEST_QUEUE_ATTR);

    if (liveRequestQueue == null) {
      log.warn(
          "Received message on WebSocket session {} but LiveRequestQueue is not available (null)."
              + " Message: {}",
          wsSession.getId(),
          message.getPayload());
      return;
    }

    try {
      String payload = message.getPayload();
      log.debug("Received text message on WebSocket session {}: {}", wsSession.getId(), payload);

      JsonNode rootNode = objectMapper.readTree(payload);
      LiveRequest.Builder liveRequestBuilder = LiveRequest.builder();

      if (rootNode.has("content")) {
        Content content = objectMapper.treeToValue(rootNode.get("content"), Content.class);
        liveRequestBuilder.content(content);
      }

      if (rootNode.has("blob")) {
        JsonNode blobNode = rootNode.get("blob");
        Blob.Builder blobBuilder = Blob.builder();
        if (blobNode.has("displayName")) {
          blobBuilder.displayName(blobNode.get("displayName").asText());
        }
        if (blobNode.has("data")) {
          blobBuilder.data(blobNode.get("data").binaryValue());
        }
        // Handle both mime_type and mimeType. Blob states mimeType but we get mime_type from the
        // frontend.
        String mimeType =
            blobNode.has("mimeType")
                ? blobNode.get("mimeType").asText()
                : (blobNode.has("mime_type") ? blobNode.get("mime_type").asText() : null);
        if (mimeType != null) {
          blobBuilder.mimeType(mimeType);
        }
        liveRequestBuilder.blob(blobBuilder.build());
      }
      LiveRequest liveRequest = liveRequestBuilder.build();
      liveRequestQueue.send(liveRequest);
    } catch (JsonProcessingException e) {
      log.error(
          "Error deserializing LiveRequest from WebSocket message for session {}: {}",
          wsSession.getId(),
          message.getPayload(),
          e);
      wsSession.sendMessage(
          new TextMessage(
              "{\"error\":\"Invalid JSON format for LiveRequest\", \"details\":\""
                  + e.getMessage()
                  + "\"}"));
    } catch (Exception e) {
      log.error(
          "Unexpected error processing text message for WebSocket session {}: {}",
          wsSession.getId(),
          message.getPayload(),
          e);
      String reason = e.getMessage() != null ? e.getMessage() : "Error processing message";
      wsSession.close(
          new CloseStatus(
              1011,
              reason.substring(0, Math.min(reason.length(), WEBSOCKET_MAX_BYTES_FOR_REASON))));
    }
  }

  @Override
  public void handleTransportError(WebSocketSession wsSession, Throwable exception)
      throws Exception {
    log.error(
        "WebSocket transport error for session {}: {}",
        wsSession.getId(),
        exception.getMessage(),
        exception);
    // Cleanup resources similar to afterConnectionClosed
    cleanupSession(wsSession);
    if (wsSession.isOpen()) {
      String reason = exception.getMessage() != null ? exception.getMessage() : "Transport error";
      wsSession.close(
          CloseStatus.PROTOCOL_ERROR.withReason(
              reason.substring(0, Math.min(reason.length(), WEBSOCKET_MAX_BYTES_FOR_REASON))));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status)
      throws Exception {
    log.info(
        "WebSocket connection closed: {} with status {}", wsSession.getId(), status.toString());
    cleanupSession(wsSession);
  }

  private void cleanupSession(WebSocketSession wsSession) {
    LiveRequestQueue liveRequestQueue =
        (LiveRequestQueue) wsSession.getAttributes().remove(LIVE_REQUEST_QUEUE_ATTR);
    if (liveRequestQueue != null) {
      liveRequestQueue.close(); // Signal end of input to the runner
      log.debug("Called close() on LiveRequestQueue for session {}", wsSession.getId());
    }

    Disposable disposable = (Disposable) wsSession.getAttributes().remove(LIVE_SUBSCRIPTION_ATTR);
    if (disposable != null && !disposable.isDisposed()) {
      disposable.dispose();
    }
    log.debug("Cleaned up resources for WebSocket session {}", wsSession.getId());
  }
}
