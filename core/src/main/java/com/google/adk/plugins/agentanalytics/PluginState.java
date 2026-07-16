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

package com.google.adk.plugins.agentanalytics;

import static com.google.adk.plugins.agentanalytics.BigQueryUtils.getVersionHeaderValue;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.api.gax.batching.FlowController;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.FixedHeaderProvider;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteSettings;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.VerifyException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.functions.Action;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.threeten.bp.Duration;

/** Manages state for the BigQueryAgentAnalyticsPlugin. */
class PluginState {
  private static final Logger logger = Logger.getLogger(PluginState.class.getName());
  private static final int GCS_OFFLOAD_CORE_POOL_SIZE = 2;
  private static final int GCS_OFFLOAD_MAX_THREADS = 10;
  // Max number of tasks in the queue before we start rejecting tasks and executing them in the
  // caller thread.
  private static final int GCS_OFFLOAD_QUEUE_SIZE = 100;
  // Idle time before threads are terminated.
  private static final int GCS_OFFLOAD_IDLE_TIME_SECONDS = 30;

  // Bounded detached-close service shared by all BatchProcessors.
  private static final int WRITER_CLOSE_MAX_THREADS = 2;
  private static final int WRITER_CLOSE_QUEUE_SIZE = 256;
  // Hard cap on LIVE StreamWriters (each owns an internal client and a NON-DAEMON append thread
  // that only its own close() stops). A permit is acquired BEFORE construction and released only
  // when that writer's close has run, so a constructed writer can never lose its cleanup owner
  // and process-level resource growth is bounded even under a sustained Storage outage.
  // INVARIANT: must be <= WRITER_CLOSE_QUEUE_SIZE so a pending close always has a queue slot and
  // pre-shutdown closer rejection is impossible.
  @VisibleForTesting static final int MAX_LIVE_WRITERS = 64;

  private final BigQueryLoggerConfig config;
  private final ScheduledExecutorService executor;
  private final ExecutorService offloadExecutor;
  private final ThreadPoolExecutor writerCloseExecutor;
  private final BigQueryWriteClient writeClient;
  private static final AtomicLong threadCounter = new AtomicLong(0);
  // Map of invocation ID to BatchProcessor.
  private final ConcurrentHashMap<String, BatchProcessor> batchProcessors =
      new ConcurrentHashMap<>();
  // Map of invocation ID to TraceManager.
  private final ConcurrentHashMap<String, TraceManager> traceManagers = new ConcurrentHashMap<>();
  // Cache of invocation ID to Boolean indicating invocation ID has been processed.
  private final Cache<String, Boolean> processedInvocations;
  private final GcsOffloader offloader;
  private final Parser parser;
  private final ConcurrentHashMap<String, Set<CompletableFuture<Void>>> pendingTasks =
      new ConcurrentHashMap<>();
  // Durable per-invocation lifecycle tokens. Unlike the bounded processedInvocations cache (whose
  // entries can be evicted by size or TTL), a token is captured by every append continuation at
  // logEvent time and stays reachable through that reference even after removal from this map, so
  // a continuation completing arbitrarily late still observes the invocation's terminal state and
  // cannot resurrect a processor.
  private final ConcurrentHashMap<String, InvocationLifecycle> lifecycles =
      new ConcurrentHashMap<>();

  /**
   * Terminal-state token for one invocation; see {@link #lifecycles}.
   *
   * <p>Admission and finalization share this token's monitor: {@link #runIfActive} executes an
   * append admission atomically against {@link #markFinalized}, so a continuation cannot pass the
   * gate, get descheduled across finalization (processor close + final stats delivery), and then
   * append into a torn-down processor or record a loss the final snapshot has already missed.
   * Critical sections are short: the append side only holds the monitor through a non-blocking
   * queue offer.
   */
  static final class InvocationLifecycle {
    private boolean finalized;

    /**
     * Runs {@code action} iff the invocation is not finalized, atomically with {@link
     * #markFinalized}. Returns whether the action ran.
     */
    synchronized boolean runIfActive(Runnable action) {
      if (finalized) {
        return false;
      }
      action.run();
      return true;
    }

    synchronized void markFinalized() {
      finalized = true;
    }

    synchronized boolean isFinalized() {
      return finalized;
    }
  }

  // Drop counters accumulated from BatchProcessors that have already been closed/removed, so the
  // aggregate survives per-invocation processor churn.
  private final AtomicLong droppedQueueFull = new AtomicLong();
  private final AtomicLong droppedAppendError = new AtomicLong();
  private final AtomicLong droppedSerializationError = new AtomicLong();
  private final AtomicLong droppedAfterClose = new AtomicLong();
  private final AtomicLong droppedShutdownTimeout = new AtomicLong();
  // Rows lost before a BatchProcessor existed (StreamWriter construction failed) or because their
  // continuation completed after the invocation was already finalized.
  private final AtomicLong droppedWriterCreateError = new AtomicLong();
  private final AtomicLong droppedAfterFinalize = new AtomicLong();
  // Rows dropped because the live-writer permit cap was exhausted (sustained Storage outage).
  private final AtomicLong droppedWriterPermitExhausted = new AtomicLong();
  private final Semaphore writerPermits = new Semaphore(MAX_LIVE_WRITERS);
  // Cleanup owners for admitted writers, registered BEFORE StreamWriter construction so a writer
  // can never exist without an owner (a construction/startup failure or a plugin close racing
  // admission would otherwise abandon its internal client and non-daemon append thread).
  private final Set<WriterLease> liveLeases = ConcurrentHashMap.newKeySet();
  // Plugin-wide closing gate, set by closeInternal's cleanup before it drains leases and
  // processors; creators racing it re-check after publication and self-close.
  private volatile boolean closing = false;

  /**
   * Cleanup owner for one admitted writer, alive from permit acquisition until the writer's
   * detached close task has run (which is the single permit-release point). State transitions are
   * monitor-guarded so "dispatch the close exactly once" holds no matter whether the creator, the
   * processor's teardown, or plugin close gets there first.
   */
  static final class WriterLease {
    private @Nullable StreamWriter writer;
    private boolean closeRequested;
    private boolean closeDispatched;

    /**
     * Attaches the constructed writer. Returns true if a close was already requested (plugin
     * closing raced admission): the creator must dispatch the close and must not publish.
     */
    synchronized boolean attachWriter(StreamWriter writer) {
      this.writer = writer;
      return closeRequested;
    }

    /**
     * Marks the lease close-requested and returns the writer to dispatch, or null if none is
     * attached yet (the creator will dispatch on attach) or the close was already dispatched.
     */
    synchronized @Nullable StreamWriter requestClose() {
      closeRequested = true;
      return takeForCloseLocked();
    }

    /** Returns the writer to dispatch exactly once, or null. */
    synchronized @Nullable StreamWriter takeForClose() {
      return takeForCloseLocked();
    }

    private @Nullable StreamWriter takeForCloseLocked() {
      if (writer == null || closeDispatched) {
        return null;
      }
      closeDispatched = true;
      return writer;
    }
  }

  PluginState(BigQueryLoggerConfig config) throws IOException {
    this.config = config;
    this.executor =
        Executors.newScheduledThreadPool(
            2, r -> new Thread(r, "bq-analytics-plugin-" + threadCounter.getAndIncrement()));
    this.offloadExecutor = createGcsOffloadThreadPool();
    this.writerCloseExecutor =
        new ThreadPoolExecutor(
            WRITER_CLOSE_MAX_THREADS,
            WRITER_CLOSE_MAX_THREADS,
            30,
            SECONDS,
            new ArrayBlockingQueue<>(WRITER_CLOSE_QUEUE_SIZE),
            r -> {
              Thread t =
                  new Thread(r, "bq-analytics-writer-close-" + threadCounter.getAndIncrement());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    this.writerCloseExecutor.allowCoreThreadTimeOut(true);
    // One write client per plugin instance, shared by all invocations.
    this.writeClient = createWriteClient(config);
    this.processedInvocations =
        CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(java.time.Duration.ofMinutes(10))
            .build();
    this.offloader = getGcsOffloader(config);
    this.parser =
        new Parser(
            offloader,
            config.maxContentLength(),
            config.connectionId().orElse(null),
            config.logMultiModalContent());
  }

  private static ExecutorService createGcsOffloadThreadPool() {
    return new ThreadPoolExecutor(
        GCS_OFFLOAD_CORE_POOL_SIZE, // The lower limit of threads.
        GCS_OFFLOAD_MAX_THREADS, // The upper limit of threads.
        GCS_OFFLOAD_IDLE_TIME_SECONDS, // Time to keep idle threads alive.
        SECONDS,
        new ArrayBlockingQueue<>(GCS_OFFLOAD_QUEUE_SIZE), // workQueue: Hand off tasks directly.
        r -> new Thread(r, "bq-analytics-plugin-offload-" + threadCounter.getAndIncrement()),
        // Reject tasks if the queue is full.
        new ThreadPoolExecutor.AbortPolicy());
  }

  ScheduledExecutorService getExecutor() {
    return executor;
  }

  boolean isProcessed(String invocationId) {
    boolean isProcessed = processedInvocations.getIfPresent(invocationId) != null;
    if (isProcessed) {
      logger.fine("Invocation ID: " + invocationId + "  already processed");
    }
    return isProcessed;
  }

  void markProcessed(String invocationId) {
    processedInvocations.put(invocationId, true);
  }

  protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) throws IOException {
    BigQueryWriteSettings.Builder settingsBuilder =
        BigQueryWriteSettings.newBuilder()
            .setHeaderProvider(
                FixedHeaderProvider.create(ImmutableMap.of("user-agent", getVersionHeaderValue())));
    if (config.credentials() != null) {
      settingsBuilder.setCredentialsProvider(FixedCredentialsProvider.create(config.credentials()));
    }
    return BigQueryWriteClient.create(settingsBuilder.build());
  }

  protected StreamWriter createWriter() {
    BigQueryLoggerConfig.RetryConfig retryConfig = config.retryConfig();
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setMaxAttempts(retryConfig.maxRetries())
            .setInitialRetryDelay(Duration.ofMillis(retryConfig.initialDelay().toMillis()))
            .setRetryDelayMultiplier(retryConfig.multiplier())
            .setMaxRetryDelay(Duration.ofMillis(retryConfig.maxDelay().toMillis()))
            .build();

    String streamName = getStreamName(config);
    try {
      return StreamWriter.newBuilder(streamName, writeClient)
          .setTraceId(BigQueryUtils.getVersionHeaderValue() + ":" + UUID.randomUUID())
          // Nonblocking admission: the default LimitExceededBehavior.Block parks append() for up
          // to five minutes waiting on inflight quota, escaping every shutdownTimeout bound this
          // plugin enforces. ThrowException surfaces quota saturation as an append failure, which
          // the flush path catches and accounts as dropped rows.
          .setLimitExceededBehavior(FlowController.LimitExceededBehavior.ThrowException)
          .setRetrySettings(retrySettings)
          .setWriterSchema(BigQuerySchema.getArrowSchema())
          // Route Storage Write append RPCs to the dataset's region. Without this, appends to any
          // location other than the US multi-region can fail with stream-not-found errors.
          .setLocation(config.location())
          .build();
    } catch (Exception e) {
      throw new VerifyException("Failed to create StreamWriter for " + streamName, e);
    }
  }

  /**
   * Normal-operation per-append RPC deadline for a {@link BatchProcessor}. Sized to cover the
   * StreamWriter's full retry budget (summed backoff over {@code maxRetries} steps, each capped by
   * {@code maxDelay}) plus one {@code shutdownTimeout} of per-attempt RPC headroom, so a batch that
   * would eventually succeed is not cancelled mid-retry and miscounted as {@code append_error}.
   * {@code shutdownTimeout} is deliberately NOT reused as the steady-state deadline: it bounds only
   * the close-time final drain, where dropping rows to honor the caller's shutdown budget is
   * acceptable.
   */
  @VisibleForTesting
  static java.time.Duration appendTimeout(BigQueryLoggerConfig config) {
    BigQueryLoggerConfig.RetryConfig retry = config.retryConfig();
    long backoffMillis = 0;
    long delayMillis = retry.initialDelay().toMillis();
    long maxDelayMillis = retry.maxDelay().toMillis();
    for (int i = 0; i < Math.max(0, retry.maxRetries()); i++) {
      backoffMillis += Math.min(delayMillis, maxDelayMillis);
      delayMillis = (long) (delayMillis * retry.multiplier());
    }
    return config.shutdownTimeout().plusMillis(backoffMillis);
  }

  @VisibleForTesting
  String getStreamName(BigQueryLoggerConfig config) {
    return String.format(
        "projects/%s/datasets/%s/tables/%s/streams/_default",
        config.projectId(), config.datasetId(), config.tableName());
  }

  /**
   * Returns (creating if absent) the invocation's lifecycle token. Called at logEvent time, while
   * the invocation is active, so the token each continuation captures predates finalization.
   */
  InvocationLifecycle getLifecycle(String invocationId) {
    return lifecycles.computeIfAbsent(invocationId, id -> new InvocationLifecycle());
  }

  @VisibleForTesting
  TraceManager getTraceManager(String invocationId) {
    return traceManagers.computeIfAbsent(invocationId, id -> new TraceManager());
  }

  @VisibleForTesting
  BatchProcessor getBatchProcessor(String invocationId) {
    return batchProcessors.computeIfAbsent(
        invocationId,
        id -> {
          BatchProcessor p = tryCreateProcessor();
          if (p == null) {
            throw new IllegalStateException(
                "Writer admission refused (permit cap exhausted or plugin closing)");
          }
          return p;
        });
  }

  /**
   * Creates and starts a processor, or returns null when admission is refused (permit cap
   * exhausted, or plugin close raced admission). Accounting for refusals happens here.
   *
   * <p>Ownership protocol: the permit is acquired and a {@link WriterLease} registered BEFORE
   * {@link #createWriter()}, so from the instant a StreamWriter exists it has a cleanup owner. Any
   * failure after construction — including {@code start()} rejection when the shared scheduler has
   * concurrently shut down — routes the writer to the detached closer through the lease rather than
   * releasing the permit directly; the close task's completion remains the single permit-release
   * point.
   */
  private @Nullable BatchProcessor tryCreateProcessor() {
    if (!writerPermits.tryAcquire()) {
      droppedWriterPermitExhausted.incrementAndGet();
      logger.severe(
          "Dropping analytics row: live-writer permit cap exhausted (pending StreamWriter closes"
              + " have not completed; likely a Storage outage).");
      return null;
    }
    WriterLease lease = new WriterLease();
    liveLeases.add(lease);
    BatchProcessor p = null;
    try {
      StreamWriter writer = createWriter();
      boolean closeAlreadyRequested = lease.attachWriter(writer);
      if (closeAlreadyRequested || closing) {
        // Plugin close raced admission: do not publish; the writer goes straight to cleanup.
        var unused = dispatchLeaseClose(lease);
        droppedAfterFinalize.incrementAndGet();
        logger.warning("Dropping analytics row: plugin is closing.");
        return null;
      }
      p =
          new BatchProcessor(
              writer,
              config.batchSize(),
              config.batchFlushInterval(),
              config.queueMaxSize(),
              executor,
              appendTimeout(config),
              config.shutdownTimeout(),
              unusedWriter -> {
                var unused = dispatchLeaseClose(lease);
              });
      p.start();
      return p;
    } catch (RuntimeException e) {
      if (p != null) {
        // Constructed but start() failed: processor teardown closes the Arrow resources and
        // dispatches the writer through the lease.
        p.close();
      } else if (!dispatchLeaseClose(lease)) {
        // No writer was ever attached (createWriter itself failed): release directly.
        liveLeases.remove(lease);
        writerPermits.release();
      }
      throw e;
    }
  }

  /**
   * Dispatches the lease's writer to the detached closer exactly once; returns whether a writer was
   * dispatched. The close task retires the lease and releases the permit.
   */
  private boolean dispatchLeaseClose(WriterLease lease) {
    StreamWriter writer = lease.takeForClose();
    if (writer == null) {
      return false;
    }
    submitWriterClose(writer, lease);
    return true;
  }

  /**
   * Detached, owner-preserving StreamWriter close: never blocks the caller; the close task retires
   * the lease and releases the permit — the ONLY permit-release point for an attached writer.
   * Pre-shutdown rejection is impossible (MAX_LIVE_WRITERS &lt;= closer queue capacity); a
   * rejection can therefore only mean the closer service was already shut down, in which case
   * ownership transfers to a bounded daemon reclaim thread rather than abandoning the writer.
   */
  private void submitWriterClose(StreamWriter writer, WriterLease lease) {
    Runnable closeTask =
        () -> {
          try {
            writer.close();
          } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to close BigQuery writer", e);
          } finally {
            liveLeases.remove(lease);
            writerPermits.release();
          }
        };
    try {
      writerCloseExecutor.execute(closeTask);
    } catch (RejectedExecutionException e) {
      // Closer already shut down (this close raced plugin close). Bounded by the permit cap.
      Thread reclaim =
          new Thread(
              closeTask, "bq-analytics-writer-close-reclaim-" + threadCounter.getAndIncrement());
      reclaim.setDaemon(true);
      reclaim.start();
    }
  }

  /**
   * Appends a row for the given invocation, honoring the invocation lifecycle and accounting for
   * every loss mode that can occur before a {@link BatchProcessor} accepts the row:
   *
   * <ul>
   *   <li>A continuation (late parse/offload) completing after {@code ensureInvocationCompleted}
   *       finalized the invocation must not recreate a processor that nothing will ever close; the
   *       row is dropped and counted under {@code late_after_finalize}.
   *   <li>A {@link StreamWriter} construction failure must not silently discard the row; it is
   *       counted under {@code writer_create_error} and surfaced in the log. The processor mapping
   *       is not populated on failure, so a later event retries construction.
   * </ul>
   */
  void appendRow(InvocationLifecycle lifecycle, String invocationId, Map<String, Object> row) {
    BatchProcessor processor;
    try {
      processor = getOrCreateProcessorIfActive(lifecycle, invocationId);
    } catch (RuntimeException e) {
      droppedWriterCreateError.incrementAndGet();
      logger.log(
          Level.SEVERE,
          "Dropping analytics row: failed to create BigQuery writer for invocation " + invocationId,
          e);
      return;
    }
    if (processor == null) {
      // Accounted inside the creation gate (finalized invocation or permit exhaustion).
      return;
    }
    // Admit the row atomically with finalization: without this, a continuation could pass the
    // gate above, get descheduled while finalization closes the processor and delivers its final
    // stats, and then either offer into a drained queue or record an after_close drop the folded
    // snapshot has already missed.
    boolean admitted = lifecycle.runIfActive(() -> processor.append(row));
    if (!admitted) {
      droppedAfterFinalize.incrementAndGet();
      logger.warning(
          "Dropping late analytics row: invocation "
              + invocationId
              + " finalized during admission.");
    }
  }

  /**
   * Atomically returns the invocation's processor, creating it only while the invocation is still
   * active.
   *
   * <p>The {@code isProcessed} check runs INSIDE the {@code computeIfAbsent} mapping function, i.e.
   * under the map's per-key lock, closing the check-then-act race with {@code
   * ensureInvocationCompleted}: finalization marks the invocation processed BEFORE removing the
   * processor mapping, so a continuation that finds no mapping after removal is guaranteed to
   * observe {@code isProcessed == true} and installs nothing (a null mapping-function result adds
   * no entry). If a continuation instead wins the key lock first and creates the processor,
   * finalization subsequently removes and closes that same processor, and the late row is accounted
   * by {@link BatchProcessor#append}'s closed gate.
   *
   * <p>Cache eviction is not a correctness hole: every continuation checks its captured durable
   * {@link InvocationLifecycle} token first, which — unlike the bounded tombstone cache — cannot be
   * evicted, so a finalized invocation's processor cannot be resurrected regardless of cache state.
   */
  private @Nullable BatchProcessor getOrCreateProcessorIfActive(
      InvocationLifecycle lifecycle, String invocationId) {
    BatchProcessor processor =
        batchProcessors.computeIfAbsent(
            invocationId,
            id -> {
              // The durable token is the primary gate (it survives processedInvocations cache
              // eviction); the cache check additionally covers callers holding a token created
              // after
              // an evicted invocation's finalization.
              if (lifecycle.isFinalized() || isProcessed(id)) {
                droppedAfterFinalize.incrementAndGet();
                logger.warning(
                    "Dropping late analytics row: invocation " + id + " is already finalized.");
                return null;
              }
              // Refusal accounting (permit exhaustion / closing race) happens inside.
              return tryCreateProcessor();
            });
    if (processor != null && closing) {
      // Plugin close may have iterated the processor map before this publication became visible.
      // Exactly one party wins the identity-remove: either the close iteration owns the
      // processor, or we self-close it here — never neither.
      if (batchProcessors.remove(invocationId, processor)) {
        processor.closeAndFold(this::foldStats, Instant.now().plus(config.shutdownTimeout()));
      }
      droppedAfterFinalize.incrementAndGet();
      logger.warning("Dropping analytics row: plugin is closing.");
      return null;
    }
    return processor;
  }

  protected @Nullable GcsOffloader getGcsOffloader(BigQueryLoggerConfig config) {
    if (config.gcsBucketName().isEmpty()) {
      return null;
    }
    return new GcsOffloader(
        config.projectId(), config.gcsBucketName(), offloadExecutor, config.credentials(), null);
  }

  Parser getParser() {
    return parser;
  }

  @VisibleForTesting
  Collection<TraceManager> getTraceManagers() {
    return traceManagers.values();
  }

  @VisibleForTesting
  Collection<BatchProcessor> getBatchProcessors() {
    return batchProcessors.values();
  }

  @VisibleForTesting
  TraceManager removeTraceManager(String invocationId) {
    return traceManagers.remove(invocationId);
  }

  @VisibleForTesting
  protected BatchProcessor removeProcessor(String invocationId) {
    return batchProcessors.remove(invocationId);
  }

  void clearTraceManagers() {
    traceManagers.clear();
  }

  void clearBatchProcessors() {
    batchProcessors.clear();
  }

  @VisibleForTesting
  protected Set<CompletableFuture<Void>> getPendingTasksForInvocation(String invocationId) {
    return pendingTasks.computeIfAbsent(invocationId, k -> ConcurrentHashMap.newKeySet());
  }

  // Relies on reference (identity) equality of CompletableFuture: the exact same future instance is
  // added here and removed on completion, which is well-defined under the default Object.equals /
  // hashCode. The set must remain concurrent (ConcurrentHashMap.newKeySet), so a JDK
  // IdentityHashMap-based set is not an option.
  @SuppressWarnings("CollectionUndefinedEquality")
  void addPendingTask(String invocationId, CompletableFuture<Void> task) {
    Set<CompletableFuture<Void>> tasks = getPendingTasksForInvocation(invocationId);
    tasks.add(task);
    var unused = task.whenComplete((res, err) -> tasks.remove(task));
  }

  Completable ensureInvocationCompleted(String invocationId) {
    // ONE absolute deadline for the whole finalization: waiting for pending tasks and draining
    // the processor share it, so shutdownTimeout is the total bound rather than restarting per
    // phase (a stuck parse consuming one full timeout must not grant the drain another).
    // Deferred so the budget starts at subscription, not assembly.
    return Completable.defer(
        () -> {
          Instant finalizeDeadline = Instant.now().plus(config.shutdownTimeout());
          return finalizeInvocation(invocationId, finalizeDeadline);
        });
  }

  private Completable finalizeInvocation(String invocationId, Instant finalizeDeadline) {
    Set<CompletableFuture<Void>> tasks = pendingTasks.get(invocationId);
    Completable tasksState = Completable.complete();
    if (tasks != null && !tasks.isEmpty()) {
      tasksState =
          Completable.fromCompletionStage(
              CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])));
    }
    logger.fine("Waiting for pending tasks to complete for invocation ID: " + invocationId);
    // Idempotent cleanup shared by the completion-ordered andThen (normal path) and doFinally
    // (disposal path): RxJava's doFinally notifies the downstream FIRST and runs its action
    // afterwards, so relying on it alone would let blockingAwait()/subscribers observe success
    // while finalization is still running. andThen(fromAction) runs the cleanup BEFORE the
    // returned Completable completes.
    Action cleanup =
        runOnce(
            () -> {
              // Mark the durable lifecycle token FIRST (before removing the processor), so any
              // continuation that later finds no mapping is guaranteed to observe the terminal
              // state. The map entry is removed for memory bounds; outstanding continuations keep
              // the token reachable through their captured reference.
              InvocationLifecycle lifecycle = lifecycles.remove(invocationId);
              if (lifecycle != null) {
                lifecycle.markFinalized();
              }
              // Mark invocation ID as processed to avoid memory leaks.
              markProcessed(invocationId);
              BatchProcessor processor = removeProcessor(invocationId);
              if (processor != null) {
                // closeAndFold drains under the SAME absolute deadline the pending-task wait
                // consumed from, so the total finalization is bounded by one shutdownTimeout.
                // Folding happens via the teardown callback, which fires when teardown ACTUALLY
                // completes (possibly after close() returns, if an in-flight flush owns the
                // resources past the deadline), so counters recorded by that last flush are never
                // lost.
                processor.closeAndFold(this::foldStats, finalizeDeadline);
              }
              TraceManager traceManager = removeTraceManager(invocationId);
              if (traceManager != null) {
                traceManager.clearStack();
              }
              logger.fine("Removing pending tasks for invocation ID: " + invocationId);
              pendingTasks.remove(invocationId);
            });
    return tasksState
        .timeout(config.shutdownTimeout().toMillis(), MILLISECONDS)
        .doOnError(
            e -> {
              if (e instanceof TimeoutException) {
                logger.log(
                    Level.WARNING,
                    "Timeout while waiting for pending tasks to complete for invocation ID: "
                        + invocationId,
                    e);
              }
            })
        .onErrorComplete()
        .andThen(Completable.fromAction(cleanup))
        .doFinally(cleanup);
  }

  /** Wraps an action so repeated invocations (completion path + disposal path) run it once. */
  private static Action runOnce(Action delegate) {
    AtomicBoolean ran = new AtomicBoolean(false);
    return () -> {
      if (ran.compareAndSet(false, true)) {
        delegate.run();
      }
    };
  }

  /**
   * Drains the closer service's unstarted queue to a bounded reclaim owner, WITHOUT interrupting
   * active closes (they finish naturally on their daemon workers). The drained tasks' writers (each
   * holding an internal client and non-daemon append thread) must still be closed; one daemon
   * thread runs them sequentially, and the backlog is bounded by the writer permit cap.
   */
  private void drainQueuedWriterCloses() {
    List<Runnable> pending = new ArrayList<>();
    writerCloseExecutor.getQueue().drainTo(pending);
    reclaimPendingWriterCloses(pending);
  }

  private void reclaimPendingWriterCloses(List<Runnable> pending) {
    if (pending.isEmpty()) {
      return;
    }
    Thread reclaim =
        new Thread(
            () -> pending.forEach(Runnable::run),
            "bq-analytics-writer-close-reclaim-" + threadCounter.getAndIncrement());
    reclaim.setDaemon(true);
    reclaim.start();
  }

  private void foldStats(ImmutableMap<String, Long> stats) {
    droppedQueueFull.addAndGet(stats.getOrDefault("queue_full", 0L));
    droppedAppendError.addAndGet(stats.getOrDefault("append_error", 0L));
    droppedSerializationError.addAndGet(stats.getOrDefault("serialization_error", 0L));
    droppedAfterClose.addAndGet(stats.getOrDefault("after_close", 0L));
    droppedShutdownTimeout.addAndGet(stats.getOrDefault("shutdown_timeout", 0L));
  }

  /**
   * Aggregated dropped-row counters across closed and still-live BatchProcessors, plus rows lost
   * before a processor existed ({@code writer_create_error}) or after their invocation was
   * finalized ({@code late_after_finalize}). Non-zero values indicate analytics rows that never
   * reached BigQuery.
   */
  ImmutableMap<String, Long> getDropStats() {
    long queueFull = droppedQueueFull.get();
    long appendError = droppedAppendError.get();
    long serializationError = droppedSerializationError.get();
    long afterClose = droppedAfterClose.get();
    long shutdownTimeout = droppedShutdownTimeout.get();
    for (BatchProcessor processor : getBatchProcessors()) {
      ImmutableMap<String, Long> stats = processor.getDropStats();
      queueFull += stats.getOrDefault("queue_full", 0L);
      appendError += stats.getOrDefault("append_error", 0L);
      serializationError += stats.getOrDefault("serialization_error", 0L);
      afterClose += stats.getOrDefault("after_close", 0L);
      shutdownTimeout += stats.getOrDefault("shutdown_timeout", 0L);
    }
    return ImmutableMap.<String, Long>builder()
        .put("queue_full", queueFull)
        .put("append_error", appendError)
        .put("serialization_error", serializationError)
        .put("after_close", afterClose)
        .put("shutdown_timeout", shutdownTimeout)
        .put("writer_permit_exhausted", droppedWriterPermitExhausted.get())
        .put("writer_create_error", droppedWriterCreateError.get())
        .put("late_after_finalize", droppedAfterFinalize.get())
        .buildOrThrow();
  }

  Completable close() {
    // ONE absolute deadline for the whole plugin shutdown: the pending-task wait, every
    // processor's drain, and executor termination all consume from the same shutdownTimeout
    // budget, so total shutdown is bounded by one timeout rather than one per phase/processor.
    // Deferred so the budget starts at subscription, not assembly.
    return Completable.defer(() -> closeInternal(Instant.now().plus(config.shutdownTimeout())));
  }

  private Completable closeInternal(Instant closeDeadline) {
    ImmutableList<CompletableFuture<Void>> tasks =
        pendingTasks.values().stream().flatMap(Set::stream).collect(toImmutableList());
    Completable tasksState = Completable.complete();
    if (tasks != null && !tasks.isEmpty()) {
      tasksState =
          Completable.fromCompletionStage(
              CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])));
    }
    // Completion-ordered cleanup shared with the disposal path; see finalizeInvocation.
    Action cleanup =
        runOnce(
            () -> {
              // Publish the closing gate FIRST: creators observing it refuse admission (or
              // self-close after publication), so the drains below plus the creator-side
              // rechecks cover every interleaving from permit acquisition to publication.
              closing = true;
              for (InvocationLifecycle lifecycle : lifecycles.values()) {
                lifecycle.markFinalized();
              }
              lifecycles.clear();
              // Drain every registered writer lease: constructed writers dispatch to the closer
              // now; writers still mid-construction dispatch when their creator attaches them
              // (attachWriter returns closeRequested).
              for (WriterLease lease : liveLeases) {
                StreamWriter leasedWriter = lease.requestClose();
                if (leasedWriter != null) {
                  submitWriterClose(leasedWriter, lease);
                }
              }
              // Identity-remove each published processor while closing it: a creator racing
              // publication re-checks the closing gate and self-closes if it still owns the
              // mapping — exactly one party wins remove(id, processor), never neither. A blind
              // clear() could silently drop a processor published after this iteration.
              for (Map.Entry<String, BatchProcessor> entry : batchProcessors.entrySet()) {
                if (batchProcessors.remove(entry.getKey(), entry.getValue())) {
                  // Fold via the teardown callback; each drain consumes only the REMAINING
                  // shared budget, so N processors cannot take N timeouts.
                  entry.getValue().closeAndFold(this::foldStats, closeDeadline);
                }
              }
              for (TraceManager traceManager : getTraceManagers()) {
                traceManager.clearStack();
              }
              clearTraceManagers();

              if (writeClient != null) {
                try {
                  writeClient.close();
                } catch (RuntimeException e) {
                  logger.log(Level.WARNING, "Failed to close BigQueryWriteClient", e);
                }
              }
              try {
                executor.shutdown();
                offloadExecutor.shutdown();
                long remainingMillis =
                    java.time.Duration.between(Instant.now(), closeDeadline).toMillis();
                if (remainingMillis <= 0
                    || !executor.awaitTermination(remainingMillis, MILLISECONDS)) {
                  executor.shutdownNow();
                }
                remainingMillis =
                    java.time.Duration.between(Instant.now(), closeDeadline).toMillis();
                if (remainingMillis > 0) {
                  if (!offloadExecutor.awaitTermination(remainingMillis, MILLISECONDS)) {
                    offloadExecutor.shutdownNow();
                  }
                } else {
                  offloadExecutor.shutdownNow();
                }
                // Detached writer closes drain without interruption: active closes finish on
                // their daemon workers, and the unstarted queue transfers to a bounded reclaim
                // owner so no writer loses its cleanup owner.
                writerCloseExecutor.shutdown();
                remainingMillis =
                    java.time.Duration.between(Instant.now(), closeDeadline).toMillis();
                if (remainingMillis <= 0
                    || !writerCloseExecutor.awaitTermination(remainingMillis, MILLISECONDS)) {
                  // Deadline expired with closes still pending. Do NOT shutdownNow(): that would
                  // interrupt ACTIVE closes mid-join, leaving partially-closed writers with no
                  // retry. Instead drain the unstarted queue to a bounded reclaim owner; active
                  // closes run to natural completion on their daemon worker threads.
                  drainQueuedWriterCloses();
                }
              } catch (InterruptedException e) {
                executor.shutdownNow();
                offloadExecutor.shutdownNow();
                writerCloseExecutor.shutdown();
                drainQueuedWriterCloses();
                Thread.currentThread().interrupt();
              }

              try {
                if (offloader != null) {
                  offloader.close();
                }
              } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close GCS offloader", e);
              }
            });
    return tasksState
        .timeout(config.shutdownTimeout().toMillis(), MILLISECONDS)
        .doOnError(
            e -> {
              if (e instanceof TimeoutException) {
                logger.log(
                    Level.WARNING, "Timeout while waiting for pending tasks to complete.", e);
              }
            })
        .onErrorComplete()
        .andThen(Completable.fromAction(cleanup))
        .doFinally(cleanup);
  }
}
