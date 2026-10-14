/*
 * Copyright 2026 Google LLC
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

import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.State;
import com.google.common.base.Preconditions;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The context passed to callbacks and tools during an invocation.
 *
 * <p>Agent and model callbacks receive a {@link CallbackContext}, and tools and tool callbacks a
 * {@link com.google.adk.tools.ToolContext}; both extend this class, so a helper that serves both,
 * or a function tool's {@code toolContext} parameter, can take a {@code Context}. {@link
 * #requestConfirmation()} needs a function call ID, which only a tool call has.
 */
public class Context extends ReadonlyContext {

  /** The event actions that record this context's changes. */
  protected EventActions eventActions;

  private final State state;
  private final @Nullable String eventId;
  private Optional<String> functionCallId = Optional.empty();
  private Optional<ToolConfirmation> toolConfirmation = Optional.empty();

  /**
   * Initializes a context.
   *
   * @param invocationContext Current invocation context.
   * @param eventActions Event actions to record changes in, or null for new empty ones.
   * @param eventId The ID of the event associated with this context, or null if there is none.
   */
  Context(
      InvocationContext invocationContext,
      @Nullable EventActions eventActions,
      @Nullable String eventId) {
    super(invocationContext);
    this.eventActions = eventActions != null ? eventActions : EventActions.builder().build();
    this.state = new State(invocationContext.session().state(), this.eventActions.stateDelta());
    this.eventId = eventId;
  }

  /** Returns the delta-aware state of the current context. */
  @Override
  public State state() {
    return state;
  }

  /** Returns the {@link EventActions} associated with this context. */
  public EventActions eventActions() {
    return eventActions;
  }

  /** Returns the same {@link EventActions} as {@link #eventActions()}. */
  public EventActions actions() {
    return this.eventActions;
  }

  /** Returns the ID of the event associated with this context, or null if there is none. */
  public String eventId() {
    return eventId;
  }

  /** Returns the ID of the function call that invoked the current tool, if any. */
  public Optional<String> functionCallId() {
    return functionCallId;
  }

  /** Sets the ID of the function call that invoked the current tool, or clears it if null. */
  public void functionCallId(@Nullable String functionCallId) {
    this.functionCallId = Optional.ofNullable(functionCallId);
  }

  /** Returns the confirmation of the current tool call, if any. */
  public Optional<ToolConfirmation> toolConfirmation() {
    return toolConfirmation;
  }

  /** Sets the confirmation of the current tool call, or clears it if null. */
  public void toolConfirmation(@Nullable ToolConfirmation toolConfirmation) {
    this.toolConfirmation = Optional.ofNullable(toolConfirmation);
  }

  /**
   * Lists the filenames of the artifacts attached to the current session.
   *
   * @return the list of artifact filenames
   */
  public Single<List<String>> listArtifacts() {
    if (invocationContext.artifactService() == null) {
      throw new IllegalStateException("Artifact service is not initialized.");
    }
    return invocationContext
        .artifactService()
        .listArtifactKeys(
            invocationContext.session().appName(),
            invocationContext.session().userId(),
            invocationContext.session().id())
        .map(ListArtifactsResponse::filenames);
  }

  /** Loads the latest version of an artifact from the service. */
  public Maybe<Part> loadArtifact(String filename) {
    checkArtifactServiceInitialized();
    return invocationContext
        .artifactService()
        .loadArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            filename);
  }

  /** Loads a specific version of an artifact from the service. */
  public Maybe<Part> loadArtifact(String filename, int version) {
    checkArtifactServiceInitialized();
    return invocationContext
        .artifactService()
        .loadArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            filename,
            version);
  }

  private void checkArtifactServiceInitialized() {
    Preconditions.checkState(
        invocationContext.artifactService() != null, "Artifact service is not initialized.");
  }

  /**
   * Saves an artifact and records it as a delta for the current session.
   *
   * @param filename Artifact file name.
   * @param artifact Artifact content to save.
   * @return a {@link Completable} that completes when the artifact is saved.
   * @throws IllegalStateException if the artifact service is not initialized.
   */
  public Completable saveArtifact(String filename, Part artifact) {
    if (invocationContext.artifactService() == null) {
      throw new IllegalStateException("Artifact service is not initialized.");
    }
    return invocationContext
        .artifactService()
        .saveArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            filename,
            artifact)
        .doOnSuccess(version -> this.eventActions.artifactDelta().put(filename, version))
        .ignoreElement();
  }

  /**
   * Requests confirmation for the current function call.
   *
   * @param hint A hint to the user on how to confirm the tool call.
   * @param payload The payload used to confirm the tool call.
   * @throws IllegalStateException if this context has no function call ID.
   */
  public void requestConfirmation(@Nullable String hint, @Nullable Object payload) {
    if (functionCallId.isEmpty()) {
      throw new IllegalStateException("function_call_id is not set.");
    }
    this.eventActions
        .requestedToolConfirmations()
        .put(functionCallId.get(), ToolConfirmation.builder().hint(hint).payload(payload).build());
  }

  /**
   * Requests confirmation for the current function call.
   *
   * @param hint A hint to the user on how to confirm the tool call.
   * @throws IllegalStateException if this context has no function call ID.
   */
  public void requestConfirmation(@Nullable String hint) {
    requestConfirmation(hint, null);
  }

  /**
   * Requests confirmation for the current function call.
   *
   * @throws IllegalStateException if this context has no function call ID.
   */
  public void requestConfirmation() {
    requestConfirmation(null, null);
  }

  /** Searches the memory of the current user. */
  public Single<SearchMemoryResponse> searchMemory(String query) {
    if (invocationContext.memoryService() == null) {
      throw new IllegalStateException("Memory service is not initialized.");
    }
    return invocationContext
        .memoryService()
        .searchMemory(
            invocationContext.session().appName(), invocationContext.session().userId(), query);
  }
}
