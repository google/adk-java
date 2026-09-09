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
 * Configuration properties for ADK session-service auto-configuration. Prefix {@code adk.session}.
 */
@ConfigurationProperties(prefix = "adk.session")
public class AdkSessionProperties {

  /** Which session-service backend to wire. Defaults to {@link Type#IN_MEMORY}. */
  private Type type = Type.IN_MEMORY;

  /** GCP project id (required when {@link #type} = {@link Type#VERTEX_AI}). */
  private String projectId;

  /** GCP location (required when {@link #type} = {@link Type#VERTEX_AI}). */
  private String location;

  public enum Type {
    /** {@code com.google.adk.sessions.InMemorySessionService} — default, no external storage. */
    IN_MEMORY,
    /**
     * {@code com.google.adk.sessions.VertexAiSessionService} — managed Vertex AI Reasoning Engine.
     */
    VERTEX_AI,
    /**
     * {@code com.google.adk.sessions.FirestoreSessionService} — requires the {@code
     * google-adk-firestore-session-service} contrib module on the classpath.
     */
    FIRESTORE
  }

  public Type getType() {
    return type;
  }

  public void setType(Type type) {
    this.type = type;
  }

  public String getProjectId() {
    return projectId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public String getLocation() {
    return location;
  }

  public void setLocation(String location) {
    this.location = location;
  }
}
