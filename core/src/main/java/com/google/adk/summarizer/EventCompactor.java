package com.google.adk.summarizer;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Maybe;

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
  Maybe<Event> compact(Session session, BaseSessionService sessionService);
}
