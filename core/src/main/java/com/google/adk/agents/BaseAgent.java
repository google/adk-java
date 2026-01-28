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
import static java.util.Objects.requireNonNullElse;

import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.events.Event;
import com.google.adk.plugins.Plugin;
import com.google.adk.telemetry.Tracing;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.DoNotCall;
import com.google.genai.types.Content;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
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

  private final List<? extends BeforeAgentCallback> beforeAgentCallback;
  private final List<? extends AfterAgentCallback> afterAgentCallback;

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
      @Nullable List<? extends BeforeAgentCallback> beforeAgentCallback,
      @Nullable List<? extends AfterAgentCallback> afterAgentCallback) {
    this.name = name;
    this.description = description;
    this.parentAgent = null;
    this.subAgents = requireNonNullElse(subAgents, ImmutableList.of());
    this.beforeAgentCallback = requireNonNullElse(beforeAgentCallback, ImmutableList.of());
    this.afterAgentCallback = requireNonNullElse(afterAgentCallback, ImmutableList.of());

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

  public List<? extends BeforeAgentCallback> beforeAgentCallback() {
    return beforeAgentCallback;
  }

  public List<? extends AfterAgentCallback> afterAgentCallback() {
    return afterAgentCallback;
  }

  /**
   * The resolved beforeAgentCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends BeforeAgentCallback> canonicalBeforeAgentCallbacks() {
    return beforeAgentCallback;
  }

  /**
   * The resolved afterAgentCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public List<? extends AfterAgentCallback> canonicalAfterAgentCallbacks() {
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
    InvocationContext.Builder builder = parentContext.toBuilder();
    builder.agent(this);
    // Check for branch to be truthy (not None, not empty string),
    if (parentContext.branch().filter(s -> !s.isEmpty()).isPresent()) {
      builder.branch(parentContext.branch().get() + "." + name());
    }
    return builder.build();
  }

  /**
   * Runs the agent asynchronously.
   *
   * @param parentContext Parent context to inherit.
   * @return stream of agent-generated events.
   */
  public Flowable<Event> runAsync(InvocationContext parentContext) {
    Tracer tracer = Tracing.getTracer();
    return Flowable.defer(
        () -> {
          Span span =
              tracer
                  .spanBuilder("agent_run [" + name() + "]")
                  .setParent(Context.current())
                  .startSpan();
          Context spanContext = Context.current().with(span);

          InvocationContext invocationContext = createInvocationContext(parentContext);

          return Tracing.traceFlowable(
              spanContext,
              span,
              () ->
                  callCallback(
                          beforeCallbacksToFunctions(
                              invocationContext.pluginManager(), beforeAgentCallback),
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
                                                    afterAgentCallback),
                                                invocationContext)
                                            .flatMapPublisher(Flowable::fromOptional));

                            return Flowable.concat(beforeEvents, mainEvents, afterEvents);
                          }));
        });
  }

  /**
   * Converts before-agent callbacks to functions.
   *
   * @param callbacks Before-agent callbacks.
   * @return callback functions.
   */
  private ImmutableList<Function<CallbackContext, Maybe<Content>>> beforeCallbacksToFunctions(
      Plugin pluginManager, List<? extends BeforeAgentCallback> callbacks) {
    return Stream.concat(
            Stream.of(ctx -> pluginManager.beforeAgentCallback(this, ctx)),
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
      Plugin pluginManager, List<? extends AfterAgentCallback> callbacks) {
    return Stream.concat(
            Stream.of(ctx -> pluginManager.afterAgentCallback(this, ctx)),
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
                        invocationContext.setEndInvocation(true);
                        return Optional.of(
                            Event.builder()
                                .id(Event.generateEventId())
                                .invocationId(invocationContext.invocationId())
                                .author(name())
                                .branch(invocationContext.branch())
                                .actions(callbackContext.eventActions())
                                .content(content)
                                .build());
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
    Tracer tracer = Tracing.getTracer();
    return Flowable.defer(
        () -> {
          Span span =
              tracer
                  .spanBuilder("agent_run [" + name() + "]")
                  .setParent(Context.current())
                  .startSpan();
          Context spanContext = Context.current().with(span);

          InvocationContext invocationContext = createInvocationContext(parentContext);

          return Tracing.traceFlowable(spanContext, span, () -> runLiveImpl(invocationContext));
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

  /**
   * Base Builder for all agents.
   *
   * @param <B> The concrete builder type.
   */
  public abstract static class Builder<B extends Builder<B>> {
    protected String name;
    protected String description;
    protected ImmutableList<BaseAgent> subAgents;
    protected ImmutableList<BeforeAgentCallback> beforeAgentCallback;
    protected ImmutableList<AfterAgentCallback> afterAgentCallback;

    /** This is a safe cast to the concrete builder type. */
    @SuppressWarnings("unchecked")
    protected B self() {
      return (B) this;
    }

    @CanIgnoreReturnValue
    public B name(String name) {
      this.name = name;
      return self();
    }

    @CanIgnoreReturnValue
    public B description(String description) {
      this.description = description;
      return self();
    }

    @CanIgnoreReturnValue
    public B subAgents(List<? extends BaseAgent> subAgents) {
      this.subAgents = ImmutableList.copyOf(subAgents);
      return self();
    }

    @CanIgnoreReturnValue
    public B subAgents(BaseAgent... subAgents) {
      this.subAgents = ImmutableList.copyOf(subAgents);
      return self();
    }

    @CanIgnoreReturnValue
    public B beforeAgentCallback(BeforeAgentCallback beforeAgentCallback) {
      this.beforeAgentCallback = ImmutableList.of(beforeAgentCallback);
      return self();
    }

    @CanIgnoreReturnValue
    public B beforeAgentCallback(List<Callbacks.BeforeAgentCallbackBase> beforeAgentCallback) {
      this.beforeAgentCallback = CallbackUtil.getBeforeAgentCallbacks(beforeAgentCallback);
      return self();
    }

    @CanIgnoreReturnValue
    public B afterAgentCallback(AfterAgentCallback afterAgentCallback) {
      this.afterAgentCallback = ImmutableList.of(afterAgentCallback);
      return self();
    }

    @CanIgnoreReturnValue
    public B afterAgentCallback(List<Callbacks.AfterAgentCallbackBase> afterAgentCallback) {
      this.afterAgentCallback = CallbackUtil.getAfterAgentCallbacks(afterAgentCallback);
      return self();
    }

    public abstract BaseAgent build();
  }
}
