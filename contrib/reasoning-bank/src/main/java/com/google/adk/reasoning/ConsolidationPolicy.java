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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Store-time strategy for reconciling an incoming memory item against the items already held for an
 * app.
 *
 * <p>The faithful default is append-only ({@link #identity()}): the reference implementation
 * deliberately avoids consolidation to isolate its result. This SPI is the seam that lets bounding,
 * dedup, or decay drop in later without touching {@link BaseReasoningBankService}.
 *
 * <p>Implementations run under the bank's per-app list monitor, so they MUST be pure, fast,
 * non-blocking, and MUST NOT mutate {@code existing} (an unmodifiable snapshot). Return the full
 * kept list in retrieval order.
 */
@FunctionalInterface
public interface ConsolidationPolicy {

  /**
   * Returns the items to keep after adding {@code incoming} to {@code existing}.
   *
   * @param existing an unmodifiable snapshot of the currently-held items, in retrieval order.
   * @param incoming the item being stored.
   */
  List<ReasoningMemoryItem> reconcile(
      List<ReasoningMemoryItem> existing, ReasoningMemoryItem incoming);

  /** Append-only (the faithful default): keep everything, with {@code incoming} last. */
  static ConsolidationPolicy identity() {
    return (existing, incoming) -> {
      List<ReasoningMemoryItem> kept = new ArrayList<>(existing);
      kept.add(incoming);
      return kept;
    };
  }

  /**
   * Bounded eviction by {@link ReasoningMemoryItem#createdAt()} (oldest-out), capacity {@code
   * maxItems}.
   *
   * <p>{@code createdAt} is an ISO-8601 {@code Z}-form string, so lexicographic order is
   * chronological; a {@code null} timestamp sorts first and is evicted first. Kept items retain
   * their original retrieval order.
   *
   * @throws IllegalArgumentException if {@code maxItems < 1}.
   */
  static ConsolidationPolicy boundedByCreatedAt(int maxItems) {
    if (maxItems < 1) {
      throw new IllegalArgumentException("maxItems must be >= 1");
    }
    return (existing, incoming) -> {
      List<ReasoningMemoryItem> all = new ArrayList<>(existing);
      all.add(incoming);
      if (all.size() <= maxItems) {
        return ImmutableList.copyOf(all);
      }
      List<ReasoningMemoryItem> oldestFirst = new ArrayList<>(all);
      oldestFirst.sort(
          Comparator.comparing(
              ReasoningMemoryItem::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())));
      List<ReasoningMemoryItem> victims = oldestFirst.subList(0, all.size() - maxItems);
      // Assumes items are distinct: ReasoningMemoryItem is an AutoValue, so contains() uses value
      // equality. Items minted by the extractor always carry a unique id, so this holds in
      // practice.
      List<ReasoningMemoryItem> kept = new ArrayList<>(maxItems);
      for (ReasoningMemoryItem item : all) {
        if (!victims.contains(item)) {
          kept.add(item);
        }
      }
      return ImmutableList.copyOf(kept);
    };
  }
}
