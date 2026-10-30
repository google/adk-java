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
import com.google.adk.flows.llmflows.AutoFlow;
import com.google.adk.flows.llmflows.BaseLlmFlow;
import com.google.adk.flows.llmflows.SingleFlow;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.Model;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.workflow.NodeInfo;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private final @Nullable AgentMode mode;

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
    this.toolsUnion = requireNonNullElse(builder.toolsUnion, ImmutableList.of());
    this.toolsets = extractToolsets(this.toolsUnion);
    this.codeExecutor = Optional.ofNullable(builder.codeExecutor);
    this.mode = builder.mode;

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
          ImmutableList.of(
              (callbackContext, llmRequestBuilder) ->
                  Maybe.fromOptional(
                      beforeModelCallbackSync.call(callbackContext, llmRequestBuilder)));
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
          ImmutableList.of(
              (callbackContext, llmResponse) ->
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
          ImmutableList.of(
              (callbackContext, llmRequest, error) ->
                  Maybe.fromOptional(
                      onModelErrorCallbackSync.call(callbackContext, llmRequest, error)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallbackSync(BeforeAgentCallbackSync beforeAgentCallbackSync) {
      this.beforeAgentCallback =
          ImmutableList.of(
              (callbackContext) ->
                  Maybe.fromOptional(beforeAgentCallbackSync.call(callbackContext)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallbackSync(AfterAgentCallbackSync afterAgentCallbackSync) {
      this.afterAgentCallback =
          ImmutableList.of(
              (callbackContext) ->
                  Maybe.fromOptional(afterAgentCallbackSync.call(callbackContext)));
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
          ImmutableList.of(
              (invocationContext, baseTool, input, toolContext) ->
                  Maybe.fromOptional(
                      beforeToolCallbackSync.call(
                          invocationContext, baseTool, input, toolContext)));
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
          ImmutableList.of(
              (invocationContext, baseTool, input, toolContext, response) ->
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
          ImmutableList.of(
              (invocationContext, baseTool, input, toolContext, error) ->
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
     * as it does outside a workflow.
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
          .map(
              c ->
                  c.stream()
                      .map(
                          callback -> {
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
    if (outputKey().isEmpty() || !event.finalResponse() || event.content().isEmpty()) {
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
        output = SchemaUtils.validateOutputSchema(rawResult, outputSchema.get());
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
    if (!invocationContext.isResumable()) {
      return llmFlow
          .run(invocationContext)
          .doOnNext(event -> maybeSaveOutputToState(event, invocationContext));
    }
    return Flowable.defer(
        () -> {
          // Resumed after a transfer: continue the sub-agent instead of re-invoking the model.
          if (invocationContext.agentStates().containsKey(name())) {
            Optional<BaseAgent> resumeTarget = findSubAgentToResume(invocationContext);
            if (resumeTarget.isPresent()) {
              AtomicBoolean resumePaused = new AtomicBoolean(false);
              return resumeTarget
                  .get()
                  .runAsync(invocationContext)
                  .doOnNext(
                      event -> {
                        if (invocationContext.shouldPauseInvocation(event)) {
                          resumePaused.set(true);
                        }
                      })
                  .concatWith(
                      Flowable.defer(
                          () -> {
                            if (resumePaused.get()) {
                              return Flowable.<Event>empty();
                            }
                            return endOfAgentAndRecord(invocationContext);
                          }));
            }
          }
          // Normal path: emit an end-of-agent checkpoint so a later run can skip this agent.
          Flowable<Event> events =
              llmFlow
                  .run(invocationContext)
                  .doOnNext(event -> maybeSaveOutputToState(event, invocationContext));
          AtomicBoolean paused = new AtomicBoolean(false);
          return events
              .doOnNext(
                  event -> {
                    if (invocationContext.shouldPauseInvocation(event)) {
                      paused.set(true);
                    }
                  })
              .concatWith(
                  Flowable.defer(
                      () -> {
                        // Only the checkpoint is withheld, as in Python; the flow still runs. A
                        // transferring agent stays open so a resume re-enters it and reaches the
                        // sub-agent it handed off to.
                        if (paused.get() || invocationContext.lastEventsPauseInvocation()) {
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
    if (!name().equals(event.author())
        || event.functionResponses().stream()
            .noneMatch(
                response -> response.name().filter(TRANSFER_TO_AGENT_TOOL::equals).isPresent())) {
      return Optional.empty();
    }
    return event.actions().transferToAgent().filter(target -> !target.equals(name()));
  }

  /**
   * Resolves a transfer target recorded in history back to its agent. A live transfer fails in
   * {@code BaseLlmFlow} when the target is gone; a resumed one has no such check, and returning
   * empty here would silently re-run this agent instead of the sub-agent it handed off to.
   */
  private BaseAgent resolveTransferTarget(String targetName) {
    // Declared targets first, as in Python, so a same-named agent on an unrelated branch is not
    // resumed into; then the same tree-wide search the live transfer path uses.
    for (BaseAgent target : transferTargets()) {
      if (target.name().equals(targetName)) {
        return target;
      }
    }
    return rootAgent()
        .findAgent(targetName)
        .orElseThrow(
            () ->
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
      return transferTargetNameFrom(lastEvent).map(this::resolveTransferTarget);
    }
    if (Objects.equals(lastEvent.author(), Role.USER) && !lastEvent.functionResponses().isEmpty()) {
      // IAE (not ISE): an unresolvable resume surfaces through Runner.runAsync's IAE contract.
      Event functionCallEvent =
          context
              .findMatchingFunctionCall(lastEvent)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "No matching function call to resume agent "
                              + name()
                              + " from a function response."));
      if (name().equals(functionCallEvent.author())) {
        return Optional.empty();
      }
    }
    // A user event answering no call falls through; Python fails the resume instead.
    for (int i = events.size() - 2; i >= 0; i--) {
      Optional<String> targetName = transferTargetNameFrom(events.get(i));
      if (targetName.isPresent()) {
        return Optional.of(resolveTransferTarget(targetName.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * Runs this agent as a workflow node. A {@link AgentMode#SINGLE_TURN} agent sees no history
   * unless {@link Builder#includeContents} was set, gets the node input as its user turn, and gives
   * its first final model turn as the node output. An agent without a mode runs as {@link
   * BaseAgent#runNode} runs it.
   */
  @Experimental
  @Override
  public Flowable<Event> runNode(Context context, @Nullable Object nodeInput) {
    if (mode != AgentMode.SINGLE_TURN) {
      return super.runNode(context, nodeInput);
    }
    InvocationContext invocationContext = context.invocationContext();
    Flowable<Event> run =
        runAsNode(
            context, invocationContext.toBuilder().agentMode(this, AgentMode.SINGLE_TURN).build());
    // Eager disposal removes the input before the run ends, so a successor never sees it.
    return Flowable.using(
        () -> injectNodeInput(context, nodeInput),
        unused -> {
          // The first final turn with an output is the node's output; later content is not.
          AtomicBoolean outputMarked = new AtomicBoolean();
          return run.map(
              event -> {
                if (outputMarked.get()) {
                  return event;
                }
                Optional<Event> marked = markOutputIfFinalTurn(event);
                outputMarked.set(marked.isPresent());
                return marked.orElse(event);
              });
        },
        injected -> injected.ifPresent(event -> removeInput(invocationContext, event)),
        /* eager= */ true);
  }

  /**
   * Returns {@code event} carrying the node output when it is this agent's final model turn with
   * text, or else empty. Without an output schema, the event's message is the output.
   *
   * @throws IllegalArgumentException if the text does not match the output schema
   */
  private Optional<Event> markOutputIfFinalTurn(Event event) {
    Optional<Content> content =
        event.content().filter(c -> c.role().filter(Role.MODEL::equals).isPresent());
    if (!name().equals(event.author())
        || !event.finalResponse()
        || !event.functionCalls().isEmpty()
        || content.isEmpty()) {
      return Optional.empty();
    }
    String text = visibleText(content.get());
    Optional<Schema> schema = outputSchema();
    Object output =
        schema.isPresent()
            ? SchemaUtils.parseModelOutput(text, schema.get())
            : (text.isBlank() ? null : text.trim());
    if (output == null) {
      return Optional.empty();
    }
    Event.Builder marked = event.toBuilder().output(output);
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
    if (nodeInput == null
        || !context.resumeInputs().isEmpty()
        || isRecordedUserMessage(invocationContext, nodeInput)) {
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
            .timestamp(invocationContext.now().toEpochMilli())
            .build();
    invocationContext.session().addEvent(event);
    invocationContext.trackNodeInput(event, /* added= */ true);
    return Optional.of(event);
  }

  // As in ADK Kotlin; Python ADK adds the user message again and relies on its no-history default.
  private static boolean isRecordedUserMessage(
      InvocationContext invocationContext, Object nodeInput) {
    return invocationContext.userContent().filter(nodeInput::equals).isPresent()
        && invocationContext.session().immutableEvents().stream()
            .anyMatch(
                event ->
                    invocationContext.invocationId().equals(event.invocationId())
                        && Objects.equals(event.author(), Role.USER)
                        && event.nodeInfo().map(NodeInfo::path).orElse("").isEmpty()
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
  private static void removeInput(InvocationContext invocationContext, Event event) {
    List<Event> events = invocationContext.session().events();
    synchronized (events) {
      events.removeIf(event::equals);
    }
    invocationContext.trackNodeInput(event, /* added= */ false);
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
   * SINGLE_TURN} workflow node overrides an unset one; see {@link #canonicalIncludeContents}.
   */
  public IncludeContents includeContents() {
    return includeContents;
  }

  /**
   * Returns how much history this agent sees in {@code context}: a single-turn workflow node sees
   * none unless {@link Builder#includeContents} was set.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public IncludeContents canonicalIncludeContents(InvocationContext context) {
    if (!includeContentsExplicit && runsSingleTurn(context)) {
      return IncludeContents.NONE;
    }
    return includeContents();
  }

  /** Returns how this agent converses when it runs as a workflow node, or empty without a mode. */
  @Experimental
  public Optional<AgentMode> mode() {
    return Optional.ofNullable(mode);
  }

  public Single<List<BaseTool>> tools() {
    return canonicalTools().toList();
  }

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
   * disallowed. Transfer to a parent or peer needs an {@link LlmAgent} parent. Package-private
   * because {@code AgentTransfer} cannot reach it and keeps its own copy of the same rule.
   */
  ImmutableList<BaseAgent> transferTargets() {
    List<BaseAgent> targets = new ArrayList<>(subAgents());
    BaseAgent parent = parentAgent();
    if (!(parent instanceof LlmAgent)) {
      return ImmutableList.copyOf(targets);
    }
    if (!disallowTransferToParent()) {
      targets.add(parent);
    }
    if (!disallowTransferToPeers()) {
      for (BaseAgent peer : parent.subAgents()) {
        if (!peer.name().equals(name())) {
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
        .forEach(
            toolset ->
                completables.add(
                    Completable.fromAction(
                        () -> {
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
