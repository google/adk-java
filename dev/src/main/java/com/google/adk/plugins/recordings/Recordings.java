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
import com.google.common.collect.ImmutableList;
import java.util.List;

/** All recordings in chronological order. */
@AutoValue
@JsonDeserialize(builder = AutoValue_Recordings.Builder.class)
public abstract class Recordings {

  /** Chronological list of all recordings. */
  public abstract ImmutableList<Recording> recordings();

  public static Builder builder() {
    return new AutoValue_Recordings.Builder();
  }

  public static Recordings of(List<Recording> recordings) {
    return builder().recordings(recordings).build();
  }

  /** Builder for Recordings. */
  @AutoValue.Builder
  @JsonPOJOBuilder(withPrefix = "")
  public abstract static class Builder {
    public abstract Builder recordings(List<Recording> recordings);

    public abstract Recordings build();
  }
}
