/*
 * Copyright 2026 Google LLC
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

package com.google.adk.runner;

import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Storage and admission contract for steering an active ordinary invocation.
 *
 * <p>This service manages additional user messages only. Initial input uses the normal Runner path.
 * Scheduling another invocation and managing execution ownership remain application concerns.
 *
 * <p>Operations are synchronous and may perform bounded storage I/O. Receipt observation is
 * asynchronous. Implementations must coordinate admission and closure across every client of the
 * same backend. They do not own execution scheduling or session persistence.
 *
 * <p>Implementations must be thread-safe. Storage calls run on the caller's thread, so remote
 * backends must use finite I/O timeouts. The application owns backend clients and their resources;
 * closing an inbox must not close a client shared by other invocations.
 */
@Experimental
public interface BaseSteeringService {
  /**
   * Opens an inbox exclusively for one executor, rejecting an already registered identity.
   * Invocation IDs must not be reused. Registration is atomic; callers should wait for invocation
   * readiness before submitting. A failed open must not leave a newly created inbox accepting input
   * or change an existing owner's inbox.
   */
  Inbox open(SessionKey sessionKey, String invocationId);

  /**
   * Admits a validated user message immediately, or throws if the identity is not accepting input.
   * Returns a replayable receipt of session persistence. Subscribing or disposing it must not
   * submit or retract input. Retrying this method is a new submission; transport retry
   * deduplication belongs to the application. Admission must be atomic with both inbox closing
   * operations.
   *
   * <p>Admission completes before this method returns; do not defer it until receipt subscription.
   * Concurrent submissions are ordered by backend admission, not by client timestamps. Receipt
   * results must remain observable by late subscribers, including when another process acknowledges
   * or closes the inbox.
   */
  Single<Event> submit(SessionKey sessionKey, String invocationId, Content message);

  /** Executor's storage handle to one steering inbox, not an invocation control handle. */
  interface Inbox extends AutoCloseable {
    /**
     * Returns a snapshot in admission order, retaining inputs and their stable IDs until
     * acknowledged; empty if closed. Reading the snapshot must not remove or acknowledge inputs.
     */
    List<Input> pending();

    /**
     * Acknowledges the pending input whose ID equals {@code event.id()} after SessionService append
     * succeeds. Duplicate or late acknowledgements are harmless and cannot turn a failed receipt
     * into success. Implementations must publish receipt notifications outside their storage
     * locks/transactions.
     */
    void persisted(Event event);

    /**
     * Atomically closes admission if no unacknowledged inputs remain. Returns true if closed, false
     * if execution must continue. Storage failures must propagate, not look like an empty inbox.
     */
    boolean closeIfIdle();

    /**
     * Idempotently closes admission and fails unacknowledged receipts during executor cleanup. This
     * storage operation does not itself cancel model or tool execution.
     *
     * <p>Lifecycle cleanup may retry a failed call, and calls may overlap during cancellation.
     * Implementations must allow safe repetition, including after a partially completed close.
     * Persistent storage failures require backend-specific expiry or recovery.
     */
    @Override
    void close();
  }

  /** A pending user message with a stable, globally unique ID used for its session event. */
  record Input(String id, Content content) {}
}
