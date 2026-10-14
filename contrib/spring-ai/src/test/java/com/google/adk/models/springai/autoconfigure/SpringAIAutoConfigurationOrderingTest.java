/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.models.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.SpringAIEmbedding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SpringAIAutoConfiguration sorts after provider auto-configurations via @AutoConfigureOrder, so
 * its order-sensitive conditions can see the provider beans.
 */
class SpringAIAutoConfigurationOrderingTest {

  // ToolCallingAutoConfiguration provides the ToolCallingManager OpenAiChatAutoConfiguration needs.
  private final ApplicationContextRunner providerRunner =
      new ApplicationContextRunner()
          .withPropertyValues("spring.ai.openai.api-key=dummy")
          .withConfiguration(
              AutoConfigurations.of(
                  SpringAIAutoConfiguration.class,
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiEmbeddingAutoConfiguration.class));

  @Test
  void registersSpringAI_whenProviderAutoConfigurationsAreProcessedFirst() {
    providerRunner.run(context -> assertThat(context).hasSingleBean(SpringAI.class));
  }

  @Test
  void registersSpringAIEmbedding_whenEmbeddingAutoConfigurationIsProcessedFirst() {
    providerRunner.run(context -> assertThat(context).hasSingleBean(SpringAIEmbedding.class));
  }
}
