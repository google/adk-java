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
import com.google.adk.memory.BaseMemoryService;
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
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** The main class for the GenAI Agents runner. */
public class Runner {
  private final BaseAgent agent;
  private final String appName;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;
  private final @Nullable BaseMemoryService memoryService;

  /** Creates a new {@code Runner}. */
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      @Nullable BaseMemoryService memoryService) {
    this.agent = agent;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
    this.memoryService = memoryService;
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

  /**
   * Appends a new user message to the session history.
   *
   * @throws IllegalArgumentException if message has no parts.
   */
  private Single<Event> appendNewMessageToSession(
      Session session,
      Content newMessage,
      InvocationContext invocationContext,
      boolean saveInputBlobsAsArtifacts) {
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
    Event event =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId(invocationContext.invocationId())
            .author("user")
            .content(Optional.of(newMessage))
            .build();
    return this.sessionService.appendEvent(session, event);
  }

  /**
   * Runs the agent in the standard mode.
   *
   * @param userId The ID of the user for the session.
   * @param sessionId The ID of the session to run the agent in.
   * @param newMessage The new message from the user to process.
   * @param runConfig Configuration for the agent run.
   * @return A Flowable stream of {@link Event} objects generated by the agent during execution.
   */
  public Flowable<Event> runAsync(
      String userId, String sessionId, Content newMessage, RunConfig runConfig) {
    Maybe<Session> maybeSession =
        this.sessionService.getSession(appName, userId, sessionId, Optional.empty());
    return maybeSession
        .switchIfEmpty(
            Single.error(
                new IllegalArgumentException(
                    String.format("Session not found: %s for user %s", sessionId, userId))))
        .flatMapPublisher(session -> this.runAsync(session, newMessage, runConfig));
  }

  /**
   * Asynchronously runs the agent for a given user and session, processing a new message and using
   * a default {@link RunConfig}.
   *
   * <p>This method initiates an agent execution within the specified session, appending the
   * provided new message to the session's history. It utilizes a default {@code RunConfig} to
   * control execution parameters. The method returns a stream of {@link Event} objects representing
   * the agent's activity during the run.
   *
   * @param userId The ID of the user initiating the session.
   * @param sessionId The ID of the session in which the agent will run.
   * @param newMessage The new {@link Content} message to be processed by the agent.
   * @return A {@link Flowable} emitting {@link Event} objects generated by the agent.
   */
  public Flowable<Event> runAsync(String userId, String sessionId, Content newMessage) {
    return runAsync(userId, sessionId, newMessage, RunConfig.builder().build());
  }

  /**
   * Runs the agent in the standard mode using a provided Session object.
   *
   * @param session The session to run the agent in.
   * @param newMessage The new message from the user to process.
   * @param runConfig Configuration for the agent run.
   * @return A Flowable stream of {@link Event} objects generated by the agent during execution.
   */
  public Flowable<Event> runAsync(
      Session session, @Nullable Content newMessage, RunConfig runConfig) {
    Span span = Telemetry.getTracer().spanBuilder("invocation").startSpan();
    try (Scope scope = span.makeCurrent()) {
      return Flowable.just(session)
          .flatMap(
              sess -> {
                BaseAgent rootAgent = this.agent;
                InvocationContext invocationContext =
                    newInvocationContext(
                        sess,
                        Optional.ofNullable(newMessage),
                        /* liveRequestQueue= */ Optional.empty(),
                        runConfig);

                Single<Event> singleEvent =
                    (newMessage != null)
                        ? appendNewMessageToSession(
                            sess,
                            newMessage,
                            invocationContext,
                            runConfig.saveInputBlobsAsArtifacts())
                        : Single.just(null);
                return singleEvent.flatMapPublisher(
                    ignored -> {
                      invocationContext.agent(this.findAgentToRun(sess, rootAgent));
                      return invocationContext
                          .agent()
                          .runAsync(invocationContext)
                          .flatMap(
                              agentEvent ->
                                  this.sessionService.appendEvent(sess, agentEvent).toFlowable());
                    });
              })
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
   * Runs the agent in live mode, appending generated events to the session.
   *
   * @return stream of events from the agent.
   */
  public Flowable<Event> runLive(
      Session session, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    Span span = Telemetry.getTracer().spanBuilder("invocation").startSpan();
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
