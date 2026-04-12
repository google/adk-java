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

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent that delegates execution planning to a {@link Planner} strategy.
 *
 * <p>The {@code PlannerAgent} owns a set of sub-agents and a planner. At runtime, the planner
 * inspects session state and decides which sub-agent(s) to run next. This enables dynamic,
 * goal-oriented agent orchestration — the execution topology is determined at runtime rather than
 * being fixed at build time.
 *
 * <p>The planning loop:
 *
 * <ol>
 *   <li>Planner is initialized with context and available agents
 *   <li>Planner returns what to do next via {@link PlannerAction}
 *   <li>Selected sub-agent(s) execute, producing events
 *   <li>Session state (world state) is updated from events
 *   <li>Planner sees updated state and decides the next action
 *   <li>Repeat until {@link PlannerAction.Done} or maxIterations
 * </ol>
 *
 * <p>Example usage with a custom planner:
 *
 * <pre>{@code
 * PlannerAgent agent = PlannerAgent.builder()
 *     .name("myAgent")
 *     .subAgents(agentA, agentB, agentC)
 *     .planner(new GoalOrientedPlanner("finalOutput", metadata))
 *     .maxIterations(20)
 *     .build();
 * }</pre>
 */
public class PlannerAgent extends BaseAgent {
  private static final Logger logger = LoggerFactory.getLogger(PlannerAgent.class);
  private static final int DEFAULT_MAX_ITERATIONS = 100;

  private final Planner planner;
  private final int maxIterations;

  private PlannerAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      Planner planner,
      int maxIterations,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback) {
    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    this.planner = planner;
    this.maxIterations = maxIterations;
  }

  /** Returns the planner strategy used by this agent. */
  public Planner planner() {
    return planner;
  }

  /** Returns the maximum number of planning iterations. */
  public int maxIterations() {
    return maxIterations;
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    List<? extends BaseAgent> agents = subAgents();
    if (agents == null || agents.isEmpty()) {
      return Flowable.empty();
    }

    ImmutableList<BaseAgent> available =
        agents.stream().map(a -> (BaseAgent) a).collect(toImmutableList());
    PlanningContext planningContext = new PlanningContext(invocationContext, available);

    planner.init(planningContext);

    AtomicInteger iteration = new AtomicInteger(0);

    return planner
        .firstAction(planningContext)
        .flatMapPublisher(
            firstAction ->
                executeActionAndContinue(
                    firstAction, planningContext, invocationContext, iteration));
  }

  private Flowable<Event> executeActionAndContinue(
      PlannerAction action,
      PlanningContext planningContext,
      InvocationContext invocationContext,
      AtomicInteger iteration) {

    int current = iteration.getAndIncrement();
    if (current >= maxIterations) {
      logger.info("PlannerAgent '{}' reached maxIterations={}", name(), maxIterations);
      return Flowable.empty();
    }

    if (action instanceof PlannerAction.Done) {
      return Flowable.empty();
    }

    if (action instanceof PlannerAction.DoneWithResult doneWithResult) {
      Event resultEvent =
          Event.builder()
              .id(Event.generateEventId())
              .invocationId(invocationContext.invocationId())
              .author(name())
              .branch(invocationContext.branch().orElse(null))
              .content(Content.fromParts(Part.fromText(doneWithResult.result())))
              .actions(EventActions.builder().build())
              .build();
      return Flowable.just(resultEvent);
    }

    if (action instanceof PlannerAction.NoOp) {
      return Flowable.defer(
          () ->
              planner
                  .nextAction(planningContext)
                  .flatMapPublisher(
                      nextAction ->
                          executeActionAndContinue(
                              nextAction, planningContext, invocationContext, iteration)));
    }

    if (action instanceof PlannerAction.RunAgents runAgents) {
      Flowable<Event> agentEvents;
      if (runAgents.agents().size() == 1) {
        agentEvents = runAgents.agents().get(0).runAsync(invocationContext);
      } else {
        agentEvents =
            Flowable.merge(
                runAgents.agents().stream()
                    .map(agent -> agent.runAsync(invocationContext))
                    .collect(toImmutableList()));
      }

      return agentEvents.concatWith(
          Flowable.defer(
              () ->
                  planner
                      .nextAction(planningContext)
                      .flatMapPublisher(
                          nextAction ->
                              executeActionAndContinue(
                                  nextAction, planningContext, invocationContext, iteration))));
    }

    // Unreachable for sealed interface, but required by compiler
    return Flowable.empty();
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.error(
        new UnsupportedOperationException("runLive is not defined for PlannerAgent yet."));
  }

  /** Returns a new {@link Builder} for creating {@link PlannerAgent} instances. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link PlannerAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {
    private Planner planner;
    private int maxIterations = DEFAULT_MAX_ITERATIONS;

    @CanIgnoreReturnValue
    public Builder planner(Planner planner) {
      this.planner = planner;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder maxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    @Override
    public PlannerAgent build() {
      if (planner == null) {
        throw new IllegalStateException(
            "PlannerAgent requires a Planner. Call .planner(...) on the builder.");
      }
      return new PlannerAgent(
          name,
          description,
          subAgents,
          planner,
          maxIterations,
          beforeAgentCallback,
          afterAgentCallback);
    }
  }
}
