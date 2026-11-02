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

package com.google.adk.web.controller;

import com.google.adk.web.service.RunnerService;
import com.google.adk.workflow.Node;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Serves the app structure that the Dev UI navigates and draws its graph views from. */
@RestController
class AppGraphController {

  private static final Logger log = LoggerFactory.getLogger(AppGraphController.class);

  private final RunnerService runnerService;

  AppGraphController(RunnerService runnerService) {
    this.runnerService = runnerService;
  }

  @GetMapping("/dev/apps/{appName}/build_graph")
  AppGraph buildGraph(@PathVariable String appName) {
    return AppGraph.of(appName, loadRoot(appName));
  }

  /** Returns the root of the app {@code appName}, or throws the error response to send. */
  private Node loadRoot(String appName) {
    Optional<Node> root;
    try {
      root = runnerService.loadRoot(appName);
    } catch (RuntimeException e) {
      log.warn("The requested app failed to load for its graph.", e);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "App failed to load.", e);
    }
    return root.orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found."));
  }
}
