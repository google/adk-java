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
import java.util.ConcurrentModificationException; // 【修正 B】: 从 java.util 导入
import java.util.concurrent.ConcurrentMap;         // 【修正 A】: 添加了 ConcurrentMap 的 import
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * BaseSessionService 的生产级实现，使用 Redis 进行分布式持久化。
 * (v3 - 修正了 import 错误)
 */
public final class RedisSessionService implements BaseSessionService {

    private static final Logger logger = LoggerFactory.getLogger(RedisSessionService.class);

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final int maxRetries; // 乐观锁最大重试次数

    /**
     * 构造函数，使用依赖注入。
     *
     * @param jedisPool Redis 连接池。
     */
    public RedisSessionService(JedisPool jedisPool) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "jedisPool cannot be null");
        this.keyPrefix = "adk:session:"; // Redis 键前缀，防止冲突
        this.maxRetries = 5;             // 设置最大并发重试次数
    }

    /**
     * 生成存储在 Redis 中的唯一键。
     */
    private String getKey(String appName, String userId, String sessionId) {
        Objects.requireNonNull(appName, "appName cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return keyPrefix + appName + ":" + userId + ":" + sessionId;
    }

    /**
     * 【实现】创建 Session。
     * (方法签名现在与 BaseSessionService 接口匹配)
     */
    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            @Nullable ConcurrentMap<String, Object> state, // 【修正 A】: 现在可以正确解析此类型
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
                            .state(state == null ? new ConcurrentHashMap<>() : state) // 【修正 A】: ConcurrentHashMap 也被正确导入
                            .events(new ArrayList<>())
                            .lastUpdateTime(Instant.now())
                            .build();

            String newJson = newSession.toJson();

            try (Jedis jedis = jedisPool.getResource()) {
                long result = jedis.setnx(key, newJson); // "SETNX" 拼写正确

                if (result == 1) {
                    return newSession;
                } else {
                    throw new SessionException("Session already exists: " + key);
                }
            } catch (JedisException e) {
                throw new SessionException("Redis error during createSession", e);
            }
        });
    }

    /**
     * 【实现】获取 Session。
     * (方法签名现在与 BaseSessionService 接口匹配)
     */
    @Override
    public Maybe<Session> getSession(
            String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {

        return Maybe.fromCallable(() -> {
                    String key = getKey(appName, userId, sessionId);
                    try (Jedis jedis = jedisPool.getResource()) {
                        String sessionJson = jedis.get(key);

                        if (sessionJson == null) {
                            return null;
                        }

                        Session session = Session.fromJson(sessionJson);

                        Session sessionCopy = Session.builder(session.id())
                                .appName(session.appName())
                                .userId(session.userId())
                                .state(new ConcurrentHashMap<>(session.state())) // 【修正 A】: ConcurrentHashMap 已导入
                                .events(new ArrayList<>(session.events()))
                                .lastUpdateTime(session.lastUpdateTime())
                                .build();

                        // 过滤逻辑 (与 InMemory... 保持一致)
                        GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
                        List<Event> eventsInCopy = sessionCopy.events();

                        config
                                .numRecentEvents()
                                .ifPresent(
                                        num -> {
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
                })
                .switchIfEmpty(Maybe.error(new SessionNotFoundException(sessionId)));
    }

    /**
     * 【实现】列出所有 Session (元数据)。
     */
    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(() -> {
            List<Session> sessions = new ArrayList<>();
            String matchPattern = getKey(appName, userId, "*");

            try (Jedis jedis = jedisPool.getResource()) {
                String cursor = "0";
                ScanParams scanParams = new ScanParams().match(matchPattern).count(100);

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

    /**
     * 【实现】列出单个 Session 的所有 Event。
     */
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
                })
                .onErrorReturn(t -> ListEventsResponse.builder().build());
    }

    /**
     * 【实现】删除 Session。
     */
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

    /**
     * 【实现】【核心】追加一个 Event。
     */
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

                    // (B) 检查并应用 stateDelta
                    EventActions actions = event.actions();
                    if (actions != null) {
                        Map<String, Object> stateDelta = actions.stateDelta();
                        if (stateDelta != null && !stateDelta.isEmpty()) {
                            currentSession.state().putAll(stateDelta);
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

                // 【修正 B】: 现在可以正确解析此异常
                throw new ConcurrentModificationException(
                        "Failed to append event after " + maxRetries + " retries due to high contention for session: " + key);
            } catch (JedisException e) {
                throw new SessionException("Redis error during appendEvent", e);
            }
        });
    }
}