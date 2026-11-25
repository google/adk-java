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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.platform.UuidProvider;
import com.google.adk.sessions.SessionKey;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-process steering service; sharing an instance lets runners submit to the same inbox. */
@Experimental
public final class InMemorySteeringService implements BaseSteeringService {
  private final ConcurrentMap<Key, MemoryInbox> inboxes = new ConcurrentHashMap<>();
  private final UuidProvider uuidProvider;

  public InMemorySteeringService() {
    this(UuidProvider.SYSTEM);
  }

  public InMemorySteeringService(UuidProvider uuidProvider) {
    this.uuidProvider = checkNotNull(uuidProvider);
  }

  @Override
  public Inbox open(SessionKey sessionKey, String invocationId) {
    Key key = new Key(checkNotNull(sessionKey), checkNotNull(invocationId));
    MemoryInbox inbox = new MemoryInbox(key);
    checkState(
        inboxes.putIfAbsent(key, inbox) == null, "Invocation is already accepting steering input");
    return inbox;
  }

  @Override
  public Single<Event> submit(SessionKey sessionKey, String invocationId, Content message) {
    MemoryInbox inbox = inboxes.get(new Key(sessionKey, invocationId));
    checkState(inbox != null, "No active steerable invocation: %s", invocationId);
    return inbox.submit(checkNotNull(message));
  }

  private record Key(SessionKey sessionKey, String invocationId) {}

  private static final class Pending {
    final Input input;
    final SingleSubject<Event> receipt = SingleSubject.create();

    Pending(Input input) {
      this.input = input;
    }
  }

  private final class MemoryInbox implements Inbox {
    private final Key key;
    private final Object lock = new Object();

    @GuardedBy("lock")
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    @GuardedBy("lock")
    private boolean closed;

    MemoryInbox(Key key) {
      this.key = key;
    }

    Single<Event> submit(Content message) {
      // A caller-supplied provider may block; do not hold up inbox cleanup or other admissions.
      String id = uuidProvider.newUuid();
      Pending input = new Pending(new Input(id, message));
      synchronized (lock) {
        checkState(!closed, "Invocation is not accepting steering input");
        checkState(!pending.containsKey(id), "Input ID is already pending");
        pending.put(id, input);
      }
      return input.receipt.hide();
    }

    @Override
    public List<Input> pending() {
      synchronized (lock) {
        return pending.values().stream()
            .map(input -> input.input)
            .collect(ImmutableList.toImmutableList());
      }
    }

    @Override
    public void persisted(Event event) {
      Pending input;
      synchronized (lock) {
        input = pending.remove(event.id());
      }
      if (input != null) {
        input.receipt.onSuccess(event);
      }
    }

    @Override
    public boolean closeIfIdle() {
      synchronized (lock) {
        if (pending.isEmpty()) {
          closed = true;
          inboxes.remove(key, this);
        }
        return closed;
      }
    }

    @Override
    public void close() {
      List<Pending> unconsumed;
      synchronized (lock) {
        if (closed) {
          return;
        }
        closed = true;
        inboxes.remove(key, this);
        unconsumed = ImmutableList.copyOf(pending.values());
        pending.clear();
      }
      for (Pending input : unconsumed) {
        input.receipt.onError(
            new IllegalStateException("Invocation ended before steering input was consumed"));
      }
    }
  }
}
