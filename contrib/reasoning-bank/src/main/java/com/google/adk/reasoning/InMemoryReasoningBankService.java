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
 * <p>Uses keyword matching instead of semantic search. For production use, consider implementing a
 * service backed by vector embeddings for semantic similarity matching.
 */
public final class InMemoryReasoningBankService implements BaseReasoningBankService {

  private static final int DEFAULT_MAX_RESULTS = 5;

  // Pattern to extract words for keyword matching.
  private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");

  /** Keys are app names, values are lists of strategies. */
  private final Map<String, List<ReasoningStrategy>> strategies;

  /** Keys are app names, values are lists of traces. */
  private final Map<String, List<ReasoningTrace>> traces;

  public InMemoryReasoningBankService() {
    this.strategies = new ConcurrentHashMap<>();
    this.traces = new ConcurrentHashMap<>();
  }

  @Override
  public Completable storeStrategy(String appName, ReasoningStrategy strategy) {
    return Completable.fromAction(
        () -> {
          List<ReasoningStrategy> appStrategies =
              strategies.computeIfAbsent(
                  appName, k -> Collections.synchronizedList(new ArrayList<>()));
          appStrategies.add(strategy);
        });
  }

  @Override
  public Completable storeTrace(String appName, ReasoningTrace trace) {
    return Completable.fromAction(
        () -> {
          List<ReasoningTrace> appTraces =
              traces.computeIfAbsent(appName, k -> Collections.synchronizedList(new ArrayList<>()));
          appTraces.add(trace);
        });
  }

  @Override
  public Single<SearchReasoningResponse> searchStrategies(String appName, String query) {
    return searchStrategies(appName, query, DEFAULT_MAX_RESULTS);
  }

  @Override
  public Single<SearchReasoningResponse> searchStrategies(
      String appName, String query, int maxResults) {
    return Single.fromCallable(
        () -> {
          if (!strategies.containsKey(appName)) {
            return SearchReasoningResponse.builder().build();
          }

          List<ReasoningStrategy> appStrategies = strategies.get(appName);
          ImmutableSet<String> queryWords = extractWords(query);

          if (queryWords.isEmpty()) {
            return SearchReasoningResponse.builder().build();
          }

          List<ScoredStrategy> scoredStrategies = new ArrayList<>();

          for (ReasoningStrategy strategy : appStrategies) {
            int score = calculateMatchScore(strategy, queryWords);
            if (score > 0) {
              scoredStrategies.add(new ScoredStrategy(strategy, score));
            }
          }

          // Sort by score descending
          scoredStrategies.sort((a, b) -> Integer.compare(b.score, a.score));

          // Take top results
          List<ReasoningStrategy> matchingStrategies =
              scoredStrategies.stream()
                  .map(scoredStrategy -> scoredStrategy.strategy)
                  .limit(maxResults)
                  .collect(Collectors.toList());

          return SearchReasoningResponse.builder().setStrategies(matchingStrategies).build();
        });
  }

  private int calculateMatchScore(ReasoningStrategy strategy, Set<String> queryWords) {
    int score = 0;

    // Check problem pattern
    Set<String> patternWords = extractWords(strategy.problemPattern());
    score += countOverlap(queryWords, patternWords) * 3; // Weight pattern matches higher

    // Check name
    Set<String> nameWords = extractWords(strategy.name());
    score += countOverlap(queryWords, nameWords) * 2;

    // Check tags
    for (String tag : strategy.tags()) {
      Set<String> tagWords = extractWords(tag);
      score += countOverlap(queryWords, tagWords);
    }

    // Check steps (lower weight)
    for (String step : strategy.steps()) {
      Set<String> stepWords = extractWords(step);
      if (!Collections.disjoint(queryWords, stepWords)) {
        score += 1;
      }
    }

    return score;
  }

  private int countOverlap(Set<String> set1, Set<String> set2) {
    Set<String> intersection = new HashSet<>(set1);
    intersection.retainAll(set2);
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

  /** Helper class for scoring strategies during search. */
  private static class ScoredStrategy {
    final ReasoningStrategy strategy;
    final int score;

    ScoredStrategy(ReasoningStrategy strategy, int score) {
      this.strategy = strategy;
      this.score = score;
    }
  }
}
