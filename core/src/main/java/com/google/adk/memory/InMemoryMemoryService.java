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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-memory memory service for prototyping purposes only.
 *
 * <p>Uses keyword matching instead of semantic search.
 */
public final class InMemoryMemoryService implements BaseMemoryService {

  // Pattern to extract words, matching the Python version.
  private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");

  /** Keys are "app_name/user_id", values are maps of "session_id" to a list of events. */
  private final Map<String, Map<String, List<Event>>> sessionEvents;

  public InMemoryMemoryService() {
    this.sessionEvents = new ConcurrentHashMap<>();
  }

  private static String userKey(String appName, String userId) {
    return appName + "/" + userId;
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.fromAction(
        () -> {
          String key = userKey(session.appName(), session.userId());
          Map<String, List<Event>> userSessions =
              sessionEvents.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
          ImmutableList<Event> nonEmptyEvents =
              session.events().stream()
                  .filter(
                      event ->
                          event
                              .content()
                              .flatMap(c -> c.parts())
                              .filter(parts -> !parts.isEmpty())
                              .isPresent())
                  .collect(toImmutableList());
          userSessions.put(session.id(), nonEmptyEvents);
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return Single.fromCallable(
        () -> {
          String key = userKey(appName, userId);

          if (!sessionEvents.containsKey(key)) {
            return SearchMemoryResponse.builder().build();
          }

          Map<String, List<Event>> userSessions = sessionEvents.get(key);

          ImmutableSet<String> wordsInQuery =
              ImmutableSet.copyOf(query.toLowerCase(Locale.ROOT).split("\\s+"));

          List<MemoryEntry> matchingMemories = new ArrayList<>();

          for (List<Event> eventsInSession : userSessions.values()) {
            for (Event event : eventsInSession) {
              if (event.content().isEmpty() || event.content().get().parts().isEmpty()) {
                continue;
              }

              Set<String> wordsInEvent = new HashSet<>();
              for (Part part : event.content().get().parts().get()) {
                if (!Strings.isNullOrEmpty(part.text().get())) {
                  Matcher matcher = WORD_PATTERN.matcher(part.text().get());
                  while (matcher.find()) {
                    wordsInEvent.add(matcher.group().toLowerCase(Locale.ROOT));
                  }
                }
              }

              if (wordsInEvent.isEmpty()) {
                continue;
              }

              if (!Collections.disjoint(wordsInQuery, wordsInEvent)) {
                MemoryEntry memory =
                    MemoryEntry.builder()
                        .content(event.content().get())
                        .author(event.author())
                        .timestamp(formatTimestamp(event.timestamp()))
                        .build();
                matchingMemories.add(memory);
              }
            }
          }

          return SearchMemoryResponse.builder()
              .setMemories(ImmutableList.copyOf(matchingMemories))
              .build();
        });
  }

  private String formatTimestamp(long timestamp) {
    return Instant.ofEpochSecond(timestamp).toString();
  }
}
