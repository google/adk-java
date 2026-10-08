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
import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Duration;

/**
 * Configuration for context caching across all agents in an app; without it, nothing is cached.
 * Gemini models cache the stable prefix of an agent's requests from its second request on, once
 * that prefix reaches the model's minimum: 2048 tokens for Gemini 2.5, 4096 for Gemini 3. Other
 * models ignore this config, and a cache carries over to the next invocation only with a session
 * service that stores whole events, such as {@code InMemorySessionService}.
 *
 * @param maxInvocations Maximum number of invocations to reuse the same cache before refreshing it,
 *     from 1 to 100. Defaults to 10.
 * @param ttl Time-to-live for cache; a whole number of seconds, at least one. Defaults to 1800
 *     seconds (30 minutes).
 * @param minTokens Minimum prompt token count of the agent's previous request needed to create a
 *     cache; raise it to skip small requests, where cache storage can cost more than it saves. Must
 *     not be negative. Defaults to 0.
 */
public record ContextCacheConfig(int maxInvocations, Duration ttl, int minTokens) {

  /**
   * Validates the config as ADK Python does.
   *
   * @throws IllegalArgumentException if a value is out of range
   * @throws NullPointerException if {@code ttl} is null
   */
  public ContextCacheConfig {
    checkArgument(
        maxInvocations >= 1 && maxInvocations <= 100,
        "maxInvocations must be between 1 and 100, but was %s.",
        maxInvocations);
    checkNotNull(ttl, "ttl must not be null.");
    checkArgument(
        ttl.toSeconds() >= 1 && ttl.toNanosPart() == 0,
        "ttl must be a whole number of seconds, at least one, but was %s.",
        ttl);
    checkArgument(minTokens >= 0, "minTokens must not be negative, but was %s.", minTokens);
  }

  public ContextCacheConfig() {
    this(10, Duration.ofMinutes(30), 0);
  }

  /** Returns the TTL in the {@code "<seconds>s"} form that the Gemini API uses. */
  public String getTtlString() {
    return ttl.toSeconds() + "s";
  }

  @Override
  public String toString() {
    return "ContextCacheConfig(maxInvocations="
        + maxInvocations
        + ", ttl="
        + ttl.toSeconds()
        + "s, minTokens="
        + minTokens
        + ")";
  }
}
