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

import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.State;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;

/** The context of various callbacks for an agent invocation. */
public class CallbackContext extends ReadonlyContext {

  protected EventActions eventActions;
  private final State state;
  private final String eventId;

  /**
   * Initializes callback context.
   *
   * @param invocationContext Current invocation context.
   * @param eventActions Callback event actions.
   */
  public CallbackContext(InvocationContext invocationContext, EventActions eventActions) {
    this(invocationContext, eventActions, null);
  }

  /**
   * Initializes callback context.
   *
   * @param invocationContext Current invocation context.
   * @param eventActions Callback event actions.
   * @param eventId The ID of the event associated with this context.
   */
  public CallbackContext(
      InvocationContext invocationContext, EventActions eventActions, String eventId) {
    super(invocationContext);
    this.eventActions = eventActions != null ? eventActions : EventActions.builder().build();
    this.state = new State(invocationContext.session().state(), this.eventActions.stateDelta());
    this.eventId = eventId;
  }

  /** Returns the delta-aware state of the current callback. */
  @Override
  public State state() {
    return state;
  }

  /** Returns the EventActions associated with this context. */
  public EventActions eventActions() {
    return eventActions;
  }

  /** Returns the ID of the event associated with this context. */
  public String eventId() {
    return eventId;
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
    return loadArtifact(filename, Optional.empty());
  }

  /** Loads a specific version of an artifact from the service. */
  public Maybe<Part> loadArtifact(String filename, int version) {
    return loadArtifact(filename, Optional.of(version));
  }

  /**
   * @deprecated Use {@link #loadArtifact(String)} or {@link #loadArtifact(String, int)} instead.
   */
  @Deprecated
  public Maybe<Part> loadArtifact(String filename, Optional<Integer> version) {
    if (invocationContext.artifactService() == null) {
      throw new IllegalStateException("Artifact service is not initialized.");
    }
    return invocationContext
        .artifactService()
        .loadArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            filename,
            version);
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
}
