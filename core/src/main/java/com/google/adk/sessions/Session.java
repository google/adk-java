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

package com.google.adk.sessions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** A {@link Session} object that encapsulates the {@link State} and {@link Event}s of a session. */
@JsonDeserialize(builder = Session.Builder.class)
public final class Session extends JsonBaseModel {
  private final String id;

  private final String appName;

  private final String userId;

  private final State state;

  private final List<Event> events;

  private Instant lastUpdateTime;

  public static Builder builder(String id) {
    return new Builder(id);
  }

  /** Creates a new {@link Builder} with the given session key. */
  public static Builder builder(SessionKey sessionKey) {
    return new Builder(sessionKey);
  }

  /** Builder for {@link Session}. */
  public static final class Builder {
    private String id;
    private String appName;
    private String userId;
    private State state = new State(new ConcurrentHashMap<>());
    private List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private Instant lastUpdateTime = Instant.EPOCH;

    public Builder(String id) {
      this.id = id;
    }

    /** Creates a new {@link Builder} with the given session key. */
    public Builder(SessionKey sessionKey) {
      this.id = sessionKey.id();
      this.appName = sessionKey.appName();
      this.userId = sessionKey.userId();
    }

    @JsonCreator
    private Builder() {}

    @CanIgnoreReturnValue
    @JsonProperty("id")
    public Builder id(String id) {
      this.id = id;
      return this;
    }

    /** Sets the session key. */
    @CanIgnoreReturnValue
    public Builder sessionKey(SessionKey sessionKey) {
      this.id = sessionKey.id();
      this.appName = sessionKey.appName();
      this.userId = sessionKey.userId();
      return this;
    }

    @CanIgnoreReturnValue
    public Builder state(State state) {
      this.state = state;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("state")
    public Builder state(Map<String, Object> state) {
      this.state = new State(state);
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("appName")
    public Builder appName(String appName) {
      this.appName = appName;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("userId")
    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("events")
    public Builder events(@Nullable List<Event> events) {
      this.events =
          events == null
              ? Collections.synchronizedList(new ArrayList<>())
              : Collections.synchronizedList(new ArrayList<>(events));
      return this;
    }

    /**
     * Backs {@link Session#events()} with the given list directly, <b>without copying</b>, so the
     * session reflects a caller-owned live or converting view whose events grow as the owner
     * appends. The caller keeps ownership and the list's own semantics - thread-safety, and a
     * read-only view throwing on {@code add} - so ordinary code should use {@link #events(List)},
     * which defensively copies.
     *
     * @deprecated Not a real deprecation - a warn-off for application code from a seam meant for
     *     ADK's own framework and interop adapters; use {@link #events(List)} instead.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder eventsView(List<Event> events) {
      this.events = events;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder lastUpdateTime(Instant lastUpdateTime) {
      this.lastUpdateTime = lastUpdateTime;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("lastUpdateTime")
    public Builder lastUpdateTimeSeconds(double seconds) {
      long secs = (long) seconds;
      // Convert fractional part to nanoseconds
      long nanos = (long) ((seconds - secs) * Duration.ofSeconds(1).toNanos());
      this.lastUpdateTime = Instant.ofEpochSecond(secs, nanos);
      return this;
    }

    public Session build() {
      if (id == null) {
        throw new IllegalStateException("Session id is null");
      }
      return new Session(appName, userId, id, state, events, lastUpdateTime);
    }
  }

  /** Returns the session key. */
  public SessionKey sessionKey() {
    return new SessionKey(appName, userId, id);
  }

  @JsonProperty("id")
  public String id() {
    return id;
  }

  @JsonProperty("state")
  public Map<String, Object> state() {
    return state;
  }

  /**
   * Returns the mutable list of events backing this session, which also serves as the monitor for
   * all event-helper synchronization.
   *
   * <p>Prefer {@link #immutableEvents()} when reading or iterating over events (to avoid {@link
   * java.util.ConcurrentModificationException}), and {@link #addEvent(Event)} or {@link
   * #addEvents(Collection)} when appending events. Direct access to the mutable backing list is
   * discouraged and may eventually be marked as {@code @Deprecated}.
   */
  @JsonProperty("events")
  public List<Event> events() {
    return events;
  }

  /**
   * Returns a thread-safe, immutable snapshot of the session's events, synchronizing on the backing
   * {@link #events()} list. Use this method whenever reading, iterating, streaming, filtering, or
   * slicing ({@link List#subList}) session events to avoid {@link
   * java.util.ConcurrentModificationException} under concurrent modifications.
   */
  public ImmutableList<Event> immutableEvents() {
    synchronized (this.events) {
      return ImmutableList.copyOf(events);
    }
  }

  /**
   * Appends a single {@link Event} to the session's event list while synchronizing on the backing
   * {@link #events()} list; prefer over {@code events().add(event)}.
   */
  public void addEvent(Event event) {
    synchronized (this.events) {
      events.add(event);
    }
  }

  /**
   * Appends all {@link Event}s in the given collection to the session's event list while
   * synchronizing on the backing {@link #events()} list; prefer over {@code
   * events().addAll(events)}.
   */
  public void addEvents(Collection<Event> events) {
    synchronized (this.events) {
      this.events.addAll(events);
    }
  }

  /**
   * Removes all {@link Event}s from the session's event list while synchronizing on the backing
   * {@link #events()} list; prefer over {@code events().clear()}.
   */
  void clearEvents() {
    synchronized (this.events) {
      events.clear();
    }
  }

  @JsonProperty("appName")
  public String appName() {
    return appName;
  }

  @JsonProperty("userId")
  public String userId() {
    return userId;
  }

  public void lastUpdateTime(Instant lastUpdateTime) {
    this.lastUpdateTime = lastUpdateTime;
  }

  public Instant lastUpdateTime() {
    return lastUpdateTime;
  }

  @JsonProperty("lastUpdateTime")
  public double getLastUpdateTimeAsDouble() {
    if (lastUpdateTime == null) {
      return 0.0;
    }
    long seconds = lastUpdateTime.getEpochSecond();
    int nanos = lastUpdateTime.getNano();
    return seconds + nanos / (double) Duration.ofSeconds(1).toNanos();
  }

  @Override
  public String toString() {
    return toJson();
  }

  public static Session fromJson(String json) {
    return fromJsonString(json, Session.class);
  }

  private Session(
      String appName,
      String userId,
      String id,
      State state,
      @Nullable List<Event> events,
      Instant lastUpdateTime) {
    this.id = id;
    this.appName = appName;
    this.userId = userId;
    this.state = state;
    this.events = events != null ? events : Collections.synchronizedList(new ArrayList<>());
    this.lastUpdateTime = lastUpdateTime;
  }
}
