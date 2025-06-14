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
 * Represents a stream of {@link Event} objects that can be iterated over.
 * This class implements {@link Iterable} and provides an {@link Iterator} that fetches
 * events on demand using a supplied {@link Supplier<Event>}. The stream terminates
 * when the {@code eventSupplier} returns {@code null}.
 */
public class EventStream implements Iterable<Event> {

  private final Supplier<Event> eventSupplier;

  /**
   * Constructs an {@code EventStream} with the given supplier for events.
   *
   * @param eventSupplier A {@link Supplier<Event>} that provides the next event in the stream.
   * The supplier should return {@code null} when no more events are available.
   */
  public EventStream(Supplier<Event> eventSupplier) {
    this.eventSupplier = eventSupplier;
  }

  /**
   * Returns an iterator over elements of type {@link Event}.
   * The iterator fetches events from the stream's supplier as needed.
   *
   * @return an {@link Iterator} for this {@code EventStream}.
   */
  @Override
  public Iterator<Event> iterator() {
    return new EventIterator();
  }

  /**
   * An inner class that implements the {@link Iterator} interface for {@link EventStream}.
   * It handles fetching events lazily from the {@link EventStream}'s supplier and
   * manages the state of the iteration, including detecting the end of the stream.
   */
  private class EventIterator implements Iterator<Event> {
    /** Stores the next event to be returned, fetched proactively by {@code hasNext()}. */
    private Event nextEvent = null;
    /** A flag indicating if the end of the stream has been reached. */
    private boolean finished = false;

    /**
     * Returns {@code true} if the iteration has more elements.
     * This method attempts to fetch the next event if it hasn't been fetched yet.
     *
     * @return {@code true} if the iterator has more elements, {@code false} otherwise.
     */
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
     * Returns the next element in the iteration.
     *
     * @return the next {@link Event} in the iteration.
     * @throws NoSuchElementException if the iteration has no more elements.
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
