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

package com.google.adk.testing;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link Scheduler} for tests that delegates to another scheduler and counts how many workers
 * were requested from it, so a test can assert that a code path ran its work through it.
 */
public final class RecordingScheduler extends Scheduler {

  private final Scheduler delegate;
  private final AtomicInteger workersCreated = new AtomicInteger();

  /**
   * Records on top of {@code delegate}; use {@link Schedulers#trampoline()} to stay
   * single-threaded.
   */
  public RecordingScheduler(Scheduler delegate) {
    this.delegate = delegate;
  }

  // Every operator ADK uses on the seam ends up here: Flowable.subscribeOn (ParallelAgent) calls
  // createWorker() directly, while Observable.subscribeOn (tool fan-out) and Completable.observeOn
  // (live loop) go through scheduleDirect(), whose base implementation calls createWorker().
  @Override
  public Worker createWorker() {
    workersCreated.incrementAndGet();
    return delegate.createWorker();
  }

  @Override
  public long now(TimeUnit unit) {
    return delegate.now(unit);
  }

  /** Returns how many times {@link #createWorker()} was called. */
  public int workersCreated() {
    return workersCreated.get();
  }
}
