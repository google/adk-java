/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.sessions;

// 导入项目中的现有类
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.SessionException;
import com.google.adk.sessions.SessionNotFoundException;
import com.google.common.collect.ImmutableList;

// 导入 RxJava
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

// 导入 Jedis (Redis 客户端)
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

// 导入 Java 标准库
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * BaseSessionService 的生产级实现，使用 Redis 进行分布式持久化。
 * (v4 - 包含 PR #579 的所有修改)
 */
public final class RedisSessionService implements BaseSessionService {

    private static final Logger logger = LoggerFactory.getLogger(RedisSessionService.class);

    // 【FIX 2】: 定义常量，避免“魔法数字”
    private static final int DEFAULT_MAX_RETRIES = 5;
    // 【FIX 4】: 定义常量，避免“魔法数字”
    private static final int DEFAULT_SCAN_COUNT = 100;

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final int maxRetries;

    /**
     * 构造函数，使用依赖注入。
     *
     * @param jedisPool Redis 连接池。
     */
    public RedisSessionService(JedisPool jedisPool) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "jedisPool cannot be null");
        this.keyPrefix = "adk:session:";
        // 【FIX 2】: 使用常量
        this.maxRetries = DEFAULT_MAX_RETRIES;
    }

    private String getKey(String appName, String userId, String sessionId) {
        Objects.requireNonNull(appName, "appName cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return keyPrefix + appName + ":" + userId + ":" + sessionId;
    }

    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            @Nullable ConcurrentMap<String, Object> state,
            @Nullable String sessionId) {

        String resolvedSessionId =
                Optional.ofNullable(sessionId)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .orElseGet(() -> UUID.randomUUID().toString());

        return Single.fromCallable(() -> {
            String key = getKey(appName, userId, resolvedSessionId);
            Session newSession =
                    Session.builder(resolvedSessionId)
                            .appName(appName)
                            .userId(userId)
                            .state(state == null ? new ConcurrentHashMap<>() : state)
                            .events(new ArrayList<>())
                            .lastUpdateTime(Instant.now())
                            .build();
            String newJson = newSession.toJson();
            try (Jedis jedis = jedisPool.getResource()) {
                long result = jedis.setnx(key, newJson);
                if (result == 1) return newSession;
                else throw new SessionException("Session already exists: " + key);
            } catch (JedisException e) {
                throw new SessionException("Redis error during createSession", e);
            }
        });
    }

    @Override
    public Maybe<Session> getSession(
            String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {

        return Maybe.fromCallable(() -> {
            String key = getKey(appName, userId, sessionId);
            try (Jedis jedis = jedisPool.getResource()) {
                String sessionJson = jedis.get(key);

                if (sessionJson == null) {
                    return null; // 触发 Maybe.empty()
                }
                Session session = Session.fromJson(sessionJson);
                Session sessionCopy = Session.builder(session.id())
                        .appName(session.appName())
                        .userId(session.userId())
                        .state(new ConcurrentHashMap<>(session.state()))
                        .events(new ArrayList<>(session.events()))
                        .lastUpdateTime(session.lastUpdateTime())
                        .build();

                GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
                List<Event> eventsInCopy = sessionCopy.events();
                config.numRecentEvents().ifPresent(num -> {
                    if (!eventsInCopy.isEmpty() && num < eventsInCopy.size()) {
                        List<Event> recentEvents = new ArrayList<>(
                                eventsInCopy.subList(eventsInCopy.size() - num, eventsInCopy.size()));
                        eventsInCopy.clear();
                        eventsInCopy.addAll(recentEvents);
                    }
                });
                if (config.numRecentEvents().isEmpty() && config.afterTimestamp().isPresent()) {
                    Instant threshold = config.afterTimestamp().get();
                    eventsInCopy.removeIf(
                            event -> (event.timestamp() / 1000L) < threshold.getEpochSecond());
                }
                return sessionCopy;
            } catch (JedisException e) {
                throw new SessionException("Redis error during getSession", e);
            }
        });
        // 【FIX 3】: 删除了 .switchIfEmpty(...)，让 Maybe 在未找到时自然 onComplete()
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(() -> {
            List<Session> sessions = new ArrayList<>();
            String matchPattern = getKey(appName, userId, "*");
            try (Jedis jedis = jedisPool.getResource()) {
                String cursor = "0";
                // 【FIX 4】: 使用常量
                ScanParams scanParams = new ScanParams().match(matchPattern).count(DEFAULT_SCAN_COUNT);
                do {
                    ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    cursor = scanResult.getCursor();
                    List<String> keys = scanResult.getResult();
                    if (!keys.isEmpty()) {
                        List<String> jsonList = jedis.mget(keys.toArray(new String[0]));
                        for (String sessionJson : jsonList) {
                            if (sessionJson != null) {
                                Session fullSession = Session.fromJson(sessionJson);
                                sessions.add(
                                        Session.builder(fullSession.id())
                                                .appName(fullSession.appName())
                                                .userId(fullSession.userId())
                                                .state(fullSession.state())
                                                .lastUpdateTime(fullSession.lastUpdateTime())
                                                .events(new ArrayList<>())
                                                .build()
                                );
                            }
                        }
                    }
                } while (!"0".equals(cursor));
            }
            return ListSessionsResponse.builder().sessions(sessions).build();
        });
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return Single.fromCallable(() -> {
            String key = getKey(appName, userId, sessionId);
            try(Jedis jedis = jedisPool.getResource()) {
                String sessionJson = jedis.get(key);
                if (sessionJson == null) {
                    throw new SessionNotFoundException(key);
                }
                Session session = Session.fromJson(sessionJson);
                return ListEventsResponse.builder()
                        .events(ImmutableList.copyOf(session.events()))
                        .build();
            }
        }).onErrorReturn(t -> ListEventsResponse.builder().build());
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(() -> {
            String key = getKey(appName, userId, sessionId);
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
            } catch (JedisException e) {
                throw new SessionException("Redis error during deleteSession", e);
            }
        });
    }

    @Override
    public Single<Event> appendEvent(Session staleSession, Event event) {
        String appName = Objects.requireNonNull(staleSession.appName());
        String userId = Objects.requireNonNull(staleSession.userId());
        String sessionId = Objects.requireNonNull(staleSession.id());
        String key = getKey(appName, userId, sessionId);

        return Single.fromCallable(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                for (int i = 0; i < maxRetries; i++) {
                    jedis.watch(key);
                    String currentJson = jedis.get(key);
                    if (currentJson == null) {
                        jedis.unwatch();
                        throw new SessionNotFoundException(key);
                    }
                    Session currentSession = Session.fromJson(currentJson);

                    // (A) 添加 Event
                    currentSession.events().add(event);

                    // 【FIX 1】: 复制 Code Review 建议的完整 stateDelta 逻辑
                    EventActions actions = event.actions();
                    if (actions != null) {
                        Map<String, Object> stateDelta = actions.stateDelta();
                        if (stateDelta != null && !stateDelta.isEmpty()) {
                            ConcurrentMap<String, Object> sessionState = currentSession.state();
                            stateDelta.forEach(
                                    (k, value) -> {
                                        // 忽略临时键
                                        if (!k.startsWith(State.TEMP_PREFIX)) {
                                            // 检查是否为“删除”标记
                                            if (value == State.REMOVED) {
                                                sessionState.remove(k);
                                            } else {
                                                sessionState.put(k, value);
                                            }
                                        }
                                    });
                        }
                    }

                    // (C) 更新时间戳
                    currentSession.lastUpdateTime(Instant.ofEpochMilli(event.timestamp()));
                    String newJson = currentSession.toJson();

                    Transaction tx = jedis.multi();
                    tx.set(key, newJson);
                    List<Object> result = tx.exec();

                    if (result != null) {
                        logger.debug("Successfully appended event to session: {}", key);
                        return event;
                    }
                    logger.warn("Redis appendEvent conflict, retrying (attempt {}/{}) for session: {}", i + 1, maxRetries, key);
                }
                throw new ConcurrentModificationException(
                        "Failed to append event after " + maxRetries + " retries due to high contention for session: " + key);
            } catch (JedisException e) {
                throw new SessionException("Redis error during appendEvent", e);
            }
        });
    }
}