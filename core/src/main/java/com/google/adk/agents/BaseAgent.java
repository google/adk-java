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
import com.google.adk.plugins.PluginManager;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.DoNotCall;
import com.google.genai.types.Content;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
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

  private final Optional<List<? extends BeforeAgentCallback>> beforeAgentCallback;
  private final Optional<List<? extends AfterAgentCallback>> afterAgentCallback;

  /**
   * Creates a new BaseAgent.
   *
   * @param name Unique agent name. Cannot be "user" (reserved).
   * @param description Agent purpose.
   * @param subAgents Agents managed by this agent.
   * @param beforeAgentCallback Callbacks before agent execution. Invoked in order until one doesn't
   *     return null.
   * @param afterAgentCallback Callbacks after agent execution. Invoked in order until one doesn't
   *     return null.
   */
  public BaseAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      List<? extends BeforeAgentCallback> beforeAgentCallback,
      List<? extends AfterAgentCallback> afterAgentCallback) {
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

  public Optional<List<? extends BeforeAgentCallback>> beforeAgentCallback() {
    return beforeAgentCallback;
  }

  public Optional<List<? extends AfterAgentCallback>> afterAgentCallback() {
    return afterAgentCallback;
  }

  /**
   * Creates a shallow copy of the parent context with the agent properly being set to this
   * instance.
   *
   * @param parentContext Parent context to copy.
   * @return new context with updated branch name.
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
   * Runs the agent asynchronously.
   *
   * @param parentContext Parent context to inherit.
   * @return stream of agent-generated events.
   */
  public Flowable<Event> runAsync(InvocationContext parentContext) {
    Tracer tracer = Telemetry.getTracer();
    return Flowable.defer(
        () -> {
          Span span =
              tracer
                  .spanBuilder("agent_run [" + name() + "]")
                  .setParent(Context.current())
                  .startSpan();
          try (Scope scope = span.makeCurrent()) {
            InvocationContext invocationContext = createInvocationContext(parentContext);

            Flowable<Event> executionFlowable =
                callCallback(
                        beforeCallbacksToFunctions(
                            invocationContext.pluginManager(),
                            beforeAgentCallback.orElse(ImmutableList.of())),
                        invocationContext)
                    .flatMapPublisher(
                        beforeEventOpt -> {
                          if (invocationContext.endInvocation()) {
                            return Flowable.fromOptional(beforeEventOpt);
                          }

                          Flowable<Event> beforeEvents = Flowable.fromOptional(beforeEventOpt);
                          Flowable<Event> mainEvents =
                              Flowable.defer(() -> runAsyncImpl(invocationContext));
                          Flowable<Event> afterEvents =
                              Flowable.defer(
                                  () ->
                                      callCallback(
                                              afterCallbacksToFunctions(
                                                  invocationContext.pluginManager(),
                                                  afterAgentCallback.orElse(ImmutableList.of())),
                                              invocationContext)
                                          .flatMapPublisher(Flowable::fromOptional));

                          return Flowable.concat(beforeEvents, mainEvents, afterEvents);
                        });
            return executionFlowable.doFinally(span::end);
          }
        });
  }

  /**
   * Converts before-agent callbacks to functions.
   *
   * @param callbacks Before-agent callbacks.
   * @return callback functions.
   */
  private ImmutableList<Function<CallbackContext, Maybe<Content>>> beforeCallbacksToFunctions(
      PluginManager pluginManager, List<? extends BeforeAgentCallback> callbacks) {
    return Stream.concat(
            Stream.of(ctx -> pluginManager.runBeforeAgentCallback(this, ctx)),
            callbacks.stream()
                .map(callback -> (Function<CallbackContext, Maybe<Content>>) callback::call))
        .collect(toImmutableList());
  }

  /**
   * Converts after-agent callbacks to functions.
   *
   * @param callbacks After-agent callbacks.
   * @return callback functions.
   */
  private ImmutableList<Function<CallbackContext, Maybe<Content>>> afterCallbacksToFunctions(
      PluginManager pluginManager, List<? extends AfterAgentCallback> callbacks) {
    return Stream.concat(
            Stream.of(ctx -> pluginManager.runAfterAgentCallback(this, ctx)),
            callbacks.stream()
                .map(callback -> (Function<CallbackContext, Maybe<Content>>) callback::call))
        .collect(toImmutableList());
  }

  /**
   * Calls agent callbacks and returns the first produced event, if any.
   *
   * @param agentCallbacks Callback functions.
   * @param invocationContext Current invocation context.
   * @return single emitting first event, or empty if none.
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
                  if (callbackContext.state().hasDelta()) {
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
   * Runs the agent synchronously.
   *
   * @param parentContext Parent context to inherit.
   * @return stream of agent-generated events.
   */
  public Flowable<Event> runLive(InvocationContext parentContext) {
    Tracer tracer = Telemetry.getTracer();
    return Flowable.defer(
        () -> {
          Span span =
              tracer
                  .spanBuilder("agent_run [" + name() + "]")
                  .setParent(Context.current())
                  .startSpan();
          try (Scope scope = span.makeCurrent()) {
            InvocationContext invocationContext = createInvocationContext(parentContext);
            Flowable<Event> executionFlowable = runLiveImpl(invocationContext);
            return executionFlowable.doFinally(span::end);
          }
        });
  }

  /**
   * Agent-specific asynchronous logic.
   *
   * @param invocationContext Current invocation context.
   * @return stream of agent-generated events.
   */
  protected abstract Flowable<Event> runAsyncImpl(InvocationContext invocationContext);

  /**
   * Agent-specific synchronous logic.
   *
   * @param invocationContext Current invocation context.
   * @return stream of agent-generated events.
   */
  protected abstract Flowable<Event> runLiveImpl(InvocationContext invocationContext);

  /**
   * Creates a new agent instance from a configuration object.
   *
   * @param config Agent configuration.
   * @param configAbsPath Absolute path to the configuration file.
   * @return new agent instance.
   */
  // TODO: Makes `BaseAgent.fromConfig` a final method and let sub-class to optionally override
  // `_parse_config` to update kwargs if needed.
  @DoNotCall("Always throws java.lang.UnsupportedOperationException")
  public static BaseAgent fromConfig(BaseAgentConfig config, String configAbsPath) {
    throw new UnsupportedOperationException(
        "BaseAgent is abstract. Override fromConfig in concrete subclasses.");
  }
}
