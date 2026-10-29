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

package com.example.springchat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** REST endpoint that forwards chat messages from the browser to the ADK runner. */
@RestController
class ChatController {

  // The sample has no login, so every browser tab shares one user and keeps its own session.
  private static final String USER_ID = "spring-chat-user";

  private static final RunConfig RUN_CONFIG = RunConfig.builder().autoCreateSession(true).build();

  private final Runner runner;

  ChatController(Runner runner) {
    this.runner = runner;
  }

  /** A message from the browser; {@code sessionId} is generated client-side once per page load. */
  private record ChatRequest(@Nullable String sessionId, @Nullable String message) {}

  private record ChatResponse(String reply) {}

  @PostMapping("/api/chat")
  ChatResponse chat(@RequestBody ChatRequest request) {
    if (isBlank(request.sessionId()) || isBlank(request.message())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    // The session service trims ids it creates, so look them up trimmed too.
    String sessionId = request.sessionId().trim();
    Content userMessage = Content.fromParts(Part.fromText(request.message()));
    String reply =
        runner
            .runAsync(USER_ID, sessionId, userMessage, RUN_CONFIG)
            .filter(Event::finalResponse)
            .map(ChatController::replyText)
            .collect(Collectors.joining("\n"))
            .blockingGet();
    return new ChatResponse(reply.strip());
  }

  /** Returns the event's text, followed by why the model stopped, if it ended early. */
  private static String replyText(Event event) {
    String text = event.stringifyContent();
    return event.errorMessage().map(error -> text + "\n[" + error + "]").orElse(text);
  }

  private static boolean isBlank(@Nullable String s) {
    return s == null || s.trim().isEmpty();
  }
}
