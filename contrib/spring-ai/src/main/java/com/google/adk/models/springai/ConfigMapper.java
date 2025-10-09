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
package com.google.adk.models.springai;

import com.google.genai.types.GenerateContentConfig;
import java.util.Optional;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Maps ADK GenerateContentConfig to Spring AI ChatOptions.
 *
 * <p>This mapper handles the translation between ADK's GenerateContentConfig and Spring AI's
 * ChatOptions, enabling configuration parameters like temperature, max tokens, and stop sequences
 * to be passed through to Spring AI models.
 */
public class ConfigMapper {

  /**
   * Converts ADK GenerateContentConfig to Spring AI ChatOptions.
   *
   * @param config The ADK configuration to convert
   * @return Spring AI ChatOptions or null if no config provided
   */
  public ChatOptions toSpringAiChatOptions(Optional<GenerateContentConfig> config) {
    if (config.isEmpty()) {
      return null;
    }

    GenerateContentConfig contentConfig = config.get();
    ChatOptions.Builder optionsBuilder = ChatOptions.builder();

    // Map temperature (convert Float to Double)
    contentConfig.temperature().ifPresent(temp -> optionsBuilder.temperature(temp.doubleValue()));

    // Map max output tokens
    contentConfig.maxOutputTokens().ifPresent(optionsBuilder::maxTokens);

    // Map top P (convert Float to Double)
    contentConfig.topP().ifPresent(topP -> optionsBuilder.topP(topP.doubleValue()));

    // Map top K (convert Float to Integer)
    contentConfig.topK().ifPresent(topK -> optionsBuilder.topK(topK.intValue()));

    // Map stop sequences
    contentConfig
        .stopSequences()
        .filter(sequences -> !sequences.isEmpty())
        .ifPresent(optionsBuilder::stopSequences);

    // Map presence penalty (if supported by Spring AI)
    contentConfig
        .presencePenalty()
        .ifPresent(
            penalty -> {
              // Spring AI may support presence penalty through model-specific options
              // This will be handled in provider-specific adapters
            });

    // Map frequency penalty (if supported by Spring AI)
    contentConfig
        .frequencyPenalty()
        .ifPresent(
            penalty -> {
              // Spring AI may support frequency penalty through model-specific options
              // This will be handled in provider-specific adapters
            });

    return optionsBuilder.build();
  }

  /**
   * Creates default ChatOptions for cases where no ADK config is provided.
   *
   * @return Basic ChatOptions with reasonable defaults
   */
  public ChatOptions createDefaultChatOptions() {
    return ChatOptions.builder().temperature(0.7).maxTokens(1000).build();
  }

  /**
   * Validates that the configuration is compatible with Spring AI.
   *
   * @param config The ADK configuration to validate
   * @return true if configuration is valid and supported
   */
  public boolean isConfigurationValid(Optional<GenerateContentConfig> config) {
    if (config.isEmpty()) {
      return true; // No config is valid
    }

    GenerateContentConfig contentConfig = config.get();

    // Check for unsupported features
    if (contentConfig.responseSchema().isPresent()) {
      // Response schema might not be supported by all Spring AI models
      // This should be logged as a warning
      return false;
    }

    if (contentConfig.responseMimeType().isPresent()) {
      // Response MIME type might not be supported by all Spring AI models
      return false;
    }

    // Check for reasonable ranges
    if (contentConfig.temperature().isPresent()) {
      float temp = contentConfig.temperature().get();
      if (temp < 0.0f || temp > 2.0f) {
        return false; // Temperature out of reasonable range
      }
    }

    if (contentConfig.topP().isPresent()) {
      float topP = contentConfig.topP().get();
      if (topP < 0.0f || topP > 1.0f) {
        return false; // topP out of valid range
      }
    }

    if (contentConfig.topK().isPresent()) {
      float topK = contentConfig.topK().get();
      if (topK < 1 || topK > 64) {
        return false; // topK out of valid range
      }
    }

    return true;
  }
}
