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
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import java.util.Optional;

/**
 * Token counts, as they appear in the trace.
 *
 * <p>adk-python records <em>different subsets</em> in the two places: three counts for an event in
 * {@code on_event_callback}, and four for a response in {@code after_model_callback}, adding {@code
 * cached_content_token_count}. One record with two factories keeps that difference exact without
 * duplicating the shape — {@link #fromEvent} leaves the cached count absent, and {@code NON_ABSENT}
 * drops it.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record UsageTrace(
    @JsonProperty("prompt_token_count") Optional<Integer> promptTokenCount,
    @JsonProperty("candidates_token_count") Optional<Integer> candidatesTokenCount,
    @JsonProperty("total_token_count") Optional<Integer> totalTokenCount,
    @JsonProperty("cached_content_token_count") Optional<Integer> cachedContentTokenCount) {

  /** The three counts an event carries. */
  static UsageTrace fromEvent(GenerateContentResponseUsageMetadata usage) {
    return new UsageTrace(
        usage.promptTokenCount(),
        usage.candidatesTokenCount(),
        usage.totalTokenCount(),
        Optional.empty());
  }

  /** The four counts an LLM response carries. */
  static UsageTrace fromResponse(GenerateContentResponseUsageMetadata usage) {
    return new UsageTrace(
        usage.promptTokenCount(),
        usage.candidatesTokenCount(),
        usage.totalTokenCount(),
        usage.cachedContentTokenCount());
  }
}
