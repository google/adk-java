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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Configuration for retrying transient failures at the LLM provider-call boundary. */
public final class RetryConfig {

  private static final ImmutableSet<Integer> DEFAULT_RETRYABLE_STATUS_CODES =
      ImmutableSet.of(408, 429, 500, 502, 503, 504);

  private final int maxAttempts;
  private final Duration initialBackoff;
  private final Duration maxBackoff;
  private final double multiplier;
  private final double jitterRatio;
  private final ImmutableSet<Integer> retryableStatusCodes;

  private RetryConfig(Builder builder) {
    this.maxAttempts = builder.maxAttempts;
    this.initialBackoff = builder.initialBackoff;
    this.maxBackoff = builder.maxBackoff;
    this.multiplier = builder.multiplier;
    this.jitterRatio = builder.jitterRatio;
    this.retryableStatusCodes = ImmutableSet.copyOf(builder.retryableStatusCodes);
  }

  /** Returns a retry configuration that performs exactly one provider attempt. */
  public static RetryConfig disabled() {
    return builder().build();
  }

  /** Returns a builder whose defaults keep retries disabled. */
  public static Builder builder() {
    return new Builder();
  }

  public int maxAttempts() {
    return maxAttempts;
  }

  public Duration initialBackoff() {
    return initialBackoff;
  }

  public Duration maxBackoff() {
    return maxBackoff;
  }

  public double multiplier() {
    return multiplier;
  }

  public double jitterRatio() {
    return jitterRatio;
  }

  public ImmutableSet<Integer> retryableStatusCodes() {
    return retryableStatusCodes;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RetryConfig that)) {
      return false;
    }
    return maxAttempts == that.maxAttempts
        && Double.compare(multiplier, that.multiplier) == 0
        && Double.compare(jitterRatio, that.jitterRatio) == 0
        && initialBackoff.equals(that.initialBackoff)
        && maxBackoff.equals(that.maxBackoff)
        && retryableStatusCodes.equals(that.retryableStatusCodes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        maxAttempts, initialBackoff, maxBackoff, multiplier, jitterRatio, retryableStatusCodes);
  }

  /** Builder for {@link RetryConfig}. */
  public static final class Builder {
    private int maxAttempts = 1;
    private Duration initialBackoff = Duration.ofMillis(500);
    private Duration maxBackoff = Duration.ofSeconds(8);
    private double multiplier = 2.0;
    private double jitterRatio = 0.0;
    private Set<Integer> retryableStatusCodes = DEFAULT_RETRYABLE_STATUS_CODES;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder initialBackoff(Duration initialBackoff) {
      this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff cannot be null");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder maxBackoff(Duration maxBackoff) {
      this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff cannot be null");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder multiplier(double multiplier) {
      this.multiplier = multiplier;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder jitterRatio(double jitterRatio) {
      this.jitterRatio = jitterRatio;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder retryableStatusCodes(Set<Integer> retryableStatusCodes) {
      this.retryableStatusCodes =
          Objects.requireNonNull(retryableStatusCodes, "retryableStatusCodes cannot be null");
      return this;
    }

    public RetryConfig build() {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be at least 1.");
      }
      if (initialBackoff.isNegative()) {
        throw new IllegalArgumentException("initialBackoff cannot be negative.");
      }
      if (maxBackoff.isNegative()) {
        throw new IllegalArgumentException("maxBackoff cannot be negative.");
      }
      if (maxBackoff.compareTo(initialBackoff) < 0) {
        throw new IllegalArgumentException("maxBackoff cannot be less than initialBackoff.");
      }
      if (!Double.isFinite(multiplier) || multiplier < 1.0) {
        throw new IllegalArgumentException("multiplier must be finite and at least 1.0.");
      }
      if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio > 1.0) {
        throw new IllegalArgumentException("jitterRatio must be between 0.0 and 1.0.");
      }
      for (int statusCode : retryableStatusCodes) {
        if (statusCode < 100 || statusCode > 599) {
          throw new IllegalArgumentException(
              "retryableStatusCodes must contain valid HTTP status codes.");
        }
      }
      return new RetryConfig(this);
    }
  }
}
