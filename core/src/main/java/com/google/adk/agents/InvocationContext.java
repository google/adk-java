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

import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.LlmCallsLimitExceededException;
import com.google.adk.plugins.Plugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.InlineMe;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** The context for an agent invocation. */
public class InvocationContext {

  private final BaseSessionService sessionService;
  private final BaseArtifactService artifactService;
  private final BaseMemoryService memoryService;
  private final Plugin pluginManager;
  private final Optional<LiveRequestQueue> liveRequestQueue;
  private final Map<String, ActiveStreamingTool> activeStreamingTools;
  private final String invocationId;
  private final Session session;
  private final Optional<Content> userContent;
  private final RunConfig runConfig;
  private final Map<String, BaseAgentState> agentStates;
  private final Map<String, Boolean> endOfAgents;
  private final ResumabilityConfig resumabilityConfig;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;
  @Nullable private final ContextCacheConfig contextCacheConfig;
  private final InvocationCostManager invocationCostManager;
  private final Map<String, Object> callbackContextData;

  private Optional<String> branch;
  private BaseAgent agent;
  private boolean endInvocation;

  protected InvocationContext(Builder builder) {
    this.sessionService = builder.sessionService;
    this.artifactService = builder.artifactService;
    this.memoryService = builder.memoryService;
    this.pluginManager = builder.pluginManager;
    this.liveRequestQueue = builder.liveRequestQueue;
    this.activeStreamingTools = builder.activeStreamingTools;
    this.branch = builder.branch;
    this.invocationId = builder.invocationId;
    this.agent = builder.agent;
    this.session = builder.session;
    this.userContent = builder.userContent;
    this.runConfig = builder.runConfig;
    this.endInvocation = builder.endInvocation;
    this.agentStates = builder.agentStates;
    this.endOfAgents = builder.endOfAgents;
    this.resumabilityConfig = builder.resumabilityConfig;
    this.eventsCompactionConfig = builder.eventsCompactionConfig;
    this.contextCacheConfig = builder.contextCacheConfig;
    this.invocationCostManager = builder.invocationCostManager;
    this.callbackContextData = builder.callbackContextData;
  }

  /**
   * @deprecated Use {@link #builder()} instead.
   */
  @Deprecated(forRemoval = true)
  public InvocationContext(
      BaseSessionService sessionService,
      BaseArtifactService artifactService,
      BaseMemoryService memoryService,
      Plugin pluginManager,
      Optional<LiveRequestQueue> liveRequestQueue,
      Optional<String> branch,
      String invocationId,
      BaseAgent agent,
      Session session,
      Optional<Content> userContent,
      RunConfig runConfig,
      boolean endInvocation) {
    this(
        builder()
            .sessionService(sessionService)
            .artifactService(artifactService)
            .memoryService(memoryService)
            .pluginManager(pluginManager)
            .liveRequestQueue(liveRequestQueue)
            .branch(branch)
            .invocationId(invocationId)
            .agent(agent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(endInvocation));
  }

  /**
   * @deprecated Use {@link #builder()} instead.
   */
  @Deprecated(forRemoval = true)
  public InvocationContext(
      BaseSessionService sessionService,
      BaseArtifactService artifactService,
      BaseMemoryService memoryService,
      Optional<LiveRequestQueue> liveRequestQueue,
      Optional<String> branch,
      String invocationId,
      BaseAgent agent,
      Session session,
      Optional<Content> userContent,
      RunConfig runConfig,
      boolean endInvocation) {
    this(
        builder()
            .sessionService(sessionService)
            .artifactService(artifactService)
            .memoryService(memoryService)
            .liveRequestQueue(liveRequestQueue)
            .branch(branch)
            .invocationId(invocationId)
            .agent(agent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(endInvocation));
  }

  /**
   * @deprecated Use {@link #builder()} instead.
   */
  @InlineMe(
      replacement =
          "InvocationContext.builder()"
              + ".sessionService(sessionService)"
              + ".artifactService(artifactService)"
              + ".invocationId(invocationId)"
              + ".agent(agent)"
              + ".session(session)"
              + ".userContent(Optional.ofNullable(userContent))"
              + ".runConfig(runConfig)"
              + ".build()",
      imports = {"com.google.adk.agents.InvocationContext", "java.util.Optional"})
  @Deprecated(forRemoval = true)
  public static InvocationContext create(
      BaseSessionService sessionService,
      BaseArtifactService artifactService,
      String invocationId,
      BaseAgent agent,
      Session session,
      Content userContent,
      RunConfig runConfig) {
    return builder()
        .sessionService(sessionService)
        .artifactService(artifactService)
        .invocationId(invocationId)
        .agent(agent)
        .session(session)
        .userContent(Optional.ofNullable(userContent))
        .runConfig(runConfig)
        .build();
  }

  /**
   * @deprecated Use {@link #builder()} instead.
   */
  @Deprecated(forRemoval = true)
  public static InvocationContext create(
      BaseSessionService sessionService,
      BaseArtifactService artifactService,
      BaseAgent agent,
      Session session,
      LiveRequestQueue liveRequestQueue,
      RunConfig runConfig) {
    return builder()
        .sessionService(sessionService)
        .artifactService(artifactService)
        .agent(agent)
        .session(session)
        .liveRequestQueue(liveRequestQueue)
        .runConfig(runConfig)
        .build();
  }

  /** Returns a new {@link Builder} for creating {@link InvocationContext} instances. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * Creates a shallow copy of the given {@link InvocationContext}.
   *
   * @deprecated Use {@code other.toBuilder().build()} instead.
   */
  @Deprecated(forRemoval = true)
  public static InvocationContext copyOf(InvocationContext other) {
    return other.toBuilder().build();
  }

  /** Returns the session service for managing session state. */
  public BaseSessionService sessionService() {
    return sessionService;
  }

  /** Returns the artifact service for persisting artifacts. */
  public BaseArtifactService artifactService() {
    return artifactService;
  }

  /** Returns the memory service for accessing agent memory. */
  public BaseMemoryService memoryService() {
    return memoryService;
  }

  /** Returns the plugin manager for accessing tools and plugins. */
  public Plugin pluginManager() {
    return pluginManager;
  }

  /** Returns a map of tool call IDs to active streaming tools for the current invocation. */
  public Map<String, ActiveStreamingTool> activeStreamingTools() {
    return activeStreamingTools;
  }

  /** Returns the queue for managing live requests, if available for this invocation. */
  public Optional<LiveRequestQueue> liveRequestQueue() {
    return liveRequestQueue;
  }

  /** Returns the unique ID for this invocation. */
  public String invocationId() {
    return invocationId;
  }

  /**
   * Sets the [branch] ID for the current invocation. A branch represents a fork in the conversation
   * history.
   *
   * @deprecated Use {@link #toBuilder()} and {@link Builder#branch(String)} instead.
   */
  @Deprecated(forRemoval = true)
  public void branch(@Nullable String branch) {
    this.branch = Optional.ofNullable(branch);
  }

  /**
   * Returns the branch ID for the current invocation, if one is set. A branch represents a fork in
   * the conversation history.
   */
  public Optional<String> branch() {
    return branch;
  }

  /** Returns the agent being invoked. */
  public BaseAgent agent() {
    return agent;
  }

  /**
   * Sets the [agent] being invoked. This is useful when delegating to a sub-agent.
   *
   * @deprecated Use {@link #toBuilder()} and {@link Builder#agent(BaseAgent)} instead.
   */
  @Deprecated(forRemoval = true)
  public void agent(BaseAgent agent) {
    this.agent = agent;
  }

  /** Returns the session associated with this invocation. */
  public Session session() {
    return session;
  }

  /** Returns the user content that triggered this invocation, if any. */
  public Optional<Content> userContent() {
    return userContent;
  }

  /** Returns the configuration for the current agent run. */
  public RunConfig runConfig() {
    return runConfig;
  }

  /**
   * Returns a map for storing temporary context data that can be shared between different parts of
   * the invocation (e.g., before/on/after model callbacks).
   */
  public Map<String, Object> callbackContextData() {
    return callbackContextData;
  }

  /** Returns agent-specific state saved within this invocation. */
  public Map<String, BaseAgentState> agentStates() {
    return agentStates;
  }

  /** Returns map of agents that ended during this invocation. */
  public Map<String, Boolean> endOfAgents() {
    return endOfAgents;
  }

  /**
   * Returns whether this invocation should be ended, e.g., due to reaching a terminal state or
   * error.
   */
  public boolean endInvocation() {
    return endInvocation;
  }

  /** Sets whether this invocation should be ended. */
  public void setEndInvocation(boolean endInvocation) {
    this.endInvocation = endInvocation;
  }

  /** Returns the application name associated with the session. */
  public String appName() {
    return session.appName();
  }

  /** Returns the user ID associated with the session. */
  public String userId() {
    return session.userId();
  }

  /** Generates a new unique ID for an invocation context. */
  public static String newInvocationContextId() {
    return "e-" + UUID.randomUUID();
  }

  /**
   * Increments the count of LLM calls made during this invocation and throws an exception if the
   * limit defined in {@link RunConfig} is exceeded.
   *
   * @throws LlmCallsLimitExceededException if the call limit is exceeded
   */
  public void incrementLlmCallsCount() throws LlmCallsLimitExceededException {
    this.invocationCostManager.incrementAndEnforceLlmCallsLimit(this.runConfig);
  }

  /** Returns whether the current invocation is resumable. */
  public boolean isResumable() {
    return resumabilityConfig.isResumable();
  }

  /** Returns ResumabilityConfig for this invocation. */
  public ResumabilityConfig resumabilityConfig() {
    return resumabilityConfig;
  }

  /**
   * Populates agentStates and endOfAgents maps by reading session events for this invocation id.
   */
  public void populateAgentStates(List<Event> events) {
    events.stream()
        .filter(event -> invocationId().equals(event.invocationId()))
        .forEach(
            event -> {
              if (event.actions() != null) {
                if (event.actions().agentState() != null
                    && !event.actions().agentState().isEmpty()) {
                  agentStates.putAll(event.actions().agentState());
                }
                if (event.actions().endOfAgent()) {
                  endOfAgents.put(event.author(), true);
                }
              }
            });
  }

  /** Returns the events compaction configuration for the current agent run. */
  public Optional<EventsCompactionConfig> eventsCompactionConfig() {
    return Optional.ofNullable(eventsCompactionConfig);
  }

  /** Returns the context cache configuration for the current agent run. */
  public Optional<ContextCacheConfig> contextCacheConfig() {
    return Optional.ofNullable(contextCacheConfig);
  }

  /** Returns whether to pause the invocation right after this [event]. */
  public boolean shouldPauseInvocation(Event event) {
    if (!isResumable()) {
      return false;
    }

    var longRunningToolIds = event.longRunningToolIds().orElse(ImmutableSet.of());
    if (longRunningToolIds.isEmpty()) {
      return false;
    }

    return event.functionCalls().stream()
        .map(FunctionCall::id)
        .flatMap(Optional::stream)
        .anyMatch(functionCallId -> longRunningToolIds.contains(functionCallId));
  }

  private static class InvocationCostManager {
    private int numberOfLlmCalls = 0;

    void incrementAndEnforceLlmCallsLimit(RunConfig runConfig)
        throws LlmCallsLimitExceededException {
      this.numberOfLlmCalls++;

      if (runConfig != null
          && runConfig.maxLlmCalls() > 0
          && this.numberOfLlmCalls > runConfig.maxLlmCalls()) {
        throw new LlmCallsLimitExceededException(
            "Max number of llm calls limit of " + runConfig.maxLlmCalls() + " exceeded");
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof InvocationCostManager that)) {
        return false;
      }
      return numberOfLlmCalls == that.numberOfLlmCalls;
    }

    @Override
    public int hashCode() {
      return Integer.hashCode(numberOfLlmCalls);
    }
  }

  /** Builder for {@link InvocationContext}. */
  public static class Builder {

    private Builder() {}

    private Builder(InvocationContext context) {
      this.sessionService = context.sessionService;
      this.artifactService = context.artifactService;
      this.memoryService = context.memoryService;
      this.pluginManager = context.pluginManager;
      this.liveRequestQueue = context.liveRequestQueue;
      this.activeStreamingTools = new ConcurrentHashMap<>(context.activeStreamingTools);
      this.branch = context.branch;
      this.invocationId = context.invocationId;
      this.agent = context.agent;
      this.session = context.session;
      this.userContent = context.userContent;
      this.runConfig = context.runConfig;
      this.endInvocation = context.endInvocation;
      this.agentStates = new ConcurrentHashMap<>(context.agentStates);
      this.endOfAgents = new ConcurrentHashMap<>(context.endOfAgents);
      this.resumabilityConfig = context.resumabilityConfig;
      this.eventsCompactionConfig = context.eventsCompactionConfig;
      this.contextCacheConfig = context.contextCacheConfig;
      this.invocationCostManager = context.invocationCostManager;
      this.callbackContextData = context.callbackContextData;
    }

    private BaseSessionService sessionService;
    private BaseArtifactService artifactService;
    private BaseMemoryService memoryService;
    private Plugin pluginManager = new PluginManager();
    private Optional<LiveRequestQueue> liveRequestQueue = Optional.empty();
    private Map<String, ActiveStreamingTool> activeStreamingTools = new ConcurrentHashMap<>();
    private Optional<String> branch = Optional.empty();
    private String invocationId = newInvocationContextId();
    private BaseAgent agent;
    private Session session;
    private Optional<Content> userContent = Optional.empty();
    private RunConfig runConfig = RunConfig.builder().build();
    private boolean endInvocation = false;
    private Map<String, BaseAgentState> agentStates = new ConcurrentHashMap<>();
    private Map<String, Boolean> endOfAgents = new ConcurrentHashMap<>();
    private ResumabilityConfig resumabilityConfig = new ResumabilityConfig();
    @Nullable private EventsCompactionConfig eventsCompactionConfig;
    @Nullable private ContextCacheConfig contextCacheConfig;
    private InvocationCostManager invocationCostManager = new InvocationCostManager();
    private Map<String, Object> callbackContextData = new ConcurrentHashMap<>();

    /**
     * Sets the session service for managing session state.
     *
     * @param sessionService the session service to use; required.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder sessionService(BaseSessionService sessionService) {
      this.sessionService = sessionService;
      return this;
    }

    /**
     * Sets the artifact service for persisting artifacts.
     *
     * @param artifactService the artifact service to use; required.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder artifactService(BaseArtifactService artifactService) {
      this.artifactService = artifactService;
      return this;
    }

    /**
     * Sets the memory service for accessing agent memory.
     *
     * @param memoryService the memory service to use.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder memoryService(BaseMemoryService memoryService) {
      this.memoryService = memoryService;
      return this;
    }

    /**
     * Sets the plugin manager for accessing tools and plugins.
     *
     * @param pluginManager the plugin manager to use.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder pluginManager(Plugin pluginManager) {
      this.pluginManager = pluginManager;
      return this;
    }

    /**
     * Sets the queue for managing live requests.
     *
     * @param liveRequestQueue the queue for managing live requests.
     * @return this builder instance for chaining.
     * @deprecated Use {@link #liveRequestQueue(LiveRequestQueue)} instead.
     */
    // TODO: b/462140921 - Builders should not accept Optional parameters.
    @Deprecated(forRemoval = true)
    @CanIgnoreReturnValue
    public Builder liveRequestQueue(Optional<LiveRequestQueue> liveRequestQueue) {
      this.liveRequestQueue = liveRequestQueue;
      return this;
    }

    /**
     * Sets the queue for managing live requests.
     *
     * @param liveRequestQueue the queue for managing live requests.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder liveRequestQueue(@Nullable LiveRequestQueue liveRequestQueue) {
      this.liveRequestQueue = Optional.ofNullable(liveRequestQueue);
      return this;
    }

    /**
     * Sets the branch ID for the invocation.
     *
     * @param branch the branch ID for the invocation.
     * @return this builder instance for chaining.
     * @deprecated Use {@link #branch(String)} instead.
     */
    // TODO: b/462140921 - Builders should not accept Optional parameters.
    @Deprecated(forRemoval = true)
    @CanIgnoreReturnValue
    public Builder branch(Optional<String> branch) {
      this.branch = branch;
      return this;
    }

    /**
     * Sets the branch ID for the invocation.
     *
     * @param branch the branch ID for the invocation.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder branch(String branch) {
      this.branch = Optional.of(branch);
      return this;
    }

    /**
     * Sets the unique ID for the invocation.
     *
     * @param invocationId the unique ID for the invocation.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder invocationId(String invocationId) {
      this.invocationId = invocationId;
      return this;
    }

    /**
     * Sets the agent being invoked.
     *
     * @param agent the agent being invoked; required.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder agent(BaseAgent agent) {
      this.agent = agent;
      return this;
    }

    /**
     * Sets the session associated with this invocation.
     *
     * @param session the session associated with this invocation; required.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder session(Session session) {
      this.session = session;
      return this;
    }

    /**
     * Sets the user content that triggered this invocation.
     *
     * @param userContent the user content that triggered this invocation.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder userContent(Optional<Content> userContent) {
      this.userContent = userContent;
      return this;
    }

    /**
     * Sets the user content that triggered this invocation.
     *
     * @param userContent the user content that triggered this invocation.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder userContent(Content userContent) {
      this.userContent = Optional.of(userContent);
      return this;
    }

    /**
     * Sets the configuration for the current agent run.
     *
     * @param runConfig the configuration for the current agent run.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder runConfig(RunConfig runConfig) {
      this.runConfig = runConfig;
      return this;
    }

    /**
     * Sets whether this invocation should be ended.
     *
     * @param endInvocation whether this invocation should be ended.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder endInvocation(boolean endInvocation) {
      this.endInvocation = endInvocation;
      return this;
    }

    /**
     * Sets agent-specific state saved within this invocation.
     *
     * @param agentStates agent-specific state saved within this invocation.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder agentStates(Map<String, BaseAgentState> agentStates) {
      this.agentStates = agentStates;
      return this;
    }

    /**
     * Sets agent end-of-invocation status.
     *
     * @param endOfAgents agent end-of-invocation status.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder endOfAgents(Map<String, Boolean> endOfAgents) {
      this.endOfAgents = endOfAgents;
      return this;
    }

    /**
     * Sets the resumability configuration for the current agent run.
     *
     * @param resumabilityConfig the resumability configuration.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder resumabilityConfig(ResumabilityConfig resumabilityConfig) {
      this.resumabilityConfig = resumabilityConfig;
      return this;
    }

    /**
     * Sets the events compaction configuration for the current agent run.
     *
     * @param eventsCompactionConfig the events compaction configuration.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder eventsCompactionConfig(@Nullable EventsCompactionConfig eventsCompactionConfig) {
      this.eventsCompactionConfig = eventsCompactionConfig;
      return this;
    }

    /**
     * Sets the context cache configuration for the current agent run.
     *
     * @param contextCacheConfig the context cache configuration.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder contextCacheConfig(@Nullable ContextCacheConfig contextCacheConfig) {
      this.contextCacheConfig = contextCacheConfig;
      return this;
    }

    /**
     * Sets the callback context data for the invocation.
     *
     * @param callbackContextData the callback context data.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder callbackContextData(Map<String, Object> callbackContextData) {
      this.callbackContextData = callbackContextData;
      return this;
    }

    /**
     * Builds the {@link InvocationContext} instance.
     *
     * @throws IllegalStateException if any required parameters are missing.
     */
    public InvocationContext build() {
      validate(this);
      return new InvocationContext(this);
    }
  }

  /**
   * Validates the required parameters fields: invocationId, agent, session, and sessionService.
   *
   * @param builder the builder to validate.
   * @throws IllegalStateException if any required parameters are missing.
   */
  private static void validate(Builder builder) {
    if (isNullOrEmpty(builder.invocationId)) {
      throw new IllegalStateException("Invocation ID must be non-empty.");
    }
    if (builder.agent == null) {
      throw new IllegalStateException("Agent must be set.");
    }
    if (builder.session == null) {
      throw new IllegalStateException("Session must be set.");
    }
    if (builder.sessionService == null) {
      throw new IllegalStateException("Session service must be set.");
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof InvocationContext that)) {
      return false;
    }
    return endInvocation == that.endInvocation
        && Objects.equals(sessionService, that.sessionService)
        && Objects.equals(artifactService, that.artifactService)
        && Objects.equals(memoryService, that.memoryService)
        && Objects.equals(pluginManager, that.pluginManager)
        && Objects.equals(liveRequestQueue, that.liveRequestQueue)
        && Objects.equals(activeStreamingTools, that.activeStreamingTools)
        && Objects.equals(branch, that.branch)
        && Objects.equals(invocationId, that.invocationId)
        && Objects.equals(agent, that.agent)
        && Objects.equals(session, that.session)
        && Objects.equals(userContent, that.userContent)
        && Objects.equals(runConfig, that.runConfig)
        && Objects.equals(agentStates, that.agentStates)
        && Objects.equals(endOfAgents, that.endOfAgents)
        && Objects.equals(resumabilityConfig, that.resumabilityConfig)
        && Objects.equals(eventsCompactionConfig, that.eventsCompactionConfig)
        && Objects.equals(contextCacheConfig, that.contextCacheConfig)
        && Objects.equals(invocationCostManager, that.invocationCostManager)
        && Objects.equals(callbackContextData, that.callbackContextData);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        sessionService,
        artifactService,
        memoryService,
        pluginManager,
        liveRequestQueue,
        activeStreamingTools,
        branch,
        invocationId,
        agent,
        session,
        userContent,
        runConfig,
        endInvocation,
        agentStates,
        endOfAgents,
        resumabilityConfig,
        eventsCompactionConfig,
        contextCacheConfig,
        invocationCostManager,
        callbackContextData);
  }
}
