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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.store.RedisHelper;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import reactor.adapter.rxjava.RxJava3Adapter;

/**
 * A Redis-backed implementation of {@link BaseSessionService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class RedisSessionService implements BaseSessionService {

  private final RedisReactiveCommands<String, String> commands;
  private final ObjectMapper objectMapper;

  public RedisSessionService() {
    StatefulRedisConnection<String, String> connection = RedisHelper.getConnection();
    this.commands = connection.reactive();
    this.objectMapper = RedisHelper.getObjectMapper();
  }

  private String sessionKey(String appName, String userId) {
    return String.format("session:%s:%s", appName, userId);
  }

  private String userStateKey(String appName, String userId) {
    return String.format("user_state:%s:%s", appName, userId);
  }

  private String appStateKey(String appName) {
    return String.format("app_state:%s", appName);
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    return Single.fromCallable(
            () -> {
              String resolvedSessionId =
                  Optional.ofNullable(sessionId)
                      .map(String::trim)
                      .filter(s -> !s.isEmpty())
                      .orElseGet(() -> UUID.randomUUID().toString());

              Session newSession =
                  Session.builder(resolvedSessionId)
                      .appName(appName)
                      .userId(userId)
                      .state(state != null ? state : new ConcurrentHashMap<>())
                      .events(new ArrayList<>())
                      .lastUpdateTime(Instant.now())
                      .build();
              return newSession;
            })
        .flatMap(
            newSession ->
                RxJava3Adapter.monoToSingle(
                        commands.hset(
                            sessionKey(appName, userId),
                            newSession.id(),
                            objectMapper.writeValueAsString(newSession)))
                    .map(v -> newSession))
        .flatMap(session -> mergeWithGlobalState(appName, userId, session));
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    return RxJava3Adapter.monoToMaybe(commands.hget(sessionKey(appName, userId), sessionId))
        .flatMap(
            data -> {
              Session session = objectMapper.readValue(data, Session.class);
              return mergeWithGlobalState(appName, userId, session).toMaybe();
            })
        .map(
            session -> {
              GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
              List<Event> events = new ArrayList<>(session.events());
              if (config.numRecentEvents().isPresent()) {
                int num = config.numRecentEvents().get();
                if (events.size() > num) {
                  events = events.subList(events.size() - num, events.size());
                }
              } else if (config.afterTimestamp().isPresent()) {
                Instant threshold = config.afterTimestamp().get();
                events.removeIf(e -> Instant.ofEpochMilli(e.timestamp()).isBefore(threshold));
              }
              return Session.builder(session.id())
                  .appName(session.appName())
                  .userId(session.userId())
                  .state(session.state())
                  .events(events)
                  .lastUpdateTime(session.lastUpdateTime())
                  .build();
            });
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return RxJava3Adapter.fluxToFlowable(commands.hvals(sessionKey(appName, userId)))
        .map(data -> objectMapper.readValue(data, Session.class))
        .map(
            session ->
                Session.builder(session.id())
                    .appName(session.appName())
                    .userId(session.userId())
                    .lastUpdateTime(session.lastUpdateTime())
                    .build())
        .toList()
        .map(sessions -> ListSessionsResponse.builder().sessions(sessions).build());
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return RxJava3Adapter.monoToCompletable(commands.hdel(sessionKey(appName, userId), sessionId));
  }

  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return getSession(appName, userId, sessionId, Optional.empty())
        .map(session -> ListEventsResponse.builder().events(session.events()).build())
        .switchIfEmpty(Single.just(ListEventsResponse.builder().build()));
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    Completable stateUpdate =
        Completable.fromAction(
            () -> {
              EventActions actions = event.actions();
              if (actions != null) {
                Map<String, Object> stateDelta = actions.stateDelta();
                if (stateDelta != null && !stateDelta.isEmpty()) {
                  stateDelta.forEach(
                      (key, value) -> {
                        try {
                          String valueStr = objectMapper.writeValueAsString(value);
                          if (key.startsWith(State.APP_PREFIX)) {
                            String appStateKey = key.substring(State.APP_PREFIX.length());
                            commands
                                .hset(appStateKey(session.appName()), appStateKey, valueStr)
                                .subscribe();
                          } else if (key.startsWith(State.USER_PREFIX)) {
                            String userStateKey = key.substring(State.USER_PREFIX.length());
                            commands
                                .hset(
                                    userStateKey(session.appName(), session.userId()),
                                    userStateKey,
                                    valueStr)
                                .subscribe();
                          }
                        } catch (Exception e) {
                          // log error
                        }
                      });
                }
              }
            });

    return stateUpdate
        .andThen(
            Single.fromCallable(
                () -> {
                  BaseSessionService.super.appendEvent(session, event);
                  session.lastUpdateTime(Instant.ofEpochMilli(event.timestamp()));
                  return session;
                }))
        .flatMap(
            updatedSession ->
                RxJava3Adapter.monoToSingle(
                        commands.hset(
                            sessionKey(updatedSession.appName(), updatedSession.userId()),
                            updatedSession.id(),
                            objectMapper.writeValueAsString(updatedSession)))
                    .map(v -> event));
  }

  private Single<Session> mergeWithGlobalState(String appName, String userId, Session session) {
    return RxJava3Adapter.monoToSingle(
            commands
                .hgetall(appStateKey(appName))
                .collectMap(kv -> kv.getKey(), kv -> kv.getValue()))
        .flatMap(
            appState -> {
              appState.forEach(
                  (key, value) -> {
                    try {
                      session
                          .state()
                          .put(State.APP_PREFIX + key, objectMapper.readValue(value, Object.class));
                    } catch (Exception e) {
                      // log error
                    }
                  });
              return RxJava3Adapter.monoToSingle(
                  commands
                      .hgetall(userStateKey(appName, userId))
                      .collectMap(kv -> kv.getKey(), kv -> kv.getValue()));
            })
        .map(
            userState -> {
              userState.forEach(
                  (key, value) -> {
                    try {
                      session
                          .state()
                          .put(
                              State.USER_PREFIX + key, objectMapper.readValue(value, Object.class));
                    } catch (Exception e) {
                      // log error
                    }
                  });
              return session;
            });
  }
}
