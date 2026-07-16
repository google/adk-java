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

package com.google.adk.models.sarvamai.tts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the Sarvam AI text-to-speech REST endpoint.
 *
 * @author Sandeep Belgavi
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class TtsRequest {

  @JsonProperty("text")
  private String text;

  @JsonProperty("target_language_code")
  private String targetLanguageCode;

  @JsonProperty("model")
  private String model;

  @JsonProperty("speaker")
  private String speaker;

  @JsonProperty("pace")
  private Double pace;

  @JsonProperty("speech_sample_rate")
  private Integer speechSampleRate;

  public TtsRequest() {}

  public TtsRequest(
      String text,
      String targetLanguageCode,
      String model,
      String speaker,
      Double pace,
      Integer speechSampleRate) {
    this.text = text;
    this.targetLanguageCode = targetLanguageCode;
    this.model = model;
    this.speaker = speaker;
    this.pace = pace;
    this.speechSampleRate = speechSampleRate;
  }

  public String getText() {
    return text;
  }

  public String getTargetLanguageCode() {
    return targetLanguageCode;
  }

  public String getModel() {
    return model;
  }

  public String getSpeaker() {
    return speaker;
  }

  public Double getPace() {
    return pace;
  }

  public Integer getSpeechSampleRate() {
    return speechSampleRate;
  }
}
