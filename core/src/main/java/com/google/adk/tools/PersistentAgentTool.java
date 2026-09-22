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
import com.google.adk.JsonBaseModel;
import com.google.adk.SchemaUtils;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An ADK {@link BaseTool} that wraps another {@link BaseAgent}, allowing it to be invoked like a
 * standard tool within an orchestrator agent.
 *
 * <p>Unlike {@link AgentTool}, this implementation uses {@link Runner} configured with externally
 * provided {@link BaseSessionService} and {@link BaseArtifactService} to execute the wrapped agent.
 * This enables integration with persistent session services for improved observability and
 * debugging in production environments, as all underlying agent events are recorded in the calling
 * agent's session.
 *
 * <p>This tool performs the following steps during {@link #runAsync}:
 *
 * <ol>
 *   <li>Executes the wrapped agent using {@link Runner}, running in the calling agent's session
 *       context.
 *   <li>Collects all {@link Event}s produced by the execution.
 *   <li>Iterates through all collected events and applies any state changes ({@link State#REMOVED}
 *       or updates) found in {@code event.actions().stateDelta()} to the {@code toolContext} of the
 *       calling agent.
 *   <li>Returns a map containing the result of the agent execution, plus a {@code trace} field
 *       which holds the complete list of {@link Event}s from the execution.
 * </ol>
 */
public class PersistentAgentTool extends BaseTool {

  private final BaseAgent agent;
  private final String appName;
  private final BaseSessionService sessionService;
  private final BaseArtifactService artifactService;

  /**
   * Creates a new instance of {@link PersistentAgentTool}.
   *
   * @param agent The agent instance to wrap and execute as a tool.
   * @param appName The application name to use for logging and session management, passed to {@link
   *     Runner}.
   * @param sessionService The session service to use for agent execution via {@link Runner}.
   * @param artifactService The artifact service to use for agent execution via {@link Runner}.
   */
  public static PersistentAgentTool create(
      BaseAgent agent,
      String appName,
      BaseSessionService sessionService,
      BaseArtifactService artifactService) {
    return new PersistentAgentTool(agent, appName, sessionService, artifactService);
  }

  /**
   * Creates a new instance of {@link PersistentAgentTool}.
   *
   * @param agent The agent instance to wrap and execute as a tool.
   * @param appName The application name to use for logging and session management, passed to {@link
   *     Runner}.
   * @param sessionService The session service to use for agent execution via {@link Runner}.
   * @param artifactService The artifact service to use for agent execution via {@link Runner}.
   */
  protected PersistentAgentTool(
      BaseAgent agent,
      String appName,
      BaseSessionService sessionService,
      BaseArtifactService artifactService) {
    super(agent.name(), agent.description());
    this.agent = agent;
    this.appName = appName;
    this.sessionService = sessionService;
    this.artifactService = artifactService;
  }

  /**
   * Tries to heuristically find an input schema defined on an {@link LlmAgent} contained within the
   * wrapped agent structure by traversing down through the *first* sub-agent at each level.
   *
   * <p>This is used to determine if the tool should accept structured input matching a schema, or a
   * simple {@code request} string.
   */
  private Optional<Schema> getInputSchema(BaseAgent agent) {
    BaseAgent currentAgent = agent;
    while (true) {
      if (currentAgent instanceof LlmAgent) {
        return ((LlmAgent) currentAgent).inputSchema();
      }
      List<? extends BaseAgent> subAgents = currentAgent.subAgents();
      if (subAgents == null || subAgents.isEmpty()) {
        return Optional.empty();
      }
      // For composite agents, check the first sub-agent.
      currentAgent = subAgents.get(0);
    }
  }

  /**
   * Tries to heuristically find an output schema defined on an {@link LlmAgent} contained within
   * the wrapped agent structure by traversing down through the *last* sub-agent at each level.
   *
   * <p>This is used to determine if the tool's final text output should be parsed as structured
   * JSON based on a schema, or returned as a simple {@code result} string.
   */
  private Optional<Schema> getOutputSchema(BaseAgent agent) {
    BaseAgent currentAgent = agent;
    while (true) {
      if (currentAgent instanceof LlmAgent) {
        return ((LlmAgent) currentAgent).outputSchema();
      }
      List<? extends BaseAgent> subAgents = currentAgent.subAgents();
      if (subAgents == null || subAgents.isEmpty()) {
        return Optional.empty();
      }
      // For composite agents, check the last sub-agent.
      currentAgent = subAgents.get(subAgents.size() - 1);
    }
  }

  /**
   * Builds the tool's function declaration.
   *
   * <p>If an input schema can be inferred via {@link #getInputSchema}, it is used as the tool's
   * parameters. Otherwise, it defaults to a single parameter {@code request} of type STRING.
   */
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

  /**
   * Executes the wrapped agent with the provided arguments.
   *
   * <p>If the agent has an input schema, {@code args} are validated and serialized to JSON to form
   * the input {@link Content}. Otherwise, the value of the {@code request} key in {@code args} is
   * used as text input.
   *
   * <p>The agent is run via {@link Runner}, and all resulting events are collected. State changes
   * from all events are applied to {@code toolContext.state()}. The content of the *last* event is
   * used as the tool's result, parsed according to {@link #getOutputSchema} if available.
   *
   * @param args The arguments for the tool call, matching either the inferred schema or containing
   *     a {@code request} key.
   * @param toolContext The context of the tool invocation, including session and state.
   * @return A map containing the agent's result, plus a {@code trace} key holding all execution
   *     events.
   */
  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    Optional<Schema> agentInputSchema = getInputSchema(agent);

    final Content content;
    if (agentInputSchema.isPresent()) {
      // If schema is defined, treat args as structured input and serialize to JSON.
      SchemaUtils.validateMapOnSchema(args, agentInputSchema.get(), /* isInput= */ true);
      try {
        content =
            Content.fromParts(Part.fromText(JsonBaseModel.getMapper().writeValueAsString(args)));
      } catch (JsonProcessingException e) {
        return Single.error(
            new IllegalStateException("Error serializing tool arguments to JSON: " + args, e));
      }
    } else {
      // If no schema, default to expecting a 'request' string in args.
      Object input = args.get("request");
      if (input == null) {
        return Single.error(
            new IllegalArgumentException(
                "Tool '"
                    + name()
                    + "' expects a 'request' argument when no input schema is defined."));
      }
      content = Content.fromParts(Part.fromText(input.toString()));
    }

    Runner runner =
        Runner.builder()
            .appName(appName)
            .agent(agent)
            .sessionService(sessionService)
            .artifactService(artifactService)
            .build();

    // Run the agent using the calling agent's session ID to ensure events are logged to the same
    // session.
    return runner
        .runAsync(
            toolContext.invocationContext().userId(),
            toolContext.invocationContext().session().id(),
            content)
        // Collect all events from the stream to build the trace and process all state deltas.
        .toList()
        .map(events -> processEvents(events, toolContext));
  }

  /**
   * Processes the list of events generated by the wrapped agent's execution.
   *
   * <p>This method performs two main functions:
   *
   * <ol>
   *   <li><b>State Propagation</b>: It iterates through *all* events and applies any state changes
   *       defined in {@code event.actions().stateDelta()} to the calling tool's context via {@link
   *       #updateState}. This ensures that state modifications from any step of the sub-agent's
   *       execution are reflected in the parent agent's session state.
   *   <li><b>Result Extraction</b>: It extracts the textual content from the *last* event in the
   *       list. If an output schema is present (determined by {@link #getOutputSchema}), it
   *       attempts to parse this text as JSON conforming to the schema. If no output schema is
   *       found, or if parsing fails, the raw text is returned in a map under the "result" key.
   * </ol>
   *
   * <p>Finally, it returns a map containing the extracted result, plus the complete list of all
   * processed events under the "trace" key for observability.
   *
   * @param events The complete list of events produced by the {@link Runner} execution.
   * @param toolContext The context of the tool invocation, whose state will be updated.
   * @return A map containing the result of the execution and a full event trace.
   */
  private ImmutableMap<String, Object> processEvents(List<Event> events, ToolContext toolContext) {
    if (events.isEmpty()) {
      return ImmutableMap.of();
    }

    // Apply state delta from all events, not just the last one.
    events.stream()
        .map(Event::actions)
        .filter(actions -> actions != null && actions.stateDelta() != null)
        .map(EventActions::stateDelta)
        .filter(stateDelta -> !stateDelta.isEmpty())
        .forEach(stateDelta -> updateState(stateDelta, toolContext.state()));

    Event lastEvent = events.get(events.size() - 1);
    Optional<String> outputText = lastEvent.content().map(Content::text);
    if (outputText.isEmpty()) {
      return ImmutableMap.of();
    }
    String output = outputText.get();

    Map<String, Object> result;
    Optional<Schema> outputSchema = getOutputSchema(agent);
    if (outputSchema.isPresent()) {
      try {
        result = SchemaUtils.validateOutputSchema(output, outputSchema.get());
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to parse agent output JSON: " + output, e);
      }
    } else {
      result = ImmutableMap.of("result", output);
    }

    // Return the result map, adding the full event trace.
    Map<String, Object> resultMap = new HashMap<>(result);
    resultMap.put("trace", events);
    return ImmutableMap.copyOf(resultMap);
  }

  /**
   * Updates the given state map with a delta.
   *
   * <p>If a value in {@code stateDelta} is {@link State#REMOVED}, the corresponding key is removed
   * from {@code state}, otherwise the key-value pair is added or updated in {@code state}.
   */
  private void updateState(Map<String, Object> stateDelta, Map<String, Object> state) {
    for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
      if (entry.getValue() == State.REMOVED) {
        state.remove(entry.getKey());
      } else {
        state.put(entry.getKey(), entry.getValue());
      }
    }
  }
}
