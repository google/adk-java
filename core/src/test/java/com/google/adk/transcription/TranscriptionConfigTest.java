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

package com.google.adk.transcription;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TranscriptionConfig.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
@DisplayName("TranscriptionConfig Tests")
class TranscriptionConfigTest {

  @Test
  @DisplayName("Builder creates valid config with required fields")
  void testBuilderWithRequiredFields() {
    TranscriptionConfig config =
        TranscriptionConfig.builder().endpoint("https://example.com/transcribe").build();

    assertThat(config.getEndpoint()).isEqualTo("https://example.com/transcribe");
    assertThat(config.getLanguage()).isEqualTo("auto");
    assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.getMaxRetries()).isEqualTo(3);
    assertThat(config.getAudioFormat()).isEqualTo(AudioFormat.PCM_16KHZ_MONO);
    assertThat(config.isEnablePartialResults()).isTrue();
  }

  @Test
  @DisplayName("Builder creates config with all fields")
  void testBuilderWithAllFields() {
    TranscriptionConfig config =
        TranscriptionConfig.builder()
            .endpoint("https://example.com/transcribe")
            .apiKey("test-api-key")
            .language("en")
            .timeout(Duration.ofSeconds(60))
            .maxRetries(5)
            .customHeaders(Map.of("X-Custom-Header", "value"))
            .audioFormat(AudioFormat.PCM_48KHZ_MONO)
            .enablePartialResults(false)
            .chunkSizeMs(1000)
            .build();

    assertThat(config.getEndpoint()).isEqualTo("https://example.com/transcribe");
    assertThat(config.getApiKey().isPresent()).isTrue();
    assertThat(config.getApiKey().get()).isEqualTo("test-api-key");
    assertThat(config.getLanguage()).isEqualTo("en");
    assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(config.getMaxRetries()).isEqualTo(5);
    assertThat(config.getCustomHeaders().size()).isEqualTo(1);
    assertThat(config.getAudioFormat()).isEqualTo(AudioFormat.PCM_48KHZ_MONO);
    assertThat(config.isEnablePartialResults()).isFalse();
    assertThat(config.getChunkSizeMs()).isEqualTo(1000);
  }

  @Test
  @DisplayName("Builder throws exception when endpoint is missing")
  void testBuilderMissingEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> TranscriptionConfig.builder().build());
  }

  @Test
  @DisplayName("Builder throws exception when endpoint is empty")
  void testBuilderEmptyEndpoint() {
    assertThrows(
        IllegalArgumentException.class, () -> TranscriptionConfig.builder().endpoint("").build());
  }

  @Test
  @DisplayName("Builder throws exception for negative max retries")
  void testBuilderNegativeMaxRetries() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TranscriptionConfig.builder().endpoint("https://example.com").maxRetries(-1).build());
  }

  @Test
  @DisplayName("Builder throws exception for invalid chunk size")
  void testBuilderInvalidChunkSize() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TranscriptionConfig.builder().endpoint("https://example.com").chunkSizeMs(0).build());
  }

  @Test
  @DisplayName("Config is immutable")
  void testConfigImmutability() {
    TranscriptionConfig config =
        TranscriptionConfig.builder().endpoint("https://example.com").build();

    Map<String, String> headers = config.getCustomHeaders();
    assertThrows(UnsupportedOperationException.class, () -> headers.put("key", "value"));
  }
}
