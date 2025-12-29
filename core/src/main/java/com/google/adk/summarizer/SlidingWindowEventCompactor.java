package com.google.adk.summarizer;

import com.google.adk.events.Event;
import com.google.adk.events.EventCompaction;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.Lists;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * This class performs events compaction in a sliding window fashion based on the {@link
 * EventsCompactionConfig}.
 */
public final class SlidingWindowEventCompactor implements EventCompactor {

  private final EventsCompactionConfig config;
  private final BaseEventSummarizer summarizer;

  public SlidingWindowEventCompactor(EventsCompactionConfig config) {
    this.config = config;
    // TODO default to LLM summarizer
    this.summarizer = config.summarizer().orElseThrow();
  }

  /**
   * Runs compaction for SlidingWindowCompactor.
   *
   * <p>This method implements the sliding window compaction logic. It determines if enough new
   * invocations have occurred since the last compaction based on {@link
   * EventsCompactionConfig#compactionInterval()}. If so, it selects a range of events to compact
   * based on {@link EventsCompactionConfig#overlapSize()}, and calls {@link
   * BaseEventSummarizer#summarizeEvents(List)}.
   *
   * <p>The compaction process is controlled by two parameters:
   *
   * <p>1. {@link EventsCompactionConfig#compactionInterval()}: The number of *new* user-initiated
   * invocations that, once fully represented in the session's events, will trigger a compaction. 2.
   * `overlap_size`: The number of preceding invocations to include from the end of the last
   * compacted range. This creates an overlap between consecutive compacted summaries, maintaining
   * context.
   *
   * <p>The compactor is called after an agent has finished processing a turn and all its events
   * have been added to the session. It checks if a new compaction is needed.
   *
   * <p>When a compaction is triggered: - The compactor identifies the range of `invocation_id`s to
   * be summarized. - This range starts `overlap_size` invocations before the beginning of the new
   * block of `compaction_invocation_threshold` invocations and ends with the last invocation in the
   * current block. - A `CompactedEvent` is created, summarizing all events within this determined
   * `invocation_id` range. This `CompactedEvent` is then appended to the session.
   *
   * <p>Here is an example with `compaction_invocation_threshold = 2` and `overlap_size = 1`: Let's
   * assume events are added for `invocation_id`s 1, 2, 3, and 4 in order.
   *
   * <p>1. **After `invocation_id` 2 events are added:** - The session now contains events for
   * invocations 1 and 2. This fulfills the `compaction_invocation_threshold = 2` criteria. - Since
   * this is the first compaction, the range starts from the beginning. - A `CompactedEvent` is
   * generated, summarizing events within `invocation_id` range [1, 2]. - The session now contains:
   * `[ E(inv=1, role=user), E(inv=1, role=model), E(inv=2, role=user), E(inv=2, role=model),
   * CompactedEvent(inv=[1, 2])]`.
   *
   * <p>2. **After `invocation_id` 3 events are added:** - No compaction happens yet, because only 1
   * new invocation (`inv=3`) has been completed since the last compaction, and
   * `compaction_invocation_threshold` is 2.
   *
   * <p>3. **After `invocation_id` 4 events are added:** - The session now contains new events for
   * invocations 3 and 4, again fulfilling `compaction_invocation_threshold = 2`. - The last
   * `CompactedEvent` covered up to `invocation_id` 2. With `overlap_size = 1`, the new compaction
   * range will start one invocation before the new block (inv 3), which is `invocation_id` 2. - The
   * new compaction range is from `invocation_id` 2 to 4. - A new `CompactedEvent` is generated,
   * summarizing events within `invocation_id` range [2, 4]. - The session now contains: `[ E(inv=1,
   * role=user), E(inv=1, role=model), E(inv=2, role=user), E(inv=2, role=model),
   * CompactedEvent(inv=[1, 2]), E(inv=3, role=user), E(inv=3, role=model), E(inv=4, role=user),
   * E(inv=4, role=model), CompactedEvent(inv=[2, 4])]`.
   */
  @Override
  public Maybe<Event> compact(Session session, BaseSessionService sessionService) {
    return getCompactionEvents(session)
        .flatMap(summarizer::summarizeEvents)
        .flatMapSingle(e -> sessionService.appendEvent(session, e));
  }

  private Maybe<List<Event>> getCompactionEvents(Session session) {
    List<Event> eventsToCompact = new ArrayList<>();
    Set<String> invocationsToCompact = new HashSet<>();
    long lastCompactTimestamp = -1L;
    int targetSize = -1;

    // Scan the list of events backward so that timestamp are in decreasing fashion.
    ListIterator<Event> iter = session.events().listIterator(session.events().size());
    while (iter.hasPrevious()) {
      Event event = iter.previous();
      String invocationId = event.invocationId();

      // For regular event, there should be an invocation id.
      if (invocationId != null && !isCompactEvent(event)) {
        // If an invocation is included for compaction, include all the events for that invocation
        if (invocationsToCompact.contains(invocationId)) {
          eventsToCompact.add(event);
          continue;
        }
        // When encountered an event that is already compacted, there are possible scenarios
        // 1. Not enough uncompacted invocations as defined by the "compactionInterval", we can
        // break without compaction needed.
        // 2. Enough uncompacted invocations, hence we need to keep adding "overlapSize" more of
        // invocations.
        if (event.timestamp() <= lastCompactTimestamp) {
          if (invocationsToCompact.size() < config.compactionInterval()) {
            break;
          }
          if (targetSize < 0) {
            targetSize = invocationsToCompact.size() + config.overlapSize();
          }
        }
        // Adds the event to be compacted until enough is accumulated based on the configuration
        if (targetSize < 0 || invocationsToCompact.size() < targetSize) {
          eventsToCompact.add(event);
          invocationsToCompact.add(invocationId);
        } else {
          break;
        }
      } else if (isCompactEvent(event)) {
        // Record the latest compaction timestamp
        lastCompactTimestamp =
            Long.max(
                lastCompactTimestamp,
                event.actions().compaction().map(EventCompaction::endTimestamp).orElse(-1L));
      }
    }

    // Compaction threshold is not met, no compaction needed
    if (invocationsToCompact.size() < config.compactionInterval()) {
      return Maybe.empty();
    }

    // The events were added backward, reserve it back to prepare for compaction
    return Maybe.just(Lists.reverse(eventsToCompact));
  }

  private static boolean isCompactEvent(Event event) {
    return event.actions() != null && event.actions().compaction().isPresent();
  }
}
