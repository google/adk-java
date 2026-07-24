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

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

/**
 * Service contract for a ReasoningBank.
 *
 * <p>A ReasoningBank implements the closed loop described in the paper:
 *
 * <ol>
 *   <li><strong>Retrieve</strong> — {@link #searchMemoryItems} pulls relevant memory items into the
 *       agent's context before it acts.
 *   <li><strong>Act</strong> — the agent interacts with the environment (external to this service).
 *   <li><strong>Judge &amp; extract</strong> — an LLM-as-a-judge self-assesses the trajectory, and
 *       a {@link MemoryExtractor} distills success insights or failure reflections into memory
 *       items.
 *   <li><strong>Consolidate</strong> — {@link #storeMemoryItem} appends the distilled items back
 *       into the bank.
 * </ol>
 *
 * <p>Raw trajectories can optionally be persisted via {@link #storeTrace} for offline or batch
 * distillation (e.g. memory-aware test-time scaling with multiple trajectories).
 *
 * <p>Reference: Ouyang et al. "ReasoningBank: Scaling Agent Self-Evolving with Reasoning Memory"
 * (ICLR 2026, <a href="https://arxiv.org/abs/2509.25140">arXiv:2509.25140</a>).
 */
public interface BaseReasoningBankService {

  /**
   * Stores a distilled memory item.
   *
   * @param appName application scope for storage and retrieval.
   * @param memoryItem the memory item to store.
   */
  Completable storeMemoryItem(String appName, ReasoningMemoryItem memoryItem);

  /**
   * Stores a raw reasoning trace for later distillation.
   *
   * <p>Traces are not searchable on their own; they exist so that a {@link MemoryExtractor} can
   * turn them into {@link ReasoningMemoryItem}s (online per-trajectory, or offline in batches for
   * parallel/sequential memory-aware test-time scaling).
   */
  Completable storeTrace(String appName, ReasoningTrace trace);

  /**
   * Searches for memory items relevant to the given query.
   *
   * @param appName application scope.
   * @param query task description used for retrieval.
   */
  Single<SearchReasoningResponse> searchMemoryItems(String appName, String query);

  /**
   * Searches for memory items with an explicit result cap.
   *
   * @param appName application scope.
   * @param query task description used for retrieval.
   * @param maxResults maximum number of items to return.
   */
  Single<SearchReasoningResponse> searchMemoryItems(String appName, String query, int maxResults);
}
