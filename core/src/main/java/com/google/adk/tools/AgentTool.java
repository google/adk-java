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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.SchemaUtils;
import com.google.adk.agents.AgentMode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.BaseAgentConfig;
import com.google.adk.agents.ConfigAgentUtils;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.State;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AgentTool implements a tool that allows an agent to call another agent.
 *
 * <p>An {@link LlmAgent} whose tree sets an agent mode runs as a workflow node, so its task and
 * single-turn sub-agents work; any other agent runs outside a workflow, so its tree may not set a
 * mode. The agent itself can't be a single-turn or task agent.
 */
public class AgentTool extends BaseTool {

  // An App needs a name that is an identifier, while the nested runner uses the caller's.
  private static final String NESTED_APP_NAME = "agent_tool";

  private final BaseAgent agent;
  private final boolean skipSummarization;
  private final boolean includePlugins;

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
    return AgentTool.create(
        agent,
        args.getOrDefault("skipSummarization", false).booleanValue(),
        args.getOrDefault("includePlugins", false).booleanValue());
  }

  public static AgentTool create(
      BaseAgent agent, boolean skipSummarization, boolean includePlugins) {
    return new AgentTool(agent, skipSummarization, includePlugins);
  }

  public static AgentTool create(BaseAgent agent, boolean skipSummarization) {
    return new AgentTool(agent, skipSummarization, /* includePlugins= */ false);
  }

  public static AgentTool create(BaseAgent agent) {
    return new AgentTool(agent, /* skipSummarization= */ false, /* includePlugins= */ false);
  }

  protected AgentTool(BaseAgent agent, boolean skipSummarization) {
    this(agent, skipSummarization, /* includePlugins= */ false);
  }

  protected AgentTool(BaseAgent agent, boolean skipSummarization, boolean includePlugins) {
    super(agent.name(), agent.description());
    requireRunnableRoot(agent);
    this.agent = agent;
    this.skipSummarization = skipSummarization;
    this.includePlugins = includePlugins;
  }

  /**
   * Throws {@link IllegalArgumentException} if this tool can't run {@code agent} or return its
   * output.
   */
  private static void requireRunnableRoot(BaseAgent agent) {
    boolean setsMode = setsMode(agent);
    checkArgument(
        agent instanceof LlmAgent || !setsMode,
        "AgentTool runs agent modes only under an LlmAgent, but '%s' is not one.",
        agent.name());
    // A single-turn agent runs only as its parent's child node; as a root it would lose its mode.
    checkArgument(
        !hasMode(agent, AgentMode.SINGLE_TURN),
        "AgentTool can't run the single-turn agent '%s' as a root.",
        agent.name());
    // A task ends in a finish_task response with no text, so its output would be lost.
    checkArgument(
        !hasMode(agent, AgentMode.TASK),
        "AgentTool can't run the task agent '%s'; add it to a chat agent's subAgents.",
        agent.name());
    // Agents in a tree with modes run as workflow nodes, whose paths use '.' as a separator.
    if (setsMode) {
      ImmutableList<String> dottedNames =
          agentTree(agent).stream()
              .map(BaseAgent::name)
              .filter(name -> name.contains("."))
              .sorted()
              .distinct()
              .collect(toImmutableList());
      checkArgument(
          dottedNames.isEmpty(),
          "Agents %s run as workflow nodes, so their names must not contain '.'.",
          dottedNames);
    }
  }

  /** Returns {@code agent} and every agent below it. */
  private static ImmutableList<BaseAgent> agentTree(BaseAgent agent) {
    ImmutableList.Builder<BaseAgent> tree = ImmutableList.builder();
    tree.add(agent);
    if (agent.subAgents() != null) {
      agent.subAgents().forEach(subAgent -> tree.addAll(agentTree(subAgent)));
    }
    return tree.build();
  }

  private static boolean hasMode(BaseAgent agent, AgentMode mode) {
    return modeOf(agent).equals(Optional.of(mode));
  }

  /** Returns whether {@code agent} or an agent below it sets a mode. */
  private static boolean setsMode(BaseAgent agent) {
    return modeOf(agent).isPresent()
        || (agent.subAgents() != null && agent.subAgents().stream().anyMatch(AgentTool::setsMode));
  }

  private static Optional<AgentMode> modeOf(BaseAgent agent) {
    // A mocked LlmAgent returns null from mode().
    return agent instanceof LlmAgent llmAgent && llmAgent.mode() != null
        ? llmAgent.mode()
        : Optional.empty();
  }

  @VisibleForTesting
  public BaseAgent getAgent() {
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

    // The genai FunctionDeclaration builder uses Optional.of() internally, which NPEs on a null
    // description. Coerce null to an empty string. This matches the Python, Go, and Kotlin ports,
    // which send an empty description when the agent has none.
    String desc = Strings.nullToEmpty(this.description());

    FunctionDeclaration.Builder builder =
        FunctionDeclaration.builder().description(desc).name(this.name());

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
      Map<String, Object> input = SchemaUtils.withDefaults(args, agentInputSchema.get());
      try {
        content =
            Content.fromParts(Part.fromText(JsonBaseModel.getMapper().writeValueAsString(input)));
      } catch (JsonProcessingException e) {
        return Single.error(new RuntimeException("Error serializing tool arguments to JSON", e));
      }
    } else {
      Object input = args.get("request");
      content = Content.fromParts(Part.fromText(input.toString()));
    }

    InvocationContext parentContext = toolContext.invocationContext();
    ImmutableList<Plugin> plugins =
        this.includePlugins ? ImmutableList.of(parentContext.pluginManager()) : ImmutableList.of();
    // Set before app(), so the runner keeps the caller's agent name as its app name.
    Runner.Builder runnerBuilder = Runner.builder().appName(toolContext.agentName());
    // A tree with modes runs as a workflow node, as Python's AgentTool runs a root LlmAgent.
    if (agent instanceof LlmAgent llmAgent && setsMode(llmAgent)) {
      runnerBuilder.app(
          App.builder().name(NESTED_APP_NAME).rootNode(llmAgent).plugins(plugins).build());
    } else {
      runnerBuilder.agent(this.agent).plugins(plugins);
    }
    // The nested run inherits the caller's scheduler, clock, and id provider.
    Runner runner =
        runnerBuilder
            .memoryService(new InMemoryMemoryService())
            .scheduler(parentContext.scheduler())
            .instantSource(parentContext.instantSource())
            .uuidProvider(parentContext.uuidProvider())
            .build();
    // Follow the caller's RunConfig but run unary: only the last event becomes the result.
    RunConfig callerRunConfig = parentContext.runConfig();
    RunConfig runConfig =
        callerRunConfig.streamingMode() == StreamingMode.NONE
            ? callerRunConfig
            : callerRunConfig.toBuilder().streamingMode(StreamingMode.NONE).build();
    return runner
        .sessionService()
        .createSession(toolContext.agentName(), "tmp-user", toolContext.state(), null)
        .flatMapPublisher(
            session -> runner.runAsync(session.userId(), session.id(), content, runConfig))
        .doOnNext(event -> {
          if (event.actions() != null
              && event.actions().stateDelta() != null
              && !event.actions().stateDelta().isEmpty()) {
            updateState(event.actions().stateDelta(), toolContext.state());
          }
        })
        .lastElement()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .map(optionalLastEvent -> {
          if (optionalLastEvent.isEmpty()) {
            return ImmutableMap.of();
          }
          Event lastEvent = optionalLastEvent.get();
          Optional<String> outputText = lastEvent.content().map(Content::text);

          if (outputText.isEmpty()) {
            return ImmutableMap.of();
          }
          String output = outputText.get();

          Optional<Schema> agentOutputSchema = getOutputSchema(agent);

          if (agentOutputSchema.isPresent()) {
            Schema schema = agentOutputSchema.get();
            if (SchemaUtils.describesObject(schema)) {
              return SchemaUtils.validateOutputSchema(output, schema);
            }
            // Like a function tool's non-map result; the value may be null.
            return Collections.singletonMap("result", SchemaUtils.validateOutput(output, schema));
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
