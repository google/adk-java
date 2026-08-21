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
