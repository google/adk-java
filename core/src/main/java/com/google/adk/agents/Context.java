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

import com.google.adk.annotations.Experimental;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.State;
import com.google.adk.workflow.DynamicNodeFailedException;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeExecutionState;
import com.google.adk.workflow.NodeRunner;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.RunNodeConfig;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The context passed to callbacks, tools, and workflow nodes during an invocation.
 *
 * <p>Agent and model callbacks receive a {@link CallbackContext}, and tools and tool callbacks a
 * {@link com.google.adk.tools.ToolContext}; both extend this class, so a helper that serves both,
 * or a function tool's {@code toolContext} parameter, can take a {@code Context}. {@link
 * #requestConfirmation()} requires a function call ID, which only a tool call has, while {@link
 * #requireNodeState()} and the other workflow node methods require the context of a workflow node.
 */
public class Context extends ReadonlyContext {

  /** The event actions that record this context's changes. */
  protected EventActions eventActions;

  private final State state;
  private final @Nullable String eventId;
  private final @Nullable NodeExecutionState nodeState;
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
    this(invocationContext, eventActions, eventId, /* nodeState= */ null);
  }

  /**
   * Creates the context of the workflow node run whose state is {@code nodeState}. Only the
   * workflow runtime creates a node state.
   */
  @Experimental
  public Context(InvocationContext invocationContext, NodeExecutionState nodeState) {
    this(
        invocationContext,
        /* eventActions= */ null,
        /* eventId= */ null,
        Preconditions.checkNotNull(nodeState));
  }

  private Context(
      InvocationContext invocationContext,
      @Nullable EventActions eventActions,
      @Nullable String eventId,
      @Nullable NodeExecutionState nodeState) {
    super(invocationContext);
    this.eventActions = eventActions != null ? eventActions : EventActions.builder().build();
    this.state = new State(invocationContext.session().state(), this.eventActions.stateDelta());
    this.eventId = eventId;
    this.nodeState = nodeState;
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

  /**
   * Returns the state of the workflow node run this context belongs to.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final NodeExecutionState requireNodeState() {
    Preconditions.checkState(
        nodeState != null, "Only the context of a workflow node has a node state.");
    return nodeState;
  }

  /**
   * Returns the author of the events this workflow node emits, or empty to use the node's name.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final String eventAuthor() {
    return requireNodeState().eventAuthor();
  }

  /**
   * Sets the author of the events this workflow node emits.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final void eventAuthor(String eventAuthor) {
    requireNodeState().eventAuthor(eventAuthor);
  }

  /**
   * Returns this workflow node's output, which is the output of its {@code useAsOutput} child once
   * that child emits one, or empty if it has none.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final Optional<Object> output() {
    return requireNodeState().output();
  }

  /**
   * Returns the routes this workflow node selected, or empty if it made no routing decision. An
   * empty list is a decision that selects no route.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final Optional<ImmutableList<Route>> routes() {
    return requireNodeState().selectedRoutes();
  }

  /**
   * Selects this workflow node's routes, which decide the outgoing edges to follow, replacing any
   * earlier selection.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final void routes(List<? extends Route> routes) {
    requireNodeState().selectRoutes(routes);
  }

  /**
   * Returns the user's answers to the requests for input and long-running calls that this workflow
   * node or its descendants made in an earlier turn, keyed by interrupt ID. A node that reruns on
   * resume reads these answers. A null answer has a null value in the map.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final Map<String, @Nullable Object> resumeInputs() {
    return requireNodeState().resumeInputs();
  }

  /**
   * Runs {@code node} as a child of this workflow node without an input, as {@link #runNode(Node,
   * Object, RunNodeConfig)} does with the default {@link RunNodeConfig}.
   */
  @Experimental
  public final Maybe<Object> runNode(Node node) {
    return runNode(node, /* nodeInput= */ null);
  }

  /**
   * Runs {@code node} as a child of this workflow node, as {@link #runNode(Node, Object,
   * RunNodeConfig)} does with the default {@link RunNodeConfig}.
   *
   * @throws IllegalArgumentException if {@code nodeInput} is a {@link RunNodeConfig}, which belongs
   *     in the three-argument overload.
   */
  @Experimental
  public final Maybe<Object> runNode(Node node, @Nullable Object nodeInput) {
    Preconditions.checkArgument(
        !(nodeInput instanceof RunNodeConfig),
        "Pass the RunNodeConfig as the third argument of runNode, not as the node input.");
    return runNode(node, nodeInput, RunNodeConfig.builder().build());
  }

  /**
   * Returns a {@link Maybe} that, when subscribed, runs {@code node} as a child of this workflow
   * node and emits the child's output, or completes empty if it has none. Include it in this node's
   * run and catch only failures you handle, such as {@link DynamicNodeFailedException}: when the
   * child waits, it fails with an internal {@link RuntimeException} that must propagate to end this
   * node's run, so that it waits too.
   *
   * <p>It fails with {@link DynamicNodeFailedException} if the child fails; with {@link
   * IllegalStateException} if this is not a workflow node's context, if this node's {@link
   * Node#rerunOnResume} is false, or if {@code config} delegates an output that this node already
   * has or has delegated; and with {@link IllegalArgumentException} if the child's name cannot form
   * a node path.
   */
  @Experimental
  public final Maybe<Object> runNode(Node node, @Nullable Object nodeInput, RunNodeConfig config) {
    return NodeRunner.runChild(this, node, nodeInput, config);
  }
}
