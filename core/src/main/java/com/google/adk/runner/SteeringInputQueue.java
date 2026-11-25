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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.sessions.SessionKey;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Single-use input queue for steering one ordinary invocation, backed by its steering service. */
@Experimental
public final class SteeringInputQueue implements AutoCloseable {
  private final SingleSubject<String> started = SingleSubject.create();
  private final CompletableSubject cancellation = CompletableSubject.create();
  private final Object lock = new Object();
  private volatile @Nullable Function<Content, Single<Event>> submit;

  @GuardedBy("lock")
  private @Nullable Runnable unbind;

  @GuardedBy("lock")
  private boolean claimed;

  @GuardedBy("lock")
  private boolean closed;

  @GuardedBy("lock")
  private boolean startupResolved;

  /**
   * Returns the invocation ID after initial input persistence and registration of the input queue.
   * Observing this notification does not start execution. It fails if startup fails or this queue
   * closes before readiness. Do not block invocation callbacks waiting for input receipts.
   */
  public Single<String> started() {
    return started.hide();
  }

  /**
   * Text convenience entry point with the same admission and receipt semantics as {@link
   * #steer(Content)}.
   */
  public Single<Event> steer(String text) {
    checkArgument(text != null && !text.isBlank(), "Steering text must not be blank");
    return steer(Content.fromParts(Part.fromText(text)));
  }

  /**
   * Immediately admits a user message, throwing if invalid or not accepting input.
   *
   * <p>Text, inline data, and file references are supported. The role must be absent or user; model
   * output and tool responses are rejected. The receipt completes with the persisted user event,
   * not an acknowledgement from the model. Observing or cancelling a receipt does not resubmit or
   * retract input. Retrying this method submits another message.
   */
  public Single<Event> steer(Content message) {
    Function<Content, Single<Event>> current = submit;
    checkState(current != null, "Invocation is not accepting steering input");
    return current.apply(message);
  }

  /**
   * Cancels the attached invocation and closes input, or prevents execution if not yet subscribed.
   * Idempotent. The execution owner must also close explicitly when cancelling an externally
   * scheduled subscription before the event source has been subscribed.
   *
   * <p>To let the invocation finish normally without further input, stop calling {@link
   * #steer(Content)} and leave this queue open. It closes automatically when execution ends.
   */
  @Override
  public void close() {
    CancellationException error = new CancellationException("Steering input queue closed");
    if (finish(error)) {
      cancellation.onError(error);
    }
  }

  Flowable<Event> attach(Supplier<Flowable<Event>> source) {
    return Flowable.defer(() -> {
      synchronized (lock) {
        checkState(
            !claimed && !closed, "Steering input queue can only be used for one execution");
        claimed = true;
      }
      return Flowable.defer(source::get)
          .takeUntil(cancellation.toFlowable())
          .doOnError(this::finish)
          .doOnComplete(() -> finish(null))
          .doOnCancel(() -> finish(new CancellationException("Invocation subscription cancelled")));
    });
  }

  // Bind under the same lock as finish so cancellation cannot leave a late registration behind.
  boolean bind(SessionKey key, ConcurrentMap<SessionKey, SteeringInputQueue> registry) {
    synchronized (lock) {
      if (closed) {
        return false;
      }
      checkState(
          registry.putIfAbsent(key, this) == null, "Session already has an active input queue");
      unbind = () -> registry.remove(key, this);
      return true;
    }
  }

  void started(String invocationId, Function<Content, Single<Event>> submit) {
    synchronized (lock) {
      if (startupResolved) {
        return;
      }
      this.submit = submit;
      startupResolved = true;
    }
    started.onSuccess(invocationId);
  }

  private boolean finish(@Nullable Throwable error) {
    boolean notifyStartup;
    synchronized (lock) {
      if (closed) {
        return false;
      }
      closed = true;
      submit = null;
      // Release the binding before cancellation can release this session's next invocation.
      if (unbind != null) {
        unbind.run();
        unbind = null;
      }
      notifyStartup = !startupResolved;
      startupResolved = true;
    }
    if (notifyStartup) {
      started.onError(
          error != null ? error : new IllegalStateException("Invocation ended before starting"));
    }
    return true;
  }
}
