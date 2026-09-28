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
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
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

  // Unicode-aware word pattern, close to Python's \w+.
  private static final Pattern WORD_PATTERN =
      Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);

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

          ImmutableSet<String> wordsInQuery = extractWordsLower(query);

          List<MemoryEntry> matchingMemories = new ArrayList<>();

          for (List<Event> eventsInSession : userSessions.values()) {
            for (Event event : eventsInSession) {
              if (event.content().isEmpty() || event.content().get().parts().isEmpty()) {
                continue;
              }

              Set<String> wordsInEvent = new HashSet<>();
              List<String> eventTexts = new ArrayList<>();
              for (Part part : event.content().get().parts().get()) {
                String text = part.text().orElse("");
                wordsInEvent.addAll(extractSearchableWords(text));
                if (!text.isEmpty()) {
                  eventTexts.add(text);
                }
              }

              if (wordsInEvent.isEmpty()) {
                continue;
              }

              // A non-ASCII query word also matches inside the text, as Japanese and Chinese put no
              // spaces between words.
              String eventTextLower =
                  Normalizer.normalize(String.join(" ", eventTexts), Normalizer.Form.NFC)
                      .toLowerCase(Locale.ROOT);
              boolean matches =
                  wordsInQuery.stream()
                      .anyMatch(
                          word ->
                              wordsInEvent.contains(word)
                                  || (!CharMatcher.ascii().matchesAllOf(word)
                                      && eventTextLower.contains(word)));
              if (matches) {
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
              .memories(ImmutableList.copyOf(matchingMemories))
              .build();
        });
  }

  /** Extracts words from a string and converts them to lowercase. */
  private static ImmutableSet<String> extractWordsLower(String text) {
    ImmutableSet.Builder<String> words = ImmutableSet.builder();
    Matcher matcher = WORD_PATTERN.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
    while (matcher.find()) {
      words.add(matcher.group().toLowerCase(Locale.ROOT));
    }
    return words.build();
  }

  /**
   * Extracts the words an event can be matched on, in lowercase: the words of {@link
   * #extractWordsLower} plus, for a word that mixes Latin and non-Latin characters, each of its
   * single-script runs. Japanese and Chinese are written without spaces, so {@code 私はPythonを使う} is
   * a single word, and splitting it where the script changes lets a query for {@code python} match
   * while a partial word such as {@code thon} still does not.
   */
  private static ImmutableSet<String> extractSearchableWords(String text) {
    ImmutableSet<String> words = extractWordsLower(text);
    ImmutableSet.Builder<String> searchable = ImmutableSet.<String>builder().addAll(words);
    for (String word : words) {
      if (CharMatcher.ascii().matchesAllOf(word)) {
        continue;
      }
      int runStart = 0;
      boolean runIsLatin = isLatin(word.codePointAt(0));
      for (int i = 0; i < word.length(); i += Character.charCount(word.codePointAt(i))) {
        boolean latin = isLatin(word.codePointAt(i));
        if (latin != runIsLatin) {
          searchable.add(word.substring(runStart, i));
          runStart = i;
          runIsLatin = latin;
        }
      }
      searchable.add(word.substring(runStart));
    }
    return searchable.build();
  }

  /**
   * ASCII letters, digits and {@code _}, or a character above ASCII whose Unicode name starts with
   * {@code LATIN}, as Python checks it. Unlike the Latin script, this leaves out characters such as
   * {@code º} and {@code ª}.
   */
  private static boolean isLatin(int codePoint) {
    if (codePoint < 0x80) {
      return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }
    String name = Character.getName(codePoint);
    return name != null && name.startsWith("LATIN");
  }

  private String formatTimestamp(long timestamp) {
    return Instant.ofEpochMilli(timestamp).toString();
  }
}
