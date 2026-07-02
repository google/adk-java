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
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.VertexAiSessionService;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the {@link BaseSessionService} bean for the {@code IN_MEMORY} and {@code
 * VERTEX_AI} backends.
 *
 * <p>{@code FIRESTORE} is handled by {@code AdkFirestoreSessionAutoConfiguration} (gated by
 * {@code @ConditionalOnClass(FirestoreSessionService.class)} and {@code @AutoConfigureBefore} this
 * class — so when both are eligible, the Firestore branch wins).
 */
@AutoConfiguration
@EnableConfigurationProperties(AdkSessionProperties.class)
public class AdkSessionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BaseSessionService sessionService(AdkSessionProperties properties) {
    return switch (properties.getType()) {
      case VERTEX_AI -> buildVertexAiSession(properties);
      case FIRESTORE -> throw firestoreModuleMissing();
      case IN_MEMORY -> new InMemorySessionService();
    };
  }

  static VertexAiSessionService buildVertexAiSession(AdkSessionProperties properties) {
    String projectId = require(properties.getProjectId(), "adk.session.project-id");
    String location = require(properties.getLocation(), "adk.session.location");
    return new VertexAiSessionService(projectId, location, null, null);
  }

  static String require(String value, String propertyKey) {
    return Optional.ofNullable(value)
        .filter(s -> !s.isBlank())
        .orElseThrow(missing(propertyKey + " must be set when adk.session.type=VERTEX_AI"));
  }

  static Supplier<BeanCreationException> missing(String message) {
    return () -> new BeanCreationException(message);
  }

  static BeanCreationException firestoreModuleMissing() {
    return new BeanCreationException(
        "adk.session.type=FIRESTORE requires the 'google-adk-firestore-session-service' contrib"
            + " module on the classpath. It is declared as an optional dependency by"
            + " google-adk-spring-boot-starter — add it explicitly to your application pom if it"
            + " was excluded.");
  }
}
