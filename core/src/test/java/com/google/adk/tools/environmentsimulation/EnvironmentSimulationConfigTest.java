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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EnvironmentSimulationConfigTest {

  @Test
  public void build_duplicateToolName_isRefused() {
    EnvironmentSimulationConfig.Builder builder =
        EnvironmentSimulationConfig.builder()
            .toolSimulationConfigs(
                ImmutableList.of(mockingTool("weather"), mockingTool("weather")));

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);

    assertThat(exception).hasMessageThat().contains("weather");
  }

  @Test
  public void build_noToolSimulationConfigs_isRefused() {
    EnvironmentSimulationConfig.Builder builder =
        EnvironmentSimulationConfig.builder().toolSimulationConfigs(ImmutableList.of());

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_defaultsToAModelAndAThinkingBudget() {
    EnvironmentSimulationConfig config =
        EnvironmentSimulationConfig.builder()
            .toolSimulationConfigs(ImmutableList.of(mockingTool("weather")))
            .build();

    assertThat(config.simulationModel()).isEqualTo("gemini-2.5-flash");
    assertThat(config.simulationModelConfiguration().thinkingConfig()).isPresent();
    assertThat(config.tracing()).isEmpty();
    assertThat(config.environmentData()).isEmpty();
  }

  @Test
  public void toolSimulationConfig_nothingToSimulate_isRefused() {
    // No injections and no mock strategy means the tool is named but never simulated, which is a
    // configuration mistake rather than a way to switch simulation off for it.
    ToolSimulationConfig.Builder builder = ToolSimulationConfig.builder().toolName("weather");

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);

    assertThat(exception).hasMessageThat().contains("weather");
  }

  @Test
  public void injectionConfig_bothAnErrorAndAResponse_isRefused() {
    InjectionConfig.Builder builder =
        InjectionConfig.builder()
            .injectedError(
                InjectedError.builder()
                    .injectedHttpErrorCode(503)
                    .errorMessage("upstream is down")
                    .build())
            .injectedResponse(ImmutableMap.of("temperature", 20));

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void injectionConfig_neitherAnErrorNorAResponse_isRefused() {
    assertThrows(IllegalStateException.class, InjectionConfig.builder()::build);
  }

  @Test
  public void injectionConfig_latencyOverTheCap_isRefused() {
    InjectionConfig.Builder builder =
        InjectionConfig.builder()
            .injectedLatencySeconds(120.1)
            .injectedResponse(ImmutableMap.of("temperature", 20));

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void injectionConfig_defaultsToAlwaysInjectingEveryCall() {
    InjectionConfig config =
        InjectionConfig.builder().injectedResponse(ImmutableMap.of("temperature", 20)).build();

    assertThat(config.injectionProbability()).isEqualTo(1.0);
    assertThat(config.matchArgs()).isEmpty();
    assertThat(config.injectedLatencySeconds()).isEqualTo(0.0);
    assertThat(config.randomSeed()).isEmpty();
  }

  private static ToolSimulationConfig mockingTool(String toolName) {
    return ToolSimulationConfig.builder()
        .toolName(toolName)
        .mockStrategyType(MockStrategy.MOCK_STRATEGY_TOOL_SPEC)
        .build();
  }
}
