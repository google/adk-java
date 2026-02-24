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

package com.google.adk.summarizer;

import javax.annotation.Nullable;

/**
 * Configuration for event compaction.
 *
 * @param compactionInterval The number of <b>new</b> user-initiated invocations that, once fully
 *     represented in the session's events, will trigger a compaction.
 * @param overlapSize The number of preceding invocations to include from the end of the last
 *     compacted range. This creates an overlap between consecutive compacted summaries, maintaining
 *     context.
 * @param summarizer An event summarizer to use for compaction.
 * @param tokenThreshold The number of tokens above which compaction will be triggered. If null, no
 *     token limit will be enforced. It will trigger compaction within the invocation.
 * @param eventRetentionSize The maximum number of events to retain and preserve from compaction. If
 *     null, no event retention limit will be enforced.
 */
public record EventsCompactionConfig(
    @Nullable Integer compactionInterval,
    @Nullable Integer overlapSize,
    @Nullable BaseEventSummarizer summarizer,
    @Nullable Integer tokenThreshold,
    @Nullable Integer eventRetentionSize) {

  public EventsCompactionConfig(int compactionInterval, int overlapSize) {
    this(compactionInterval, overlapSize, null, null, null);
  }

  public EventsCompactionConfig(
      int compactionInterval, int overlapSize, @Nullable BaseEventSummarizer summarizer) {
    this(compactionInterval, overlapSize, summarizer, null, null);
  }

  public boolean hasSlidingWindowCompactionConfig() {
    return compactionInterval != null && compactionInterval > 0 && overlapSize != null;
  }
}
