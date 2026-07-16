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

package com.google.adk.transcription.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.JsonBaseModel;
import com.google.adk.transcription.TranscriptionResult;
import java.time.Duration;

/**
 * Response DTO from Whisper API transcription.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class WhisperResponse extends JsonBaseModel {
  @JsonProperty("text")
  private String text;

  @JsonProperty("language")
  private String language;

  @JsonProperty("confidence")
  private Double confidence;

  @JsonProperty("duration")
  private Double duration; // Duration in seconds

  public WhisperResponse() {}

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public Double getConfidence() {
    return confidence;
  }

  public void setConfidence(Double confidence) {
    this.confidence = confidence;
  }

  public Double getDuration() {
    return duration;
  }

  public void setDuration(Double duration) {
    this.duration = duration;
  }

  /**
   * Converts WhisperResponse to TranscriptionResult.
   *
   * @return TranscriptionResult
   */
  public TranscriptionResult toTranscriptionResult() {
    TranscriptionResult.Builder builder = TranscriptionResult.builder().text(text);

    if (language != null) {
      builder.language(language);
    }

    if (confidence != null) {
      builder.confidence(confidence);
    }

    if (duration != null) {
      builder.duration(Duration.ofMillis((long) (duration * 1000)));
    }

    return builder.build();
  }
}
