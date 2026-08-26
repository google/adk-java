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
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns a {@link CancellationToken} and can request cancellation for it. */
public final class CancellationTokenSource implements AutoCloseable {
  private final AtomicBoolean cancellationRequested = new AtomicBoolean();
  private final CompletableSubject cancellationSignal = CompletableSubject.create();
  private final CancellationToken token =
      new CancellationToken() {
        @Override
        public boolean isCancellationRequested() {
          return cancellationRequested.get();
        }

        @Override
        public Completable onCancellation() {
          return cancellationSignal;
        }
      };

  /** Returns the token controlled by this source. */
  public CancellationToken token() {
    return token;
  }

  /** Requests cancellation. Calling this method more than once is safe. */
  public void cancel() {
    if (cancellationRequested.compareAndSet(false, true)) {
      cancellationSignal.onComplete();
    }
  }

  /** Returns whether cancellation has been requested. */
  public boolean isCancellationRequested() {
    return cancellationRequested.get();
  }

  /** Requests cancellation. */
  @Override
  public void close() {
    cancel();
  }
}
