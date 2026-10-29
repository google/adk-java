/*
 * Copyright 2026 Google LLC
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

package com.example.springchat.advanced;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** REST endpoint that streams the ADK agent's events to the browser as server-sent events. */
@RestController
class ChatController {

  private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

  // The sample has no login, so every browser tab shares one user and keeps its own session.
  private static final String USER_ID = "spring-chat-user";

  private static final RunConfig RUN_CONFIG =
      RunConfig.builder().autoCreateSession(true).streamingMode(StreamingMode.SSE).build();

  private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

  private final Runner runner;

  ChatController(Runner runner) {
    this.runner = runner;
  }

  /** A message from the browser; {@code sessionId} is generated client-side once per page load. */
  private record ChatRequest(@Nullable String sessionId, @Nullable String message) {}

  /**
   * One streamed update. {@code type} is {@code partial} for a chunk of a reply, {@code text} for
   * the complete reply that replaces its chunks, {@code tool_call}, {@code tool_result} or {@code
   * error}, whose {@code text} says why the model stopped, if known.
   */
  private record StreamEvent(String type, @Nullable String text, @Nullable String tool) {}

  @PostMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter chat(@RequestBody ChatRequest request) {
    if (isBlank(request.sessionId()) || isBlank(request.message())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    // The session service trims ids it creates, so look them up trimmed too.
    String sessionId = request.sessionId().trim();
    Content userMessage = Content.fromParts(Part.fromText(request.message()));

    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
    Disposable subscription =
        runner
            .runAsync(USER_ID, sessionId, userMessage, RUN_CONFIG)
            .subscribeOn(Schedulers.io())
            .subscribe(
                event -> send(emitter, event),
                error -> sendError(emitter, error),
                emitter::complete);
    // Runs however the request ends, including timeouts and a failed send to a browser that went
    // away, and stops the agent run and with it the model call.
    emitter.onCompletion(subscription::dispose);
    emitter.onTimeout(
        () -> {
          trySend(emitter, new StreamEvent("error", null, null));
          emitter.complete();
        });
    return emitter;
  }

  private static void send(SseEmitter emitter, Event event) {
    boolean partial = event.partial().orElse(false);
    String text =
        event.content().flatMap(Content::parts).stream()
            .flatMap(parts -> parts.stream())
            .filter(part -> !part.thought().orElse(false))
            .flatMap(part -> part.text().stream())
            .collect(Collectors.joining());
    if (!text.isEmpty()) {
      trySend(emitter, new StreamEvent(partial ? "partial" : "text", text, null));
    }
    // Partial events preview function calls; the complete event that follows carries them too.
    if (partial) {
      return;
    }
    // Says why the model stopped, for example when the reply was cut short or blocked. An event
    // that still asks for tools doesn't end the turn, so the agent carries on after it.
    if (event.finalResponse()) {
      event
          .errorMessage()
          .ifPresent(error -> trySend(emitter, new StreamEvent("error", error, null)));
    }
    for (FunctionCall call : event.functionCalls()) {
      trySend(emitter, new StreamEvent("tool_call", null, call.name().orElse("")));
    }
    for (FunctionResponse response : event.functionResponses()) {
      trySend(emitter, new StreamEvent("tool_result", null, response.name().orElse("")));
    }
    // A returnDirect tool's text result is the reply; parallel tools' map results are not shown.
    if (event.actions().skipSummarization().orElse(false)) {
      for (FunctionResponse response : event.functionResponses()) {
        if (response.response().orElse(Map.of()).get("result") instanceof String result) {
          trySend(emitter, new StreamEvent("text", result, null));
        }
      }
    }
  }

  private static void sendError(SseEmitter emitter, Throwable error) {
    logger.warn("Agent run failed: {}", error.getClass().getName());
    trySend(emitter, new StreamEvent("error", null, null));
    emitter.complete();
  }

  /**
   * Sends {@code event}, dropping it if the browser has gone away. The emitter then completes and
   * cancels the run, so the failure must not reach the cancelled subscriber.
   */
  private static void trySend(SseEmitter emitter, StreamEvent event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException e) {
      // Nothing left to tell the browser.
    }
  }

  private static boolean isBlank(@Nullable String s) {
    return s == null || s.trim().isEmpty();
  }
}
