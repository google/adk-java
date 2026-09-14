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

package com.google.adk.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Metadata for the context cache associated with LLM responses.
 *
 * <p>Holds the identification, usage tracking and lifecycle information for one cache instance. A
 * record is in one of two states, and never in between:
 *
 * <ol>
 *   <li>fingerprint-only, where {@link #cacheName()} is absent and only {@link #fingerprint()} and
 *       {@link #contentsCount()} describe the cacheable prefix that was hashed for prefix matching;
 *   <li>active, where a cache exists and its resource name, its expiry and the number of
 *       invocations it has served are known as well.
 * </ol>
 *
 * <p>{@link Builder#build()} refuses any mixture of the two. The creation time is optional in both
 * states.
 *
 * <p>Token counts are carried by the LLM response's usage metadata and are deliberately not
 * duplicated here.
 */
@AutoValue
@JsonDeserialize(builder = CacheMetadata.Builder.class)
public abstract class CacheMetadata extends JsonBaseModel {

  /** How long before its expiry an active cache is already considered due for a refresh. */
  private static final Duration REFRESH_BUFFER = Duration.ofMinutes(2);

  CacheMetadata() {}

  /**
   * Full resource name of the cached content, for example {@code
   * projects/123/locations/us-central1/cachedContents/456}.
   *
   * <p>Absent in the fingerprint-only state.
   */
  @JsonProperty("cacheName")
  public abstract Optional<String> cacheName();

  /** When the cache expires. Absent in the fingerprint-only state. */
  @JsonProperty("expireTime")
  public abstract Optional<Instant> expireTime();

  /** Hash of the cacheable contents (instruction, tools and contents), used to detect changes. */
  @JsonProperty("fingerprint")
  public abstract String fingerprint();

  /**
   * Number of invocations this cache has served. Absent in the fingerprint-only state.
   *
   * <p>Never negative.
   */
  @JsonProperty("invocationsUsed")
  public abstract Optional<Integer> invocationsUsed();

  /**
   * Number of contents behind the fingerprint: the cached contents when a cache exists, the
   * cacheable prefix otherwise.
   *
   * <p>Never negative.
   */
  @JsonProperty("contentsCount")
  public abstract int contentsCount();

  /** When the cache was created. Optional in both states. */
  @JsonProperty("createdAt")
  public abstract Optional<Instant> createdAt();

  /**
   * Returns whether an active cache is close enough to its expiry that it should be refreshed
   * rather than reused, allowing a buffer for the time the request itself takes.
   *
   * <p>Always false in the fingerprint-only state, where there is no cache to expire.
   */
  public boolean expireSoon() {
    return expireTime()
        .map(expiry -> Instant.now().isAfter(expiry.minus(REFRESH_BUFFER)))
        .orElse(false);
  }

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_CacheMetadata.Builder();
  }

  /** Builder for constructing {@link CacheMetadata} instances. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {

    @JsonCreator
    static Builder jacksonBuilder() {
      return CacheMetadata.builder();
    }

    @JsonProperty("cacheName")
    public abstract Builder cacheName(@Nullable String cacheName);

    @JsonProperty("expireTime")
    public abstract Builder expireTime(@Nullable Instant expireTime);

    @JsonProperty("fingerprint")
    public abstract Builder fingerprint(String fingerprint);

    @JsonProperty("invocationsUsed")
    public abstract Builder invocationsUsed(@Nullable Integer invocationsUsed);

    @JsonProperty("contentsCount")
    public abstract Builder contentsCount(int contentsCount);

    @JsonProperty("createdAt")
    public abstract Builder createdAt(@Nullable Instant createdAt);

    abstract CacheMetadata autoBuild();

    public final CacheMetadata build() {
      CacheMetadata metadata = autoBuild();
      Preconditions.checkState(
          metadata.cacheName().isPresent() == metadata.expireTime().isPresent()
              && metadata.expireTime().isPresent() == metadata.invocationsUsed().isPresent(),
          "cacheName, expireTime and invocationsUsed must all be set (active cache) or all be"
              + " absent (fingerprint-only state)");
      Preconditions.checkArgument(
          metadata.contentsCount() >= 0,
          "contentsCount must not be negative, but was %s",
          metadata.contentsCount());
      metadata
          .invocationsUsed()
          .ifPresent(
              used ->
                  Preconditions.checkArgument(
                      used >= 0, "invocationsUsed must not be negative, but was %s", used));
      return metadata;
    }
  }
}
