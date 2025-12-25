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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.Type;

/**
 * Entity for storing application-level state in the database. This is mapped to the "app_states"
 * table.
 */
@Entity
@Table(name = "app_states")
public class StorageAppState {

  @Id
  @Column(name = "app_name", length = 128)
  private String appName;

  @Column(name = "state")
  @Type(JsonUserType.class)
  private Map<String, Object> state;

  @Column(name = "update_time")
  private Instant updateTime;

  // Default constructor
  public StorageAppState() {}

  // Getters and setters
  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public Map<String, Object> getState() {
    return state;
  }

  public void setState(Map<String, Object> state) {
    this.state = state;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant updateTime) {
    this.updateTime = updateTime;
  }
}
