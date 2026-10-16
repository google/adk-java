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

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.FinishReason;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The runner for one workflow node. It stamps every emitted event with its author, node path, and
 * branch before forwarding it, and records the node's output, routes, interrupts, and failure on
 * the node state of the returned context.
 */
@Experimental
final class NodeRunner {

  /** Sink that receives the events of a workflow run. */
  @Experimental
  interface EventSink {

    /**
     * Sends {@code event} and returns a {@link Completable} that completes once the event is
     * processed. The Completable must not fail; a consumer that cannot process an event stops the
     * run by canceling it.
     */
    Completable send(Event event);
  }

  private final Node node;
  private final InvocationContext invocationContext;
  private final EventSink eventSink;
  private final @Nullable Context parent;
  private final String runId;
  private final boolean useSubBranch;
  private final @Nullable String overrideBranch;

  private NodeRunner(Builder builder) {
    checkState(builder.node != null, "A node runner needs a node.");
    if (builder.parent != null) {
      checkState(
          builder.invocationContext == null && builder.eventSink == null,
          "A node runner with a parent takes its invocation context and event sink from it.");
      this.invocationContext = builder.parent.invocationContext();
      this.eventSink = builder.parent.requireNodeState().eventSink();
    } else {
      checkState(
          builder.invocationContext != null && builder.eventSink != null,
          "A node runner without a parent needs an invocation context and an event sink.");
      this.invocationContext = builder.invocationContext;
      this.eventSink = builder.eventSink;
    }
    this.node = builder.node;
    this.parent = builder.parent;
    this.runId = builder.runId;
    this.useSubBranch = builder.useSubBranch;
    this.overrideBranch = builder.overrideBranch;
  }

  /** Returns a builder for a runner of one node. */
  static Builder builder() {
    return new Builder();
  }

  /**
   * Runs the node on {@code nodeInput} and returns its context when the node finishes. An exception
   * from the node is recorded on the context's node state rather than signaled, while an {@code
   * Error} propagates.
   */
  Single<Context> run(@Nullable Object nodeInput) {
    return Single.defer(
        () -> {
          Context context =
              new Context(
                  childInvocationContext(),
                  NodeExecutionState.builder()
                      .node(node)
                      .eventSink(eventSink)
                      .parent(parent)
                      .runId(runId)
                      .build());
          return new Activation(context).run(nodeInput).toSingleDefault(context);
        });
  }

  private InvocationContext childInvocationContext() {
    if (useSubBranch) {
      String base =
          overrideBranch != null ? overrideBranch : invocationContext.branch().orElse(null);
      return invocationContext.toBuilder()
          .branch(appendSegment(base, node.name(), runId, /* separator= */ '.'))
          .build();
    }
    if (overrideBranch != null) {
      return invocationContext.toBuilder().branch(overrideBranch).build();
    }
    return invocationContext;
  }

  /**
   * One run of the node, during which the node is asked for each event only after its previous
   * event is processed. Events from nested nodes pass through without reapplying their routing or
   * transfers. When the node finishes, a final event carries any output, routes, or deltas not
   * carried by an earlier event.
   */
  private final class Activation {
    private final Context context;
    private final NodeExecutionState nodeState;

    Activation(Context context) {
      this.context = context;
      this.nodeState = context.requireNodeState();
    }

    Completable run(@Nullable Object nodeInput) {
      return asBaseNode(node)
          .run(context, nodeInput)
          // Avoid concatMapCompletable: its prefetch would let the node run ahead of the sink.
          .flatMapCompletable(this::dispatch, /* delayErrors= */ true, /* maxConcurrency= */ 1)
          .andThen(Completable.defer(this::flushPending))
          .onErrorResumeNext(this::onError);
    }

    private Completable onError(Throwable error) {
      // Only an Exception fails the node; an Error is fatal and propagates.
      return error instanceof Exception exception ? fail(exception) : Completable.error(error);
    }

    private Completable dispatch(Event event) {
      track(event);
      return send(event);
    }

    /** Records the output, interrupts, and routing carried by {@code event} on the node state. */
    private void track(Event event) {
      if (event.output().isPresent()) {
        nodeState.produceOutput(event.output().get());
      } else if (isMessageAsOutput(event)) {
        nodeState.produceOutput(event.content().get());
      }
      event.longRunningToolIds().ifPresent(nodeState::addInterruptIds);
      // An event of a nested node was already acted on at that node's level.
      if (isNullOrEmpty(event.author()) || event.author().equals(node.name())) {
        event
            .actions()
            .route()
            .ifPresent(
                route -> {
                  nodeState.selectRoutes(route);
                  nodeState.markRoutesEmitted();
                });
        event.actions().transferToAgent().ifPresent(context.eventActions()::setTransferToAgent);
      }
    }

    /**
     * Stamps {@code event}, moves the pending deltas onto it unless it is partial, and sends it.
     */
    private Completable send(Event event) {
      boolean messageAsOutput = isMessageAsOutput(event);
      // A message-as-output event's content is its output, so drop the repeated output field.
      Event stamped = stamp(messageAsOutput ? event.toBuilder().output(null).build() : event);
      Event outgoing = stamped.partial().orElse(false) ? stamped : withPendingDeltas(stamped);
      return eventSink
          .send(outgoing)
          .doOnComplete(
              () -> {
                if (outgoing.output().isPresent() || messageAsOutput) {
                  nodeState.markOutputEmitted();
                }
              });
    }

    /** Sends a final event carrying any output, routes, or deltas that no earlier event carried. */
    private Completable flushPending() {
      boolean pendingOutput = nodeState.output().isPresent() && !nodeState.outputEmitted();
      boolean pendingRoute = nodeState.selectedRoutes().isPresent() && !nodeState.routesEmitted();
      EventActions actions = context.eventActions();
      boolean pendingDeltas = !actions.stateDelta().isEmpty() || !actions.artifactDelta().isEmpty();
      if (!pendingOutput && !pendingRoute && !pendingDeltas) {
        return Completable.complete();
      }
      Event event =
          withPendingDeltas(
              stamp(
                  newEvent()
                      .output(pendingOutput ? nodeState.output().get() : null)
                      .actions(
                          EventActions.builder()
                              .route(pendingRoute ? nodeState.selectedRoutes().get() : null)
                              .build())
                      .build()));
      return eventSink
          .send(event)
          .doOnComplete(
              () -> {
                if (pendingOutput) {
                  nodeState.markOutputEmitted();
                }
                if (pendingRoute) {
                  nodeState.markRoutesEmitted();
                }
              });
    }

    /**
     * Sends an error event for {@code failure} with any pending deltas to preserve state changes
     * made before the failure, then records the failure on the node state.
     */
    private Completable fail(Exception failure) {
      Event errorEvent =
          withPendingDeltas(
              stamp(
                  newEvent()
                      .errorCode(new FinishReason(errorTypeName(failure)))
                      .errorMessage(nullToEmpty(failure.getMessage()))
                      .build()));
      return eventSink.send(errorEvent).doOnComplete(() -> nodeState.failure(failure));
    }

    /**
     * Moves the context's pending deltas onto a copy of {@code event} so each is sent only once.
     */
    private Event withPendingDeltas(Event event) {
      Map<String, Object> stateDelta = context.eventActions().stateDelta();
      Map<String, Integer> artifactDelta = context.eventActions().artifactDelta();
      if (stateDelta.isEmpty() && artifactDelta.isEmpty()) {
        return event;
      }
      EventActions actions = event.actions().toBuilder().build();
      actions.stateDelta().putAll(stateDelta);
      actions.artifactDelta().putAll(artifactDelta);
      stateDelta.clear();
      artifactDelta.clear();
      return event.toBuilder().actions(actions).build();
    }

    /** Attributes {@code event} to its author and to this run of the node. */
    private Event stamp(Event event) {
      InvocationContext eventContext = context.invocationContext();
      String author = nodeState.eventAuthor();
      NodeInfo nodeInfo = event.nodeInfo().orElseGet(() -> NodeInfo.builder().build());
      boolean carriesOutput = event.output().isPresent() || isMessageAsOutput(event);
      return event.toBuilder()
          .author(author.isEmpty() ? node.name() : author)
          .invocationId(eventContext.invocationId())
          .branch(event.branch().orElse(eventContext.branch().orElse(null)))
          .nodeInfo(
              nodeInfo.toBuilder()
                  .path(nodeState.nodePath())
                  .outputFor(
                      carriesOutput ? ImmutableList.of(nodeState.nodePath()) : nodeInfo.outputFor())
                  .build())
          .build();
    }

    private Event.Builder newEvent() {
      InvocationContext eventContext = context.invocationContext();
      return Event.builder()
          .id(eventContext.newUuid())
          .timestamp(eventContext.now().toEpochMilli());
    }
  }

  /** Appends a {@code name@runId} segment to {@code base}, joined by {@code separator}. */
  static String appendSegment(@Nullable String base, String name, String runId, char separator) {
    String segment = name + "@" + runId;
    return isNullOrEmpty(base) ? segment : base + separator + segment;
  }

  /** Returns whether {@code event} is a final event whose content is the node's output. */
  private static boolean isMessageAsOutput(Event event) {
    return !event.partial().orElse(false)
        && event.content().isPresent()
        && event.nodeInfo().map(NodeInfo::messageAsOutput).orElse(false);
  }

  /** Returns the simple name of the failure's type, or its full name for an anonymous class. */
  private static String errorTypeName(Exception failure) {
    String name = failure.getClass().getSimpleName();
    return name.isEmpty() ? failure.getClass().getName() : name;
  }

  /** Adapts any node to a {@link BaseNode}, whose {@link BaseNode#run} turns values into events. */
  private static BaseNode asBaseNode(Node node) {
    if (node instanceof BaseNode baseNode) {
      return baseNode;
    }
    return new BaseNode(node.name(), node.description()) {
      @Override
      public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
        return node.runNode(context, nodeInput);
      }
    };
  }

  /**
   * Builder for {@link NodeRunner}. A runner at the root of a run takes an invocation context and
   * an event sink, while a runner under a parent node takes both from the parent's context.
   */
  @Experimental
  static final class Builder {
    private @Nullable Node node;
    private @Nullable InvocationContext invocationContext;
    private @Nullable EventSink eventSink;
    private @Nullable Context parent;
    private String runId = "1";
    private boolean useSubBranch;
    private @Nullable String overrideBranch;

    private Builder() {}

    /** Sets the node to run. */
    @CanIgnoreReturnValue
    Builder node(Node node) {
      this.node = checkNotNull(node);
      return this;
    }

    /** Sets the invocation context of a run at the root. */
    @CanIgnoreReturnValue
    Builder invocationContext(InvocationContext invocationContext) {
      this.invocationContext = checkNotNull(invocationContext);
      return this;
    }

    /** Sets the sink that receives the events of a run at the root. */
    @CanIgnoreReturnValue
    Builder eventSink(EventSink eventSink) {
      this.eventSink = checkNotNull(eventSink);
      return this;
    }

    /** Sets the context of the node that runs this node. */
    @CanIgnoreReturnValue
    Builder parent(Context parent) {
      this.parent = checkNotNull(parent);
      return this;
    }

    /** Sets the ID of this run among the node's runs, which defaults to {@code "1"}. */
    @CanIgnoreReturnValue
    Builder runId(String runId) {
      this.runId = checkNotNull(runId);
      return this;
    }

    /**
     * Sets whether the node runs on a sub-branch of its base branch, as a node fanned out from its
     * parent does. Branches isolate one parallel branch's events from a sibling's model history.
     */
    @CanIgnoreReturnValue
    Builder useSubBranch(boolean useSubBranch) {
      this.useSubBranch = useSubBranch;
      return this;
    }

    /** Sets the branch that replaces the inherited one, or null to keep the inherited branch. */
    @CanIgnoreReturnValue
    Builder overrideBranch(@Nullable String overrideBranch) {
      this.overrideBranch = overrideBranch;
      return this;
    }

    /**
     * Builds the runner.
     *
     * @throws IllegalStateException if no node is set, if neither a parent nor both an invocation
     *     context and an event sink are set, or if a parent is set together with either of them.
     */
    NodeRunner build() {
      return new NodeRunner(this);
    }
  }
}
