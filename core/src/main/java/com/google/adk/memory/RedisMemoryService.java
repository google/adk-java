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

package com.google.adk.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.store.RedisHelper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.adapter.rxjava.RxJava3Adapter;

/**
 * A Redis-backed implementation of the {@link BaseMemoryService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class RedisMemoryService implements BaseMemoryService {

  private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");
  private final RedisReactiveCommands<String, String> commands;
  private final ObjectMapper objectMapper;

  public RedisMemoryService() {
    StatefulRedisConnection<String, String> connection = RedisHelper.getConnection();
    this.commands = connection.reactive();
    this.objectMapper = RedisHelper.getObjectMapper();
  }

  private String eventKey(String appName, String userId, String eventId) {
    return String.format("event:%s:%s:%s", appName, userId, eventId);
  }

  private String indexKey(String appName, String userId, String word) {
    return String.format("index:%s:%s:%s", appName, userId, word);
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.fromRunnable(
        () -> {
          for (Event event : session.events()) {
            if (event.content().isEmpty()
                || event.content().get().parts().isEmpty()
                || event.content().get().parts().get().isEmpty()) {
              continue;
            }
            String eventId = UUID.randomUUID().toString();
            try {
              String eventData = objectMapper.writeValueAsString(event);
              commands
                  .set(eventKey(session.appName(), session.userId(), eventId), eventData)
                  .subscribe();
              for (Part part : event.content().get().parts().get()) {
                if (!Strings.isNullOrEmpty(part.text().get())) {
                  Matcher matcher = WORD_PATTERN.matcher(part.text().get());
                  while (matcher.find()) {
                    String word = matcher.group().toLowerCase(Locale.ROOT);
                    commands
                        .sadd(indexKey(session.appName(), session.userId(), word), eventId)
                        .subscribe();
                  }
                }
              }
            } catch (Exception e) {
              // log error
            }
          }
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    ImmutableSet<String> wordsInQuery =
        ImmutableSet.copyOf(query.toLowerCase(Locale.ROOT).split("\\s+"));
    String[] keys =
        wordsInQuery.stream().map(word -> indexKey(appName, userId, word)).toArray(String[]::new);

    return RxJava3Adapter.fluxToFlowable(commands.sunion(keys))
        .flatMap(
            eventId ->
                RxJava3Adapter.monoToFlowable(commands.get(eventKey(appName, userId, eventId))))
        .map(
            eventData -> {
              try {
                Event event = objectMapper.readValue(eventData, Event.class);
                return MemoryEntry.builder()
                    .content(event.content().get())
                    .author(event.author())
                    .timestamp(Instant.ofEpochMilli(event.timestamp()).toString())
                    .build();
              } catch (Exception e) {
                return MemoryEntry.builder().build(); // Or handle error appropriately
              }
            })
        .toList()
        .map(
            memories ->
                SearchMemoryResponse.builder().setMemories(ImmutableList.copyOf(memories)).build());
  }
}
