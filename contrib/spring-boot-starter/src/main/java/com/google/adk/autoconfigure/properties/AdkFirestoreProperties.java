/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.autoconfigure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the {@code com.google.cloud.firestore.Firestore} client bean the
 * starter creates when one of {@code adk.session.type=FIRESTORE} or {@code
 * adk.memory.type=FIRESTORE} is requested. Both fields are optional — when unset the starter relies
 * on Application Default Credentials and Firestore's {@code "(default)"} database.
 */
@ConfigurationProperties(prefix = "adk.firestore")
public class AdkFirestoreProperties {

  /** GCP project id. Optional — falls back to the ADC project. */
  private String projectId;

  /** Firestore database id. Optional — falls back to {@code "(default)"}. */
  private String databaseId;

  public String getProjectId() {
    return projectId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public String getDatabaseId() {
    return databaseId;
  }

  public void setDatabaseId(String databaseId) {
    this.databaseId = databaseId;
  }
}
