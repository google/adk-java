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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;

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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Base class for all agents. */
public abstract class BaseAgent {

  // Pattern for valid agent names.
  private static final String IDENTIFIER_REGEX = "^_?[a-zA-Z0-9]*([. _-][a-zA-Z0-9]+)*$";
  private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(IDENTIFIER_REGEX);

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

  private final ImmutableList<? extends BaseAgent> subAgents;

  private final ImmutableList<? extends BeforeAgentCallback> beforeAgentCallback;
  private final ImmutableList<? extends AfterAgentCallback> afterAgentCallback;

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
      @Nullable List<? extends BaseAgent> subAgents,
      @Nullable List<? extends BeforeAgentCallback> beforeAgentCallback,
      @Nullable List<? extends AfterAgentCallback> afterAgentCallback) {
    validateAgentName(name);
    this.name = name;
    this.description = description;
    this.parentAgent = null;
    this.subAgents = (subAgents != null) ? ImmutableList.copyOf(subAgents) : ImmutableList.of();
    validateSubAgents(this.name, this.subAgents);
    this.beforeAgentCallback =
        (beforeAgentCallback != null)
            ? ImmutableList.copyOf(beforeAgentCallback)
            : ImmutableList.of();
    this.afterAgentCallback =
        (afterAgentCallback != null)
            ? ImmutableList.copyOf(afterAgentCallback)
            : ImmutableList.of();

    // Establish parent relationships for all sub-agents if needed.
    for (BaseAgent subAgent : this.subAgents) {
      subAgent.parentAgent(this);
    }
  }

  /**
   * Closes all sub-agents.
   *
   * @return a {@link Completable} that completes when all sub-agents are closed.
   */
  public Completable close() {
    List<Completable> completables = new ArrayList<>();
    this.subAgents.forEach(subAgent -> completables.add(subAgent.close()));
    return Completable.mergeDelayError(completables);
  }

  /**
   * Validates the agent name.
   *
   * @param name The agent name to validate.
   * @throws IllegalArgumentException if the agent name is null, empty, or does not match the
   *     identifier pattern.
   */
  private static void validateAgentName(String name) {
    if (isNullOrEmpty(name)) {
      throw new IllegalArgumentException("Agent name cannot be null or empty.");
    }
    if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException(
          format("Agent name '%s' does not match regex '%s'.", name, IDENTIFIER_REGEX));
    }
    if (name.equals("user")) {
      throw new IllegalArgumentException(
          "Agent name cannot be 'user'; reserved for end-user input.");
    }
  }

  /**
   * Validates the sub-agents.
   *
   * @param name The name of the parent agent.
   * @param subAgents The list of sub-agents to validate.
   * @throws IllegalArgumentException if the sub-agents have duplicate names.
   */
  private static void validateSubAgents(
      String name, @Nullable List<? extends BaseAgent> subAgents) {
    if (subAgents == null) {
      return;
    }
    HashSet<String> subAgentNames = new HashSet<>();
    HashSet<String> duplicateSubAgentNames = new HashSet<>();
    for (BaseAgent subAgent : subAgents) {
      String subAgentName = subAgent.name();
      // NOTE: Mocked agents have null names because BaseAgent.name() is a final method that
      // cannot be mocked.
      if (subAgentName != null && !subAgentNames.add(subAgentName)) {
        duplicateSubAgentNames.add(subAgentName);
      }
    }
    if (!duplicateSubAgentNames.isEmpty()) {
      throw new IllegalArgumentException(
          format(
              "Agent named '%s' has sub-agents with duplicate names: %s. Sub-agents: %s",
              name, duplicateSubAgentNames, subAgents));
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
   * @return an {@link Optional} containing the agent or descendant with the given name, or {@link
   *     Optional#empty()} if not found.
   */
  public Optional<BaseAgent> findAgent(String name) {
    if (this.name().equals(name)) {
      return Optional.of(this);
    }
    return findSubAgent(name);
  }

  /**
   * Recursively search sub agent by name.
   *
   * @return an {@link Optional} containing the sub agent with the given name, or {@link
   *     Optional#empty()} if not found.
   */
  public Optional<BaseAgent> findSubAgent(String name) {
    return subAgents.stream()
        .map(subAgent -> subAgent.findAgent(name))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public List<? extends BaseAgent> subAgents() {
    return subAgents;
  }

  public ImmutableList<? extends BeforeAgentCallback> beforeAgentCallback() {
    return beforeAgentCallback;
  }

  public ImmutableList<? extends AfterAgentCallback> afterAgentCallback() {
    return afterAgentCallback;
  }

  /**
   * The resolved beforeAgentCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public ImmutableList<? extends BeforeAgentCallback> canonicalBeforeAgentCallbacks() {
    return beforeAgentCallback;
  }

  /**
   * The resolved afterAgentCallback field as a list.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  public ImmutableList<? extends AfterAgentCallback> canonicalAfterAgentCallbacks() {
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
    parentContext
        .branch()
        .filter(s -> !s.isEmpty())
        .ifPresent(branch -> builder.branch(branch + "." + name()));
    return builder.build();
  }

  /**
   * Runs the agent asynchronously.
   *
   * @param parentContext Parent context to inherit.
   * @return stream of agent-generated events.
   */
  public Flowable<Event> runAsync(InvocationContext parentContext) {
    return run(parentContext, this::runAsyncImpl);
  }

  /**
   * Runs the agent with the given implementation.
   *
   * @param parentContext Parent context to inherit.
   * @param runImplementation The agent-specific logic to run.
   * @return stream of agent-generated events.
   */
  private Flowable<Event> run(
      InvocationContext parentContext,
      Function<InvocationContext, Flowable<Event>> runImplementation) {
    Tracer tracer = Tracing.getTracer();
    return Flowable.defer(
        () -> {
          InvocationContext invocationContext = createInvocationContext(parentContext);
          Span span =
              tracer.spanBuilder("invoke_agent " + name()).setParent(Context.current()).startSpan();
          Tracing.traceAgentInvocation(span, name(), description(), invocationContext);
          Context spanContext = Context.current().with(span);

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
                                Flowable.defer(() -> runImplementation.apply(invocationContext));
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
    if (agentCallbacks.isEmpty()) {
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
    return run(parentContext, this::runLiveImpl);
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
