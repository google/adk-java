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
 * A distilled memory item produced from one or more reasoning trajectories.
 *
 * <p>Schema matches the ReasoningBank paper and the extraction prompts in the reference
 * implementation (<a
 * href="https://github.com/google-research/reasoning-bank">google-research/reasoning-bank</a>):
 *
 * <pre>
 *   # Memory Item
 *   ## Title       &lt;concise identifier summarizing the core strategy&gt;
 *   ## Description &lt;one-sentence summary&gt;
 *   ## Content     &lt;distilled reasoning steps, decision rationales, or operational insights&gt;
 * </pre>
 *
 * <p>Unlike Agent Workflow Memory, memory items capture tactical/strategic insights — including
 * preventative lessons learned from <em>failed</em> trajectories — rather than procedural step
 * lists. The origin of the item (a success or failure trace) is tracked via {@link
 * #sourceTraceSuccessful()} so downstream consumers can surface both positive strategies and "do
 * not" guardrails.
 *
 * <p>Reference: Ouyang et al. "ReasoningBank: Scaling Agent Self-Evolving with Reasoning Memory"
 * (ICLR 2026, <a href="https://arxiv.org/abs/2509.25140">arXiv:2509.25140</a>).
 */
@AutoValue
@JsonDeserialize(builder = ReasoningMemoryItem.Builder.class)
public abstract class ReasoningMemoryItem {

  /** Returns the unique identifier for this memory item. */
  @JsonProperty("id")
  public abstract String id();

  /** Returns a concise identifier summarizing the core strategy. */
  @JsonProperty("title")
  public abstract String title();

  /** Returns a one-sentence summary of the memory item. */
  @JsonProperty("description")
  public abstract String description();

  /**
   * Returns the distilled reasoning content: decision rationales, operational insights, or
   * preventative lessons (1-5 sentences in the reference prompts).
   */
  @JsonProperty("content")
  public abstract String content();

  /** Returns optional tags for categorization and retrieval. */
  @JsonProperty("tags")
  public abstract ImmutableList<String> tags();

  /**
   * Returns whether this memory item was distilled from a successful trajectory.
   *
   * <p>Items distilled from failed trajectories typically encode preventative lessons ("always
   * verify X before Y") and are retained as counterfactual guardrails rather than positive
   * strategies.
   */
  @JsonProperty("sourceTraceSuccessful")
  public abstract boolean sourceTraceSuccessful();

  /** Returns the timestamp when this item was created, as an ISO 8601 string. */
  @Nullable
  @JsonProperty("createdAt")
  public abstract String createdAt();

  /**
   * Returns the id of the {@link ReasoningTrace} this item was distilled from, or {@code null} if
   * the item was authored manually.
   *
   * <p>Provenance makes a poisoned item locatable and evictable; it is the audit primitive that
   * every recovery action depends on.
   */
  @Nullable
  @JsonProperty("sourceTraceId")
  public abstract String sourceTraceId();

  /**
   * Returns the judge verdict that produced this item (e.g. {@code "SUCCESS"}, {@code "FAILURE"},
   * {@code "malformed"}), or {@code null} if the item was not distilled by a judge.
   */
  @Nullable
  @JsonProperty("judgeVerdict")
  public abstract String judgeVerdict();

  /** Returns the judge's confidence in {@code [0, 1]}, or {@code null} if unknown. */
  @Nullable
  @JsonProperty("judgeConfidence")
  public abstract Double judgeConfidence();

  /**
   * Returns the retrieval trust weight in {@code [0, 1]} (default {@code 1.0}).
   *
   * <p>Failure-derived items may be demoted so they surface only when no trusted success item
   * matches, capping the influence of a bogus guardrail.
   */
  @JsonProperty("trust")
  public abstract double trust();

  /** Returns a new builder for creating a {@link ReasoningMemoryItem}. */
  public static Builder builder() {
    return new AutoValue_ReasoningMemoryItem.Builder()
        .tags(ImmutableList.of())
        .sourceTraceSuccessful(true)
        .trust(1.0);
  }

  /** Creates a new builder with a copy of this item's values. */
  public abstract Builder toBuilder();

  /** Builder for {@link ReasoningMemoryItem}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return new AutoValue_ReasoningMemoryItem.Builder()
          .tags(ImmutableList.of())
          .sourceTraceSuccessful(true)
          .trust(1.0);
    }

    /** Sets the unique identifier. */
    @JsonProperty("id")
    public abstract Builder id(String id);

    /** Sets the title. */
    @JsonProperty("title")
    public abstract Builder title(String title);

    /** Sets the one-sentence description. */
    @JsonProperty("description")
    public abstract Builder description(String description);

    /** Sets the distilled reasoning content. */
    @JsonProperty("content")
    public abstract Builder content(String content);

    /** Sets the tags. */
    @JsonProperty("tags")
    public abstract Builder tags(ImmutableList<String> tags);

    /** Sets whether this item was distilled from a successful trajectory. */
    @JsonProperty("sourceTraceSuccessful")
    public abstract Builder sourceTraceSuccessful(boolean sourceTraceSuccessful);

    /** Sets the creation timestamp as an ISO 8601 string. */
    @JsonProperty("createdAt")
    public abstract Builder createdAt(@Nullable String createdAt);

    /** Convenience: sets the creation timestamp from an {@link Instant}. */
    public Builder createdAt(Instant instant) {
      return createdAt(instant.toString());
    }

    /** Sets the id of the source trace this item was distilled from. */
    @JsonProperty("sourceTraceId")
    public abstract Builder sourceTraceId(@Nullable String sourceTraceId);

    /** Sets the judge verdict that produced this item. */
    @JsonProperty("judgeVerdict")
    public abstract Builder judgeVerdict(@Nullable String judgeVerdict);

    /** Sets the judge's confidence in {@code [0, 1]}. */
    @JsonProperty("judgeConfidence")
    public abstract Builder judgeConfidence(@Nullable Double judgeConfidence);

    /** Sets the retrieval trust weight in {@code [0, 1]}. */
    @JsonProperty("trust")
    public abstract Builder trust(double trust);

    /** Builds the immutable {@link ReasoningMemoryItem}. */
    public abstract ReasoningMemoryItem build();
  }
}
