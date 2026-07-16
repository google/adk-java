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

import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for transcription services. Uses Builder Pattern for flexible configuration.
 *
 * <p>All fields are immutable once built. Use the builder to create instances.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public final class TranscriptionConfig {
  private final String endpoint;
  private final Optional<String> apiKey;
  private final String language;
  private final Duration timeout;
  private final int maxRetries;
  private final ImmutableMap<String, String> customHeaders;
  private final AudioFormat audioFormat;
  private final boolean enablePartialResults;
  private final int chunkSizeMs;

  private TranscriptionConfig(Builder builder) {
    this.endpoint = builder.endpoint;
    this.apiKey = Optional.ofNullable(builder.apiKey);
    this.language = builder.language;
    this.timeout = builder.timeout;
    this.maxRetries = builder.maxRetries;
    this.customHeaders = ImmutableMap.copyOf(builder.customHeaders);
    this.audioFormat = builder.audioFormat;
    this.enablePartialResults = builder.enablePartialResults;
    this.chunkSizeMs = builder.chunkSizeMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Optional<String> getApiKey() {
    return apiKey;
  }

  public String getLanguage() {
    return language;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public ImmutableMap<String, String> getCustomHeaders() {
    return customHeaders;
  }

  public AudioFormat getAudioFormat() {
    return audioFormat;
  }

  public boolean isEnablePartialResults() {
    return enablePartialResults;
  }

  public int getChunkSizeMs() {
    return chunkSizeMs;
  }

  /** Builder for TranscriptionConfig. */
  public static class Builder {
    private String endpoint;
    private String apiKey;
    private String language = "auto";
    private Duration timeout = Duration.ofSeconds(30);
    private int maxRetries = 3;
    private Map<String, String> customHeaders = Map.of();
    private AudioFormat audioFormat = AudioFormat.PCM_16KHZ_MONO;
    private boolean enablePartialResults = true;
    private int chunkSizeMs = 500; // 500ms chunks for real-time

    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder language(String language) {
      this.language = language;
      return this;
    }

    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("Max retries must be >= 0");
      }
      this.maxRetries = maxRetries;
      return this;
    }

    public Builder customHeaders(Map<String, String> headers) {
      this.customHeaders = Map.copyOf(headers);
      return this;
    }

    public Builder audioFormat(AudioFormat format) {
      this.audioFormat = format;
      return this;
    }

    public Builder enablePartialResults(boolean enable) {
      this.enablePartialResults = enable;
      return this;
    }

    public Builder chunkSizeMs(int chunkSizeMs) {
      if (chunkSizeMs <= 0) {
        throw new IllegalArgumentException("Chunk size must be > 0");
      }
      this.chunkSizeMs = chunkSizeMs;
      return this;
    }

    public TranscriptionConfig build() {
      if (endpoint == null || endpoint.isEmpty()) {
        throw new IllegalArgumentException("Endpoint is required");
      }
      return new TranscriptionConfig(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "TranscriptionConfig{endpoint='%s', language='%s', timeout=%s, format=%s}",
        endpoint, language, timeout, audioFormat);
  }
}
