/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.example.springbootadktemplate;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class SpringBootAdkTemplateApplicationTest {

  @Test
  void contextLoadsAndStarterBeansArePresent(ApplicationContext ctx) {
    assertThat(ctx.getBean(LlmAgent.class)).isNotNull();
    assertThat(ctx.getBean(App.class).name()).isEqualTo("spring_boot_adk_template");
    assertThat(ctx.getBean(BaseArtifactService.class)).isNotNull();
    assertThat(ctx.getBean(BaseSessionService.class)).isNotNull();
    assertThat(ctx.getBean(RunConfig.class)).isNotNull();
    assertThat(ctx.getBean(Runner.class).appName()).isEqualTo("spring_boot_adk_template");
  }
}
