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

import com.google.adk.sessions.db.converter.JsonUserType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Type;

/** Entity for storing session data in the database. This is mapped to the "sessions" table. */
@Entity
@Table(name = "sessions")
@IdClass(SessionId.class)
public class StorageSession {

  @Id
  @Column(name = "app_name", length = 128)
  private String appName;

  @Id
  @Column(name = "user_id", length = 128)
  private String userId;

  @Id
  @Column(name = "id", length = 128)
  private String id;

  @Column(name = "state")
  @Type(JsonUserType.class)
  private Map<String, Object> state;

  @Column(name = "create_time")
  private Instant createTime;

  @Column(name = "update_time")
  private Instant updateTime;

  @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<StorageEvent> events = new ArrayList<>();

  // Default constructor
  public StorageSession() {}

  // Getter and setter methods
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

  public Map<String, Object> getState() {
    return state;
  }

  public void setState(Map<String, Object> state) {
    this.state = state;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant createTime) {
    this.createTime = createTime;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant updateTime) {
    this.updateTime = updateTime;
  }

  public List<StorageEvent> getEvents() {
    return events;
  }

  public void setEvents(List<StorageEvent> events) {
    this.events = events;
  }

  /**
   * Adds an event to this session.
   *
   * @param event The event to add
   */
  public void addEvent(StorageEvent event) {
    events.add(event);
    event.setSession(this);
  }
}
