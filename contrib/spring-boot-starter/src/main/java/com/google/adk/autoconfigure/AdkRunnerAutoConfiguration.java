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

import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures a {@link Runner} bean from a user-declared {@link App} plus the starter-provided
 * service beans.
 *
 * <p>Single-BU case: when exactly one {@link App} bean exists in the context, this factory wires a
 * single {@link Runner}. Multi-BU case: when multiple {@link App} beans exist (different business
 * units in the same Spring Boot app), {@link ConditionalOnSingleCandidate} suppresses this factory
 * and the user is expected to declare one explicit {@link Runner} per business unit.
 *
 * <p>The {@link BaseMemoryService} dependency is injected via {@link ObjectProvider} because it is
 * optional — {@link Runner.Builder} accepts {@code null} memory and the starter does not always
 * produce a memory bean.
 */
@AutoConfiguration(
    after = {
      AdkArtifactsAutoConfiguration.class,
      AdkSessionAutoConfiguration.class,
      AdkMemoryAutoConfiguration.class,
      AdkFirestoreSessionAutoConfiguration.class,
      AdkFirestoreMemoryAutoConfiguration.class
    })
public class AdkRunnerAutoConfiguration {

  @Bean
  @ConditionalOnBean(App.class)
  @ConditionalOnSingleCandidate(App.class)
  @ConditionalOnMissingBean(Runner.class)
  public Runner runner(
      App app,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      ObjectProvider<BaseMemoryService> memoryServiceProvider) {
    Runner.Builder builder =
        Runner.builder().app(app).artifactService(artifactService).sessionService(sessionService);
    memoryServiceProvider.ifAvailable(builder::memoryService);
    return builder.build();
  }
}
