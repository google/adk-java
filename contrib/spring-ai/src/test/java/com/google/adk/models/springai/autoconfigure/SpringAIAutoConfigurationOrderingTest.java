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

import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.SpringAIEmbedding;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies that {@link SpringAIAutoConfiguration} is ordered after the Spring AI model
 * auto-configurations.
 *
 * <p>{@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean} conditions are
 * evaluated when the configuration is processed, so {@code @ConditionalOnBean(ChatModel)} on this
 * auto-configuration only sees the model beans if the provider auto-configurations ran first. In a
 * real application the configurations are otherwise sorted alphabetically, which puts {@code
 * com.google.adk...} before {@code org.springframework.ai...} and makes every SpringAI bean
 * silently disappear (see issue #1501).
 */
class SpringAIAutoConfigurationOrderingTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  SpringAIAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiEmbeddingAutoConfiguration.class,
                  ToolCallingAutoConfiguration.class));

  @Test
  void springAIBeansAreCreatedWhenModelsComeFromSpringAIModelAutoConfigurations() {
    contextRunner
        .withPropertyValues(
            "spring.ai.openai.api-key=dummy-key", "adk.spring-ai.validation.enabled=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SpringAI.class);
              assertThat(context).hasSingleBean(SpringAIEmbedding.class);
            });
  }

  @Test
  void disabledAutoConfigurationStillBacksOff() {
    contextRunner
        .withPropertyValues(
            "spring.ai.openai.api-key=dummy-key", "adk.spring-ai.auto-configuration.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(SpringAI.class);
              assertThat(context).doesNotHaveBean(SpringAIEmbedding.class);
              assertThat(context).hasSingleBean(org.springframework.ai.chat.model.ChatModel.class);
            });
  }
}
