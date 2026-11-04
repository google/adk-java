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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.telemetry.Tracing;
import com.google.adk.workflow.ResumeScan.Interception;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
  private final @Nullable String overrideIsolationScope;
  private final Map<String, @Nullable Object> resumeInputs;
  private final ImmutableSet<String> openInterrupts;
  private final boolean checkInput;
  private final @Nullable NodeExecutionState topLevelPeer;
  private final @Nullable String basePath;

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
    this.overrideIsolationScope = builder.overrideIsolationScope;
    this.resumeInputs = builder.resumeInputs;
    this.openInterrupts = builder.openInterrupts;
    this.checkInput = builder.checkInput;
    this.topLevelPeer = builder.topLevelPeer;
    this.basePath = builder.basePath;
  }

  /**
   * Returns whether {@code error} signals that a child node waits, which must end its caller's run
   * so the caller waits too, rather than count as a failure.
   *
   * <p>This method is only for use by Agent Development Kit.
   */
  @Experimental
  public static boolean isWaitSignal(Throwable error) {
    return error instanceof NodeInterruptedException;
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
    return Flowable.defer(() -> {
      FlowableProcessor<QueuedEvent> queue = UnicastProcessor.<QueuedEvent>create().toSerialized();
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
          runRootNode(node, context, sink)
              .doOnSuccess(nodeContext ->
                  nodeContext
                      .requireNodeState()
                      .failure()
                      .ifPresentOrElse(queue::onError, queue::onComplete))
              .doOnError(queue::onError)
              .ignoreElement()
              .onErrorComplete();
      return queue
          .concatMap(queued ->
              processEvent
                  .apply(queued.event())
                  .doOnError(error -> {
                    // As in Kotlin, keep the failures of unprocessed error events.
                    if (error instanceof Exception) {
                      unprocessedFailures.forEach(error::addSuppressed);
                    }
                  })
                  .toFlowable()
                  // The node proceeds only after the caller receives the processed event.
                  .doAfterNext(unused -> {
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
    return Maybe.defer(
        () -> startChild(parent, node, nodeInput, config, /* reservedRunId= */ null));
  }

  /**
   * Runs {@code node} as {@link #runChild(Context, Node, Object, RunNodeConfig)} does, using the
   * {@code runId} that the caller reserved with {@link NodeExecutionState#nextChildRunId}. {@code
   * config} must leave its run ID unset.
   */
  static Maybe<Object> runChild(
      Context parent, Node node, @Nullable Object nodeInput, RunNodeConfig config, String runId) {
    checkNotNull(parent);
    checkNotNull(node);
    checkNotNull(config);
    checkNotNull(runId);
    checkArgument(config.runId().isEmpty(), "The config sets its own run ID.");
    return Maybe.defer(() -> startChild(parent, node, nodeInput, config, runId));
  }

  /** Runs {@code node} as a child of {@code parent} and turns the child's run into its result. */
  private static Maybe<Object> startChild(
      Context parent,
      Node node,
      @Nullable Object nodeInput,
      RunNodeConfig config,
      @Nullable String reservedRunId) {
    NodeExecutionState parentState = parent.requireNodeState();
    // As in Kotlin, a body whose emit was rejected starts no child, nor takes a run ID.
    parentState.throwIfBodyRejected();
    checkState(
        parentState.node().rerunOnResume(),
        "Node '%s' must rerun on resume to run child nodes: a child it runs may be interrupted, and"
            + " the workflow reruns the caller on resume to collect the child's output.",
        parentState.node().name());
    // No graph validates a child that a node runs itself, so check that it keeps paths intact.
    NodeNames.validate(node.name());
    // Taken before the input check, as in Kotlin, so a rejected call still uses up its run ID.
    String runId =
        reservedRunId != null
            ? reservedRunId
            : config.runId().orElseGet(() -> parentState.nextChildRunId(node.name()));
    // Checked in the caller, as in Kotlin, so a rejected input fails the caller and no child runs.
    Object input = asBaseNode(node).childInput(nodeInput);
    // A workflow takes its output from its terminal node, so it never delegates it to a child.
    boolean delegatesOutput = config.useAsOutput() && !(parentState.node() instanceof Workflow);
    if (delegatesOutput) {
      parentState.claimOutputDelegation();
    }
    return sharedChildRun(parent, node, input, runId, config)
        .doOnError(error -> {
          if (delegatesOutput) {
            parentState.releaseOutputDelegation();
          }
        })
        .flatMapMaybe(
            child -> childResult(parent, node, child, delegatesOutput, config.raiseOnWait()));
  }

  /**
   * Returns the run of {@code node} under {@code runId} shared by the caller's calls with that run
   * ID, starting it if needed, with the handoffs it requests. A run that fails or waits for an
   * output it did not produce is not shared, so a later call runs the child again. The run is
   * canceled only once every call waiting on it is canceled.
   */
  private static Single<Context> sharedChildRun(
      Context parent, Node node, @Nullable Object nodeInput, String runId, RunNodeConfig config) {
    return Single.defer(() -> {
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
          run.result.doOnDispose(() -> {
            if (run.leave()) {
              childRuns.remove(childKey, run);
            }
          });
      if (existing != null) {
        return call;
      }
      // Start after the call subscribes, so a cancel reaches a child blocked as it starts.
      return Single.create(emitter -> {
        emitter.setDisposable(call.subscribe(emitter::onSuccess, emitter::onError));
        run.start(
            // Defer so a throw from scan or intercept fails the run instead of leaking it.
            Single.defer(() -> runOrReplayChild(parent, node, nodeInput, runId, config))
                .flatMap(first ->
                    followHandoffs(parent, node, first, config)
                        .doOnSuccess(last -> {
                          // A handoff keeps the run unless its last target failed.
                          if (!keepsItsRun(first)
                              || last.requireNodeState().failure().isPresent()) {
                            childRuns.remove(childKey, run);
                          }
                        }))
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
            parentState.childFailedThisTurn(childKey),
            parent.invocationContext());
    if (!interception.shouldRun()) {
      // A handoff target resumes under the replayed node, so the node keeps its branch.
      InvocationContext scheduled =
          scheduledContext(
              parent.invocationContext(),
              config.overrideBranch().orElse(null),
              config.useSubBranch(),
              node.name(),
              runId);
      return Single.just(
          ResumeScan.replayContext(
              parent, scheduled, node, runId, config.useAsOutput(), recovered, interception));
    }
    return builder()
        .node(node)
        .parent(parent)
        .runId(runId)
        .useAsOutput(config.useAsOutput())
        .useSubBranch(config.useSubBranch())
        .overrideBranch(config.overrideBranch().orElse(null))
        // As in Python, a rerun keeps the scope it ran in, so a caller that picks a fresh scope
        // each turn does not split the child's history.
        .overrideIsolationScope(
            recovered != null && recovered.isolationScope != null
                ? recovered.isolationScope
                : config.overrideIsolationScope().orElse(null))
        .resumeInputs(interception.resumeInputs())
        .openInterrupts(interception.interrupts())
        .checkInput(false)
        .build()
        .run(nodeInput);
  }

  /**
   * Follows the handoffs that {@code first}, a run of {@code node} that {@code caller} started with
   * {@code config}, requests, and returns the context of the run that ends the loop. Each target
   * runs, or replays, with the sub-branch setting of {@code config} on the branch and in the scope
   * of the run that handed off, under the context it resolves to or at the top of the invocation.
   */
  static Single<Context> followHandoffs(
      Context caller, Node node, Context first, RunNodeConfig config) {
    // As in Python, each hop keeps the scope of the run that handed off, such as a task's.
    AtomicReference<@Nullable String> scope =
        new AtomicReference<>(first.requireNodeState().isolationScope().orElse(null));
    // Unlike Python, each hop also branches from the run that handed off, so it sees that history.
    AtomicReference<@Nullable String> branch =
        new AtomicReference<>(first.invocationContext().branch().orElse(null));
    return TransferTarget.followHandoffs(
        node,
        first,
        target -> {
          String name = target.agent().name();
          RunNodeConfig scoped =
              config.toBuilder()
                  .overrideIsolationScope(scope.get())
                  .overrideBranch(branch.get())
                  .build();
          Context parent = target.parent();
          Single<Context> hop;
          if (parent == null) {
            hop = runAtTopLevel(target.agent(), caller, scoped);
          } else {
            // An agent name may contain '.', which no node path or branch can hold.
            NodeNames.validate(name);
            String runId = parent.requireNodeState().nextChildRunId(name);
            // Only a run under the caller takes the caller's output.
            RunNodeConfig child =
                scoped.toBuilder()
                    .useAsOutput(config.useAsOutput() && parent.equals(caller))
                    .build();
            hop = runOrReplayChild(parent, target.agent(), /* nodeInput= */ null, runId, child);
          }
          return hop.doOnSuccess(next -> {
            scope.set(next.requireNodeState().isolationScope().orElse(scope.get()));
            branch.set(next.invocationContext().branch().orElse(null));
          });
        });
  }

  /**
   * Runs {@code agent} at the top of the invocation, beside the root node above {@code context}, or
   * replays it from history, with the branch and isolation settings of {@code config}.
   */
  private static Single<Context> runAtTopLevel(
      BaseAgent agent, Context context, RunNodeConfig config) {
    Context root = context;
    while (root.requireNodeState().parent().isPresent()) {
      root = root.requireNodeState().parent().get();
    }
    NodeExecutionState rootState = root.requireNodeState();
    return runTopLevelHop(
        agent,
        root.invocationContext(),
        rootState.eventSink(),
        rootState,
        config,
        ancestorAgentPath(agent),
        /* firstRunId= */ "1");
  }

  /**
   * Runs {@code node} as the root of an invocation: a workflow or other node on the user content,
   * or an agent picked from the tree of a root agent that runs as a node, as {@link #runRootLoop}
   * does.
   */
  private static Single<Context> runRootNode(Node node, InvocationContext context, EventSink sink) {
    if (node instanceof BaseAgent agent) {
      return runRootLoop(agent, context, sink);
    }
    // App checks a root node's name only against the agent-name rule, which allows '.'.
    NodeNames.validate(node.name());
    return builder()
        .node(node)
        .invocationContext(context)
        .eventSink(sink)
        .build()
        .run(context.userContent().orElse(null));
  }

  /**
   * Runs {@code agent}, the agent that takes this turn under a root agent that runs as a node, at
   * the top of the invocation, and follows the handoffs it requests until an agent fails, waits, or
   * finishes without one. An agent below the root runs under its ancestor agents' path.
   */
  private static Single<Context> runRootLoop(
      BaseAgent agent, InvocationContext context, EventSink sink) {
    // A resumed agent keeps the path and run it last had in this invocation, so it finds its
    // progress; the runs on that path count as taken, so a later hop to one gets a new ID.
    String lastPath = lastPath(agent, context);
    String resumedParentPath = lastPath == null ? null : pathAbove(lastPath);
    AtomicReference<Context> latestHop = new AtomicReference<>();
    return runTopLevelHop(
            agent,
            context,
            sink,
            /* topLevelPeer= */ null,
            RunNodeConfig.builder().build(),
            resumedParentPath != null ? resumedParentPath : ancestorAgentPath(agent),
            lastPath == null ? "1" : ownRunId(agent, lastPath))
        .flatMap(first -> {
          latestHop.set(first);
          // The hops after the resumed agent are new work, so each takes a run not yet recorded.
          takeRecordedRuns(first.requireNodeState().topLevelRunCounts(), context);
          return TransferTarget.followHandoffs(
              agent,
              first,
              target -> {
                Context parent = target.parent();
                Single<Context> hop;
                if (parent == null) {
                  // A top hop keeps the handing-off agent's scope, such as a task root's.
                  String scope = latestHop.get().requireNodeState().isolationScope().orElse(null);
                  hop =
                      runTopLevelHop(
                          target.agent(),
                          context,
                          sink,
                          first.requireNodeState(),
                          RunNodeConfig.builder().overrideIsolationScope(scope).build(),
                          topLevelParentPath(target.agent(), latestHop.get()),
                          /* firstRunId= */ "1");
                } else {
                  String name = target.agent().name();
                  NodeNames.validate(name);
                  hop =
                      runOrReplayChild(
                          parent,
                          target.agent(),
                          /* nodeInput= */ null,
                          parent.requireNodeState().nextChildRunId(name),
                          RunNodeConfig.builder().build());
                }
                return hop.doOnSuccess(latestHop::set);
              });
        });
  }

  /**
   * Returns the path that {@code target}, a handoff target that runs at the top of the invocation,
   * runs under: a peer of the agent that ran {@code latestHop} at the top runs beside it, and any
   * other target under its ancestor agents' first runs.
   */
  private static @Nullable String topLevelParentPath(BaseAgent target, Context latestHop) {
    NodeExecutionState latest = latestHop.requireNodeState();
    if (latest.parent().isEmpty()
        && latest.node() instanceof BaseAgent latestAgent
        && latestAgent.parentAgent() != null
        && latestAgent.parentAgent().equals(target.parentAgent())) {
      return pathAbove(latest.nodePath());
    }
    return ancestorAgentPath(target);
  }

  /** Records the runs on the node paths of this invocation's events in {@code runCounts}. */
  private static void takeRecordedRuns(
      ConcurrentMap<String, Integer> runCounts, InvocationContext context) {
    for (Event event : context.session().immutableEvents()) {
      if (context.invocationId().equals(event.invocationId())) {
        event
            .nodeInfo()
            .map(NodeInfo::path)
            .filter(path -> !path.isEmpty())
            .ifPresent(path -> NodeExecutionState.takeRunsOnPath(runCounts, path));
      }
    }
  }

  /** Returns the path above the last segment of {@code nodePath}, or null if it has one segment. */
  private static @Nullable String pathAbove(String nodePath) {
    int slash = nodePath.lastIndexOf('/');
    return slash < 0 ? null : nodePath.substring(0, slash);
  }

  /**
   * Runs {@code agent} at the top of the invocation, under {@code parentPath}, or replays it from
   * history. A hop beside {@code topLevelPeer} shares its run counts and records; the first hop,
   * without one, gets {@code firstRunId}.
   */
  private static Single<Context> runTopLevelHop(
      BaseAgent agent,
      InvocationContext context,
      EventSink sink,
      @Nullable NodeExecutionState topLevelPeer,
      RunNodeConfig config,
      @Nullable String parentPath,
      String firstRunId) {
    // An agent name may contain '.', which no node path or branch can hold.
    NodeNames.validate(agent.name());
    String runId =
        topLevelPeer == null
            ? firstRunId
            : NodeExecutionState.nextTopLevelRunId(
                topLevelPeer.topLevelRunCounts(), parentPath, agent.name());
    return builder()
        .node(agent)
        .invocationContext(context)
        .eventSink(sink)
        .runId(runId)
        .basePath(parentPath)
        .topLevelPeer(topLevelPeer)
        .useSubBranch(config.useSubBranch())
        .overrideBranch(config.overrideBranch().orElse(null))
        .overrideIsolationScope(config.overrideIsolationScope().orElse(null))
        .build()
        .run(/* nodeInput= */ null);
  }

  /** Returns the path of {@code agent}'s ancestor agents, each with run ID 1, or null for none. */
  private static @Nullable String ancestorAgentPath(BaseAgent agent) {
    String path = null;
    for (BaseAgent ancestor : ancestorsFromRoot(agent)) {
      // An agent name may contain '.', which no node path can hold.
      NodeNames.validate(ancestor.name());
      path = appendSegment(path, ancestor.name(), "1", '/');
    }
    return path;
  }

  /**
   * Returns the run ID that {@code lastPath}, from an event of {@code agent}, gives the agent: its
   * last segment's run when that segment is the agent's own, as a sequence step's is not, else 1.
   */
  private static String ownRunId(BaseAgent agent, String lastPath) {
    String segment = lastPath.substring(lastPath.lastIndexOf('/') + 1);
    int at = segment.lastIndexOf('@');
    return at > 0 && segment.substring(0, at).equals(agent.name())
        ? segment.substring(at + 1)
        : "1";
  }

  /**
   * Returns the node path of {@code agent}'s latest event in this invocation, or null if it has not
   * run in it.
   */
  private static @Nullable String lastPath(BaseAgent agent, InvocationContext context) {
    ImmutableList<Event> events = context.session().immutableEvents();
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (!context.invocationId().equals(event.invocationId())
          || !agent.name().equals(event.author())) {
        continue;
      }
      Optional<String> path = event.nodeInfo().map(NodeInfo::path).filter(p -> !p.isEmpty());
      if (path.isEmpty()) {
        continue;
      }
      return path.get();
    }
    return null;
  }

  /** Returns the ancestor agents of {@code agent}, starting at the root of its tree. */
  private static ImmutableList<BaseAgent> ancestorsFromRoot(BaseAgent agent) {
    List<BaseAgent> ancestors = new ArrayList<>();
    for (BaseAgent ancestor = agent.parentAgent();
        ancestor != null;
        ancestor = ancestor.parentAgent()) {
      ancestors.add(ancestor);
    }
    return ImmutableList.copyOf(ancestors).reverse();
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
      Context parent,
      Node startedNode,
      Context child,
      boolean delegatesOutput,
      boolean raiseOnWait) {
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
    // After a handoff, the child context holds the target rather than the started node.
    boolean isStartedNode = childNode.equals(startedNode);
    // A handoff target need not give an output; only the started node leaves one unfinished.
    boolean leftWaiting =
        isStartedNode
            && childState.output().isEmpty()
            && child.eventActions().transferToAgent().isEmpty()
            && (childNode instanceof Workflow || childNode.waitForOutput());
    // A handoff target that stopped on a waiting child, such as a task that asks, still waits.
    boolean targetWaits = !isStartedNode && childState.waitsOnChild();
    if (raiseOnWait && (leftWaiting || targetWaits)) {
      return Maybe.error(new NodeInterruptedException());
    }
    // No event of this run carries a delegate's replayed or shared output, so the caller emits it.
    Optional<Object> output = childState.output();
    boolean emittedForCaller =
        childState.outputEmitted() && childState.outputParent() == parentState;
    if (delegatesOutput && output.isPresent() && !emittedForCaller) {
      // A child that sent an output and then handed off leaves the target's output to replace it.
      if (parentState.delegatedOutput().isPresent()) {
        parentState.replaceDelegatedOutput(output.get());
      } else {
        parentState.adoptReplayedOutput(output.get());
      }
    }
    return Maybe.fromOptional(output);
  }

  /**
   * Runs the node on {@code nodeInput}, retrying it as its retry policy allows, and returns the
   * context of its last attempt. An exception from the node is recorded on the context's node state
   * rather than signaled, while an {@code Error} other than a pause propagates.
   */
  Single<Context> run(@Nullable Object nodeInput) {
    return Single.defer(() -> {
      if (parent != null) {
        return runAttempts(nodeInput, resumeInputs, openInterrupts, overrideIsolationScope);
      }
      // A root scans its own progress and resumes like a dynamic child.
      String path = segment(node.name(), runId);
      ResumeScan.RecoveredNode recovered =
          ResumeScan.scan(invocationContext, nullToEmpty(basePath)).get(path);
      Interception interception =
          ResumeScan.intercept(
              node,
              recovered,
              /* dynamic= */ true,
              topLevelPeer != null && topLevelPeer.runCanceledMidRun(path),
              topLevelPeer != null && topLevelPeer.runFailedThisTurn(path),
              invocationContext);
      // Without a scope of its own, a resumed root keeps the scope it ran in, as a child does.
      String scope =
          overrideIsolationScope == null && recovered != null
              ? recovered.isolationScope
              : overrideIsolationScope;
      if (!interception.shouldRun()) {
        Context context =
            newContext(interception.resumeInputs(), /* previousAttempt= */ null, scope);
        interception.replayOn(context);
        // A hop at the top does not resend what an earlier turn's events carried.
        if (topLevelPeer != null && recovered != null) {
          NodeExecutionState state = context.requireNodeState();
          if (recovered.output != null && state.output().isPresent()) {
            state.markOutputEmitted();
          }
          if (recovered.route != null) {
            state.markRoutesEmitted();
          }
        }
        return new Activation(context).flushPending().toSingleDefault(context);
      }
      return runAttempts(nodeInput, interception.resumeInputs(), interception.interrupts(), scope);
    });
  }

  private Single<Context> runAttempts(
      @Nullable Object nodeInput,
      Map<String, @Nullable Object> resumeInputs,
      ImmutableSet<String> openInterrupts,
      @Nullable String isolationScope) {
    RetryConfig policy = node.config().retryConfig().orElse(null);
    int maxAttempts = policy == null ? 1 : Math.max(1, policy.maxAttempts());
    AtomicReference<@Nullable NodeExecutionState> previousAttempt = new AtomicReference<>();
    // A retry starts on another thread, so each attempt restores the caller's trace context.
    io.opentelemetry.context.Context traceContext = io.opentelemetry.context.Context.current();
    // concatMap loops instead of recursing, so many instant retries keep the stack flat.
    return Flowable.range(1, maxAttempts)
        .concatMapSingle(attempt ->
            backoff(policy, attempt)
                .andThen(
                    Single.defer(
                            () -> {
                              Context context =
                                  newContext(resumeInputs, previousAttempt.get(), isolationScope);
                              previousAttempt.set(context.requireNodeState());
                              Activation activation = new Activation(context);
                              return activation.run(nodeInput).toSingleDefault(activation);
                            })
                        .compose(Tracing.withContext(traceContext))))
        .takeUntil(activation -> !activation.retries(policy))
        .lastOrError()
        .map(activation -> keepOpenInterrupts(activation.context, openInterrupts));
  }

  /**
   * Keeps {@code context} waiting on {@code openInterrupts}, those this rerun started with, if it
   * finishes without a failure, output, or route, since only an answer closes them.
   */
  private static Context keepOpenInterrupts(Context context, ImmutableSet<String> openInterrupts) {
    NodeExecutionState state = context.requireNodeState();
    boolean hasResult =
        state.failure().isPresent()
            || state.output().isPresent()
            || state.selectedRoutes().isPresent();
    if (!openInterrupts.isEmpty() && !hasResult) {
      state.addInterruptIds(openInterrupts);
    }
    return context;
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
      Map<String, @Nullable Object> resumeInputs,
      @Nullable NodeExecutionState previousAttempt,
      @Nullable String isolationScope) {
    return new Context(
        childInvocationContext(),
        NodeExecutionState.builder()
            .node(node)
            .eventSink(eventSink)
            .parent(parent)
            .runId(runId)
            .useAsOutput(useAsOutput)
            .isolationScope(isolationScope)
            .resumeInputs(resumeInputs)
            .previousAttempt(previousAttempt)
            .topLevelPeer(topLevelPeer)
            .basePath(basePath)
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
              .run(context, nodeInput, checkInput)
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

    /**
     * Fails {@code run} if it outlasts the node's timeout: with a {@link NodeTimeoutException}, or,
     * as in Kotlin, with the rejection of an {@code emit} that its body caught.
     */
    private Completable withTimeout(Completable run) {
      Optional<Duration> timeout = node.config().timeout();
      if (timeout.isEmpty()) {
        return run;
      }
      Duration limit = timeout.get();
      // The timeout only interrupts a body, so the node fails once the body has returned.
      Completable timedOut =
          nodeState
              .bodyReturned()
              .andThen(
                  Completable.error(() ->
                      nodeState.bodyRejectionOr(new NodeTimeoutException(node.name(), limit))));
      io.opentelemetry.context.Context traceContext = io.opentelemetry.context.Context.current();
      // Off the caller's thread, so a body that blocks does not block the caller.
      return completeOn(invocationContext.scheduler())
          .andThen(run.compose(Tracing.withContext(traceContext)))
          // convert saturates, so a timeout too long to count in nanoseconds waits about 292 years.
          .timeout(NANOSECONDS.convert(limit), NANOSECONDS, timedOut)
          // The failure arrives on another thread, so it needs the caller's trace context too.
          .compose(Tracing.withContext(traceContext));
    }

    private Completable onError(Throwable error) {
      if (error instanceof NodeInterruptedException pause) {
        // A waiting child's interrupt IDs are already recorded; ask sends and records a request's.
        if (pause.request() == null) {
          nodeState.markWaitsOnChild();
          return flushPending();
        }
        return ask(pause.request());
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
      Event checked = nodeState.withCheckedStateDelta(event);
      boolean outputKept = track(checked);
      if (outputKept) {
        return send(checked);
      }
      return withoutDroppedOutput(checked).map(this::send).orElseGet(Completable::complete);
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
      Event stamped =
          stamp(context, messageAsOutput ? event.toBuilder().output(null).build() : event);
      Event outgoing = stamped.partial().orElse(false) ? stamped : withPendingDeltas(stamped);
      return eventSink
          .send(outgoing, /* nodeFailure= */ null)
          .doOnComplete(() -> {
            if (outgoing.output().isPresent() || messageAsOutput) {
              nodeState.markOutputEmitted();
            }
            // Releases a body that waits in Context.emit for this event.
            nodeState.markProcessed(outgoing);
          });
    }

    /** Asks for {@code request}; a request that has no JSON form fails the node. */
    private Completable ask(RequestInput request) {
      Event event;
      try {
        event = request.toEvent(context.invocationContext());
      } catch (IllegalArgumentException e) {
        return fail(e);
      }
      return dispatch(event).andThen(Completable.defer(this::flushPending));
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
                  context,
                  newEvent(context)
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
      Event errorEvent = withPendingDeltas(errorEvent(context, failure));
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
      moveEntries(stateDelta, actions.stateDelta());
      moveEntries(artifactDelta, actions.artifactDelta());
      return event.toBuilder().actions(actions).build();
    }

    /**
     * Moves the entries of the concurrent map {@code from} to {@code to}, but keeps in {@code from}
     * an entry another thread changed after it was copied.
     */
    private static <V> void moveEntries(Map<String, V> from, Map<String, V> to) {
      for (Map.Entry<String, V> entry : from.entrySet()) {
        to.put(entry.getKey(), entry.getValue());
        from.remove(entry.getKey(), entry.getValue());
      }
    }
  }

  /** Returns an error event for {@code failure}, attributed to {@code run}. */
  static Event errorEvent(Context run, Exception failure) {
    return stamp(
        run,
        newEvent(run)
            .errorCode(new FinishReason(RetryConfig.errorTypeName(failure)))
            .errorMessage(nullToEmpty(failure.getMessage()))
            .build());
  }

  /** Attributes {@code event} to its author and to {@code run}. */
  private static Event stamp(Context run, Event event) {
    NodeExecutionState nodeState = run.requireNodeState();
    InvocationContext eventContext = run.invocationContext();
    String author = nodeState.eventAuthor();
    NodeInfo nodeInfo = event.nodeInfo().orElseGet(() -> NodeInfo.builder().build());
    boolean carriesOutput = event.output().isPresent() || isMessageAsOutput(event);
    // Marked, so that a stored session reads the output back as a message, not as a map.
    boolean messageInOutput = event.output().orElse(null) instanceof Content;
    return event.toBuilder()
        .author(author.isEmpty() ? nodeState.node().name() : author)
        .invocationId(eventContext.invocationId())
        .branch(event.branch().orElse(eventContext.branch().orElse(null)))
        .isolationScope(event.isolationScope().orElse(nodeState.isolationScope().orElse(null)))
        .nodeInfo(
            nodeInfo.toBuilder()
                .path(nodeState.nodePath())
                .outputFor(carriesOutput ? nodeState.outputFor() : nodeInfo.outputFor())
                .messageAsOutput(nodeInfo.messageAsOutput() || messageInOutput)
                .build())
        .build();
  }

  private static Event.Builder newEvent(Context run) {
    InvocationContext eventContext = run.invocationContext();
    return Event.builder().id(eventContext.newUuid()).timestamp(eventContext.now().toEpochMilli());
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

  /**
   * Returns a {@link Completable} that completes on {@code scheduler}, so work chained after it
   * starts on that scheduler instead of the caller's thread. Unlike {@code subscribeOn}, canceling
   * the chained work does not interrupt the thread it runs on.
   */
  static Completable completeOn(Scheduler scheduler) {
    return Completable.timer(0, NANOSECONDS, scheduler);
  }

  /**
   * Returns whether {@code event} is a final event whose content is the node's output. An event
   * whose output field already holds a {@link Content} carries its message in {@code output}, not
   * in {@code content}.
   */
  private static boolean isMessageAsOutput(Event event) {
    return !event.partial().orElse(false)
        && event.content().isPresent()
        && !(event.output().orElse(null) instanceof Content)
        && event.nodeInfo().map(NodeInfo::messageAsOutput).orElse(false);
  }

  /**
   * Adapts any node to a {@link BaseNode}, whose {@link BaseNode#run} turns values into events and
   * checks them against the node's schemas.
   */
  private static BaseNode asBaseNode(Node node) {
    if (node instanceof BaseNode baseNode) {
      return baseNode;
    }
    return new BaseNode(
        node.name(),
        node.description(),
        node.rerunOnResume(),
        node.waitForOutput(),
        node.config(),
        node.inputSchema().orElse(null),
        node.outputSchema().orElse(null),
        node.stateSchema().orElse(null)) {
      @Override
      public boolean requiresAllPredecessors() {
        return node.requiresAllPredecessors();
      }

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
    private @Nullable String overrideIsolationScope;
    private Map<String, @Nullable Object> resumeInputs = ImmutableMap.of();
    private ImmutableSet<String> openInterrupts = ImmutableSet.of();
    private boolean checkInput = true;
    private @Nullable NodeExecutionState topLevelPeer;
    private @Nullable String basePath;

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
     * Sets the isolation scope that replaces the inherited one, or null to keep the inherited
     * scope.
     */
    @CanIgnoreReturnValue
    Builder overrideIsolationScope(@Nullable String overrideIsolationScope) {
      this.overrideIsolationScope = overrideIsolationScope;
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

    /** Sets the root run that this parentless run starts beside, whose turn records it shares. */
    @CanIgnoreReturnValue
    Builder topLevelPeer(@Nullable NodeExecutionState topLevelPeer) {
      this.topLevelPeer = topLevelPeer;
      return this;
    }

    /** Sets the node path a run at the root extends, or null to start a new path. */
    @CanIgnoreReturnValue
    Builder basePath(@Nullable String basePath) {
      this.basePath = basePath;
      return this;
    }

    /**
     * Sets the unanswered interrupts that a rerun child keeps waiting on if it ends without a
     * result (a failure, output, or route).
     */
    @CanIgnoreReturnValue
    Builder openInterrupts(ImmutableSet<String> openInterrupts) {
      this.openInterrupts = checkNotNull(openInterrupts);
      return this;
    }

    /**
     * Sets whether the node checks its input against its input schema, which defaults to {@code
     * true}. A caller that runs a child checks the child's input itself.
     */
    @CanIgnoreReturnValue
    Builder checkInput(boolean checkInput) {
      this.checkInput = checkInput;
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
