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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/**
 * The tool a step of a live {@link SequentialAgent} calls when its part of the task is done. A live
 * run has no natural end, so this call ends the step and lets the next one start, as in ADK Python.
 */
final class TaskCompletedTool extends BaseTool {
  static final String NAME = "task_completed";

  /** Added to a step's instructions along with the tool, worded as in ADK Python. */
  static final String INSTRUCTION =
      "If you finished the user's request according to its description, call the "
          + NAME
          + " function to exit so the next agents can take over. When calling this function, do"
          + " not generate any text other than the function call.";

  /**
   * Whether {@code agent} runs as a live step of a {@link SequentialAgent}, which gets this tool
   * unless it has its own.
   */
  static boolean offeredTo(InvocationContext context, LlmAgent agent) {
    return context.liveRequestQueue().isPresent() && agent.parentAgent() instanceof SequentialAgent;
  }

  TaskCompletedTool() {
    super(NAME, "Signals that the agent has successfully completed the user's question or task.");
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder().name(name()).description(description()).build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    return Single.just(ImmutableMap.of("result", "Task completion signaled."));
  }
}
