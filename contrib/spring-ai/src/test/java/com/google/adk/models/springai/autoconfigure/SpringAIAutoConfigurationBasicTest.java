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
import com.google.adk.models.springai.properties.SpringAIProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class SpringAIAutoConfigurationBasicTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SpringAIAutoConfiguration.class));

  @Test
  void testAutoConfigurationWithChatModelOnly() {
    contextRunner
        .withUserConfiguration(TestConfigurationWithChatModel.class)
        .withPropertyValues(
            "adk.spring-ai.model=test-model",
            "adk.spring-ai.validation.enabled=false") // Disable validation for simplicity
        .run(
            context -> {
              assertThat(context).hasSingleBean(SpringAI.class);
              SpringAI springAI = context.getBean(SpringAI.class);
              assertThat(springAI.model()).isEqualTo("test-model");
            });
  }

  @Test
  void testAutoConfigurationDisabled() {
    contextRunner
        .withUserConfiguration(TestConfigurationWithChatModel.class)
        .withPropertyValues("adk.spring-ai.auto-configuration.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(SpringAI.class));
  }

  @Test
  void testDefaultConfiguration() {
    contextRunner
        .withUserConfiguration(TestConfigurationWithChatModel.class)
        .withPropertyValues("adk.spring-ai.validation.enabled=false") // Disable validation
        .run(
            context -> {
              assertThat(context).hasSingleBean(SpringAI.class);
              assertThat(context).hasSingleBean(SpringAIProperties.class);

              SpringAIProperties properties = context.getBean(SpringAIProperties.class);
              assertThat(properties.getTemperature()).isEqualTo(0.7);
              assertThat(properties.getMaxTokens()).isEqualTo(2048);
              assertThat(properties.getTopP()).isEqualTo(0.9);
              assertThat(properties.getValidation().isEnabled()).isFalse(); // We set it to false
              assertThat(properties.getValidation().isFailFast()).isTrue();
              assertThat(properties.getObservability().isEnabled()).isTrue();
              assertThat(properties.getObservability().isMetricsEnabled()).isTrue();
              assertThat(properties.getObservability().isIncludeContent()).isFalse();
            });
  }

  @Test
  void testValidConfigurationValues() {
    contextRunner
        .withUserConfiguration(TestConfigurationWithChatModel.class)
        .withPropertyValues(
            "adk.spring-ai.validation.enabled=false",
            "adk.spring-ai.temperature=0.5",
            "adk.spring-ai.max-tokens=1024",
            "adk.spring-ai.top-p=0.8")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SpringAI.class);
              SpringAIProperties properties = context.getBean(SpringAIProperties.class);
              assertThat(properties.getTemperature()).isEqualTo(0.5);
              assertThat(properties.getMaxTokens()).isEqualTo(1024);
              assertThat(properties.getTopP()).isEqualTo(0.8);
            });
  }

  @Configuration
  static class TestConfigurationWithChatModel {
    @Bean
    public ChatModel chatModel() {
      return prompt ->
          new ChatResponse(java.util.List.of(new Generation(new AssistantMessage("response"))));
    }
  }

  @Configuration
  static class TestConfigurationWithBothModels {
    @Bean
    public ChatModel chatModel() {
      return prompt ->
          new ChatResponse(java.util.List.of(new Generation(new AssistantMessage("response"))));
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
      return prompt ->
          Flux.just(
              new ChatResponse(
                  java.util.List.of(new Generation(new AssistantMessage("streaming")))));
    }
  }
}
