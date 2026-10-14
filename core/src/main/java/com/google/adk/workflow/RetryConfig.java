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

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * How a node is retried when it fails. The builder initializes each property to its {@code
 * DEFAULT_} constant, except {@link #exceptions}, which starts unset.
 */
@Experimental
@AutoValue
public abstract class RetryConfig {

  /** The default for {@link #maxAttempts}. */
  public static final int DEFAULT_MAX_ATTEMPTS = 5;

  /** The default for {@link #initialDelay}. */
  public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(1);

  /** The default for {@link #maxDelay}. */
  public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(60);

  /** The default for {@link #backoffFactor}. */
  public static final double DEFAULT_BACKOFF_FACTOR = 2.0;

  /** The default for {@link #jitter}. */
  public static final double DEFAULT_JITTER = 1.0;

  /** Returns the maximum number of attempts, including the first, so 0 or 1 means no retry. */
  public abstract int maxAttempts();

  /** Returns the delay before the first retry. */
  public abstract Duration initialDelay();

  /** Returns the ceiling on any single delay. */
  public abstract Duration maxDelay();

  /** Returns the multiplier applied to the delay after each attempt. */
  public abstract double backoffFactor();

  /**
   * Returns the randomness factor, not a duration: the delay is spread over {@code delay * (1 +/-
   * jitter)}. Zero removes randomness.
   */
  public abstract double jitter();

  /**
   * Returns the simple type names of the failures to retry on, matched exactly with no subclass
   * match. When unset, any failure is retried; an empty list retries none.
   */
  public abstract Optional<ImmutableList<String>> exceptions();

  public static Builder builder() {
    return new AutoValue_RetryConfig.Builder()
        .maxAttempts(DEFAULT_MAX_ATTEMPTS)
        .initialDelay(DEFAULT_INITIAL_DELAY)
        .maxDelay(DEFAULT_MAX_DELAY)
        .backoffFactor(DEFAULT_BACKOFF_FACTOR)
        .jitter(DEFAULT_JITTER);
  }

  public abstract Builder toBuilder();

  /** Builder for {@link RetryConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder maxAttempts(int maxAttempts);

    @CanIgnoreReturnValue
    public abstract Builder initialDelay(Duration initialDelay);

    @CanIgnoreReturnValue
    public abstract Builder maxDelay(Duration maxDelay);

    @CanIgnoreReturnValue
    public abstract Builder backoffFactor(double backoffFactor);

    @CanIgnoreReturnValue
    public abstract Builder jitter(double jitter);

    @CanIgnoreReturnValue
    abstract Builder exceptions(@Nullable ImmutableList<String> exceptions);

    @CanIgnoreReturnValue
    public Builder exceptions(@Nullable List<String> exceptions) {
      return exceptions(exceptions == null ? null : ImmutableList.copyOf(exceptions));
    }

    abstract RetryConfig autoBuild();

    public final RetryConfig build() {
      RetryConfig config = autoBuild();
      checkArgument(
          config.maxAttempts() >= 0, "maxAttempts must not be negative; 0 or 1 means no retry.");
      checkArgument(!config.initialDelay().isNegative(), "initialDelay must not be negative.");
      checkArgument(!config.maxDelay().isNegative(), "maxDelay must not be negative.");
      checkArgument(
          isFiniteAndNotNegative(config.backoffFactor()),
          "backoffFactor must be finite and not negative.");
      checkArgument(
          isFiniteAndNotNegative(config.jitter()), "jitter must be finite and not negative.");
      return config;
    }

    private static boolean isFiniteAndNotNegative(double value) {
      return Double.isFinite(value) && value >= 0.0;
    }
  }
}
