package com.google.adk.summarizer;

import java.util.Optional;

/**
 * Configuration for event compaction.
 *
 * @param compactionInterval The number of <b>new</b> user-initiated invocations that, once fully
 *     represented in the session's events, will trigger a compaction.
 * @param overlapSize The number of preceding invocations to include from the end of the last
 *     compacted range. This creates an overlap between consecutive compacted summaries, maintaining
 *     context.
 * @param summarizer An optional event summarizer to use for compaction.
 */
public record EventsCompactionConfig(
    int compactionInterval, int overlapSize, Optional<BaseEventSummarizer> summarizer) {

  public EventsCompactionConfig(int compactionInterval, int overlapSize) {
    this(compactionInterval, overlapSize, Optional.empty());
  }
}
