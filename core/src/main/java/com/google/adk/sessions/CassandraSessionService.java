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

package com.google.adk.sessions;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.store.CassandraHelper;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * A Cassandra-backed implementation of {@link BaseSessionService}.
 *
 * <p>This implementation stores sessions and events in a Cassandra database with normalized event
 * storage matching the PostgresRunner structure.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class CassandraSessionService implements BaseSessionService {

  // private static final Logger logger = LoggerFactory.getLogger(CassandraSessionService.class);
  private final CqlSession session;
  private final ObjectMapper objectMapper;

  /** Creates a new instance of the cassandra-backed session service. */
  public CassandraSessionService() {
    this.session = CassandraHelper.getSession();
    this.objectMapper = CassandraHelper.getObjectMapper();
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");

          String resolvedSessionId =
              Optional.ofNullable(sessionId)
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .orElseGet(() -> UUID.randomUUID().toString());

          ConcurrentMap<String, Object> initialState =
              (state == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(state);
          List<Event> initialEvents = new ArrayList<>();
          Instant now = Instant.now();

          Session newSession =
              Session.builder(resolvedSessionId)
                  .appName(appName)
                  .userId(userId)
                  .state(initialState)
                  .events(initialEvents)
                  .lastUpdateTime(now)
                  .build();

          // Store session in normalized structure
          String stateJson = objectMapper.writeValueAsString(newSession.state());
          String eventDataJson = objectMapper.writeValueAsString(newSession.events());

          session.execute(
              "INSERT INTO sessions (id, app_name, user_id, state, event_data, last_update_time) VALUES (?, ?, ?, ?, ?, ?)",
              resolvedSessionId,
              appName,
              userId,
              stateJson,
              eventDataJson,
              now.toEpochMilli());

          return newSession;
        });
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    return Maybe.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(sessionId, "sessionId cannot be null");
          Objects.requireNonNull(configOpt, "configOpt cannot be null");

          Row row =
              session
                  .execute(
                      "SELECT id, app_name, user_id, state, event_data, last_update_time FROM sessions WHERE app_name = ? AND user_id = ? AND id = ?",
                      appName,
                      userId,
                      sessionId)
                  .one();

          if (row == null) {
            return null;
          }

          String stateJson = row.getString("state");
          String eventDataJson = row.getString("event_data");
          long lastUpdateTimeMillis = row.getLong("last_update_time");

          ConcurrentMap<String, Object> state =
              objectMapper.readValue(
                  stateJson,
                  objectMapper
                      .getTypeFactory()
                      .constructMapType(ConcurrentMap.class, String.class, Object.class));

          List<Event> events =
              objectMapper.readValue(
                  eventDataJson,
                  objectMapper.getTypeFactory().constructCollectionType(List.class, Event.class));

          GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());

          if (config.numRecentEvents().isPresent()) {
            int num = config.numRecentEvents().get();
            if (events.size() > num) {
              events = events.subList(events.size() - num, events.size());
            }
          } else if (config.afterTimestamp().isPresent()) {
            Instant threshold = config.afterTimestamp().get();
            events.removeIf(e -> getInstantFromEvent(e).isBefore(threshold));
          }

          Session storedSession =
              Session.builder(sessionId)
                  .appName(appName)
                  .userId(userId)
                  .state(state)
                  .events(events)
                  .lastUpdateTime(Instant.ofEpochMilli(lastUpdateTimeMillis))
                  .build();

          return storedSession;
        });
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");

          ResultSet rs =
              session.execute(
                  "SELECT id, app_name, user_id, last_update_time FROM sessions WHERE app_name = ? AND user_id = ?",
                  appName,
                  userId);

          List<Session> sessions = new ArrayList<>();
          for (Row row : rs) {
            String sessionId = row.getString("id");
            long lastUpdateTimeMillis = row.getLong("last_update_time");

            sessions.add(
                Session.builder(sessionId)
                    .appName(appName)
                    .userId(userId)
                    .lastUpdateTime(Instant.ofEpochMilli(lastUpdateTimeMillis))
                    .build());
          }

          return ListSessionsResponse.builder().sessions(sessions).build();
        });
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(sessionId, "sessionId cannot be null");

          // Delete from sessions table
          session.execute(
              "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND id = ?",
              appName,
              userId,
              sessionId);

          // Delete associated events
          session.execute("DELETE FROM events WHERE session_id = ?", sessionId);

          // Delete associated event content parts
          session.execute("DELETE FROM event_content_parts WHERE session_id = ?", sessionId);
        });
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return getSession(appName, userId, sessionId, Optional.empty())
        .map(
            session ->
                ListEventsResponse.builder().events(ImmutableList.copyOf(session.events())).build())
        .switchIfEmpty(Single.just(ListEventsResponse.builder().build()));
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(session, "session cannot be null");
          Objects.requireNonNull(event, "event cannot be null");

          // Add event to session's event list
          BaseSessionService.super.appendEvent(session, event);
          session.lastUpdateTime(getInstantFromEvent(event));

          // Update session table with new event_data and timestamp
          String eventDataJson = objectMapper.writeValueAsString(session.events());
          this.session.execute(
              "INSERT INTO sessions (id, app_name, user_id, state, event_data, last_update_time) VALUES (?, ?, ?, ?, ?, ?)",
              session.id(),
              session.appName(),
              session.userId(),
              objectMapper.writeValueAsString(session.state()),
              eventDataJson,
              session.lastUpdateTime().toEpochMilli());

          // Insert normalized event into events table
          insertEvent(session, event);

          return event;
        });
  }

  private void insertEvent(Session session, Event event) throws JsonProcessingException {
    EventActions actions = event.actions();
    String stateDelta =
        actions != null && actions.stateDelta() != null
            ? objectMapper.writeValueAsString(actions.stateDelta())
            : "{}";
    String artifactDelta =
        actions != null && actions.artifactDelta() != null
            ? objectMapper.writeValueAsString(actions.artifactDelta())
            : "{}";
    String requestedAuthConfigs =
        actions != null && actions.requestedAuthConfigs() != null
            ? objectMapper.writeValueAsString(actions.requestedAuthConfigs())
            : "{}";
    String transferToAgent =
        actions != null && actions.transferToAgent().isPresent()
            ? actions.transferToAgent().get()
            : null;

    String contentRole =
        event.content().isPresent() && event.content().get().role().isPresent()
            ? event.content().get().role().get()
            : null;

    // Insert event
    this.session.execute(
        "INSERT INTO events (id, session_id, author, actions_state_delta, actions_artifact_delta, "
            + "actions_requested_auth_configs, actions_transfer_to_agent, content_role, timestamp, "
            + "invocation_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        event.id(),
        session.id(),
        event.author(),
        stateDelta,
        artifactDelta,
        requestedAuthConfigs,
        transferToAgent,
        contentRole,
        event.timestamp(),
        event.invocationId(),
        Instant.now().toEpochMilli());

    // Insert event content parts
    if (event.content().isPresent() && event.content().get().parts().isPresent()) {
      List<com.google.genai.types.Part> parts = event.content().get().parts().get();
      for (com.google.genai.types.Part part : parts) {
        insertEventContentPart(session.id(), event.id(), part);
      }
    }
  }

  private void insertEventContentPart(
      String sessionId, String eventId, com.google.genai.types.Part part)
      throws JsonProcessingException {
    String partType;
    String textContent = null;
    String functionCallId = null;
    String functionCallName = null;
    String functionCallArgs = null;
    String functionResponseId = null;
    String functionResponseName = null;
    String functionResponseData = null;

    if (part.text().isPresent()) {
      partType = "text";
      textContent = part.text().get();
    } else if (part.functionCall().isPresent()) {
      partType = "functionCall";
      com.google.genai.types.FunctionCall fc = part.functionCall().get();
      functionCallId = fc.id().isPresent() ? fc.id().get() : null;
      functionCallName = fc.name().isPresent() ? fc.name().get() : null;
      functionCallArgs =
          fc.args().isPresent() ? objectMapper.writeValueAsString(fc.args().get()) : null;
    } else if (part.functionResponse().isPresent()) {
      partType = "functionResponse";
      com.google.genai.types.FunctionResponse fr = part.functionResponse().get();
      functionResponseId = fr.id().isPresent() ? fr.id().get() : null;
      functionResponseName = fr.name().isPresent() ? fr.name().get() : null;
      functionResponseData =
          fr.response().isPresent() ? objectMapper.writeValueAsString(fr.response().get()) : null;
    } else {
      partType = "unknown";
    }

    this.session.execute(
        "INSERT INTO event_content_parts (event_id, session_id, part_type, text_content, "
            + "function_call_id, function_call_name, function_call_args, function_response_id, "
            + "function_response_name, function_response_data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        eventId,
        sessionId,
        partType,
        textContent,
        functionCallId,
        functionCallName,
        functionCallArgs,
        functionResponseId,
        functionResponseName,
        functionResponseData,
        Instant.now().toEpochMilli());
  }

  private Instant getInstantFromEvent(Event event) {
    return Instant.ofEpochMilli(event.timestamp());
  }
}
