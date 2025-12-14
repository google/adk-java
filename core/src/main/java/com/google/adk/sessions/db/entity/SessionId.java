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

/** Composite key for the StorageSession entity, consisting of appName, userId, and id. */
public class SessionId implements Serializable {
  private String appName;
  private String userId;
  private String id;

  // Default constructor required by JPA
  public SessionId() {}

  public SessionId(String appName, String userId, String id) {
    this.appName = appName;
    this.userId = userId;
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

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SessionId sessionId = (SessionId) o;
    return Objects.equals(appName, sessionId.appName)
        && Objects.equals(userId, sessionId.userId)
        && Objects.equals(id, sessionId.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(appName, userId, id);
  }
}
