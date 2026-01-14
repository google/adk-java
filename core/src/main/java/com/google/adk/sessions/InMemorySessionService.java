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

import static java.util.stream.Collectors.toCollection;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory implementation of {@link BaseSessionService} assuming {@link Session} objects are
 * mutable regarding their state map, events list, and last update time.
 *
 * <p>This implementation stores sessions, user state, and app state directly in memory using
 * concurrent maps for basic thread safety. It is suitable for testing or single-node deployments
 * where persistence is not required.
 *
 * <p>Note: State merging (app/user state prefixed with {@code _app_} / {@code _user_}) occurs
 * during retrieval operations ({@code getSession}, {@code createSession}).
 */
public final class InMemorySessionService implements BaseSessionService {
  // Structure: appName -> userId -> sessionId -> Session
  private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Session>>>
      sessions;
  // Structure: appName -> userId -> stateKey -> stateValue
  private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Object>>>
      userState;
  // Structure: appName -> stateKey -> stateValue
  private final ConcurrentMap<String, ConcurrentMap<String, Object>> appState;

  /** Creates a new instance of the in-memory session service with empty storage. */
  public InMemorySessionService() {
    this.sessions = new ConcurrentHashMap<>();
    this.userState = new ConcurrentHashMap<>();
    this.appState = new ConcurrentHashMap<>();
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    String resolvedSessionId =
        Optional.ofNullable(sessionId)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .orElseGet(() -> UUID.randomUUID().toString());

    // Ensure state map and events list are mutable for the new session
    ConcurrentMap<String, Object> initialState =
        (state == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(state);
    List<Event> initialEvents = new ArrayList<>();

    // Assuming Session constructor or setters allow setting these mutable collections
    Session newSession =
        Session.builder(resolvedSessionId)
            .appName(appName)
            .userId(userId)
            .state(initialState)
            .events(initialEvents)
            .lastUpdateTime(Instant.now())
            .build();

    sessions
        .computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
        .put(resolvedSessionId, newSession);

    // Create a mutable copy for the return value
    Session returnCopy = copySession(newSession);
    // Merge state into the copy before returning
    return Single.just(mergeWithGlobalState(appName, userId, returnCopy));
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(configOpt, "configOpt cannot be null");

    Session storedSession =
        sessions
            .getOrDefault(appName, new ConcurrentHashMap<>())
            .getOrDefault(userId, new ConcurrentHashMap<>())
            .get(sessionId);

    if (storedSession == null) {
      return Maybe.empty();
    }

    Session sessionCopy = copySession(storedSession);

    // Apply filtering based on config directly to the mutable list in the copy
    GetSessionConfig config = configOpt.orElseGet(() -> GetSessionConfig.builder().build());
    List<Event> eventsInCopy = sessionCopy.events();

    config
        .numRecentEvents()
        .ifPresent(
            num -> {
              if (!eventsInCopy.isEmpty() && num < eventsInCopy.size()) {
                // Keep the last 'num' events by removing older ones
                // Create sublist view (modifications affect original list)

                List<Event> eventsToRemove = eventsInCopy.subList(0, eventsInCopy.size() - num);
                eventsToRemove.clear(); // Clear the sublist view, modifying eventsInCopy
              }
            });

    // Only apply timestamp filter if numRecentEvents was not applied
    if (config.numRecentEvents().isEmpty() && config.afterTimestamp().isPresent()) {
      Instant threshold = config.afterTimestamp().get();

      eventsInCopy.removeIf(
          event -> getEventTimestampEpochSeconds(event) < threshold.getEpochSecond());
    }

    // Merge state into the potentially filtered copy and return
    return Maybe.just(mergeWithGlobalState(appName, userId, sessionCopy));
  }

  // Helper to get event timestamp as epoch seconds
  private long getEventTimestampEpochSeconds(Event event) {
    return event.timestamp() / 1000L;
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    Map<String, Session> userSessionsMap =
        sessions.getOrDefault(appName, new ConcurrentHashMap<>()).get(userId);

    if (userSessionsMap == null || userSessionsMap.isEmpty()) {
      return Single.just(ListSessionsResponse.builder().build());
    }

    // Create copies with empty events and state for the response
    List<Session> sessionCopies =
        prepareSessionsForListResponse(appName, userId, userSessionsMap.values());

    return Single.just(ListSessionsResponse.builder().sessions(sessionCopies).build());
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    ConcurrentMap<String, Session> userSessionsMap =
        sessions.getOrDefault(appName, new ConcurrentHashMap<>()).get(userId);

    if (userSessionsMap != null) {
      userSessionsMap.remove(sessionId);
    }
    return Completable.complete();
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    Session storedSession =
        sessions
            .getOrDefault(appName, new ConcurrentHashMap<>())
            .getOrDefault(userId, new ConcurrentHashMap<>())
            .get(sessionId);

    if (storedSession == null) {
      return Single.just(ListEventsResponse.builder().build());
    }

    ImmutableList<Event> eventsCopy = ImmutableList.copyOf(storedSession.events());
    return Single.just(ListEventsResponse.builder().events(eventsCopy).build());
  }

  @CanIgnoreReturnValue
  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");

    String appName = session.appName();
    String userId = session.userId();
    String sessionId = session.id();

    // --- Update User/App State (Same as before) ---
    EventActions actions = event.actions();
    if (actions != null) {
      Map<String, Object> stateDelta = actions.stateDelta();
      if (stateDelta != null && !stateDelta.isEmpty()) {
        stateDelta.forEach(
            (key, value) -> {
              if (key.startsWith(State.APP_PREFIX)) {
                String appStateKey = key.substring(State.APP_PREFIX.length());
                appState
                    .computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                    .put(appStateKey, value);
              } else if (key.startsWith(State.USER_PREFIX)) {
                String userStateKey = key.substring(State.USER_PREFIX.length());
                userState
                    .computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(userStateKey, value);
              } else {
                session.state().put(key, value);
              }
            });
      }
    }

    BaseSessionService.super.appendEvent(session, event);
    session.lastUpdateTime(getInstantFromEvent(event));

    // --- Update the session stored in this service ---
    sessions
        .computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
        .put(sessionId, session);

    mergeWithGlobalState(appName, userId, session);

    return Single.just(event);
  }

  /** Converts an event's timestamp to an Instant. Adapt based on actual Event structure. */
  // TODO: have Event.timestamp() return Instant directly
  private Instant getInstantFromEvent(Event event) {
    double epochSeconds = getEventTimestampEpochSeconds(event);
    long seconds = (long) epochSeconds;
    long nanos = (long) ((epochSeconds % 1.0) * 1_000_000_000L);
    return Instant.ofEpochSecond(seconds, nanos);
  }

  /**
   * Creates a shallow copy of the session, but with deep copies of the mutable state map and events
   * list. Assumes Session provides necessary getters and a suitable constructor/setters.
   *
   * @param original The session to copy.
   * @return A new Session instance with copied data, including mutable collections.
   */
  private Session copySession(Session original) {
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .state(new ConcurrentHashMap<>(original.state()))
        .events(new ArrayList<>(original.events()))
        .lastUpdateTime(original.lastUpdateTime())
        .build();
  }

  /**
   * Merges the app-specific and user-specific state into the provided *mutable* session's state
   * map.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param session The mutable session whose state map will be augmented.
   * @return The same session instance passed in, now with merged state.
   */
  @CanIgnoreReturnValue
  private Session mergeWithGlobalState(String appName, String userId, Session session) {
    Map<String, Object> sessionState = session.state();

    // Merge App State directly into the session's state map
    appState
        .getOrDefault(appName, new ConcurrentHashMap<String, Object>())
        .forEach((key, value) -> sessionState.put(State.APP_PREFIX + key, value));

    userState
        .getOrDefault(appName, new ConcurrentHashMap<>())
        .getOrDefault(userId, new ConcurrentHashMap<>())
        .forEach((key, value) -> sessionState.put(State.USER_PREFIX + key, value));

    return session;
  }

  /**
   * Prepares copies of sessions for use in a {@code listSessions} response.
   *
   * <p>For each session provided, this method creates a deep copy, clears the copy's event list,
   * and merges app-level and user-level state into the copy's state map.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessions The collection of sessions to process.
   * @return A list of processed {@link Session} copies.
   */
  private List<Session> prepareSessionsForListResponse(
      String appName, String userId, Collection<Session> sessions) {
    return sessions.stream()
        .map(this::copySession)
        .peek(s -> s.events().clear())
        .map(s -> mergeWithGlobalState(appName, userId, s))
        .collect(toCollection(ArrayList::new));
  }
}
