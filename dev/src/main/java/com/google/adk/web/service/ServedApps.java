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

package com.google.adk.web.service;

import static com.google.common.base.Preconditions.checkState;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.AppLoader;
import com.google.adk.workflow.Node;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** What the server serves, read from whichever loader it was configured with. */
interface ServedApps {

  /** Returns the names that {@code /list-apps} lists. */
  ImmutableList<String> listApps();

  /**
   * Returns the root that the app {@code appName} runs, or empty if there is no such app.
   *
   * @throws IllegalStateException if the app exists but fails to load
   */
  Optional<Node> loadRoot(String appName);

  /**
   * Returns a runner for {@code appName}, built on {@code builder} with {@code plugins} after the
   * app's own, or empty if there is no such app.
   *
   * @throws IllegalStateException if the app exists but fails to load
   */
  Optional<Runner> createRunner(
      String appName, Runner.Builder builder, List<? extends Plugin> plugins);

  /**
   * Serves {@code appLoader}'s apps if there is one, else {@code agentLoader}'s agents.
   *
   * @throws IllegalStateException if there is neither, or both
   */
  static ServedApps select(@Nullable AppLoader appLoader, @Nullable AgentLoader agentLoader) {
    if (appLoader != null) {
      checkState(agentLoader == null, "Configure an AppLoader or an AgentLoader, not both.");
      return new AppLoaderApps(appLoader);
    }
    checkState(agentLoader != null, "Configure an AppLoader or an AgentLoader.");
    return new AgentLoaderApps(agentLoader);
  }

  /** Serves an {@link AppLoader}'s apps, each under the requested name. */
  final class AppLoaderApps implements ServedApps {
    private final AppLoader loader;

    AppLoaderApps(AppLoader loader) {
      this.loader = loader;
    }

    @Override
    public ImmutableList<String> listApps() {
      return loader.listApps();
    }

    @Override
    public Optional<Node> loadRoot(String appName) {
      return loader.loadApp(appName).map(app -> app.rootNode().orElse(app.rootAgent()));
    }

    @Override
    public Optional<Runner> createRunner(
        String appName, Runner.Builder builder, List<? extends Plugin> plugins) {
      return loader
          .loadApp(appName)
          .map(
              app ->
                  builder
                      .app(
                          app.toBuilder()
                              .name(appName)
                              .plugins(
                                  ImmutableList.<Plugin>builder()
                                      .addAll(app.plugins())
                                      .addAll(plugins)
                                      .build())
                              .build())
                      .build());
    }
  }

  /**
   * Serves an {@link AgentLoader}'s agents as the server did before apps: without an {@link App}.
   */
  final class AgentLoaderApps implements ServedApps {
    private final AgentLoader loader;

    AgentLoaderApps(AgentLoader loader) {
      this.loader = loader;
    }

    @Override
    public ImmutableList<String> listApps() {
      return loader.listAgents();
    }

    @Override
    public Optional<Node> loadRoot(String appName) {
      return load(appName).map(Node.class::cast);
    }

    @Override
    public Optional<Runner> createRunner(
        String appName, Runner.Builder builder, List<? extends Plugin> plugins) {
      return load(appName)
          .map(agent -> builder.agent(agent).appName(appName).plugins(plugins).build());
    }

    private Optional<BaseAgent> load(String appName) {
      try {
        return Optional.of(loader.loadAgent(appName));
      } catch (NoSuchElementException e) {
        return Optional.empty();
      }
    }
  }
}
