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

package com.google.adk.plugins.reflectandretry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.common.base.Strings;
import java.util.Map;

/**
 * The text {@link ReflectAndRetryToolPlugin} sends the model after a tool fails.
 *
 * <p>Kept apart from the plugin so that one class decides <em>whether</em> to retry and this one
 * decides only <em>what the model is told</em>. Both messages are ports of the f-strings in
 * adk-python's {@code reflect_retry_tool_plugin.py} and are reproduced verbatim.
 */
final class ReflectionGuidance {

  private static final String ERROR_DETAILS_FORMAT = "%s: %s";

  private static final String RETRY =
      """
      The call to tool `%s` failed.

      **Error Details:**
      ```
      %s
      ```

      **Tool Arguments Used:**
      ```json
      %s
      ```

      **Reflection Guidance:**
      This is retry attempt **%d of %d**. Analyze the error and the arguments you provided. Do not \
      repeat the exact same call. Consider the following before your next attempt:

      1.  **Invalid Parameters**: Does the error suggest that one or more arguments are incorrect, \
      badly formatted, or missing? Review the tool's schema and your arguments.
      2.  **State or Preconditions**: Did a previous step fail or not produce the necessary \
      state/resource for this tool to succeed?
      3.  **Alternative Approach**: Is this the right tool for the job? Could another tool or a \
      different sequence of steps achieve the goal?
      4.  **Simplify the Task**: Can you break the problem down into smaller, simpler steps?
      5.  **Wrong Function Name**: Does the error indicates the tool is not found? Please check \
      again and only use available tools.

      Formulate a new plan based on your analysis and try a corrected or different approach.""";

  private static final String EXHAUSTED =
      """
      The tool `%s` has failed consecutively %d times and the retry limit has been exceeded.

      **Last Error:**
      ```
      %s
      ```

      **Last Arguments Used:**
      ```json
      %s
      ```

      **Final Instruction:**
      **Do not attempt to use the `%s` tool again for this task.** You must now try a different \
      approach. Acknowledge the failure and devise a new strategy, potentially using other \
      available tools or informing the user that the task cannot be completed.""";

  private ReflectionGuidance() {}

  /** Asks the model to analyze the failure and try a corrected call. */
  static String forRetry(
      BaseTool tool, Map<String, Object> toolArgs, Throwable error, int attempt, int maxRetries) {
    return RETRY.formatted(
        tool.name(), errorDetails(error), argsAsJson(toolArgs), attempt, maxRetries);
  }

  /**
   * Tells the model to stop calling the tool and change approach.
   *
   * <p>{@code failures} is the number of consecutive failures observed. adk-python interpolates its
   * configured {@code max_retries} here instead ({@code reflect_retry_tool_plugin.py:357}), which
   * under-reports by one at every setting because the give-up fires on the failure *after* the
   * limit — and at {@code max_retries=0} tells the model the tool "has failed consecutively 0 times
   * and the retry limit has been exceeded". This port reports what actually happened.
   */
  static String forExhausted(
      BaseTool tool, Map<String, Object> toolArgs, Throwable error, int failures) {
    return EXHAUSTED.formatted(
        tool.name(), failures, errorDetails(error), argsAsJson(toolArgs), tool.name());
  }

  private static String errorDetails(Throwable error) {
    return ERROR_DETAILS_FORMAT.formatted(
        error.getClass().getSimpleName(), Strings.nullToEmpty(error.getMessage()));
  }

  /**
   * Pretty-prints the arguments for the guidance message, falling back to the map's own rendering.
   *
   * <p>Never throws: a serialization failure must not mask the tool failure being reported, and the
   * model still needs the echo of the arguments it sent. Mirrors adk-python's {@code
   * json.dumps(..., default=str)}.
   */
  private static String argsAsJson(Map<String, Object> toolArgs) {
    try {
      return JsonBaseModel.getMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(toolArgs);
    } catch (JsonProcessingException e) {
      return String.valueOf(toolArgs);
    }
  }
}
