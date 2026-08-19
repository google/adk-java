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

import com.google.adk.autoconfigure.properties.AdkSessionProperties;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.cloud.firestore.Firestore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Firestore-backed {@link BaseSessionService} variant. Active only when {@code
 * FirestoreSessionService} is on the classpath; runs <em>before</em> {@link
 * AdkSessionAutoConfiguration} so the regular factory's {@code @ConditionalOnMissingBean} steps
 * aside whenever this branch is eligible.
 */
@AutoConfiguration
@AutoConfigureBefore(AdkSessionAutoConfiguration.class)
@ConditionalOnClass(FirestoreSessionService.class)
@EnableConfigurationProperties(AdkSessionProperties.class)
public class AdkFirestoreSessionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BaseSessionService sessionService(
      AdkSessionProperties properties, ObjectProvider<Firestore> firestoreProvider) {
    return switch (properties.getType()) {
      case VERTEX_AI -> AdkSessionAutoConfiguration.buildVertexAiSession(properties);
      case FIRESTORE -> new FirestoreSessionService(firestoreProvider.getObject());
      case IN_MEMORY -> new InMemorySessionService();
    };
  }
}
