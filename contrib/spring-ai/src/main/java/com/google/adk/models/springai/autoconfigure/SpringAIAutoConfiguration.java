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

import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.SpringAIEmbedding;
import com.google.adk.models.springai.properties.SpringAIProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for Spring AI integration with ADK.
 *
 * <p>This auto-configuration automatically creates SpringAI beans when Spring AI ChatModel beans
 * are available in the application context. It supports both regular ChatModel and
 * StreamingChatModel instances.
 *
 * <p>The auto-configuration can be disabled by setting:
 *
 * <pre>
 * adk.spring-ai.auto-configuration.enabled=false
 * </pre>
 *
 * <p>Example usage in application.properties:
 *
 * <pre>
 * # OpenAI configuration
 * spring.ai.openai.api-key=${OPENAI_API_KEY}
 * spring.ai.openai.chat.options.model=gpt-4o-mini
 * spring.ai.openai.chat.options.temperature=0.7
 *
 * # ADK Spring AI configuration
 * adk.spring-ai.default-model=gpt-4o-mini
 * adk.spring-ai.validation.enabled=true
 * </pre>
 */
@AutoConfiguration
@ConditionalOnClass({SpringAI.class, ChatModel.class})
@ConditionalOnProperty(
    prefix = "adk.spring-ai.auto-configuration",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(SpringAIProperties.class)
public class SpringAIAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(SpringAIAutoConfiguration.class);

  /**
   * Creates a SpringAI bean when both ChatModel and StreamingChatModel are available.
   *
   * @param chatModel the Spring AI ChatModel
   * @param streamingChatModel the Spring AI StreamingChatModel
   * @param properties the ADK Spring AI properties
   * @return configured SpringAI instance
   */
  @Bean
  @Primary
  @ConditionalOnMissingBean(SpringAI.class)
  @ConditionalOnBean({ChatModel.class, StreamingChatModel.class})
  public SpringAI springAIWithBothModels(
      ChatModel chatModel, StreamingChatModel streamingChatModel, SpringAIProperties properties) {

    String modelName = determineModelName(chatModel, properties);
    logger.info(
        "Auto-configuring SpringAI with both ChatModel and StreamingChatModel. Model: {}",
        modelName);

    validateConfiguration(properties);
    return new SpringAI(chatModel, streamingChatModel, modelName, properties.getObservability());
  }

  /**
   * Creates a SpringAI bean when only ChatModel is available.
   *
   * @param chatModel the Spring AI ChatModel
   * @param properties the ADK Spring AI properties
   * @return configured SpringAI instance
   */
  @Bean
  @ConditionalOnMissingBean(SpringAI.class)
  @ConditionalOnBean(ChatModel.class)
  public SpringAI springAIWithChatModel(ChatModel chatModel, SpringAIProperties properties) {

    String modelName = determineModelName(chatModel, properties);
    logger.info("Auto-configuring SpringAI with ChatModel only. Model: {}", modelName);

    validateConfiguration(properties);
    return new SpringAI(chatModel, modelName, properties.getObservability());
  }

  /**
   * Creates a SpringAI bean when only StreamingChatModel is available.
   *
   * @param streamingChatModel the Spring AI StreamingChatModel
   * @param properties the ADK Spring AI properties
   * @return configured SpringAI instance
   */
  @Bean
  @ConditionalOnMissingBean({SpringAI.class, ChatModel.class})
  @ConditionalOnBean(StreamingChatModel.class)
  public SpringAI springAIWithStreamingModel(
      StreamingChatModel streamingChatModel, SpringAIProperties properties) {

    String modelName = determineModelName(streamingChatModel, properties);
    logger.info("Auto-configuring SpringAI with StreamingChatModel only. Model: {}", modelName);

    validateConfiguration(properties);
    return new SpringAI(streamingChatModel, modelName, properties.getObservability());
  }

  /**
   * Creates a SpringAIEmbedding bean when EmbeddingModel is available.
   *
   * @param embeddingModel the Spring AI EmbeddingModel
   * @param properties the ADK Spring AI properties
   * @return configured SpringAIEmbedding instance
   */
  @Bean
  @ConditionalOnMissingBean(SpringAIEmbedding.class)
  @ConditionalOnBean(EmbeddingModel.class)
  public SpringAIEmbedding springAIEmbedding(
      EmbeddingModel embeddingModel, SpringAIProperties properties) {

    String modelName = determineEmbeddingModelName(embeddingModel, properties);
    logger.info("Auto-configuring SpringAIEmbedding with EmbeddingModel. Model: {}", modelName);

    return new SpringAIEmbedding(embeddingModel, modelName, properties.getObservability());
  }

  /**
   * Determines the model name to use for the SpringAI instance.
   *
   * @param model the Spring AI model (ChatModel or StreamingChatModel)
   * @param properties the configuration properties
   * @return the model name to use
   */
  private String determineModelName(Object model, SpringAIProperties properties) {
    // Try to extract model name from the actual model instance
    String extractedName = extractModelNameFromInstance(model);
    if (extractedName != null && !extractedName.trim().isEmpty()) {
      return extractedName;
    }

    // Check if model name is configured in properties
    if (properties.getModel() != null && !properties.getModel().trim().isEmpty()) {
      return properties.getModel();
    }

    return "Unknown Model Name";
  }

  /**
   * Determines the model name to use for the SpringAIEmbedding instance.
   *
   * @param embeddingModel the Spring AI EmbeddingModel
   * @param properties the configuration properties
   * @return the model name to use
   */
  private String determineEmbeddingModelName(
      EmbeddingModel embeddingModel, SpringAIProperties properties) {
    // Try to extract model name from the actual model instance
    String extractedName = extractEmbeddingModelNameFromInstance(embeddingModel);
    if (extractedName != null && !extractedName.trim().isEmpty()) {
      return extractedName;
    }

    // Check if model name is configured in properties
    if (properties.getModel() != null && !properties.getModel().trim().isEmpty()) {
      return properties.getModel();
    }

    return "Unknown Embedding Model Name";
  }

  /**
   * Attempts to extract the model name from the Spring AI embedding model instance.
   *
   * @param embeddingModel the embedding model instance
   * @return the extracted model name, or null if not extractable
   */
  private String extractEmbeddingModelNameFromInstance(EmbeddingModel embeddingModel) {
    try {
      // Try to get the default options from the model using reflection
      java.lang.reflect.Method getDefaultOptions =
          embeddingModel.getClass().getMethod("getDefaultOptions");
      Object options = getDefaultOptions.invoke(embeddingModel);

      if (options != null) {
        // Try to get the model name from the options
        java.lang.reflect.Method getModel = options.getClass().getMethod("getModel");
        Object modelName = getModel.invoke(options);

        if (modelName instanceof String && !((String) modelName).trim().isEmpty()) {
          logger.debug("Extracted embedding model name from options: {}", modelName);
          return (String) modelName;
        }
      }
    } catch (Exception e) {
      logger.debug(
          "Could not extract embedding model name via getDefaultOptions(): {}", e.getMessage());
    }

    return null;
  }

  /**
   * Attempts to extract the model name from the Spring AI model instance.
   *
   * @param model the model instance
   * @return the extracted model name, or null if not extractable
   */
  private String extractModelNameFromInstance(Object model) {
    try {
      // Try to get the default options from the model using reflection
      java.lang.reflect.Method getDefaultOptions = model.getClass().getMethod("getDefaultOptions");
      Object options = getDefaultOptions.invoke(model);

      if (options != null) {
        // Try to get the model name from the options
        java.lang.reflect.Method getModel = options.getClass().getMethod("getModel");
        Object modelName = getModel.invoke(options);

        if (modelName instanceof String && !((String) modelName).trim().isEmpty()) {
          logger.debug("Extracted model name from options: {}", modelName);
          return (String) modelName;
        }
      }
    } catch (Exception e) {
      logger.debug("Could not extract model name via getDefaultOptions(): {}", e.getMessage());
    }

    return null;
  }

  /**
   * Validates the configuration properties if validation is enabled.
   *
   * @param properties the configuration properties to validate
   * @throws IllegalArgumentException if validation fails and fail-fast is enabled
   */
  private void validateConfiguration(SpringAIProperties properties) {
    if (!properties.getValidation().isEnabled()) {
      logger.debug("Configuration validation is disabled");
      return;
    }

    logger.debug("Validating SpringAI configuration");

    try {
      // Validate temperature
      if (properties.getTemperature() != null) {
        double temperature = properties.getTemperature();
        if (temperature < 0.0 || temperature > 2.0) {
          throw new IllegalArgumentException(
              "Temperature must be between 0.0 and 2.0, got: " + temperature);
        }
      }

      // Validate topP
      if (properties.getTopP() != null) {
        double topP = properties.getTopP();
        if (topP < 0.0 || topP > 1.0) {
          throw new IllegalArgumentException("Top-p must be between 0.0 and 1.0, got: " + topP);
        }
      }

      // Validate maxTokens
      if (properties.getMaxTokens() != null) {
        int maxTokens = properties.getMaxTokens();
        if (maxTokens < 1) {
          throw new IllegalArgumentException("Max tokens must be at least 1, got: " + maxTokens);
        }
      }

      // Validate topK
      if (properties.getTopK() != null) {
        int topK = properties.getTopK();
        if (topK < 1) {
          throw new IllegalArgumentException("Top-k must be at least 1, got: " + topK);
        }
      }

      logger.info("SpringAI configuration validation passed");

    } catch (IllegalArgumentException e) {
      logger.error("SpringAI configuration validation failed: {}", e.getMessage());

      if (properties.getValidation().isFailFast()) {
        throw e;
      } else {
        logger.warn("Continuing with invalid configuration (fail-fast disabled)");
      }
    }
  }
}
