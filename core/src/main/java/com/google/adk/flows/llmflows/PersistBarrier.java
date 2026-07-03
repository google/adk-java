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

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.annotations.VisibleForTesting;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets {@link BaseLlmFlow}'s multi-step loop wait until the {@code Runner} -- the sole event
 * persister -- has appended the current step's events, so the next step's request (built from
 * {@code session.events()} by {@link Contents}) is not assembled from a stale session. The {@code
 * Runner} calls {@link #markPersisted} (or {@link #markFailed}) after each append; the flow calls
 * {@link #awaitPersisted} between steps. State lives in the per-invocation {@link
 * InvocationContext#callbackContextData()} map, shared across the agent tree.
 *
 * <p>Each event id maps to a {@link CompletableSubject}: pending until its append finishes, then
 * terminally completed or failed. The subject retains its terminal state, so {@code
 * awaitPersisted}/{@code mark*} may happen in any order and a late await -- e.g. at a higher flow
 * level across an agent transfer -- resolves immediately. If an append fails, the matching await
 * fails with that error rather than blocking forever.
 *
 * <p>Thread-safe and lock-free: {@code markPersisted}/{@code markFailed} may run off-thread (async
 * {@code appendEvent}) concurrently with {@code awaitPersisted}; {@link
 * java.util.concurrent.ConcurrentHashMap#computeIfAbsent} hands both sides the same subject, which
 * itself serializes its terminal signal against subscription.
 */
public final class PersistBarrier {

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
  }

  /**
   * Completes once every event in {@code events} has been {@link #markPersisted}, or fails if any
   * was {@link #markFailed}; completes immediately if the barrier was never {@link #enable}d.
   * Already-resolved events resolve immediately, so the order of {@code awaitPersisted}/{@code
   * mark*} does not matter.
   */
  public static Completable awaitPersisted(InvocationContext context, List<Event> events) {
    Boolean enabled = (Boolean) context.callbackContextData().get(ENABLED_KEY);
    if (enabled == null || !enabled) {
      return Completable.complete();
    }
    Completable result = Completable.complete();
    for (Event event : events) {
      String eventId = event.id();
      if (eventId != null) {
        result = result.andThen(barrier(context, eventId));
      }
    }
    return result;
  }

  /** Signals that the {@code Runner} persisted the event with the given id. */
  public static void markPersisted(InvocationContext context, String eventId) {
    if (eventId != null) {
      barrier(context, eventId).onComplete();
    }
  }

  /**
   * Signals that persisting the event with the given id failed, so an await on it fails with {@code
   * error} instead of blocking forever.
   */
  public static void markFailed(InvocationContext context, String eventId, Throwable error) {
    if (eventId != null) {
      barrier(context, eventId).onError(error);
    }
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
