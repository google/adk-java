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

import com.google.adk.SchemaUtils;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.DynamicNodeFailedException;
import com.google.adk.workflow.RunNodeConfig;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Offers a {@link AgentMode#SINGLE_TURN} sub-agent to its parent as a tool: a call runs the
 * sub-agent as a child node of the parent's node, on its own sub-branch, and answers with its
 * output.
 */
final class SingleTurnAgentTool extends BaseTool {

  private final LlmAgent agent;
  private final Optional<FunctionDeclaration> declaration;

  SingleTurnAgentTool(LlmAgent agent) {
    super(agent.name(), agent.description());
    this.agent = agent;
    this.declaration = AgentTool.create(agent).declaration();
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return declaration;
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
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    Object nodeInput = args.get("request");
    if (agent.inputSchema().isPresent()) {
      try {
        SchemaUtils.validateMapOnSchema(args, agent.inputSchema().get(), /* isInput= */ true);
      } catch (IllegalArgumentException e) {
        return Single.just(result("Error validating input: " + e.getMessage()));
      }
      nodeInput = args;
    }
    Optional<Context> parentNode = toolContext.invocationContext().nodeContext();
    if (parentNode.isEmpty()) {
      return Single.just(
          result(
              "Error running sub-agent: single-turn agent '"
                  + agent.name()
                  + "' can only run as a tool of the agent that a workflow node runs."));
    }
    Object input = nodeInput;
    // A child that waits fails this call with an internal error, so the parent's node waits too.
    return Single.defer(
            () -> {
              // Built here so a call ID that can't be a run ID answers the call with an error.
              RunNodeConfig config =
                  RunNodeConfig.builder()
                      .runId(toolContext.functionCallId().orElse(null))
                      .useSubBranch(true)
                      .build();
              return parentNode
                  .get()
                  .runNode(agent, input, config)
                  .map(SingleTurnAgentTool::asResponse)
                  .defaultIfEmpty(result(null));
            })
        .onErrorResumeNext(e -> {
          // The sub-agent's own failure, or a call ID that can't be a run ID, answers the call.
          if (e instanceof DynamicNodeFailedException failure) {
            return Single.just(result("Error running sub-agent: " + failure.error().getMessage()));
          }
          if (e instanceof IllegalArgumentException) {
            return Single.just(result("Error running sub-agent: " + e.getMessage()));
          }
          return Single.error(e);
        });
  }

  @SuppressWarnings("unchecked") // A map output is a JSON object.
  private static Map<String, Object> asResponse(Object output) {
    return output instanceof Map<?, ?> map ? (Map<String, Object>) map : result(output);
  }

  private static Map<String, Object> result(@Nullable Object value) {
    return Collections.singletonMap("result", value);
  }
}
