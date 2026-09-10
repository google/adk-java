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
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Simulation configuration for a single tool.
 *
 * <p>Experimental. The shape of this type may change.
 */
@AutoValue
public abstract class ToolSimulationConfig {

  /** Name of the tool to be simulated. */
  public abstract String toolName();

  /**
   * Injections for the tool, tried in order. The mock strategy answers the call when none of them
   * applies.
   */
  public abstract ImmutableList<InjectionConfig> injectionConfigs();

  /** The mock strategy to use. */
  public abstract MockStrategy mockStrategyType();

  public static Builder builder() {
    return new AutoValue_ToolSimulationConfig.Builder()
        .injectionConfigs(ImmutableList.of())
        .mockStrategyType(MockStrategy.MOCK_STRATEGY_UNSPECIFIED);
  }

  public abstract Builder toBuilder();

  /** Builder for {@link ToolSimulationConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder toolName(String toolName);

    @CanIgnoreReturnValue
    public abstract Builder injectionConfigs(Iterable<InjectionConfig> injectionConfigs);

    @CanIgnoreReturnValue
    public abstract Builder mockStrategyType(MockStrategy mockStrategyType);

    abstract ToolSimulationConfig autoBuild();

    public final ToolSimulationConfig build() {
      ToolSimulationConfig config = autoBuild();
      Preconditions.checkState(
          !config.injectionConfigs().isEmpty()
              || config.mockStrategyType() != MockStrategy.MOCK_STRATEGY_UNSPECIFIED,
          "Tool \"%s\" has no injectionConfigs, so mockStrategyType cannot be"
              + " MOCK_STRATEGY_UNSPECIFIED: nothing would ever be simulated for it.",
          config.toolName());
      return config;
    }
  }
}
