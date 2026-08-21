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
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;

/** Base interface for compacting events. */
public interface EventCompactor {

  /**
   * Compacts events in the given session. If there is compaction happened, the new compaction event
   * will be appended to the given {@link BaseSessionService}.
   *
   * @param session the session containing the events to be compacted.
   * @param sessionService the session service for appending the new compaction event.
   * @return the {@link Event} containing the events summary.
   */
  Completable compact(Session session, BaseSessionService sessionService);
}
