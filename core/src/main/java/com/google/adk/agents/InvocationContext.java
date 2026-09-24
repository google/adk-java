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
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.adk.annotations.Experimental;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.LlmCallsLimitExceededException;
import com.google.adk.plugins.Plugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
  // Shared by reference so a sub-agent's checkpoint is visible to its parent and the runner.
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

  /**
   * Returns a snapshot of the session's events, keeping only those on the branch this invocation is
   * running on.
   *
   * <p>The rule is author-asymmetric on purpose, so a confirmation the user answered on a
   * sub-branch stays visible while a descendant agent's own events do not.
   */
  public ImmutableList<Event> eventsOnCurrentBranch() {
    ImmutableList<Event> events;
    synchronized (session.events()) {
      events = ImmutableList.copyOf(session.events());
    }
    // Snapshot the mutable branch too, so it cannot change between the id set and the filter.
    @Nullable String scopeBranch = branch;
    // Only the user-response cross-check needs these, and a null or empty branch skips it.
    ImmutableSet<String> branchFunctionCallIds =
        isNullOrEmpty(scopeBranch) ? ImmutableSet.of() : branchFunctionCallIds(events, scopeBranch);
    return events.stream()
        .filter(event -> isOnCurrentBranch(event, scopeBranch, branchFunctionCallIds))
        .collect(toImmutableList());
  }

  /**
   * Returns whether {@code event} belongs to this invocation's branch.
   *
   * <p>A user event matches this branch, a descendant sub-branch, or no branch at all; one carrying
   * function responses must additionally answer a call issued on this branch or below, which is
   * what stops a reply leaking in from a parallel tree. Any other event must sit on exactly this
   * branch, so a descendant's own events stay hidden.
   */
  private boolean isOnCurrentBranch(
      Event event, @Nullable String scopeBranch, ImmutableSet<String> branchFunctionCallIds) {
    @Nullable String eventBranch = event.branch().orElse(null);
    if (!Objects.equals(event.author(), Role.USER)) {
      return Objects.equals(eventBranch, scopeBranch);
    }
    if (!isNullOrEmpty(scopeBranch)) {
      ImmutableSet<String> responseIds =
          event.functionResponses().stream()
              .map(FunctionResponse::id)
              .flatMap(Optional::stream)
              .collect(toImmutableSet());
      if (!responseIds.isEmpty() && Collections.disjoint(responseIds, branchFunctionCallIds)) {
        return false;
      }
    }
    // Mirrors Python's `self.branch` guard: an empty branch has no descendants.
    return eventBranch == null
        || scopeBranch == null
        || eventBranch.equals(scopeBranch)
        || (!scopeBranch.isEmpty() && eventBranch.startsWith(scopeBranch + "."));
  }

  /**
   * Returns the IDs of function calls issued on this branch or on a descendant sub-branch.
   *
   * <p>Branches are dot-joined, so the trailing dot keeps the prefix test on a segment boundary.
   */
  private ImmutableSet<String> branchFunctionCallIds(
      ImmutableList<Event> events, String scopeBranch) {
    String descendantPrefix = scopeBranch + ".";
    return events.stream()
        .filter(
            event -> {
              @Nullable String eventBranch = event.branch().orElse(null);
              return !isNullOrEmpty(eventBranch)
                  && (eventBranch.equals(scopeBranch) || eventBranch.startsWith(descendantPrefix));
            })
        .flatMap(event -> event.functionCalls().stream())
        .map(FunctionCall::id)
        .flatMap(Optional::stream)
        .collect(toImmutableSet());
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

  /** Returns whether the current invocation is resumable. */
  @Experimental
  public boolean isResumable() {
    return resumabilityConfig != null && resumabilityConfig.isResumable();
  }

  /**
   * Returns whether the invocation runs the legacy resumption flow: the behavior {@link
   * #isResumable()} had before durable checkpoints existed. It is a separate flow, not a weaker
   * {@link #isResumable()}, so a caller wanting either has to ask for both.
   *
   * @deprecated Reports the deprecated plain-text continuation shim and goes away with it; use
   *     {@link #isResumable()}.
   */
  @Deprecated
  @SuppressWarnings("deprecation") // The shim it reads is deprecated by design.
  public boolean isLegacyResumability() {
    return resumabilityConfig != null && resumabilityConfig.isPlainTextContinuationAutoResume();
  }

  /**
   * Returns an unmodifiable view of the per-agent resumability checkpoint states for this
   * invocation, keyed by agent name. The backing map is shared by reference across derived contexts
   * within the invocation; mutate it only through {@link #setAgentState}.
   */
  @Experimental
  public Map<String, Map<String, Object>> agentStates() {
    return Collections.unmodifiableMap(agentStates);
  }

  /**
   * Returns an unmodifiable view of the per-agent end-of-agent flags for this invocation, keyed by
   * agent name.
   */
  @Experimental
  public Map<String, Boolean> endOfAgents() {
    return Collections.unmodifiableMap(endOfAgents);
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
      // LinkedHashMap, not ImmutableMap.copyOf: a deserialized agentState may carry a null value.
      agentStates.put(agentName, Collections.unmodifiableMap(new LinkedHashMap<>(agentState)));
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
      setAgentState(subAgent.name(), /* agentState= */ null, /* endOfAgent= */ false);
      resetSubAgentStates(subAgent.name());
    }
  }

  /**
   * Rehydrates {@link #agentStates()} and {@link #endOfAgents()} from the current invocation's
   * history when this invocation is resumable. For each event carrying agent-state information,
   * sets the authoring agent's checkpoint; for a non-workflow author that already produced content,
   * seeds an empty state so it is treated as mid-run.
   */
  @Experimental
  public void populateInvocationAgentStates() {
    if (!isResumable()) {
      return;
    }
    for (Event event : events(/* currentInvocation= */ true, /* currentBranch= */ false)) {
      String author = event.author();
      if (author == null) {
        continue;
      }
      Optional<Map<String, Object>> agentState = event.actions().agentState();
      // The deprecated setEndInvocation aliases endOfAgent, so only a content-less marker counts.
      if (event.actions().endOfAgent() && event.content().isEmpty()) {
        endOfAgents.put(author, true);
        agentStates.remove(author);
      } else if (agentState.isPresent()) {
        setAgentState(author, agentState.get(), /* endOfAgent= */ false);
      } else if (!author.equals(Role.USER)
          && event.content().isPresent()
          && !agentStates.containsKey(author)) {
        // Content after an end-of-agent marker reopens the agent, as in Python.
        agentStates.put(author, ImmutableMap.of());
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
  @Experimental
  public ImmutableList<Event> events(boolean currentInvocation, boolean currentBranch) {
    // eventsOnCurrentBranch owns branch filtering: author-asymmetric rule and cross-branch guard.
    List<Event> results;
    if (currentBranch) {
      results = new ArrayList<>(eventsOnCurrentBranch());
    } else {
      // session.events() is a synchronized list; copy it under its own monitor.
      List<Event> sessionEvents = session.events();
      synchronized (sessionEvents) {
        results = new ArrayList<>(sessionEvents);
      }
    }
    if (currentInvocation) {
      results.removeIf(event -> !invocationId.equals(event.invocationId()));
    }
    return ImmutableList.copyOf(results);
  }

  /**
   * Returns whether to pause the invocation right after this event: it is resumable and the event
   * carries a long-running function call, including a synthetic {@code adk_request_confirmation}
   * HITL request. Pausing, unlike ending, leaves the invocation resumable.
   */
  @Experimental
  public boolean shouldPauseInvocation(Event event) {
    return isResumable() && carriesLongRunningCall(event);
  }

  /**
   * Returns whether the event carries a long-running function call, independent of the mode. Shared
   * with the legacy flow's check so the two cannot drift apart.
   */
  private static boolean carriesLongRunningCall(Event event) {
    return Functions.hasPendingLongRunningCall(event);
  }

  /**
   * Returns whether either of the last two events on this branch pauses the invocation, the
   * condition Python uses to decide whether to withhold an agent's end-of-agent checkpoint.
   */
  boolean lastEventsPauseInvocation() {
    ImmutableList<Event> events = events(/* currentInvocation= */ true, /* currentBranch= */ true);
    return events.subList(Math.max(0, events.size() - 2), events.size()).stream()
        .anyMatch(this::shouldPauseInvocation);
  }

  /**
   * Returns whether a long-running call made anywhere inside {@code agent}'s subtree is still
   * unanswered. A workflow agent needs this because a sub-agent can pause without emitting an
   * event, and because Java gives every sub-agent of a {@link ParallelAgent} that agent's own
   * branch, which no branch filter can tell apart. Scoping by subtree keeps a paused parallel
   * sibling from stalling an unrelated branch, as Python does.
   */
  boolean hasUnansweredLongRunningCallIn(BaseAgent agent) {
    Set<String> scope = new HashSet<>();
    collectAgentNames(agent, scope);
    return hasUnansweredLongRunningCall(
        events(/* currentInvocation= */ true, /* currentBranch= */ false), scope);
  }

  private static void collectAgentNames(BaseAgent agent, Set<String> names) {
    if (!names.add(agent.name())) {
      return;
    }
    for (BaseAgent subAgent : agent.subAgents()) {
      collectAgentNames(subAgent, names);
    }
  }

  /**
   * Whether {@code events} hold a long-running call with no response. {@code scope} restricts which
   * authors' calls count; responses always count whoever authored them, since a resumed answer is
   * authored by the user.
   */
  private static boolean hasUnansweredLongRunningCall(
      ImmutableList<Event> events, Set<String> scope) {
    if (events.isEmpty()) {
      return false;
    }
    Set<String> awaited = new HashSet<>();
    for (Event event : events) {
      if (!carriesLongRunningCall(event)) {
        continue;
      }
      if (!scope.contains(event.author())) {
        continue;
      }
      // Only long-running calls count, as in Python: an ordinary call in the event runs inline.
      Set<String> longRunningIds = event.longRunningToolIds().orElse(ImmutableSet.of());
      for (FunctionCall call : event.functionCalls()) {
        call.id().filter(longRunningIds::contains).ifPresent(awaited::add);
      }
    }
    if (awaited.isEmpty()) {
      return false;
    }
    Set<String> answered = new HashSet<>();
    for (Event event : events) {
      for (FunctionResponse response : event.functionResponses()) {
        response.id().ifPresent(answered::add);
      }
    }
    return !answered.containsAll(awaited);
  }

  /**
   * Finds the current-invocation event whose function call matches any function response id in
   * {@code functionResponseEvent}, searching newest-first. Matching any id (not just the first)
   * keeps parallel function responses resolvable when their calls interleave.
   */
  Optional<Event> findMatchingFunctionCall(Event functionResponseEvent) {
    Set<String> targetIds = new HashSet<>();
    for (FunctionResponse response : functionResponseEvent.functionResponses()) {
      response.id().ifPresent(targetIds::add);
    }
    if (targetIds.isEmpty()) {
      return Optional.empty();
    }
    ImmutableList<Event> events = events(/* currentInvocation= */ true, /* currentBranch= */ false);
    for (int i = events.size() - 1; i >= 0; i--) {
      // The response event never answers itself, as in Python.
      if (events.get(i).id().equals(functionResponseEvent.id())) {
        continue;
      }
      for (FunctionCall call : events.get(i).functionCalls()) {
        if (call.id().filter(targetIds::contains).isPresent()) {
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
      // Shared by reference, not copied: the checkpoints belong to the invocation, not a context.
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
