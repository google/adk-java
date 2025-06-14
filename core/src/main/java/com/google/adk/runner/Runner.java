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
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.utils.CollectionUtils;
import com.google.common.collect.ImmutableList;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** The main class for the GenAI Agents runner. */
public class Runner {
  private final BaseAgent agent;
  private final String appName;
  private final BaseArtifactService artifactService;
  private final BaseSessionService sessionService;

  /**
   * Constructs a new {@code Runner} instance.
   *
   * @param agent The primary {@link BaseAgent} to be run by this runner.
   * @param appName The name of the application associated with this runner.
   * @param artifactService The service for managing artifacts (e.g., storing input blobs).
   * @param sessionService The service for managing sessions and their events.
   */
  public Runner(
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService) {
    this.agent = agent;
    this.appName = appName;
    this.artifactService = artifactService;
    this.sessionService = sessionService;
  }

  /**
   * Returns the primary agent associated with this runner.
   *
   * @return The {@link BaseAgent} instance.
   */
  public BaseAgent agent() {
    return this.agent;
  }

  /**
   * Returns the application name associated with this runner.
   *
   * @return The application name string.
   */
  public String appName() {
    return this.appName;
  }

  /**
   * Returns the artifact service used by this runner.
   *
   * @return The {@link BaseArtifactService} instance.
   */
  public BaseArtifactService artifactService() {
    return this.artifactService;
  }

  /**
   * Returns the session service used by this runner.
   *
   * @return The {@link BaseSessionService} instance.
   */
  public BaseSessionService sessionService() {
    return this.sessionService;
  }

  /**
   * Appends a new user message to the session's event history.
   * If {@code saveInputBlobsAsArtifacts} is true and an artifact service is provided,
   * any inline data parts within the message are saved as artifacts, and the original
   * part is replaced with a placeholder text indicating the artifact saving.
   *
   * @param session The current {@link Session} to which the message will be appended.
   * @param newMessage The new {@link Content} message from the user.
   * @param invocationContext The {@link InvocationContext} for the current run.
   * @param saveInputBlobsAsArtifacts A boolean flag indicating whether input blobs should be saved as artifacts.
   * @throws IllegalArgumentException if the {@code newMessage} contains no parts.
   */
  private void appendNewMessageToSession(
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
    this.sessionService.appendEvent(session, event);
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
  public Flowable<Event> runAsync(Session session, Content newMessage, RunConfig runConfig) {
    Span span = Telemetry.getTracer().spanBuilder("invocation").startSpan();
    try (Scope scope = span.makeCurrent()) {
      return Flowable.just(session)
          .flatMap(
              sess -> {
                BaseAgent rootAgent = this.agent;
                InvocationContext invocationContext =
                    InvocationContext.create(
                        this.sessionService,
                        this.artifactService,
                        InvocationContext.newInvocationContextId(),
                        rootAgent,
                        sess,
                        newMessage,
                        runConfig);

                if (newMessage != null) {
                  appendNewMessageToSession(
                      sess, newMessage, invocationContext, runConfig.saveInputBlobsAsArtifacts());
                }

                invocationContext.agent(this.findAgentToRun(sess, rootAgent));
                Flowable<Event> events = invocationContext.agent().runAsync(invocationContext);
                return events.doOnNext(event -> this.sessionService.appendEvent(sess, event));
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
   * Creates a new {@link InvocationContext} for a live (streaming) agent run,
   * applying default modalities if necessary.
   * If {@code responseModalities} are not specified but a {@code liveRequestQueue} is present,
   * it defaults the response modality to {@link Modality.Known#AUDIO} and enables audio transcription
   * if not explicitly configured.
   *
   * @param session The {@link Session} for which to create the invocation context.
   * @param liveRequestQueue An {@link Optional} containing the {@link LiveRequestQueue} if this is a live run.
   * @param runConfig The {@link RunConfig} for the current run.
   * @return A new {@link InvocationContext} configured for a live run.
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
    return newInvocationContext(session, liveRequestQueue, runConfigBuilder.build());
  }

  /**
   * Creates a new {@link InvocationContext} for a given session, live request queue, and run configuration.
   * It determines the active agent within the session's hierarchy.
   *
   * @param session The {@link Session} for which the context is created.
   * @param liveRequestQueue An {@link Optional} containing the {@link LiveRequestQueue} if applicable, or empty.
   * @param runConfig The {@link RunConfig} to be used for the invocation.
   * @return A new {@link InvocationContext} instance.
   */
  private InvocationContext newInvocationContext(
      Session session, Optional<LiveRequestQueue> liveRequestQueue, RunConfig runConfig) {
    BaseAgent rootAgent = this.agent;
    InvocationContext invocationContext =
        InvocationContext.create(
            this.sessionService,
            this.artifactService,
            rootAgent,
            session,
            liveRequestQueue.orElse(null),
            runConfig);
    invocationContext.agent(this.findAgentToRun(session, rootAgent));
    return invocationContext;
  }

  /**
   * Runs the agent in live (streaming) mode, allowing for continuous interaction.
   * This method initializes an invocation context suitable for live interaction
   * and delegates the execution to the appropriate agent's {@code runLive} method.
   * Events generated by the agent are appended to the session.
   *
   * @param session The {@link Session} for the live run.
   * @param liveRequestQueue The {@link LiveRequestQueue} for handling real-time requests.
   * @param runConfig Configuration for the agent run.
   * @return A {@link Flowable} stream of {@link Event} objects generated by the agent during live execution.
   */
  public Flowable<Event> runLive(
      Session session, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    Span span = Telemetry.getTracer().spanBuilder("invocation").startSpan();
    try (Scope scope = span.makeCurrent()) {
      InvocationContext invocationContext =
          newInvocationContextForLive(session, Optional.of(liveRequestQueue), runConfig);
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
   * Runs the agent in live (streaming) mode, retrieving the session by user and session ID.
   * This method is a convenience wrapper around {@link #runLive(Session, LiveRequestQueue, RunConfig)}.
   *
   * @param userId The ID of the user for the session.
   * @param sessionId The ID of the session to run the agent in.
   * @param liveRequestQueue The {@link LiveRequestQueue} for handling real-time requests.
   * @param runConfig Configuration for the agent run.
   * @return A {@link Flowable} stream of {@link Event} objects generated by the agent during live execution.
   * @throws IllegalArgumentException if the specified session is not found.
   */
  public Flowable<Event> runLive(
      String userId, String sessionId, LiveRequestQueue liveRequestQueue, RunConfig runConfig) {
    Session session =
        this.sessionService.getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
    if (session == null) {
      return Flowable.error(
          new IllegalArgumentException(
              String.format("Session not found: %s for user %s", sessionId, userId)));
    }
    return this.runLive(session, liveRequestQueue, runConfig);
  }

  /**
   * Runs the agent asynchronously for a given session ID and new message, using a default user ID.
   * This method is a convenience for starting an agent run without explicitly providing a user ID.
   *
   * @param sessionId The ID of the session.
   * @param newMessage The new message from the user to process.
   * @param runConfig Configuration for the agent run.
   * @return A {@link Flowable} stream of {@link Event} objects generated by the agent.
   */
  public Flowable<Event> runWithSessionId(
      String sessionId, Content newMessage, RunConfig runConfig) {
    // TODO(b/410859954): Add user_id to getter or method signature. Assuming "tmp-user" for now.
    return this.runAsync("tmp-user", sessionId, newMessage, runConfig);
  }

  /**
   * Determines if a given agent and its parent chain are eligible for transfer across the agent tree.
   * An agent is considered transferable if it is an instance of {@link LlmAgent} and
   * neither it nor any of its {@link LlmAgent} parents disallow transfer to their parents.
   *
   * @param agentToRun The {@link BaseAgent} to check for transferability.
   * @return {@code true} if the agent is transferable across the agent tree, {@code false} otherwise.
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
   * Finds the appropriate agent to run within the agent hierarchy based on the session's event history.
   * It iterates through the session's events in reverse order to identify the last agent that
   * responded or the root agent if no specific agent is found or if the found agent is not transferable.
   *
   * @param session The current {@link Session}.
   * @param rootAgent The root {@link BaseAgent} of the application.
   * @return The {@link BaseAgent} instance that should handle the next request.
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
