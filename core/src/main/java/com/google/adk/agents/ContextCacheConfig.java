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

import com.google.errorprone.annotations.InlineMe;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for context caching across all agents in an app.
 *
 * <p>This configuration enables and controls context caching behavior for all LLM agents in an app.
 * When this config is present on an app, context caching is enabled for all agents. When absent
 * (null), context caching is disabled.
 *
 * <p>Context caching can significantly reduce costs and improve response times by reusing
 * previously processed context across multiple requests.
 *
 * @param cacheIntervals Maximum number of invocations to reuse the same cache before refreshing it.
 *     Defaults to 10.
 * @param ttl Time-to-live for cache. Defaults to 1800 seconds (30 minutes).
 * @param minTokens Minimum estimated request tokens required to enable caching. This compares
 *     against the estimated total tokens of the request (system instruction + tools + contents).
 *     Context cache storage may have cost. Set higher to avoid caching small requests where
 *     overhead may exceed benefits. Defaults to 0.
 * @param createHttpOptions HTTP options, such as a timeout, for the call that creates a cache; null
 *     uses the client's defaults. Defaults to null.
 */
public record ContextCacheConfig(
    int cacheIntervals, Duration ttl, int minTokens, @Nullable HttpOptions createHttpOptions) {

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

  /** Returns TTL as string format for cache creation. */
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
