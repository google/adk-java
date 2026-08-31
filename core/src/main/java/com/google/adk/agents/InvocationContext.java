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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** The context for an agent invocation. */
@SuppressWarnings("deprecation") // Plumbs the deprecated ResumabilityConfig.
public class InvocationContext {

  private final BaseSessionService sessionService;
  private final BaseArtifactService artifactService;
  private final BaseMemoryService memoryService;
  private final Plugin pluginManager;
  @Nullable private final LiveRequestQueue liveRequestQueue;
  private final Map<String, ActiveStreamingTool> activeStreamingTools;
  private final String invocationId;
  private final Session session;
  @Nullable private final Content userContent;
  private final RunConfig runConfig;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;
  @Nullable private final ContextCacheConfig contextCacheConfig;
  private final @Nullable ResumabilityConfig resumabilityConfig;
  private final InvocationCostManager invocationCostManager;
  private final Map<String, Object> callbackContextData;
  // Resumability checkpoints, shared by reference across derived contexts so a sub-agent's
  // checkpoint is visible to its parent and the runner.
  private final Map<String, Map<String, Object>> agentStates;
  private final Map<String, Boolean> endOfAgents;

  @Nullable private String branch;
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
    this.eventsCompactionConfig = builder.eventsCompactionConfig;
    this.contextCacheConfig = builder.contextCacheConfig;
    this.resumabilityConfig = builder.resumabilityConfig;
    this.invocationCostManager = builder.invocationCostManager;
    // Don't copy the callback context data.  This should be the same instance for the full
    // invocation invocation so that Plugins can access the same data it during the invocation
    // across all types of callbacks.
    this.callbackContextData = builder.callbackContextData;
    this.agentStates = builder.agentStates;
    this.endOfAgents = builder.endOfAgents;
  }

  /** Returns a new {@link Builder} for creating {@link InvocationContext} instances. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder toBuilder() {
    return new Builder(this);
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
    return Optional.ofNullable(liveRequestQueue);
  }

  /** Returns the unique ID for this invocation. */
  public String invocationId() {
    return invocationId;
  }

  /**
   * Sets the [branch] ID for the current invocation. A branch represents a fork in the conversation
   * history.
   */
  public void branch(@Nullable String branch) {
    this.branch = branch;
  }

  /**
   * Returns the branch ID for the current invocation, if one is set. A branch represents a fork in
   * the conversation history.
   */
  public Optional<String> branch() {
    return Optional.ofNullable(branch);
  }

  /** Returns the agent being invoked. */
  public BaseAgent agent() {
    return agent;
  }

  /** Returns the session associated with this invocation. */
  public Session session() {
    return session;
  }

  /** Returns the user content that triggered this invocation, if any. */
  public Optional<Content> userContent() {
    return Optional.ofNullable(userContent);
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

  /** Returns the events compaction configuration for the current agent run. */
  public Optional<EventsCompactionConfig> eventsCompactionConfig() {
    return Optional.ofNullable(eventsCompactionConfig);
  }

  /** Returns the context cache configuration for the current agent run. */
  public Optional<ContextCacheConfig> contextCacheConfig() {
    return Optional.ofNullable(contextCacheConfig);
  }

  /**
   * Returns whether the current invocation is resumable. Mirrors Python ADK v1's {@code
   * InvocationContext.is_resumable}.
   */
  public boolean isResumable() {
    return resumabilityConfig != null && resumabilityConfig.isResumable();
  }

  /**
   * Returns the per-agent resumability checkpoint states for this invocation, keyed by agent name.
   * Shared by reference across derived contexts within the invocation.
   */
  public Map<String, Map<String, Object>> agentStates() {
    return agentStates;
  }

  /** Returns the per-agent end-of-agent flags for this invocation, keyed by agent name. */
  public Map<String, Boolean> endOfAgents() {
    return endOfAgents;
  }

  /**
   * Sets the checkpoint state of an agent explicitly. Does not implicitly initialize.
   *
   * @param agentName the agent whose state to set.
   * @param agentState the serialized agent state to store; ignored when {@code endOfAgent} is true.
   * @param endOfAgent when true, marks the agent finished and drops any stored state.
   */
  void setAgentState(
      String agentName, @Nullable Map<String, Object> agentState, boolean endOfAgent) {
    if (endOfAgent) {
      endOfAgents.put(agentName, true);
      agentStates.remove(agentName);
    } else if (agentState != null) {
      agentStates.put(agentName, agentState);
      endOfAgents.put(agentName, false);
    } else {
      endOfAgents.remove(agentName);
      agentStates.remove(agentName);
    }
  }

  /** Recursively resets the checkpoint state of all sub-agents of the given agent. */
  void resetSubAgentStates(String agentName) {
    Optional<BaseAgent> target = agent.findAgent(agentName);
    if (target.isEmpty()) {
      return;
    }
    for (BaseAgent subAgent : target.get().subAgents()) {
      setAgentState(subAgent.name(), null, false);
      resetSubAgentStates(subAgent.name());
    }
  }

  /**
   * Rehydrates {@link #agentStates()} and {@link #endOfAgents()} from the current invocation's
   * history when this invocation is resumable. For each event carrying agent-state information,
   * sets the authoring agent's checkpoint; for a non-workflow author that already produced content,
   * seeds an empty state so it is treated as mid-run.
   */
  public void populateInvocationAgentStates() {
    if (!isResumable()) {
      return;
    }
    for (Event event : getEvents(/* currentInvocation= */ true, /* currentBranch= */ false)) {
      String author = event.author();
      if (author == null) {
        continue;
      }
      Optional<Map<String, Object>> agentState = event.actions().agentState();
      if (event.actions().endOfAgent()) {
        endOfAgents.put(author, true);
        agentStates.remove(author);
      } else if (agentState.isPresent()) {
        agentStates.put(author, agentState.get());
        endOfAgents.put(author, false);
      } else if (!author.equals("user")
          && event.content().isPresent()
          && !agentStates.containsKey(author)) {
        agentStates.put(author, new HashMap<>());
        endOfAgents.put(author, false);
      }
    }
  }

  /**
   * Returns the current session's events, optionally filtered to the current invocation and/or the
   * current branch. Reads the in-memory {@link Session#events()} list, which {@link
   * BaseSessionService#appendEvent} keeps in sync. A {@code null}-branch event is visible on any
   * branch.
   *
   * @param currentInvocation whether to filter to events from this invocation.
   * @param currentBranch whether to filter to events on this branch (or with no branch).
   */
  List<Event> getEvents(boolean currentInvocation, boolean currentBranch) {
    List<Event> results = new ArrayList<>(session.events());
    if (currentInvocation) {
      results.removeIf(event -> !invocationId.equals(event.invocationId()));
    }
    if (currentBranch) {
      results.removeIf(
          event -> event.branch().isPresent() && !event.branch().get().equals(this.branch));
    }
    return results;
  }

  /**
   * Returns whether to pause the invocation right after this event. Pausing (unlike ending) leaves
   * the invocation resumable. Both conditions must hold: the app is {@link #isResumable()} and the
   * event carries a long-running function call (including a synthetic {@code
   * adk_request_confirmation} HITL request).
   */
  boolean shouldPauseInvocation(Event event) {
    if (!isResumable()) {
      return false;
    }
    Set<String> longRunningIds = event.longRunningToolIds().orElse(ImmutableSet.of());
    if (longRunningIds.isEmpty()) {
      return false;
    }
    ImmutableList<FunctionCall> functionCalls = event.functionCalls();
    if (functionCalls.isEmpty()) {
      return false;
    }
    return functionCalls.stream()
        .anyMatch(call -> call.id().isPresent() && longRunningIds.contains(call.id().get()));
  }

  /**
   * Finds the current-invocation event whose function call matches the first function response id
   * in {@code functionResponseEvent}, searching newest-first.
   */
  Optional<Event> findMatchingFunctionCall(Event functionResponseEvent) {
    ImmutableList<FunctionResponse> responses = functionResponseEvent.functionResponses();
    if (responses.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> targetId = responses.get(0).id();
    if (targetId.isEmpty()) {
      return Optional.empty();
    }
    List<Event> events = getEvents(/* currentInvocation= */ true, /* currentBranch= */ false);
    for (int i = events.size() - 1; i >= 0; i--) {
      for (FunctionCall call : events.get(i).functionCalls()) {
        if (call.id().isPresent() && call.id().get().equals(targetId.get())) {
          return Optional.of(events.get(i));
        }
      }
    }
    return Optional.empty();
  }

  private static class InvocationCostManager {
    private final AtomicInteger numberOfLlmCalls = new AtomicInteger(0);

    void incrementAndEnforceLlmCallsLimit(RunConfig runConfig)
        throws LlmCallsLimitExceededException {
      int currentCount = this.numberOfLlmCalls.incrementAndGet();

      if (runConfig != null
          && runConfig.maxLlmCalls() > 0
          && currentCount > runConfig.maxLlmCalls()) {
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
      return numberOfLlmCalls.get() == that.numberOfLlmCalls.get();
    }

    @Override
    public int hashCode() {
      return Integer.hashCode(numberOfLlmCalls.get());
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
      this.eventsCompactionConfig = context.eventsCompactionConfig;
      this.contextCacheConfig = context.contextCacheConfig;
      this.resumabilityConfig = context.resumabilityConfig;
      this.invocationCostManager = context.invocationCostManager;
      // Don't copy the callback context data.  This should be the same instance for the full
      // invocation invocation so that Plugins can access the same data it during the invocation
      // across all types of callbacks.
      this.callbackContextData = context.callbackContextData;
      // Shared by reference so a sub-agent's checkpoint is visible to its parent and the runner
      // within one invocation.
      this.agentStates = context.agentStates;
      this.endOfAgents = context.endOfAgents;
    }

    private BaseSessionService sessionService;
    private BaseArtifactService artifactService;
    private BaseMemoryService memoryService;
    private Plugin pluginManager = new PluginManager();
    @Nullable private LiveRequestQueue liveRequestQueue = null;
    private Map<String, ActiveStreamingTool> activeStreamingTools = new ConcurrentHashMap<>();
    @Nullable private String branch = null;
    private String invocationId = newInvocationContextId();
    private BaseAgent agent;
    private Session session;
    @Nullable private Content userContent = null;
    private RunConfig runConfig = RunConfig.builder().build();
    private boolean endInvocation = false;
    @Nullable private EventsCompactionConfig eventsCompactionConfig;
    @Nullable private ContextCacheConfig contextCacheConfig;
    private @Nullable ResumabilityConfig resumabilityConfig;
    private InvocationCostManager invocationCostManager = new InvocationCostManager();
    private Map<String, Object> callbackContextData = new ConcurrentHashMap<>();
    private Map<String, Map<String, Object>> agentStates = new ConcurrentHashMap<>();
    private Map<String, Boolean> endOfAgents = new ConcurrentHashMap<>();

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
     */
    @CanIgnoreReturnValue
    public Builder liveRequestQueue(@Nullable LiveRequestQueue liveRequestQueue) {
      this.liveRequestQueue = liveRequestQueue;
      return this;
    }

    /**
     * Sets the branch ID for the invocation.
     *
     * @param branch the branch ID for the invocation.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder branch(@Nullable String branch) {
      this.branch = branch;
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
    public Builder userContent(@Nullable Content userContent) {
      this.userContent = userContent;
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
     * Sets the resumability configuration for the invocation.
     *
     * @param resumabilityConfig the resumability configuration.
     * @return this builder instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder resumabilityConfig(@Nullable ResumabilityConfig resumabilityConfig) {
      this.resumabilityConfig = resumabilityConfig;
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
        && Objects.equals(eventsCompactionConfig, that.eventsCompactionConfig)
        && Objects.equals(contextCacheConfig, that.contextCacheConfig)
        && Objects.equals(resumabilityConfig, that.resumabilityConfig)
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
        eventsCompactionConfig,
        contextCacheConfig,
        resumabilityConfig,
        invocationCostManager,
        callbackContextData);
  }
}
