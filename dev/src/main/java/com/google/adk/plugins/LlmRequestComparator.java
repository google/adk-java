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
package com.google.adk.plugins;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import java.util.Map;
import java.util.Optional;

/**
 * Compares LlmRequest objects for equality, excluding fields that can vary between runs.
 *
 * <p>Excluded fields:
 *
 * <ul>
 *   <li>liveConnectConfig - varies per run
 *   <li>config.httpOptions - varies per run
 *   <li>config.labels - varies per run
 * </ul>
 */
class LlmRequestComparator {
  private final ObjectMapper objectMapper;

  LlmRequestComparator() {
    this.objectMapper = new ObjectMapper();
    // Register Jdk8Module to handle Optional types
    objectMapper.registerModule(new Jdk8Module());
    // Configure mix-ins to exclude runtime-variable fields
    objectMapper.addMixIn(GenerateContentConfig.class, GenerateContentConfigMixin.class);
    objectMapper.addMixIn(LlmRequest.class, LlmRequestMixin.class);
  }

  /**
   * Compares two LlmRequest objects for equality, excluding runtime-variable fields.
   *
   * @param recorded the recorded request
   * @param current the current request
   * @return true if the requests match (excluding runtime-variable fields)
   */
  boolean equals(LlmRequest recorded, LlmRequest current) {
    Map<String, Object> recordedMap = toComparisonMap(recorded);
    Map<String, Object> currentMap = toComparisonMap(current);
    return recordedMap.equals(currentMap);
  }

  /**
   * Converts an LlmRequest to a Map for comparison purposes.
   *
   * @param request the request to convert
   * @return a Map representation with runtime-variable fields excluded
   */
  @SuppressWarnings("unchecked")
  Map<String, Object> toComparisonMap(LlmRequest request) {
    return objectMapper.convertValue(request, Map.class);
  }

  /** Mix-in to exclude GenerateContentConfig fields that vary between runs. */
  abstract static class GenerateContentConfigMixin {
    @JsonIgnore
    abstract Optional<HttpOptions> httpOptions();

    @JsonIgnore
    abstract Optional<Map<String, String>> labels();
  }

  /** Mix-in to exclude LlmRequest fields that vary between runs. */
  abstract static class LlmRequestMixin {
    @JsonIgnore
    abstract LiveConnectConfig liveConnectConfig();
  }
}
