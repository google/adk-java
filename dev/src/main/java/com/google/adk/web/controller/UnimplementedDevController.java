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

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the development endpoints the Dev UI calls but this server does not implement with 501
 * and a readable {@code detail}, which a client can tell apart from a 404 for a wrong path.
 */
@RestController
@RequestMapping("/dev/apps/{appName}")
class UnimplementedDevController {

  private static final String BUILDER = "The agent builder is not implemented in ADK Java.";

  // The Dev UI shows its own notice, rather than an error, when the detail says "not installed".
  private static final String EVALUATION =
      "Evaluation is not installed: ADK Java does not support it.";

  private static final String DEPLOY = "Deploying from the Dev UI is not supported in ADK Java.";

  private static final String TESTS = "Agent tests are not implemented in ADK Java.";

  @GetMapping("/builder")
  ResponseEntity<Map<String, String>> getBuilder() {
    return notImplemented(BUILDER);
  }

  @PostMapping({"/builder/save", "/builder/cancel"})
  ResponseEntity<Map<String, String>> postBuilder() {
    return notImplemented(BUILDER);
  }

  @GetMapping({
    "/eval-sets/{evalSetId}",
    "/eval_sets",
    "/eval_sets/{evalSetId}/evals",
    "/eval_sets/{evalSetId}/evals/{evalId}",
    "/eval_results",
    "/eval_results/{evalResultId}",
    "/metrics-info"
  })
  ResponseEntity<Map<String, String>> getEvaluation() {
    return notImplemented(EVALUATION);
  }

  @PostMapping({"/eval-sets", "/eval-sets/{evalSetId}/run", "/eval_sets/{evalSetId}/add_session"})
  ResponseEntity<Map<String, String>> postEvaluation() {
    return notImplemented(EVALUATION);
  }

  @PutMapping("/eval_sets/{evalSetId}/evals/{evalId}")
  ResponseEntity<Map<String, String>> putEvaluation() {
    return notImplemented(EVALUATION);
  }

  @DeleteMapping({"/eval-sets/{evalSetId}", "/eval_sets/{evalSetId}/evals/{evalId}"})
  ResponseEntity<Map<String, String>> deleteEvaluation() {
    return notImplemented(EVALUATION);
  }

  @GetMapping("/deploy/defaults")
  ResponseEntity<Map<String, String>> getDeploy() {
    return notImplemented(DEPLOY);
  }

  @PostMapping("/deploy/{target}")
  ResponseEntity<Map<String, String>> postDeploy() {
    return notImplemented(DEPLOY);
  }

  @GetMapping({"/tests", "/tests/{testName}"})
  ResponseEntity<Map<String, String>> getTests() {
    return notImplemented(TESTS);
  }

  @PostMapping({"/tests/rebuild", "/tests/run"})
  ResponseEntity<Map<String, String>> postTests() {
    return notImplemented(TESTS);
  }

  @PutMapping("/tests/{testName}")
  ResponseEntity<Map<String, String>> putTests() {
    return notImplemented(TESTS);
  }

  @DeleteMapping("/tests/{testName}")
  ResponseEntity<Map<String, String>> deleteTests() {
    return notImplemented(TESTS);
  }

  private static ResponseEntity<Map<String, String>> notImplemented(String detail) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", detail));
  }
}
