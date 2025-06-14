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

import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;

/**
 * A shell agent that runs its sub-agents in parallel in isolated manner.
 *
 * <p>This approach is beneficial for scenarios requiring multiple perspectives or attempts on a
 * single task, such as running different algorithms simultaneously or generating multiple responses
 * for review by a subsequent evaluation agent.
 */
public class ParallelAgent extends BaseAgent {

  /**
   * Constructor for ParallelAgent.
   *
   * @param name The agent's name.
   * @param description The agent's description.
   * @param subAgents The list of sub-agents to run sequentially.
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
   * Sets the branch for the current agent in the invocation context.
   *
   * <p>This method appends the current agent's name to the existing branch, or sets it as the
   * branch if none exists.
   *
   * @param currentAgent The current agent whose name will be used to set the branch.
   * @param invocationContext The context of the invocation where the branch will be set.
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
   * Runs the sub-agents in parallel and returns a Flowable of events.
   *
   * <p>This method sets the branch for the current agent in the invocation context and then
   * collects events from all sub-agents running in parallel.
   *
   * @param invocationContext The context of the invocation.
   * @return A Flowable that emits events from all sub-agents.
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
   * Runs the sub-agents in parallel and returns a Flowable of events in live mode.
   *
   * <p>This method is not defined for ParallelAgent, as it does not support live execution.
   *
   * @param invocationContext The context of the invocation.
   * @return A Flowable that emits events, but throws an UnsupportedOperationException.
   */
  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.error(
        new UnsupportedOperationException("runLive is not defined for ParallelAgent yet."));
  }
}
