package com.google.adk.summarizer;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;

/** Base interface for producing events summary. */
public interface BaseEventSummarizer {

  /**
   * Compact a list of events into a single event.
   *
   * <p>If compaction failed, return {@link Maybe#empty()}. Otherwise, compact into a content and
   * return it.
   *
   * <p>This method will summarize the events and return a new summary event indicating the range of
   * events it summarized.
   *
   * @param events Events to compact.
   * @return The new compacted event, or {@link Maybe#empty()} if no compaction happened.
   */
  Maybe<Event> summarizeEvents(List<Event> events);
}
