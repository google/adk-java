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
 * Configuration properties for ADK memory-service auto-configuration. Prefix {@code adk.memory}.
 */
@ConfigurationProperties(prefix = "adk.memory")
public class AdkMemoryProperties {

  /** Which memory-service backend to wire. Defaults to {@link Type#IN_MEMORY}. */
  private Type type = Type.IN_MEMORY;

  public enum Type {
    /** {@code com.google.adk.memory.InMemoryMemoryService} — default, no external storage. */
    IN_MEMORY,
    /** Reserved — no concrete Vertex AI memory service exists in ADK today; fails fast. */
    VERTEX_AI,
    /**
     * {@code com.google.adk.memory.FirestoreMemoryService} — requires the {@code
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
}
