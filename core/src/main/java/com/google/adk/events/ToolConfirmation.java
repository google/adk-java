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
package com.google.adk.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import javax.annotation.Nullable;

/** Represents a tool confirmation configuration. */
@AutoValue
@JsonDeserialize(builder = ToolConfirmation.Builder.class)
public abstract class ToolConfirmation extends JsonBaseModel {

  @Nullable
  @JsonProperty("hint")
  public abstract String hint();

  @JsonProperty("confirmed")
  public abstract boolean confirmed();

  @Nullable
  @JsonProperty("payload")
  public abstract Object payload();

  public static Builder builder() {
    return new AutoValue_ToolConfirmation.Builder().hint("").confirmed(false);
  }

  public abstract Builder toBuilder();

  /** Builder for {@link ToolConfirmation}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    @JsonProperty("hint")
    public abstract Builder hint(@Nullable String hint);

    @CanIgnoreReturnValue
    @JsonProperty("confirmed")
    public abstract Builder confirmed(boolean confirmed);

    @CanIgnoreReturnValue
    @JsonProperty("payload")
    public abstract Builder payload(@Nullable Object payload);

    /** For internal usage. Please use `ToolConfirmation.builder()` for instantiation. */
    @JsonCreator
    static Builder create() {
      return new AutoValue_ToolConfirmation.Builder();
    }

    public abstract ToolConfirmation build();
  }
}
