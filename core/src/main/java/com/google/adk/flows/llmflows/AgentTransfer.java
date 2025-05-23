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
    if (!(baseAgent instanceof LlmAgent)) {
      throw new IllegalArgumentException(
          "Base agent in InvocationContext is not an instance of Agent.");
    }
    LlmAgent agent = (LlmAgent) baseAgent;

    List<BaseAgent> transferTargets = getTransferTargets(agent);
    if (transferTargets.isEmpty()) {
      return Single.just(
          RequestProcessor.RequestProcessingResult.create(request, ImmutableList.of()));
    }

    LlmRequest.Builder builder = request.toBuilder();
    builder.appendInstructions(
        ImmutableList.of(buildTargetAgentsInstructions(agent, transferTargets)));
    Method transferToAgentMethod;
    try {
      transferToAgentMethod =
          AgentTransfer.class.getMethod("transferToAgent", String.class, ToolContext.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    FunctionTool agentTransferTool = FunctionTool.create(transferToAgentMethod);
    agentTransferTool.processLlmRequest(builder, ToolContext.builder(context).build());
    return Single.just(
        RequestProcessor.RequestProcessingResult.create(builder.build(), ImmutableList.of()));
  }

  private String buildTargetAgentsInfo(BaseAgent targetAgent) {
    return String.format(
        "Agent name: %s\nAgent description: %s", targetAgent.name(), targetAgent.description());
  }

  private String buildTargetAgentsInstructions(LlmAgent agent, List<BaseAgent> transferTargets) {
    StringBuilder sb = new StringBuilder();
    sb.append("You have a list of other agents to transfer to:\n");
    for (BaseAgent targetAgent : transferTargets) {
      sb.append(buildTargetAgentsInfo(targetAgent));
      sb.append("\n");
    }
    sb.append(
        "If you are the best to answer the question according to your description, you can answer"
            + " it.\n");
    sb.append(
        "If another agent is better for answering the question according to its description, call"
            + " `transferToAgent` function to transfer the question to that agent. When"
            + " transferring, do not generate any text other than the function call.\n");
    if (agent.parentAgent() != null) {
      sb.append("Your parent agent is ");
      sb.append(agent.parentAgent().name());
      sb.append(
          ".If neither the other agents nor you are best for answering the question according to"
              + " the descriptions, transfer to your parent agent. If you don't have parent agent,"
              + " try answer by yourself.\n");
    }
    return sb.toString();
  }

  private List<BaseAgent> getTransferTargets(LlmAgent agent) {
    List<BaseAgent> transferTargets = new ArrayList<>();
    transferTargets.addAll(agent.subAgents()); // Add all sub-agents

    BaseAgent parent = agent.parentAgent();
    // Agents eligible to transfer must have an LLM-based agent parent.
    if (!(parent instanceof LlmAgent)) {
      return transferTargets;
    }

    if (!agent.disallowTransferToParent()) {
      transferTargets.add(parent);
    }

    if (!agent.disallowTransferToPeers()) {
      for (BaseAgent peerAgent : parent.subAgents()) {
        if (!peerAgent.name().equals(agent.name())) {
          transferTargets.add(peerAgent);
        }
      }
    }

    return transferTargets;
  }

  public static void transferToAgent(String agentName, ToolContext toolContext) {
    EventActions eventActions = toolContext.eventActions();
    toolContext.setActions(eventActions.toBuilder().transferToAgent(agentName).build());
  }
}
