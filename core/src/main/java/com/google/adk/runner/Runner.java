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
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.CollectionUtils;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.InlineMe;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.Content;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
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
  private final @Nullable BaseMemoryService memoryService;
  private final PluginManager pluginManager;

  /** Creates a new {@code Runner}. */
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService) {
    this(agent, appName, artifactService, sessionService, memoryService, ImmutableList.of());
  }

  /** Creates a new {@code Runner} with a list of plugins. */
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService,
      List<BasePlugin> plugins) {
    this.agent = agent;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
    this.pluginManager = new PluginManager(plugins);
  }

  /**
   * Creates a new {@code Runner}.
   *
   * @deprecated Use the constructor with {@code BaseMemoryService} instead even if with a null if
   *     you don't need the memory service.
   */
  @InlineMe(replacement = "this(agent, appName, artifactService, sessionService, null)")
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

  public @Nullable BaseMemoryService memoryService() {
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
    try (Scope unusedScope = span.makeCurrent()) {
      BaseAgent rootAgent = this.agent;
      String invocationId = InvocationContext.newInvocationContextId();

      // Create initial context
      InvocationContext initialContext =
          newInvocationContextWithId(
              session,
              Optional.of(newMessage),
              /* liveRequestQueue= */ Optional.empty(),
              runConfig,
              invocationId);

      Flowable<Event> events =
          Flowable.defer(
              () ->
                  this.pluginManager
                      .runOnUserMessageCallback(initialContext, newMessage)
                      .switchIfEmpty(Single.just(newMessage))
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
                            // Get the updated session after the message and state delta are applied
                            return this.sessionService
                                .getSession(
                                    session.appName(),
                                    session.userId(),
                                    session.id(),
                                    Optional.empty())
                                .flatMapPublisher(
                                    updatedSession -> {
                                      // Create context with updated session for beforeRunCallback
                                      InvocationContext contextWithUpdatedSession =
                                          newInvocationContextWithId(
                                              updatedSession,
                                              event.content(),
                                              Optional.empty(),
                                              runConfig,
                                              invocationId);
                                      contextWithUpdatedSession.agent(
                                          this.findAgentToRun(updatedSession, rootAgent));

                                      // Call beforeRunCallback with updated session
                                      Maybe<Event> beforeRunEvent =
                                          this.pluginManager
                                              .runBeforeRunCallback(contextWithUpdatedSession)
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
                                                          .appendEvent(updatedSession, agentEvent)
                                                          .flatMap(
                                                              registeredEvent -> {
                                                                // TODO: remove this hack after
                                                                // deprecating runAsync with
                                                                // Session.
                                                                copySessionStates(
                                                                    updatedSession, session);
                                                                return contextWithUpdatedSession
                                                                    .pluginManager()
                                                                    .runOnEventCallback(
                                                                        contextWithUpdatedSession,
                                                                        registeredEvent)
                                                                    .defaultIfEmpty(
                                                                        registeredEvent);
                                                              })
                                                          .toFlowable());

                                      // If beforeRunCallback returns content, emit it and skip
                                      // agent
                                      return beforeRunEvent
                                          .toFlowable()
                                          .switchIfEmpty(agentEvents)
                                          .concatWith(
                                              Completable.defer(
                                                  () ->
                                                      pluginManager.runAfterRunCallback(
                                                          contextWithUpdatedSession)));
                                    });
                          }));

      return events
          .doOnError(
              throwable -> {
                span.setStatus(StatusCode.ERROR, "Error in runAsync Flowable execution");
                span.recordException(throwable);
              })
          .doFinally(span::end);
    } catch (Throwable t) {
      span.setStatus(StatusCode.ERROR, "Error during runAsync synchronous setup");
      span.recordException(t);
      span.end();
      return Flowable.error(t);
    }
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
    if (!CollectionUtils.isNullOrEmpty(runConfig.responseModalities())
        && liveRequestQueue.isPresent()) {
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
    BaseAgent rootAgent = this.agent;
    InvocationContext invocationContext =
        new InvocationContext(
            this.sessionService,
            this.artifactService,
            this.memoryService,
            this.pluginManager,
            liveRequestQueue,
            /* branch= */ Optional.empty(),
            InvocationContext.newInvocationContextId(),
            rootAgent,
            session,
            newMessage,
            runConfig,
            /* endInvocation= */ false);
    invocationContext.agent(this.findAgentToRun(session, rootAgent));
    return invocationContext;
  }

  /**
   * Creates a new InvocationContext with a specific invocation ID.
   *
   * @return a new {@link InvocationContext} with the specified invocation ID.
   */
  private InvocationContext newInvocationContextWithId(
      Session session,
      Optional<Content> newMessage,
      Optional<LiveRequestQueue> liveRequestQueue,
      RunConfig runConfig,
      String invocationId) {
    BaseAgent rootAgent = this.agent;
    InvocationContext invocationContext =
        new InvocationContext(
            this.sessionService,
            this.artifactService,
            this.memoryService,
            this.pluginManager,
            liveRequestQueue,
            /* branch= */ Optional.empty(),
            invocationId,
            rootAgent,
            session,
            newMessage,
            runConfig,
            /* endInvocation= */ false);
    invocationContext.agent(this.findAgentToRun(session, rootAgent));
    return invocationContext;
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
    try (Scope scope = span.makeCurrent()) {
      InvocationContext invocationContext =
          newInvocationContextForLive(session, Optional.of(liveRequestQueue), runConfig);
      if (invocationContext.agent() instanceof LlmAgent) {
        LlmAgent agent = (LlmAgent) invocationContext.agent();
        for (BaseTool tool : agent.tools()) {
          if (tool instanceof FunctionTool functionTool) {
            for (Parameter parameter : functionTool.func().getParameters()) {
              if (parameter.getType().equals(LiveRequestQueue.class)) {
                invocationContext
                    .activeStreamingTools()
                    .put(functionTool.name(), new ActiveStreamingTool(new LiveRequestQueue()));
              }
            }
          }
        }
      }
      return invocationContext
          .agent()
          .runLive(invocationContext)
          .doOnNext(event -> this.sessionService.appendEvent(session, event))
          .doOnError(
              throwable -> {
                span.setStatus(StatusCode.ERROR, "Error in runLive Flowable execution");
                span.recordException(throwable);
              })
          .doFinally(span::end);
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

  // TODO: run statelessly
}
