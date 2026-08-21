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

package com.google.adk.tools;

import com.google.adk.tools.Annotations.Schema;

/** Tool for exiting execution of {@link com.google.adk.agents.LoopAgent}. */
public final class ExitLoopTool {
  public static final FunctionTool INSTANCE = FunctionTool.create(ExitLoopTool.class, "exitLoop");

  /**
   * Exit the {@link com.google.adk.agents.LoopAgent} execution.
   *
   * <p>Usage example in an LlmAgent:
   *
   * <pre>{@code
   * LlmAgent subAgent = LlmAgent.builder()
   *     .addTool(ExitLoopTool.INSTANCE)
   *     .build();
   * }</pre>
   *
   * <p>The @Schema name and description is consistent with the Python version.
   *
   * <p>Refer to:
   * https://github.com/google/adk-python/blob/main/src/google/adk/tools/exit_loop_tool.py
   */
  @Schema(
      name = "exit_loop",
      description = "Exits the loop.\n\nCall this function only when you are instructed to do so.")
  public static void exitLoop(ToolContext toolContext) {
    toolContext.setActions(
        toolContext.actions().toBuilder().escalate(true).skipSummarization(true).build());
  }

  private ExitLoopTool() {}
}
