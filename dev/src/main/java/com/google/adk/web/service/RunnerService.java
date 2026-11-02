/*
 * Copyright 2025 Google LLC
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.annotations.Experimental;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.AppLoader;
import com.google.adk.web.CompiledAgentLoader;
import com.google.adk.workflow.Node;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Serves the configured apps, and creates and caches a Runner for each. */
@Component
public class RunnerService {
  private static final Logger log = LoggerFactory.getLogger(RunnerService.class);

  private final ServedApps servedApps;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  private final BaseMemoryService memoryService;
  private final List<BasePlugin> extraPlugins;
  private final Map<String, Runner> runnerCache = new ConcurrentHashMap<>();

  /**
   * Serves the apps of the configured {@link AppLoader}, or the agents of the configured {@link
   * AgentLoader} if there is no {@code AppLoader}.
   *
   * @throws IllegalStateException if neither loader is configured, or if both are and the agent
   *     loader is not a {@link CompiledAgentLoader}
   */
  @Autowired
  RunnerService(
      ObjectProvider<AppLoader> appLoader,
      ObjectProvider<AgentLoader> agentLoader,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      @Autowired(required = false) @Qualifier("extraPlugins") List<BasePlugin> extraPlugins) {
    this(
        selectServedApps(appLoader.getIfAvailable(), agentLoader),
        artifactService,
        sessionService,
        memoryService,
        extraPlugins);
  }

  /** Serves the apps of {@code appLoader}. */
  public RunnerService(
      AppLoader appLoader,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      List<BasePlugin> extraPlugins) {
    this(
        new ServedApps.AppLoaderApps(checkNotNull(appLoader)),
        artifactService,
        sessionService,
        memoryService,
        extraPlugins);
  }

  /** Serves the agents of {@code agentProvider}. */
  public RunnerService(
      AgentLoader agentProvider,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      List<BasePlugin> extraPlugins) {
    this(
        new ServedApps.AgentLoaderApps(checkNotNull(agentProvider)),
        artifactService,
        sessionService,
        memoryService,
        extraPlugins);
  }

  private RunnerService(
      ServedApps servedApps,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      List<BasePlugin> extraPlugins) {
    this.servedApps = servedApps;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
    this.extraPlugins =
        extraPlugins != null ? ImmutableList.copyOf(extraPlugins) : ImmutableList.of();
  }

  private static ServedApps selectServedApps(
      @Nullable AppLoader appLoader, ObjectProvider<AgentLoader> agentLoaders) {
    if (appLoader == null) {
      return ServedApps.select(null, agentLoaders.getIfAvailable());
    }
    // The default loader is @Primary, so look past it for one the app loader would hide.
    return ServedApps.select(
        appLoader,
        agentLoaders
            .orderedStream()
            .filter(loader -> !(loader instanceof CompiledAgentLoader))
            .findFirst()
            .orElse(null));
  }

  /** Returns the names of the apps this server serves. */
  public ImmutableList<String> listApps() {
    return servedApps.listApps();
  }

  /**
   * Returns the root that the app {@code appName} runs: the workflow node it runs, or else its root
   * agent. Empty if there is no such app.
   *
   * @throws IllegalStateException if the app exists but fails to load
   */
  @Experimental
  public Optional<Node> loadRoot(String appName) {
    return servedApps.loadRoot(appName);
  }

  /**
   * Gets the Runner instance for a given application name. An app runs under the requested name,
   * with this server's plugins after its own.
   *
   * @param appName The application name requested by the user.
   * @return A configured Runner instance.
   * @throws ResponseStatusException with 404 if there is no such app, or 500 if it fails to load
   */
  public Runner getRunner(String appName) {
    return runnerCache.computeIfAbsent(appName, this::createRunner);
  }

  private Runner createRunner(String appName) {
    Optional<Runner> runner;
    try {
      runner =
          servedApps.createRunner(
              appName,
              Runner.builder()
                  .artifactService(artifactService)
                  .sessionService(sessionService)
                  .memoryService(memoryService),
              extraPlugins);
    } catch (RuntimeException e) {
      // Callers catch only ResponseStatusException; /run_sse would never end on anything else.
      log.error("The requested app failed to load.", e);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "App failed to load.", e);
    }
    return runner.orElseThrow(
        () -> {
          log.warn(
              "No app is served under the requested name; {} apps are available.",
              servedApps.listApps().size());
          return new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found.");
        });
  }

  /** Called by hot loader when agents are updated */
  public void onAgentUpdated(String agentName) {
    Runner removed = runnerCache.remove(agentName);
    if (removed != null) {
      log.info("Cleared cached Runner for updated agent: {}", agentName);
    } else {
      log.debug("No cached Runner found for agent: {}", agentName);
    }
  }
}
