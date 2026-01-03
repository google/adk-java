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

import com.google.adk.Telemetry;
import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.flows.llmflows.ResumabilityConfig;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.Model;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.CollectionUtils;
import com.google.common.collect.ImmutableList;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** The main class for the GenAI Agents runner. */
public class Runner {
  private final BaseAgent agent;
  private final String appName;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  @Nullable private final BaseMemoryService memoryService;
  private final PluginManager pluginManager;
  private final ResumabilityConfig resumabilityConfig;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;

  /** Builder for {@link Runner}. */
  public static class Builder {
    private BaseAgent agent;
    private String appName;
    private BaseArtifactService artifactService = new InMemoryArtifactService();
    private BaseSessionService sessionService = new InMemorySessionService();
    @Nullable private BaseMemoryService memoryService = null;
    private List<BasePlugin> plugins = ImmutableList.of();
    private ResumabilityConfig resumabilityConfig = new ResumabilityConfig();
    @Nullable private EventsCompactionConfig eventsCompactionConfig;

    @CanIgnoreReturnValue
    public Builder agent(BaseAgent agent) {
      this.agent = agent;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder appName(String appName) {
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
    public Builder plugins(List<BasePlugin> plugins) {
      this.plugins = plugins;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder resumabilityConfig(ResumabilityConfig resumabilityConfig) {
      this.resumabilityConfig = resumabilityConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder eventsCompactionConfig(EventsCompactionConfig eventsCompactionConfig) {
      this.eventsCompactionConfig = eventsCompactionConfig;
      return this;
    }

    public Runner build() {
      if (agent == null) {
        throw new IllegalStateException("Agent must be provided.");
      }
      if (appName == null) {
        throw new IllegalStateException("App name must be provided.");
      }
      if (artifactService == null) {
        throw new IllegalStateException("Artifact service must be provided.");
      }
      if (sessionService == null) {
        throw new IllegalStateException("Session service must be provided.");
      }
      return new Runner(
          agent,
          appName,
          artifactService,
          sessionService,
          memoryService,
          plugins,
          resumabilityConfig,
          eventsCompactionConfig);
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
    this(
        agent,
        appName,
        artifactService,
        sessionService,
        memoryService,
        ImmutableList.of(),
        new ResumabilityConfig());
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
      List<BasePlugin> plugins) {
    this(
        agent,
        appName,
        artifactService,
        sessionService,
        memoryService,
        plugins,
        new ResumabilityConfig());
  }

  /**
   * Creates a new {@code Runner} with a list of plugins and resumability config.
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
      List<BasePlugin> plugins,
      ResumabilityConfig resumabilityConfig) {
    this(
        agent,
        appName,
        artifactService,
        sessionService,
        memoryService,
        plugins,
        resumabilityConfig,
        null);
  }

  /**
   * Creates a new {@code Runner} with a list of plugins and resumability config.
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
      List<BasePlugin> plugins,
      ResumabilityConfig resumabilityConfig,
      @Nullable EventsCompactionConfig eventsCompactionConfig) {
    this.agent = agent;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
    this.pluginManager = new PluginManager(plugins);
    this.resumabilityConfig = resumabilityConfig;
    this.eventsCompactionConfig =
        Optional.ofNullable(eventsCompactionConfig)
            .map(c -> createEventsCompactionConfig(agent, c))
            .orElse(null);
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

    if (this.artifactService != null && saveInputBlobsAsArtifacts) {
      // The runner directly saves the artifacts (if applicable) in the
      // user message and replaces the artifact data with a file name
      // placeholder.
      for (int i = 0; i < newMessage.parts().get().size(); i++) {
        Part part = newMessage.parts().get().get(i);
        if (part.inlineData().isEmpty()) {
          continue;
        }
        String fileName = "artifact_" + invocationContext.invocationId() + "_" + i;
        var unused =
            this.artifactService.saveArtifact(
                this.appName, session.userId(), session.id(), fileName, part);

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
            .content(Optional.of(newMessage));

    // Add state delta if provided
    if (stateDelta != null && !stateDelta.isEmpty()) {
      eventBuilder.actions(
          EventActions.builder().stateDelta(new ConcurrentHashMap<>(stateDelta)).build());
    }

    return this.sessionService.appendEvent(session, eventBuilder.build());
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
    Maybe<Session> maybeSession =
        this.sessionService.getSession(appName, userId, sessionId, Optional.empty());
    return maybeSession
        .switchIfEmpty(
            Single.error(
                new IllegalArgumentException(
                    String.format("Session not found: %s for user %s", sessionId, userId))))
        .flatMapPublisher(session -> this.runAsync(session, newMessage, runConfig, stateDelta));
  }

  /** See {@link #runAsync(String, String, Content, RunConfig, Map)}. */
  public Flowable<Event> runAsync(String userId, String sessionId, Content newMessage) {
    return runAsync(userId, sessionId, newMessage, RunConfig.builder().build());
  }

  /**
   * See {@link #runAsync(Session, Content, RunConfig, Map)}.
   *
   * @deprecated Use runAsync with sessionId.
   */
  @Deprecated(since = "0.4.0", forRemoval = true)
  public Flowable<Event> runAsync(Session session, Content newMessage, RunConfig runConfig) {
    return runAsync(session, newMessage, runConfig, /* stateDelta= */ null);
  }

  /**
   * Runs the agent asynchronously using a provided Session object.
   *
   * @param session The session to run the agent in.
   * @param newMessage The new message from the user to process.
   * @param runConfig Configuration for the agent run.
   * @param stateDelta Optional map of state updates to merge into the session for this run.
   * @return A Flowable stream of {@link Event} objects generated by the agent during execution.
   * @deprecated Use runAsync with sessionId.
   */
  @Deprecated(since = "0.4.0", forRemoval = true)
  public Flowable<Event> runAsync(
      Session session,
      Content newMessage,
      RunConfig runConfig,
      @Nullable Map<String, Object> stateDelta) {
    Span span =
        Telemetry.getTracer().spanBuilder("invocation").setParent(Context.current()).startSpan();
    Context spanContext = Context.current().with(span);

    try {
      BaseAgent rootAgent = this.agent;
      String invocationId = InvocationContext.newInvocationContextId();

      // Create initial context
      InvocationContext initialContext =
          newInvocationContextBuilder(
                  session,
                  Optional.of(newMessage),
                  /* liveRequestQueue= */ Optional.empty(),
                  runConfig)
              .invocationId(invocationId)
              .build();

      return Telemetry.traceFlowable(
          spanContext,
          span,
          () ->
              Flowable.defer(
                      () ->
                          this.pluginManager
                              .onUserMessageCallback(initialContext, newMessage)
                              .defaultIfEmpty(newMessage)
                              .flatMap(
                                  content ->
                                      (content != null)
                                          ? appendNewMessageToSession(
                                              session,
                                              content,
                                              initialContext,
                                              runConfig.saveInputBlobsAsArtifacts(),
                                              stateDelta)
                                          : Single.just(null))
                              .flatMapPublisher(
                                  event -> {
                                    // Get the updated session after the message and state delta are
                                    // applied
                                    return this.sessionService
                                        .getSession(
                                            session.appName(),
                                            session.userId(),
                                            session.id(),
                                            Optional.empty())
                                        .flatMapPublisher(
                                            updatedSession -> {
                                              // Create context with updated session for
                                              // beforeRunCallback
                                              InvocationContext contextWithUpdatedSession =
                                                  newInvocationContextBuilder(
                                                          updatedSession,
                                                          event.content(),
                                                          /* liveRequestQueue= */ Optional.empty(),
                                                          runConfig)
                                                      .invocationId(invocationId)
                                                      .agent(
                                                          this.findAgentToRun(
                                                              updatedSession, rootAgent))
                                                      .build();

                                              // Call beforeRunCallback with updated session
                                              Maybe<Event> beforeRunEvent =
                                                  this.pluginManager
                                                      .beforeRunCallback(contextWithUpdatedSession)
                                                      .map(
                                                          content ->
                                                              Event.builder()
                                                                  .id(Event.generateEventId())
                                                                  .invocationId(
                                                                      contextWithUpdatedSession
                                                                          .invocationId())
                                                                  .author("model")
                                                                  .content(Optional.of(content))
                                                                  .build());

                                              // Agent execution
                                              Flowable<Event> agentEvents =
                                                  contextWithUpdatedSession
                                                      .agent()
                                                      .runAsync(contextWithUpdatedSession)
                                                      .flatMap(
                                                          agentEvent ->
                                                              this.sessionService
                                                                  .appendEvent(
                                                                      updatedSession, agentEvent)
                                                                  .flatMap(
                                                                      registeredEvent -> {
                                                                        // TODO: remove this hack
                                                                        // after
                                                                        // deprecating runAsync with
                                                                        // Session.
                                                                        copySessionStates(
                                                                            updatedSession,
                                                                            session);
                                                                        return contextWithUpdatedSession
                                                                            .pluginManager()
                                                                            .onEventCallback(
                                                                                contextWithUpdatedSession,
                                                                                registeredEvent)
                                                                            .defaultIfEmpty(
                                                                                registeredEvent);
                                                                      })
                                                                  .toFlowable());

                                              // If beforeRunCallback returns content, emit it and
                                              // skip
                                              // agent
                                              return beforeRunEvent
                                                  .toFlowable()
                                                  .switchIfEmpty(agentEvents)
                                                  .concatWith(
                                                      Completable.defer(
                                                          () ->
                                                              pluginManager.runAfterRunCallback(
                                                                  contextWithUpdatedSession)))
                                                  .concatWith(
                                                      Completable.defer(
                                                          () -> compactEvents(updatedSession)));
                                            });
                                  }))
                  .doOnError(
                      throwable -> {
                        span.setStatus(StatusCode.ERROR, "Error in runAsync Flowable execution");
                        span.recordException(throwable);
                      }));
    } catch (Throwable t) {
      span.setStatus(StatusCode.ERROR, "Error during runAsync synchronous setup");
      span.recordException(t);
      span.end();
      return Flowable.error(t);
    }
  }

  private Completable compactEvents(Session session) {
    return Optional.ofNullable(eventsCompactionConfig)
        .map(SlidingWindowEventCompactor::new)
        .map(c -> c.compact(session, sessionService))
        .orElse(Completable.complete());
  }

  private void copySessionStates(Session source, Session target) {
    // TODO: remove this hack when deprecating all runAsync with Session.
    for (var entry : source.state().entrySet()) {
      target.state().put(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Creates an {@link InvocationContext} for a live (streaming) run.
   *
   * @return invocation context configured for a live run.
   */
  private InvocationContext newInvocationContextForLive(
      Session session, Optional<LiveRequestQueue> liveRequestQueue, RunConfig runConfig) {
    RunConfig.Builder runConfigBuilder = RunConfig.builder(runConfig);
    if (liveRequestQueue.isPresent()) {
      // Default to AUDIO modality if not specified.
      if (CollectionUtils.isNullOrEmpty(runConfig.responseModalities())) {
        runConfigBuilder.setResponseModalities(
            ImmutableList.of(new Modality(Modality.Known.AUDIO)));
        if (runConfig.outputAudioTranscription() == null) {
          runConfigBuilder.setOutputAudioTranscription(AudioTranscriptionConfig.builder().build());
        }
      } else if (!runConfig.responseModalities().contains(new Modality(Modality.Known.TEXT))) {
        if (runConfig.outputAudioTranscription() == null) {
          runConfigBuilder.setOutputAudioTranscription(AudioTranscriptionConfig.builder().build());
        }
      }
      // Need input transcription for agent transferring in live mode.
      if (runConfig.inputAudioTranscription() == null) {
        runConfigBuilder.setInputAudioTranscription(AudioTranscriptionConfig.builder().build());
      }
    }
    return newInvocationContext(
        session, /* newMessage= */ Optional.empty(), liveRequestQueue, runConfigBuilder.build());
  }

  /**
   * Creates an {@link InvocationContext} for the given session, request queue, and config.
   *
   * @return a new {@link InvocationContext}.
   */
  private InvocationContext newInvocationContext(
      Session session,
      Optional<Content> newMessage,
      Optional<LiveRequestQueue> liveRequestQueue,
      RunConfig runConfig) {
    return newInvocationContextBuilder(session, newMessage, liveRequestQueue, runConfig).build();
  }

  private InvocationContext.Builder newInvocationContextBuilder(
      Session session,
      Optional<Content> newMessage,
      Optional<LiveRequestQueue> liveRequestQueue,
      RunConfig runConfig) {
    BaseAgent rootAgent = this.agent;
    var invocationContextBuilder =
        InvocationContext.builder()
            .sessionService(this.sessionService)
            .artifactService(this.artifactService)
            .memoryService(this.memoryService)
            .pluginManager(this.pluginManager)
            .agent(rootAgent)
            .session(session)
            .userContent(newMessage.orElse(Content.fromParts()))
            .runConfig(runConfig)
            .resumabilityConfig(this.resumabilityConfig)
            .agent(this.findAgentToRun(session, rootAgent));
    liveRequestQueue.ifPresent(invocationContextBuilder::liveRequestQueue);
    return invocationContextBuilder;
  }

  /**
   * Runs the agent in live mode, appending generated events to the session.
   *
   * @return stream of events from the agent.
   */
  public Flowable<Event> runLive(
      Session session, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    Span span =
        Telemetry.getTracer().spanBuilder("invocation").setParent(Context.current()).startSpan();
    Context spanContext = Context.current().with(span);

    try {
      InvocationContext invocationContext =
          newInvocationContextForLive(session, Optional.of(liveRequestQueue), runConfig);

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

      return invocationContextSingle.flatMapPublisher(
          updatedInvocationContext ->
              Telemetry.traceFlowable(
                  spanContext,
                  span,
                  () ->
                      updatedInvocationContext
                          .agent()
                          .runLive(updatedInvocationContext)
                          .doOnNext(event -> this.sessionService.appendEvent(session, event))
                          .onErrorResumeNext(
                              throwable -> {
                                span.setStatus(
                                    StatusCode.ERROR, "Error in runLive Flowable execution");
                                span.recordException(throwable);
                                span.end();
                                return Flowable.error(throwable);
                              })));
    } catch (Throwable t) {
      span.setStatus(StatusCode.ERROR, "Error during runLive synchronous setup");
      span.recordException(t);
      span.end();
      return Flowable.error(t);
    }
  }

  /**
   * Retrieves the session and runs the agent in live mode.
   *
   * @return stream of events from the agent.
   * @throws IllegalArgumentException if the session is not found.
   */
  public Flowable<Event> runLive(
      String userId, String sessionId, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    return this.sessionService
        .getSession(appName, userId, sessionId, Optional.empty())
        .flatMapPublisher(
            session -> {
              if (session == null) {
                return Flowable.error(
                    new IllegalArgumentException(
                        String.format("Session not found: %s for user %s", sessionId, userId)));
              }
              return this.runLive(session, liveRequestQueue, runConfig);
            });
  }

  /**
   * Runs the agent asynchronously with a default user ID.
   *
   * @return stream of generated events.
   */
  public Flowable<Event> runWithSessionId(
      String sessionId, Content newMessage, RunConfig runConfig) {
    // TODO(b/410859954): Add user_id to getter or method signature. Assuming "tmp-user" for now.
    return this.runAsync("tmp-user", sessionId, newMessage, runConfig);
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
      if (author.equals("user")) {
        continue;
      }

      if (author.equals(rootAgent.name())) {
        return rootAgent;
      }

      BaseAgent agent = rootAgent.findSubAgent(author);

      if (agent == null) {
        continue;
      }

      if (this.isTransferableAcrossAgentTree(agent)) {
        return agent;
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

  /**
   * Creates a new {@link EventsCompactionConfig} based on the given configuration. If the {@link
   * com.google.adk.summarizer.BaseEventSummarizer} is missing, it will be default to the {@link
   * LlmEventSummarizer} using the same model as the LLM base agent.
   */
  private static EventsCompactionConfig createEventsCompactionConfig(
      BaseAgent agent, EventsCompactionConfig config) {
    return new EventsCompactionConfig(
        config.compactionInterval(),
        config.overlapSize(),
        config
            .summarizer()
            .or(
                () ->
                    Optional.of(agent)
                        .filter(LlmAgent.class::isInstance)
                        .map(LlmAgent.class::cast)
                        .map(LlmAgent::resolvedModel)
                        .flatMap(Model::model)
                        .map(LlmEventSummarizer::new)));
  }

  // TODO: run statelessly
}
