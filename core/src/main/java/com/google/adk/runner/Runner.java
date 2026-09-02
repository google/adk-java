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

import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
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
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/** The main class for the GenAI Agents runner. */
@SuppressWarnings("deprecation") // Plumbs the deprecated ResumabilityConfig.
public class Runner {
  private final BaseAgent agent;
  private final String appName;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  @Nullable private final BaseMemoryService memoryService;
  private final PluginManager pluginManager;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;
  @Nullable private final ContextCacheConfig contextCacheConfig;
  private final @Nullable ResumabilityConfig resumabilityConfig;
  private final ConcurrentMap<String, Completable> activeSessionCompletables =
      new MapMaker().weakValues().makeMap();

  /** Builder for {@link Runner}. */
  public static class Builder {
    private App app;
    private BaseAgent agent;
    private String appName;
    private BaseArtifactService artifactService = new InMemoryArtifactService();
    private BaseSessionService sessionService = new InMemorySessionService();
    @Nullable private BaseMemoryService memoryService = null;
    private List<? extends Plugin> plugins = ImmutableList.of();

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

    public Runner build() {
      BaseAgent buildAgent;
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
        buildPlugins = this.app.plugins();
        buildAppName = this.appName == null ? this.app.name() : this.appName;
        buildEventsCompactionConfig = this.app.eventsCompactionConfig();
        buildContextCacheConfig = this.app.contextCacheConfig();
        buildResumabilityConfig = this.app.resumabilityConfig();
      } else {
        buildAgent = this.agent;
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
        throw new IllegalStateException("Session service must be provided.");
      }
      return new Runner(
          buildAgent,
          buildAppName,
          artifactService,
          sessionService,
          memoryService,
          buildPlugins,
          buildEventsCompactionConfig,
          buildContextCacheConfig,
          buildResumabilityConfig);
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
    this.agent = agent;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
    this.pluginManager = new PluginManager(plugins);
    this.eventsCompactionConfig = createEventsCompactionConfig(agent, eventsCompactionConfig);
    this.contextCacheConfig = contextCacheConfig;
    this.resumabilityConfig = resumabilityConfig;
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
    completables.add(this.pluginManager.close());
    return Completable.mergeDelayError(completables);
  }

  /**
   * Appends a new user message to the session history with optional state delta.
   *
   * <p>{@code newMessage} is never modified; when inline blobs are saved as artifacts, the appended
   * event carries a copy in which the blob data is replaced by placeholders.
   *
   * @throws IllegalArgumentException if message has no parts.
   */
  private Single<Event> appendNewMessageToSession(
      Session session,
      Content newMessage,
      InvocationContext invocationContext,
      boolean saveInputBlobsAsArtifacts,
      @Nullable Map<String, Object> stateDelta) {
    return appendNewMessageToSession(
        session,
        newMessage,
        invocationContext,
        saveInputBlobsAsArtifacts,
        stateDelta,
        /* branch= */ null);
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
    // Appends only. We do not yield the event because it's not from the model.
    Event.Builder eventBuilder =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId(invocationContext.invocationId())
            .author("user")
            .branch(branch)
            .content(messageToAppend);

    // Add state delta if provided
    if (stateDelta != null && !stateDelta.isEmpty()) {
      eventBuilder.actions(
          EventActions.builder().stateDelta(new ConcurrentHashMap<>(stateDelta)).build());
    }

    return saveArtifactsFlow.andThen(
        this.sessionService.appendEvent(session, eventBuilder.build()));
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

  /** See {@link #resumeAsync(String, String, String, Content, RunConfig)}. */
  public Flowable<Event> resumeAsync(
      String userId, String sessionId, @Nullable String invocationId) {
    return resumeAsync(
        userId, sessionId, invocationId, /* newMessage= */ null, RunConfig.builder().build());
  }

  /**
   * Resumes a paused, resumable invocation instead of starting a new one: the message is optional
   * and the run continues an existing invocation rather than minting a new id.
   *
   * <p>The invocation to resume is resolved from {@code newMessage} when it carries a function
   * response, else from {@code invocationId}, else from the last event in the session. Agent
   * checkpoints are rehydrated from history, and an invocation whose active agent already finished
   * resolves to a no-op.
   *
   * @param userId the user id of the session.
   * @param sessionId the session id.
   * @param invocationId the invocation to resume; may be {@code null} when it can be inferred.
   * @param newMessage an optional message (typically a function response) to append before running.
   * @param runConfig the run configuration.
   * @return the events generated while resuming, or an empty stream when there is nothing to
   *     resume.
   * @throws IllegalArgumentException if the app is not resumable or the invocation cannot be
   *     resolved.
   */
  public Flowable<Event> resumeAsync(
      String userId,
      String sessionId,
      @Nullable String invocationId,
      @Nullable Content newMessage,
      RunConfig runConfig) {
    checkArgument(
        isResumable(),
        "resumeAsync requires an App configured with a resumable ResumabilityConfig.");
    return Flowable.defer(
            () ->
                this.sessionService
                    .getSession(appName, userId, sessionId, Optional.empty())
                    .switchIfEmpty(
                        Single.error(
                            () ->
                                new IllegalArgumentException(
                                    String.format(
                                        "Session not found: %s for user %s", sessionId, userId))))
                    .flatMapPublisher(
                        session ->
                            runResumableFromSession(
                                session,
                                invocationId,
                                newMessage,
                                runConfig,
                                /* stateDelta= */ null)))
        .compose(Tracing.trace("invocation"));
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
    // When resumable, a message that resolves to an existing invocation (e.g. a function response
    // to a paused call) resumes it; any other message starts a new invocation. Disabled: unchanged.
    if (isResumable()) {
      return runResumableFromSession(
          session, /* providedInvocationId= */ null, newMessage, runConfig, stateDelta);
    }
    return runNewInvocation(session, newMessage, runConfig, stateDelta);
  }

  /** Starts a brand-new invocation for {@code newMessage} (the default, non-resume flow). */
  private Flowable<Event> runNewInvocation(
      Session session,
      Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    return Flowable.defer(
            () -> {
              Context capturedContext = Context.current();
              BaseAgent rootAgent = this.agent;
              String invocationId = InvocationContext.newInvocationContextId();

              // Pre-merge stateDelta so onUserMessageCallback can access it.
              // Safe: session is a copy; persistence still happens via appendNewMessageToSession.
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
                  .flatMap(
                      content ->
                          appendNewMessageToSession(
                              session,
                              content,
                              initialContext,
                              runConfig.saveInputBlobsAsArtifacts(),
                              stateDelta))
                  .flatMapPublisher(
                      event ->
                          runAgentWithUpdatedSession(initialContext, session, event, rootAgent)
                              .compose(Tracing.<Event>withContext(capturedContext)))
                  .doOnError(
                      throwable ->
                          this.pluginManager
                              .runOnRunErrorCallback(initialContext, throwable)
                              .onErrorComplete()
                              .subscribe());
            })
        .doOnError(
            throwable -> {
              Span span = Span.current();
              span.setStatus(StatusCode.ERROR, "Error in runAsync Flowable execution");
              span.recordException(throwable);
            });
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
    // Create context with updated session for beforeRunCallback
    InvocationContext contextWithUpdatedSession =
        initialContext.toBuilder()
            .session(updatedSession)
            .agent(this.findAgentToRun(updatedSession, rootAgent))
            .userContent(event.content().orElseGet(Content::fromParts))
            .build();

    // Call beforeRunCallback with updated session
    Maybe<Event> beforeRunEvent =
        this.pluginManager
            .beforeRunCallback(contextWithUpdatedSession)
            .map(
                content ->
                    Event.builder()
                        .id(Event.generateEventId())
                        .invocationId(contextWithUpdatedSession.invocationId())
                        .author("model")
                        .content(content)
                        .build());

    // Let BaseLlmFlow block each step until this Runner has persisted the prior step's events.
    PersistBarrier.enable(contextWithUpdatedSession);

    // Agent execution
    Flowable<Event> agentEvents =
        contextWithUpdatedSession
            .agent()
            .runAsync(contextWithUpdatedSession)
            .concatMap(
                agentEvent -> {
                  // Mirror ADK Python (runners.py): partial events are streamed to the caller but
                  // never persisted, so managed session services (e.g. VertexAiSessionService) do
                  // not store a duplicate of the function call/text that the final aggregated event
                  // already carries. Nothing to persist, so resolve the barrier immediately.
                  Single<Event> persistStep =
                      agentEvent.partial().orElse(false)
                          ? Single.just(agentEvent)
                          : this.sessionService.appendEvent(updatedSession, agentEvent);
                  return persistStep
                      // Release (or fail) BaseLlmFlow's wait for this step; the Runner stays the
                      // sole appendEvent caller (see PersistBarrier).
                      .doOnSuccess(
                          unusedEvent ->
                              PersistBarrier.markPersisted(
                                  contextWithUpdatedSession, agentEvent.id()))
                      .doOnError(
                          error ->
                              PersistBarrier.markFailed(
                                  contextWithUpdatedSession, agentEvent.id(), error))
                      .flatMap(
                          registeredEvent -> {
                            // TODO: remove this hack after deprecating runAsync with Session.
                            copySessionStates(updatedSession, initialContext.session());
                            return contextWithUpdatedSession
                                .pluginManager()
                                .onEventCallback(contextWithUpdatedSession, registeredEvent)
                                .defaultIfEmpty(registeredEvent);
                          })
                      .toFlowable();
                });

    // If beforeRunCallback returns content, emit it and skip agent
    Context capturedContext = Context.current();
    return beforeRunEvent
        .toFlowable()
        .switchIfEmpty(agentEvents)
        .concatWith(
            Completable.defer(() -> pluginManager.afterRunCallback(contextWithUpdatedSession)))
        .concatWith(Completable.defer(() -> compactEvents(updatedSession)))
        .compose(Tracing.withContext(capturedContext));
  }

  private Completable compactEvents(Session session) {
    return Optional.ofNullable(eventsCompactionConfig)
        .filter(EventsCompactionConfig::hasSlidingWindowCompactionConfig)
        .map(SlidingWindowEventCompactor::new)
        .map(c -> c.compact(session, sessionService))
        .orElseGet(Completable::complete);
  }

  /**
   * Resumes an existing invocation when one resolves from {@code providedInvocationId} or a
   * function response in {@code newMessage}; otherwise starts a new invocation. Requires
   * resumability.
   */
  private Flowable<Event> runResumableFromSession(
      Session session,
      @Nullable String providedInvocationId,
      @Nullable Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    return Flowable.defer(
        () -> {
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
          if (stateDelta != null && !stateDelta.isEmpty()) {
            stateDelta.forEach((key, value) -> session.state().put(key, value));
          }

          // Append the function-response message under the resumed invocation first, inheriting the
          // branch of the call it answers, so routing and rehydration see it.
          Completable appendMessage = Completable.complete();
          if (newMessage != null) {
            InvocationContext appendContext =
                newInvocationContextBuilder(session)
                    .invocationId(resolvedInvocationId)
                    .runConfig(runConfig)
                    .userContent(newMessage)
                    .build();
            String branch =
                matchingFunctionCallEvent(session, newMessage).flatMap(Event::branch).orElse(null);
            appendMessage =
                appendNewMessageToSession(
                        session,
                        newMessage,
                        appendContext,
                        runConfig.saveInputBlobsAsArtifacts(),
                        stateDelta,
                        branch)
                    .ignoreElement();
          }

          return appendMessage
              .andThen(
                  Flowable.defer(
                      () -> {
                        // Build the resumed context after any append so routing and rehydration see
                        // the latest history.
                        InvocationContext context =
                            newInvocationContextBuilder(session)
                                .invocationId(resolvedInvocationId)
                                .runConfig(runConfig)
                                .userContent(newMessage == null ? Content.fromParts() : newMessage)
                                .build();
                        context.populateInvocationAgentStates();

                        // No-op guard: a completed invocation (its active agent already finished)
                        // is not re-run.
                        if (context.endOfAgents().getOrDefault(context.agent().name(), false)) {
                          return Flowable.<Event>empty();
                        }

                        PersistBarrier.enable(context);

                        return context
                            .agent()
                            .runAsync(context)
                            .concatMap(
                                agentEvent -> {
                                  Single<Event> persistStep =
                                      agentEvent.partial().orElse(false)
                                          ? Single.just(agentEvent)
                                          : this.sessionService.appendEvent(session, agentEvent);
                                  return persistStep
                                      .doOnSuccess(
                                          unusedEvent ->
                                              PersistBarrier.markPersisted(
                                                  context, agentEvent.id()))
                                      .doOnError(
                                          error ->
                                              PersistBarrier.markFailed(
                                                  context, agentEvent.id(), error))
                                      .flatMap(
                                          registeredEvent ->
                                              context
                                                  .pluginManager()
                                                  .onEventCallback(context, registeredEvent)
                                                  .defaultIfEmpty(registeredEvent))
                                      .toFlowable();
                                })
                            .concatWith(Completable.defer(() -> compactEvents(session)));
                      }))
              .compose(Tracing.<Event>withContext(capturedContext));
        });
  }

  /**
   * Resolves which invocation a request targets: the invocation that issued the function call
   * matching {@code newMessage}'s function response, else the caller-supplied {@code invocationId}.
   * Returns {@code null} when neither applies (a fresh message starts a new invocation).
   */
  private static @Nullable String resolveInvocationId(
      Session session, @Nullable Content newMessage, @Nullable String invocationId) {
    if (newMessage != null) {
      Optional<String> fromResponse =
          matchingFunctionCallEvent(session, newMessage).map(Event::invocationId);
      if (fromResponse.isPresent()) {
        return fromResponse.get();
      }
    }
    return invocationId;
  }

  /**
   * Returns the session event whose function call matches a function response id carried by {@code
   * newMessage}, searching newest-first. Both the resumed invocation id and the branch of the
   * appended function-response event are derived from it.
   */
  private static Optional<Event> matchingFunctionCallEvent(Session session, Content newMessage) {
    Set<String> responseIds = new HashSet<>();
    newMessage
        .parts()
        .ifPresent(
            parts ->
                parts.forEach(
                    part ->
                        part.functionResponse()
                            .flatMap(FunctionResponse::id)
                            .ifPresent(responseIds::add)));
    if (responseIds.isEmpty()) {
      return Optional.empty();
    }
    List<Event> events = session.events();
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      for (FunctionCall call : event.functionCalls()) {
        if (call.id().isPresent() && responseIds.contains(call.id().get())) {
          return Optional.of(event);
        }
      }
    }
    return Optional.empty();
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
    InvocationContext.Builder builder =
        newInvocationContextBuilder(session)
            .runConfig(runConfigBuilder.build())
            .userContent(Content.fromParts())
            .liveRequestQueue(liveRequestQueue);

    return builder.build();
  }

  private InvocationContext.Builder newInvocationContextBuilder(Session session) {
    BaseAgent rootAgent = this.agent;
    return InvocationContext.builder()
        .sessionService(this.sessionService)
        .artifactService(this.artifactService)
        .memoryService(this.memoryService)
        .pluginManager(this.pluginManager)
        .agent(rootAgent)
        .session(session)
        .eventsCompactionConfig(this.eventsCompactionConfig)
        .contextCacheConfig(this.contextCacheConfig)
        .resumabilityConfig(this.resumabilityConfig)
        .agent(this.findAgentToRun(session, rootAgent));
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
                    this.pluginManager
                        .runOnRunErrorCallback(invocationContext, throwable)
                        .onErrorComplete()
                        .subscribe();
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

  /** Returns the agent that should handle the next request based on session history. */
  private BaseAgent findAgentToRun(Session session, BaseAgent rootAgent) {
    // Route a function response to its call's author; when resumable, re-enter via the author's
    // top-most resume-aware workflow ancestor (SequentialAgent/LoopAgent) so the workflow can
    // advance past it (else route straight to it, matching Python ADK v1 with resumability off).
    // Temporary, event-based.
    Optional<BaseAgent> functionCallAuthor =
        Functions.findMatchingFunctionCallEvent(session.events())
            .filter(event -> event.author() != null)
            .flatMap(event -> rootAgent.findAgent(event.author()));
    if (functionCallAuthor.isPresent()) {
      return isResumable()
          ? topmostResumableWorkflowAncestor(functionCallAuthor.get())
          : functionCallAuthor.get();
    }

    List<Event> events = new ArrayList<>(session.events());
    Collections.reverse(events);

    for (Event event : events) {
      String author = event.author();
      if (author == null) {
        continue;
      }
      if (author.equals("user")) {
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
   * Returns the top-most ancestor reachable from {@code agent} through resume-aware workflow
   * parents ({@link SequentialAgent} or {@link LoopAgent}), or {@code agent} itself otherwise, so a
   * sub-agent resumed from a long-running pause re-enters the workflow that sequences it and the
   * workflow can advance past it. Other agents resume their paused sub-agent directly.
   */
  private static BaseAgent topmostResumableWorkflowAncestor(BaseAgent agent) {
    BaseAgent result = agent;
    BaseAgent parent = agent.parentAgent();
    while (parent instanceof SequentialAgent || parent instanceof LoopAgent) {
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
      BaseAgent agent, @Nullable EventsCompactionConfig config) {
    if (config == null || config.summarizer() != null) {
      return config;
    }
    LlmEventSummarizer summarizer =
        Optional.of(agent)
            .filter(LlmAgent.class::isInstance)
            .map(LlmAgent.class::cast)
            .flatMap(LlmAgent::model)
            .flatMap(Model::model)
            .map(LlmEventSummarizer::new)
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
