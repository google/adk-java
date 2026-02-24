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

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.events.Event;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent that runs its sub-agents sequentially in a loop.
 *
 * <p>The loop continues until a sub-agent escalates, or until the maximum number of iterations is
 * reached (if specified).
 */
public class LoopAgent extends BaseAgent {
  private static final Logger logger = LoggerFactory.getLogger(LoopAgent.class);

  private final Optional<Integer> maxIterations;

  /**
   * Constructor for LoopAgent.
   *
   * @param name The agent's name.
   * @param description The agent's description.
   * @param subAgents The list of sub-agents to run in the loop.
   * @param maxIterations Optional termination condition: maximum number of loop iterations.
   * @param beforeAgentCallback Optional callback before the agent runs.
   * @param afterAgentCallback Optional callback after the agent runs.
   */
  private LoopAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      Optional<Integer> maxIterations,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback) {

    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    this.maxIterations = maxIterations;
  }

  /** Builder for {@link LoopAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {
    private Optional<Integer> maxIterations = Optional.empty();

    @CanIgnoreReturnValue
    public Builder maxIterations(int maxIterations) {
      this.maxIterations = Optional.of(maxIterations);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder maxIterations(Optional<Integer> maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    @Override
    public LoopAgent build() {
      // TODO(b/410859954): Add validation for required fields like name.
      return new LoopAgent(
          name, description, subAgents, maxIterations, beforeAgentCallback, afterAgentCallback);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a LoopAgent from configuration.
   *
   * @param config The agent configuration.
   * @param configAbsPath The absolute path to the agent config file.
   * @return the configured LoopAgent
   * @throws ConfigurationException if the configuration is invalid
   */
  public static LoopAgent fromConfig(LoopAgentConfig config, String configAbsPath)
      throws ConfigurationException {
    logger.debug("Creating LoopAgent from config: {}", config.name());

    Builder builder = builder();
    ConfigAgentUtils.resolveAndSetCommonAgentFields(builder, config, configAbsPath);

    if (config.maxIterations() != null) {
      builder.maxIterations(config.maxIterations());
    }

    // Build and return the agent
    LoopAgent agent = builder.build();
    logger.info(
        "Successfully created LoopAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    List<? extends BaseAgent> subAgents = subAgents();
    if (subAgents == null || subAgents.isEmpty()) {
      return Flowable.empty();
    }

    return Flowable.fromIterable(subAgents)
        .concatMap(subAgent -> subAgent.runAsync(invocationContext))
        .repeat(maxIterations.orElse(Integer.MAX_VALUE))
        .takeUntil(LoopAgent::hasEscalateAction);
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.error(
        new UnsupportedOperationException("runLive is not defined for LoopAgent yet."));
  }

  private static boolean hasEscalateAction(Event event) {
    return event.actions().escalate().orElse(false);
  }
}
