/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.agents;

import io.reactivex.rxjava3.core.Completable;

/** A thread-safe, cooperative cancellation signal for an agent invocation. */
@FunctionalInterface
public interface CancellationToken {
  /** Returns whether cancellation has been requested. */
  boolean isCancellationRequested();

  /** Completes when cancellation is requested, or never for polling-only token implementations. */
  default Completable onCancellation() {
    return Completable.never();
  }

  /** Returns a token that is never cancelled. */
  static CancellationToken none() {
    return NeverCancelledHolder.INSTANCE;
  }

  /** Holder for the shared no-op token. */
  final class NeverCancelledHolder {
    private static final CancellationToken INSTANCE = () -> false;

    private NeverCancelledHolder() {}
  }
}
