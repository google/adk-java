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
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent that runs its sub-agents sequentially in a loop.
 *
 * <p>The loop continues until a sub-agent escalates, or until the maximum number of iterations is
 * reached (if specified).
 *
 * <p><b>Composition with {@link LlmAgent}s:</b> a {@code LoopAgent} does not transfer control back
 * to a parent {@link LlmAgent}. To react to loop results, place the {@code LoopAgent} and the
 * follow-up {@link LlmAgent} as siblings inside a {@link SequentialAgent}. Loop sub-agents publish
 * via {@code outputKey} and the follow-up reads via {@code {key}} placeholders in its instruction:
 *
 * <pre>{@code
 * var refiner =
 *     LlmAgent.builder()
 *         .name("refiner")
 *         .model("gemini-flash-latest")
 *         .instruction("Refine: {draft?}")
 *         .outputKey("draft")
 *         .build();
 * var publisher =
 *     LlmAgent.builder()
 *         .name("publisher")
 *         .model("gemini-flash-latest")
 *         .instruction("Publish: {draft}")
 *         .build();
 * var loop =
 *     LoopAgent.builder().name("loop").subAgents(refiner).maxIterations(3).build();
 * var root = SequentialAgent.builder().name("root").subAgents(loop, publisher).build();
 * }</pre>
 */
public class LoopAgent extends BaseAgent {
  private static final Logger logger = LoggerFactory.getLogger(LoopAgent.class);

  private final @Nullable Integer maxIterations;

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
      @Nullable Integer maxIterations,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback) {

    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    this.maxIterations = maxIterations;
  }

  /** Builder for {@link LoopAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {
    private @Nullable Integer maxIterations;

    @CanIgnoreReturnValue
    public Builder maxIterations(@Nullable Integer maxIterations) {
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

    if (!invocationContext.isResumable()) {
      return Flowable.fromIterable(subAgents)
          .concatMap(subAgent -> subAgent.runAsync(invocationContext))
          .repeat(maxIterations != null ? maxIterations : Integer.MAX_VALUE)
          .takeUntil(LoopAgent::hasEscalateAction);
    }

    // Resumable: checkpoint {current_sub_agent, times_looped} before each sub-agent, resume into
    // the checkpointed iteration, pause (not end) on a long-running call, and reset sub-agent
    // state between iterations.
    return Flowable.defer(
        () -> {
          Map<String, Object> state = invocationContext.agentStates().get(name());
          String startSubAgentName =
              state == null ? null : (String) state.get(WorkflowAgentStates.CURRENT_SUB_AGENT);
          int startTimesLooped =
              state == null || state.get(WorkflowAgentStates.TIMES_LOOPED) == null
                  ? 0
                  : ((Number) state.get(WorkflowAgentStates.TIMES_LOOPED)).intValue();
          int startIndex =
              WorkflowAgentStates.findIndexForResumption(subAgents, startSubAgentName, logger);
          AtomicInteger timesLooped = new AtomicInteger(startTimesLooped);
          AtomicBoolean shouldExit = new AtomicBoolean(false);
          AtomicBoolean paused = new AtomicBoolean(false);
          if (maxIterations != null && startTimesLooped >= maxIterations) {
            return Flowable.just(endOfAgentEvent(invocationContext));
          }
          return runLoopIteration(
              invocationContext,
              subAgents,
              startIndex,
              new AtomicBoolean(startSubAgentName != null),
              timesLooped,
              shouldExit,
              paused);
        });
  }

  /**
   * Runs one loop iteration over the sub-agents from {@code startIndex}, then either recurses for
   * the next iteration or terminates (emitting end-of-agent unless paused). Shared holders carry
   * the loop's mutable state across iterations.
   */
  private Flowable<Event> runLoopIteration(
      InvocationContext context,
      List<? extends BaseAgent> subAgents,
      int startIndex,
      AtomicBoolean resumingFirst,
      AtomicInteger timesLooped,
      AtomicBoolean shouldExit,
      AtomicBoolean paused) {
    Flowable<Event> iteration =
        Flowable.fromIterable(subAgents.subList(startIndex, subAgents.size()))
            .concatMap(
                subAgent ->
                    Flowable.defer(
                        () -> {
                          if (shouldExit.get() || paused.get()) {
                            return Flowable.<Event>empty();
                          }
                          Flowable<Event> checkpoint = Flowable.empty();
                          if (!resumingFirst.getAndSet(false)) {
                            ImmutableMap<String, Object> subState =
                                ImmutableMap.of(
                                    WorkflowAgentStates.CURRENT_SUB_AGENT, subAgent.name(),
                                    WorkflowAgentStates.TIMES_LOOPED, timesLooped.get());
                            context.setAgentState(name(), subState, /* endOfAgent= */ false);
                            checkpoint = Flowable.just(createStateEvent(context, subState));
                          }
                          Flowable<Event> run =
                              subAgent
                                  .runAsync(context)
                                  .doOnNext(
                                      event -> {
                                        if (hasEscalateAction(event)) {
                                          shouldExit.set(true);
                                        }
                                        if (context.shouldPauseInvocation(event)) {
                                          paused.set(true);
                                        }
                                      });
                          return checkpoint.concatWith(run);
                        }));
    return iteration.concatWith(
        Flowable.defer(
            () -> {
              if (paused.get()) {
                return Flowable.<Event>empty();
              }
              if (shouldExit.get()) {
                return Flowable.just(endOfAgentEvent(context));
              }
              int looped = timesLooped.incrementAndGet();
              context.resetSubAgentStates(name());
              if (maxIterations != null && looped >= maxIterations) {
                return Flowable.just(endOfAgentEvent(context));
              }
              return runLoopIteration(
                  context,
                  subAgents,
                  /* startIndex= */ 0,
                  new AtomicBoolean(false),
                  timesLooped,
                  shouldExit,
                  paused);
            }));
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.error(
        new UnsupportedOperationException("runLive is not defined for LoopAgent yet."));
  }

  private static boolean hasEscalateAction(Event event) {
    return event.actions().escalate().orElse(false);
  }

  public @Nullable Integer maxIterations() {
    return maxIterations;
  }
}
