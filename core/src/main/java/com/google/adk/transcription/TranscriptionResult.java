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

import java.time.Duration;
import java.util.Optional;

/**
 * Result of transcription operation.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public final class TranscriptionResult {
  private final String text;
  private final Optional<String> language;
  private final Optional<Double> confidence;
  private final Optional<Duration> duration;
  private final long timestamp;

  private TranscriptionResult(Builder builder) {
    this.text = builder.text;
    this.language = Optional.ofNullable(builder.language);
    this.confidence = Optional.ofNullable(builder.confidence);
    this.duration = Optional.ofNullable(builder.duration);
    this.timestamp = builder.timestamp;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getText() {
    return text;
  }

  public Optional<String> getLanguage() {
    return language;
  }

  public Optional<Double> getConfidence() {
    return confidence;
  }

  public Optional<Duration> getDuration() {
    return duration;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public static class Builder {
    private String text;
    private String language;
    private Double confidence;
    private Duration duration;
    private long timestamp = System.currentTimeMillis();

    public Builder text(String text) {
      this.text = text;
      return this;
    }

    public Builder language(String language) {
      this.language = language;
      return this;
    }

    public Builder confidence(Double confidence) {
      this.confidence = confidence;
      return this;
    }

    public Builder duration(Duration duration) {
      this.duration = duration;
      return this;
    }

    public Builder timestamp(long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public TranscriptionResult build() {
      if (text == null) {
        throw new IllegalArgumentException("Text is required");
      }
      return new TranscriptionResult(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "TranscriptionResult{text='%s', language=%s, confidence=%s, timestamp=%d}",
        text,
        language.orElse("unknown"),
        confidence.map(c -> String.format("%.2f", c)).orElse("unknown"),
        timestamp);
  }
}
