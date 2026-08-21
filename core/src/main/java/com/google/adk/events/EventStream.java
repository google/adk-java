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

package com.google.adk.events;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Iterable stream of {@link Event} objects.
 *
 * <p>NOTE: This class is not thread-safe. Concurrent iteration from multiple threads should be
 * avoided or externally synchronized.
 */
public class EventStream implements Iterable<Event> {

  private final Supplier<Event> eventSupplier;

  /** Constructs a new event stream. */
  public EventStream(Supplier<Event> eventSupplier) {
    this.eventSupplier = eventSupplier;
  }

  /** Returns an iterator that fetches events lazily. */
  @Override
  public Iterator<Event> iterator() {
    return new EventIterator();
  }

  /** Iterator that returns events from the supplier until it returns {@code null}. */
  private class EventIterator implements Iterator<Event> {
    private Event nextEvent = null;
    private boolean finished = false;

    /** Returns {@code true} if another event is available. */
    @Override
    public boolean hasNext() {
      if (finished) {
        return false;
      }
      if (nextEvent == null) {
        nextEvent = eventSupplier.get();
        finished = (nextEvent == null);
      }
      return !finished;
    }

    /**
     * Returns the next event.
     *
     * @throws NoSuchElementException if no more events are available.
     */
    @Override
    public Event next() {
      if (!hasNext()) {
        throw new NoSuchElementException("No more events.");
      }
      Event currentEvent = nextEvent;
      nextEvent = null;
      return currentEvent;
    }
  }
}
