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
import com.google.adk.memory.FirestoreMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.cloud.firestore.Firestore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Firestore-backed {@link BaseMemoryService} variant. Active only when {@code
 * FirestoreMemoryService} is on the classpath; runs <em>before</em> {@link
 * AdkMemoryAutoConfiguration}.
 */
@AutoConfiguration
@AutoConfigureBefore(AdkMemoryAutoConfiguration.class)
@ConditionalOnClass(FirestoreMemoryService.class)
@EnableConfigurationProperties(AdkMemoryProperties.class)
public class AdkFirestoreMemoryAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BaseMemoryService memoryService(
      AdkMemoryProperties properties, ObjectProvider<Firestore> firestoreProvider) {
    return switch (properties.getType()) {
      case VERTEX_AI -> throw AdkMemoryAutoConfiguration.vertexAiUnsupported();
      case FIRESTORE -> new FirestoreMemoryService(firestoreProvider.getObject());
      case IN_MEMORY -> new InMemoryMemoryService();
    };
  }
}
