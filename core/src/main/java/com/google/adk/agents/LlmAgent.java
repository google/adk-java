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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
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
import com.google.adk.tools.BaseTool.ToolArgsConfig;
import com.google.adk.tools.BaseTool.ToolConfig;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
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
    NONE
  }

  private final Optional<Model> model;
  private final Instruction instruction;
  private final Instruction globalInstruction;
  private final List<Object> toolsUnion;
  private final Optional<GenerateContentConfig> generateContentConfig;
  private final Optional<BaseExampleProvider> exampleProvider;
  private final IncludeContents includeContents;

  private final boolean planning;
  private final Optional<Integer> maxSteps;
  private final boolean disallowTransferToParent;
  private final boolean disallowTransferToPeers;
  private final Optional<List<BeforeModelCallback>> beforeModelCallback;
  private final Optional<List<AfterModelCallback>> afterModelCallback;
  private final Optional<List<BeforeToolCallback>> beforeToolCallback;
  private final Optional<List<AfterToolCallback>> afterToolCallback;
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
    this.codeExecutor = Optional.ofNullable(builder.codeExecutor);

    this.llmFlow = determineLlmFlow();

    // Validate name not empty.
    Preconditions.checkArgument(!this.name().isEmpty(), "Agent name cannot be empty.");
  }

  /** Returns a {@link Builder} for {@link LlmAgent}. */
  public static Builder builder() {
    return new Builder();
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
    private ImmutableList<BeforeModelCallback> beforeModelCallback;
    private ImmutableList<AfterModelCallback> afterModelCallback;
    private ImmutableList<BeforeAgentCallback> beforeAgentCallback;
    private ImmutableList<AfterAgentCallback> afterAgentCallback;
    private ImmutableList<BeforeToolCallback> beforeToolCallback;
    private ImmutableList<AfterToolCallback> afterToolCallback;
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

    @CanIgnoreReturnValue
    public Builder exampleProvider(BaseExampleProvider exampleProvider) {
      this.exampleProvider = exampleProvider;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder exampleProvider(List<Example> examples) {
      this.exampleProvider =
          new BaseExampleProvider() {
            @Override
            public List<Example> getExamples(String query) {
              return examples;
            }
          };
      return this;
    }

    @CanIgnoreReturnValue
    public Builder exampleProvider(Example... examples) {
      this.exampleProvider =
          new BaseExampleProvider() {
            @Override
            public ImmutableList<Example> getExamples(String query) {
              return ImmutableList.copyOf(examples);
            }
          };
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
                    (callbackContext, llmRequest) ->
                        Maybe.fromOptional(
                            beforeModelCallbackSyncInstance.call(callbackContext, llmRequest)));
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
              (callbackContext, llmRequest) ->
                  Maybe.fromOptional(beforeModelCallbackSync.call(callbackContext, llmRequest)));
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
    public Builder beforeToolCallback(@Nullable List<BeforeToolCallbackBase> beforeToolCallbacks) {
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
          System.err.println(
              "Error: LlmAgent output for outputKey '"
                  + outputKey().get()
                  + "' was not valid JSON, despite an outputSchema being present."
                  + " Saving raw output to state. Error: "
                  + e.getMessage());
          output = rawResult;
        } catch (IllegalArgumentException e) {
          System.err.println(
              "Error: LlmAgent output for outputKey '"
                  + outputKey().get()
                  + "' did not match the outputSchema."
                  + " Saving raw output to state. Error: "
                  + e.getMessage());
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

  public boolean disallowTransferToParent() {
    return disallowTransferToParent;
  }

  public boolean disallowTransferToPeers() {
    return disallowTransferToPeers;
  }

  public Optional<List<BeforeModelCallback>> beforeModelCallback() {
    return beforeModelCallback;
  }

  public Optional<List<AfterModelCallback>> afterModelCallback() {
    return afterModelCallback;
  }

  public Optional<List<BeforeToolCallback>> beforeToolCallback() {
    return beforeToolCallback;
  }

  public Optional<List<AfterToolCallback>> afterToolCallback() {
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
        builder.tools(resolveTools(config.tools(), configAbsPath));
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

    // Build and return the agent
    LlmAgent agent = builder.build();
    logger.info(
        "Successfully created LlmAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
  }

  /**
   * Resolves a list of tool configurations into {@link BaseTool} instances.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param toolConfigs The list of tool configurations to resolve.
   * @param configAbsPath The absolute path to the agent config file currently being processed. This
   *     path can be used to resolve relative paths for tool configurations, if necessary.
   * @return An immutable list of resolved {@link BaseTool} instances.
   * @throws ConfigurationException if any tool configuration is invalid (e.g., missing name), if a
   *     tool cannot be found by its name or class, or if tool instantiation fails.
   */
  static ImmutableList<BaseTool> resolveTools(List<ToolConfig> toolConfigs, String configAbsPath)
      throws ConfigurationException {

    if (toolConfigs == null || toolConfigs.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<BaseTool> resolvedTools = ImmutableList.builder();

    for (ToolConfig toolConfig : toolConfigs) {
      try {
        if (isNullOrEmpty(toolConfig.name())) {
          throw new ConfigurationException("Tool name cannot be empty");
        }

        String toolName = toolConfig.name().trim();

        // Option 1: Try to resolve as a tool instance
        BaseTool tool = resolveToolInstance(toolName);
        if (tool != null) {
          resolvedTools.add(tool);
          logger.debug("Successfully resolved tool instance: {}", toolName);
          continue;
        }

        // Option 2: Try to resolve as a tool class (with or without args)
        BaseTool toolFromClass = resolveToolFromClass(toolName, toolConfig.args());
        if (toolFromClass != null) {
          resolvedTools.add(toolFromClass);
          logger.debug("Successfully resolved tool from class: {}", toolName);
          continue;
        }

        throw new ConfigurationException("Tool not found: " + toolName);

      } catch (Exception e) {
        String errorMsg = "Failed to resolve tool: " + toolConfig.name();
        logger.error(errorMsg, e);
        throw new ConfigurationException(errorMsg, e);
      }
    }

    return resolvedTools.build();
  }

  /**
   * Resolves a tool instance by its unique name or its static field reference.
   *
   * <p>It first checks the {@link ComponentRegistry} for a registered tool instance. If not found,
   * and the name looks like a fully qualified Java name referencing a static field (e.g.,
   * "com.google.mytools.MyToolClass.INSTANCE"), it attempts to resolve it via reflection using
   * {@link #resolveInstanceViaReflection(String)}.
   *
   * @param toolName The name of the tool or a static field reference (e.g., "myTool",
   *     "com.google.mytools.MyToolClass.INSTANCE").
   * @return The resolved tool instance, or {@code null} if the tool is not found in the registry
   *     and cannot be resolved via reflection.
   */
  @Nullable
  static BaseTool resolveToolInstance(String toolName) {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // First try registry
    Optional<BaseTool> toolOpt = ComponentRegistry.resolveToolInstance(toolName);
    if (toolOpt.isPresent()) {
      return toolOpt.get();
    }

    // If not in registry and looks like Java qualified name, try reflection
    if (isJavaQualifiedName(toolName)) {
      try {
        BaseTool tool = resolveInstanceViaReflection(toolName);
        if (tool != null) {
          registry.register(toolName, tool);
          logger.debug("Resolved and registered tool instance via reflection: {}", toolName);
          return tool;
        }
      } catch (Exception e) {
        logger.debug("Failed to resolve instance via reflection: {}", toolName, e);
      }
    }
    logger.debug("Could not resolve tool instance: {}", toolName);
    return null;
  }

  /**
   * Resolves a tool from a class name and optional arguments.
   *
   * <p>It attempts to load the class specified by {@code className}. If {@code args} are provided
   * and non-empty, it looks for a static factory method {@code fromConfig(ToolArgsConfig)} on the
   * class to instantiate the tool. If {@code args} are null or empty, it looks for a default
   * constructor.
   *
   * @param className The fully qualified name of the tool class to instantiate.
   * @param args Optional configuration arguments for tool creation. If provided, the class must
   *     implement a static {@code fromConfig(ToolArgsConfig)} factory method. If null or empty, the
   *     class must have a default constructor.
   * @return The instantiated tool instance, or {@code null} if the class cannot be found or loaded.
   * @throws ConfigurationException if {@code args} are provided but no {@code fromConfig} method
   *     exists, if {@code args} are not provided but no default constructor exists, or if
   *     instantiation via the factory method or constructor fails.
   */
  @Nullable
  static BaseTool resolveToolFromClass(String className, ToolArgsConfig args)
      throws ConfigurationException {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // First try registry for class
    Optional<Class<? extends BaseTool>> classOpt = ComponentRegistry.resolveToolClass(className);
    Class<? extends BaseTool> toolClass = null;

    if (classOpt.isPresent()) {
      toolClass = classOpt.get();
    } else if (isJavaQualifiedName(className)) {
      // Try reflection to get class
      try {
        Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
        if (BaseTool.class.isAssignableFrom(clazz)) {
          toolClass = clazz.asSubclass(BaseTool.class);
          // Optimization: register for reuse
          registry.register(className, toolClass);
          logger.debug("Resolved and registered tool class via reflection: {}", className);
        }
      } catch (ClassNotFoundException e) {
        logger.debug("Failed to resolve class via reflection: {}", className, e);
        return null;
      }
    }

    if (toolClass == null) {
      return null;
    }

    // If args provided and not empty, try fromConfig method first
    if (args != null && !args.isEmpty()) {
      try {
        Method fromConfigMethod = toolClass.getMethod("fromConfig", ToolArgsConfig.class);
        Object instance = fromConfigMethod.invoke(null, args);
        if (instance instanceof BaseTool baseTool) {
          return baseTool;
        }
      } catch (NoSuchMethodException e) {
        throw new ConfigurationException(
            "Class " + className + " does not have fromConfig method but args were provided.", e);
      } catch (Exception e) {
        logger.error("Error calling fromConfig on class {}", className, e);
        throw new ConfigurationException("Error creating tool from class " + className, e);
      }
    }

    // No args provided or empty args, try default constructor
    try {
      Constructor<? extends BaseTool> constructor = toolClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (NoSuchMethodException e) {
      throw new ConfigurationException(
          "Class " + className + " does not have a default constructor and no args were provided.",
          e);
    } catch (Exception e) {
      logger.error("Error calling default constructor on class {}", className, e);
      throw new ConfigurationException(
          "Error creating tool from class " + className + " using default constructor", e);
    }
  }

  /**
   * Checks if a string appears to be a Java fully qualified name, such as "com.google.adk.MyClass"
   * or "com.google.adk.MyClass.MY_FIELD".
   *
   * <p>It verifies that the name contains at least one dot ('.') and consists of characters valid
   * for Java identifiers and package names.
   *
   * @param name The string to check.
   * @return {@code true} if the string matches the pattern of a Java qualified name, {@code false}
   *     otherwise.
   */
  static boolean isJavaQualifiedName(String name) {
    if (name == null || name.trim().isEmpty()) {
      return false;
    }
    return name.contains(".") && name.matches("^[a-zA-Z_$][a-zA-Z0-9_.$]*$");
  }

  /**
   * Resolves a {@link BaseTool} instance by attempting to access a public static field via
   * reflection.
   *
   * <p>This method expects {@code toolName} to be in the format
   * "com.google.package.ClassName.STATIC_FIELD_NAME", where "STATIC_FIELD_NAME" is the name of a
   * public static field in "com.google.package.ClassName" that holds a {@link BaseTool} instance.
   *
   * @param toolName The fully qualified name of a static field holding a tool instance.
   * @return The {@link BaseTool} instance, or {@code null} if {@code toolName} is not in the
   *     expected format, or if the field is not found, not static, or not of type {@link BaseTool}.
   * @throws Exception if the class specified in {@code toolName} cannot be loaded, or if there is a
   *     security manager preventing reflection, or if accessing the field causes an exception.
   */
  @Nullable
  static BaseTool resolveInstanceViaReflection(String toolName) throws Exception {
    int lastDotIndex = toolName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return null;
    }

    String className = toolName.substring(0, lastDotIndex);
    String fieldName = toolName.substring(lastDotIndex + 1);

    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

    try {
      Field field = clazz.getField(fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        logger.debug("Field {} in class {} is not static", fieldName, className);
        return null;
      }
      Object instance = field.get(null);
      if (instance instanceof BaseTool baseTool) {
        return baseTool;
      } else {
        logger.debug("Field {} in class {} is not a BaseTool instance", fieldName, className);
      }
    } catch (NoSuchFieldException e) {
      logger.debug("Field {} not found in class {}", fieldName, className);
    }
    return null;
  }
}
