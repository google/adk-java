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

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.events.Event;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A shell agent that runs its sub-agents in parallel in isolated manner.
 *
 * <p>This approach is beneficial for scenarios requiring multiple perspectives or attempts on a
 * single task, such as running different algorithms simultaneously or generating multiple responses
 * for review by a subsequent evaluation agent.
 */
public class ParallelAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(ParallelAgent.class);

  /**
   * Constructor for ParallelAgent.
   *
   * @param name The agent's name.
   * @param description The agent's description.
   * @param subAgents The list of sub-agents to run in parallel.
   * @param beforeAgentCallback Optional callback before the agent runs.
   * @param afterAgentCallback Optional callback after the agent runs.
   */
  private ParallelAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback) {

    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
  }

  /** Builder for {@link ParallelAgent}. */
  public static class Builder {
    private String name;
    private String description;
    private List<? extends BaseAgent> subAgents;
    private ImmutableList<Callbacks.BeforeAgentCallback> beforeAgentCallback;
    private ImmutableList<Callbacks.AfterAgentCallback> afterAgentCallback;

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
    public Builder subAgents(List<? extends BaseAgent> subAgents) {
      this.subAgents = subAgents;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder subAgents(BaseAgent... subAgents) {
      this.subAgents = ImmutableList.copyOf(subAgents);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallback(Callbacks.BeforeAgentCallback beforeAgentCallback) {
      this.beforeAgentCallback = ImmutableList.of(beforeAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallback(
        List<Callbacks.BeforeAgentCallbackBase> beforeAgentCallback) {
      this.beforeAgentCallback = CallbackUtil.getBeforeAgentCallbacks(beforeAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallback(Callbacks.AfterAgentCallback afterAgentCallback) {
      this.afterAgentCallback = ImmutableList.of(afterAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallback(List<Callbacks.AfterAgentCallbackBase> afterAgentCallback) {
      this.afterAgentCallback = CallbackUtil.getAfterAgentCallbacks(afterAgentCallback);
      return this;
    }

    public ParallelAgent build() {
      return new ParallelAgent(
          name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a ParallelAgent from configuration.
   *
   * @param config the agent configuration
   * @param configAbsPath The absolute path to the agent config file.
   * @return the configured ParallelAgent
   * @throws ConfigurationException if the configuration is invalid
   */
  public static ParallelAgent fromConfig(ParallelAgentConfig config, String configAbsPath)
      throws ConfigurationException {
    logger.debug("Creating ParallelAgent from config: {}", config.name());

    // Validate required fields
    if (config.name() == null || config.name().trim().isEmpty()) {
      throw new ConfigurationException("Agent name is required");
    }

    // Create builder with required fields
    Builder builder =
        ParallelAgent.builder().name(config.name()).description(nullToEmpty(config.description()));

    // Resolve and add subagents using the utility class
    if (config.subAgents() != null && !config.subAgents().isEmpty()) {
      ImmutableList<BaseAgent> subAgents =
          ConfigAgentUtils.resolveSubAgents(config.subAgents(), configAbsPath);
      builder.subAgents(subAgents);
    }

    // Resolve callbacks if configured
    setCallbacksFromConfig(config, builder);

    // Build and return the agent
    ParallelAgent agent = builder.build();
    logger.info(
        "Successfully created ParallelAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
  }

  private static void setCallbacksFromConfig(ParallelAgentConfig config, Builder builder)
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
  }

  private static <T> void setCallbackFromConfig(
      @Nullable List<ParallelAgentConfig.CallbackRef> refs,
      Class<T> callbackBaseClass,
      String callbackTypeName,
      Consumer<ImmutableList<T>> builderSetter)
      throws ConfigurationException {
    if (refs != null) {
      ImmutableList.Builder<T> list = ImmutableList.builder();
      for (ParallelAgentConfig.CallbackRef ref : refs) {
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

  /**
   * Sets the branch for the current agent in the invocation context.
   *
   * <p>Appends the agent name to the current branch, or sets it if undefined.
   *
   * @param currentAgent Current agent.
   * @param invocationContext Invocation context to update.
   */
  private static void setBranchForCurrentAgent(
      BaseAgent currentAgent, InvocationContext invocationContext) {
    String branch = invocationContext.branch().orElse(null);
    if (isNullOrEmpty(branch)) {
      invocationContext.branch(currentAgent.name());
    } else {
      invocationContext.branch(branch + "." + currentAgent.name());
    }
  }

  /**
   * Runs sub-agents in parallel and emits their events.
   *
   * <p>Sets the branch and merges event streams from all sub-agents.
   *
   * @param invocationContext Invocation context.
   * @return Flowable emitting events from all sub-agents.
   */
  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    setBranchForCurrentAgent(this, invocationContext);

    List<? extends BaseAgent> currentSubAgents = subAgents();
    if (currentSubAgents == null || currentSubAgents.isEmpty()) {
      return Flowable.empty();
    }

    List<Flowable<Event>> agentFlowables = new ArrayList<>();
    for (BaseAgent subAgent : currentSubAgents) {
      agentFlowables.add(subAgent.runAsync(invocationContext));
    }
    return Flowable.merge(agentFlowables);
  }

  /**
   * Not supported for ParallelAgent.
   *
   * @param invocationContext Invocation context.
   * @return Flowable that always throws UnsupportedOperationException.
   */
  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.error(
        new UnsupportedOperationException("runLive is not defined for ParallelAgent yet."));
  }
}
