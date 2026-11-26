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

package com.google.adk.agents;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.SchemaUtils;
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.AfterModelCallbackBase;
import com.google.adk.agents.Callbacks.AfterModelCallbackSync;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.AfterToolCallbackBase;
import com.google.adk.agents.Callbacks.AfterToolCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallbackBase;
import com.google.adk.agents.Callbacks.BeforeModelCallbackSync;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallbackBase;
import com.google.adk.agents.Callbacks.BeforeToolCallbackSync;
import com.google.adk.agents.Callbacks.OnModelErrorCallback;
import com.google.adk.agents.Callbacks.OnModelErrorCallbackBase;
import com.google.adk.agents.Callbacks.OnModelErrorCallbackSync;
import com.google.adk.agents.Callbacks.OnToolErrorCallback;
import com.google.adk.agents.Callbacks.OnToolErrorCallbackBase;
import com.google.adk.agents.Callbacks.OnToolErrorCallbackSync;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.annotations.Experimental;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.flows.llmflows.AutoFlow;
import com.google.adk.flows.llmflows.BaseLlmFlow;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.flows.llmflows.SingleFlow;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.Model;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.workflow.NodeExecutionState;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.RunNodeConfig;
import com.google.adk.workflow.WorkflowConfigurationException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.BlockedReason;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The LLM-based agent. */
public class LlmAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(LlmAgent.class);

  /** Name of the built-in tool whose response records an agent transfer. */
  private static final String TRANSFER_TO_AGENT_TOOL = "transfer_to_agent";

  /**
   * Enum to define if contents of previous events should be included in requests to the underlying
   * LLM.
   */
  public enum IncludeContents {
    DEFAULT,
    NONE;
  }

  private final Optional<Model> model;
  private final Instruction instruction;
  private final Instruction globalInstruction;
  private final List<Object> toolsUnion;
  private final ImmutableList<BaseToolset> toolsets;
  private final Optional<GenerateContentConfig> generateContentConfig;
  private final IncludeContents includeContents;
  private final boolean includeContentsExplicit;

  private final boolean planning;
  private final Optional<Integer> maxSteps;
  private final boolean disallowTransferToParent;
  private final boolean disallowTransferToPeers;
  private final ImmutableList<? extends BeforeModelCallback> beforeModelCallback;
  private final ImmutableList<? extends AfterModelCallback> afterModelCallback;
  private final ImmutableList<? extends OnModelErrorCallback> onModelErrorCallback;
  private final ImmutableList<? extends BeforeToolCallback> beforeToolCallback;
  private final ImmutableList<? extends AfterToolCallback> afterToolCallback;
  private final ImmutableList<? extends OnToolErrorCallback> onToolErrorCallback;
  private final Optional<Schema> inputSchema;
  private final Optional<Schema> outputSchema;
  private final Optional<Executor> executor;
  private final Optional<String> outputKey;
  private final Optional<BaseCodeExecutor> codeExecutor;
  private final Optional<AgentMode> mode;

  private volatile Model resolvedModel;
  private final BaseLlmFlow llmFlow;

  protected LlmAgent(Builder builder) {
    super(
        builder.name,
        builder.description,
        builder.subAgents,
        builder.beforeAgentCallback,
        builder.afterAgentCallback);
    this.model = Optional.ofNullable(builder.model);
    this.instruction = requireNonNullElse(builder.instruction, new Instruction.Static(""));
    this.globalInstruction =
        requireNonNullElse(builder.globalInstruction, new Instruction.Static(""));
    this.generateContentConfig = Optional.ofNullable(builder.generateContentConfig);
    this.includeContents = requireNonNullElse(builder.includeContents, IncludeContents.DEFAULT);
    this.includeContentsExplicit = builder.includeContents != null;
    this.planning = requireNonNullElse(builder.planning, false);
    this.maxSteps = Optional.ofNullable(builder.maxSteps);
    this.disallowTransferToParent = requireNonNullElse(builder.disallowTransferToParent, false);
    this.disallowTransferToPeers = requireNonNullElse(builder.disallowTransferToPeers, false);
    this.beforeModelCallback = requireNonNullElse(builder.beforeModelCallback, ImmutableList.of());
    this.afterModelCallback = requireNonNullElse(builder.afterModelCallback, ImmutableList.of());
    this.onModelErrorCallback =
        requireNonNullElse(builder.onModelErrorCallback, ImmutableList.of());
    this.beforeToolCallback = requireNonNullElse(builder.beforeToolCallback, ImmutableList.of());
    this.afterToolCallback = requireNonNullElse(builder.afterToolCallback, ImmutableList.of());
    this.onToolErrorCallback = requireNonNullElse(builder.onToolErrorCallback, ImmutableList.of());
    this.inputSchema = Optional.ofNullable(builder.inputSchema);
    this.outputSchema = Optional.ofNullable(builder.outputSchema);
    this.executor = Optional.ofNullable(builder.executor);
    this.outputKey = Optional.ofNullable(builder.outputKey);
    // A task agent ends its task with finish_task; task and single-turn sub-agents are tools.
    this.toolsUnion =
        ImmutableList.builder()
            .addAll(requireNonNullElse(builder.toolsUnion, ImmutableList.of()))
            .addAll(
                builder.mode == AgentMode.TASK
                    ? ImmutableList.of(
                        outputSchema.map(FinishTaskTool::new).orElseGet(FinishTaskTool::new))
                    : ImmutableList.of())
            .addAll(
                subAgents().stream()
                    .filter(LlmAgent::runsAsTool)
                    .map(LlmAgent.class::cast)
                    .map(subAgent ->
                        subAgent.hasMode(AgentMode.TASK)
                            ? new TaskAgentTool(subAgent)
                            : new SingleTurnAgentTool(subAgent))
                    .iterator())
            .build();
    this.toolsets = extractToolsets(this.toolsUnion);
    this.codeExecutor = Optional.ofNullable(builder.codeExecutor);
    this.mode = Optional.ofNullable(builder.mode);

    this.llmFlow = determineLlmFlow();

    // Validate name not empty.
    Preconditions.checkArgument(!this.name().isEmpty(), "Agent name cannot be empty.");
  }

  /** Returns a {@link Builder} for {@link LlmAgent}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Extracts BaseToolset instances from the toolsUnion list. */
  private static ImmutableList<BaseToolset> extractToolsets(List<Object> toolsUnion) {
    return toolsUnion.stream()
        .filter(obj -> obj instanceof BaseToolset)
        .map(obj -> (BaseToolset) obj)
        .collect(toImmutableList());
  }

  /** Builder for {@link LlmAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {
    private Model model;

    private Instruction instruction;
    private Instruction globalInstruction;
    private ImmutableList<Object> toolsUnion;
    private GenerateContentConfig generateContentConfig;
    private IncludeContents includeContents;
    private Boolean planning;
    private Integer maxSteps;
    private Boolean disallowTransferToParent;
    private Boolean disallowTransferToPeers;
    private ImmutableList<? extends BeforeModelCallback> beforeModelCallback;
    private ImmutableList<? extends AfterModelCallback> afterModelCallback;
    private ImmutableList<? extends OnModelErrorCallback> onModelErrorCallback;
    private ImmutableList<? extends BeforeToolCallback> beforeToolCallback;
    private ImmutableList<? extends AfterToolCallback> afterToolCallback;
    private ImmutableList<? extends OnToolErrorCallback> onToolErrorCallback;
    private Schema inputSchema;
    private Schema outputSchema;
    private Executor executor;
    private String outputKey;
    private BaseCodeExecutor codeExecutor;
    private AgentMode mode;

    @CanIgnoreReturnValue
    public Builder model(String model) {
      this.model = Model.builder().modelName(model).build();
      return this;
    }

    @CanIgnoreReturnValue
    public Builder model(BaseLlm model) {
      this.model = Model.builder().model(model).build();
      return this;
    }

    @CanIgnoreReturnValue
    public Builder instruction(Instruction instruction) {
      this.instruction = instruction;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder instruction(String instruction) {
      this.instruction = (instruction == null) ? null : new Instruction.Static(instruction);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder globalInstruction(Instruction globalInstruction) {
      this.globalInstruction = globalInstruction;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder globalInstruction(String globalInstruction) {
      this.globalInstruction =
          (globalInstruction == null) ? null : new Instruction.Static(globalInstruction);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder tools(List<?> tools) {
      this.toolsUnion = ImmutableList.copyOf(tools);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder tools(Object... tools) {
      this.toolsUnion = ImmutableList.copyOf(tools);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder generateContentConfig(GenerateContentConfig generateContentConfig) {
      this.generateContentConfig = generateContentConfig;
      return this;
    }

    /**
     * Sets how much session history the agent sees. Unset, it is {@link IncludeContents#DEFAULT},
     * except that a {@code SINGLE_TURN} workflow node then runs with {@link IncludeContents#NONE}.
     */
    @CanIgnoreReturnValue
    public Builder includeContents(IncludeContents includeContents) {
      this.includeContents = includeContents;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder planning(boolean planning) {
      this.planning = planning;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder maxSteps(int maxSteps) {
      this.maxSteps = maxSteps;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder disallowTransferToParent(boolean disallowTransferToParent) {
      this.disallowTransferToParent = disallowTransferToParent;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder disallowTransferToPeers(boolean disallowTransferToPeers) {
      this.disallowTransferToPeers = disallowTransferToPeers;
      return this;
    }

    /** Removes any before-model callbacks set on this builder. */
    @CanIgnoreReturnValue
    public Builder clearBeforeModelCallbacks() {
      this.beforeModelCallback = null;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeModelCallback(BeforeModelCallback beforeModelCallback) {
      this.beforeModelCallback = ImmutableList.of(beforeModelCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeModelCallback(
        @Nullable List<? extends BeforeModelCallbackBase> beforeModelCallbacks) {
      this.beforeModelCallback =
          convertCallbacks(
              beforeModelCallbacks,
              callback -> {
                if (callback instanceof BeforeModelCallback beforeModelCallbackInstance) {
                  return beforeModelCallbackInstance;
                } else if (callback
                    instanceof BeforeModelCallbackSync beforeModelCallbackSyncInstance) {
                  return (callbackContext, llmRequestBuilder) ->
                      Maybe.fromOptional(
                          beforeModelCallbackSyncInstance.call(callbackContext, llmRequestBuilder));
                } else {
                  return null;
                }
              },
              "beforeModelCallback");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeModelCallbackSync(BeforeModelCallbackSync beforeModelCallbackSync) {
      this.beforeModelCallback =
          ImmutableList.of((callbackContext, llmRequestBuilder) ->
              Maybe.fromOptional(beforeModelCallbackSync.call(callbackContext, llmRequestBuilder)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterModelCallback(AfterModelCallback afterModelCallback) {
      this.afterModelCallback = ImmutableList.of(afterModelCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterModelCallback(
        @Nullable List<? extends AfterModelCallbackBase> afterModelCallbacks) {
      this.afterModelCallback =
          convertCallbacks(
              afterModelCallbacks,
              callback -> {
                if (callback instanceof AfterModelCallback afterModelCallbackInstance) {
                  return afterModelCallbackInstance;
                } else if (callback
                    instanceof AfterModelCallbackSync afterModelCallbackSyncInstance) {
                  return (callbackContext, llmResponse) ->
                      Maybe.fromOptional(
                          afterModelCallbackSyncInstance.call(callbackContext, llmResponse));
                } else {
                  return null;
                }
              },
              "afterModelCallback");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterModelCallbackSync(AfterModelCallbackSync afterModelCallbackSync) {
      this.afterModelCallback =
          ImmutableList.of((callbackContext, llmResponse) ->
              Maybe.fromOptional(afterModelCallbackSync.call(callbackContext, llmResponse)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onModelErrorCallback(OnModelErrorCallback onModelErrorCallback) {
      this.onModelErrorCallback = ImmutableList.of(onModelErrorCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onModelErrorCallback(
        @Nullable List<? extends OnModelErrorCallbackBase> onModelErrorCallbacks) {
      this.onModelErrorCallback =
          convertCallbacks(
              onModelErrorCallbacks,
              callback -> {
                if (callback instanceof OnModelErrorCallback onModelErrorCallbackInstance) {
                  return onModelErrorCallbackInstance;
                } else if (callback
                    instanceof OnModelErrorCallbackSync onModelErrorCallbackSyncInstance) {
                  return (callbackContext, llmRequest, error) ->
                      Maybe.fromOptional(
                          onModelErrorCallbackSyncInstance.call(
                              callbackContext, llmRequest, error));
                } else {
                  return null;
                }
              },
              "onModelErrorCallback");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onModelErrorCallbackSync(OnModelErrorCallbackSync onModelErrorCallbackSync) {
      this.onModelErrorCallback =
          ImmutableList.of((callbackContext, llmRequest, error) ->
              Maybe.fromOptional(
                  onModelErrorCallbackSync.call(callbackContext, llmRequest, error)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallbackSync(BeforeAgentCallbackSync beforeAgentCallbackSync) {
      this.beforeAgentCallback =
          ImmutableList.of(
              callbackContext -> Maybe.fromOptional(beforeAgentCallbackSync.call(callbackContext)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallbackSync(AfterAgentCallbackSync afterAgentCallbackSync) {
      this.afterAgentCallback =
          ImmutableList.of(
              callbackContext -> Maybe.fromOptional(afterAgentCallbackSync.call(callbackContext)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeToolCallback(BeforeToolCallback beforeToolCallback) {
      this.beforeToolCallback = ImmutableList.of(beforeToolCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeToolCallback(
        @Nullable List<? extends BeforeToolCallbackBase> beforeToolCallbacks) {
      this.beforeToolCallback =
          convertCallbacks(
              beforeToolCallbacks,
              callback -> {
                if (callback instanceof BeforeToolCallback beforeToolCallbackInstance) {
                  return beforeToolCallbackInstance;
                } else if (callback
                    instanceof BeforeToolCallbackSync beforeToolCallbackSyncInstance) {
                  return (invocationContext, baseTool, input, toolContext) ->
                      Maybe.fromOptional(
                          beforeToolCallbackSyncInstance.call(
                              invocationContext, baseTool, input, toolContext));
                } else {
                  return null;
                }
              },
              "beforeToolCallback");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeToolCallbackSync(BeforeToolCallbackSync beforeToolCallbackSync) {
      this.beforeToolCallback =
          ImmutableList.of((invocationContext, baseTool, input, toolContext) ->
              Maybe.fromOptional(
                  beforeToolCallbackSync.call(invocationContext, baseTool, input, toolContext)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterToolCallback(AfterToolCallback afterToolCallback) {
      this.afterToolCallback = ImmutableList.of(afterToolCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterToolCallback(
        @Nullable List<? extends AfterToolCallbackBase> afterToolCallbacks) {
      this.afterToolCallback =
          convertCallbacks(
              afterToolCallbacks,
              callback -> {
                if (callback instanceof AfterToolCallback afterToolCallbackInstance) {
                  return afterToolCallbackInstance;
                } else if (callback
                    instanceof AfterToolCallbackSync afterToolCallbackSyncInstance) {
                  return (invocationContext, baseTool, input, toolContext, response) ->
                      Maybe.fromOptional(
                          afterToolCallbackSyncInstance.call(
                              invocationContext, baseTool, input, toolContext, response));
                } else {
                  return null;
                }
              },
              "afterToolCallback");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterToolCallbackSync(AfterToolCallbackSync afterToolCallbackSync) {
      this.afterToolCallback =
          ImmutableList.of((invocationContext, baseTool, input, toolContext, response) ->
              Maybe.fromOptional(
                  afterToolCallbackSync.call(
                      invocationContext, baseTool, input, toolContext, response)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onToolErrorCallback(OnToolErrorCallback onToolErrorCallback) {
      this.onToolErrorCallback = ImmutableList.of(onToolErrorCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onToolErrorCallback(
        @Nullable List<? extends OnToolErrorCallbackBase> onToolErrorCallbacks) {
      this.onToolErrorCallback =
          convertCallbacks(
              onToolErrorCallbacks,
              callback -> {
                if (callback instanceof OnToolErrorCallback onToolErrorCallbackInstance) {
                  return onToolErrorCallbackInstance;
                } else if (callback
                    instanceof OnToolErrorCallbackSync onToolErrorCallbackSyncInstance) {
                  return (invocationContext, baseTool, input, toolContext, error) ->
                      Maybe.fromOptional(
                          onToolErrorCallbackSyncInstance.call(
                              invocationContext, baseTool, input, toolContext, error));
                } else {
                  return null;
                }
              },
              "onToolErrorCallback");
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onToolErrorCallbackSync(OnToolErrorCallbackSync onToolErrorCallbackSync) {
      this.onToolErrorCallback =
          ImmutableList.of((invocationContext, baseTool, input, toolContext, error) ->
              Maybe.fromOptional(
                  onToolErrorCallbackSync.call(
                      invocationContext, baseTool, input, toolContext, error)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder inputSchema(Schema inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputSchema(Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    /**
     * Sets the executor for this agent's offloaded work: tool calls in {@code
     * ToolExecutionMode.PARALLEL_SUBSCRIBE} mode and the live request loop. When unset, that work
     * runs on the invocation's scheduler ({@link InvocationContext#scheduler()}).
     */
    @CanIgnoreReturnValue
    public Builder executor(Executor executor) {
      this.executor = executor;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputKey(String outputKey) {
      this.outputKey = outputKey;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder codeExecutor(BaseCodeExecutor codeExecutor) {
      this.codeExecutor = codeExecutor;
      return this;
    }

    /**
     * Sets how this agent converses when it runs as a workflow node. An agent without a mode runs
     * as {@link AgentMode#CHAT} when another agent in its tree sets a mode, and otherwise as it
     * does outside a workflow.
     */
    @CanIgnoreReturnValue
    @Experimental
    public Builder mode(AgentMode mode) {
      this.mode = mode;
      return this;
    }

    private static <B, A> @Nullable ImmutableList<A> convertCallbacks(
        @Nullable List<? extends B> callbacks, Function<B, A> converter, String callbackType) {
      return Optional.ofNullable(callbacks)
          .map(c ->
              c.stream()
                  .map(callback -> {
                    A converted = converter.apply(callback);
                    if (converted == null) {
                      LlmAgent.logger.warn(
                          "Invalid {} callback type: {}. Ignoring this callback.",
                          callbackType,
                          callback.getClass().getName());
                    }
                    return converted;
                  })
                  .filter(Objects::nonNull)
                  .collect(toImmutableList()))
          .orElse(null);
    }

    protected void validate() {
      this.disallowTransferToParent =
          this.disallowTransferToParent != null && this.disallowTransferToParent;
      this.disallowTransferToPeers =
          this.disallowTransferToPeers != null && this.disallowTransferToPeers;
    }

    @Override
    public LlmAgent build() {
      validate();
      return new LlmAgent(this);
    }
  }

  protected BaseLlmFlow determineLlmFlow() {
    if (disallowTransferToParent() && disallowTransferToPeers() && subAgents().isEmpty()) {
      return new SingleFlow(maxSteps);
    } else {
      return new AutoFlow(maxSteps);
    }
  }

  private void maybeSaveOutputToState(Event event, InvocationContext invocationContext) {
    // Events from another agent, e.g. a transfer target, must not overwrite this agent's output.
    if (!name().equals(event.author())) {
      logger.debug(
          "Skipping output save for agent {}: event authored by {}", name(), event.author());
      return;
    }
    // A task node's output comes from finish_task, not from a text turn.
    if (outputKey().isEmpty()
        || !event.finalResponse()
        || event.content().isEmpty()
        || runsAsTask(this, invocationContext)) {
      return;
    }
    List<Part> parts = event.content().flatMap(Content::parts).orElseGet(ImmutableList::of);

    // Skip events with no non-thought text part (e.g. a function-call-only long-running call, or a
    // function-response-only event) so an output value already in state is not overwritten with an
    // empty string. Mirrors ADK Python's output_key handling.
    boolean hasTextPart =
        parts.stream().anyMatch(part -> !isThought(part) && part.text().isPresent());
    if (!hasTextPart) {
      return;
    }

    String rawResult = visibleText(event.content().get());

    Object output = rawResult;
    Optional<Schema> outputSchema = outputSchema();
    if (outputSchema.isPresent() && runsSingleTurn(invocationContext)) {
      try {
        output = SchemaUtils.parseModelOutput(rawResult, outputSchema.get());
      } catch (IllegalArgumentException e) {
        // Like the node output, the saved value must match the schema.
        return;
      }
      if (output == null) {
        return;
      }
    } else if (outputSchema.isPresent()) {
      try {
        output =
            SchemaUtils.valueWithDefaults(
                SchemaUtils.validateOutput(rawResult, outputSchema.get()), outputSchema.get());
        if (output == null) {
          // A JSON null for a nullable array or primitive schema. State cannot hold null, so save
          // "", which {key} renders as Python renders None. Single-turn mode skips the save.
          output = "";
        }
      } catch (JsonProcessingException e) {
        logger.error(
            "LlmAgent output for outputKey '{}' was not valid JSON, despite an outputSchema being"
                + " present. Saving raw output to state. Cause: {}",
            outputKey().get(),
            // The exception's message can quote the model's output.
            e.getClass().getSimpleName());
      } catch (IllegalArgumentException e) {
        logger.error(
            "LlmAgent output for outputKey '{}' did not match the outputSchema. Saving raw output"
                + " to state. Cause: {}",
            outputKey().get(),
            e.getClass().getSimpleName());
      }
    }
    event.actions().stateDelta().put(outputKey().get(), output);
  }

  private static boolean isThought(Part part) {
    return part.thought().isPresent() && part.thought().get();
  }

  /** Returns the text of the parts of {@code content} that are not thoughts. */
  private static String visibleText(Content content) {
    return content.parts().orElseGet(ImmutableList::of).stream()
        .filter(part -> !isThought(part))
        .map(part -> part.text().orElse(""))
        .collect(joining());
  }

  private boolean runsSingleTurn(InvocationContext invocationContext) {
    return invocationContext.agentMode(this).filter(AgentMode.SINGLE_TURN::equals).isPresent();
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    // A single-turn node that reruns with answers resumes into the target it ran inline, if any.
    boolean resumesSingleTurnNode =
        runsSingleTurn(invocationContext)
            && invocationContext
                .nodeContext()
                .filter(nodeContext -> !nodeContext.resumeInputs().isEmpty())
                .isPresent();
    Flowable<Event> flow =
        llmFlow
            .run(invocationContext)
            .doOnNext(event -> maybeSaveOutputToState(event, invocationContext));
    if (!invocationContext.isResumable() && !resumesSingleTurnNode) {
      return flow;
    }
    return Flowable.defer(() -> {
      // Resumed after a transfer: continue the sub-agent instead of re-invoking the model.
      if (resumesSingleTurnNode || invocationContext.agentStates().containsKey(name())) {
        Optional<BaseAgent> resumeTarget = findSubAgentToResume(invocationContext);
        if (resumeTarget.isPresent()) {
          AtomicBoolean resumePaused = new AtomicBoolean(false);
          return resumeTarget
              .get()
              .runAsync(invocationContext)
              .doOnNext(event -> {
                if (invocationContext.shouldPauseInvocation(event)) {
                  resumePaused.set(true);
                }
              })
              .concatWith(
                  Flowable.defer(() -> {
                    if (resumePaused.get() || !invocationContext.isResumable()) {
                      return Flowable.<Event>empty();
                    }
                    return endOfAgentAndRecord(invocationContext);
                  }));
        }
      }
      if (!invocationContext.isResumable()) {
        return flow;
      }
      // Normal path: emit an end-of-agent checkpoint so a later run can skip this agent.
      AtomicBoolean paused = new AtomicBoolean(false);
      return flow.doOnNext(
              event -> {
                if (invocationContext.shouldPauseInvocation(event)) {
                  paused.set(true);
                }
              })
          .concatWith(
              Flowable.defer(() -> {
                // Only the checkpoint is withheld, as in Python; the flow still runs. A
                // transferring agent stays open so a resume re-enters it and reaches the
                // sub-agent it handed off to.
                // An open delegation leaves the agent unfinished too, so a resume re-enters it.
                if (paused.get()
                    || invocationContext.lastEventsPauseInvocation()
                    || Functions.leftADelegationUnanswered(invocationContext, delegationTools())) {
                  return Flowable.empty();
                }
                return endOfAgentAndRecord(invocationContext);
              }));
    });
  }

  /**
   * Returns the name of the agent this agent transferred to in {@code event}, or empty when {@code
   * event} is not such a transfer. A {@code transfer_to_agent} function response is required, as in
   * Python ADK, so a callback that sets the action on an unrelated event does not read as one.
   */
  private Optional<String> transferTargetNameFrom(Event event) {
    if (!name().equals(event.author()) || !hasTransferResponse(event)) {
      return Optional.empty();
    }
    return event.actions().transferToAgent().filter(target -> !target.equals(name()));
  }

  /** Whether {@code event} is another agent's transfer to this agent. */
  private boolean isTransferToThisAgent(Event event) {
    return !name().equals(event.author())
        && hasTransferResponse(event)
        && event.actions().transferToAgent().filter(name()::equals).isPresent();
  }

  private static boolean hasTransferResponse(Event event) {
    return event.functionResponses().stream()
        .anyMatch(response -> response.name().filter(TRANSFER_TO_AGENT_TOOL::equals).isPresent());
  }

  /**
   * Resolves a transfer target recorded in history back to its agent. A live transfer fails in
   * {@code BaseLlmFlow} when the target is gone; a resumed one has no such check, and returning
   * empty here would silently re-run this agent instead of the sub-agent it handed off to.
   */
  private BaseAgent resolveTransferTarget(InvocationContext context, String targetName) {
    // Declared targets first, as in Python, so a same-named agent on an unrelated branch is not
    // resumed into; then the same tree-wide search the live transfer path uses.
    for (BaseAgent target : transferTargets(context)) {
      if (target.name().equals(targetName)) {
        return target;
      }
    }
    return rootAgent()
        .findAgent(targetName)
        .orElseThrow(() ->
            new IllegalStateException(
                "Cannot resume agent "
                    + name()
                    + ": the agent it transferred to is no longer in the agent tree."));
  }

  /**
   * When this agent is being resumed, returns the sub-agent it had transferred to (so the resume
   * continues that sub-agent), or empty when this agent should continue itself.
   */
  private Optional<BaseAgent> findSubAgentToResume(InvocationContext context) {
    ImmutableList<Event> events =
        context.events(/* currentInvocation= */ true, /* currentBranch= */ true);
    if (events.isEmpty()) {
      return Optional.empty();
    }
    Event lastEvent = Iterables.getLast(events);
    if (name().equals(lastEvent.author())) {
      return transferTargetNameFrom(lastEvent).map(name -> resolveTransferTarget(context, name));
    }
    if (Objects.equals(lastEvent.author(), Role.USER) && !lastEvent.functionResponses().isEmpty()) {
      // IAE (not ISE): an unresolvable resume surfaces through Runner.runAsync's IAE contract.
      Event functionCallEvent =
          context
              .findMatchingFunctionCall(lastEvent)
              .orElseThrow(() ->
                  new IllegalArgumentException(
                      "No matching function call to resume agent "
                          + name()
                          + " from a function response."));
      if (name().equals(functionCallEvent.author())) {
        return Optional.empty();
      }
    }
    // A user event answering no call falls through; Python fails the resume instead.
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      // Control returned to this agent after its last transfer, so it continues itself.
      if (isTransferToThisAgent(event)) {
        return Optional.empty();
      }
      Optional<String> targetName = transferTargetNameFrom(event);
      if (targetName.isPresent()) {
        return Optional.of(resolveTransferTarget(context, targetName.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * Runs this agent as a workflow node: a {@link AgentMode#SINGLE_TURN} agent takes the node input
   * as its user turn and gives the first final reply, its own or its inline target's, as output; a
   * {@link AgentMode#TASK} agent works until {@code finish_task} succeeds; and a {@link
   * AgentMode#CHAT} agent runs the tasks it delegates and stays open ({@link #waitForOutput}). The
   * flow of a chat or task agent stops at a handoff, and the workflow runs the target as a node, or
   * fails the run if the handoff is invalid. Any other agent runs as {@link BaseAgent#runNode} runs
   * it.
   */
  @Experimental
  @Override
  public Flowable<Event> runNode(Context context, @Nullable Object nodeInput) {
    if (hasMode(AgentMode.TASK)) {
      return runTask(context, nodeInput);
    }
    // A chat agent that can delegate runs delegation rounds; one that can't runs one flow.
    if (defaultMode() == AgentMode.CHAT
        && toolsUnion.stream().anyMatch(TaskAgentTool.class::isInstance)) {
      return runWithDelegations(
          context,
          context.invocationContext().toBuilder().agentMode(this, AgentMode.CHAT).build(),
          /* finishTool= */ null);
    }
    if (defaultMode() == AgentMode.CHAT) {
      // Its flow stops at a handoff, and the workflow runs the target as its own node.
      return runAsNode(
          context, context.invocationContext().toBuilder().agentMode(this, AgentMode.CHAT).build());
    }
    if (!hasMode(AgentMode.SINGLE_TURN)) {
      return super.runNode(context, nodeInput);
    }
    InvocationContext invocationContext = context.invocationContext();
    InvocationContext singleTurnContext =
        invocationContext.toBuilder().agentMode(this, AgentMode.SINGLE_TURN).build();
    // A target runs inside this node, as in Python. Any agent's first final reply is the output.
    Flowable<Event> run = runAsNode(context, singleTurnContext);
    // Eager disposal removes the input before the run ends, so a successor never sees it.
    return Flowable.using(
        () -> injectNodeInput(context, nodeInput),
        unused -> {
          // The first final turn with an output is the node's output; later content is not.
          AtomicBoolean outputMarked = new AtomicBoolean();
          return run.map(event -> {
            if (outputMarked.get()) {
              return event;
            }
            Optional<Event> marked = markOutputIfFinalTurn(event);
            outputMarked.set(marked.isPresent());
            return marked.orElse(event);
          });
        },
        injected -> injected.ifPresent(event -> removeEvent(invocationContext.session(), event)),
        /* eager= */ true);
  }

  /**
   * Works on a task until a {@code finish_task} call succeeds, whose output becomes the node's
   * output and {@link #outputKey} value. The node input, when given, opens the task.
   */
  private Flowable<Event> runTask(Context context, @Nullable Object nodeInput) {
    InvocationContext invocationContext = context.invocationContext();
    InvocationContext taskContext =
        invocationContext.toBuilder()
            .agentMode(this, AgentMode.TASK)
            .userContent(
                nodeInput == null
                    ? invocationContext.userContent().orElse(null)
                    : nodeInputToUserContent(nodeInput))
            .build();
    FinishTaskTool finishTool =
        toolsUnion.stream()
            .filter(FinishTaskTool.class::isInstance)
            .map(FinishTaskTool.class::cast)
            .findFirst()
            .orElseThrow();
    return runWithDelegations(context, taskContext, finishTool);
  }

  /**
   * Runs this agent as a chat or task node, running the tasks it delegates. Each round runs the
   * agent until the model delegates tasks or ends; the delegated tasks then run as child nodes,
   * each in the scope of its call, and their answers start the next round. With a {@code
   * finishTool}, the run ends once a {@code finish_task} call succeeds; the flow stops at a handoff
   * too, so the workflow runs the target as a node.
   */
  private Flowable<Event> runWithDelegations(
      Context context, InvocationContext agentContext, @Nullable FinishTaskTool finishTool) {
    return Flowable.defer(() -> {
      ImmutableMap<String, BaseTool> tools =
          toolsUnion.stream()
              .filter(BaseTool.class::isInstance)
              .map(BaseTool.class::cast)
              .collect(
                  toImmutableMap(BaseTool::name, Function.identity(), (first, unused) -> first));
      // A task resumes only its own delegations; a chat agent's may sit in any scope.
      ImmutableList<FunctionCall> pending =
          unansweredDelegations(context, tools, /* ownScopeOnly= */ finishTool != null);
      Map<TaskState, List<FunctionCall>> byState = new EnumMap<>(TaskState.class);
      for (FunctionCall call : pending) {
        byState.computeIfAbsent(taskState(context, call), unused -> new ArrayList<>()).add(call);
      }
      // A failed task gets an error answer, so the agent goes on without running it again.
      Flowable<Event> answers =
          answerWithError(
                  context,
                  byState.getOrDefault(TaskState.FAILED, ImmutableList.of()),
                  "The delegated task failed.")
              .concatWith(
                  answerDelegations(
                      context, byState.getOrDefault(TaskState.RUNNABLE, ImmutableList.of())));
      // A task that waits on the user elsewhere is not run here, so the agent waits too.
      if (byState.containsKey(TaskState.WAITING)) {
        return answers;
      }
      return answers.concatWith(
          delegationRounds(new DelegationRun(context, agentContext, tools, finishTool)));
    });
  }

  /** Returns this agent's task delegation tools, keyed by name. */
  private ImmutableMap<String, BaseTool> delegationTools() {
    return toolsUnion.stream()
        .filter(TaskAgentTool.class::isInstance)
        .map(BaseTool.class::cast)
        .collect(toImmutableMap(BaseTool::name, Function.identity(), (first, unused) -> first));
  }

  /** Where the task that a delegation call started stands, as {@link #taskState} reads it. */
  private enum TaskState {
    /** The task runs now: it never ran, or it waits in this invocation. */
    RUNNABLE,
    /** The task's latest event, from an earlier invocation, is an execution error. */
    FAILED,
    /** The task waits on the user in another invocation. */
    WAITING
  }

  /**
   * Returns where the task a delegation {@code call} started stands, from the latest event in its
   * scope, named by the call ID. A task that ended gives its output again, so it runs; one that
   * failed in an earlier invocation gets an error answer; one whose latest event is from another
   * invocation waits.
   */
  private static TaskState taskState(Context context, FunctionCall call) {
    String callId = call.id().orElseThrow();
    Event latest = null;
    boolean finished = false;
    for (Event event : context.invocationContext().session().immutableEvents()) {
      if (event.isolationScope().filter(callId::equals).isPresent()) {
        latest = event;
        finished |= finishesTask(event);
      }
    }
    if (latest == null
        || finished
        || context.invocationContext().invocationId().equals(latest.invocationId())) {
      return TaskState.RUNNABLE;
    }
    return endsTaskWithError(latest) ? TaskState.FAILED : TaskState.WAITING;
  }

  /**
   * What stays the same across the delegation rounds of one node run, and the arguments of the
   * run's latest {@code finish_task} call, which a later round can answer.
   */
  private record DelegationRun(
      Context context,
      InvocationContext agentContext,
      ImmutableMap<String, BaseTool> tools,
      @Nullable FinishTaskTool finishTool,
      AtomicReference<Map<String, Object>> finishArgs) {

    DelegationRun(
        Context context,
        InvocationContext agentContext,
        ImmutableMap<String, BaseTool> tools,
        @Nullable FinishTaskTool finishTool) {
      this(context, agentContext, tools, finishTool, new AtomicReference<>());
    }

    @Nullable String branch() {
      return agentContext.branch().orElse(null);
    }
  }

  /** What one delegation round has seen so far. */
  private static final class DelegationRound {
    ImmutableList<FunctionCall> delegations = ImmutableList.of();
    ImmutableList<FunctionCall> rejectedCalls = ImmutableList.of();
    final Set<String> pendingLongRunningCalls = new HashSet<>();
    boolean ended;
    boolean handedOff;
  }

  private Flowable<Event> delegationRounds(DelegationRun run) {
    return Flowable.defer(() -> {
      DelegationRound round = new DelegationRound();
      Context context = run.context();
      Flowable<Event> roundEvents =
          runAsNode(context, run.agentContext())
              // Once the model delegates, the round ends before its next model step.
              .takeWhile(
                  event -> round.delegations.isEmpty() || !startsModelStep(event, run.branch()))
              .map(event -> roundEvent(run, round, event))
              .takeUntil((Event event) -> round.ended);
      return roundEvents.concatWith(
          Flowable.defer(() -> {
            if (round.handedOff) {
              return Flowable.empty();
            }
            if (round.delegations.isEmpty()) {
              return Flowable.empty();
            }
            // Rejected first, so a task that waits leaves no other call open.
            return rejectCalls(context, round.rejectedCalls)
                .concatWith(answerDelegations(context, round.delegations))
                .concatWith(
                    Flowable.defer(() ->
                        round.pendingLongRunningCalls.isEmpty()
                                && maxSteps
                                    .map(max -> run.agentContext().stepsTaken(this) < max)
                                    .orElse(true)
                            ? delegationRounds(run)
                            : Flowable.empty()));
          }));
    });
  }

  /**
   * Returns {@code event} as one round emits it, updating {@code round}: after the model delegates,
   * the round keeps only that step's tool responses, and a task run ends on the {@code finish_task}
   * response that succeeds.
   */
  private Event roundEvent(DelegationRun run, DelegationRound round, Event event) {
    Context context = run.context();
    Map<String, BaseTool> tools = run.tools();
    FinishTaskTool finishTool = run.finishTool();
    AtomicReference<Map<String, Object>> finishArgs = run.finishArgs();
    // A sub-agent's events, such as a single-turn tool's, pass through; its calls are not ours.
    if (!isOwnStep(event, run.branch())) {
      return event;
    }
    // A partial chunk never runs its calls, and its IDs can differ from the final one's.
    if (!event.partial().orElse(false)) {
      event.longRunningToolIds().ifPresent(round.pendingLongRunningCalls::addAll);
    }
    event.functionResponses().forEach(r -> r.id().ifPresent(round.pendingLongRunningCalls::remove));
    if (!round.delegations.isEmpty()) {
      // A tool callback can answer a delegation itself, and then the task must not run.
      Set<String> answered = new HashSet<>();
      event.functionResponses().forEach(r -> r.id().ifPresent(answered::add));
      round.delegations =
          round.delegations.stream()
              .filter(call -> !answered.contains(call.id().orElseThrow()))
              .collect(toImmutableList());
      // The tools' responses end the step, unless they answered every delegation, so the model
      // is not called again while a delegation is open.
      round.ended = !round.delegations.isEmpty() && !event.functionResponses().isEmpty();
      return FinishTaskTool.withRejectedResponses(event);
    }
    ImmutableList<FunctionCall> delegations = taskDelegationsIn(event, tools);
    if (finishTool != null) {
      if (!event.partial().orElse(false) && delegations.isEmpty()) {
        event.functionCalls().stream()
            .filter(call -> call.name().filter(FinishTaskTool.NAME::equals).isPresent())
            .findFirst()
            .ifPresent(call -> finishArgs.set(call.args().orElse(ImmutableMap.of())));
      }
      if (finishArgs.get() == null && FinishTaskTool.isSuccessResponse(event)) {
        answeredFinishArgs(context.invocationContext(), event).ifPresent(finishArgs::set);
      }
      if (finishArgs.get() != null && FinishTaskTool.isSuccessResponse(event)) {
        round.ended = true;
        Object output = finishTool.taskOutput(finishArgs.get());
        if (output == null) {
          return event;
        }
        if (outputKey().isPresent()) {
          var unused = context.state().put(outputKey().get(), output);
        }
        // As in adk-python, schema defaults apply to the node output, not the saved arguments.
        Object nodeOutput =
            outputSchema()
                .map(schema -> SchemaUtils.valueWithDefaults(output, schema))
                .orElse(output);
        return event.toBuilder().output(nodeOutput).build();
      }
    }
    if (handsOff(event)) {
      round.handedOff = true;
      return event;
    }
    round.delegations = delegations;
    if (!round.delegations.isEmpty()) {
      // A call no tool runs, such as a transfer, must not run with the step's tools, so the step
      // ends before them and every other call is answered with an error. Python instead runs the
      // other calls and answers only an unknown tool with an error.
      if (!callsNoToolRuns(event, tools).isEmpty()) {
        round.rejectedCalls = callsBesideDelegations(event, round.delegations);
        round.ended = true;
        // The error answers a rejected long-running call too, so it no longer holds the round.
        round.rejectedCalls.forEach(
            call -> call.id().ifPresent(round.pendingLongRunningCalls::remove));
      }
    }

    return event;
  }

  /**
   * Returns the calls in {@code event} that no tool of this agent answers, such as a {@code
   * transfer_to_agent} beside a delegation, which the delegation's round answers with an error.
   */
  private ImmutableList<FunctionCall> callsNoToolRuns(Event event, Map<String, BaseTool> tools) {
    // A toolset's tools are resolved per request, so they are known only by the step's own tools.
    if (toolsUnion.stream().anyMatch(BaseToolset.class::isInstance)) {
      return event.functionCalls().stream()
          .filter(call -> call.id().filter(id -> !id.isEmpty()).isPresent())
          .filter(call -> call.name().filter(TRANSFER_TO_AGENT_TOOL::equals).isPresent())
          .collect(toImmutableList());
    }
    return event.functionCalls().stream()
        .filter(call -> call.id().filter(id -> !id.isEmpty()).isPresent())
        .filter(call -> call.name().map(tools::get).isEmpty())
        .collect(toImmutableList());
  }

  /** Returns the calls in {@code event} with an ID that are not among {@code delegations}. */
  private static ImmutableList<FunctionCall> callsBesideDelegations(
      Event event, List<FunctionCall> delegations) {
    Set<String> delegationIds = new HashSet<>();
    delegations.forEach(call -> call.id().ifPresent(delegationIds::add));
    return event.functionCalls().stream()
        .filter(call -> call.id().filter(id -> !id.isEmpty()).isPresent())
        .filter(call -> !delegationIds.contains(call.id().get()))
        .collect(toImmutableList());
  }

  /** Returns the task delegations in {@code event}, or none in a partial event. */
  private static ImmutableList<FunctionCall> taskDelegationsIn(
      Event event, Map<String, BaseTool> tools) {
    if (event.partial().orElse(false)) {
      return ImmutableList.of();
    }
    return event.functionCalls().stream()
        .filter(call -> call.id().filter(id -> !id.isEmpty()).isPresent())
        .filter(call -> call.name().map(tools::get).filter(LlmAgent::delegatesTask).isPresent())
        .collect(toImmutableList());
  }

  /**
   * Returns the task delegations this agent made that nothing answered yet. With {@code
   * ownScopeOnly}, only delegations made in this node's scope count. A delegation made by another
   * run still in progress is that run's to answer, as when its task handed off to this run.
   */
  private ImmutableList<FunctionCall> unansweredDelegations(
      Context context, Map<String, BaseTool> tools, boolean ownScopeOnly) {
    NodeExecutionState nodeState = context.requireNodeState();
    Optional<String> nodeScope = nodeState.isolationScope();
    Map<String, FunctionCall> calls = new LinkedHashMap<>();
    Set<String> answered = new HashSet<>();
    for (Event event : context.invocationContext().session().immutableEvents()) {
      // Only this agent's own calls delegate; a user's event can only answer them.
      boolean inScope = !ownScopeOnly || event.isolationScope().equals(nodeScope);
      boolean delegatorRuns =
          event.nodeInfo().map(NodeInfo::path).filter(nodeState::otherRunInProgress).isPresent();
      if (name().equals(event.author()) && inScope && !delegatorRuns) {
        taskDelegationsIn(event, tools).forEach(call -> calls.put(call.id().orElseThrow(), call));
      }
      event.functionResponses().forEach(response -> response.id().ifPresent(answered::add));
    }
    return calls.entrySet().stream()
        .filter(entry -> !answered.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .collect(toImmutableList());
  }

  /** Runs the task agent each delegation names and emits the response that answers the call. */
  private Flowable<Event> answerDelegations(Context context, List<FunctionCall> calls) {
    return Flowable.fromIterable(calls).concatMap(call -> answerDelegation(context, call));
  }

  private Flowable<Event> answerDelegation(Context context, FunctionCall call) {
    return Flowable.defer(() -> {
      String callId = call.id().orElseThrow();
      // The sub-agent the tool was made for, not a same-named agent elsewhere in the tree.
      LlmAgent target =
          toolsUnion.stream()
              .filter(TaskAgentTool.class::isInstance)
              .map(TaskAgentTool.class::cast)
              .filter(tool -> call.name().equals(Optional.of(tool.name())))
              .findFirst()
              .orElseThrow(() -> new WorkflowConfigurationException("Task target agent not found."))
              .agent();
      RunNodeConfig config =
          RunNodeConfig.builder()
              .runId(callId)
              .useSubBranch(true)
              .overrideIsolationScope(callId)
              .raiseOnWait(true)
              .build();
      return context
          .runNode(target, call.args().orElse(ImmutableMap.of()), config)
          .map(Optional::of)
          .defaultIfEmpty(Optional.empty())
          .flatMapPublisher(
              output -> asUser(context, taskResponseEvent(context, call, output.orElse(null))));
    });
  }

  /** Answers each of {@code calls} with an error, so no call is left open for the next round. */
  private static Flowable<Event> rejectCalls(Context context, List<FunctionCall> calls) {
    return answerWithError(
        context,
        calls,
        "This call was not run because a task delegation was sent in the same turn. Call it again"
            + " by itself once the delegated result is available.");
  }

  /** Answers each of {@code calls} with {@code error}, as the user's response. */
  private static Flowable<Event> answerWithError(
      Context context, List<FunctionCall> calls, String error) {
    return Flowable.fromIterable(calls)
        .concatMap(call ->
            asUser(context, taskResponseEvent(context, call, ImmutableMap.of("error", error))));
  }

  /** Emits {@code event} as the user's, since the node otherwise stamps its own author. */
  private static Flowable<Event> asUser(Context context, Event event) {
    NodeExecutionState state = context.requireNodeState();
    // Eager disposal restores the author before the next emission can read it.
    return Flowable.using(
        () -> {
          String author = state.eventAuthor();
          state.eventAuthor(Role.USER);
          return author;
        },
        unused -> Flowable.just(event),
        state::eventAuthor,
        /* eager= */ true);
  }

  /**
   * Returns the user's function response that answers a delegation {@code call} with the task's
   * {@code output}: a map output is the response, anything else goes under {@code output}.
   */
  private static Event taskResponseEvent(
      Context context, FunctionCall call, @Nullable Object output) {
    Map<String, Object> response = new LinkedHashMap<>();
    if (output instanceof Map<?, ?> map) {
      map.forEach((key, value) -> response.put(String.valueOf(key), value));
    } else {
      response.put("output", output);
    }
    InvocationContext invocationContext = context.invocationContext();
    return Event.builder()
        .id(invocationContext.newUuid())
        .invocationId(invocationContext.invocationId())
        .author(Role.USER)
        .content(
            Content.builder()
                .role(Role.USER)
                .parts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id(call.id().orElseThrow())
                                .name(call.name().orElseThrow())
                                .response(response)
                                .build())
                        .build())
                .build())
        .timestamp(invocationContext.now().toEpochMilli())
        .build();
  }

  /** Returns whether {@code event} starts a model step of this agent's own on {@code branch}. */
  private boolean startsModelStep(Event event, @Nullable String branch) {
    return isOwnStep(event, branch)
        && event.content().flatMap(Content::role).filter("model"::equals).isPresent()
        && event.functionCalls().stream()
            .noneMatch(call ->
                call.name()
                    .filter(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME::equals)
                    .isPresent());
  }

  /**
   * Returns whether {@code event} is this agent's own on {@code branch}, not a sub-branch run's.
   */
  private boolean isOwnStep(Event event, @Nullable String branch) {
    return name().equals(event.author()) && Objects.equals(event.branch().orElse(null), branch);
  }

  /**
   * Returns the arguments of this agent's {@code finish_task} call that {@code event} answers, from
   * the session, since a resumed run replays the call without emitting its call event again.
   */
  private Optional<Map<String, Object>> answeredFinishArgs(
      InvocationContext invocationContext, Event event) {
    Optional<String> callId =
        event.functionResponses().stream()
            .filter(response -> response.name().filter(FinishTaskTool.NAME::equals).isPresent())
            .findFirst()
            .flatMap(FunctionResponse::id)
            .filter(id -> !id.isEmpty());
    if (callId.isEmpty()) {
      return Optional.empty();
    }
    // The newest matching call on this branch in this invocation, so a reused ID finds this one.
    ImmutableList<Event> events =
        invocationContext.events(/* currentInvocation= */ true, /* currentBranch= */ true);
    for (int i = events.size() - 1; i >= 0; i--) {
      Event earlier = events.get(i);
      if (!name().equals(earlier.author())) {
        continue;
      }
      Optional<FunctionCall> call =
          earlier.functionCalls().stream()
              .filter(c -> c.name().filter(FinishTaskTool.NAME::equals).isPresent())
              .filter(c -> c.id().equals(callId))
              .findFirst();
      if (call.isPresent()) {
        return Optional.of(call.get().args().orElse(ImmutableMap.of()));
      }
    }
    return Optional.empty();
  }

  /** Returns whether {@code event} is this agent's own complete handoff to another agent. */
  private boolean handsOff(Event event) {
    return name().equals(event.author())
        && !event.partial().orElse(false)
        && event.actions().transferToAgent().isPresent();
  }

  /**
   * Returns {@code true} for a {@link AgentMode#CHAT} or {@link AgentMode#TASK} agent, whose node
   * stays open across turns until it produces an output. An agent without a mode is a chat agent
   * when another agent in its tree sets a mode.
   */
  @Experimental
  @Override
  public boolean waitForOutput() {
    AgentMode nodeMode = defaultMode();
    return nodeMode == AgentMode.CHAT || nodeMode == AgentMode.TASK;
  }

  /**
   * Returns {@code event} carrying the node output when it is a final model turn with text, this
   * agent's or a transfer target's, or else empty. Without an output schema, the event's message is
   * the output.
   *
   * @throws IllegalArgumentException if the text, the target's included, does not match the schema
   */
  private Optional<Event> markOutputIfFinalTurn(Event event) {
    Optional<Content> content =
        event.content().filter(c -> c.role().filter(Role.MODEL::equals).isPresent());
    // As in Python, any non-user final reply in a single-turn node, as a target's, is output.
    if (Objects.equals(event.author(), Role.USER)
        || !event.finalResponse()
        || !event.functionCalls().isEmpty()
        || event.actions().transferToAgent().isPresent()
        || content.isEmpty()) {
      return Optional.empty();
    }
    String text = visibleText(content.get());
    Optional<Schema> schema = outputSchema();
    // As in Python, a target's reply follows this agent's output schema too.
    Object output =
        schema.isPresent()
            ? SchemaUtils.parseModelOutput(text, schema.get())
            : (text.isBlank() ? null : text.trim());
    if (output == null) {
      return Optional.empty();
    }
    Event.Builder marked = event.toBuilder().output(output);
    if (!name().equals(event.author()) && outputKey().isPresent()) {
      // As in Python, a target's reply is saved under this agent's output key too.
      EventActions actions = event.actions().toBuilder().build();
      actions.stateDelta().put(outputKey().get(), output);
      marked.actions(actions);
    }
    if (schema.isEmpty()) {
      // The output field gives the successor the trimmed text; the stored event drops it.
      marked.nodeInfo(
          event
              .nodeInfo()
              .map(NodeInfo::toBuilder)
              .orElseGet(NodeInfo::builder)
              .messageAsOutput(true)
              .build());
    }
    return Optional.of(marked.build());
  }

  /**
   * Adds the node input to the session as the agent's user turn and returns it. A resuming node
   * gets none, and the user message the runner already recorded is not added twice.
   */
  private static Optional<Event> injectNodeInput(Context context, @Nullable Object nodeInput) {
    InvocationContext invocationContext = context.invocationContext();
    String isolationScope = context.requireNodeState().isolationScope().orElse(null);
    if (nodeInput == null
        || !context.resumeInputs().isEmpty()
        || isRecordedUserMessage(invocationContext, nodeInput, isolationScope)) {
      return Optional.empty();
    }
    Event event =
        Event.builder()
            .id(invocationContext.newUuid())
            .invocationId(invocationContext.invocationId())
            .author(Role.USER)
            .content(nodeInputToUserContent(nodeInput))
            .branch(invocationContext.branch().orElse(null))
            .nodeInfo(NodeInfo.builder().path(context.requireNodeState().nodePath()).build())
            .isolationScope(isolationScope)
            .timestamp(invocationContext.now().toEpochMilli())
            .build();
    // Tracked before it is added, so every copy of the session events that holds it can filter it.
    invocationContext.trackNodeInput(event);
    invocationContext.session().addEvent(event);
    return Optional.of(event);
  }

  // As in ADK Kotlin; Python ADK adds the user message again and relies on its no-history default.
  private static boolean isRecordedUserMessage(
      InvocationContext invocationContext, Object nodeInput, @Nullable String isolationScope) {
    return invocationContext.userContent().filter(nodeInput::equals).isPresent()
        && invocationContext.session().immutableEvents().stream()
            .anyMatch(event ->
                invocationContext.invocationId().equals(event.invocationId())
                    && Objects.equals(event.author(), Role.USER)
                    && event.nodeInfo().map(NodeInfo::path).orElse("").isEmpty()
                    && Objects.equals(event.isolationScope().orElse(null), isolationScope)
                    && event.content().filter(nodeInput::equals).isPresent());
  }

  /**
   * Converts a node input into a user turn: a string or other scalar as its text, and any other
   * value, such as a map, list or record, as JSON text, like Python ADK's {@code model_dump_json}.
   * An object that JSON can't represent falls back to its {@code toString}.
   *
   * @throws IllegalArgumentException if a map or list holds a value that JSON cannot represent
   */
  @SuppressWarnings("UnusedException") // Jackson's message can quote the input, so it is dropped.
  private static Content nodeInputToUserContent(Object nodeInput) {
    if (nodeInput instanceof Content content) {
      return content.toBuilder().role(Role.USER).build();
    }
    String text;
    if (nodeInput instanceof String
        || nodeInput instanceof Number
        || nodeInput instanceof Boolean
        || nodeInput instanceof Character
        || nodeInput instanceof Enum<?>) {
      text = String.valueOf(nodeInput);
    } else {
      try {
        text = JsonBaseModel.getMapper().writeValueAsString(nodeInput);
      } catch (JsonProcessingException e) {
        if (nodeInput instanceof Map<?, ?> || nodeInput instanceof List<?>) {
          throw new IllegalArgumentException(
              "A node input map or list must hold only values that JSON can represent.");
        }
        text = String.valueOf(nodeInput);
      }
    }
    return Content.fromParts(Part.fromText(text));
  }

  // Session has no remove helper, so this locks the event list as Session.addEvent does.
  @SuppressWarnings("deprecation")
  private static void removeEvent(Session session, Event event) {
    List<Event> events = session.events();
    synchronized (events) {
      events.removeIf(event::equals);
    }
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return llmFlow
        .runLive(invocationContext)
        .doOnNext(event -> maybeSaveOutputToState(event, invocationContext));
  }

  /**
   * Constructs the text instruction for this agent based on the {@link #instruction} field. Also
   * returns a boolean indicating that state injection should be bypassed when the instruction is
   * constructed with an {@link Instruction.Provider}.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param context The context to retrieve the session state.
   * @return The resolved instruction as a {@link Single} wrapped Map.Entry. The key is the
   *     instruction string and the value is a boolean indicating if state injection should be
   *     bypassed.
   */
  public Single<Map.Entry<String, Boolean>> canonicalInstruction(ReadonlyContext context) {
    if (instruction instanceof Instruction.Static staticInstr) {
      return Single.just(Map.entry(staticInstr.instruction(), false));
    } else if (instruction instanceof Instruction.Provider provider) {
      return provider.getInstruction().apply(context).map(instr -> Map.entry(instr, true));
    }
    throw new IllegalStateException("Unknown Instruction subtype: " + instruction.getClass());
  }

  /**
   * Constructs the text global instruction for this agent based on the {@link #globalInstruction}
   * field. Also returns a boolean indicating that state injection should be bypassed when the
   * instruction is constructed with an {@link Instruction.Provider}.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param context The context to retrieve the session state.
   * @return The resolved global instruction as a {@link Single} wrapped Map.Entry. The key is the
   *     instruction string and the value is a boolean indicating if state injection should be
   *     bypassed.
   */
  public Single<Map.Entry<String, Boolean>> canonicalGlobalInstruction(ReadonlyContext context) {
    if (globalInstruction instanceof Instruction.Static staticInstr) {
      return Single.just(Map.entry(staticInstr.instruction(), false));
    } else if (globalInstruction instanceof Instruction.Provider provider) {
      return provider.getInstruction().apply(context).map(instr -> Map.entry(instr, true));
    }
    throw new IllegalStateException("Unknown Instruction subtype: " + globalInstruction.getClass());
  }

  /**
   * Constructs the list of tools for this agent based on the {@link #tools} field.
   *
   * @return The resolved list of tools as a {@link Single} wrapped list of {@link BaseTool}.
   */
  public Flowable<BaseTool> canonicalTools() {
    return canonicalTools((ReadonlyContext) null);
  }

  /**
   * Constructs the list of tools for this agent based on the {@link #tools} field.
   *
   * @param context The context to retrieve the session state.
   * @return The resolved list of tools as a {@link Single} wrapped list of {@link BaseTool}.
   */
  public Flowable<BaseTool> canonicalTools(@Nullable ReadonlyContext context) {
    List<Flowable<BaseTool>> toolFlowables = new ArrayList<>();
    for (Object toolOrToolset : toolsUnion) {
      if (toolOrToolset instanceof BaseTool baseTool) {
        toolFlowables.add(Flowable.just(baseTool));
      } else if (toolOrToolset instanceof BaseToolset baseToolset) {
        toolFlowables.add(baseToolset.getTools(context));
      } else {
        throw new IllegalArgumentException(
            "Object in tools list is not of a supported type: "
                + toolOrToolset.getClass().getName());
      }
    }
    return Flowable.concat(toolFlowables);
  }

  public Instruction instruction() {
    return instruction;
  }

  public Instruction globalInstruction() {
    return globalInstruction;
  }

  public Optional<Model> model() {
    return model;
  }

  public boolean planning() {
    return planning;
  }

  public Optional<Integer> maxSteps() {
    return maxSteps;
  }

  public Optional<GenerateContentConfig> generateContentConfig() {
    return generateContentConfig;
  }

  /**
   * Returns the configured history setting, {@link IncludeContents#DEFAULT} when unset. A {@code
   * SINGLE_TURN} workflow node overrides an unset one; see {@link #includeContentsExplicit()}.
   */
  public IncludeContents includeContents() {
    return includeContents;
  }

  /**
   * Returns whether {@link Builder#includeContents(IncludeContents)} was set, which a {@code
   * SINGLE_TURN} workflow node then uses instead of running without history.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public boolean includeContentsExplicit() {
    return includeContentsExplicit;
  }

  /** Returns how this agent converses when it runs as a workflow node, or empty without a mode. */
  @Experimental
  public Optional<AgentMode> mode() {
    return mode;
  }

  /**
   * Returns whether this agent's own mode is {@code expected}.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public boolean hasMode(AgentMode expected) {
    return mode.equals(Optional.of(expected));
  }

  /**
   * Returns whether this agent runs as a {@link AgentMode#CHAT} workflow node, either set or
   * because another agent in its tree sets a mode.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public boolean isChatNode() {
    return defaultMode() == AgentMode.CHAT;
  }

  /**
   * Returns this agent's mode, or else {@link AgentMode#CHAT} when another agent in its tree sets a
   * mode, or else null, so the agent runs as it does outside a workflow.
   */
  private @Nullable AgentMode defaultMode() {
    if (mode.isPresent()) {
      return mode.get();
    }
    return setsMode(rootAgent()) ? AgentMode.CHAT : null;
  }

  /**
   * Returns whether {@code agent} runs as a {@link AgentMode#TASK} workflow node in {@code
   * context}, so it ends its task with {@code finish_task}. A task agent run any other way runs as
   * an agent without a mode does.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean runsAsTask(BaseAgent agent, InvocationContext context) {
    return context.agentMode(agent).filter(AgentMode.TASK::equals).isPresent();
  }

  /**
   * Returns whether {@code tool} is the {@code finish_task} tool a task agent gets.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean isFinishTaskTool(Object tool) {
    return tool instanceof FinishTaskTool;
  }

  /**
   * Returns whether {@code agent} is a {@link AgentMode#SINGLE_TURN} or {@link AgentMode#TASK}
   * agent, which its parent calls as a tool rather than transfers to.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean runsAsTool(BaseAgent agent) {
    return agent instanceof LlmAgent llmAgent
        && (llmAgent.hasMode(AgentMode.SINGLE_TURN) || llmAgent.hasMode(AgentMode.TASK));
  }

  /**
   * Returns whether {@code event} ends a task with its own {@code finish_task} call that succeeded:
   * one the task agent answered, not the user or an agent it transferred to.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean finishesTask(Event event) {
    return FinishTaskTool.isSuccessResponse(event) && authoredByTaskRun(event);
  }

  /**
   * Returns whether {@code event} reports an execution error of a task's run, such as a failure,
   * rather than an error code a model gave as its finish or block reason.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean endsTaskWithError(Event event) {
    if (event.errorCode().isEmpty() || event.finishReason().isPresent()) {
      return false;
    }
    return !MODEL_ERROR_CODES.contains(event.errorCode().get().toString());
  }

  /**
   * Error codes a model response gives: finish and block reasons, as Python's {@code
   * _LLM_RESPONSE_ERROR_CODES}, and two Java-only codes for a response with no candidates and for a
   * live server message it does not recognize, outcomes Python reaches without a code.
   */
  private static final ImmutableSet<String> MODEL_ERROR_CODES =
      ImmutableSet.<String>builder()
          .addAll(Arrays.stream(FinishReason.Known.values()).map(Enum::name).iterator())
          .addAll(Arrays.stream(BlockedReason.Known.values()).map(Enum::name).iterator())
          .add("Unknown error.", "Unknown server message.")
          .build();

  /**
   * Returns whether a task's own run wrote {@code event}: its node path ends at its author, and it
   * is the run whose scope the event carries, where a task node's scope is its node path and a
   * delegated task's is its run ID.
   */
  private static boolean authoredByTaskRun(Event event) {
    Optional<NodeInfo> nodeInfo = event.nodeInfo();
    if (nodeInfo.flatMap(NodeInfo::name).filter(name -> name.equals(event.author())).isEmpty()) {
      return false;
    }
    Optional<String> scope = event.isolationScope();
    return scope.isEmpty()
        || nodeInfo.get().path().equals(scope.get())
        || nodeInfo.get().runId().equals(scope);
  }

  /**
   * Returns whether {@code tool} delegates a task to a {@link AgentMode#TASK} sub-agent, whose
   * response the delegating agent gives once the task ends.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean delegatesTask(BaseTool tool) {
    return tool instanceof TaskAgentTool;
  }

  private static boolean setsMode(BaseAgent agent) {
    return (agent instanceof LlmAgent llmAgent && llmAgent.mode.isPresent())
        || agent.subAgents().stream().anyMatch(LlmAgent::setsMode);
  }

  public Single<List<BaseTool>> tools() {
    return canonicalTools().toList();
  }

  /**
   * Returns the configured tools and toolsets, then {@code finish_task} for a {@link
   * AgentMode#TASK} agent, then one tool per {@link AgentMode#TASK} or {@link
   * AgentMode#SINGLE_TURN} sub-agent.
   */
  public List<Object> toolsUnion() {
    return toolsUnion;
  }

  public List<BaseToolset> toolsets() {
    return toolsets;
  }

  public boolean disallowTransferToParent() {
    return disallowTransferToParent;
  }

  public boolean disallowTransferToPeers() {
    return disallowTransferToPeers;
  }

  /**
   * Returns the agents this one may transfer to: its sub-agents, plus its parent and peers unless
   * disallowed, in the order the model sees them. Transfer to a parent or peer needs an {@link
   * LlmAgent} parent, and an agent its parent calls as a tool is no transfer target.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public ImmutableList<BaseAgent> transferTargets() {
    return transferTargets(/* inWorkflow= */ true);
  }

  /**
   * Returns the agents this one may transfer to in {@code context}. Outside a workflow a
   * single-turn agent cannot run as a tool, so it stays a transfer target, as before modes.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public ImmutableList<BaseAgent> transferTargets(InvocationContext context) {
    return transferTargets(/* inWorkflow= */ context.nodePath().isPresent());
  }

  private ImmutableList<BaseAgent> transferTargets(boolean inWorkflow) {
    List<BaseAgent> targets = new ArrayList<>();
    for (BaseAgent subAgent : subAgents()) {
      if (!inWorkflow || !runsAsTool(subAgent)) {
        targets.add(subAgent);
      }
    }
    BaseAgent parent = parentAgent();
    if (!(parent instanceof LlmAgent)) {
      return ImmutableList.copyOf(targets);
    }
    if (!disallowTransferToParent()) {
      targets.add(parent);
    }
    if (!disallowTransferToPeers()) {
      for (BaseAgent peer : parent.subAgents()) {
        if (!peer.name().equals(name()) && (!inWorkflow || !runsAsTool(peer))) {
          targets.add(peer);
        }
      }
    }
    return ImmutableList.copyOf(targets);
  }

  public List<? extends BeforeModelCallback> beforeModelCallback() {
    return beforeModelCallback;
  }

  public List<? extends AfterModelCallback> afterModelCallback() {
    return afterModelCallback;
  }

  public List<? extends BeforeToolCallback> beforeToolCallback() {
    return beforeToolCallback;
  }

  public List<? extends AfterToolCallback> afterToolCallback() {
    return afterToolCallback;
  }

  public List<? extends OnModelErrorCallback> onModelErrorCallback() {
    return onModelErrorCallback;
  }

  public List<? extends OnToolErrorCallback> onToolErrorCallback() {
    return onToolErrorCallback;
  }

  /**
   * The resolved beforeModelCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends BeforeModelCallback> canonicalBeforeModelCallbacks() {
    return beforeModelCallback;
  }

  /**
   * The resolved afterModelCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends AfterModelCallback> canonicalAfterModelCallbacks() {
    return afterModelCallback;
  }

  /**
   * The resolved onModelErrorCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends OnModelErrorCallback> canonicalOnModelErrorCallbacks() {
    return onModelErrorCallback;
  }

  /**
   * The resolved beforeToolCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends BeforeToolCallback> canonicalBeforeToolCallbacks() {
    return beforeToolCallback;
  }

  /**
   * The resolved afterToolCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends AfterToolCallback> canonicalAfterToolCallbacks() {
    return afterToolCallback;
  }

  /**
   * The resolved onToolErrorCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends OnToolErrorCallback> canonicalOnToolErrorCallbacks() {
    return onToolErrorCallback;
  }

  @Override
  public Optional<Schema> inputSchema() {
    return inputSchema;
  }

  @Override
  public Optional<Schema> outputSchema() {
    return outputSchema;
  }

  /**
   * Returns {@code true}, so that a workflow resuming this agent as a node runs it again and its
   * model sees the answers to its long-running calls.
   */
  @Experimental
  @Override
  public boolean rerunOnResume() {
    return true;
  }

  public Optional<Executor> executor() {
    return executor;
  }

  public Optional<String> outputKey() {
    return outputKey;
  }

  public Optional<BaseCodeExecutor> codeExecutor() {
    return codeExecutor;
  }

  public Model resolvedModel() {
    if (resolvedModel == null) {
      synchronized (this) {
        if (resolvedModel == null) {
          resolvedModel = resolveModelInternal();
        }
      }
    }
    return resolvedModel;
  }

  /**
   * Resolves the model for this agent, checking first if it is defined locally, then searching
   * through ancestors.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @return The resolved {@link Model} for this agent.
   * @throws IllegalStateException if no model is found for this agent or its ancestors.
   */
  private Model resolveModelInternal() {
    if (this.model.isPresent()) {
      Model currentModel = this.model.get();

      if (currentModel.model().isPresent()) {
        String modelName = currentModel.model().get().model();
        BaseLlm resolvedLlm = currentModel.model().get();

        return Model.builder().modelName(modelName).model(resolvedLlm).build();
      }

      if (currentModel.modelName().isPresent()) {
        String modelName = currentModel.modelName().get();
        BaseLlm resolvedLlm = LlmRegistry.getLlm(modelName);

        return Model.builder().modelName(modelName).model(resolvedLlm).build();
      }
    }
    BaseAgent current = this.parentAgent();
    while (current != null) {
      if (current instanceof LlmAgent) {
        return ((LlmAgent) current).resolvedModel();
      }
      current = current.parentAgent();
    }
    throw new IllegalStateException("No model found for agent " + name() + " or its ancestors.");
  }

  /**
   * Creates an LlmAgent from configuration with full subagent support.
   *
   * @param config the agent configuration
   * @param configAbsPath The absolute path to the agent config file. This is needed for resolving
   *     relative paths for e.g. tools and subagents.
   * @return the configured LlmAgent
   * @throws ConfigurationException if the configuration is invalid
   */
  public static LlmAgent fromConfig(LlmAgentConfig config, String configAbsPath)
      throws ConfigurationException {
    logger.debug("Creating LlmAgent from config: {}", config.name());

    Builder builder = LlmAgent.builder();
    ConfigAgentUtils.resolveAndSetCommonAgentFields(builder, config, configAbsPath);

    if (config.instruction() == null || config.instruction().trim().isEmpty()) {
      throw new ConfigurationException("Agent instruction is required");
    }

    builder.instruction(config.instruction());

    if (config.model() != null && !config.model().trim().isEmpty()) {
      builder.model(config.model());
    }

    try {
      if (config.tools() != null) {
        builder.tools(ToolResolver.resolveToolsAndToolsets(config.tools(), configAbsPath));
      }
    } catch (ConfigurationException e) {
      throw new ConfigurationException("Error resolving tools for agent " + config.name(), e);
    }

    // Set optional transfer configuration
    if (config.disallowTransferToParent() != null) {
      builder.disallowTransferToParent(config.disallowTransferToParent());
    }

    if (config.disallowTransferToPeers() != null) {
      builder.disallowTransferToPeers(config.disallowTransferToPeers());
    }

    // Set optional output key
    if (config.outputKey() != null && !config.outputKey().trim().isEmpty()) {
      builder.outputKey(config.outputKey());
    }

    // Set optional include_contents
    if (config.includeContents() != null) {
      builder.includeContents(config.includeContents());
    }

    // Set optional generateContentConfig
    if (config.generateContentConfig() != null) {
      builder.generateContentConfig(config.generateContentConfig());
    }

    // Resolve callbacks if configured
    setCallbacksFromConfig(config, builder);

    // Build and return the agent
    LlmAgent agent = builder.build();
    logger.info(
        "Successfully created LlmAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
  }

  @Override
  public Completable close() {
    List<Completable> completables = new ArrayList<>();
    toolsets()
        .forEach(toolset ->
            completables.add(
                Completable.fromAction(() -> {
                  try {
                    toolset.close();
                  } catch (Exception e) {
                    logger.error("Failed to close toolset", e);
                    throw e;
                  }
                })));
    completables.add(super.close());
    return Completable.mergeDelayError(completables);
  }

  private static void setCallbacksFromConfig(LlmAgentConfig config, Builder builder)
      throws ConfigurationException {
    ConfigAgentUtils.resolveAndSetCallback(
        config.beforeModelCallbacks(),
        Callbacks.BeforeModelCallbackBase.class,
        "before_model_callback",
        builder::beforeModelCallback);
    ConfigAgentUtils.resolveAndSetCallback(
        config.afterModelCallbacks(),
        Callbacks.AfterModelCallbackBase.class,
        "after_model_callback",
        builder::afterModelCallback);
    ConfigAgentUtils.resolveAndSetCallback(
        config.beforeToolCallbacks(),
        Callbacks.BeforeToolCallbackBase.class,
        "before_tool_callback",
        builder::beforeToolCallback);
    ConfigAgentUtils.resolveAndSetCallback(
        config.afterToolCallbacks(),
        Callbacks.AfterToolCallbackBase.class,
        "after_tool_callback",
        builder::afterToolCallback);
  }
}
