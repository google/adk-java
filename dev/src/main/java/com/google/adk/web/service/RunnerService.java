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

import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.web.AgentLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Service for creating and caching Runner instances. */
@Component
public class RunnerService {
  private static final Logger log = LoggerFactory.getLogger(RunnerService.class);

  private final AgentLoader agentProvider;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  private final BaseMemoryService memoryService;
  private final Map<String, Runner> runnerCache = new ConcurrentHashMap<>();

  @Autowired
  public RunnerService(
      AgentLoader agentProvider,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService) {
    this.agentProvider = agentProvider;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
  }

  /**
   * Gets the Runner instance for a given application name. Handles potential agent engine ID
   * overrides.
   *
   * @param appName The application name requested by the user.
   * @return A configured Runner instance.
   */
  public Runner getRunner(String appName) {
    return runnerCache.computeIfAbsent(
        appName,
        key -> {
          try {
            BaseAgent agent = agentProvider.loadAgent(key);
            log.info(
                "RunnerService: Creating Runner for appName: {}, using agent definition: {}",
                appName,
                agent.name());
            return new Runner(
                agent, appName, this.artifactService, this.sessionService, this.memoryService);
          } catch (java.util.NoSuchElementException e) {
            log.error(
                "Agent/App named '{}' not found in registry. Available apps: {}",
                key,
                agentProvider.listAgents());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent/App not found: " + key);
          } catch (IllegalStateException e) {
            log.error("Agent '{}' exists but failed to load: {}", key, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Agent failed to load: " + key, e);
          }
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
