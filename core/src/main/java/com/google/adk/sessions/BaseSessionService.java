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
import com.google.adk.events.EventActions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * Defines the contract for managing {@link Session}s and their associated {@link Event}s. Provides
 * methods for creating, retrieving, listing, and deleting sessions, as well as listing and
 * appending events to a session. Implementations of this interface handle the underlying storage
 * and retrieval logic.
 */
public interface BaseSessionService {

  /**
   * Creates a new session with the specified parameters.
   *
   * @param appName The name of the application associated with the session.
   * @param userId The identifier for the user associated with the session.
   * @param state An optional map representing the initial state of the session. Can be null or
   *     empty.
   * @param sessionId An optional client-provided identifier for the session. If empty or null, the
   *     service should generate a unique ID.
   * @return The newly created {@link Session} instance.
   * @throws SessionException if creation fails.
   * @deprecated Use {@link #createSession(String, String, Map, String)} instead.
   */
  @Deprecated
  Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId);

  /**
   * Creates a new session with the specified parameters.
   *
   * @param appName The name of the application associated with the session.
   * @param userId The identifier for the user associated with the session.
   * @param state An optional map representing the initial state of the session. Can be null or
   *     empty.
   * @param sessionId An optional client-provided identifier for the session. If empty or null, the
   *     service should generate a unique ID.
   * @return The newly created {@link Session} instance.
   * @throws SessionException if creation fails.
   */
  default Single<Session> createSession(
      String appName,
      String userId,
      @Nullable Map<String, Object> state,
      @Nullable String sessionId) {
    return createSession(appName, userId, ensureConcurrentMap(state), sessionId);
  }

  /**
   * Creates a new session with the specified parameters.
   *
   * @param sessionKey The session key containing appName, userId and sessionId.
   * @param state An optional map representing the initial state of the session. Can be null or
   *     empty.
   */
  default Single<Session> createSession(
      SessionKey sessionKey, @Nullable Map<String, Object> state) {
    return createSession(sessionKey.appName(), sessionKey.userId(), state, sessionKey.id());
  }

  /**
   * Creates a new session with the specified application name and user ID, using a default state
   * (null) and allowing the service to generate a unique session ID.
   *
   * <p>This is a shortcut for {@link #createSession(String, String, Map, String)} with null state
   * and a null session ID.
   *
   * @param appName The name of the application associated with the session.
   * @param userId The identifier for the user associated with the session.
   * @return The newly created {@link Session} instance.
   * @throws SessionException if creation fails.
   */
  default Single<Session> createSession(String appName, String userId) {
    return createSession(appName, userId, null, null);
  }

  default Single<Session> createSession(SessionKey sessionKey) {
    return createSession(sessionKey.appName(), sessionKey.userId(), null, sessionKey.id());
  }

  /**
   * Retrieves a specific session, optionally filtering the events included.
   *
   * @param appName The name of the application.
   * @param userId The identifier of the user.
   * @param sessionId The unique identifier of the session to retrieve.
   * @param config Optional configuration to filter the events returned within the session (e.g.,
   *     limit number of recent events, filter by timestamp). If empty, default retrieval behavior
   *     is used (potentially all events or a service-defined limit).
   * @return An {@link Optional} containing the {@link Session} if found, otherwise {@link
   *     Optional#empty()}.
   * @throws SessionException for retrieval errors other than not found.
   */
  Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config);

  default Maybe<Session> getSession(SessionKey sessionKey, @Nullable GetSessionConfig config) {
    return getSession(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), Optional.ofNullable(config));
  }

  /**
   * Lists sessions associated with a specific application and user.
   *
   * <p>The {@link Session} objects in the response typically contain only metadata (like ID,
   * creation time) and not the full event list or state to optimize performance.
   *
   * @param appName The name of the application.
   * @param userId The identifier of the user whose sessions are to be listed.
   * @return A {@link ListSessionsResponse} containing a list of matching sessions.
   * @throws SessionException if listing fails.
   */
  Single<ListSessionsResponse> listSessions(String appName, String userId);

  default Single<ListSessionsResponse> listSessions(SessionKey sessionKey) {
    return listSessions(sessionKey.appName(), sessionKey.userId());
  }

  /**
   * Deletes a specific session.
   *
   * @param appName The name of the application.
   * @param userId The identifier of the user.
   * @param sessionId The unique identifier of the session to delete.
   * @throws SessionNotFoundException if the session doesn't exist.
   * @throws SessionException for other deletion errors.
   */
  Completable deleteSession(String appName, String userId, String sessionId);

  default Completable deleteSession(SessionKey sessionKey) {
    return deleteSession(sessionKey.appName(), sessionKey.userId(), sessionKey.id());
  }

  /**
   * Lists the events within a specific session. Supports pagination via the response object.
   *
   * @param appName The name of the application.
   * @param userId The identifier of the user.
   * @param sessionId The unique identifier of the session whose events are to be listed.
   * @return A {@link ListEventsResponse} containing a list of events and an optional token for
   *     retrieving the next page.
   * @throws SessionNotFoundException if the session doesn't exist.
   * @throws SessionException for other listing errors.
   */
  Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId);

  default Single<ListEventsResponse> listEvents(SessionKey sessionKey) {
    return listEvents(sessionKey.appName(), sessionKey.userId(), sessionKey.id());
  }

  /**
   * Closes a session. This is currently a placeholder and may involve finalizing session state or
   * performing cleanup actions in future implementations. The default implementation does nothing.
   *
   * @param session The session object to close.
   */
  default Completable closeSession(Session session) {
    // Default implementation does nothing.
    // TODO: Determine whether we want to finalize the session here.
    return Completable.complete();
  }

  /**
   * Appends an event to an in-memory session object and updates the session's state based on the
   * event's state delta, if applicable.
   *
   * <p>This method primarily modifies the passed {@code session} object in memory. Persisting these
   * changes typically requires a separate call to an update/save method provided by the specific
   * service implementation, or might happen implicitly depending on the implementation's design.
   *
   * <p>If the event is marked as partial (e.g., {@code event.isPartial() == true}), it is returned
   * directly without modifying the session state or event list. State delta keys starting with
   * {@link State#TEMP_PREFIX} are ignored during state updates.
   *
   * @param session The {@link Session} object to which the event should be appended (will be
   *     mutated).
   * @param event The {@link Event} to append.
   * @return The appended {@link Event} instance (or the original event if it was partial).
   * @throws NullPointerException if session or event is null.
   */
  @CanIgnoreReturnValue
  default Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");

    // If the event indicates it's partial or incomplete, don't process it yet.
    if (event.partial().orElse(false)) {
      return Single.just(event);
    }

    EventActions actions = event.actions();
    if (actions != null) {
      Map<String, Object> stateDelta = actions.stateDelta();
      Map<String, Object> sessionState = session.state();
      if (stateDelta != null && !stateDelta.isEmpty() && sessionState != null) {
        stateDelta.forEach(
            (key, value) -> {
              if (!key.startsWith(State.TEMP_PREFIX)) {
                if (value == State.REMOVED) {
                  sessionState.remove(key);
                } else {
                  sessionState.put(key, value);
                }
              }
            });
      }
    }

    List<Event> sessionEvents = session.events();
    if (sessionEvents != null) {
      sessionEvents.add(event);
    }

    return Single.just(event);
  }

  /**
   * Ensures the given {@link Map} is a {@link ConcurrentMap}. If the input is null, returns null.
   * If the input is already a {@link ConcurrentMap}, it is cast and returned. Otherwise, a new
   * {@link ConcurrentHashMap} is created from the input map.
   */
  @Nullable
  private static ConcurrentMap<String, Object> ensureConcurrentMap(
      @Nullable Map<String, Object> state) {
    if (state == null) {
      return null;
    }
    if (state instanceof ConcurrentMap<String, Object> concurrentMap) {
      return concurrentMap;
    }
    return new ConcurrentHashMap<>(state);
  }
}
