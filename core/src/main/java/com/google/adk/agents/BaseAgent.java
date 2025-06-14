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

import com.google.adk.Telemetry;
import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Base class for all agents. */
public abstract class BaseAgent {

  /** The agent's name. Must be a unique identifier within the agent tree. */
  private final String name;

  /**
   * One line description about the agent's capability. The system can use this for decision-making
   * when delegating control to different agents.
   */
  private final String description;

  /**
   * The parent agent in the agent tree. Note that one agent cannot be added to two different
   * parents' sub-agents lists.
   */
  private BaseAgent parentAgent;

  private final List<? extends BaseAgent> subAgents;

  private final Optional<List<BeforeAgentCallback>> beforeAgentCallback;
  private final Optional<List<AfterAgentCallback>> afterAgentCallback;

  /**
   * Constructs a new BaseAgent with the specified name, description, and optional sub-agents.
   *
   * @param name The unique name of the agent.
   * @param description A one-line description of the agent's capability.
   * @param subAgents Optional list of sub-agents that this agent manages.
   * @param beforeAgentCallback Optional list of callbacks to execute before the agent runs.
   * @param afterAgentCallback Optional list of callbacks to execute after the agent runs.
   */
  public BaseAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      List<BeforeAgentCallback> beforeAgentCallback,
      List<AfterAgentCallback> afterAgentCallback) {
    this.name = name;
    this.description = description;
    this.parentAgent = null;
    this.subAgents = subAgents != null ? subAgents : ImmutableList.of();
    this.beforeAgentCallback = Optional.ofNullable(beforeAgentCallback);
    this.afterAgentCallback = Optional.ofNullable(afterAgentCallback);

    // Establish parent relationships for all sub-agents if needed.
    for (BaseAgent subAgent : this.subAgents) {
      subAgent.parentAgent(this);
    }
  }

  /**
   * Gets the agent's unique name.
   *
   * @return the unique name of the agent.
   */
  public final String name() {
    return name;
  }

  /**
   * Gets the one-line description of the agent's capability.
   *
   * @return the description of the agent.
   */
  public final String description() {
    return description;
  }

  /**
   * Retrieves the parent agent in the agent tree.
   *
   * @return the parent agent, or {@code null} if this agent does not have a parent.
   */
  public BaseAgent parentAgent() {
    return parentAgent;
  }

  /**
   * Sets the parent agent.
   *
   * @param parentAgent The parent agent to set.
   */
  protected void parentAgent(BaseAgent parentAgent) {
    this.parentAgent = parentAgent;
  }

  /**
   * Returns the root agent for this agent by traversing up the parent chain.
   *
   * @return the root agent.
   */
  public BaseAgent rootAgent() {
    BaseAgent agent = this;
    while (agent.parentAgent() != null) {
      agent = agent.parentAgent();
    }
    return agent;
  }

  /**
   * Finds an agent (this or descendant) by name.
   *
   * @return the agent or descendant with the given name, or {@code null} if not found.
   */
  public BaseAgent findAgent(String name) {
    if (this.name().equals(name)) {
      return this;
    }
    return findSubAgent(name);
  }

  /** Recursively search sub agent by name. */
  public @Nullable BaseAgent findSubAgent(String name) {
    for (BaseAgent subAgent : subAgents) {
      if (subAgent.name().equals(name)) {
        return subAgent;
      }
      BaseAgent result = subAgent.findSubAgent(name);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  public List<? extends BaseAgent> subAgents() {
    return subAgents;
  }

  public Optional<List<BeforeAgentCallback>> beforeAgentCallback() {
    return beforeAgentCallback;
  }

  public Optional<List<AfterAgentCallback>> afterAgentCallback() {
    return afterAgentCallback;
  }

  /**
   * Creates a new {@link InvocationContext} based on the provided parent context.
   * The new context will inherit properties from the parent and update the branch name if
   * applicable.
   *
   * @param parentContext The parent invocation context to copy from.
   * @return A new {@link InvocationContext} with updated properties.
   */
  private InvocationContext createInvocationContext(InvocationContext parentContext) {
    InvocationContext invocationContext = InvocationContext.copyOf(parentContext);
    invocationContext.agent(this);
    // Check for branch to be truthy (not None, not empty string),
    if (parentContext.branch().filter(s -> !s.isEmpty()).isPresent()) {
      invocationContext.branch(parentContext.branch().get() + "." + name());
    }
    return invocationContext;
  }

  /**
   * Runs the agent asynchronously, executing any before and after callbacks.
   * The agent's main logic is implemented in {@link #runAsyncImpl(InvocationContext)}.
   *
   * @param parentContext The parent invocation context to inherit properties from.
   * @return A {@link Flowable<Event>} that emits events produced by the agent.
   */
  public Flowable<Event> runAsync(InvocationContext parentContext) {
    Tracer tracer = Telemetry.getTracer();
    return Flowable.defer(
        () -> {
          Span span = tracer.spanBuilder("agent_run [" + name() + "]").startSpan();
          try (Scope scope = span.makeCurrent()) {
            InvocationContext invocationContext = createInvocationContext(parentContext);

            Flowable<Event> executionFlowable =
                beforeAgentCallback
                    .map(
                        callback ->
                            callCallback(beforeCallbacksToFunctions(callback), invocationContext))
                    .orElse(Single.just(Optional.empty()))
                    .flatMapPublisher(
                        beforeEventOpt -> {
                          if (invocationContext.endInvocation()) {
                            return Flowable.fromOptional(beforeEventOpt);
                          }

                          Flowable<Event> beforeEvents = Flowable.fromOptional(beforeEventOpt);
                          Flowable<Event> mainEvents =
                              Flowable.defer(() -> runAsyncImpl(invocationContext));
                          Flowable<Event> afterEvents =
                              afterAgentCallback
                                  .map(
                                      callback ->
                                          Flowable.defer(
                                              () ->
                                                  callCallback(
                                                          afterCallbacksToFunctions(callback),
                                                          invocationContext)
                                                      .flatMapPublisher(Flowable::fromOptional)))
                                  .orElse(Flowable.empty());

                          return Flowable.concat(beforeEvents, mainEvents, afterEvents);
                        });
            return executionFlowable.doFinally(span::end);
          }
        });
  }

  /**
   * Converts a list of {@link BeforeAgentCallback} to a list of functions that can be applied
   * to a {@link CallbackContext}. This is used to prepare the callbacks for execution.
   *
   * @param callbacks The list of before agent callbacks.
   * @return An immutable list of functions that can be applied to a {@link CallbackContext}.
   */
  private ImmutableList<Function<CallbackContext, Maybe<Content>>> beforeCallbacksToFunctions(
      List<BeforeAgentCallback> callbacks) {
    return callbacks.stream()
        .map(callback -> (Function<CallbackContext, Maybe<Content>>) callback::call)
        .collect(toImmutableList());
  }

  /**
   * Converts a list of {@link AfterAgentCallback} to a list of functions that can be applied
   * to a {@link CallbackContext}. This is used to prepare the callbacks for execution.
   *
   * @param callbacks The list of after agent callbacks.
   * @return An immutable list of functions that can be applied to a {@link CallbackContext}.
   */
  private ImmutableList<Function<CallbackContext, Maybe<Content>>> afterCallbacksToFunctions(
      List<AfterAgentCallback> callbacks) {
    return callbacks.stream()
        .map(callback -> (Function<CallbackContext, Maybe<Content>>) callback::call)
        .collect(toImmutableList());
  }

  /**
   * Calls the provided agent callbacks and returns the first non-empty event produced by any of
   * the callbacks. If no callbacks produce an event, it returns an empty Optional.
   *
   * @param agentCallbacks The list of agent callbacks to call.
   * @param invocationContext The context for the current invocation.
   * @return A Single that emits an Optional containing the first produced event, or empty if none.
   */
  private Single<Optional<Event>> callCallback(
      List<Function<CallbackContext, Maybe<Content>>> agentCallbacks,
      InvocationContext invocationContext) {
    if (agentCallbacks == null || agentCallbacks.isEmpty()) {
      return Single.just(Optional.empty());
    }

    CallbackContext callbackContext =
        new CallbackContext(invocationContext, /* eventActions= */ null);

    return Flowable.fromIterable(agentCallbacks)
        .concatMap(
            callback -> {
              Maybe<Content> maybeContent = callback.apply(callbackContext);

              return maybeContent
                  .map(
                      content -> {
                        Event.Builder eventBuilder =
                            Event.builder()
                                .id(Event.generateEventId())
                                .invocationId(invocationContext.invocationId())
                                .author(name())
                                .branch(invocationContext.branch())
                                .actions(callbackContext.eventActions());

                        eventBuilder.content(Optional.of(content));
                        invocationContext.setEndInvocation(true);
                        return Optional.of(eventBuilder.build());
                      })
                  .toFlowable();
            })
        .firstElement()
        .switchIfEmpty(
            Single.defer(
                () -> {
                  boolean hasStateDelta = !callbackContext.eventActions().stateDelta().isEmpty();

                  if (hasStateDelta) {
                    Event.Builder eventBuilder =
                        Event.builder()
                            .id(Event.generateEventId())
                            .invocationId(invocationContext.invocationId())
                            .author(name())
                            .branch(invocationContext.branch())
                            .actions(callbackContext.eventActions());

                    return Single.just(Optional.of(eventBuilder.build()));
                  } else {
                    return Single.just(Optional.empty());
                  }
                }));
  }

  /**
   * Runs the agent synchronously, executing any before and after callbacks.
   * The agent's main logic is implemented in {@link #runLiveImpl(InvocationContext)}.
   *
   * @param parentContext The parent invocation context to inherit properties from.
   * @return A {@link Flowable<Event>} that emits events produced by the agent.
   */
  public Flowable<Event> runLive(InvocationContext parentContext) {
    Tracer tracer = Telemetry.getTracer();
    return Flowable.defer(
        () -> {
          Span span = tracer.spanBuilder("agent_run [" + name() + "]").startSpan();
          try (Scope scope = span.makeCurrent()) {
            InvocationContext invocationContext = createInvocationContext(parentContext);
            Flowable<Event> executionFlowable = runLiveImpl(invocationContext);
            return executionFlowable.doFinally(span::end);
          }
        });
  }

  /**
   * The main implementation of the agent's asynchronous logic.
   * This method should be overridden by subclasses to provide the agent's specific behavior.
   *
   * @param invocationContext The context for the current invocation.
   * @return A {@link Flowable<Event>} that emits events produced by the agent.
   */
  protected abstract Flowable<Event> runAsyncImpl(InvocationContext invocationContext);

  /**
   * The main implementation of the agent's synchronous logic.
   * This method should be overridden by subclasses to provide the agent's specific behavior.
   *
   * @param invocationContext The context for the current invocation.
   * @return A {@link Flowable<Event>} that emits events produced by the agent.
   */
  protected abstract Flowable<Event> runLiveImpl(InvocationContext invocationContext);
}
