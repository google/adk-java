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

import java.util.Optional;

/**
 * Event representing transcription update (partial or final). Used for streaming transcription
 * results.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public final class TranscriptionEvent {
  private final String text;
  private final boolean finished;
  private final long timestamp;
  private final Optional<String> language;

  private TranscriptionEvent(Builder builder) {
    this.text = builder.text;
    this.finished = builder.finished;
    this.timestamp = builder.timestamp;
    this.language = Optional.ofNullable(builder.language);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getText() {
    return text;
  }

  public boolean isFinished() {
    return finished;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public Optional<String> getLanguage() {
    return language;
  }

  public static class Builder {
    private String text;
    private boolean finished = false;
    private long timestamp = System.currentTimeMillis();
    private String language;

    public Builder text(String text) {
      this.text = text;
      return this;
    }

    public Builder finished(boolean finished) {
      this.finished = finished;
      return this;
    }

    public Builder timestamp(long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder language(String language) {
      this.language = language;
      return this;
    }

    public TranscriptionEvent build() {
      if (text == null) {
        throw new IllegalArgumentException("Text is required");
      }
      return new TranscriptionEvent(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "TranscriptionEvent{text='%s', finished=%s, timestamp=%d}", text, finished, timestamp);
  }
}
