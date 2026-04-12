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

import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/** The main class for the GenAI Agents runner. */
public class Runner {
  private final BaseAgent agent;
  private final String appName;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  @Nullable private final BaseMemoryService memoryService;
  private final PluginManager pluginManager;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;
  @Nullable private final ContextCacheConfig contextCacheConfig;
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
      } else {
        buildAgent = this.agent;
        buildAppName = this.appName;
        buildPlugins = this.plugins;
        buildEventsCompactionConfig = null;
        buildContextCacheConfig = null;
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
          buildContextCacheConfig);
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
    this.agent = agent;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
    this.pluginManager = new PluginManager(plugins);
    this.eventsCompactionConfig = createEventsCompactionConfig(agent, eventsCompactionConfig);
    this.contextCacheConfig = contextCacheConfig;
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
   * @throws IllegalArgumentException if message has no parts.
   */
  private Single<Event> appendNewMessageToSession(
      Session session,
      Content newMessage,
      InvocationContext invocationContext,
      boolean saveInputBlobsAsArtifacts,
      @Nullable Map<String, Object> stateDelta) {
    if (newMessage.parts().isEmpty()) {
      throw new IllegalArgumentException("No parts in the new_message.");
    }

    Completable saveArtifactsFlow = Completable.complete();
    if (this.artifactService != null && saveInputBlobsAsArtifacts) {
      // The runner directly saves the artifacts (if applicable) in the user message and replaces
      // the artifact data with a file name placeholder.
      for (int i = 0; i < newMessage.parts().get().size(); i++) {
        Part part = newMessage.parts().get().get(i);
        if (part.inlineData().isEmpty()) {
          continue;
        }
        String fileName = "artifact_" + invocationContext.invocationId() + "_" + i;
        saveArtifactsFlow =
            saveArtifactsFlow.andThen(
                this.artifactService
                    .saveArtifact(this.appName, session.userId(), session.id(), fileName, part)
                    .ignoreElement());

        newMessage
            .parts()
            .get()
            .set(
                i,
                Part.fromText(
                    "Uploaded file: " + fileName + ". It has been saved to the artifacts"));
      }
    }
    // Appends only. We do not yield the event because it's not from the model.
    Event.Builder eventBuilder =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId(invocationContext.invocationId())
            .author("user")
            .content(newMessage);

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
    Context capturedContext = Context.current();
    return Flowable.defer(
            () -> {
              BaseAgent rootAgent = this.agent;
              String invocationId = InvocationContext.newInvocationContextId();

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
                      event -> {
                        // Get the updated session after the message and state delta are
                        // applied
                        return this.sessionService
                            .getSession(
                                session.appName(), session.userId(), session.id(), Optional.empty())
                            .flatMapPublisher(
                                updatedSession ->
                                    runAgentWithUpdatedSession(
                                        initialContext, updatedSession, event, rootAgent))
                            .compose(Tracing.<Event>withContext(capturedContext));
                      });
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

    // Agent execution
    Flowable<Event> agentEvents =
        contextWithUpdatedSession
            .agent()
            .runAsync(contextWithUpdatedSession)
            .concatMap(
                agentEvent ->
                    this.sessionService
                        .appendEvent(updatedSession, agentEvent)
                        .flatMap(
                            registeredEvent -> {
                              // TODO: remove this hack after deprecating runAsync with Session.
                              copySessionStates(updatedSession, initialContext.session());
                              return contextWithUpdatedSession
                                  .pluginManager()
                                  .onEventCallback(contextWithUpdatedSession, registeredEvent)
                                  .defaultIfEmpty(registeredEvent);
                            })
                        .toFlowable());

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
                          .doOnNext(event -> this.sessionService.appendEvent(session, event)))
              .doOnError(
                  throwable -> {
                    Span span = Span.current();
                    span.setStatus(StatusCode.ERROR, "Error in runLive Flowable execution");
                    span.recordException(throwable);
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

  /**
   * Returns the agent that should handle the next request based on session history.
   *
   * @return agent to run.
   */
  private BaseAgent findAgentToRun(Session session, BaseAgent rootAgent) {
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
