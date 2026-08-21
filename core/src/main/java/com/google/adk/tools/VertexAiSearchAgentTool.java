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

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.common.collect.ImmutableList;

/**
 * A tool that wraps a sub-agent that only uses vertex_ai_search tool.
 *
 * <p>This is a workaround to support using {@link VertexAiSearchTool} tool with other tools.
 */
public class VertexAiSearchAgentTool extends AgentTool {

  public static VertexAiSearchAgentTool create(
      BaseLlm model, VertexAiSearchTool vertexAiSearchTool) {
    LlmAgent vertexAiSearchAgent =
        LlmAgent.builder()
            .name("vertex_ai_search_agent")
            .model(model)
            .description(
                "An agent for performing Vertex AI search using the `vertex_ai_search` tool")
            .instruction(
                "        You are a specialized Vertex AI search agent.\n"
                    + "\n"
                    + "        When given a search query, use the `vertex_ai_search` tool to find"
                    + " the related information.")
            .tools(ImmutableList.of(vertexAiSearchTool))
            .build();
    return new VertexAiSearchAgentTool(vertexAiSearchAgent);
  }

  protected VertexAiSearchAgentTool(LlmAgent agent) {
    super(agent, false);
  }
}
