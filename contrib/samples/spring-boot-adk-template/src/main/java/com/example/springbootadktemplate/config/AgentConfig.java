/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.example.springbootadktemplate.config;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sample agent topology — defines a root LlmAgent and packages it into an App.
 *
 * <p>The starter provides {@code Runner}, {@code BaseSessionService}, {@code BaseArtifactService},
 * and {@code RunConfig} automatically — this configuration only declares the user-specific
 * agent topology.
 */
@Configuration
public class AgentConfig {

  @Bean
  public LlmAgent rootAgent() {
    return LlmAgent.builder()
        .name("root_agent")
        .description("Sample assistant agent.")
        .model("gemini-2.5-flash")
        .instruction("Answer user questions to the best of your knowledge.")
        .build();
  }

  @Bean
  public App app(LlmAgent rootAgent, @Value("${spring.application.name}") String appName) {
    return App.builder().name(appName).rootAgent(rootAgent).build();
  }
}
