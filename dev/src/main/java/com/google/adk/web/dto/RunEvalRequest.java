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
import java.util.List;

/**
 * DTO for POST /apps/{appName}/eval_sets/{evalSetId}/run-eval requests. Contains information for
 * running evaluations.
 */
public class RunEvalRequest {
  @JsonProperty("evalIds")
  public List<String> evalIds;

  @JsonProperty("evalMetrics")
  public List<String> evalMetrics;

  public RunEvalRequest() {}

  public List<String> getEvalIds() {
    return evalIds;
  }

  public List<String> getEvalMetrics() {
    return evalMetrics;
  }
}
