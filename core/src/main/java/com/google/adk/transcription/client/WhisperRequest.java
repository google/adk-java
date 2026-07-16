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

/**
 * Request DTO for Whisper API transcription.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class WhisperRequest extends JsonBaseModel {
  @JsonProperty("audio")
  private String audio;

  @JsonProperty("language")
  private String language;

  @JsonProperty("format")
  private String format;

  public WhisperRequest() {}

  public WhisperRequest(String audio, String language, String format) {
    this.audio = audio;
    this.language = language;
    this.format = format;
  }

  public String getAudio() {
    return audio;
  }

  public void setAudio(String audio) {
    this.audio = audio;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getFormat() {
    return format;
  }

  public void setFormat(String format) {
    this.format = format;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String audio;
    private String language;
    private String format;

    public Builder audio(String audio) {
      this.audio = audio;
      return this;
    }

    public Builder language(String language) {
      this.language = language;
      return this;
    }

    public Builder format(String format) {
      this.format = format;
      return this;
    }

    public WhisperRequest build() {
      return new WhisperRequest(audio, language, format);
    }
  }
}
