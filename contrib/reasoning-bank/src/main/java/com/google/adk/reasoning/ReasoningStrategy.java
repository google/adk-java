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
 * Represents a distilled reasoning strategy that can be reused across tasks.
 *
 * <p>A reasoning strategy captures a generalized approach to solving a class of problems, distilled
 * from one or more successful task executions. Strategies include the problem pattern they apply
 * to, the reasoning steps to follow, and optional metadata for retrieval and organization.
 *
 * <p>Based on the ReasoningBank paper (arXiv:2509.25140).
 */
@AutoValue
@JsonDeserialize(builder = ReasoningStrategy.Builder.class)
public abstract class ReasoningStrategy {

  /** Returns the unique identifier for this strategy. */
  @JsonProperty("id")
  public abstract String id();

  /** Returns the name or title of this strategy. */
  @JsonProperty("name")
  public abstract String name();

  /**
   * Returns the description of the problem pattern this strategy applies to.
   *
   * <p>This is used for matching strategies to new tasks.
   */
  @JsonProperty("problemPattern")
  public abstract String problemPattern();

  /**
   * Returns the ordered list of reasoning steps that comprise this strategy.
   *
   * <p>Each step describes a phase of the reasoning process.
   */
  @JsonProperty("steps")
  public abstract ImmutableList<String> steps();

  /** Returns optional tags for categorization and retrieval. */
  @JsonProperty("tags")
  public abstract ImmutableList<String> tags();

  /** Returns the timestamp when this strategy was created. */
  @Nullable
  @JsonProperty("createdAt")
  public abstract String createdAt();

  /** Returns a new builder for creating a {@link ReasoningStrategy}. */
  public static Builder builder() {
    return new AutoValue_ReasoningStrategy.Builder().tags(ImmutableList.of());
  }

  /**
   * Creates a new builder with a copy of this strategy's values.
   *
   * @return a new {@link Builder} instance.
   */
  public abstract Builder toBuilder();

  /** Builder for {@link ReasoningStrategy}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return new AutoValue_ReasoningStrategy.Builder().tags(ImmutableList.of());
    }

    /** Sets the unique identifier for this strategy. */
    @JsonProperty("id")
    public abstract Builder id(String id);

    /** Sets the name of this strategy. */
    @JsonProperty("name")
    public abstract Builder name(String name);

    /** Sets the problem pattern description. */
    @JsonProperty("problemPattern")
    public abstract Builder problemPattern(String problemPattern);

    /** Sets the ordered list of reasoning steps. */
    @JsonProperty("steps")
    public abstract Builder steps(ImmutableList<String> steps);

    /** Sets the tags for categorization. */
    @JsonProperty("tags")
    public abstract Builder tags(ImmutableList<String> tags);

    /** Sets the creation timestamp as an ISO 8601 string. */
    @JsonProperty("createdAt")
    public abstract Builder createdAt(@Nullable String createdAt);

    /**
     * Convenience method to set the creation timestamp from an {@link Instant}.
     *
     * @param instant The timestamp as an Instant object.
     */
    public Builder createdAt(Instant instant) {
      return createdAt(instant.toString());
    }

    /** Builds the immutable {@link ReasoningStrategy} object. */
    public abstract ReasoningStrategy build();
  }
}
