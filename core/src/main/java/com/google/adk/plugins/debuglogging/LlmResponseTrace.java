/*
 * Copyright 2026 Google LLC
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

package com.google.adk.plugins.debuglogging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.models.LlmResponse;
import java.util.Optional;

/**
 * One {@link LlmResponse} as it appears in the trace — port of adk-python's {@code
 * after_model_callback}.
 *
 * <p>Grounding metadata is reduced to a boolean, as upstream does. Token counts use {@link
 * UsageTrace#fromResponse}, which carries the cached-content count an event's copy omits.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record LlmResponseTrace(
    Optional<ContentTrace> content,
    Optional<Boolean> partial,
    @JsonProperty("turn_complete") Optional<Boolean> turnComplete,
    @JsonProperty("error_code") Optional<String> errorCode,
    @JsonProperty("error_message") Optional<String> errorMessage,
    @JsonProperty("usage_metadata") Optional<UsageTrace> usageMetadata,
    @JsonProperty("has_grounding_metadata") Optional<Boolean> hasGroundingMetadata,
    @JsonProperty("finish_reason") Optional<String> finishReason,
    @JsonProperty("model_version") Optional<String> modelVersion)
    implements TracePayload {

  static LlmResponseTrace from(LlmResponse response) {
    return new LlmResponseTrace(
        response.content().map(ContentTrace::from),
        response.partial(),
        response.turnComplete(),
        response.errorCode().map(String::valueOf),
        response.errorMessage(),
        response.usageMetadata().map(UsageTrace::fromResponse),
        response.groundingMetadata().map(unused -> Boolean.TRUE),
        response.finishReason().map(String::valueOf),
        response.modelVersion());
  }
}
