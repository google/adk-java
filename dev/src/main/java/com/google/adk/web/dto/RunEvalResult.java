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

package com.google.adk.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.JsonBaseModel;
import java.util.List;

/**
 * DTO for the response of POST /apps/{appName}/eval_sets/{evalSetId}/run-eval. Contains the results
 * of an evaluation run.
 */
public class RunEvalResult extends JsonBaseModel {
  @JsonProperty("appName")
  public String appName;

  @JsonProperty("evalSetId")
  public String evalSetId;

  @JsonProperty("evalId")
  public String evalId;

  @JsonProperty("finalEvalStatus")
  public String finalEvalStatus;

  @JsonProperty("evalMetricResults")
  public List<List<Object>> evalMetricResults;

  @JsonProperty("sessionId")
  public String sessionId;

  /**
   * Constructs a RunEvalResult.
   *
   * @param appName The application name.
   * @param evalSetId The evaluation set ID.
   * @param evalId The evaluation ID.
   * @param finalEvalStatus The final status of the evaluation.
   * @param evalMetricResults The results for each metric.
   * @param sessionId The session ID associated with the evaluation.
   */
  public RunEvalResult(
      String appName,
      String evalSetId,
      String evalId,
      String finalEvalStatus,
      List<List<Object>> evalMetricResults,
      String sessionId) {
    this.appName = appName;
    this.evalSetId = evalSetId;
    this.evalId = evalId;
    this.finalEvalStatus = finalEvalStatus;
    this.evalMetricResults = evalMetricResults;
    this.sessionId = sessionId;
  }

  public RunEvalResult() {}
}
