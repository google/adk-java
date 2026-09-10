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
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.ThinkingConfig;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for an environment simulation: which tools stop really running, and what answers
 * them instead.
 *
 * <p>Experimental. The shape of this type may change.
 */
@AutoValue
public abstract class EnvironmentSimulationConfig {

  private static final String DEFAULT_SIMULATION_MODEL = "gemini-2.5-flash";

  /** The tools to simulate. Each tool may be named once. */
  public abstract ImmutableList<ToolSimulationConfig> toolSimulationConfigs();

  /**
   * The model the simulation itself asks, for tool analysis and for mock responses. Defaults to
   * {@code gemini-2.5-flash}.
   */
  public abstract String simulationModel();

  /** The configuration for the calls the simulation makes to {@link #simulationModel()}. */
  public abstract GenerateContentConfig simulationModelConfiguration();

  /**
   * Tracing data, such as a prior agent run trace as a JSON string, giving the mock strategy
   * historical context.
   */
  public abstract Optional<String> tracing();

  /**
   * Environment-specific data, such as a minimal database dump as a JSON string, that the mock
   * strategy generates against.
   */
  public abstract Optional<String> environmentData();

  public static Builder builder() {
    return new AutoValue_EnvironmentSimulationConfig.Builder()
        .simulationModel(DEFAULT_SIMULATION_MODEL)
        .simulationModelConfiguration(
            GenerateContentConfig.builder()
                .thinkingConfig(
                    ThinkingConfig.builder().includeThoughts(false).thinkingBudget(10240).build())
                .build());
  }

  public abstract Builder toBuilder();

  /** Builder for {@link EnvironmentSimulationConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder toolSimulationConfigs(
        Iterable<ToolSimulationConfig> toolSimulationConfigs);

    @CanIgnoreReturnValue
    public abstract Builder simulationModel(String simulationModel);

    @CanIgnoreReturnValue
    public abstract Builder simulationModelConfiguration(
        GenerateContentConfig simulationModelConfiguration);

    @CanIgnoreReturnValue
    public abstract Builder tracing(String tracing);

    @CanIgnoreReturnValue
    public abstract Builder environmentData(String environmentData);

    abstract EnvironmentSimulationConfig autoBuild();

    public final EnvironmentSimulationConfig build() {
      EnvironmentSimulationConfig config = autoBuild();
      Preconditions.checkState(
          !config.toolSimulationConfigs().isEmpty(), "toolSimulationConfigs must be provided.");
      Set<String> seenToolNames = new HashSet<>();
      for (ToolSimulationConfig toolSimulationConfig : config.toolSimulationConfigs()) {
        Preconditions.checkState(
            seenToolNames.add(toolSimulationConfig.toolName()),
            "Duplicate toolName found: %s. Only one of the entries could ever be reached.",
            toolSimulationConfig.toolName());
      }
      return config;
    }
  }
}
