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

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.SchemaUtils;
import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.AfterAgentCallbackBase;
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.AfterModelCallbackBase;
import com.google.adk.agents.Callbacks.AfterModelCallbackSync;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.AfterToolCallbackBase;
import com.google.adk.agents.Callbacks.AfterToolCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackBase;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallbackBase;
import com.google.adk.agents.Callbacks.BeforeModelCallbackSync;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallbackBase;
import com.google.adk.agents.Callbacks.BeforeToolCallbackSync;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.events.Event;
import com.google.adk.examples.BaseExampleProvider;
import com.google.adk.examples.Example;
import com.google.adk.flows.llmflows.AutoFlow;
import com.google.adk.flows.llmflows.BaseLlmFlow;
import com.google.adk.flows.llmflows.SingleFlow;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.Model;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The LLM-based agent. */
public class LlmAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(LlmAgent.class);

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
  // TODO: Remove exampleProvider field - examples should only be provided via ExampleTool
  private final Optional<BaseExampleProvider> exampleProvider;
  private final IncludeContents includeContents;

  private final boolean planning;
  private final Optional<Integer> maxSteps;
  private final boolean disallowTransferToParent;
  private final boolean disallowTransferToPeers;
  private final Optional<List<? extends BeforeModelCallback>> beforeModelCallback;
  private final Optional<List<? extends AfterModelCallback>> afterModelCallback;
  private final Optional<List<? extends BeforeToolCallback>> beforeToolCallback;
  private final Optional<List<? extends AfterToolCallback>> afterToolCallback;
  private final Optional<Schema> inputSchema;
  private final Optional<Schema> outputSchema;
  private final Optional<Executor> executor;
  private final Optional<String> outputKey;
  private final Optional<BaseCodeExecutor> codeExecutor;

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
    this.instruction =
        builder.instruction == null ? new Instruction.Static("") : builder.instruction;
    this.globalInstruction =
        builder.globalInstruction == null ? new Instruction.Static("") : builder.globalInstruction;
    this.generateContentConfig = Optional.ofNullable(builder.generateContentConfig);
    this.exampleProvider = Optional.ofNullable(builder.exampleProvider);
    this.includeContents =
        builder.includeContents != null ? builder.includeContents : IncludeContents.DEFAULT;
    this.planning = builder.planning != null && builder.planning;
    this.maxSteps = Optional.ofNullable(builder.maxSteps);
    this.disallowTransferToParent = builder.disallowTransferToParent;
    this.disallowTransferToPeers = builder.disallowTransferToPeers;
    this.beforeModelCallback = Optional.ofNullable(builder.beforeModelCallback);
    this.afterModelCallback = Optional.ofNullable(builder.afterModelCallback);
    this.beforeToolCallback = Optional.ofNullable(builder.beforeToolCallback);
    this.afterToolCallback = Optional.ofNullable(builder.afterToolCallback);
    this.inputSchema = Optional.ofNullable(builder.inputSchema);
    this.outputSchema = Optional.ofNullable(builder.outputSchema);
    this.executor = Optional.ofNullable(builder.executor);
    this.outputKey = Optional.ofNullable(builder.outputKey);
    this.toolsUnion = builder.toolsUnion != null ? builder.toolsUnion : ImmutableList.of();
    this.toolsets = extractToolsets(this.toolsUnion);
    this.codeExecutor = Optional.ofNullable(builder.codeExecutor);

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
  public static class Builder {
    private String name;
    private String description;

    private Model model;

    private Instruction instruction;
    private Instruction globalInstruction;
    private ImmutableList<BaseAgent> subAgents;
    private ImmutableList<Object> toolsUnion;
    private GenerateContentConfig generateContentConfig;
    private BaseExampleProvider exampleProvider;
    private IncludeContents includeContents;
    private Boolean planning;
    private Integer maxSteps;
    private Boolean disallowTransferToParent;
    private Boolean disallowTransferToPeers;
    private ImmutableList<? extends BeforeModelCallback> beforeModelCallback;
    private ImmutableList<? extends AfterModelCallback> afterModelCallback;
    private ImmutableList<? extends BeforeAgentCallback> beforeAgentCallback;
    private ImmutableList<? extends AfterAgentCallback> afterAgentCallback;
    private ImmutableList<? extends BeforeToolCallback> beforeToolCallback;
    private ImmutableList<? extends AfterToolCallback> afterToolCallback;
    private Schema inputSchema;
    private Schema outputSchema;
    private Executor executor;
    private String outputKey;
    private BaseCodeExecutor codeExecutor;

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder description(String description) {
      this.description = description;
      return this;
    }

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
    public Builder subAgents(List<? extends BaseAgent> subAgents) {
      this.subAgents = ImmutableList.copyOf(subAgents);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder subAgents(BaseAgent... subAgents) {
      this.subAgents = ImmutableList.copyOf(subAgents);
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

    // TODO: Remove these example provider methods and only use ExampleTool for providing examples.
    // Direct example methods should be deprecated in favor of using ExampleTool consistently.
    @CanIgnoreReturnValue
    public Builder exampleProvider(BaseExampleProvider exampleProvider) {
      this.exampleProvider = exampleProvider;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder exampleProvider(List<Example> examples) {
      this.exampleProvider = (query) -> examples;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder exampleProvider(Example... examples) {
      this.exampleProvider = (query) -> ImmutableList.copyOf(examples);
      return this;
    }

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

    @CanIgnoreReturnValue
    public Builder beforeModelCallback(BeforeModelCallback beforeModelCallback) {
      this.beforeModelCallback = ImmutableList.of(beforeModelCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeModelCallback(List<BeforeModelCallbackBase> beforeModelCallback) {
      if (beforeModelCallback == null) {
        this.beforeModelCallback = null;
      } else if (beforeModelCallback.isEmpty()) {
        this.beforeModelCallback = ImmutableList.of();
      } else {
        ImmutableList.Builder<BeforeModelCallback> builder = ImmutableList.builder();
        for (BeforeModelCallbackBase callback : beforeModelCallback) {
          if (callback instanceof BeforeModelCallback beforeModelCallbackInstance) {
            builder.add(beforeModelCallbackInstance);
          } else if (callback instanceof BeforeModelCallbackSync beforeModelCallbackSyncInstance) {
            builder.add(
                (BeforeModelCallback)
                    (callbackContext, llmRequestBuilder) ->
                        Maybe.fromOptional(
                            beforeModelCallbackSyncInstance.call(
                                callbackContext, llmRequestBuilder)));
          } else {
            logger.warn(
                "Invalid beforeModelCallback callback type: %s. Ignoring this callback.",
                callback.getClass().getName());
          }
        }
        this.beforeModelCallback = builder.build();
      }

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
    public Builder afterModelCallback(List<AfterModelCallbackBase> afterModelCallback) {
      if (afterModelCallback == null) {
        this.afterModelCallback = null;
      } else if (afterModelCallback.isEmpty()) {
        this.afterModelCallback = ImmutableList.of();
      } else {
        ImmutableList.Builder<AfterModelCallback> builder = ImmutableList.builder();
        for (AfterModelCallbackBase callback : afterModelCallback) {
          if (callback instanceof AfterModelCallback afterModelCallbackInstance) {
            builder.add(afterModelCallbackInstance);
          } else if (callback instanceof AfterModelCallbackSync afterModelCallbackSyncInstance) {
            builder.add(
                (AfterModelCallback)
                    (callbackContext, llmResponse) ->
                        Maybe.fromOptional(
                            afterModelCallbackSyncInstance.call(callbackContext, llmResponse)));
          } else {
            logger.warn(
                "Invalid afterModelCallback callback type: %s. Ignoring this callback.",
                callback.getClass().getName());
          }
        }
        this.afterModelCallback = builder.build();
      }

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
    public Builder beforeAgentCallback(BeforeAgentCallback beforeAgentCallback) {
      this.beforeAgentCallback = ImmutableList.of(beforeAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallback(List<BeforeAgentCallbackBase> beforeAgentCallback) {
      this.beforeAgentCallback = CallbackUtil.getBeforeAgentCallbacks(beforeAgentCallback);
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
    public Builder afterAgentCallback(AfterAgentCallback afterAgentCallback) {
      this.afterAgentCallback = ImmutableList.of(afterAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallback(List<AfterAgentCallbackBase> afterAgentCallback) {
      this.afterAgentCallback = CallbackUtil.getAfterAgentCallbacks(afterAgentCallback);
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
      if (beforeToolCallbacks == null) {
        this.beforeToolCallback = null;
      } else if (beforeToolCallbacks.isEmpty()) {
        this.beforeToolCallback = ImmutableList.of();
      } else {
        ImmutableList.Builder<BeforeToolCallback> builder = ImmutableList.builder();
        for (BeforeToolCallbackBase callback : beforeToolCallbacks) {
          if (callback instanceof BeforeToolCallback beforeToolCallbackInstance) {
            builder.add(beforeToolCallbackInstance);
          } else if (callback instanceof BeforeToolCallbackSync beforeToolCallbackSyncInstance) {
            builder.add(
                (invocationContext, baseTool, input, toolContext) ->
                    Maybe.fromOptional(
                        beforeToolCallbackSyncInstance.call(
                            invocationContext, baseTool, input, toolContext)));
          } else {
            logger.warn(
                "Invalid beforeToolCallback callback type: {}. Ignoring this callback.",
                callback.getClass().getName());
          }
        }
        this.beforeToolCallback = builder.build();
      }
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
    public Builder afterToolCallback(@Nullable List<AfterToolCallbackBase> afterToolCallbacks) {
      if (afterToolCallbacks == null) {
        this.afterToolCallback = null;
      } else if (afterToolCallbacks.isEmpty()) {
        this.afterToolCallback = ImmutableList.of();
      } else {
        ImmutableList.Builder<AfterToolCallback> builder = ImmutableList.builder();
        for (AfterToolCallbackBase callback : afterToolCallbacks) {
          if (callback instanceof AfterToolCallback afterToolCallbackInstance) {
            builder.add(afterToolCallbackInstance);
          } else if (callback instanceof AfterToolCallbackSync afterToolCallbackSyncInstance) {
            builder.add(
                (invocationContext, baseTool, input, toolContext, response) ->
                    Maybe.fromOptional(
                        afterToolCallbackSyncInstance.call(
                            invocationContext, baseTool, input, toolContext, response)));
          } else {
            logger.warn(
                "Invalid afterToolCallback callback type: {}. Ignoring this callback.",
                callback.getClass().getName());
          }
        }
        this.afterToolCallback = builder.build();
      }
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
    public Builder inputSchema(Schema inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputSchema(Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

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

    protected void validate() {
      this.disallowTransferToParent =
          this.disallowTransferToParent != null && this.disallowTransferToParent;
      this.disallowTransferToPeers =
          this.disallowTransferToPeers != null && this.disallowTransferToPeers;

      if (this.outputSchema != null) {
        if (!this.disallowTransferToParent || !this.disallowTransferToPeers) {
          System.err.println(
              "Warning: Invalid config for agent "
                  + this.name
                  + ": outputSchema cannot co-exist with agent transfer"
                  + " configurations. Setting disallowTransferToParent=true and"
                  + " disallowTransferToPeers=true.");
          this.disallowTransferToParent = true;
          this.disallowTransferToPeers = true;
        }

        if (this.subAgents != null && !this.subAgents.isEmpty()) {
          throw new IllegalArgumentException(
              "Invalid config for agent "
                  + this.name
                  + ": if outputSchema is set, subAgents must be empty to disable agent"
                  + " transfer.");
        }
        if (this.toolsUnion != null && !this.toolsUnion.isEmpty()) {
          throw new IllegalArgumentException(
              "Invalid config for agent "
                  + this.name
                  + ": if outputSchema is set, tools must be empty.");
        }
      }
    }

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

  private void maybeSaveOutputToState(Event event) {
    if (outputKey().isPresent() && event.finalResponse() && event.content().isPresent()) {
      // Concatenate text from all parts.
      Object output;
      String rawResult =
          event.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
              .map(part -> part.text().orElse(""))
              .collect(joining());

      Optional<Schema> outputSchema = outputSchema();
      if (outputSchema.isPresent()) {
        try {
          Map<String, Object> validatedMap =
              SchemaUtils.validateOutputSchema(rawResult, outputSchema.get());
          output = validatedMap;
        } catch (JsonProcessingException e) {
          logger.error(
              "LlmAgent output for outputKey '{}' was not valid JSON, despite an outputSchema being"
                  + " present. Saving raw output to state.",
              outputKey().get(),
              e);
          output = rawResult;
        } catch (IllegalArgumentException e) {
          logger.error(
              "LlmAgent output for outputKey '{}' did not match the outputSchema. Saving raw output"
                  + " to state.",
              outputKey().get(),
              e);
          output = rawResult;
        }
      } else {
        output = rawResult;
      }
      event.actions().stateDelta().put(outputKey().get(), output);
    }
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return llmFlow.run(invocationContext).doOnNext(this::maybeSaveOutputToState);
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return llmFlow.runLive(invocationContext).doOnNext(this::maybeSaveOutputToState);
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
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param context The context to retrieve the session state.
   * @return The resolved list of tools as a {@link Single} wrapped list of {@link BaseTool}.
   */
  public Flowable<BaseTool> canonicalTools(Optional<ReadonlyContext> context) {
    List<Flowable<BaseTool>> toolFlowables = new ArrayList<>();
    for (Object toolOrToolset : toolsUnion) {
      if (toolOrToolset instanceof BaseTool baseTool) {
        toolFlowables.add(Flowable.just(baseTool));
      } else if (toolOrToolset instanceof BaseToolset baseToolset) {
        toolFlowables.add(baseToolset.getTools(context.orElse(null)));
      } else {
        throw new IllegalArgumentException(
            "Object in tools list is not of a supported type: "
                + toolOrToolset.getClass().getName());
      }
    }
    return Flowable.concat(toolFlowables);
  }

  /** Overload of canonicalTools that defaults to an empty context. */
  public Flowable<BaseTool> canonicalTools() {
    return canonicalTools(Optional.empty());
  }

  /** Convenience overload of canonicalTools that accepts a non-optional ReadonlyContext. */
  public Flowable<BaseTool> canonicalTools(ReadonlyContext context) {
    return canonicalTools(Optional.ofNullable(context));
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

  // TODO: Remove this getter - examples should only be provided via ExampleTool
  public Optional<BaseExampleProvider> exampleProvider() {
    return exampleProvider;
  }

  public IncludeContents includeContents() {
    return includeContents;
  }

  public List<BaseTool> tools() {
    return canonicalTools().toList().blockingGet();
  }

  public List<Object> toolsUnion() {
    return toolsUnion;
  }

  public ImmutableList<BaseToolset> toolsets() {
    return toolsets;
  }

  public boolean disallowTransferToParent() {
    return disallowTransferToParent;
  }

  public boolean disallowTransferToPeers() {
    return disallowTransferToPeers;
  }

  public Optional<List<? extends BeforeModelCallback>> beforeModelCallback() {
    return beforeModelCallback;
  }

  public Optional<List<? extends AfterModelCallback>> afterModelCallback() {
    return afterModelCallback;
  }

  public Optional<List<? extends BeforeToolCallback>> beforeToolCallback() {
    return beforeToolCallback;
  }

  public Optional<List<? extends AfterToolCallback>> afterToolCallback() {
    return afterToolCallback;
  }

  public Optional<Schema> inputSchema() {
    return inputSchema;
  }

  public Optional<Schema> outputSchema() {
    return outputSchema;
  }

  public Optional<Executor> executor() {
    return executor;
  }

  public Optional<String> outputKey() {
    return outputKey;
  }

  @Nullable
  public BaseCodeExecutor codeExecutor() {
    return codeExecutor.orElse(null);
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
        return currentModel;
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

    // Validate required fields
    if (config.name() == null || config.name().trim().isEmpty()) {
      throw new ConfigurationException("Agent name is required");
    }

    if (config.instruction() == null || config.instruction().trim().isEmpty()) {
      throw new ConfigurationException("Agent instruction is required");
    }

    // Create builder with required fields
    Builder builder =
        LlmAgent.builder()
            .name(config.name())
            .description(nullToEmpty(config.description()))
            .instruction(config.instruction());

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
    // Resolve and add subagents using the utility class
    if (config.subAgents() != null && !config.subAgents().isEmpty()) {
      ImmutableList<BaseAgent> subAgents =
          ConfigAgentUtils.resolveSubAgents(config.subAgents(), configAbsPath);
      builder.subAgents(subAgents);
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

  private static void setCallbacksFromConfig(LlmAgentConfig config, Builder builder)
      throws ConfigurationException {
    setCallbackFromConfig(
        config.beforeAgentCallbacks(),
        Callbacks.BeforeAgentCallbackBase.class,
        "before_agent_callback",
        builder::beforeAgentCallback);
    setCallbackFromConfig(
        config.afterAgentCallbacks(),
        Callbacks.AfterAgentCallbackBase.class,
        "after_agent_callback",
        builder::afterAgentCallback);
    setCallbackFromConfig(
        config.beforeModelCallbacks(),
        Callbacks.BeforeModelCallbackBase.class,
        "before_model_callback",
        builder::beforeModelCallback);
    setCallbackFromConfig(
        config.afterModelCallbacks(),
        Callbacks.AfterModelCallbackBase.class,
        "after_model_callback",
        builder::afterModelCallback);
    setCallbackFromConfig(
        config.beforeToolCallbacks(),
        Callbacks.BeforeToolCallbackBase.class,
        "before_tool_callback",
        builder::beforeToolCallback);
    setCallbackFromConfig(
        config.afterToolCallbacks(),
        Callbacks.AfterToolCallbackBase.class,
        "after_tool_callback",
        builder::afterToolCallback);
  }

  private static <T> void setCallbackFromConfig(
      @Nullable List<LlmAgentConfig.CallbackRef> refs,
      Class<T> callbackBaseClass,
      String callbackTypeName,
      Consumer<ImmutableList<T>> builderSetter)
      throws ConfigurationException {
    if (refs != null) {
      ImmutableList.Builder<T> list = ImmutableList.builder();
      for (LlmAgentConfig.CallbackRef ref : refs) {
        list.add(
            ComponentRegistry.getInstance()
                .get(ref.name(), callbackBaseClass)
                .orElseThrow(
                    () ->
                        new ConfigurationException(
                            "Invalid " + callbackTypeName + ": " + ref.name())));
      }
      builderSetter.accept(list.build());
    }
  }
}
