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

package com.google.adk.agents;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/**
 * Offers a {@link AgentMode#TASK} sub-agent to its parent as a tool. A call delegates the task: the
 * parent runs the task agent and answers the call with the task's output, so the tool itself
 * answers nothing. A parent that does not run as a chat or task node gets an error instead.
 */
final class TaskAgentTool extends BaseTool {

  private static final String SUFFIX =
      "\nIMPORTANT: This tool delegates execution to a specialized agent. Do NOT call this tool in"
          + " parallel with any other tools.";
  private static final Schema DEFAULT_INPUT_SCHEMA =
      Schema.builder()
          .type("OBJECT")
          .properties(
              ImmutableMap.of(
                  "request",
                  Schema.builder()
                      .type("STRING")
                      .description("Detailed instructions or context for the task sub-agent.")
                      .build()))
          .required(ImmutableList.of("request"))
          .build();

  private final LlmAgent agent;

  TaskAgentTool(LlmAgent agent) {
    super(agent.name(), agent.description());
    this.agent = agent;
  }

  /** Returns the task agent this tool delegates to. */
  LlmAgent agent() {
    return agent;
  }

  /** Outside a workflow the agent is a transfer target instead, so the model is not offered it. */
  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    if (toolContext.invocationContext().nodePath().isEmpty()) {
      return Completable.complete();
    }
    return super.processLlmRequest(llmRequestBuilder, toolContext);
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description((Strings.nullToEmpty(description()) + SUFFIX).trim())
            .parameters(agent.inputSchema().orElse(DEFAULT_INPUT_SCHEMA))
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    InvocationContext context = toolContext.invocationContext();
    boolean runsDelegations =
        context
            .agentMode(context.agent())
            .filter(mode -> mode == AgentMode.CHAT || mode == AgentMode.TASK)
            .isPresent();
    if (runsDelegations) {
      return Single.just(ImmutableMap.of());
    }
    return Single.just(
        ImmutableMap.of(
            "result",
            "Error running sub-agent: Task agent '"
                + agent.name()
                + "' can only run as a tool of a chat or task agent in a workflow node."));
  }
}
