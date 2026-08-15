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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One {@link LlmRequest} as it appears in the trace — port of adk-python's {@code
 * before_model_callback}.
 *
 * <p>Only tool <em>names</em> are recorded, as upstream does: the declarations are large, repeat on
 * every turn, and say nothing about the turn being traced.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record LlmRequestTrace(
    Optional<String> model,
    @JsonProperty("content_count") int contentCount,
    ImmutableList<ContentTrace> contents,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) ImmutableList<String> tools,
    Optional<GenerateContentConfigTrace> config)
    implements TracePayload {

  static LlmRequestTrace from(LlmRequest request, boolean includeSystemInstruction) {
    return new LlmRequestTrace(
        request.model(),
        request.contents().size(),
        request.contents().stream().map(ContentTrace::from).collect(toImmutableList()),
        toolNames(request),
        request.config().flatMap(config -> configTrace(config, includeSystemInstruction)));
  }

  private static ImmutableList<String> toolNames(LlmRequest request) {
    return ImmutableList.copyOf(request.tools().keySet());
  }

  private static Optional<GenerateContentConfigTrace> configTrace(
      GenerateContentConfig config, boolean includeSystemInstruction) {
    return GenerateContentConfigTrace.from(config, includeSystemInstruction);
  }

  /**
   * The generation settings, as they appear in the trace.
   *
   * <p>{@link GenerateContentConfig#systemInstruction()} is {@code Optional<Content>}, never a
   * string, so the off-switch emits {@code has_system_instruction} and never upstream's {@code
   * system_instruction_length}.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record GenerateContentConfigTrace(
      @JsonProperty("system_instruction") Optional<ContentTrace> systemInstruction,
      @JsonProperty("has_system_instruction") Optional<Boolean> hasSystemInstruction,
      Optional<Float> temperature,
      @JsonProperty("top_p") Optional<Float> topP,
      @JsonProperty("top_k") Optional<Float> topK,
      @JsonProperty("max_output_tokens") Optional<Integer> maxOutputTokens,
      @JsonProperty("response_mime_type") Optional<String> responseMimeType,
      @JsonProperty("has_response_schema") Optional<Boolean> hasResponseSchema) {

    /** Empty when nothing was configured, matching upstream's {@code if config_data:}. */
    static Optional<GenerateContentConfigTrace> from(
        GenerateContentConfig config, boolean includeSystemInstruction) {
      GenerateContentConfigTrace trace =
          new GenerateContentConfigTrace(
              includedInstruction(config, includeSystemInstruction),
              summarizedInstruction(config, includeSystemInstruction),
              config.temperature(),
              config.topP(),
              config.topK(),
              config.maxOutputTokens(),
              config.responseMimeType(),
              config.responseSchema().map(unused -> Boolean.TRUE));
      return trace.isEmpty() ? Optional.empty() : Optional.of(trace);
    }

    private static Optional<ContentTrace> includedInstruction(
        GenerateContentConfig config, boolean includeSystemInstruction) {
      return includeSystemInstruction
          ? config.systemInstruction().map(ContentTrace::from)
          : Optional.empty();
    }

    private static Optional<Boolean> summarizedInstruction(
        GenerateContentConfig config, boolean includeSystemInstruction) {
      return includeSystemInstruction
          ? Optional.empty()
          : config.systemInstruction().map(unused -> Boolean.TRUE);
    }

    @JsonIgnore
    boolean isEmpty() {
      return Stream.of(
              systemInstruction,
              hasSystemInstruction,
              temperature,
              topP,
              topK,
              maxOutputTokens,
              responseMimeType,
              hasResponseSchema)
          .allMatch(Optional::isEmpty);
    }
  }
}
