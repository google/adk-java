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
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
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
   * <p>When resumability is enabled, on resume execution fast-forwards to the sub-agent being
   * resumed (completed ones are not re-run) and pauses on a pending long-running call; when
   * disabled, sub-agents simply run in order (matches Python ADK v1 with resumability off).
   * Temporary, event-based.
   *
   * @param invocationContext Invocation context.
   * @return Flowable emitting events from sub-agents.
   */
  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    List<? extends BaseAgent> subAgents = subAgents();
    if (subAgents.isEmpty()) {
      return Flowable.empty();
    }
    if (!invocationContext.isResumable()) {
      return Flowable.fromIterable(subAgents)
          .concatMap(subAgent -> subAgent.runAsync(invocationContext));
    }
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
