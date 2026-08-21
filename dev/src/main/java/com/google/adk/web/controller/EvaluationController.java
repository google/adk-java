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

import com.google.adk.web.dto.AddSessionToEvalSetRequest;
import com.google.adk.web.dto.RunEvalRequest;
import com.google.adk.web.dto.RunEvalResult;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Controller handling evaluation-related endpoints (mostly placeholder implementations). */
@RestController
public class EvaluationController {

  private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

  /** Placeholder for creating an evaluation set. */
  @PostMapping("/apps/{appName}/eval_sets/{evalSetId}")
  public ResponseEntity<Object> createEvalSet(
      @PathVariable String appName, @PathVariable String evalSetId) {
    log.warn("Endpoint /apps/{}/eval_sets/{} (POST) is not implemented", appName, evalSetId);
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(Collections.singletonMap("message", "Eval set creation not implemented"));
  }

  /** Placeholder for listing evaluation sets. */
  @GetMapping("/apps/{appName}/eval_sets")
  public List<String> listEvalSets(@PathVariable String appName) {
    log.warn("Endpoint /apps/{}/eval_sets (GET) is not implemented", appName);
    return Collections.emptyList();
  }

  /** Placeholder for adding a session to an evaluation set. */
  @PostMapping("/apps/{appName}/eval_sets/{evalSetId}/add-session")
  public ResponseEntity<Object> addSessionToEvalSet(
      @PathVariable String appName,
      @PathVariable String evalSetId,
      @RequestBody AddSessionToEvalSetRequest req) {
    log.warn(
        "Endpoint /apps/{}/eval_sets/{}/add-session is not implemented. Request details:"
            + " evalId={}, sessionId={}, userId={}",
        appName,
        evalSetId,
        req.getEvalId(),
        req.getSessionId(),
        req.getUserId());
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(Collections.singletonMap("message", "Adding session to eval set not implemented"));
  }

  /** Placeholder for listing evaluations within an evaluation set. */
  @GetMapping("/apps/{appName}/eval_sets/{evalSetId}/evals")
  public List<String> listEvalsInEvalSet(
      @PathVariable String appName, @PathVariable String evalSetId) {
    log.warn("Endpoint /apps/{}/eval_sets/{}/evals is not implemented", appName, evalSetId);
    return Collections.emptyList();
  }

  /** Placeholder for running evaluations. */
  @PostMapping("/apps/{appName}/eval_sets/{evalSetId}/run-eval")
  public List<RunEvalResult> runEval(
      @PathVariable String appName,
      @PathVariable String evalSetId,
      @RequestBody RunEvalRequest req) {
    log.warn(
        "Endpoint /apps/{}/eval_sets/{}/run-eval is not implemented. Request details: evalIds={},"
            + " evalMetrics={}",
        appName,
        evalSetId,
        req.getEvalIds(),
        req.getEvalMetrics());
    return Collections.emptyList();
  }

  /**
   * Gets a specific evaluation result. (STUB - Not Implemented)
   *
   * @param appName The application name.
   * @param evalResultId The evaluation result ID.
   * @return A ResponseEntity indicating the endpoint is not implemented.
   */
  @GetMapping("/apps/{appName}/eval_results/{evalResultId}")
  public ResponseEntity<Object> getEvalResult(
      @PathVariable String appName, @PathVariable String evalResultId) {
    log.warn("Endpoint /apps/{}/eval_results/{} (GET) is not implemented", appName, evalResultId);
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(Collections.singletonMap("message", "Get evaluation result not implemented"));
  }

  /**
   * Lists all evaluation results for an app. (STUB - Not Implemented)
   *
   * @param appName The application name.
   * @return An empty list, as this endpoint is not implemented.
   */
  @GetMapping("/apps/{appName}/eval_results")
  public List<String> listEvalResults(@PathVariable String appName) {
    log.warn("Endpoint /apps/{}/eval_results (GET) is not implemented", appName);
    return Collections.emptyList();
  }
}
