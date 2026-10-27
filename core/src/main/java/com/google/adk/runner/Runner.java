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

package com.google.adk.runner;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.toCollection;

import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.NodeViewAgent;
import com.google.adk.agents.Role;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.annotations.Experimental;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.flows.llmflows.PersistBarrier;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.Model;
import com.google.adk.platform.UuidProvider;
import com.google.adk.plugins.Plugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.google.adk.telemetry.Tracing;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.CollectionUtils;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeRunner;
import com.google.adk.workflow.Workflow;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.MapMaker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The main class for the GenAI Agents runner. */
public class Runner {
  private final BaseAgent agent;
  private final @Nullable Node node;
  private final String appName;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  @Nullable private final BaseMemoryService memoryService;
  private final PluginManager pluginManager;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;
  @Nullable private final ContextCacheConfig contextCacheConfig;
  private final @Nullable ResumabilityConfig resumabilityConfig;
  @Nullable private final Scheduler scheduler;
  private final InstantSource instantSource;
  private final UuidProvider uuidProvider;
  private final ConcurrentMap<String, Completable> activeSessionCompletables =
      new MapMaker().weakValues().makeMap();

  /** Builder for {@link Runner}. */
  public static class Builder {
    private App app;
    private BaseAgent agent;
    private String appName;
    private BaseArtifactService artifactService = new InMemoryArtifactService();
    private @Nullable BaseSessionService sessionService = null;
    // Distinguishes "never set" (a shared in-memory default is created) from an explicit null.
    private boolean sessionServiceSet;
    @Nullable private BaseMemoryService memoryService = null;
    private List<? extends Plugin> plugins = ImmutableList.of();
    @Nullable private Scheduler scheduler = null;
    private InstantSource instantSource = InstantSource.system();
    private UuidProvider uuidProvider = UuidProvider.SYSTEM;

    @CanIgnoreReturnValue
    public Builder app(App app) {
      Preconditions.checkState(this.agent == null, "app() cannot be called when agent() is set.");
      this.app = app;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder agent(BaseAgent agent) {
      Preconditions.checkState(this.app == null, "agent() cannot be called when app is set.");
      this.agent = agent;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder appName(String appName) {
      Preconditions.checkState(this.app == null, "appName() cannot be called when app is set.");
      this.appName = appName;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder artifactService(BaseArtifactService artifactService) {
      this.artifactService = artifactService;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder sessionService(BaseSessionService sessionService) {
      this.sessionService = sessionService;
      this.sessionServiceSet = true;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder memoryService(BaseMemoryService memoryService) {
      this.memoryService = memoryService;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder plugins(List<? extends Plugin> plugins) {
      Preconditions.checkState(this.app == null, "plugins() cannot be called when app is set.");
      this.plugins = plugins;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder plugins(Plugin... plugins) {
      Preconditions.checkState(this.app == null, "plugins() cannot be called when app is set.");
      this.plugins = ImmutableList.copyOf(plugins);
      return this;
    }

    /**
     * Sets the {@link Scheduler} for the work ADK hands off to worker threads in this runner's
     * invocations: the sub-agents of a {@code ParallelAgent}, tool calls in {@link
     * RunConfig.ToolExecutionMode#PARALLEL_SUBSCRIBE} mode, workflow node retries and attempts with
     * a timeout, and the hop that starts the live send loop. The runs that an {@code AgentTool}
     * nests inherit it. An agent-level {@code ParallelAgent.Builder#scheduler} or {@code
     * LlmAgent.Builder#executor} takes precedence. {@code null} (the default) selects {@code
     * Schedulers.io()}.
     *
     * <p>Only these hand-offs are affected; work that completes asynchronously elsewhere (model
     * clients, session services, MCP, plugins) stays on its own threads. {@code
     * Schedulers.trampoline()} runs the hand-offs inline, so sub-agents and tools are subscribed
     * sequentially, but anything they start asynchronously can still overlap.
     */
    @CanIgnoreReturnValue
    public Builder scheduler(@Nullable Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /**
     * Sets the {@link InstantSource} that supplies every timestamp this runner generates: event
     * timestamps, and the {@code lastUpdateTime} of sessions created by the default in-memory
     * session service, which is created on the first {@code build()}. {@code null} (the default)
     * selects {@link InstantSource#system()}.
     */
    @CanIgnoreReturnValue
    public Builder instantSource(@Nullable InstantSource instantSource) {
      this.instantSource = instantSource != null ? instantSource : InstantSource.system();
      return this;
    }

    /**
     * Sets the {@link UuidProvider} that supplies every identifier this runner generates: event,
     * invocation, and client function-call ids, and the ids of sessions created by the default
     * in-memory session service, which is created on the first {@code build()}. {@code null} (the
     * default) selects {@link UuidProvider#SYSTEM}.
     */
    @CanIgnoreReturnValue
    public Builder uuidProvider(@Nullable UuidProvider uuidProvider) {
      this.uuidProvider = uuidProvider != null ? uuidProvider : UuidProvider.SYSTEM;
      return this;
    }

    // OVERWRITE is the documented default of new InMemorySessionService() until callers migrate.
    @SuppressWarnings("deprecation")
    public Runner build() {
      BaseAgent buildAgent;
      Node buildNode;
      String buildAppName;
      List<? extends Plugin> buildPlugins;
      EventsCompactionConfig buildEventsCompactionConfig;
      ContextCacheConfig buildContextCacheConfig;
      ResumabilityConfig buildResumabilityConfig;

      if (this.app != null) {
        if (this.agent != null) {
          throw new IllegalStateException("agent() cannot be called when app() is called.");
        }
        if (!this.plugins.isEmpty()) {
          throw new IllegalStateException("plugins() cannot be called when app() is called.");
        }
        buildAgent = this.app.rootAgent();
        buildNode = this.app.rootNode().orElse(null);
        buildPlugins = this.app.plugins();
        buildAppName = this.appName == null ? this.app.name() : this.appName;
        buildEventsCompactionConfig = this.app.eventsCompactionConfig();
        buildContextCacheConfig = this.app.contextCacheConfig();
        buildResumabilityConfig = this.app.resumabilityConfig();
      } else {
        buildAgent = this.agent;
        buildNode = null;
        buildAppName = this.appName;
        buildPlugins = this.plugins;
        buildEventsCompactionConfig = null;
        buildContextCacheConfig = null;
        buildResumabilityConfig = null;
      }

      if (buildAgent == null) {
        throw new IllegalStateException("Agent must be provided via app() or agent().");
      }
      if (buildAppName == null) {
        throw new IllegalStateException("App name must be provided via app() or appName().");
      }
      if (artifactService == null) {
        throw new IllegalStateException("Artifact service must be provided.");
      }
      if (sessionService == null) {
        if (sessionServiceSet) {
          throw new IllegalStateException("Session service must be provided.");
        }
        // Created once per builder, so every runner built from it shares the same sessions.
        sessionService =
            new InMemorySessionService(
                InMemorySessionService.DuplicateSessionIdBehavior.OVERWRITE,
                instantSource,
                uuidProvider);
      }
      return new Runner(
          buildAgent,
          buildNode,
          buildAppName,
          artifactService,
          sessionService,
          memoryService,
          buildPlugins,
          buildEventsCompactionConfig,
          buildContextCacheConfig,
          buildResumabilityConfig,
          scheduler,
          instantSource,
          uuidProvider);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a new {@code Runner}.
   *
   * @deprecated Use {@link Runner.Builder} instead.
   */
  @Deprecated
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService) {
    this(agent, appName, artifactService, sessionService, memoryService, ImmutableList.of());
  }

  /**
   * Creates a new {@code Runner} with a list of plugins.
   *
   * @deprecated Use {@link Runner.Builder} instead.
   */
  @Deprecated
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService,
      List<? extends Plugin> plugins) {
    this(agent, appName, artifactService, sessionService, memoryService, plugins, null, null);
  }

  /**
   * Creates a new {@code Runner} with a list of plugins.
   *
   * @deprecated Use {@link Runner.Builder} instead.
   */
  @Deprecated
  protected Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ContextCacheConfig contextCacheConfig) {
    this(
        agent,
        appName,
        artifactService,
        sessionService,
        memoryService,
        plugins,
        eventsCompactionConfig,
        contextCacheConfig,
        /* resumabilityConfig= */ null);
  }

  /**
   * Creates a new {@code Runner} with a resumability config.
   *
   * @deprecated Use {@link Runner.Builder} instead.
   */
  @Deprecated
  protected Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ContextCacheConfig contextCacheConfig,
      @Nullable ResumabilityConfig resumabilityConfig) {
    this(
        agent,
        /* node= */ null,
        appName,
        artifactService,
        sessionService,
        memoryService,
        plugins,
        eventsCompactionConfig,
        contextCacheConfig,
        resumabilityConfig,
        /* scheduler= */ null,
        InstantSource.system(),
        UuidProvider.SYSTEM);
  }

  private Runner(
      BaseAgent agent,
      @Nullable Node node,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ContextCacheConfig contextCacheConfig,
      @Nullable ResumabilityConfig resumabilityConfig,
      @Nullable Scheduler scheduler,
      InstantSource instantSource,
      UuidProvider uuidProvider) {
    checkArgument(
        !(agent instanceof NodeViewAgent) || node != null,
        "A node view agent cannot run on its own; run its node through an app's root node.");
    this.agent = agent;
    this.node = node;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
    this.pluginManager = new PluginManager(plugins);
    this.eventsCompactionConfig =
        createEventsCompactionConfig(agent, eventsCompactionConfig, instantSource, uuidProvider);
    this.contextCacheConfig = contextCacheConfig;
    this.resumabilityConfig = resumabilityConfig;
    this.scheduler = scheduler;
    this.instantSource = instantSource;
    this.uuidProvider = uuidProvider;
  }

  /**
   * Creates a new {@code Runner}.
   *
   * @deprecated Use {@link Runner.Builder} instead.
   */
  @Deprecated
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService) {
    this(agent, appName, artifactService, sessionService, null);
  }

  public BaseAgent agent() {
    return this.agent;
  }

  /**
   * Returns the workflow node this runner runs, or empty when it runs an agent tree. When it runs a
   * node, {@link #agent()} returns a {@link NodeViewAgent} that stands in for it.
   */
  @Experimental
  public Optional<Node> node() {
    return Optional.ofNullable(node);
  }

  public String appName() {
    return this.appName;
  }

  public BaseArtifactService artifactService() {
    return this.artifactService;
  }

  public BaseSessionService sessionService() {
    return this.sessionService;
  }

  @Nullable
  public BaseMemoryService memoryService() {
    return this.memoryService;
  }

  public PluginManager pluginManager() {
    return this.pluginManager;
  }

  /** Closes all plugins, code executors, and releases any resources. */
  public Completable close() {
    List<Completable> completables = new ArrayList<>();
    completables.add(agent.close());
    if (node != null) {
      Set<BaseAgent> nodeAgents = new LinkedHashSet<>();
      collectAgents(node, nodeAgents);
      // Closing an agent closes its sub-agents, so skip agents whose ancestor is closed here.
      nodeAgents.stream()
          .filter(nodeAgent -> !hasAncestorIn(nodeAgent, nodeAgents))
          .forEach(nodeAgent -> completables.add(nodeAgent.close()));
    }
    completables.add(this.pluginManager.close());
    return Completable.mergeDelayError(completables);
  }

  /** Adds the agents that {@code node} runs, through its own graph and nested workflows. */
  private static void collectAgents(Node node, Set<BaseAgent> agents) {
    if (node instanceof BaseAgent agent) {
      agents.add(agent);
    } else if (node instanceof Workflow workflow) {
      for (Edge edge : workflow.edges()) {
        collectAgents(edge.from(), agents);
        collectAgents(edge.to(), agents);
      }
    }
  }

  /** Returns whether {@code agents} contains an ancestor of {@code agent}. */
  private static boolean hasAncestorIn(BaseAgent agent, Set<BaseAgent> agents) {
    for (BaseAgent ancestor = agent.parentAgent();
        ancestor != null;
        ancestor = ancestor.parentAgent()) {
      if (agents.contains(ancestor)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Prepares and creates the user-authored {@link Event}, saving any input blobs as artifacts and
   * attaching state deltas if configured.
   *
   * <p>{@code newMessage} is never modified; when inline blobs are saved as artifacts, the returned
   * event carries a copy in which the blob data is replaced by placeholders.
   *
   * @throws IllegalArgumentException if message has no parts.
   */
  private Single<Event> createUserMessageEvent(
      Session session,
      Content newMessage,
      InvocationContext invocationContext,
      boolean saveInputBlobsAsArtifacts,
      @Nullable Map<String, Object> stateDelta) {
    // As on the resumable path, a function response takes the branch of the call it answers.
    return appendNewMessageToSession(
        session,
        newMessage,
        invocationContext,
        saveInputBlobsAsArtifacts,
        stateDelta,
        matchingFunctionCallEvent(session, newMessage).flatMap(Event::branch).orElse(null));
  }

  private Single<Event> appendNewMessageToSession(
      Session session,
      Content newMessage,
      InvocationContext invocationContext,
      boolean saveInputBlobsAsArtifacts,
      @Nullable Map<String, Object> stateDelta,
      @Nullable String branch) {
    checkArgument(newMessage.parts().isPresent(), "No parts in the new_message.");

    Content messageToAppend = newMessage;
    Completable saveArtifactsFlow = Completable.complete();
    if (this.artifactService != null && saveInputBlobsAsArtifacts) {
      // The runner directly saves the artifacts (if applicable) in the user message and replaces
      // the artifact data with a file name placeholder. The rewrite happens on a copy of the parts
      // list: the caller's list may be immutable, and the caller does not expect the message it
      // passed to runAsync to be modified.
      List<Part> parts = new ArrayList<>(newMessage.parts().get());
      for (int i = 0; i < parts.size(); i++) {
        Part part = parts.get(i);
        if (part.inlineData().isEmpty()) {
          continue;
        }
        String fileName = "artifact_" + invocationContext.invocationId() + "_" + i;
        saveArtifactsFlow =
            saveArtifactsFlow.andThen(
                this.artifactService
                    .saveArtifact(this.appName, session.userId(), session.id(), fileName, part)
                    .ignoreElement());

        parts.set(
            i,
            Part.fromText("Uploaded file: " + fileName + ". It has been saved to the artifacts"));
      }
      messageToAppend = newMessage.toBuilder().parts(ImmutableList.copyOf(parts)).build();
    }
    Event.Builder eventBuilder =
        Event.builder()
            .id(invocationContext.newUuid())
            .timestamp(invocationContext.now().toEpochMilli())
            .invocationId(invocationContext.invocationId())
            .author(Role.USER)
            .branch(branch)
            .content(messageToAppend);

    // Add state delta if provided
    if (stateDelta != null && !stateDelta.isEmpty()) {
      eventBuilder.actions(
          EventActions.builder().stateDelta(new ConcurrentHashMap<>(stateDelta)).build());
    }
    return saveArtifactsFlow.andThen(Single.just(eventBuilder.build()));
  }

  /** See {@link #runAsync(String, String, Content, RunConfig, Map)}. */
  public Flowable<Event> runAsync(
      String userId, String sessionId, Content newMessage, RunConfig runConfig) {
    return runAsync(userId, sessionId, newMessage, runConfig, /* stateDelta= */ null);
  }

  /**
   * Runs the agent with an invocation-based mode.
   *
   * <p>TODO: make this the main implementation.
   *
   * @param userId The ID of the user for the session.
   * @param sessionId The ID of the session to run the agent in.
   * @param newMessage The new message from the user to process.
   * @param runConfig Configuration for the agent run.
   * @param stateDelta Optional map of state updates to merge into the session for this run.
   * @return A Flowable stream of {@link Event} objects generated by the agent during execution.
   */
  public Flowable<Event> runAsync(
      String userId,
      String sessionId,
      Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    Flowable<Event> result =
        Flowable.defer(
                () ->
                    this.sessionService
                        .getSession(appName, userId, sessionId, Optional.empty())
                        .switchIfEmpty(
                            Single.defer(
                                () -> {
                                  if (runConfig.autoCreateSession()) {
                                    return this.sessionService.createSession(
                                        appName, userId, (Map<String, Object>) null, sessionId);
                                  }
                                  return Single.error(
                                      new IllegalArgumentException(
                                          String.format(
                                              "Session not found: %s for user %s",
                                              sessionId, userId)));
                                }))
                        .flatMapPublisher(
                            session ->
                                this.runAsyncImpl(session, newMessage, runConfig, stateDelta)))
            .compose(Tracing.trace("invocation"));

    return serializePerSession(sessionId, result);
  }

  /** See {@link #runAsync(String, String, Content, RunConfig, Map)}. */
  public Flowable<Event> runAsync(
      SessionKey sessionKey,
      Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    return runAsync(sessionKey.userId(), sessionKey.id(), newMessage, runConfig, stateDelta);
  }

  /** See {@link #runAsync(String, String, Content, RunConfig, Map)}. */
  public Flowable<Event> runAsync(SessionKey sessionKey, Content newMessage, RunConfig runConfig) {
    return runAsync(sessionKey, newMessage, runConfig, /* stateDelta= */ null);
  }

  /** See {@link #runAsync(String, String, Content, RunConfig, Map)}. */
  public Flowable<Event> runAsync(SessionKey sessionKey, Content newMessage) {
    return runAsync(sessionKey, newMessage, RunConfig.builder().build());
  }

  /** See {@link #runAsync(String, String, Content, RunConfig, Map)}. */
  public Flowable<Event> runAsync(String userId, String sessionId, Content newMessage) {
    return runAsync(userId, sessionId, newMessage, RunConfig.builder().build());
  }

  /**
   * Runs the agent, resuming an existing invocation instead of starting a new one. The invocation
   * is resolved from {@code invocationId}, or from a function response carried by {@code
   * newMessage}. Agent checkpoints are rehydrated from history and an invocation whose active agent
   * already finished resolves to a no-op, while a workflow node root rebuilds its progress from the
   * invocation's events even when the app is not resumable.
   *
   * @param userId the user id of the session.
   * @param sessionId the session id.
   * @param invocationId the invocation to resume; may be {@code null} when it can be inferred from
   *     {@code newMessage}.
   * @param newMessage an optional message (typically a function response) to append before running.
   * @param runConfig the run configuration.
   * @param stateDelta optional state updates to merge into the session for this run.
   * @return the events generated while resuming, or an empty stream when there is nothing to
   *     resume.
   * @throws IllegalStateException if the app is not resumable and its root is an agent.
   * @throws IllegalArgumentException if the invocation cannot be resolved.
   */
  @Experimental
  public Flowable<Event> runAsync(
      String userId,
      String sessionId,
      @Nullable String invocationId,
      @Nullable Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    checkState(
        isResumable() || node != null,
        "Resuming an invocation requires an App configured with a resumable ResumabilityConfig.");
    Flowable<Event> result =
        Flowable.defer(
                () ->
                    this.sessionService
                        .getSession(appName, userId, sessionId, Optional.empty())
                        .switchIfEmpty(
                            Single.defer(
                                () -> {
                                  // Same get-or-create as a new run, as in Python.
                                  if (runConfig.autoCreateSession()) {
                                    return this.sessionService.createSession(
                                        appName, userId, (Map<String, Object>) null, sessionId);
                                  }
                                  return Single.error(
                                      new IllegalArgumentException(
                                          String.format(
                                              "Session not found: %s for user %s",
                                              sessionId, userId)));
                                }))
                        .flatMapPublisher(
                            session ->
                                runResumableFromSession(
                                    session, invocationId, newMessage, runConfig, stateDelta)))
            .compose(Tracing.trace("invocation"));

    // Resuming reads session events around an append, so it shares the new run's per-session turn.
    return serializePerSession(sessionId, result);
  }

  /** Returns {@code content} with the user role applied when it carries none, as Python does. */
  private static @Nullable Content withUserRole(@Nullable Content content) {
    if (content == null || content.role().filter(role -> !role.isEmpty()).isPresent()) {
      return content;
    }
    return content.toBuilder().role(Role.USER).build();
  }

  /**
   * Chains {@code result} behind any run already in flight for {@code sessionId}, so two runs never
   * read and append the session's events concurrently. A null session id runs unsequenced.
   */
  private Flowable<Event> serializePerSession(@Nullable String sessionId, Flowable<Event> result) {
    return Flowable.defer(
        () -> {
          if (sessionId == null) {
            return result;
          }

          CompletableSubject requestCompletion = CompletableSubject.create();

          Completable[] previousHolder = new Completable[1];

          activeSessionCompletables.compute(
              sessionId,
              (key, current) -> {
                previousHolder[0] = current;
                return requestCompletion;
              });

          Completable previous = previousHolder[0];

          Flowable<Event> sequenced =
              (previous == null) ? result : previous.onErrorComplete().andThen(result);

          return sequenced.doFinally(
              () -> {
                requestCompletion.onComplete();
                activeSessionCompletables.remove(sessionId, requestCompletion);
              });
        });
  }

  /**
   * Runs the agent asynchronously using a provided Session object.
   *
   * @param session The session to run the agent in.
   * @param newMessage The new message from the user to process.
   * @param runConfig Configuration for the agent run.
   * @param stateDelta Optional map of state updates to merge into the session for this run.
   * @return A Flowable stream of {@link Event} objects generated by the agent during execution.
   */
  protected Flowable<Event> runAsyncImpl(
      Session session,
      Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    Preconditions.checkNotNull(session, "session cannot be null");
    Preconditions.checkNotNull(newMessage, "newMessage cannot be null");
    Preconditions.checkNotNull(runConfig, "runConfig cannot be null");
    // A node root rebuilds its progress from its invocation's events, so it resumes on any app.
    if (isResumable() || node != null) {
      return runResumableFromSession(
          session, /* providedInvocationId= */ null, newMessage, runConfig, stateDelta);
    }
    return runNewInvocation(session, newMessage, runConfig, stateDelta);
  }

  /**
   * Starts an invocation for {@code newMessage}; a node root joins the one a plugin's answers
   * resolve to.
   */
  private Flowable<Event> runNewInvocation(
      Session session,
      Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    return Flowable.defer(
            () -> {
              Context capturedContext = Context.current();
              BaseAgent rootAgent = this.agent;
              String invocationId = InvocationContext.newInvocationContextId(this.uuidProvider);

              // Eagerly merge stateDelta into session state so onUserMessageCallback() can see it.
              // Safe: this in-memory update is ahead of time; canonical persistence still happens
              // when userEvent is appended in runAgentForUserEvent().
              if (stateDelta != null && !stateDelta.isEmpty()) {
                stateDelta.forEach((key, value) -> session.state().put(key, value));
              }

              // Create initial context
              InvocationContext initialContext =
                  newInvocationContextBuilder(session)
                      .invocationId(invocationId)
                      .runConfig(runConfig)
                      .userContent(newMessage)
                      .build();

              return this.pluginManager
                  .onUserMessageCallback(initialContext, newMessage)
                  .compose(Tracing.<Content>withContext(capturedContext))
                  .defaultIfEmpty(newMessage)
                  .flatMapPublisher(
                      content -> {
                        InvocationContext context =
                            resolveNodeRootContext(session, initialContext, content);
                        return createUserMessageEvent(
                                session,
                                content,
                                context,
                                runConfig.saveInputBlobsAsArtifacts(),
                                stateDelta)
                            .flatMapPublisher(
                                userEvent ->
                                    runAgentForUserEvent(context, session, userEvent, rootAgent)
                                        .compose(Tracing.<Event>withContext(capturedContext)));
                      })
                  .doOnError(throwable -> runOnRunError(initialContext, throwable));
            })
        .doOnError(
            throwable -> {
              Span span = Span.current();
              span.setStatus(StatusCode.ERROR, "Error in runAsync Flowable execution");
              span.recordException(throwable);
            });
  }

  /**
   * Persists the user message event to the session and executes the agent.
   *
   * <p>The initial {@code userEvent} is persisted to {@code session} to update session history and
   * state, and is used to override the {@code userContent} in the {@link InvocationContext}. The
   * returned {@link Flowable} yields <b>only</b> model/agent events (or the event produced by
   * {@code beforeRunCallback}); the user event itself is not emitted into the output stream.
   *
   * @param initialContext the context from the start of the invocation, used to preserve metadata
   *     and callback data.
   * @param session the session to append the user message to and execute within.
   * @param userEvent the unpersisted user message event.
   * @param rootAgent the agent to be executed.
   * @return a stream of events generated during execution by the model/agent and plugins.
   */
  private Flowable<Event> runAgentForUserEvent(
      InvocationContext initialContext, Session session, Event userEvent, BaseAgent rootAgent) {
    return this.sessionService
        .appendEvent(session, userEvent)
        .flatMapPublisher(
            unusedPersistedEvent ->
                runAgentWithUpdatedSession(initialContext, session, userEvent, rootAgent));
  }

  /**
   * Runs the agent with the updated session state.
   *
   * <p>This method is called after the user message has been persistent in the session. It creates
   * a final {@link InvocationContext} that inherits state from the {@code initialContext} but uses
   * the {@code updatedSession} to ensure the agent can access the latest conversation history.
   *
   * @param initialContext the context from the start of the invocation, used to preserve metadata
   *     and callback data.
   * @param updatedSession the session object containing the latest message.
   * @param event the event representing the user message that was just appended.
   * @param rootAgent the agent to be executed.
   * @return a stream of events from the agent execution and subsequent plugin callbacks.
   */
  private Flowable<Event> runAgentWithUpdatedSession(
      InvocationContext initialContext, Session updatedSession, Event event, BaseAgent rootAgent) {
    BaseAgent agentToRun = this.findAgentToRun(updatedSession, rootAgent);
    // Create context with updated session for beforeRunCallback
    InvocationContext contextWithUpdatedSession =
        initialContext.toBuilder()
            .session(updatedSession)
            .agent(agentToRun)
            .userContent(
                userContentFor(
                    updatedSession,
                    initialContext.invocationId(),
                    event.content().orElseGet(Content::fromParts)))
            .branch(routedAgentParentBranch(updatedSession, agentToRun, rootAgent))
            .build();

    // If beforeRunCallback returns content, emit it and skip agent.
    Maybe<Event> beforeRunEvent = beforeRunEventFor(contextWithUpdatedSession);
    Context capturedContext = Context.current();
    return executeAgentPipeline(
            contextWithUpdatedSession,
            updatedSession,
            beforeRunEvent,
            // TODO: remove this hack after deprecating runAsync with Session.
            () -> copySessionStates(updatedSession, initialContext.session()))
        .compose(Tracing.withContext(capturedContext));
  }

  /**
   * Runs the root of {@code context} and drives the persist / plugin-callback pipeline shared by
   * both invocation paths, through the after-run and compaction brackets. A {@code beforeRunEvent}
   * short-circuits the run; {@code onEachPersisted} runs after each persisted event.
   */
  private Flowable<Event> executeAgentPipeline(
      InvocationContext context,
      Session sessionToPersist,
      Maybe<Event> beforeRunEvent,
      @Nullable Runnable onEachPersisted) {
    // Let BaseLlmFlow block each step until this Runner has persisted the prior step's events.
    PersistBarrier.enable(context);

    return beforeRunEvent
        .toFlowable()
        .switchIfEmpty(
            runRoot(
                context, event -> processEvent(context, sessionToPersist, onEachPersisted, event)))
        .concatWith(Completable.defer(() -> pluginManager.afterRunCallback(context)))
        .concatWith(Completable.defer(() -> compactEvents(sessionToPersist)));
  }

  /**
   * Persists {@code event} unless it is partial, runs {@code onEachPersisted} and the plugins'
   * event callbacks, and returns the event to emit.
   */
  private Single<Event> processEvent(
      InvocationContext context,
      Session sessionToPersist,
      @Nullable Runnable onEachPersisted,
      Event event) {
    // Mirror ADK Python (runners.py): partial events are streamed to the caller but
    // never persisted, so managed session services (e.g. VertexAiSessionService) do
    // not store a duplicate of the function call/text that the final aggregated event
    // already carries. Nothing to persist, so resolve the barrier immediately.
    Single<Event> persistStep =
        event.partial().orElse(false)
            ? Single.just(event)
            : this.sessionService.appendEvent(sessionToPersist, event);
    return persistStep
        // Release (or fail) BaseLlmFlow's wait for this step; the Runner stays the
        // sole appendEvent caller (see PersistBarrier).
        .doOnSuccess(unusedEvent -> PersistBarrier.markPersisted(context, event.id()))
        .doOnError(error -> PersistBarrier.markFailed(context, event.id(), error))
        .flatMap(
            registeredEvent -> {
              if (onEachPersisted != null) {
                onEachPersisted.run();
              }
              return context
                  .pluginManager()
                  .onEventCallback(context, registeredEvent)
                  .defaultIfEmpty(registeredEvent);
            });
  }

  /** Runs an agent root through its own lifecycle, and any other node root through NodeRunner. */
  private static Flowable<Event> runRoot(
      InvocationContext context, Function<Event, Single<Event>> processEvent) {
    Node root = context.node().orElse(context.agent());
    if (root instanceof BaseAgent agent) {
      return agent.runAsync(context).concatMapSingle(processEvent::apply);
    }
    return NodeRunner.runRoot(root, context, processEvent);
  }

  private Completable compactEvents(Session session) {
    return Optional.ofNullable(eventsCompactionConfig)
        .filter(EventsCompactionConfig::hasSlidingWindowCompactionConfig)
        .map(SlidingWindowEventCompactor::new)
        .map(c -> c.compact(session, sessionService))
        .orElseGet(Completable::complete);
  }

  /** Wraps before-run callback content as a model event that short-circuits the agent run. */
  private Maybe<Event> beforeRunEventFor(InvocationContext context) {
    return this.pluginManager
        .beforeRunCallback(context)
        .map(
            content ->
                Event.builder()
                    .id(context.newUuid())
                    .timestamp(context.now().toEpochMilli())
                    .invocationId(context.invocationId())
                    .author("model")
                    .content(content)
                    .build());
  }

  /** Fires the on-run-error plugin callback; the run paths share this error handler. */
  private void runOnRunError(InvocationContext context, Throwable throwable) {
    this.pluginManager.runOnRunErrorCallback(context, throwable).onErrorComplete().subscribe();
  }

  /**
   * Resumes an existing invocation when one resolves from {@code providedInvocationId} or a
   * function response in {@code rawMessage}; otherwise starts a new invocation. Requires
   * resumability or a workflow node root.
   */
  private Flowable<Event> runResumableFromSession(
      Session session,
      @Nullable String providedInvocationId,
      @Nullable Content rawMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    // As in Python, a role-less message is a user turn; otherwise the request drops it.
    Content newMessage = withUserRole(rawMessage);
    return Flowable.defer(
        () -> {
          // Rejected before the invocation-id fallback; defer turns the throw into an error signal.
          if (node != null) {
            checkNoTextWithFunctionResponses(newMessage);
          }
          if (newMessage != null) {
            validateResumeMessage(
                session,
                newMessage,
                /* requireFirstId= */ node == null || providedInvocationId != null);
          }
          String resolvedInvocationId =
              resolveInvocationId(session, newMessage, providedInvocationId);
          if (resolvedInvocationId == null) {
            if (newMessage == null) {
              return Flowable.<Event>error(
                  new IllegalArgumentException(
                      "No new message provided and no resumable invocation to resume."));
            }
            return runNewInvocation(session, newMessage, runConfig, stateDelta);
          }
          if (session.immutableEvents().isEmpty()) {
            // Only a wholly empty session is rejected; Python does not reject an unmatched id.
            return Flowable.<Event>error(
                new IllegalArgumentException("Session has no events to resume."));
          }
          return resumeCore(session, resolvedInvocationId, newMessage, runConfig, stateDelta);
        });
  }

  /**
   * Runs an existing invocation on the given session: optionally appends {@code newMessage},
   * rehydrates agent checkpoints, skips a completed invocation, and runs the resolved agent under
   * the resumed invocation id.
   */
  private Flowable<Event> resumeCore(
      Session session,
      String resolvedInvocationId,
      @Nullable Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    return Flowable.defer(
        () -> {
          Context capturedContext = Context.current();

          // Not new input: Python drops newMessage, and outside the node path stateDelta too.
          boolean supersededByOriginalMessage =
              newMessage != null
                  && !carriesFunctionResponse(newMessage)
                  && findOriginalUserMessage(session, resolvedInvocationId).isPresent();
          Content message = supersededByOriginalMessage ? null : newMessage;
          Map<String, Object> delta =
              supersededByOriginalMessage && node == null ? null : stateDelta;

          // Merged eagerly so the on-user-message callback sees it.
          if (delta != null && !delta.isEmpty()) {
            delta.forEach((key, value) -> session.state().put(key, value));
          }

          // For the pre-run plugin callbacks; the agent is resolved later, in runResumedAgent.
          InvocationContext initialContext =
              newInvocationContextBuilder(session)
                  .invocationId(resolvedInvocationId)
                  .runConfig(runConfig)
                  .userContent(userContentFor(session, resolvedInvocationId, newMessage))
                  .build();

          Flowable<Event> events;
          if (message != null) {
            // Inherit the branch of the call it answers, so routing and rehydration see it.
            String branch =
                matchingFunctionCallEvent(session, message).flatMap(Event::branch).orElse(null);
            events =
                this.pluginManager
                    .onUserMessageCallback(initialContext, message)
                    .compose(Tracing.<Content>withContext(capturedContext))
                    .defaultIfEmpty(message)
                    .flatMap(
                        content -> {
                          // A plugin's message must not mix answers and text either.
                          if (node != null) {
                            checkNoTextWithFunctionResponses(content);
                          }
                          return appendNewMessageToSession(
                              session,
                              content,
                              initialContext,
                              runConfig.saveInputBlobsAsArtifacts(),
                              delta,
                              branch);
                        })
                    // Persist first: an unpersisted response leaves the call looking unanswered.
                    .flatMap(userEvent -> this.sessionService.appendEvent(session, userEvent))
                    .flatMapPublisher(
                        userEvent ->
                            runResumedAgent(
                                session,
                                resolvedInvocationId,
                                userEvent.content().orElse(null),
                                runConfig));
          } else if (delta != null && !delta.isEmpty()) {
            // No message to carry the delta, so persist a content-less event, as Python does.
            Event stateDeltaEvent =
                Event.builder()
                    .id(initialContext.newUuid())
                    .timestamp(initialContext.now().toEpochMilli())
                    .invocationId(resolvedInvocationId)
                    .author(Role.USER)
                    .actions(
                        EventActions.builder().stateDelta(new ConcurrentHashMap<>(delta)).build())
                    .build();
            events =
                this.sessionService
                    .appendEvent(session, stateDeltaEvent)
                    .ignoreElement()
                    .andThen(
                        runResumedAgent(
                            session, resolvedInvocationId, /* userContent= */ null, runConfig));
          } else {
            events =
                runResumedAgent(session, resolvedInvocationId, /* userContent= */ null, runConfig);
          }

          return events
              .doOnError(throwable -> runOnRunError(initialContext, throwable))
              .compose(Tracing.<Event>withContext(capturedContext));
        });
  }

  /**
   * Runs the resolved agent for a resumed invocation with the same before-run / after-run plugin
   * bracket as the new-invocation path. Rehydrates checkpoints, skips a completed invocation, and
   * persists each event.
   */
  private Flowable<Event> runResumedAgent(
      Session session,
      String resolvedInvocationId,
      @Nullable Content userContent,
      RunConfig runConfig) {
    return Flowable.defer(
        () -> {
          BaseAgent rootAgent = this.agent;
          InvocationContext rootContext =
              newInvocationContextBuilder(session, rootAgent)
                  .invocationId(resolvedInvocationId)
                  .runConfig(runConfig)
                  .userContent(userContentFor(session, resolvedInvocationId, userContent))
                  .build();
          // Rehydrate before routing, as Python does, so a root that checkpointed re-enters itself.
          rootContext.populateInvocationAgentStates();
          BaseAgent resumeAgent =
              rootContext.endOfAgents().containsKey(rootAgent.name())
                  ? rootAgent
                  : findAgentToRun(session, rootAgent);
          InvocationContext context =
              resumeAgent.equals(rootAgent)
                  ? rootContext
                  : rootContext.toBuilder()
                      .agent(resumeAgent)
                      .branch(resumeParentBranch(session, resolvedInvocationId, resumeAgent))
                      .build();

          // No-op guard: a completed invocation (its active agent already finished) is not re-run.
          if (context.endOfAgents().getOrDefault(context.agent().name(), false)) {
            return Flowable.<Event>empty();
          }

          // before_run may short-circuit the run with a model event, as on the new-invocation path.
          Maybe<Event> beforeRunEvent = beforeRunEventFor(context);

          return executeAgentPipeline(
              context, session, beforeRunEvent, /* onEachPersisted= */ null);
        });
  }

  /**
   * Branch to seed a new invocation with so a routed sub-agent runs where it ran before, or {@code
   * null} when {@code agentToRun} is the root. A function response restores the branch of the call
   * it answers, since a Java agent's branch depends on the transfer path that reached it; other
   * routing restores the agent's latest branch, as Python does.
   */
  private static @Nullable String routedAgentParentBranch(
      Session session, BaseAgent agentToRun, BaseAgent rootAgent) {
    if (agentToRun.equals(rootAgent)) {
      return null;
    }
    Optional<Event> answeredCall =
        Functions.findMatchingFunctionCallEvent(session.immutableEvents());
    if (answeredCall.isPresent()) {
      String ownSuffix = sequentialBranchSuffix(agentToRun, answeredCall.get().author());
      if (ownSuffix != null) {
        return parentBranch(answeredCall.get().branch().orElse(null), ownSuffix);
      }
    }
    return resumeParentBranch(session, /* invocationId= */ null, agentToRun);
  }

  /**
   * Branch to seed a context with so {@code resumeAgent} runs under the branch it last ran on: the
   * parent branch of the newest event by {@code resumeAgent}, or by an agent it reaches through
   * SequentialAgents, with a non-empty branch. A non-null {@code invocationId} limits the search to
   * that invocation; {@code null} is returned for the root branch.
   */
  private static @Nullable String resumeParentBranch(
      Session session, @Nullable String invocationId, BaseAgent resumeAgent) {
    ImmutableList<Event> events = session.immutableEvents();
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if ((invocationId == null || invocationId.equals(event.invocationId()))
          && event.branch().filter(branch -> !branch.isEmpty()).isPresent()) {
        String ownSuffix = sequentialBranchSuffix(resumeAgent, event.author());
        if (ownSuffix != null) {
          return parentBranch(event.branch().get(), ownSuffix);
        }
      }
    }
    return null;
  }

  /**
   * Returns {@code branch} minus the trailing {@code ownSuffix} that {@link BaseAgent#runAsync}
   * re-appends, or {@code null} for the root branch.
   */
  private static @Nullable String parentBranch(@Nullable String branch, String ownSuffix) {
    if (branch == null || branch.isEmpty() || branch.equals(ownSuffix)) {
      return null;
    }
    if (branch.endsWith("." + ownSuffix)) {
      String parent = branch.substring(0, branch.length() - ownSuffix.length() - 1);
      return parent.isEmpty() ? null : parent;
    }
    return branch;
  }

  /**
   * Returns the dot-joined names from {@code agent} down to the agent named {@code author} when
   * that agent is {@code agent} or below it through SequentialAgents, else {@code null}. The legacy
   * flow resumes such an ancestor, which records no events of its own.
   */
  private static @Nullable String sequentialBranchSuffix(BaseAgent agent, @Nullable String author) {
    if (agent.name().equals(author)) {
      return agent.name();
    }
    if (agent instanceof SequentialAgent) {
      for (BaseAgent subAgent : agent.subAgents()) {
        String suffix = sequentialBranchSuffix(subAgent, author);
        if (suffix != null) {
          return agent.name() + "." + suffix;
        }
      }
    }
    return null;
  }

  /**
   * Resolves which invocation a request targets: the invocation that issued the function call
   * matching {@code newMessage}'s function response, else the caller-supplied {@code invocationId}.
   * Returns {@code null} when neither applies (a fresh message starts a new invocation).
   */
  private static @Nullable String resolveInvocationId(
      Session session, @Nullable Content newMessage, @Nullable String invocationId) {
    if (newMessage != null) {
      return matchingFunctionCallEvent(session, newMessage)
          .map(Event::invocationId)
          .orElse(invocationId);
    }
    return invocationId;
  }

  /**
   * Returns the session event whose function call matches a function response id carried by {@code
   * newMessage}, searching newest-first. Both the resumed invocation id and the branch of the
   * appended function-response event are derived from it.
   */
  private static Optional<Event> matchingFunctionCallEvent(Session session, Content newMessage) {
    Set<String> responseIds = functionResponseIds(newMessage);
    if (responseIds.isEmpty()) {
      return Optional.empty();
    }
    ImmutableList<Event> events = session.immutableEvents();
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      for (FunctionCall call : event.functionCalls()) {
        if (call.id().filter(responseIds::contains).isPresent()) {
          return Optional.of(event);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Rejects a message for a node root if the message mixes text with function responses that carry
   * an id: the responses resume an existing invocation, while the text starts a new one. Python's
   * node path rejects such a message too; its agent path accepts it.
   *
   * @throws IllegalArgumentException if {@code message} mixes them.
   */
  private static void checkNoTextWithFunctionResponses(@Nullable Content message) {
    if (message == null || functionResponseIds(message).stream().allMatch(String::isEmpty)) {
      return;
    }
    boolean hasText =
        message.parts().stream()
            .flatMap(List::stream)
            .map(Part::text)
            .flatMap(Optional::stream)
            .anyMatch(text -> !text.isEmpty());
    checkArgument(
        !hasText,
        "Message cannot contain both function responses and text. Function responses resume an"
            + " existing invocation while text starts a new one.");
  }

  /**
   * Returns {@code context} moved to the invocation whose calls {@code message} answers when the
   * root is a workflow node, or unchanged otherwise. A plugin may replace the user's message with
   * {@code message}, so it is checked again.
   *
   * @throws IllegalArgumentException if {@code message} mixes answers and text, or its answers do
   *     not resolve to one invocation.
   */
  private InvocationContext resolveNodeRootContext(
      Session session, InvocationContext context, Content message) {
    if (node == null) {
      return context;
    }
    checkNoTextWithFunctionResponses(message);
    validateResumeMessage(session, message, /* requireFirstId= */ false);
    String answeredInvocationId = resolveInvocationId(session, message, /* invocationId= */ null);
    return answeredInvocationId == null
        ? context
        : context.toBuilder().invocationId(answeredInvocationId).build();
  }

  /**
   * Rejects a resume message no invocation can be resumed from: one whose first function response
   * carries no id when {@code requireFirstId} is set, one whose response ids match no call in
   * session history (it would feed the model an orphan response), and one whose responses span more
   * than one invocation. Counts rather than ids are reported, since the ids come from the caller.
   * Text alongside a function response is accepted, as on Python's agent path.
   *
   * @throws IllegalArgumentException if {@code newMessage} is not resumable.
   */
  private static void validateResumeMessage(
      Session session, Content newMessage, boolean requireFirstId) {
    ImmutableList<FunctionResponse> responses =
        newMessage.parts().stream()
            .flatMap(List::stream)
            .map(Part::functionResponse)
            .flatMap(Optional::stream)
            .collect(toImmutableList());
    if (responses.isEmpty()) {
      return;
    }
    // Python checks the first response only, and raises rather than opening a new invocation that
    // would carry an orphan response.
    checkArgument(
        !requireFirstId || responses.get(0).id().isPresent(),
        "A function response id is required to resume an invocation.");
    Set<String> unmatched = functionResponseIds(newMessage);
    Set<String> invocationIds = new HashSet<>();
    ImmutableList<Event> events = session.immutableEvents();
    for (int i = events.size() - 1; i >= 0 && !unmatched.isEmpty(); i--) {
      Event event = events.get(i);
      for (FunctionCall call : event.functionCalls()) {
        if (call.id().filter(unmatched::remove).isPresent()) {
          invocationIds.add(event.invocationId());
        }
      }
    }
    checkArgument(
        unmatched.isEmpty(),
        "No matching function call for %s of the resume message's function responses.",
        unmatched.size());
    checkArgument(
        invocationIds.size() <= 1,
        "The resume message's function responses span %s invocations; all of them must answer the"
            + " same one.",
        invocationIds.size());
  }

  private static Set<String> functionResponseIds(Content message) {
    return message.parts().stream()
        .flatMap(List::stream)
        .map(Part::functionResponse)
        .flatMap(Optional::stream)
        .map(FunctionResponse::id)
        .flatMap(Optional::stream)
        .collect(toCollection(HashSet::new));
  }

  /**
   * Returns the user message that started {@code invocationId}, so a resume that carries no message
   * of its own still shows plugins, callbacks and the model the original prompt instead of empty
   * content. A function-response message does not qualify: it answers an invocation rather than
   * starting one.
   *
   * @throws IllegalArgumentException if the invocation has no such message, as in Python ADK.
   */
  private static Content originalUserMessage(Session session, String invocationId) {
    return findOriginalUserMessage(session, invocationId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No user message to resume the requested invocation from."));
  }

  /**
   * Returns the user content for a run of {@code invocationId}: {@code message}, or the message
   * that started the invocation when there is none. As in Python, a workflow node root also gets
   * that message when {@code message} answers calls.
   *
   * @throws IllegalArgumentException if there is no message and the invocation has no user message
   */
  private Content userContentFor(Session session, String invocationId, @Nullable Content message) {
    if (message == null) {
      return originalUserMessage(session, invocationId);
    }
    if (node != null && carriesFunctionResponse(message)) {
      return findOriginalUserMessage(session, invocationId).orElse(message);
    }
    return message;
  }

  private static boolean carriesFunctionResponse(Content message) {
    return message.parts().stream()
        .flatMap(List::stream)
        .anyMatch(part -> part.functionResponse().isPresent());
  }

  /** The user message that started {@code invocationId}, or empty when it has none. */
  private static Optional<Content> findOriginalUserMessage(Session session, String invocationId) {
    return session.immutableEvents().stream()
        .filter(event -> invocationId.equals(event.invocationId()))
        .filter(event -> Objects.equals(event.author(), Role.USER))
        .map(event -> event.content().orElse(null))
        .filter(Objects::nonNull)
        .filter(content -> content.parts().stream().flatMap(List::stream).findAny().isPresent())
        .filter(
            content ->
                content.parts().stream()
                    .flatMap(List::stream)
                    .noneMatch(part -> part.functionResponse().isPresent()))
        .findFirst();
  }

  private void copySessionStates(Session source, Session target) {
    // TODO: remove this hack when deprecating all runAsync with Session.
    target.state().putAll(source.state());
  }

  /**
   * Creates an {@link InvocationContext} for a live (streaming) run.
   *
   * @return invocation context configured for a live run.
   */
  private InvocationContext newInvocationContextForLive(
      Session session, @Nullable LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    RunConfig.Builder runConfigBuilder = RunConfig.builder(runConfig);
    if (liveRequestQueue != null) {
      // Default to AUDIO modality if not specified.
      if (CollectionUtils.isNullOrEmpty(runConfig.responseModalities())) {
        runConfigBuilder.responseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)));
        if (runConfig.outputAudioTranscription() == null) {
          runConfigBuilder.outputAudioTranscription(AudioTranscriptionConfig.builder().build());
        }
      } else if (!runConfig.responseModalities().contains(new Modality(Modality.Known.TEXT))) {
        if (runConfig.outputAudioTranscription() == null) {
          runConfigBuilder.outputAudioTranscription(AudioTranscriptionConfig.builder().build());
        }
      }
      // Need input transcription for agent transferring in live mode.
      if (runConfig.inputAudioTranscription() == null) {
        runConfigBuilder.inputAudioTranscription(AudioTranscriptionConfig.builder().build());
      }
    }
    BaseAgent agentToRun = findAgentToRun(session, this.agent);
    InvocationContext.Builder builder =
        newInvocationContextBuilder(session, agentToRun)
            .runConfig(runConfigBuilder.build())
            .userContent(Content.fromParts())
            .liveRequestQueue(liveRequestQueue)
            .branch(routedAgentParentBranch(session, agentToRun, this.agent));

    return builder.build();
  }

  private InvocationContext.Builder newInvocationContextBuilder(Session session) {
    return newInvocationContextBuilder(session, this.findAgentToRun(session, this.agent));
  }

  /** Overload for callers that already resolved the agent, so the walk is not repeated. */
  private InvocationContext.Builder newInvocationContextBuilder(Session session, BaseAgent agent) {
    return InvocationContext.builder()
        .sessionService(this.sessionService)
        .artifactService(this.artifactService)
        .memoryService(this.memoryService)
        .pluginManager(this.pluginManager)
        .session(session)
        .eventsCompactionConfig(this.eventsCompactionConfig)
        .contextCacheConfig(this.contextCacheConfig)
        .resumabilityConfig(this.resumabilityConfig)
        .scheduler(this.scheduler)
        .instantSource(this.instantSource)
        .uuidProvider(this.uuidProvider)
        .agent(agent)
        .node(node);
  }

  public Flowable<Event> runLive(
      Session session, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    return runLiveImpl(session, liveRequestQueue, runConfig).compose(Tracing.trace("invocation"));
  }

  /**
   * Retrieves the session and runs the agent in live mode.
   *
   * @return stream of events from the agent.
   * @throws IllegalArgumentException if the session is not found.
   */
  public Flowable<Event> runLive(
      String userId, String sessionId, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    return Flowable.defer(
            () ->
                this.sessionService
                    .getSession(appName, userId, sessionId, Optional.empty())
                    .switchIfEmpty(
                        Single.defer(
                            () -> {
                              if (runConfig.autoCreateSession()) {
                                return this.sessionService.createSession(
                                    appName, userId, (Map<String, Object>) null, sessionId);
                              }
                              return Single.error(
                                  new IllegalArgumentException(
                                      String.format(
                                          "Session not found: %s for user %s", sessionId, userId)));
                            }))
                    .flatMapPublisher(
                        session -> this.runLiveImpl(session, liveRequestQueue, runConfig)))
        .compose(Tracing.trace("invocation"));
  }

  /**
   * Retrieves the session and runs the agent in live mode.
   *
   * @return stream of events from the agent.
   * @throws IllegalArgumentException if the session is not found.
   */
  public Flowable<Event> runLive(
      SessionKey sessionKey, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    return runLive(sessionKey.userId(), sessionKey.id(), liveRequestQueue, runConfig);
  }

  /**
   * Runs the agent in live mode, appending generated events to the session.
   *
   * @return stream of events from the agent.
   */
  protected Flowable<Event> runLiveImpl(
      Session session, @Nullable LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    return Flowable.defer(
        () -> {
          if (node != null) {
            return Flowable.<Event>error(
                new UnsupportedOperationException(
                    "Live runs of a workflow node root are not supported."));
          }
          Context capturedContext = Context.current();
          InvocationContext invocationContext =
              newInvocationContextForLive(session, liveRequestQueue, runConfig);

          Single<InvocationContext> invocationContextSingle;
          if (invocationContext.agent() instanceof LlmAgent agent) {
            invocationContextSingle =
                agent
                    .tools()
                    .map(
                        tools -> {
                          this.addActiveStreamingTools(invocationContext, tools);
                          return invocationContext;
                        });
          } else {
            invocationContextSingle = Single.just(invocationContext);
          }
          return invocationContextSingle
              .flatMapPublisher(
                  updatedInvocationContext ->
                      updatedInvocationContext
                          .agent()
                          .runLive(updatedInvocationContext)
                          .concatMapSingle(
                              event -> this.sessionService.appendEvent(session, event)))
              .doOnError(
                  throwable -> {
                    Span span = Span.current();
                    span.setStatus(StatusCode.ERROR, "Error in runLive Flowable execution");
                    span.recordException(throwable);
                    runOnRunError(invocationContext, throwable);
                  })
              .compose(Tracing.<Event>withContext(capturedContext));
        });
  }

  /**
   * Checks if the agent and its parent chain allow transfer up the tree.
   *
   * @return true if transferable, false otherwise.
   */
  private boolean isTransferableAcrossAgentTree(BaseAgent agentToRun) {
    BaseAgent current = agentToRun;
    while (current != null) {
      // Agents eligible to transfer must have an LLM-based agent parent.
      if (!(current instanceof LlmAgent)) {
        return false;
      }
      // If any agent can't transfer to its parent, the chain is broken.
      LlmAgent agent = (LlmAgent) current;
      if (agent.disallowTransferToParent()) {
        return false;
      }
      current = current.parentAgent();
    }
    return true;
  }

  /** Returns whether resumability is enabled for this runner's app. */
  private boolean isResumable() {
    return resumabilityConfig != null && resumabilityConfig.isResumable();
  }

  /**
   * Returns whether this runner's app runs the legacy resumption flow. A separate flow from {@link
   * #isResumable()}, so a caller wanting either has to ask for both.
   */
  @SuppressWarnings("deprecation") // The shim it reads is deprecated by design.
  private boolean isLegacyResumability() {
    return resumabilityConfig != null && resumabilityConfig.isPlainTextContinuationAutoResume();
  }

  /** Returns the agent that should handle the next request based on session history. */
  private BaseAgent findAgentToRun(Session session, BaseAgent rootAgent) {
    if (node != null) {
      // A node root has no agent tree to pick a sub-agent from, so it always runs itself.
      return rootAgent;
    }
    // Route a function response to its call's author, as Python's _agent_router does. An enclosing
    // workflow advances by re-entering the root, which holds its own checkpoint, not from here.
    ImmutableList<Event> sessionEvents = session.immutableEvents();
    Optional<BaseAgent> functionCallAuthor =
        Functions.findMatchingFunctionCallEvent(sessionEvents)
            .filter(event -> event.author() != null)
            .flatMap(event -> rootAgent.findAgent(event.author()));
    if (functionCallAuthor.isPresent()) {
      BaseAgent author = functionCallAuthor.get();
      if (isLegacyResumability()) {
        return topmostSequentialAncestor(author);
      }
      return author;
    }

    ImmutableList<Event> events = sessionEvents.reverse();

    for (Event event : events) {
      String author = event.author();
      if (author == null) {
        continue;
      }
      if (author.equals(Role.USER)) {
        continue;
      }

      // Markers carry no model turn; resumable-only, since endOfAgent shares endInvocation's field.
      if (isResumable()
          && (event.actions().endOfAgent() || event.actions().agentState().isPresent())) {
        continue;
      }

      if (author.equals(rootAgent.name())) {
        return rootAgent;
      }

      Optional<BaseAgent> agent = rootAgent.findSubAgent(author);

      if (agent.isEmpty()) {
        continue;
      }

      if (this.isTransferableAcrossAgentTree(agent.get())) {
        return agent.get();
      }
    }

    return rootAgent;
  }

  /**
   * Returns the top-most ancestor reachable from {@code agent} through {@link SequentialAgent}
   * parents, or {@code agent} itself otherwise. Used by the deprecated legacy flow, whose only
   * resume-aware workflow agent is SequentialAgent.
   */
  private static BaseAgent topmostSequentialAncestor(BaseAgent agent) {
    BaseAgent result = agent;
    BaseAgent parent = agent.parentAgent();
    while (parent instanceof SequentialAgent) {
      result = parent;
      parent = parent.parentAgent();
    }
    return result;
  }

  private void addActiveStreamingTools(InvocationContext invocationContext, List<BaseTool> tools) {
    tools.stream()
        .filter(FunctionTool.class::isInstance)
        .map(FunctionTool.class::cast)
        .filter(this::hasLiveRequestQueueParameter)
        .forEach(
            tool ->
                invocationContext
                    .activeStreamingTools()
                    .put(tool.name(), new ActiveStreamingTool(new LiveRequestQueue())));
  }

  private boolean hasLiveRequestQueueParameter(FunctionTool functionTool) {
    return Arrays.stream(functionTool.func().getParameters())
        .anyMatch(parameter -> parameter.getType().equals(LiveRequestQueue.class));
  }

  @Nullable
  private static EventsCompactionConfig createEventsCompactionConfig(
      BaseAgent agent,
      @Nullable EventsCompactionConfig config,
      InstantSource instantSource,
      UuidProvider uuidProvider) {
    if (config == null || config.summarizer() != null) {
      return config;
    }
    LlmEventSummarizer summarizer =
        Optional.of(agent)
            .filter(LlmAgent.class::isInstance)
            .map(LlmAgent.class::cast)
            .flatMap(LlmAgent::model)
            .flatMap(Model::model)
            .map(
                baseLlm ->
                    new LlmEventSummarizer(
                        baseLlm, /* promptTemplate= */ null, instantSource, uuidProvider))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No BaseLlm model available for event compaction"));
    return new EventsCompactionConfig(
        config.compactionInterval(),
        config.overlapSize(),
        summarizer,
        config.tokenThreshold(),
        config.eventRetentionSize());
  }

  // TODO: run statelessly
}
