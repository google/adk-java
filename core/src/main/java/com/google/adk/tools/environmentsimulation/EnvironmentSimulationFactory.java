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

import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.plugins.Plugin;

/**
 * Creates an environment simulation, either for one agent or for everything a runner runs.
 *
 * <p>Experimental. The shape of this type may change.
 */
public final class EnvironmentSimulationFactory {

  /**
   * Creates a before-tool callback that simulates the tools the configuration names, for the agent
   * it is attached to.
   *
   * @param config the configuration for the simulation.
   * @return a callback to pass to {@code LlmAgent.Builder.beforeToolCallback}.
   */
  public static BeforeToolCallback createCallback(EnvironmentSimulationConfig config) {
    EnvironmentSimulationEngine engine = new EnvironmentSimulationEngine(config);
    return (invocationContext, tool, args, toolContext) -> engine.simulate(tool, args, toolContext);
  }

  /**
   * Creates a plugin that simulates the tools the configuration names, for every agent the runner
   * runs.
   *
   * @param config the configuration for the simulation.
   * @return a plugin to pass to a runner.
   */
  public static Plugin createPlugin(EnvironmentSimulationConfig config) {
    return new EnvironmentSimulationPlugin(new EnvironmentSimulationEngine(config));
  }

  private EnvironmentSimulationFactory() {}
}
