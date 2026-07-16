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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response from the Sarvam AI text-to-speech REST endpoint.
 *
 * @author Sandeep Belgavi
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TtsResponse {

  @JsonProperty("request_id")
  private String requestId;

  @JsonProperty("audios")
  private List<String> audios;

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  /** Returns base64-encoded audio strings. Each element corresponds to an input text segment. */
  public List<String> getAudios() {
    return audios;
  }

  public void setAudios(List<String> audios) {
    this.audios = audios;
  }
}
