/*
 * Copyright 2025 Google LLC
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

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression tests: SpringAIAutoConfiguration guards its beans with {@code @ConditionalOnBean},
 * which is order-sensitive. It must therefore declare itself ordered after the Spring AI model
 * auto-configurations, otherwise the conditions never match and no SpringAI/SpringAIEmbedding bean
 * is registered.
 */
class SpringAIAutoConfigurationOrderingTest {

  // OpenAI auto-configurations fail at context refresh without a non-blank api key.
  private static final String[] PROPERTIES = {"spring.ai.openai.api-key=dummy"};

  // ToolCallingAutoConfiguration provides the ToolCallingManager that
  // OpenAiChatAutoConfiguration requires. A real application imports it
  // automatically; ApplicationContextRunner only processes what is declared.
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(PROPERTIES)
          .withConfiguration(
              AutoConfigurations.of(
                  SpringAIAutoConfiguration.class, // listed first on purpose
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiEmbeddingAutoConfiguration.class));

  @Test
  void registersSpringAI_whenProviderAutoConfigurationsAreProcessedFirst() {
    runner.run(context -> assertThat(context).hasBean("springAIWithBothModels"));
  }

  @Test
  void registersSpringAIEmbedding_whenEmbeddingAutoConfigurationIsProcessedFirst() {
    runner.run(context -> assertThat(context).hasBean("springAIEmbedding"));
  }
}
