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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link FunctionTool} that hands the conversation to another agent, restricted to the agents
 * that exist.
 *
 * <p>The {@code agent_name} parameter carries those agents as an enum in its schema, so a model
 * cannot transfer to an agent it was never offered.
 *
 * <p>This is the one definition of the {@code transfer_to_agent} tool. The auto flow's transfer
 * request processor builds one of these per turn, from the agents that turn can reach, so what the
 * model is told about transferring is written in exactly one place.
 */
public final class TransferToAgentTool extends FunctionTool {

  private static final String AGENT_NAME_PARAMETER = "agent_name";

  private static final Method TRANSFER_TO_AGENT = transferToAgentMethod();

  private final Optional<FunctionDeclaration> declaration;

  /**
   * Returns a transfer tool that offers the model exactly {@code agentNames} to transfer to.
   *
   * @param agentNames the valid agent names that can be transferred to.
   */
  public static TransferToAgentTool create(List<String> agentNames) {
    return new TransferToAgentTool(ImmutableList.copyOf(agentNames));
  }

  private TransferToAgentTool(ImmutableList<String> agentNames) {
    super(/* instance= */ null, TRANSFER_TO_AGENT, /* isLongRunning= */ false);
    this.declaration = super.declaration().map(decl -> restrictAgentName(decl, agentNames));
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return declaration;
  }

  /**
   * Returns {@code declaration} with its agent name parameter constrained to {@code agentNames}.
   */
  private static FunctionDeclaration restrictAgentName(
      FunctionDeclaration declaration, ImmutableList<String> agentNames) {
    Schema parameters = declaration.parameters().orElse(null);
    if (parameters == null) {
      return declaration;
    }
    Map<String, Schema> properties = parameters.properties().orElse(ImmutableMap.of());
    Schema agentName = properties.get(AGENT_NAME_PARAMETER);
    if (agentName == null) {
      return declaration;
    }
    Map<String, Schema> restricted = new LinkedHashMap<>(properties);
    restricted.put(AGENT_NAME_PARAMETER, agentName.toBuilder().enum_(agentNames).build());
    return declaration.toBuilder()
        .parameters(parameters.toBuilder().properties(restricted).build())
        .build();
  }

  private static Method transferToAgentMethod() {
    try {
      return TransferToAgentTool.class.getMethod(
          "transferToAgent", String.class, ToolContext.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  @Annotations.Schema(
      name = "transfer_to_agent",
      description =
          """
          Transfer the query to another agent.

            Use this tool to hand off control to another agent that is more suitable to
            answer the user's query according to the agent's description.

            Args:
              agent_name: the agent name to transfer to.
            \
          """)
  public static void transferToAgent(
      @Annotations.Schema(name = AGENT_NAME_PARAMETER) String agentName,
      @Annotations.Schema(optional = true) ToolContext toolContext) {
    toolContext.setActions(toolContext.actions().toBuilder().transferToAgent(agentName).build());
  }
}
