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

package com.google.adk.planners;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Optional;

/**
 * Base interface for all planners.
 *
 * <p>The planner allows the agent to generate plans for the queries to guide its action.
 */
public interface BasePlanner {

  /**
   * Builds the system instruction to be appended to the LLM request for planning.
   *
   * @param readonlyContext The readonly context of the invocation.
   * @param llmRequest The LLM request. Readonly.
   * @return The planning system instruction, or empty if no instruction is needed.
   */
  Optional<String> buildPlanningInstruction(ReadonlyContext readonlyContext, LlmRequest llmRequest);

  /**
   * Processes the LLM response for planning.
   *
   * @param callbackContext The callback context of the invocation. Anything the planner writes to
   *     its state becomes a state delta on the invocation.
   * @param responseParts The LLM response parts. Readonly.
   * @return The processed response parts, or empty if no processing is needed.
   */
  Optional<ImmutableList<Part>> processPlanningResponse(
      CallbackContext callbackContext, List<Part> responseParts);
}
