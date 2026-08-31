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
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent that runs its sub-agents sequentially.
 *
 * <p><b>Composition with {@link LlmAgent}s:</b> a {@code SequentialAgent} does not transfer control
 * back to a parent {@link LlmAgent}. Use it as the root or transferred-to agent and place any
 * follow-up {@link LlmAgent} as the next sibling. Upstream publishes via {@code outputKey} and
 * downstream reads via {@code {key}} placeholders in its instruction:
 *
 * <pre>{@code
 * var draft =
 *     LlmAgent.builder()
 *         .name("draft")
 *         .model("gemini-flash-latest")
 *         .instruction("Draft a summary.")
 *         .outputKey("draft")
 *         .build();
 * var reviewer =
 *     LlmAgent.builder()
 *         .name("reviewer")
 *         .model("gemini-flash-latest")
 *         .instruction("Polish the draft: {draft}")
 *         .build();
 * var pipeline =
 *     SequentialAgent.builder().name("pipeline").subAgents(draft, reviewer).build();
 * }</pre>
 */
public class SequentialAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(SequentialAgent.class);

  /**
   * Constructor for SequentialAgent.
   *
   * @param name The agent's name.
   * @param description The agent's description.
   * @param subAgents The list of sub-agents to run sequentially.
   * @param beforeAgentCallback Optional callback before the agent runs.
   * @param afterAgentCallback Optional callback after the agent runs.
   */
  private SequentialAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback) {

    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
  }

  /** Builder for {@link SequentialAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {

    @Override
    public SequentialAgent build() {
      // TODO(b/410859954): Add validation for required fields like name.
      return new SequentialAgent(
          name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Runs sub-agents sequentially.
   *
   * <p>Three modes: with resumability on, a resume fast-forwards to the checkpointed sub-agent and
   * pauses on a long-running call; with the deprecated shim on, the same happens but the resume
   * point is reconstructed from history and nothing is checkpointed; with neither, sub-agents just
   * run in order.
   *
   * @param invocationContext Invocation context.
   * @return Flowable emitting events from sub-agents.
   */
  @Override
  @SuppressWarnings("deprecation") // The shim it dispatches on is deprecated by design.
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    List<? extends BaseAgent> subAgents = subAgents();
    if (subAgents.isEmpty()) {
      return Flowable.empty();
    }
    if (invocationContext.isResumable()) {
      return runAsyncResumable(invocationContext, subAgents);
    }
    if (invocationContext.isLegacyResumability()) {
      return runAsyncLegacyResumption(invocationContext, subAgents);
    }
    return Flowable.fromIterable(subAgents)
        .concatMap(subAgent -> subAgent.runAsync(invocationContext));
  }

  /**
   * Runs sub-agents under the deprecated legacy resumption flow, reconstructing the resume point
   * from session events. Frozen copy of the behavior resumability had before durable checkpoints:
   * no state is read or written, so only history decides where the sequence restarts.
   */
  private Flowable<Event> runAsyncLegacyResumption(
      InvocationContext invocationContext, List<? extends BaseAgent> subAgents) {
    int startIndex =
        WorkflowAgentResumption.resumeSubAgentIndex(invocationContext, subAgents).orElse(0);
    AtomicBoolean paused = new AtomicBoolean(false);
    return Flowable.fromIterable(subAgents.subList(startIndex, subAgents.size()))
        .concatMap(
            subAgent ->
                paused.get()
                    ? Flowable.<Event>empty()
                    : subAgent
                        .runAsync(invocationContext)
                        .doOnNext(
                            event -> {
                              if (WorkflowAgentResumption.hasPendingLongRunningCall(event)) {
                                paused.set(true);
                              }
                            }));
  }

  /**
   * Runs sub-agents under durable resumability, matching Python ADK: checkpoint each sub-agent
   * before it runs, fast-forward to the checkpoint on resume, and pause (without ending) on a
   * long-running call.
   */
  private Flowable<Event> runAsyncResumable(
      InvocationContext invocationContext, List<? extends BaseAgent> subAgents) {
    // Deferred so the checkpoint is read and the loop's mutable state created per subscription,
    // not once when the Flowable is assembled.
    return Flowable.defer(
        () -> {
          Map<String, Object> state = invocationContext.agentStates().get(name());
          String startSubAgentName =
              state != null && state.get(WorkflowAgentStates.CURRENT_SUB_AGENT) instanceof String s
                  ? s
                  : null;
          int startIndex;
          if (state == null) {
            startIndex = 0;
          } else if (isNullOrEmpty(startSubAgentName)) {
            // State with no current sub-agent means the sequence already finished; empty counts as
            // absent, since that is Python's default for the field.
            startIndex = subAgents.size();
          } else {
            startIndex =
                WorkflowAgentStates.findIndexForResumption(subAgents, startSubAgentName, logger);
          }
          AtomicBoolean paused = new AtomicBoolean(false);
          AtomicBoolean resuming = new AtomicBoolean(state != null);
          return Flowable.fromIterable(subAgents.subList(startIndex, subAgents.size()))
              .concatMap(
                  subAgent ->
                      Flowable.defer(
                          () -> {
                            // The previous sub-agent may have paused silently, emitting nothing, so
                            // no event carried the pause. Scoped to this agent's subtree so a
                            // paused parallel sibling elsewhere does not stall this sequence.
                            if (paused.get()
                                || invocationContext.hasUnansweredLongRunningCallIn(this)) {
                              paused.set(true);
                              return Flowable.<Event>empty();
                            }
                            Flowable<Event> checkpoint = Flowable.empty();
                            if (!resuming.getAndSet(false)) {
                              ImmutableMap<String, Object> subState =
                                  ImmutableMap.of(
                                      WorkflowAgentStates.CURRENT_SUB_AGENT, subAgent.name());
                              checkpoint = checkpointAndRecord(invocationContext, subState);
                            }
                            Flowable<Event> run =
                                subAgent
                                    .runAsync(invocationContext)
                                    .doOnNext(
                                        event -> {
                                          if (invocationContext.shouldPauseInvocation(event)) {
                                            paused.set(true);
                                          }
                                        });
                            return checkpoint.concatWith(run);
                          }))
              .concatWith(
                  Flowable.defer(
                      () -> {
                        if (paused.get()
                            || invocationContext.hasUnansweredLongRunningCallIn(this)) {
                          return Flowable.<Event>empty();
                        }
                        return endOfAgentAndRecord(invocationContext);
                      }));
        });
  }

  /**
   * Runs sub-agents sequentially in live mode.
   *
   * @param invocationContext Invocation context.
   * @return Flowable emitting events from sub-agents in live mode.
   */
  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.fromIterable(subAgents())
        .concatMap(subAgent -> subAgent.runLive(invocationContext));
  }

  /**
   * Creates a SequentialAgent from configuration.
   *
   * @param config the agent configuration
   * @param configAbsPath The absolute path to the agent config file.
   * @return the configured SequentialAgent
   * @throws ConfigurationException if the configuration is invalid
   */
  public static SequentialAgent fromConfig(SequentialAgentConfig config, String configAbsPath)
      throws ConfigurationException {
    logger.debug("Creating SequentialAgent from config: {}", config.name());

    Builder builder = SequentialAgent.builder();
    ConfigAgentUtils.resolveAndSetCommonAgentFields(builder, config, configAbsPath);

    // Build and return the agent
    SequentialAgent agent = builder.build();
    logger.info(
        "Successfully created SequentialAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
  }
}
