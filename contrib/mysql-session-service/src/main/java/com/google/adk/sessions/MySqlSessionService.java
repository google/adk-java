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

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** A MySQL-backed implementation of {@link BaseSessionService}. */
public class MySqlSessionService implements BaseSessionService {

  private final MySqlDBHelper dbHelper;

  public MySqlSessionService(DataSource dataSource) {
    this.dbHelper = new MySqlDBHelper(dataSource);
  }

  // For testing purposes
  MySqlSessionService(MySqlDBHelper dbHelper) {
    this.dbHelper = dbHelper;
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    return validateAppAndUser(appName, userId)
        .andThen(createNewSession(appName, userId, state, sessionId));
  }

  private Single<Session> createNewSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    ConcurrentMap<String, Object> sessionState =
        (state != null) ? new ConcurrentHashMap<>(state) : new ConcurrentHashMap<>();
    String newSessionId =
        (sessionId == null || sessionId.trim().isEmpty())
            ? UUID.randomUUID().toString()
            : sessionId;

    return dbHelper
        .getInitialState(appName, userId)
        .flatMap(
            globalState -> {
              // Start with global state (defaults)
              ConcurrentHashMap<String, Object> mergedState = new ConcurrentHashMap<>(globalState);
              // Overlay initial session state (overrides)
              mergedState.putAll(sessionState);

              Session session =
                  Session.builder(newSessionId)
                      .appName(appName)
                      .userId(userId)
                      .state(new State(mergedState))
                      .events(new ArrayList<>())
                      .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                      .build();

              return dbHelper.saveSession(session).toSingleDefault(session);
            });
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    return validateAppAndUser(appName, userId)
        .andThen(validateSessionId(sessionId))
        .andThen(dbHelper.getSession(appName, userId, sessionId, config));
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return validateAppAndUser(appName, userId)
        .andThen(
            dbHelper
                .listSessions(appName, userId)
                .map(sessions -> ListSessionsResponse.builder().sessions(sessions).build()));
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return validateAppAndUser(appName, userId)
        .andThen(validateSessionId(sessionId))
        .andThen(dbHelper.deleteSession(appName, userId, sessionId));
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return validateAppAndUser(appName, userId)
        .andThen(validateSessionId(sessionId))
        .andThen(
            dbHelper
                .listEvents(appName, userId, sessionId)
                .map(events -> ListEventsResponse.builder().events(events).build()));
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    if (session == null) {
      return Single.error(new IllegalArgumentException("session cannot be null."));
    }
    if (event == null) {
      return Single.error(new IllegalArgumentException("event cannot be null."));
    }

    // If the event indicates it's partial or incomplete, don't process it yet.
    if (event.partial().orElse(false)) {
      return Single.just(event);
    }

    // Persist the new event and update state transactionally first.
    // Only if DB update succeeds, update the in-memory session object using the base
    // implementation.
    return dbHelper
        .appendEventAndUpdateState(session, event)
        .andThen(BaseSessionService.super.appendEvent(session, event));
  }

  private Completable validateAppAndUser(String appName, String userId) {
    if (appName == null || appName.trim().isEmpty()) {
      return Completable.error(new IllegalArgumentException("appName cannot be null or empty."));
    }
    if (userId == null || userId.trim().isEmpty()) {
      return Completable.error(new IllegalArgumentException("userId cannot be null or empty."));
    }
    return Completable.complete();
  }

  private Completable validateSessionId(String sessionId) {
    if (sessionId == null || sessionId.trim().isEmpty()) {
      return Completable.error(new IllegalArgumentException("sessionId cannot be null or empty."));
    }
    return Completable.complete();
  }
}
