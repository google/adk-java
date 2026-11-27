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
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.State;
import com.google.adk.sessions.StateSchemaException;
import com.google.adk.workflow.DynamicNodeFailedException;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeExecutionState;
import com.google.adk.workflow.NodeRunner;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.RunNodeConfig;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
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
    this(invocationContext, eventActions, eventId, /* nodeState= */ null, /* stateSchema= */ null);
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
        Preconditions.checkNotNull(nodeState),
        /* stateSchema= */ null);
  }

  /**
   * Initializes a context that is not a workflow node's and checks its state writes against {@code
   * stateSchema}, if given.
   */
  Context(
      InvocationContext invocationContext,
      @Nullable EventActions eventActions,
      @Nullable String eventId,
      @Nullable Schema stateSchema) {
    this(invocationContext, eventActions, eventId, /* nodeState= */ null, stateSchema);
  }

  private Context(
      InvocationContext invocationContext,
      @Nullable EventActions eventActions,
      @Nullable String eventId,
      @Nullable NodeExecutionState nodeState,
      @Nullable Schema stateSchema) {
    super(invocationContext);
    this.eventActions = eventActions != null ? eventActions : EventActions.builder().build();
    Map<String, Object> sessionState = invocationContext.session().state();
    Map<String, Object> stateDelta = this.eventActions.stateDelta();
    Schema schema = nodeState != null ? nodeState.stateSchema().orElse(null) : stateSchema;
    if (schema != null) {
      // EventActions keeps its state delta in a ConcurrentMap.
      ConcurrentMap<String, Object> concurrentDelta = (ConcurrentMap<String, Object>) stateDelta;
      // DeltaView drops State's raw delta writes so only CheckedStateMap's converted ones land.
      this.state =
          State.delegatingCompute(
              new CheckedStateMap(new ReadOnlyViewsMap(sessionState), concurrentDelta, schema),
              new DeltaView(concurrentDelta));
    } else if (nodeState != null) {
      this.state = new State(new ReadOnlyViewsMap(sessionState), stateDelta);
    } else {
      this.state = new State(sessionState, stateDelta);
    }
    this.eventId = eventId;
    this.nodeState = nodeState;
  }

  /**
   * Returns the delta-aware state of the current context. In the context a workflow passes to a
   * node, or under a state schema, writing through its map views or {@link State#clear()} throws
   * {@link UnsupportedOperationException}; see {@link State}. Under the node's {@link
   * Node#stateSchema()}, which a tool node also applies to its tool's context, writes store
   * converted values and throw {@link StateSchemaException} on a mismatch or undeclared key. A
   * stored value reads back in the form a stored session gives: lists and maps unmodifiable, a
   * record as a map, an enum by its name, a small whole number as an {@code Integer}.
   */
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
   * Returns the number of the attempt this workflow node is on, counting from 1; each retry adds
   * one.
   *
   * @throws IllegalStateException if this is not the context of a workflow node.
   */
  @Experimental
  public final int attemptCount() {
    return requireNodeState().attemptCount();
  }

  /**
   * Sends {@code event} from this workflow node's {@code FunctionNode} body, after the events of
   * earlier calls and before the output. It returns once the run has stored the event and handed it
   * to the run's caller, and any thread of the body may call it. On a {@code Runner} that runs work
   * inline, the node needs a {@code FunctionNode.Builder#scheduler} that does not, such as {@code
   * Schedulers.io()}. The {@link com.google.adk.workflow.FunctionNode} documentation gives the
   * rules for state, threads, events, cancellation and resumes.
   *
   * @throws IllegalArgumentException if {@code event} has an output, or has the ID of an event
   *     another {@code emit} call still waits on
   * @throws StateSchemaException if the node's state schema rejects the event's state delta; the
   *     node then fails with it even if the body catches it; later calls while the body runs throw
   *     one with the same message, and a later {@code runNode} starts no child and fails with one.
   *     The rejection does not interrupt the body's thread: it keeps running until it returns,
   *     pauses or throws, and state it writes meanwhile is still saved, but the node fails with the
   *     rejection, with any other exception the body throws added as suppressed.
   * @throws IllegalStateException if no {@code FunctionNode} body of this node is running
   * @throws CancellationException if the node is canceled, or the calling thread is interrupted
   *     while this call waits; the thread stays interrupted
   */
  @Experimental
  public final void emit(Event event) {
    Preconditions.checkNotNull(event);
    if (Strings.isNullOrEmpty(event.id())) {
      event = event.toBuilder().id(invocationContext.newUuid()).build();
    }
    if (nodeState == null || !nodeState.emitFromBody(event)) {
      throw new IllegalStateException(
          "Context.emit works only while the FunctionNode body of this context's node runs; a"
              + " custom node sends events as items of its Flowable, and a tool or callback"
              + " returns its result.");
    }
  }

  /** Sends {@code message} as an event without an output, as {@link #emit(Event)} does. */
  @Experimental
  public final void emit(Content message) {
    Preconditions.checkNotNull(message);
    emit(
        Event.builder()
            .author("")
            .timestamp(invocationContext.now().toEpochMilli())
            .content(message)
            .build());
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
   * run: when the child waits, the {@code Maybe} fails with an internal {@link Error} so that this
   * node waits too, which {@code catch (Exception e)} lets through but {@code catch (Throwable t)}
   * and Rx error handlers such as {@code onErrorResumeNext} stop.
   *
   * <p>When {@code node} is a chat or task agent that hands off to another agent in an agent tree
   * with modes, that agent runs as a node too, and the result is that of the last agent to run. A
   * single-turn agent runs its target inside its own run.
   *
   * <p>It fails with {@link DynamicNodeFailedException} if the child fails, as when an agent's
   * handoff is invalid: to the agent itself, to a task or single-turn sub-agent or peer, to a
   * parent or peer its flags forbid, or to an agent outside its sub-agents, parent and peers. It
   * fails with {@link IllegalStateException} if this is not a workflow node's context, if this
   * node's {@link Node#rerunOnResume} is false, or if {@code config} delegates an output that this
   * node already has or has delegated; with {@link StateSchemaException}, starting no child, once
   * an {@link #emit} in this node's body was rejected; with {@link IllegalArgumentException} if a
   * child or handoff target name cannot form a node path, or if the child's input schema rejects
   * {@code nodeInput}; or with {@link com.google.adk.workflow.NodeInputValidationException} (an
   * {@code IllegalArgumentException}) if the input port or builder input schema of a {@link
   * com.google.adk.workflow.FunctionNode} child rejects {@code nodeInput}. A child whose input is
   * rejected does not start.
   */
  @Experimental
  public final Maybe<Object> runNode(Node node, @Nullable Object nodeInput, RunNodeConfig config) {
    return NodeRunner.runChild(this, node, nodeInput, config);
  }

  /** The session state behind a workflow node's {@link State}, with read-only views. */
  private static final class ReadOnlyViewsMap extends ForwardingConcurrentMap<String, Object> {
    private final ConcurrentMap<String, Object> sessionState;
    // unmodifiableMap also wraps each entry, so Entry.setValue throws.
    private final Map<String, Object> readOnly;

    ReadOnlyViewsMap(Map<String, Object> sessionState) {
      // Session.state() is a State; any other Map is copied, as State(Map, Map) does.
      this.sessionState =
          sessionState instanceof ConcurrentMap<String, Object> concurrentState
              ? concurrentState
              : new State(sessionState);
      this.readOnly = Collections.unmodifiableMap(this.sessionState);
    }

    @Override
    protected ConcurrentMap<String, Object> delegate() {
      return sessionState;
    }

    @Override
    public void clear() {
      throw new UnsupportedOperationException(
          "A workflow node cannot clear its state; remove each key with State.remove.");
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
      return readOnly.entrySet();
    }

    @Override
    public Set<String> keySet() {
      return readOnly.keySet();
    }

    @Override
    public Collection<Object> values() {
      return readOnly.values();
    }
  }

  /**
   * A map backing a workflow node's {@link State} that checks and converts each written value once,
   * storing the same converted value in both the session state and the event delta.
   */
  private static final class CheckedStateMap extends ForwardingConcurrentMap<String, Object> {
    private static final Object RETRY = new Object();

    private final ConcurrentMap<String, Object> delegate;
    private final ConcurrentMap<String, Object> stateDelta;
    private final Schema stateSchema;

    CheckedStateMap(
        ConcurrentMap<String, Object> delegate,
        ConcurrentMap<String, Object> stateDelta,
        Schema stateSchema) {
      this.delegate = delegate;
      this.stateDelta = stateDelta;
      this.stateSchema = stateSchema;
    }

    private Object check(String key, Object value) {
      return NodeExecutionState.checkStateValue(stateSchema, key, value);
    }

    @Override
    protected ConcurrentMap<String, Object> delegate() {
      return delegate;
    }

    @Override
    public @Nullable Object put(String key, Object value) {
      Object checked = check(key, value);
      Object previous = delegate.put(key, checked);
      stateDelta.put(key, checked);
      return previous;
    }

    @Override
    public @Nullable Object putIfAbsent(String key, Object value) {
      Object checked = check(key, value);
      Object existing = delegate.putIfAbsent(key, checked);
      if (existing == null) {
        stateDelta.put(key, checked);
      }
      return existing;
    }

    @Override
    public void putAll(Map<? extends String, ? extends Object> map) {
      // Checks every entry before writing any, so a rejected entry leaves the state unchanged.
      Map<String, Object> checked = new LinkedHashMap<>();
      map.forEach((key, value) -> checked.put(key, check(key, value)));
      delegate.putAll(checked);
      stateDelta.putAll(checked);
    }

    @Override
    public @Nullable Object remove(Object key) {
      Object removed = delegate.remove(key);
      if (removed != null) {
        stateDelta.put((String) key, State.REMOVED);
      }
      return removed;
    }

    @Override
    public boolean remove(Object key, Object value) {
      boolean removed = delegate.remove(key, value);
      if (removed) {
        stateDelta.put((String) key, State.REMOVED);
      }
      return removed;
    }

    @Override
    public boolean replace(String key, Object oldValue, Object newValue) {
      Preconditions.checkNotNull(oldValue);
      Object checked = check(key, newValue);
      boolean replaced = delegate.replace(key, oldValue, checked);
      if (replaced) {
        stateDelta.put(key, checked);
      }
      return replaced;
    }

    @Override
    public @Nullable Object replace(String key, Object value) {
      Object checked = check(key, value);
      Object previous = delegate.replace(key, checked);
      if (previous != null) {
        stateDelta.put(key, checked);
      }
      return previous;
    }

    // Runs user functions and schema checks outside the backing map's lock, retrying on races.
    @Override
    public @Nullable Object compute(
        String key,
        BiFunction<? super String, ? super @Nullable Object, ? extends @Nullable Object>
            remapping) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(remapping);
      while (true) {
        Object oldValue = delegate.get(key);
        Object result = update(key, oldValue, remapping.apply(key, oldValue));
        if (result != RETRY) {
          return result;
        }
      }
    }

    @Override
    public @Nullable Object computeIfAbsent(
        String key, Function<? super String, ? extends @Nullable Object> mapping) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(mapping);
      Object existing = delegate.get(key);
      if (existing != null) {
        return existing;
      }
      Object newValue = mapping.apply(key);
      if (newValue == null) {
        return null;
      }
      Object checked = check(key, newValue);
      Object raced = delegate.putIfAbsent(key, checked);
      if (raced != null) {
        return raced;
      }
      stateDelta.put(key, checked);
      return checked;
    }

    @Override
    public @Nullable Object computeIfPresent(
        String key,
        BiFunction<? super String, ? super Object, ? extends @Nullable Object> remapping) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(remapping);
      while (true) {
        Object oldValue = delegate.get(key);
        if (oldValue == null) {
          return null;
        }
        Object result = update(key, oldValue, remapping.apply(key, oldValue));
        if (result != RETRY) {
          return result;
        }
      }
    }

    @Override
    public @Nullable Object merge(
        String key,
        Object value,
        BiFunction<? super Object, ? super Object, ? extends @Nullable Object> remapping) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(value);
      Preconditions.checkNotNull(remapping);
      while (true) {
        Object oldValue = delegate.get(key);
        Object next = oldValue == null ? value : remapping.apply(oldValue, value);
        Object result = update(key, oldValue, next);
        if (result != RETRY) {
          return result;
        }
      }
    }

    private @Nullable Object update(
        String key, @Nullable Object oldValue, @Nullable Object newValue) {
      if (newValue == null) {
        if (oldValue == null) {
          return null;
        }
        if (!delegate.remove(key, oldValue)) {
          return RETRY;
        }
        stateDelta.put(key, State.REMOVED);
        return null;
      }
      Object checked = check(key, newValue);
      boolean written =
          oldValue == null
              ? delegate.putIfAbsent(key, checked) == null
              : delegate.replace(key, oldValue, checked);
      if (!written) {
        return RETRY;
      }
      stateDelta.put(key, checked);
      return checked;
    }
  }

  /**
   * Keeps {@link State#hasDelta()} backed by the real delta while letting {@link CheckedStateMap}
   * perform the single write of each converted entry.
   */
  private static final class DeltaView extends ForwardingConcurrentMap<String, Object> {
    private final ConcurrentMap<String, Object> stateDelta;

    DeltaView(ConcurrentMap<String, Object> stateDelta) {
      this.stateDelta = stateDelta;
    }

    @Override
    protected ConcurrentMap<String, Object> delegate() {
      return stateDelta;
    }

    @Override
    public @Nullable Object put(String key, Object value) {
      return null;
    }

    @Override
    public void putAll(Map<? extends String, ? extends Object> map) {}
  }
}
