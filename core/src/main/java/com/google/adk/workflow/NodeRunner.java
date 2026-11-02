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
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.telemetry.Tracing;
import com.google.adk.workflow.ResumeScan.Interception;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.FinishReason;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The runner for one workflow node, which times out an attempt that exceeds the node's optional
 * timeout and retries the node as its retry policy allows. It stamps every emitted event with its
 * author, node path, and branch before forwarding it, and records the node's output, routes,
 * interrupts, and failure on the node state of the returned context. {@link #runRoot} runs a node
 * as the root of an invocation, and {@link #runChild} runs one as a child that another node starts
 * itself.
 */
@Experimental
public final class NodeRunner {

  /** Sink that receives the events of a workflow run. */
  @Experimental
  interface EventSink {

    /**
     * Sends {@code event} and returns a {@link Completable} that completes once the event is
     * processed. When {@code nodeFailure} is not null, {@code event} is the error event of a node
     * that failed with {@code nodeFailure}. The Completable must not fail; a consumer that cannot
     * process an event stops the run by canceling it.
     */
    Completable send(Event event, @Nullable Exception nodeFailure);
  }

  private final Node node;
  private final InvocationContext invocationContext;
  private final EventSink eventSink;
  private final @Nullable Context parent;
  private final String runId;
  private final boolean useAsOutput;
  private final boolean useSubBranch;
  private final @Nullable String overrideBranch;
  private final Map<String, @Nullable Object> resumeInputs;

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
    this.useAsOutput = builder.useAsOutput;
    this.useSubBranch = builder.useSubBranch;
    this.overrideBranch = builder.overrideBranch;
    this.resumeInputs = builder.resumeInputs;
  }

  /** Returns a builder for a runner of one node. */
  static Builder builder() {
    return new Builder();
  }

  /**
   * Runs {@code node} as the root of {@code context} on the invocation's user content, passing each
   * event through {@code processEvent} in order; only {@link com.google.adk.runner.Runner} should
   * call this method. The node is asked for its next event, and its failure is signaled, only after
   * the previous non-partial event is processed and emitted, so a failure follows the error event
   * that reports it. If processing an event fails with an exception, the failures reported by
   * unprocessed error events are attached to it as suppressed.
   */
  @Experimental
  public static Flowable<Event> runRoot(
      Node node,
      InvocationContext context,
      Function<? super Event, ? extends Single<Event>> processEvent) {
    checkNotNull(node);
    checkNotNull(context);
    checkNotNull(processEvent);
    return Flowable.defer(
        () -> {
          // App checks a root node's name only against the agent-name rule, which allows '.'.
          NodeNames.validate(node.name());
          FlowableProcessor<QueuedEvent> queue =
              UnicastProcessor.<QueuedEvent>create().toSerialized();
          Queue<Exception> unprocessedFailures = new ConcurrentLinkedQueue<>();
          EventSink sink =
              (event, nodeFailure) -> {
                CompletableSubject processed = CompletableSubject.create();
                if (nodeFailure != null) {
                  unprocessedFailures.add(nodeFailure);
                }
                queue.onNext(new QueuedEvent(event, processed, nodeFailure));
                return event.partial().orElse(false) ? Completable.complete() : processed;
              };
          Completable nodeRun =
              builder()
                  .node(node)
                  .invocationContext(context)
                  .eventSink(sink)
                  .build()
                  .run(context.userContent().orElse(null))
                  .doOnSuccess(
                      nodeContext ->
                          nodeContext
                              .requireNodeState()
                              .failure()
                              .ifPresentOrElse(queue::onError, queue::onComplete))
                  .doOnError(queue::onError)
                  .ignoreElement()
                  .onErrorComplete();
          return queue
              .concatMap(
                  queued ->
                      processEvent
                          .apply(queued.event())
                          .doOnError(
                              error -> {
                                // As in Kotlin, keep the failures of unprocessed error events.
                                if (error instanceof Exception) {
                                  unprocessedFailures.forEach(error::addSuppressed);
                                }
                              })
                          .toFlowable()
                          // The node proceeds only after the caller receives the processed event.
                          .doAfterNext(
                              unused -> {
                                if (queued.nodeFailure() != null) {
                                  unprocessedFailures.remove(queued.nodeFailure());
                                }
                                queued.processed().onComplete();
                              }))
              .mergeWith(nodeRun);
        });
  }

  /**
   * Runs {@code node} as a child of the node whose context is {@code parent}, as {@link
   * Context#runNode(Node, Object, RunNodeConfig)} describes. Nodes call that method; this one is
   * public only so {@link Context} can call it from another package.
   */
  @Experimental
  public static Maybe<Object> runChild(
      Context parent, Node node, @Nullable Object nodeInput, RunNodeConfig config) {
    checkNotNull(parent);
    checkNotNull(node);
    checkNotNull(config);
    return Maybe.defer(() -> startChild(parent, node, nodeInput, config));
  }

  /** Runs {@code node} as a child of {@code parent} and turns the child's run into its result. */
  private static Maybe<Object> startChild(
      Context parent, Node node, @Nullable Object nodeInput, RunNodeConfig config) {
    NodeExecutionState parentState = parent.requireNodeState();
    checkState(
        parentState.node().rerunOnResume(),
        "Node '%s' must rerun on resume to run child nodes: a child it runs may be interrupted, and"
            + " the workflow reruns the caller on resume to collect the child's output.",
        parentState.node().name());
    // No graph validates a child that a node runs itself, so check that it keeps paths intact.
    NodeNames.validate(node.name());
    // A workflow takes its output from its terminal node, so it never delegates it to a child.
    boolean delegatesOutput = config.useAsOutput() && !(parentState.node() instanceof Workflow);
    if (delegatesOutput) {
      parentState.claimOutputDelegation();
    }
    String runId = config.runId().orElseGet(() -> parentState.nextChildRunId(node.name()));
    return sharedChildRun(parent, node, nodeInput, runId, config)
        .doOnError(
            error -> {
              if (delegatesOutput) {
                parentState.releaseOutputDelegation();
              }
            })
        .flatMapMaybe(child -> childResult(parent, child, delegatesOutput, config.raiseOnWait()));
  }

  /**
   * Returns the run of {@code node} under {@code runId} shared by the caller's calls with that run
   * ID, starting it if needed. A run that fails or waits for an output it did not produce is not
   * shared, so a later call runs the child again. The run is canceled only once every call waiting
   * on it is canceled.
   */
  private static Single<Context> sharedChildRun(
      Context parent, Node node, @Nullable Object nodeInput, String runId, RunNodeConfig config) {
    return Single.defer(
        () -> {
          ConcurrentMap<String, SharedChildRun> childRuns = parent.requireNodeState().childRuns();
          String childKey = segment(node.name(), runId);
          SharedChildRun created = new SharedChildRun();
          SharedChildRun existing = childRuns.putIfAbsent(childKey, created);
          SharedChildRun run = existing != null ? existing : created;
          if (!run.join()) {
            // The run was canceled as this call joined it, so this call starts a new run.
            childRuns.remove(childKey, run);
            return sharedChildRun(parent, node, nodeInput, runId, config);
          }
          Single<Context> call =
              run.result.doOnDispose(
                  () -> {
                    if (run.leave()) {
                      childRuns.remove(childKey, run);
                    }
                  });
          if (existing != null) {
            return call;
          }
          // Start after the call subscribes, so a cancel reaches a child blocked as it starts.
          return Single.create(
              emitter -> {
                emitter.setDisposable(call.subscribe(emitter::onSuccess, emitter::onError));
                run.start(
                    // Defer so a throw from scan or intercept fails the run instead of leaking it.
                    Single.defer(() -> runOrReplayChild(parent, node, nodeInput, runId, config))
                        .doOnSuccess(
                            child -> {
                              if (!keepsItsRun(child)) {
                                childRuns.remove(childKey, run);
                              }
                            })
                        .doOnError(error -> childRuns.remove(childKey, run)));
              });
        });
  }

  /**
   * Runs {@code node} as a child of {@code parent}, or replays the result it recorded in an earlier
   * turn under the same run ID.
   */
  private static Single<Context> runOrReplayChild(
      Context parent, Node node, @Nullable Object nodeInput, String runId, RunNodeConfig config) {
    NodeExecutionState parentState = parent.requireNodeState();
    String childKey = segment(node.name(), runId);
    ResumeScan.RecoveredNode recovered =
        parentState.recoveredChildren(parent.invocationContext()).get(childKey);
    Interception interception =
        ResumeScan.intercept(
            node,
            recovered,
            /* dynamic= */ true,
            parentState.childCanceledMidRun(childKey),
            parentState.childFailedThisTurn(childKey));
    if (!interception.shouldRun()) {
      // The caller reads only the replayed result, not the branch, so the parent's context will do.
      return Single.just(
          ResumeScan.replayContext(
              parent, parent.invocationContext(), node, runId, config.useAsOutput(), interception));
    }
    return builder()
        .node(node)
        .parent(parent)
        .runId(runId)
        .useAsOutput(config.useAsOutput())
        .useSubBranch(config.useSubBranch())
        .overrideBranch(config.overrideBranch().orElse(null))
        .resumeInputs(interception.resumeInputs())
        .build()
        .run(nodeInput);
  }

  /**
   * Returns whether a later call with the same run ID shares this finished run of a child: a failed
   * child runs again, as does one that waits for an output and left no outcome.
   */
  private static boolean keepsItsRun(Context child) {
    NodeExecutionState state = child.requireNodeState();
    if (state.failure().isPresent()) {
      return false;
    }
    return !state.interruptIds().isEmpty()
        || state.output().isPresent()
        || state.selectedRoutes().isPresent()
        || child.eventActions().transferToAgent().isPresent()
        || !state.node().waitForOutput();
  }

  /**
   * Returns the child's output, or fails with the child's failure or with an interrupt, which the
   * caller takes on as its own.
   */
  private static Maybe<Object> childResult(
      Context parent, Context child, boolean delegatesOutput, boolean raiseOnWait) {
    NodeExecutionState parentState = parent.requireNodeState();
    NodeExecutionState childState = child.requireNodeState();
    if (child.eventActions().skipSummarization().orElse(false)) {
      parent.eventActions().setSkipSummarization(true);
    }
    Optional<Exception> failure = childState.failure();
    if (failure.isPresent()) {
      // A delegate that fails before it emits an output leaves the caller free to produce one.
      if (delegatesOutput) {
        parentState.releaseOutputDelegation();
      }
      return Maybe.error(
          new DynamicNodeFailedException(
              childState.node().name(), failure.get(), childState.failureNodePath().orElseThrow()));
    }
    ImmutableSet<String> interruptIds = childState.interruptIds();
    if (!interruptIds.isEmpty()) {
      // The caller waits as well, and reruns on resume to collect the child's output.
      parentState.addInterruptIds(interruptIds);
      return Maybe.error(new NodeInterruptedException());
    }
    Node childNode = childState.node();
    if (raiseOnWait
        && childState.output().isEmpty()
        && child.eventActions().transferToAgent().isEmpty()
        && (childNode instanceof Workflow || childNode.waitForOutput())) {
      return Maybe.error(new NodeInterruptedException());
    }
    // No event of this run carries a delegate's replayed or shared output, so the caller emits it.
    Optional<Object> output = childState.output();
    boolean emittedForCaller =
        childState.outputEmitted() && childState.outputParent() == parentState;
    if (delegatesOutput && output.isPresent() && !emittedForCaller) {
      parentState.adoptReplayedOutput(output.get());
    }
    return Maybe.fromOptional(output);
  }

  /**
   * Runs the node on {@code nodeInput}, retrying it as its retry policy allows, and returns the
   * context of its last attempt. An exception from the node is recorded on the context's node state
   * rather than signaled, while an {@code Error} propagates.
   */
  Single<Context> run(@Nullable Object nodeInput) {
    return Single.defer(
        () -> {
          if (parent != null) {
            return runAttempts(nodeInput, resumeInputs);
          }
          // A root scans its own progress and resumes like a dynamic child.
          Interception interception =
              ResumeScan.intercept(
                  node,
                  ResumeScan.scan(invocationContext, /* basePath= */ "")
                      .get(segment(node.name(), runId)),
                  /* dynamic= */ true,
                  /* canceledMidRun= */ false,
                  /* failedThisTurn= */ false);
          if (!interception.shouldRun()) {
            Context context = newContext(interception.resumeInputs(), /* previousAttempt= */ null);
            interception.replayOn(context);
            return new Activation(context).flushPending().toSingleDefault(context);
          }
          return runAttempts(nodeInput, interception.resumeInputs());
        });
  }

  private Single<Context> runAttempts(
      @Nullable Object nodeInput, Map<String, @Nullable Object> resumeInputs) {
    RetryConfig policy = node.config().retryConfig().orElse(null);
    int maxAttempts = policy == null ? 1 : Math.max(1, policy.maxAttempts());
    AtomicReference<@Nullable NodeExecutionState> previousAttempt = new AtomicReference<>();
    // A retry starts on another thread, so each attempt restores the caller's trace context.
    io.opentelemetry.context.Context traceContext = io.opentelemetry.context.Context.current();
    // concatMap loops instead of recursing, so many instant retries keep the stack flat.
    return Flowable.range(1, maxAttempts)
        .concatMapSingle(
            attempt ->
                backoff(policy, attempt)
                    .andThen(
                        Single.defer(
                                () -> {
                                  Context context = newContext(resumeInputs, previousAttempt.get());
                                  previousAttempt.set(context.requireNodeState());
                                  Activation activation = new Activation(context);
                                  return activation.run(nodeInput).toSingleDefault(activation);
                                })
                            .compose(Tracing.withContext(traceContext))))
        .takeUntil(activation -> !activation.retries(policy))
        .lastOrError()
        .map(activation -> activation.context);
  }

  /** Returns the wait before attempt {@code attempt}; there is none before the first. */
  private Completable backoff(@Nullable RetryConfig policy, int attempt) {
    if (policy == null || attempt == 1) {
      return Completable.complete();
    }
    Duration delay = policy.delayFor(attempt - 1, ThreadLocalRandom.current());
    // Even without a delay, the retry starts on the invocation's scheduler, not the failing thread.
    return Completable.timer(delay.toNanos(), NANOSECONDS, invocationContext.scheduler());
  }

  private Context newContext(
      Map<String, @Nullable Object> resumeInputs, @Nullable NodeExecutionState previousAttempt) {
    return new Context(
        childInvocationContext(),
        NodeExecutionState.builder()
            .node(node)
            .eventSink(eventSink)
            .parent(parent)
            .runId(runId)
            .useAsOutput(useAsOutput)
            .resumeInputs(resumeInputs)
            .previousAttempt(previousAttempt)
            .build());
  }

  private InvocationContext childInvocationContext() {
    return scheduledContext(invocationContext, overrideBranch, useSubBranch, node.name(), runId);
  }

  /**
   * Returns the context that the run {@code runId} of the node {@code name} runs against: a
   * sub-branch of its base branch when it is fanned out, else {@code overrideBranch} when one is
   * given, else {@code parent} itself.
   */
  static InvocationContext scheduledContext(
      InvocationContext parent,
      @Nullable String overrideBranch,
      boolean useSubBranch,
      String name,
      String runId) {
    if (useSubBranch) {
      String base = overrideBranch != null ? overrideBranch : parent.branch().orElse(null);
      return parent.toBuilder()
          .branch(appendSegment(base, name, runId, /* separator= */ '.'))
          .build();
    }
    if (overrideBranch != null) {
      return parent.toBuilder().branch(overrideBranch).build();
    }
    return parent;
  }

  /**
   * One attempt at running the node, during which the node is asked for each event only after its
   * previous event is processed. Events from nested nodes pass through without reapplying their
   * routing or transfers. When the node finishes, a final event carries any output, routes, or
   * deltas not carried by an earlier event.
   */
  private final class Activation {
    private final Context context;
    private final NodeExecutionState nodeState;
    // Set when the node takes on a child's failure, which only the child's own policy retries.
    private boolean childFailed;

    Activation(Context context) {
      this.context = context;
      this.nodeState = context.requireNodeState();
    }

    Completable run(@Nullable Object nodeInput) {
      Completable body =
          asBaseNode(node)
              .run(context, nodeInput)
              // Avoid concatMapCompletable: its prefetch would let the node run ahead of the sink.
              .flatMapCompletable(this::dispatch, /* delayErrors= */ true, /* maxConcurrency= */ 1);
      return withTimeout(body)
          .andThen(Completable.defer(this::flushPending))
          .onErrorResumeNext(this::onError)
          // A run cut short stays unfinished, so a later call in this turn reruns it.
          .doOnSubscribe(unused -> nodeState.markStarted())
          .doOnComplete(nodeState::markFinished);
    }

    /** Returns whether {@code policy} retries this attempt. */
    boolean retries(@Nullable RetryConfig policy) {
      return policy != null
          && !childFailed
          && nodeState
              .failure()
              .map(failure -> policy.shouldRetry(failure, nodeState.attemptCount()))
              .orElse(false);
    }

    /** Fails {@code run} with a {@link NodeTimeoutException} if it outlasts the node's timeout. */
    private Completable withTimeout(Completable run) {
      Optional<Duration> timeout = node.config().timeout();
      if (timeout.isEmpty()) {
        return run;
      }
      Duration limit = timeout.get();
      // Fails on the timer's thread: the timed-out attempt may still hold the scheduler's threads.
      Completable timedOut = Completable.error(() -> new NodeTimeoutException(node.name(), limit));
      io.opentelemetry.context.Context traceContext = io.opentelemetry.context.Context.current();
      // Off the caller's thread, so a body that blocks its thread cannot hold off the timeout.
      return Completable.timer(0, NANOSECONDS, invocationContext.scheduler())
          // Unlike subscribeOn, canceling this hop does not interrupt the handling of node events.
          .andThen(run.compose(Tracing.withContext(traceContext)))
          // convert saturates, so a timeout too long to count in nanoseconds waits about 292 years.
          .timeout(NANOSECONDS.convert(limit), NANOSECONDS, timedOut)
          // The failure arrives on the timer's thread, so it needs the caller's trace context too.
          .compose(Tracing.withContext(traceContext));
    }

    private Completable onError(Throwable error) {
      if (error instanceof NodeInterruptedException) {
        // A child waits, or raiseOnWait ended the run; any interrupt IDs are already recorded.
        return flushPending();
      }
      if (error instanceof DynamicNodeFailedException childFailure) {
        // The child already sent an error event, so the node takes on its failure without one.
        childFailed = true;
        nodeState.failure(childFailure.error(), childFailure.errorNodePath());
        return Completable.complete();
      }
      // Only an Exception fails the node; an Error is fatal and propagates.
      return error instanceof Exception exception ? fail(exception) : Completable.error(error);
    }

    private Completable dispatch(Event event) {
      boolean outputKept = track(event);
      if (outputKept) {
        return send(event);
      }
      return withoutDroppedOutput(event).map(this::send).orElseGet(Completable::complete);
    }

    /**
     * Records the output, interrupts, and routing carried by {@code event} on the node state.
     * Returns {@code false} if a {@code useAsOutput} child holds the node's output, so the event's
     * own output was dropped.
     */
    private boolean track(Event event) {
      boolean outputKept = true;
      if (event.output().isPresent()) {
        outputKept = nodeState.tryProduceOutput(event.output().get());
      } else if (isMessageAsOutput(event)) {
        outputKept = nodeState.tryProduceOutput(event.content().get());
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
      return outputKept;
    }

    /**
     * Returns {@code event} without the output the node produced after delegating its output, or
     * empty if the event has no content, long-running tool IDs or actions to send without it. A
     * message-as-output event becomes a plain message, so its text is not read back as the output.
     */
    private Optional<Event> withoutDroppedOutput(Event event) {
      boolean carriesMore =
          event.content().isPresent()
              || event.hasPendingLongRunningToolCall()
              || !event.actions().equals(EventActions.builder().build());
      if (!carriesMore) {
        return Optional.empty();
      }
      NodeInfo plainInfo =
          event
              .nodeInfo()
              .map(info -> info.toBuilder().messageAsOutput(false).build())
              .orElse(null);
      return Optional.of(event.toBuilder().output(null).nodeInfo(plainInfo).build());
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
          .send(outgoing, /* nodeFailure= */ null)
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
          .send(event, /* nodeFailure= */ null)
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
                      .errorCode(new FinishReason(RetryConfig.errorTypeName(failure)))
                      .errorMessage(nullToEmpty(failure.getMessage()))
                      .build()));
      return eventSink.send(errorEvent, failure).doOnComplete(() -> nodeState.failure(failure));
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
                  .outputFor(carriesOutput ? nodeState.outputFor() : nodeInfo.outputFor())
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

  /**
   * A run of a child that a node's calls with one run ID share. It is canceled only once every call
   * waiting on it is canceled, so a canceled call never makes another call run the child again.
   */
  static final class SharedChildRun {
    private final SingleSubject<Context> result = SingleSubject.create();
    private final CompositeDisposable run = new CompositeDisposable();

    @GuardedBy("this")
    private int waitingCalls;

    @GuardedBy("this")
    private boolean canceled;

    /** Adds a waiting call, or returns false if the run was already canceled. */
    private synchronized boolean join() {
      if (canceled) {
        return false;
      }
      waitingCalls++;
      return true;
    }

    /** Starts the run unless every call has left, and reports its result to every waiting call. */
    private void start(Single<Context> child) {
      synchronized (this) {
        if (canceled) {
          return;
        }
      }
      // Added before it subscribes, so a cancel reaches a child blocked as it starts.
      child.subscribe(result::onSuccess, result::onError, run);
    }

    /**
     * Removes a waiting call. If it was the last one and the run has not finished, cancels the run
     * and returns true.
     */
    private boolean leave() {
      synchronized (this) {
        if (--waitingCalls > 0 || result.hasValue() || result.hasThrowable()) {
          return false;
        }
        canceled = true;
      }
      run.dispose();
      return true;
    }
  }

  /**
   * An event queued for the runner, paired with the signal that completes once the caller receives
   * the event and, for an error event, with the failure it reports.
   */
  private record QueuedEvent(
      Event event, CompletableSubject processed, @Nullable Exception nodeFailure) {}

  /** Appends a {@code name@runId} segment to {@code base}, joined by {@code separator}. */
  static String appendSegment(@Nullable String base, String name, String runId, char separator) {
    String segment = segment(name, runId);
    return isNullOrEmpty(base) ? segment : base + separator + segment;
  }

  /** Returns the {@code name@runId} path segment of a node's run. */
  static String segment(String name, String runId) {
    return name + "@" + runId;
  }

  /** Returns whether {@code event} is a final event whose content is the node's output. */
  private static boolean isMessageAsOutput(Event event) {
    return !event.partial().orElse(false)
        && event.content().isPresent()
        && event.nodeInfo().map(NodeInfo::messageAsOutput).orElse(false);
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
    private boolean useAsOutput;
    private boolean useSubBranch;
    private @Nullable String overrideBranch;
    private Map<String, @Nullable Object> resumeInputs = ImmutableMap.of();

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
     * Sets whether the node's output is also its parent's output, as when a terminal node produces
     * the output of its workflow.
     */
    @CanIgnoreReturnValue
    Builder useAsOutput(boolean useAsOutput) {
      this.useAsOutput = useAsOutput;
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
     * Sets the answers that a child resumes with, keyed by interrupt ID. A runner at the root reads
     * the root's answers from the session itself.
     */
    @CanIgnoreReturnValue
    Builder resumeInputs(Map<String, @Nullable Object> resumeInputs) {
      this.resumeInputs = checkNotNull(resumeInputs);
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
