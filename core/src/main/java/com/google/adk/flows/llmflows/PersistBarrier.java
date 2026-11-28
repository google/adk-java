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

package com.google.adk.flows.llmflows;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.common.annotations.VisibleForTesting;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets {@link BaseLlmFlow}'s multi-step loop wait until the {@code Runner} has appended and
 * delivered the current step's events, so the next step's request (built from {@code
 * session.events()} by {@link Contents}) is not assembled from a stale session. The {@code Runner}
 * (and {@code NodeRunner.runRoot}) calls {@link #markPersisted} after each event is appended,
 * processed by plugins' {@code onEventCallback}, and emitted to the caller (or {@link #markFailed}
 * if persisting or {@code onEventCallback} fails); the flow calls {@link #awaitPersisted} between
 * steps, and workflow agents between sub-agents. State lives in the per-invocation {@link
 * InvocationContext#callbackContextData()} map, shared across the agent tree.
 *
 * <p>Each event id maps to a {@link CompletableSubject}: pending until processing and delivery
 * finish, then terminally completed or failed. The subject retains its terminal state, so {@code
 * awaitPersisted}/{@code mark*} may happen in any order and a late await -- e.g. at a higher flow
 * level across an agent transfer -- resolves immediately. If persisting or {@code onEventCallback}
 * fails, the matching await fails with that error rather than blocking forever.
 *
 * <p>Thread-safe and lock-free: {@code markPersisted}/{@code markFailed} may run off-thread (async
 * {@code appendEvent}) concurrently with {@code awaitPersisted}; {@link
 * java.util.concurrent.ConcurrentHashMap#computeIfAbsent} hands both sides the same subject, which
 * itself serializes its terminal signal against subscription.
 */
public final class PersistBarrier {

  private static final Logger logger = LoggerFactory.getLogger(PersistBarrier.class);

  @VisibleForTesting static final Duration STALL_WARNING_DELAY = Duration.ofSeconds(60);

  private static final String ENABLED_KEY = "com.google.adk.flows.llmflows.persistBarrier.enabled";
  private static final String BARRIERS_KEY =
      "com.google.adk.flows.llmflows.persistBarrier.barriers";

  private PersistBarrier() {}

  /**
   * Marks that a {@code Runner} is driving this invocation and will resolve each appended event.
   * Otherwise (flow run directly, e.g. unit tests) {@link #awaitPersisted} is a no-op, avoiding a
   * deadlock waiting for a signal that never comes.
   */
  public static void enable(InvocationContext context) {
    context.callbackContextData().put(ENABLED_KEY, true);
    // Created now, not on first use, so a copy of callbackContextData made later shares it.
    var unused = barriers(context);
  }

  /**
   * Completes once every event in {@code events} has been {@link #markPersisted}, or fails if any
   * was {@link #markFailed}; completes immediately if the barrier was never {@link #enable}d, and
   * logs a warning if still pending after 60 seconds. Already-resolved events resolve immediately,
   * so the order of {@code awaitPersisted}/{@code mark*} does not matter. Partial events are
   * skipped, since the Runner never persists them.
   */
  public static Completable awaitPersisted(InvocationContext context, List<Event> events) {
    if (!isEnabled(context)) {
      return Completable.complete();
    }
    // Await the per-event barriers via Completable.concat instead of folding them into a
    // left-nested chain of Completable.andThen(...). A single step's event list can be large (an
    // agent transfer folds the sub-agent's events into the parent step), and a nested andThen chain
    // recurses once per element on both subscription and completion, overflowing the stack for
    // long sessions. Completable.concat drains its sources iteratively, so it stays stack-safe
    // while keeping the same await semantics (order is irrelevant, as each subject retains its
    // terminal state).
    List<CompletableSubject> barriers = new ArrayList<>(events.size());
    for (Event event : events) {
      String eventId = event.id();
      if (eventId != null && !event.partial().orElse(false)) {
        barriers.add(barrier(context, eventId));
      }
    }
    Completable persisted = Completable.concat(barriers);
    return Completable.defer(() -> {
      if (barriers.stream().allMatch(subject -> subject.hasComplete() || subject.hasThrowable())) {
        return persisted;
      }
      // Warn rather than time out: the Runner saves only as fast as the caller reads.
      Disposable warning =
          Completable.timer(STALL_WARNING_DELAY.toMillis(), TimeUnit.MILLISECONDS)
              .subscribe(() ->
                  logger.warn(
                      "Agent '{}' (invocation {}) has waited {}s for the Runner to save its"
                          + " events. Expected if the caller has stopped reading events."
                          + " Otherwise an agent between it and the Runner drops, holds"
                          + " back, buffers or changes the id of its events and the wait"
                          + " never ends: run this agent with its own Runner and session.",
                      context.agent().name(),
                      context.invocationId(),
                      STALL_WARNING_DELAY.toSeconds()));
      return persisted
          // Cancel before signaling: the next step can run inside onComplete.
          .doOnTerminate(warning::dispose)
          .doOnDispose(warning::dispose);
    });
  }

  /**
   * Signals synchronously on the calling thread that the {@code Runner} persisted the event with
   * the given id.
   */
  public static void markPersisted(InvocationContext context, String eventId) {
    if (eventId != null) {
      barrier(context, eventId).onComplete();
    }
  }

  /**
   * Signals that the {@code Runner} persisted and delivered the event with the given id. When an
   * awaiter is already subscribed, schedules completion on {@code scheduler} so the resumed step or
   * sub-agent normally runs off the session service's thread without stalling sibling branches;
   * otherwise completes synchronously with no scheduling overhead. No-op when the barrier was never
   * {@link #enable}d.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static void markPersisted(InvocationContext context, String eventId, Scheduler scheduler) {
    checkNotNull(scheduler);
    if (eventId == null || !isEnabled(context)) {
      return;
    }
    CompletableSubject subject = barrier(context, eventId);
    if (subject.hasObservers()) {
      Disposable unused = scheduler.scheduleDirect(subject::onComplete);
    } else {
      subject.onComplete();
    }
  }

  /**
   * Signals that persisting or post-processing ({@code onEventCallback}) the event with the given
   * id failed, so an await on it fails with {@code error} instead of blocking forever. No-op when
   * the barrier was never {@link #enable}d.
   */
  public static void markFailed(InvocationContext context, String eventId, Throwable error) {
    if (eventId != null && isEnabled(context)) {
      barrier(context, eventId).onError(error);
    }
  }

  private static boolean isEnabled(InvocationContext context) {
    return Boolean.TRUE.equals(context.callbackContextData().get(ENABLED_KEY));
  }

  /**
   * The per-event subject, created on first use. {@code computeIfAbsent} is atomic, so an awaiter
   * and a concurrent mark share one subject regardless of order.
   */
  private static CompletableSubject barrier(InvocationContext context, String eventId) {
    return barriers(context).computeIfAbsent(eventId, unusedKey -> CompletableSubject.create());
  }

  /** Awaited-but-unresolved events; drains to 0 once a step's events are persisted or failed. */
  @VisibleForTesting
  static int pendingCount(InvocationContext context) {
    int pending = 0;
    for (CompletableSubject barrier : barriers(context).values()) {
      if (!barrier.hasComplete() && !barrier.hasThrowable()) {
        pending++;
      }
    }
    return pending;
  }

  // Safe: BARRIERS_KEY only ever holds the Map created here.
  @SuppressWarnings("unchecked")
  private static Map<String, CompletableSubject> barriers(InvocationContext context) {
    return (Map<String, CompletableSubject>)
        context
            .callbackContextData()
            .computeIfAbsent(
                BARRIERS_KEY, unusedKey -> new ConcurrentHashMap<String, CompletableSubject>());
  }
}
