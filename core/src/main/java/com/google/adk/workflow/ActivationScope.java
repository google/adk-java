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

package com.google.adk.workflow;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The work started by one activation of a workflow node, that is one attempt at running it: its
 * body, its tool call, the child runs it started and the attempts of the nodes it runs. Closing the
 * scope cancels the body, the tool call and the child runs, and completes once all of that work has
 * stopped, so a retry, the caller and the successors never overlap it. The scope only waits for the
 * attempts of the nodes it runs: whoever runs a node cancels its attempt.
 */
final class ActivationScope {
  private final @Nullable ActivationScope parent;
  private final CompletableSubject stopped = CompletableSubject.create();

  @GuardedBy("this")
  private final Set<Work> running = new LinkedHashSet<>();

  @GuardedBy("this")
  private boolean closed;

  @GuardedBy("this")
  private @Nullable Completable closing;

  /** Creates the scope of an attempt run under the attempt that owns {@code parent}, if any. */
  ActivationScope(@Nullable ActivationScope parent) {
    this.parent = parent;
  }

  /**
   * Starts the attempt in its parent's scope, which then waits for this scope to close but does not
   * cancel the attempt. Canceling the attempt's run closes it.
   *
   * @return false if the parent's scope is closed, so the attempt must not start
   */
  boolean open() {
    return parent == null || parent.add(Disposable.empty(), stopped);
  }

  /**
   * Records work this attempt started, unless the scope is closed. Closing the scope disposes
   * {@code cancel} and waits for {@code stopped}, which must not fail.
   *
   * @return false if the scope is closed, so the work must not start
   */
  boolean add(Disposable cancel, Completable stopped) {
    Work work = new Work(cancel, stopped);
    synchronized (this) {
      if (closed) {
        return false;
      }
      running.add(work);
    }
    // Removed as it stops, so a close that comes later does not wait for it.
    var unused = stopped.subscribe(() -> remove(work));
    return true;
  }

  private synchronized void remove(Work work) {
    running.remove(work);
  }

  /** Returns whether the scope rejects new work, after {@link #rejectNewWork} or {@link #close}. */
  synchronized boolean isClosed() {
    return closed;
  }

  /**
   * Rejects new work and leaves the work still running to {@link #close}. A {@link FunctionNode}'s
   * cancel calls it before it interrupts the body, so the body it wakes starts no child run.
   */
  synchronized void rejectNewWork() {
    closed = true;
  }

  /**
   * Rejects new work, cancels the work still running, and returns a {@link Completable} that
   * completes once that work has stopped: on the subscribing thread if it already has, else on a
   * computation thread. Calls after the first return the same {@code Completable}.
   */
  Completable close() {
    ImmutableList<Work> toStop;
    CompletableSubject allStopped = CompletableSubject.create();
    Completable done;
    synchronized (this) {
      closed = true;
      if (closing != null) {
        return closing;
      }
      toStop = ImmutableList.copyOf(running);
      done =
          Completable.defer(() ->
              allStopped.hasComplete()
                  // Stays on the current thread, so an inline Runner keeps the caller's thread.
                  ? Completable.complete()
                  // Off the thread of the work that stops last, which may be an interrupted
                  // body's.
                  : allStopped.observeOn(Schedulers.computation()));
      closing = done;
    }
    toStop.forEach(work -> work.cancel().dispose());
    Completable.merge(toStop.stream().map(Work::stopped).collect(toImmutableList()))
        .subscribe(allStopped);
    var unused = allStopped.subscribe(stopped::onComplete);
    return done;
  }

  /**
   * One piece of work: {@code cancel} asks it to stop, and {@code stopped} completes once it has.
   */
  private record Work(Disposable cancel, Completable stopped) {}
}
