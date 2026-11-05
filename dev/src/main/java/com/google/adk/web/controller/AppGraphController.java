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

import com.google.adk.web.controller.AppGraph.GraphNode;
import com.google.adk.web.service.RunnerService;
import com.google.adk.workflow.Node;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Serves the app structure and graph images that the Dev UI draws its graph views from. */
@RestController
class AppGraphController {

  private static final Logger log = LoggerFactory.getLogger(AppGraphController.class);

  private static final ImmutableSet<String> TRUE_QUERY_VALUES =
      ImmutableSet.of("1", "on", "t", "true", "y", "yes");

  private static final ImmutableSet<String> FALSE_QUERY_VALUES =
      ImmutableSet.of("0", "off", "f", "false", "n", "no");

  private final RunnerService runnerService;

  AppGraphController(RunnerService runnerService) {
    this.runnerService = runnerService;
  }

  @GetMapping("/dev/apps/{appName}/build_graph")
  AppGraph buildGraph(@PathVariable String appName) {
    return AppGraph.of(appName, loadRoot(appName));
  }

  /**
   * Returns DOT source for the root and every workflow below it, keyed by node path, or a single
   * {@link DotGraph} for the subtree a non-empty {@code node} names.
   */
  @GetMapping("/dev/apps/{appName}/build_graph_image")
  Object buildGraphImage(
      @PathVariable String appName,
      @RequestParam(name = "dark_mode", required = false) @Nullable String darkModeValue,
      @RequestParam(name = "node", defaultValue = "") String nodePath) {
    boolean darkMode = parseDarkMode(darkModeValue);
    GraphNode target =
        AppGraph.graphNodeOf(loadRoot(appName))
            .find(nodePath)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found."));
    if (!nodePath.isEmpty()) {
      // The Dev UI's drill-in dialog reads a bare DotGraph.
      return new DotGraph(GraphPlotter.plot(target, darkMode));
    }
    // Keys below the root start with its name: the Dev UI drops each key's first segment.
    ImmutableMap.Builder<String, DotGraph> graphs = ImmutableMap.builder();
    graphs.put("", new DotGraph(GraphPlotter.plot(target, darkMode)));
    target
        .nestedWorkflowsByPath()
        .forEach(
            (path, workflow) ->
                graphs.put(path, new DotGraph(GraphPlotter.plot(workflow, darkMode))));
    return graphs.buildOrThrow();
  }

  /** DOT source for one graph, under the {@code dotSrc} key the Dev UI reads. */
  record DotGraph(String dotSrc) {}

  /** Returns whether {@code value} turns dark mode on, or throws the error response to send. */
  private static boolean parseDarkMode(@Nullable String value) {
    // Not a defaultValue, which Spring would also apply to an empty value.
    if (value == null) {
      return false;
    }
    String lowercase = value.toLowerCase(Locale.ROOT);
    if (TRUE_QUERY_VALUES.contains(lowercase)) {
      return true;
    }
    if (FALSE_QUERY_VALUES.contains(lowercase)) {
      return false;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dark_mode must be true or false.");
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
