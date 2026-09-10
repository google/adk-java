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

package com.google.adk.tools.environmentsimulation;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;

/**
 * Injection configuration for a tool: what to answer instead of running it, and when.
 *
 * <p>Experimental. The shape of this type may change.
 */
@AutoValue
public abstract class InjectionConfig {

  /** The largest latency that may be injected into a single tool call, in seconds. */
  private static final double MAX_INJECTED_LATENCY_SECONDS = 120.0;

  /** Probability of injecting the injected value. Defaults to 1.0, which always injects. */
  public abstract double injectionProbability();

  /**
   * Injects only into calls whose arguments contain every entry named here. An empty map injects
   * into every call.
   */
  public abstract ImmutableMap<String, Object> matchArgs();

  /**
   * Latency to inject into the tool call, defaulting to none and capped at 120 seconds. Note it may
   * not be accurate if the interceptor is applied as an after-tool callback.
   */
  public abstract double injectedLatencySeconds();

  /** The random seed to use for this injection, which makes the outcome reproducible. */
  public abstract Optional<Long> randomSeed();

  /** The error to answer with. Exactly one of this and {@link #injectedResponse()} is set. */
  public abstract Optional<InjectedError> injectedError();

  /** The response to answer with. Exactly one of this and {@link #injectedError()} is set. */
  public abstract ImmutableMap<String, Object> injectedResponse();

  public static Builder builder() {
    return new AutoValue_InjectionConfig.Builder()
        .injectionProbability(1.0)
        .matchArgs(ImmutableMap.of())
        .injectedLatencySeconds(0.0)
        .injectedResponse(ImmutableMap.of());
  }

  public abstract Builder toBuilder();

  /** Builder for {@link InjectionConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder injectionProbability(double injectionProbability);

    @CanIgnoreReturnValue
    public abstract Builder matchArgs(Map<String, Object> matchArgs);

    @CanIgnoreReturnValue
    public abstract Builder injectedLatencySeconds(double injectedLatencySeconds);

    @CanIgnoreReturnValue
    public abstract Builder randomSeed(long randomSeed);

    @CanIgnoreReturnValue
    public abstract Builder injectedError(InjectedError injectedError);

    @CanIgnoreReturnValue
    public abstract Builder injectedResponse(Map<String, Object> injectedResponse);

    abstract InjectionConfig autoBuild();

    public final InjectionConfig build() {
      InjectionConfig config = autoBuild();
      Preconditions.checkState(
          config.injectedLatencySeconds() <= MAX_INJECTED_LATENCY_SECONDS,
          "injectedLatencySeconds must be at most %s seconds, but was %s.",
          MAX_INJECTED_LATENCY_SECONDS,
          config.injectedLatencySeconds());
      boolean hasError = config.injectedError().isPresent();
      boolean hasResponse = !config.injectedResponse().isEmpty();
      Preconditions.checkState(
          hasError != hasResponse,
          "Either injectedError or injectedResponse must be set, but not both, and not neither.");
      return config;
    }
  }
}
