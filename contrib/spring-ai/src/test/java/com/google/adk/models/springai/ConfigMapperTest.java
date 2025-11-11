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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.google.genai.types.GenerateContentConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

class ConfigMapperTest {

  private ConfigMapper configMapper;

  @BeforeEach
  void setUp() {
    configMapper = new ConfigMapper();
  }

  @Test
  void testToSpringAiChatOptionsWithEmptyConfig() {
    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.empty());

    assertThat(chatOptions).isNull();
  }

  @Test
  void testToSpringAiChatOptionsWithBasicConfig() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.8f).maxOutputTokens(1000).topP(0.9f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isCloseTo(0.8, within(0.001));
    assertThat(chatOptions.getMaxTokens()).isEqualTo(1000);
    assertThat(chatOptions.getTopP()).isCloseTo(0.9, within(0.001));
  }

  @Test
  void testToSpringAiChatOptionsWithStopSequences() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().stopSequences(List.of("STOP", "END", "FINISH")).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getStopSequences()).containsExactly("STOP", "END", "FINISH");
  }

  @Test
  void testToSpringAiChatOptionsWithEmptyStopSequences() {
    GenerateContentConfig config = GenerateContentConfig.builder().stopSequences(List.of()).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getStopSequences()).isNull();
  }

  @Test
  void testToSpringAiChatOptionsWithInvalidTopK() {
    GenerateContentConfig config = GenerateContentConfig.builder().topK(100f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(isValid).isFalse();
  }

  @Test
  void testToSpringAiChatOptionsWithPenalties() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().presencePenalty(0.5f).frequencyPenalty(0.3f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    // Penalties are not directly supported by Spring AI ChatOptions
    // The implementation should handle this gracefully
  }

  @Test
  void testToSpringAiChatOptionsWithAllParameters() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .temperature(0.7f)
            .maxOutputTokens(2000)
            .topP(0.95f)
            .topK(50f)
            .stopSequences(List.of("STOP"))
            .presencePenalty(0.1f)
            .frequencyPenalty(0.2f)
            .build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isCloseTo(0.7, within(0.001));
    assertThat(chatOptions.getMaxTokens()).isEqualTo(2000);
    assertThat(chatOptions.getTopP()).isCloseTo(0.95, within(0.001));
    assertThat(chatOptions.getTopK()).isCloseTo(50, within(1));
    assertThat(chatOptions.getStopSequences()).containsExactly("STOP");
  }

  @Test
  void testCreateDefaultChatOptions() {
    ChatOptions defaultOptions = configMapper.createDefaultChatOptions();

    assertThat(defaultOptions).isNotNull();
    assertThat(defaultOptions.getTemperature()).isCloseTo(0.7, within(0.001));
    assertThat(defaultOptions.getMaxTokens()).isEqualTo(1000);
  }

  @Test
  void testIsConfigurationValidWithEmptyConfig() {
    boolean isValid = configMapper.isConfigurationValid(Optional.empty());

    assertThat(isValid).isTrue();
  }

  @Test
  void testIsConfigurationValidWithValidConfig() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.8f).topP(0.9f).maxOutputTokens(1000).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isTrue();
  }

  @Test
  void testIsConfigurationValidWithInvalidTemperature() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(-0.5f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithHighTemperature() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(3.0f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithInvalidTopP() {
    GenerateContentConfig config = GenerateContentConfig.builder().topP(-0.1f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithHighTopP() {
    GenerateContentConfig config = GenerateContentConfig.builder().topP(1.5f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithResponseSchema() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseSchema(com.google.genai.types.Schema.builder().type("OBJECT").build())
            .build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithResponseMimeType() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().responseMimeType("application/json").build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testToSpringAiChatOptionsWithBoundaryValues() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.0f).topP(1.0f).maxOutputTokens(1).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isEqualTo(0.0);
    assertThat(chatOptions.getTopP()).isEqualTo(1.0);
    assertThat(chatOptions.getMaxTokens()).isEqualTo(1);
  }

  @Test
  void testIsConfigurationValidWithBoundaryValues() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.0f).topP(0.0f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isTrue();

    GenerateContentConfig config2 =
        GenerateContentConfig.builder().temperature(2.0f).topP(1.0f).build();

    boolean isValid2 = configMapper.isConfigurationValid(Optional.of(config2));

    assertThat(isValid2).isTrue();
  }

  @Test
  void testToSpringAiChatOptionsWithNullStopSequences() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(0.5f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getStopSequences()).isNull();
  }

  @Test
  void testTypeConversions() {
    // Test Float to Double conversions are handled properly
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.123456f).topP(0.987654f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isCloseTo(0.123456, within(0.000001));
    assertThat(chatOptions.getTopP()).isCloseTo(0.987654, within(0.000001));
  }
}
