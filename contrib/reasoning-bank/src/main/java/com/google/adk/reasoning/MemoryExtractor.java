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
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Extension point for distilling {@link ReasoningTrace}s into {@link ReasoningMemoryItem}s.
 *
 * <p>A {@code MemoryExtractor} corresponds to the "judge &amp; extract" step of the ReasoningBank
 * loop. Implementations typically wrap an LLM-as-a-judge plus one of the extraction prompt
 * templates from the reference implementation:
 *
 * <ul>
 *   <li>Single successful trajectory → {@code SUCCESSFUL_SI} prompt.
 *   <li>Single failed trajectory → {@code FAILED_SI} prompt (preventative lessons).
 *   <li>Multiple trajectories for the same query → {@code PARALLEL_SI} prompt (self-contrast over
 *       memory-aware test-time scaling samples).
 * </ul>
 *
 * <p>This module ships a {@link NoOpMemoryExtractor} default. Concrete LLM-backed extractors are
 * intentionally left to downstream modules so this contrib module stays free of model dependencies.
 */
public interface MemoryExtractor {

  /**
   * Distills the given trajectories into memory items.
   *
   * @param query the task/query all trajectories attempted to solve.
   * @param trajectories one or more trajectories; may mix successful and failed runs (enabling
   *     self-contrast over parallel samples).
   * @return zero or more memory items. Must not be {@code null}.
   */
  Single<ImmutableList<ReasoningMemoryItem>> extract(
      String query, List<ReasoningTrace> trajectories);
}
