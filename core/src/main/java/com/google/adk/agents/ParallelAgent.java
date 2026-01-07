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

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
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
  private final Scheduler scheduler;

  /**
   * Constructor for ParallelAgent.
   *
   * @param name The agent's name.
   * @param description The agent's description.
   * @param subAgents The list of sub-agents to run in parallel.
   * @param beforeAgentCallback Optional callback before the agent runs.
   * @param afterAgentCallback Optional callback after the agent runs.
   * @param scheduler The scheduler to use for parallel execution.
   */
  private ParallelAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback,
      Scheduler scheduler) {

    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    this.scheduler = scheduler;
  }

  /** Builder for {@link ParallelAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {

    private Scheduler scheduler = Schedulers.io();

    public Builder scheduler(Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    @Override
    public ParallelAgent build() {
      return new ParallelAgent(
          name, description, subAgents, beforeAgentCallback, afterAgentCallback, scheduler);
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

    Builder builder = ParallelAgent.builder();
    ConfigAgentUtils.resolveAndSetCommonAgentFields(builder, config, configAbsPath);

    // Build and return the agent
    ParallelAgent agent = builder.build();
    logger.info(
        "Successfully created ParallelAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
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
      agentFlowables.add(subAgent.runAsync(invocationContext).subscribeOn(scheduler));
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
