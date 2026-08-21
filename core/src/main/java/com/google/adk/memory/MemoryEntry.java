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

package com.google.adk.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.auto.value.AutoValue;
import com.google.genai.types.Content;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Represents one memory entry. */
@AutoValue
@JsonDeserialize(builder = MemoryEntry.Builder.class)
public abstract class MemoryEntry {

  /** Returns the main content of the memory. */
  @JsonProperty("content")
  public abstract Content content();

  /** Returns the author of the memory, or null if not set. */
  @Nullable
  @JsonProperty("author")
  public abstract String author();

  /**
   * Returns the timestamp when the original content of this memory happened, or null if not set.
   *
   * <p>This string will be forwarded to LLM. Preferred format is ISO 8601 format
   */
  @Nullable
  public abstract String timestamp();

  /** Returns a new builder for creating a {@link MemoryEntry}. */
  public static Builder builder() {
    return new AutoValue_MemoryEntry.Builder();
  }

  /**
   * Creates a new builder with a copy of this entry's values.
   *
   * @return a new {@link Builder} instance.
   */
  public abstract Builder toBuilder();

  /** Builder for {@link MemoryEntry}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return new AutoValue_MemoryEntry.Builder();
    }

    /**
     * Sets the main content of the memory.
     *
     * <p>This is a required field.
     */
    @JsonProperty("content")
    public abstract Builder content(Content content);

    /** Sets the author of the memory. */
    @JsonProperty("author")
    public abstract Builder author(@Nullable String author);

    /** Sets the timestamp when the original content of this memory happened. */
    @JsonProperty("timestamp")
    public abstract Builder timestamp(@Nullable String timestamp);

    /**
     * A convenience method to set the timestamp from an {@link Instant} object, formatted as an ISO
     * 8601 string.
     *
     * @param instant The timestamp as an Instant object.
     */
    public Builder timestamp(Instant instant) {
      return timestamp(instant.toString());
    }

    /** Builds the immutable {@link MemoryEntry} object. */
    public abstract MemoryEntry build();
  }
}
