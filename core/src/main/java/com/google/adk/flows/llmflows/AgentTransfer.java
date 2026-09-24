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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** {@link RequestProcessor} that handles agent transfer for LLM flow. */
public final class AgentTransfer implements RequestProcessor {

  public AgentTransfer() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    BaseAgent baseAgent = context.agent();
    if (!(baseAgent instanceof LlmAgent agent)) {
      throw new IllegalArgumentException(
          "Base agent in InvocationContext is not an instance of Agent.");
    }

    List<BaseAgent> targets = transferTargets(agent);
    if (targets.isEmpty()) {
      return Single.just(
          RequestProcessor.RequestProcessingResult.create(request, ImmutableList.of()));
    }

    LlmRequest.Builder builder =
        request.toBuilder()
            .appendInstructions(ImmutableList.of(buildTargetAgentsInstructions(agent, targets)));

    FunctionTool agentTransferTool = createTransferToAgentTool();
    agentTransferTool.processLlmRequest(builder, ToolContext.builder(context).build());
    return Single.just(
        RequestProcessor.RequestProcessingResult.create(builder.build(), ImmutableList.of()));
  }

  private FunctionTool createTransferToAgentTool() {
    Method transferToAgentMethod;
    try {
      transferToAgentMethod =
          AgentTransfer.class.getMethod("transferToAgent", String.class, ToolContext.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    return FunctionTool.create(transferToAgentMethod);
  }

  /** Builds a string with the target agent’s name and description. */
  private String buildTargetAgentsInfo(BaseAgent targetAgent) {
    return String.format(
        "\nAgent name: %s\nAgent description: %s", targetAgent.name(), targetAgent.description());
  }

  /** Builds LLM instructions about when and how to transfer to another agent. */
  private String buildTargetAgentsInstructions(LlmAgent agent, List<BaseAgent> transferTargets) {
    StringBuilder sb = new StringBuilder();
    sb.append("\nYou have a list of other agents to transfer to:");
    sb.append("\n\n");
    List<String> agentNames = new ArrayList<>();
    for (BaseAgent targetAgent : transferTargets) {
      agentNames.add("`" + targetAgent.name() + "`");
      sb.append(buildTargetAgentsInfo(targetAgent));
      sb.append("\n\n");
    }
    sb.append(
        """

        If you are the best to answer the question according to your description, you
        can answer it.

        If another agent is better for answering the question according to its
        description, call `transfer_to_agent` function to transfer the
        question to that agent. When transferring, do not generate any text other than
        the function call.

        **NOTE**: the only available agents for `transfer_to_agent` function are\
        """);
    sb.append(" ");
    agentNames.sort(String::compareTo);
    sb.append(String.join(", ", agentNames));
    sb.append(".\n");

    if (agent.parentAgent() != null && !agent.disallowTransferToParent()) {
      sb.append(
          "\n"
              + "If neither you nor the other agents are best for the question, transfer to your"
              + " parent agent ");
      sb.append(agent.parentAgent().name());
      sb.append(".\n");
    }

    return sb.toString();
  }

  /**
   * Returns the agents {@code agent} may transfer to: its sub-agents, plus its parent and peers
   * unless disallowed. Transfer to a parent or peer needs an {@link LlmAgent} parent. Order is
   * load-bearing: it is the order the target list is rendered to the model in. {@code LlmAgent}
   * keeps the same rule for its own use, since it cannot reach this one.
   */
  static ImmutableList<BaseAgent> transferTargets(LlmAgent agent) {
    List<BaseAgent> targets = new ArrayList<>(agent.subAgents());
    BaseAgent parent = agent.parentAgent();
    if (!(parent instanceof LlmAgent)) {
      return ImmutableList.copyOf(targets);
    }
    if (!agent.disallowTransferToParent()) {
      targets.add(parent);
    }
    if (!agent.disallowTransferToPeers()) {
      for (BaseAgent peer : parent.subAgents()) {
        if (!peer.name().equals(agent.name())) {
          targets.add(peer);
        }
      }
    }
    return ImmutableList.copyOf(targets);
  }

  @Schema(
      name = "transfer_to_agent",
      description =
          """
          Transfer the question to another agent.

            This tool hands off control to another agent when it's more suitable to
            answer the user's question according to the agent's description.

            Args:
              agent_name: the agent name to transfer to.
            \
          """)
  public static void transferToAgent(
      @Schema(name = "agent_name") String agentName,
      @Schema(optional = true) ToolContext toolContext) {
    EventActions eventActions = toolContext.eventActions();
    toolContext.setActions(eventActions.toBuilder().transferToAgent(agentName).build());
  }
}
