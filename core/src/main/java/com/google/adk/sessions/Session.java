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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

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

  /** Builder for {@link Session}. */
  public static final class Builder {
    private String id;
    private String appName;
    private String userId;
    private State state = new State(new ConcurrentHashMap<>());
    private List<Event> events = new ArrayList<>();
    private Instant lastUpdateTime = Instant.EPOCH;

    public Builder(String id) {
      this.id = id;
    }

    @JsonCreator
    private Builder() {}

    @JsonProperty("id")
    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder state(State state) {
      this.state = state;
      return this;
    }

    @JsonProperty("state")
    public Builder state(ConcurrentMap<String, Object> state) {
      this.state = new State(state);
      return this;
    }

    @JsonProperty("appName")
    public Builder appName(String appName) {
      this.appName = appName;
      return this;
    }

    @JsonProperty("userId")
    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    @JsonProperty("events")
    public Builder events(List<Event> events) {
      this.events = events;
      return this;
    }

    public Builder lastUpdateTime(Instant lastUpdateTime) {
      this.lastUpdateTime = lastUpdateTime;
      return this;
    }

    @JsonProperty("lastUpdateTime")
    public Builder lastUpdateTimeSeconds(double seconds) {
      long secs = (long) seconds;
      // Convert fractional part to nanoseconds
      long nanos = (long) ((seconds - secs) * TimeUnit.SECONDS.toNanos(1));
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

  @JsonProperty("id")
  public String id() {
    return id;
  }

  @JsonProperty("state")
  public ConcurrentMap<String, Object> state() {
    return state;
  }

  @JsonProperty("events")
  public List<Event> events() {
    return events;
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
    return seconds + nanos / (double) TimeUnit.SECONDS.toNanos(1);
  }

  public static Session fromJson(String json) {
    return fromJsonString(json, Session.class);
  }

  private Session(
      String appName,
      String userId,
      String id,
      State state,
      List<Event> events,
      Instant lastUpdateTime) {
    this.id = id;
    this.appName = appName;
    this.userId = userId;
    this.state = state;
    this.events = events;
    this.lastUpdateTime = lastUpdateTime;
  }
}
