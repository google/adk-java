/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.autoconfigure;

import com.google.adk.autoconfigure.properties.AdkMemoryProperties;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the {@link BaseMemoryService} bean for the {@code IN_MEMORY} backend.
 *
 * <p>{@code VERTEX_AI} fails fast — no Vertex AI memory service exists in ADK today. {@code
 * FIRESTORE} is handled by {@code AdkFirestoreMemoryAutoConfiguration} when the contrib module is
 * on the classpath.
 */
@AutoConfiguration
@EnableConfigurationProperties(AdkMemoryProperties.class)
public class AdkMemoryAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BaseMemoryService memoryService(AdkMemoryProperties properties) {
    return switch (properties.getType()) {
      case VERTEX_AI -> throw vertexAiUnsupported();
      case FIRESTORE -> throw firestoreModuleMissing();
      case IN_MEMORY -> new InMemoryMemoryService();
    };
  }

  static BeanCreationException vertexAiUnsupported() {
    return new BeanCreationException(
        "adk.memory.type=VERTEX_AI is not supported — no Vertex AI memory service exists in ADK"
            + " today. Use IN_MEMORY, FIRESTORE, or provide your own BaseMemoryService bean.");
  }

  static BeanCreationException firestoreModuleMissing() {
    return new BeanCreationException(
        "adk.memory.type=FIRESTORE requires the 'google-adk-firestore-session-service' contrib"
            + " module on the classpath. It is declared as an optional dependency by"
            + " google-adk-spring-boot-starter — add it explicitly to your application pom if it"
            + " was excluded.");
  }
}
