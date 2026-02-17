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
package com.google.adk.plugins.recordings;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.auto.value.AutoValue;
import java.util.Optional;
import javax.annotation.Nullable;

/** Paired LLM request and response for replay. */
@AutoValue
@JsonDeserialize(builder = AutoValue_LlmRecording.Builder.class)
public abstract class LlmRecording {

  /** The LLM request. */
  public abstract Optional<LlmRequest> llmRequest();

  /** The LLM response. */
  public abstract Optional<LlmResponse> llmResponse();

  public static Builder builder() {
    return new AutoValue_LlmRecording.Builder();
  }

  /** Builder for LlmRecording. */
  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder llmRequest(@Nullable LlmRequest llmRequest);

    public abstract Builder llmResponse(@Nullable LlmResponse llmResponse);

    public abstract LlmRecording build();
  }
}
