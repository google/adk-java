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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.SchemaUtils;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.BaseAgentConfig;
import com.google.adk.agents.ConfigAgentUtils;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.State;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** AgentTool implements a tool that allows an agent to call another agent. */
public class AgentTool extends BaseTool {

  private final BaseAgent agent;
  private final boolean skipSummarization;

  public static BaseTool fromConfig(ToolArgsConfig args, String configAbsPath)
      throws ConfigurationException {
    var agentRef = args.getOrEmpty("agent", new TypeReference<BaseAgentConfig.AgentRefConfig>() {});
    if (agentRef.isEmpty()) {
      throw new ConfigurationException("AgentTool config requires 'agent' argument.");
    }

    ImmutableList<BaseAgent> resolvedAgents =
        ConfigAgentUtils.resolveSubAgents(ImmutableList.of(agentRef.get()), configAbsPath);

    if (resolvedAgents.isEmpty()) {
      throw new ConfigurationException("Failed to resolve agent.");
    }

    BaseAgent agent = resolvedAgents.get(0);
    return AgentTool.create(agent, args.getOrDefault("skipSummarization", false).booleanValue());
  }

  public static AgentTool create(BaseAgent agent, boolean skipSummarization) {
    return new AgentTool(agent, skipSummarization);
  }

  public static AgentTool create(BaseAgent agent) {
    return new AgentTool(agent, false);
  }

  protected AgentTool(BaseAgent agent, boolean skipSummarization) {
    super(agent.name(), agent.description());
    this.agent = agent;
    this.skipSummarization = skipSummarization;
  }

  @VisibleForTesting
  BaseAgent getAgent() {
    return agent;
  }

  private Optional<Schema> getInputSchema(BaseAgent agent) {
    BaseAgent currentAgent = agent;
    while (true) {
      if (currentAgent instanceof LlmAgent llmAgent) {
        return llmAgent.inputSchema();
      }
      List<? extends BaseAgent> subAgents = currentAgent.subAgents();
      if (subAgents == null || subAgents.isEmpty()) {
        return Optional.empty();
      }
      // For composite agents, check the first sub-agent.
      currentAgent = subAgents.get(0);
    }
  }

  private Optional<Schema> getOutputSchema(BaseAgent agent) {
    BaseAgent currentAgent = agent;
    while (true) {
      if (currentAgent instanceof LlmAgent llmAgent) {
        return llmAgent.outputSchema();
      }
      List<? extends BaseAgent> subAgents = currentAgent.subAgents();
      if (subAgents == null || subAgents.isEmpty()) {
        return Optional.empty();
      }
      // For composite agents, check the last sub-agent.
      currentAgent = subAgents.get(subAgents.size() - 1);
    }
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    FunctionDeclaration.Builder builder =
        FunctionDeclaration.builder().description(this.description()).name(this.name());

    Optional<Schema> agentInputSchema = getInputSchema(agent);

    if (agentInputSchema.isPresent()) {
      builder.parameters(agentInputSchema.get());
    } else {
      builder.parameters(
          Schema.builder()
              .type("OBJECT")
              .properties(ImmutableMap.of("request", Schema.builder().type("STRING").build()))
              .required(ImmutableList.of("request"))
              .build());
    }
    return Optional.of(builder.build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {

    if (this.skipSummarization) {
      // Mutate EventActions in-place to ensure object references are maintained.
      toolContext.actions().setSkipSummarization(true);
    }

    Optional<Schema> agentInputSchema = getInputSchema(agent);

    final Content content;
    if (agentInputSchema.isPresent()) {
      SchemaUtils.validateMapOnSchema(args, agentInputSchema.get(), true);
      try {
        content =
            Content.fromParts(Part.fromText(JsonBaseModel.getMapper().writeValueAsString(args)));
      } catch (JsonProcessingException e) {
        return Single.error(
            new RuntimeException("Error serializing tool arguments to JSON: " + args, e));
      }
    } else {
      Object input = args.get("request");
      content = Content.fromParts(Part.fromText(input.toString()));
    }

    Runner runner = new InMemoryRunner(this.agent, toolContext.agentName());
    // Session state is final, can't update to toolContext state
    // session.toBuilder().setState(toolContext.getState());
    return runner
        .sessionService()
        .createSession(toolContext.agentName(), "tmp-user", toolContext.state(), null)
        .flatMapPublisher(session -> runner.runAsync(session.userId(), session.id(), content))
        .lastElement()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .map(
            optionalLastEvent -> {
              if (optionalLastEvent.isEmpty()) {
                return ImmutableMap.of();
              }
              Event lastEvent = optionalLastEvent.get();
              Optional<String> outputText = lastEvent.content().map(Content::text);

              // Forward state delta to parent session.
              if (lastEvent.actions() != null
                  && lastEvent.actions().stateDelta() != null
                  && !lastEvent.actions().stateDelta().isEmpty()) {
                updateState(lastEvent.actions().stateDelta(), toolContext.state());
              }

              if (outputText.isEmpty()) {
                return ImmutableMap.of();
              }
              String output = outputText.get();

              Optional<Schema> agentOutputSchema = getOutputSchema(agent);

              if (agentOutputSchema.isPresent()) {
                return SchemaUtils.validateOutputSchema(output, agentOutputSchema.get());
              } else {
                return ImmutableMap.of("result", output);
              }
            });
  }

  /**
   * Updates the given state map with the state delta.
   *
   * <p>If a value in the delta is {@link State#REMOVED}, the key is removed from the state map.
   * Otherwise, the key-value pair is put into the state map. This method does not distinguish
   * between session, app, and user state based on key prefixes.
   *
   * @param state The state map to update.
   */
  private void updateState(Map<String, Object> stateDelta, Map<String, Object> state) {
    stateDelta.forEach(
        (key, value) -> {
          if (value == State.REMOVED) {
            state.remove(key);
          } else {
            state.put(key, value);
          }
        });
  }
}
