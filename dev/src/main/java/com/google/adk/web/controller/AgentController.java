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

package com.google.adk.web.controller;

import static java.util.stream.Collectors.toList;

import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.service.RunnerService;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spring Boot REST Controller handling agent-related API endpoints. */
@RestController
public class AgentController {

  private static final Logger log = LoggerFactory.getLogger(AgentController.class);

  private final RunnerService runnerService;

  /**
   * Constructs the AgentController.
   *
   * @param runnerService The service serving the configured apps.
   */
  @Autowired
  public AgentController(RunnerService runnerService) {
    this.runnerService = runnerService;
    ImmutableList<String> agentNames = runnerService.listApps();
    log.info(
        "AgentController initialized with {} dynamic agents: {}", agentNames.size(), agentNames);
    if (agentNames.isEmpty()) {
      log.warn(
          "No apps are served. Check the configured loader, such as the 'adk.agents.source-dir'"
              + " property and compilation logs for compiled agents.");
    }
  }

  /**
   * Constructs an AgentController that serves only the agents of {@code agentProvider}.
   *
   * @param agentProvider The provider for loading agents.
   * @deprecated Use {@link #AgentController(RunnerService)} instead.
   */
  @Deprecated
  public AgentController(AgentLoader agentProvider) {
    // Listing apps reads only the loader, so these runner services are never used.
    this(
        new RunnerService(
            agentProvider,
            new InMemoryArtifactService(),
            new InMemorySessionService(),
            new InMemoryMemoryService(),
            ImmutableList.of()));
  }

  /**
   * Lists the available applications.
   *
   * @return The names of the served apps, sorted.
   */
  @GetMapping("/list-apps")
  public List<String> listApps() {
    ImmutableList<String> agentNames = runnerService.listApps();
    log.info("Listing apps from dynamic registry. Found: {}", agentNames);
    return agentNames.stream().sorted().collect(toList());
  }
}
