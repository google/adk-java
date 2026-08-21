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

package com.google.adk.web.controller;

import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.web.AgentGraphGenerator;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.dto.GraphResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Controller handling graph generation endpoints. */
@RestController
public class GraphController {

  private static final Logger log = LoggerFactory.getLogger(GraphController.class);

  private final BaseSessionService sessionService;
  private final AgentLoader agentProvider;

  @Autowired
  public GraphController(BaseSessionService sessionService, AgentLoader agentProvider) {
    this.sessionService = sessionService;
    this.agentProvider = agentProvider;
  }

  /**
   * Finds a session by its identifiers or throws a ResponseStatusException if not found or if
   * there's an app/user mismatch.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @return The found Session object.
   * @throws ResponseStatusException with HttpStatus.NOT_FOUND if the session doesn't exist or
   *     belongs to a different app/user.
   */
  private Session findSessionOrThrow(String appName, String userId, String sessionId) {
    Maybe<Session> maybeSession =
        sessionService.getSession(appName, userId, sessionId, Optional.empty());

    Session session = maybeSession.blockingGet();

    if (session == null) {
      log.warn(
          "Session not found for appName={}, userId={}, sessionId={}", appName, userId, sessionId);
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          String.format(
              "Session not found: appName=%s, userId=%s, sessionId=%s",
              appName, userId, sessionId));
    }

    if (!Objects.equals(session.appName(), appName) || !Objects.equals(session.userId(), userId)) {
      log.warn(
          "Session ID {} found but appName/userId mismatch (Expected: {}/{}, Found: {}/{}) -"
              + " Treating as not found.",
          sessionId,
          appName,
          userId,
          session.appName(),
          session.userId());

      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Session found but belongs to a different app/user.");
    }
    log.debug("Found session: {}", sessionId);
    return session;
  }

  /**
   * Endpoint to get a graph representation of an event (currently returns a placeholder). Requires
   * Graphviz or similar tooling for full implementation.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param eventId Event ID.
   * @return ResponseEntity containing a GraphResponse with placeholder DOT source.
   * @throws ResponseStatusException if the session or event is not found.
   */
  @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph")
  public ResponseEntity<GraphResponse> getEventGraph(
      @PathVariable String appName,
      @PathVariable String userId,
      @PathVariable String sessionId,
      @PathVariable String eventId) {
    log.info(
        "Request received for GET /apps/{}/users/{}/sessions/{}/events/{}/graph",
        appName,
        userId,
        sessionId,
        eventId);

    BaseAgent currentAppAgent;
    try {
      currentAppAgent = agentProvider.loadAgent(appName);
    } catch (java.util.NoSuchElementException e) {
      log.warn("Agent app '{}' not found for graph generation.", appName);
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(new GraphResponse("Agent app not found: " + appName));
    } catch (IllegalStateException e) {
      log.warn("Agent app '{}' failed to load for graph generation: {}", appName, e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(new GraphResponse("Agent app failed to load: " + appName));
    }

    Session session = findSessionOrThrow(appName, userId, sessionId);
    Event event =
        session.events().stream()
            .filter(e -> Objects.equals(e.id(), eventId))
            .findFirst()
            .orElse(null);

    if (event == null) {
      log.warn("Event {} not found in session {}", eventId, sessionId);
      return ResponseEntity.ok(new GraphResponse(null));
    }

    log.debug("Found event {} for graph generation.", eventId);

    List<List<String>> highlightPairs = new ArrayList<>();
    String eventAuthor = event.author();
    List<FunctionCall> functionCalls = event.functionCalls();
    List<FunctionResponse> functionResponses = event.functionResponses();

    if (!functionCalls.isEmpty()) {
      log.debug("Processing {} function calls for highlighting.", functionCalls.size());
      for (FunctionCall fc : functionCalls) {
        Optional<String> toolName = fc.name();
        if (toolName.isPresent() && !toolName.get().isEmpty()) {
          highlightPairs.add(ImmutableList.of(eventAuthor, toolName.get()));
          log.trace("Adding function call highlight: {} -> {}", eventAuthor, toolName.get());
        }
      }
    } else if (!functionResponses.isEmpty()) {
      log.debug("Processing {} function responses for highlighting.", functionResponses.size());
      for (FunctionResponse fr : functionResponses) {
        Optional<String> toolName = fr.name();
        if (toolName.isPresent() && !toolName.get().isEmpty()) {
          highlightPairs.add(ImmutableList.of(toolName.get(), eventAuthor));
          log.trace("Adding function response highlight: {} -> {}", toolName.get(), eventAuthor);
        }
      }
    } else {
      log.debug("Processing simple event, highlighting author: {}", eventAuthor);
      highlightPairs.add(ImmutableList.of(eventAuthor, eventAuthor));
    }

    Optional<String> dotSourceOpt =
        AgentGraphGenerator.getAgentGraphDotSource(currentAppAgent, highlightPairs);

    if (dotSourceOpt.isPresent()) {
      log.debug("Successfully generated graph DOT source for event {}", eventId);
      return ResponseEntity.ok(new GraphResponse(dotSourceOpt.get()));
    } else {
      log.warn(
          "Failed to generate graph DOT source for event {} with agent {}",
          eventId,
          currentAppAgent.name());
      return ResponseEntity.ok(new GraphResponse("Could not generate graph for this event."));
    }
  }
}
