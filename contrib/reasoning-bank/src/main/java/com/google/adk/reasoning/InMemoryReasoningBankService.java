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

package com.google.adk.reasoning;

import com.google.common.collect.ImmutableSet;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
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
import java.util.stream.Collectors;

/**
 * An in-memory reasoning bank service for prototyping purposes only.
 *
 * <p>Uses bag-of-words keyword matching instead of semantic search. The reference ReasoningBank
 * implementation uses embedding-based retrieval (e.g. {@code gemini-embedding-001} with cosine
 * similarity). For production use, implement {@link BaseReasoningBankService} against a vector
 * store.
 */
public final class InMemoryReasoningBankService implements BaseReasoningBankService {

  private static final int DEFAULT_MAX_RESULTS = 5;

  private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");

  /** appName → memory items. */
  private final Map<String, List<ReasoningMemoryItem>> memoryItems = new ConcurrentHashMap<>();

  /** appName → traces. */
  private final Map<String, List<ReasoningTrace>> traces = new ConcurrentHashMap<>();

  @Override
  public Completable storeMemoryItem(String appName, ReasoningMemoryItem memoryItem) {
    return Completable.fromAction(
        () ->
            memoryItems
                .computeIfAbsent(appName, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(memoryItem));
  }

  @Override
  public Completable storeTrace(String appName, ReasoningTrace trace) {
    return Completable.fromAction(
        () ->
            traces
                .computeIfAbsent(appName, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(trace));
  }

  @Override
  public Single<SearchReasoningResponse> searchMemoryItems(String appName, String query) {
    return searchMemoryItems(appName, query, DEFAULT_MAX_RESULTS);
  }

  @Override
  public Single<SearchReasoningResponse> searchMemoryItems(
      String appName, String query, int maxResults) {
    return Single.fromCallable(
        () -> {
          List<ReasoningMemoryItem> items = memoryItems.get(appName);
          if (items == null || items.isEmpty()) {
            return SearchReasoningResponse.builder().build();
          }

          ImmutableSet<String> queryWords = extractWords(query);
          if (queryWords.isEmpty()) {
            return SearchReasoningResponse.builder().build();
          }

          List<Scored> scored = new ArrayList<>();
          // Snapshot to avoid iterating over the synchronized list without locking.
          List<ReasoningMemoryItem> snapshot;
          synchronized (items) {
            snapshot = new ArrayList<>(items);
          }
          for (ReasoningMemoryItem item : snapshot) {
            int score = matchScore(item, queryWords);
            if (score > 0) {
              scored.add(new Scored(item, score));
            }
          }

          scored.sort((a, b) -> Integer.compare(b.score, a.score));

          List<ReasoningMemoryItem> top =
              scored.stream().map(s -> s.item).limit(maxResults).collect(Collectors.toList());
          return SearchReasoningResponse.builder().setMemoryItems(top).build();
        });
  }

  /**
   * Scores a memory item against the query bag-of-words.
   *
   * <p>Weighting mirrors the paper's emphasis on identity fields: title > description > tags >
   * content. Content matches get a flat bonus rather than per-word to avoid long items dominating
   * retrieval.
   */
  private int matchScore(ReasoningMemoryItem item, Set<String> queryWords) {
    int score = 0;
    score += countOverlap(queryWords, extractWords(item.title())) * 3;
    score += countOverlap(queryWords, extractWords(item.description())) * 2;
    for (String tag : item.tags()) {
      score += countOverlap(queryWords, extractWords(tag));
    }
    Set<String> contentWords = extractWords(item.content());
    if (!Collections.disjoint(queryWords, contentWords)) {
      score += 1;
    }
    return score;
  }

  private int countOverlap(Set<String> a, Set<String> b) {
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    return intersection.size();
  }

  private ImmutableSet<String> extractWords(String text) {
    if (text == null || text.isEmpty()) {
      return ImmutableSet.of();
    }
    Set<String> words = new HashSet<>();
    Matcher matcher = WORD_PATTERN.matcher(text);
    while (matcher.find()) {
      words.add(matcher.group().toLowerCase(Locale.ROOT));
    }
    return ImmutableSet.copyOf(words);
  }

  private static final class Scored {
    final ReasoningMemoryItem item;
    final int score;

    Scored(ReasoningMemoryItem item, int score) {
      this.item = item;
      this.score = score;
    }
  }
}
