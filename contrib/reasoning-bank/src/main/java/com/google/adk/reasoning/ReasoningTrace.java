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

package com.google.adk.reasoning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * Represents a raw reasoning trace captured from a task execution.
 *
 * <p>A reasoning trace captures the input, output, and intermediate reasoning steps from a
 * successful task execution. Traces can be distilled into reusable {@link ReasoningStrategy}
 * objects.
 *
 * <p>Based on the ReasoningBank paper (arXiv:2509.25140).
 */
@AutoValue
@JsonDeserialize(builder = ReasoningTrace.Builder.class)
public abstract class ReasoningTrace {

  /** Returns the unique identifier for this trace. */
  @JsonProperty("id")
  public abstract String id();

  /** Returns the original task or prompt that was executed. */
  @JsonProperty("task")
  public abstract String task();

  /** Returns the final output or response from the task execution. */
  @JsonProperty("output")
  public abstract String output();

  /**
   * Returns the intermediate reasoning steps captured during execution.
   *
   * <p>These are the raw chain-of-thought steps before distillation.
   */
  @JsonProperty("reasoningSteps")
  public abstract ImmutableList<String> reasoningSteps();

  /** Returns whether the task execution was successful. */
  @JsonProperty("successful")
  public abstract boolean successful();

  /** Returns the timestamp when this trace was captured. */
  @Nullable
  @JsonProperty("capturedAt")
  public abstract String capturedAt();

  /** Returns optional metadata about the execution context. */
  @Nullable
  @JsonProperty("metadata")
  public abstract String metadata();

  /** Returns a new builder for creating a {@link ReasoningTrace}. */
  public static Builder builder() {
    return new AutoValue_ReasoningTrace.Builder()
        .reasoningSteps(ImmutableList.of())
        .successful(true);
  }

  /**
   * Creates a new builder with a copy of this trace's values.
   *
   * @return a new {@link Builder} instance.
   */
  public abstract Builder toBuilder();

  /** Builder for {@link ReasoningTrace}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return new AutoValue_ReasoningTrace.Builder()
          .reasoningSteps(ImmutableList.of())
          .successful(true);
    }

    /** Sets the unique identifier for this trace. */
    @JsonProperty("id")
    public abstract Builder id(String id);

    /** Sets the original task or prompt. */
    @JsonProperty("task")
    public abstract Builder task(String task);

    /** Sets the final output from the task execution. */
    @JsonProperty("output")
    public abstract Builder output(String output);

    /** Sets the intermediate reasoning steps. */
    @JsonProperty("reasoningSteps")
    public abstract Builder reasoningSteps(ImmutableList<String> reasoningSteps);

    /** Sets whether the task execution was successful. */
    @JsonProperty("successful")
    public abstract Builder successful(boolean successful);

    /** Sets the capture timestamp as an ISO 8601 string. */
    @JsonProperty("capturedAt")
    public abstract Builder capturedAt(@Nullable String capturedAt);

    /**
     * Convenience method to set the capture timestamp from an {@link Instant}.
     *
     * @param instant The timestamp as an Instant object.
     */
    public Builder capturedAt(Instant instant) {
      return capturedAt(instant.toString());
    }

    /** Sets optional metadata about the execution context. */
    @JsonProperty("metadata")
    public abstract Builder metadata(@Nullable String metadata);

    /** Builds the immutable {@link ReasoningTrace} object. */
    public abstract ReasoningTrace build();
  }
}
