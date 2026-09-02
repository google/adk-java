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

import com.google.adk.agents.RunConfig;
import com.google.adk.autoconfigure.properties.AdkRunConfigProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configures the {@link RunConfig} bean from {@code adk.run-config.*} properties. */
@AutoConfiguration
@EnableConfigurationProperties(AdkRunConfigProperties.class)
public class AdkRunConfigAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public RunConfig runConfig(AdkRunConfigProperties properties) {
    return RunConfig.builder()
        .setStreamingMode(properties.getStreamingMode())
        .setMaxLlmCalls(properties.getMaxLlmCalls())
        .setToolExecutionMode(properties.getToolExecutionMode())
        .setSaveInputBlobsAsArtifacts(properties.isSaveInputBlobsAsArtifacts())
        .setAutoCreateSession(properties.isAutoCreateSession())
        .build();
  }
}
