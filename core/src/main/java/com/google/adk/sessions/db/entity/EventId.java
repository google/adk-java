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
package com.google.adk.sessions.db.entity;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for the StorageEvent entity, consisting of id, appName, userId, and sessionId. */
public class EventId implements Serializable {
  private String id;
  private String appName;
  private String userId;
  private String sessionId;

  // Default constructor required by JPA
  public EventId() {}

  public EventId(String id, String appName, String userId, String sessionId) {
    this.id = id;
    this.appName = appName;
    this.userId = userId;
    this.sessionId = sessionId;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    EventId eventId = (EventId) o;
    return Objects.equals(id, eventId.id)
        && Objects.equals(appName, eventId.appName)
        && Objects.equals(userId, eventId.userId)
        && Objects.equals(sessionId, eventId.sessionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, appName, userId, sessionId);
  }
}
