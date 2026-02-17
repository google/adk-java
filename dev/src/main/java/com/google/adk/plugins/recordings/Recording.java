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
import com.google.auto.value.AutoValue;
import java.util.Optional;
import javax.annotation.Nullable;

/** Single interaction recording, ordered by request timestamp. */
@AutoValue
@JsonDeserialize(builder = AutoValue_Recording.Builder.class)
public abstract class Recording {

  /** Index of the user message this recording belongs to (0-based). */
  public abstract int userMessageIndex();

  /** Name of the agent. */
  public abstract String agentName();

  /** LLM request-response pair. */
  public abstract Optional<LlmRecording> llmRecording();

  /** Tool call-response pair. */
  public abstract Optional<ToolRecording> toolRecording();

  public static Builder builder() {
    return new AutoValue_Recording.Builder();
  }

  /** Builder for Recording. */
  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder userMessageIndex(int userMessageIndex);

    public abstract Builder agentName(String agentName);

    public abstract Builder llmRecording(@Nullable LlmRecording llmRecording);

    public abstract Builder toolRecording(@Nullable ToolRecording toolRecording);

    public abstract Recording build();
  }
}
