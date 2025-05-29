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
import com.google.adk.events.Event;
import com.google.adk.examples.BaseExampleProvider;
import com.google.adk.examples.Example;
import com.google.adk.flows.llmflows.AutoFlow;
import com.google.adk.flows.llmflows.BaseLlmFlow;
import com.google.adk.flows.llmflows.SingleFlow;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Model;
import com.google.adk.tools.BaseTool;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
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
  private final List<BaseTool> tools;
  private final Optional<GenerateContentConfig> generateContentConfig;
  private final Optional<BaseExampleProvider> exampleProvider;
  private final IncludeContents includeContents;

  private final boolean planning;
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
    this.planning = builder.planning != null ? builder.planning : false;
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
    this.tools = builder.tools != null ? builder.tools : Collections.emptyList();

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
    private List<BaseAgent> subAgents;
    private List<BaseTool> tools;
    private GenerateContentConfig generateContentConfig;
    private BaseExampleProvider exampleProvider;
    private IncludeContents includeContents;
    private Boolean planning;
    private Boolean disallowTransferToParent;
    private Boolean disallowTransferToPeers;
    private List<BeforeModelCallback> beforeModelCallback;
    private List<AfterModelCallback> afterModelCallback;
    private List<BeforeAgentCallback> beforeAgentCallback;
    private List<AfterAgentCallback> afterAgentCallback;
    private ImmutableList<BeforeToolCallback> beforeToolCallback;
    private ImmutableList<AfterToolCallback> afterToolCallback;
    private Schema inputSchema;
    private Schema outputSchema;
    private Executor executor;
    private String outputKey;

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
    public Builder tools(List<? extends BaseTool> tools) {
      this.tools = ImmutableList.copyOf(tools);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder tools(BaseTool... tools) {
      this.tools = ImmutableList.copyOf(tools);
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
        if (this.tools != null && !this.tools.isEmpty()) {
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
      return new SingleFlow();
    } else {
      return new AutoFlow();
    }
  }

  private void maybeSaveOutputToState(Event event) {
    if (outputKey().isPresent() && event.finalResponse() && event.content().isPresent()) {
      // Concatenate text from all parts.
      Object output;
      String rawResult =
          event.content().flatMap(Content::parts).orElse(Collections.emptyList()).stream()
              .map(part -> part.text().orElse(""))
              .collect(Collectors.joining());

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
   * Constructs the text instruction for this agent based on the {@link #instruction} field.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param context The context to retrieve the session state.
   * @return The resolved instruction as a {@link Single} wrapped string.
   */
  public Single<String> canonicalInstruction(ReadonlyContext context) {
    if (instruction instanceof Instruction.Static staticInstr) {
      return Single.just(staticInstr.instruction());
    } else if (instruction instanceof Instruction.Provider provider) {
      return provider.getInstruction().apply(context);
    }
    throw new IllegalStateException("Unknown Instruction subtype: " + instruction.getClass());
  }

  /**
   * Constructs the text global instruction for this agent based on the {@link #globalInstruction}
   * field.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param context The context to retrieve the session state.
   * @return The resolved global instruction as a {@link Single} wrapped string.
   */
  public Single<String> canonicalGlobalInstruction(ReadonlyContext context) {
    if (globalInstruction instanceof Instruction.Static staticInstr) {
      return Single.just(staticInstr.instruction());
    } else if (globalInstruction instanceof Instruction.Provider provider) {
      return provider.getInstruction().apply(context);
    }
    throw new IllegalStateException("Unknown Instruction subtype: " + instruction.getClass());
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
    return tools;
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

  private Model resolveModelInternal() {
    // 1. Check if the model is defined locally for this agent.
    if (this.model.isPresent()) {
      if (this.model().isPresent()) {
        return this.model.get();
      }
    }
    // 2. If not defined locally, search ancestors.
    BaseAgent current = this.parentAgent();
    while (current != null) {
      if (current instanceof LlmAgent) {
        return ((LlmAgent) current).resolvedModel();
      }
      current = current.parentAgent();
    }
    throw new IllegalStateException("No model found for agent " + name() + " or its ancestors.");
  }
}
