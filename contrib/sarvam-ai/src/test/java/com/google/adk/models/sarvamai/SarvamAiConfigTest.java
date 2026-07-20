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

package com.google.adk.models.sarvamai;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * @author Sandeep Belgavi
 */
class SarvamAiConfigTest {

  @Test
  void builder_withApiKey_succeeds() {
    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("test-key").build();
    assertThat(config.apiKey()).isEqualTo("test-key");
  }

  @Test
  void builder_withoutApiKey_throwsIfEnvNotSet() {
    if (System.getenv("SARVAM_API_KEY") != null) {
      return;
    }
    assertThrows(IllegalArgumentException.class, () -> SarvamAiConfig.builder().build());
  }

  @Test
  void builder_setsDefaultEndpoints() {
    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").build();
    assertThat(config.chatEndpoint()).isEqualTo(SarvamAiConfig.DEFAULT_CHAT_ENDPOINT);
    assertThat(config.sttEndpoint()).isEqualTo(SarvamAiConfig.DEFAULT_STT_ENDPOINT);
    assertThat(config.ttsEndpoint()).isEqualTo(SarvamAiConfig.DEFAULT_TTS_ENDPOINT);
    assertThat(config.visionEndpoint()).isEqualTo(SarvamAiConfig.DEFAULT_VISION_ENDPOINT);
  }

  @Test
  void builder_customEndpoints() {
    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("key")
            .chatEndpoint("http://custom/chat")
            .sttEndpoint("http://custom/stt")
            .ttsEndpoint("http://custom/tts")
            .build();

    assertThat(config.chatEndpoint()).isEqualTo("http://custom/chat");
    assertThat(config.sttEndpoint()).isEqualTo("http://custom/stt");
    assertThat(config.ttsEndpoint()).isEqualTo("http://custom/tts");
  }

  @Test
  void builder_temperatureValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SarvamAiConfig.builder().apiKey("key").temperature(3.0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> SarvamAiConfig.builder().apiKey("key").temperature(-1.0).build());

    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").temperature(0.7).build();
    assertThat(config.temperature().getAsDouble()).isWithin(0.001).of(0.7);
  }

  @Test
  void builder_reasoningEffortValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SarvamAiConfig.builder().apiKey("key").reasoningEffort("invalid").build());

    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").reasoningEffort("high").build();
    assertThat(config.reasoningEffort()).hasValue("high");
  }

  @Test
  void builder_sttModeValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SarvamAiConfig.builder().apiKey("key").sttMode("invalid").build());

    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").sttMode("translate").build();
    assertThat(config.sttMode()).hasValue("translate");
  }

  @Test
  void builder_ttsPaceValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SarvamAiConfig.builder().apiKey("key").ttsPace(0.1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> SarvamAiConfig.builder().apiKey("key").ttsPace(3.0).build());

    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").ttsPace(1.5).build();
    assertThat(config.ttsPace().getAsDouble()).isWithin(0.001).of(1.5);
  }

  @Test
  void builder_maxRetriesDefault() {
    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").build();
    assertThat(config.maxRetries()).isEqualTo(SarvamAiConfig.DEFAULT_MAX_RETRIES);
  }

  @Test
  void builder_wikiGrounding() {
    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").wikiGrounding(true).build();
    assertThat(config.wikiGrounding()).hasValue(true);
  }

  @Test
  void builder_chatParametersOptionalByDefault() {
    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").build();
    assertThat(config.temperature().isEmpty()).isTrue();
    assertThat(config.topP().isEmpty()).isTrue();
    assertThat(config.maxTokens().isEmpty()).isTrue();
    assertThat(config.reasoningEffort().isEmpty()).isTrue();
    assertThat(config.wikiGrounding().isEmpty()).isTrue();
  }
}
