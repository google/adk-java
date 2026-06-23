package com.google.adk.plugins.agentanalytics;

import static com.google.adk.plugins.agentanalytics.BigQueryUtils.getVersionHeaderValue;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

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
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.threeten.bp.Duration;
import org.threeten.bp.Instant;

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

  private final BigQueryLoggerConfig config;
  private final ScheduledExecutorService executor;
  private final ExecutorService offloadExecutor;
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

  PluginState(BigQueryLoggerConfig config) throws IOException {
    this.config = config;
    this.executor =
        Executors.newScheduledThreadPool(
            2, r -> new Thread(r, "bq-analytics-plugin-" + threadCounter.getAndIncrement()));
    this.offloadExecutor = createGcsOffloadThreadPool();
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
      logger.info("Invocation ID: " + invocationId + "  already processed");
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
          .setRetrySettings(retrySettings)
          .setWriterSchema(BigQuerySchema.getArrowSchema())
          .build();
    } catch (Exception e) {
      throw new VerifyException("Failed to create StreamWriter for " + streamName, e);
    }
  }

  @VisibleForTesting
  String getStreamName(BigQueryLoggerConfig config) {
    return String.format(
        "projects/%s/datasets/%s/tables/%s/streams/_default",
        config.projectId(), config.datasetId(), config.tableName());
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
          BatchProcessor p =
              new BatchProcessor(
                  createWriter(),
                  config.batchSize(),
                  config.batchFlushInterval(),
                  config.queueMaxSize(),
                  executor);
          p.start();
          return p;
        });
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

  void addPendingTask(String invocationId, CompletableFuture<Void> task) {
    Set<CompletableFuture<Void>> tasks = getPendingTasksForInvocation(invocationId);
    tasks.add(task);
    var unused = task.whenComplete((res, err) -> tasks.remove(task));
  }

  Completable ensureInvocationCompleted(String invocationId) {
    Set<CompletableFuture<Void>> tasks = pendingTasks.get(invocationId);
    Completable tasksState = Completable.complete();
    if (tasks != null && !tasks.isEmpty()) {
      tasksState =
          Completable.fromCompletionStage(
              CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])));
    }
    logger.info("Waiting for pending tasks to complete for invocation ID: " + invocationId);
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
        .doFinally(
            () -> {
              // Mark invocation ID as processed to avoid memory leaks.
              markProcessed(invocationId);
              BatchProcessor processor = removeProcessor(invocationId);
              if (processor != null) {
                processor.flush();
                processor.close();
              }
              TraceManager traceManager = removeTraceManager(invocationId);
              if (traceManager != null) {
                traceManager.clearStack();
              }
              logger.info("Removing pending tasks for invocation ID: " + invocationId);
              pendingTasks.remove(invocationId);
            });
  }

  Completable close() {
    ImmutableList<CompletableFuture<Void>> tasks =
        pendingTasks.values().stream().flatMap(Set::stream).collect(toImmutableList());
    Completable tasksState = Completable.complete();
    if (tasks != null && !tasks.isEmpty()) {
      tasksState =
          Completable.fromCompletionStage(
              CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])));
    }
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
        .doFinally(
            () -> {
              for (BatchProcessor processor : getBatchProcessors()) {
                processor.close();
              }
              for (TraceManager traceManager : getTraceManagers()) {
                traceManager.clearStack();
              }
              clearBatchProcessors();
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
                long totalTimeoutMillis = config.shutdownTimeout().toMillis();
                Instant startTime = Instant.now();
                if (!executor.awaitTermination(totalTimeoutMillis, MILLISECONDS)) {
                  executor.shutdownNow();
                }
                long elapsedTimeMillis = Duration.between(startTime, Instant.now()).toMillis();
                long remainingMillis = totalTimeoutMillis - elapsedTimeMillis;
                if (remainingMillis > 0) {
                  if (!offloadExecutor.awaitTermination(remainingMillis, MILLISECONDS)) {
                    offloadExecutor.shutdownNow();
                  }
                } else {
                  offloadExecutor.shutdownNow();
                }
              } catch (InterruptedException e) {
                executor.shutdownNow();
                offloadExecutor.shutdownNow();
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
  }
}
