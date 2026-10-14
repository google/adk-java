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
package com.google.adk.agents;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.errorprone.annotations.InlineMe;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Configures context caching for all agents in an app. Without it, nothing is cached.
 *
 * @param cacheIntervals maximum number of invocations that reuse the same cache before it is
 *     refreshed; must be from 1 to 100
 * @param ttl time-to-live of the cache; must be a whole number of seconds, at least one
 * @param minTokens minimum prompt token count of the agent's previous request needed to create a
 *     cache; raise it to skip small requests, where cache storage can cost more than it saves; must
 *     not be negative
 * @param createHttpOptions HTTP options, such as a timeout, for the call that creates a cache, or
 *     {@code null} to use the client's defaults; if that call fails, the request is sent without a
 *     cache
 */
public record ContextCacheConfig(
    int cacheIntervals, Duration ttl, int minTokens, @Nullable HttpOptions createHttpOptions) {

  /**
   * Creates a config.
   *
   * @throws IllegalArgumentException if a value is out of range
   */
  public ContextCacheConfig {
    checkArgument(
        cacheIntervals >= 1 && cacheIntervals <= 100,
        "cacheIntervals must be between 1 and 100, but was %s.",
        cacheIntervals);
    checkArgument(
        ttl.getSeconds() >= 1 && ttl.getNano() == 0,
        "ttl must be a whole number of seconds, at least one, but was %s.",
        ttl);
    checkArgument(minTokens >= 0, "minTokens must not be negative, but was %s.", minTokens);
  }

  /**
   * Creates a config with the defaults: {@code cacheIntervals} of 10, {@code ttl} of 30 minutes,
   * {@code minTokens} of 0, and the client's default HTTP options ({@code createHttpOptions} is
   * {@code null}).
   */
  public ContextCacheConfig() {
    this(10, Duration.ofMinutes(30), 0);
  }

  /** Creates a config that creates caches with the client's default HTTP options. */
  public ContextCacheConfig(int cacheIntervals, Duration ttl, int minTokens) {
    this(cacheIntervals, ttl, minTokens, /* createHttpOptions= */ null);
  }

  /**
   * Returns {@link #cacheIntervals()}.
   *
   * @deprecated Use {@link #cacheIntervals()}, the name ADK Python and ADK Kotlin use.
   */
  @Deprecated
  @InlineMe(replacement = "this.cacheIntervals()")
  public int maxInvocations() {
    return cacheIntervals();
  }

  /** Returns the TTL in the {@code "<seconds>s"} form that the Gemini API uses. */
  public String getTtlString() {
    return ttl.toSeconds() + "s";
  }

  @Override
  public String toString() {
    // Says only whether HTTP options are set, since their headers can carry credentials.
    return "ContextCacheConfig(cacheIntervals="
        + cacheIntervals
        + ", ttl="
        + ttl.toSeconds()
        + "s, minTokens="
        + minTokens
        + ", createHttpOptions="
        + (createHttpOptions == null ? "null" : "set")
        + ")";
  }
}
