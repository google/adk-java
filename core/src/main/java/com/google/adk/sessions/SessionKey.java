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
import com.google.adk.JsonBaseModel;
import java.util.Objects;

/** Key for a session, composed of appName, userId and session id. */
public final class SessionKey extends JsonBaseModel {
  private final String appName;
  private final String userId;
  private final String id;

  @JsonCreator
  public SessionKey(
      @JsonProperty("appName") String appName,
      @JsonProperty("userId") String userId,
      @JsonProperty("id") String id) {
    this.appName = appName;
    this.userId = userId;
    this.id = id;
  }

  @JsonProperty("appName")
  public String appName() {
    return appName;
  }

  @JsonProperty("userId")
  public String userId() {
    return userId;
  }

  @JsonProperty("id")
  public String id() {
    return id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SessionKey that = (SessionKey) o;
    return Objects.equals(appName, that.appName)
        && Objects.equals(userId, that.userId)
        && Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(appName, userId, id);
  }

  @Override
  public String toString() {
    return toJson();
  }

  public static SessionKey fromJson(String json) {
    return fromJsonString(json, SessionKey.class);
  }
}
