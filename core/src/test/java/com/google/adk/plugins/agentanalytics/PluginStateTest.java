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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class PluginStateTest {
  private BigQueryLoggerConfig config;
  private TestPluginState pluginState;
  private Handler mockHandler;
  private Logger pluginLogger;
  private Level originalLevel;

  private static class TestPluginState extends PluginState {
    TestPluginState(BigQueryLoggerConfig config) throws IOException {
      super(config);
    }

    private BigQueryWriteClient mockWriteClient;

    @Override
    protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
      mockWriteClient = mock(BigQueryWriteClient.class);
      return mockWriteClient;
    }

    @Override
    protected StreamWriter createWriter() {
      StreamWriter writer = mock(StreamWriter.class);
      when(writer.append(any(ArrowRecordBatch.class)))
          .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.newBuilder().build()));
      return writer;
    }
  }

  @Before
  public void setUp() throws IOException {
    config =
        BigQueryLoggerConfig.builder()
            .projectId("test-project")
            .datasetId("test-dataset")
            .tableName("test-table")
            .gcsBucketName("")
            .build();
    pluginState = new TestPluginState(config);

    pluginLogger = Logger.getLogger(PluginState.class.getName());
    mockHandler = mock(Handler.class);
    originalLevel = pluginLogger.getLevel();
    pluginLogger.setLevel(Level.INFO);
    pluginLogger.addHandler(mockHandler);
  }

  @After
  public void tearDown() {
    pluginLogger.removeHandler(mockHandler);
    pluginLogger.setLevel(originalLevel);
  }

  @Test
  public void getGcsOffloader_emptyBucketName_returnsNull() {
    assertNull(pluginState.getGcsOffloader(config));
  }

  @Test
  public void addPendingTask_removedTaskOnCompletion() {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    task.complete(null);
    pluginState.ensureInvocationCompleted(invocationId).blockingAwait();

    // No specific log to check now, but we verify it completes without error.
  }

  @Test
  public void ensureInvocationCompleted_foldsClosedProcessorDropStats() throws IOException {
    String invocationId = "inv-fold";
    BatchProcessor closedProcessor = mock(BatchProcessor.class);
    ImmutableMap<String, Long> closedStats =
        ImmutableMap.of("queue_full", 5L, "append_error", 3L, "serialization_error", 2L);
    // closeAndFold delivers the final snapshot via its callback at teardown completion.
    org.mockito.Mockito.doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              java.util.function.Consumer<ImmutableMap<String, Long>> consumer =
                  (java.util.function.Consumer<ImmutableMap<String, Long>>)
                      invocation.getArgument(0);
              consumer.accept(closedStats);
              return null;
            })
        .when(closedProcessor)
        .closeAndFold(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    // Completing an invocation removes and closes its processor; each drop counter must be folded
    // into the plugin-level totals so it survives after the per-invocation processor is gone.
    TestPluginState stateWithClosedProcessor =
        new TestPluginState(config) {
          @Override
          protected BatchProcessor removeProcessor(String id) {
            return id.equals(invocationId) ? closedProcessor : super.removeProcessor(id);
          }
        };

    stateWithClosedProcessor.ensureInvocationCompleted(invocationId).blockingAwait();

    ImmutableMap<String, Long> stats = stateWithClosedProcessor.getDropStats();
    assertEquals(5L, (long) stats.get("queue_full"));
    assertEquals(3L, (long) stats.get("append_error"));
    assertEquals(2L, (long) stats.get("serialization_error"));
  }

  @Test
  public void ensureInvocationCompleted_noTasks_succeeds() {
    String invocationId = "testInvocation";

    pluginState.ensureInvocationCompleted(invocationId).test().assertComplete();
  }

  @Test
  public void ensureInvocationCompleted_executionException_completesSuccessfully()
      throws InterruptedException {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    task.completeExceptionally(new RuntimeException("test exception"));

    pluginState.ensureInvocationCompleted(invocationId).test().assertComplete();
  }

  @Test
  public void ensureInvocationCompleted_interrupted_logsNothing() throws InterruptedException {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    Thread testThread =
        new Thread(
            () -> {
              pluginLogger.addHandler(mockHandler);
              pluginState.ensureInvocationCompleted(invocationId).blockingAwait();
            });
    testThread.start();
    Thread.sleep(50);
    testThread.interrupt();
    testThread.join(1000);

    // RxJava handles interruption differently, we just verify it doesn't crash here.
  }

  @Test
  public void ensureInvocationCompleted_timeout_logsWarning() throws IOException {
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(100)).build();
    pluginState = new TestPluginState(config);

    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>(); // Never completes
    pluginState.addPendingTask(invocationId, task);

    pluginState.ensureInvocationCompleted(invocationId).test().awaitDone(1, SECONDS);

    // Wait for cleanup side effects which run after terminal signal.
    long deadline = Instant.now().plusMillis(1000).toEpochMilli();
    while (!pluginState.isProcessed(invocationId) && Instant.now().toEpochMilli() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockHandler, atLeastOnce()).publish(captor.capture());

    boolean found =
        captor.getAllValues().stream()
            .anyMatch(
                record ->
                    record.getLevel().equals(Level.WARNING)
                        && record
                            .getMessage()
                            .contains("Timeout while waiting for pending tasks to complete"));
    assertTrue(
        "Expected log message 'Timeout while waiting for pending tasks to complete' not found",
        found);
  }

  @Test
  public void ensureInvocationCompleted_timeout_cleansUpState() throws IOException {
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(100)).build();
    pluginState = new TestPluginState(config);

    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>(); // Never completes
    pluginState.addPendingTask(invocationId, task);

    // Populate processor and trace manager.
    var unusedProcessor = pluginState.getBatchProcessor(invocationId);
    var unusedTraceManager = pluginState.getTraceManager(invocationId);

    pluginState.ensureInvocationCompleted(invocationId).test().awaitDone(1, SECONDS);

    // Wait for cleanup side effects which run after terminal signal.
    long deadline = Instant.now().plusMillis(1000).toEpochMilli();
    while ((!pluginState.getBatchProcessors().isEmpty()
            || !pluginState.getTraceManagers().isEmpty())
        && Instant.now().toEpochMilli() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify cleanup
    assertTrue(
        "Invocation ID should be marked as processed", pluginState.isProcessed(invocationId));
    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertTrue(pluginState.getTraceManagers().isEmpty());
  }

  @Test
  public void close_succeedsAndCleansUp() throws Exception {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    // Populate processor and trace manager.
    var unusedProcessor = pluginState.getBatchProcessor(invocationId);
    var unusedTraceManager = pluginState.getTraceManager(invocationId);

    // Complete the task so close doesn't time out.
    task.complete(null);

    pluginState.close().test().assertComplete();

    // Verify cleanup
    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertTrue(pluginState.getTraceManagers().isEmpty());
    assertTrue(pluginState.getExecutor().isShutdown());
  }

  @Test
  public void close_respectsRemainingTimeoutBudget() throws Exception {
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(500)).build();
    pluginState = new TestPluginState(config);

    ExecutorService mockOffloadExecutor = mock(ExecutorService.class);
    Field field = PluginState.class.getDeclaredField("offloadExecutor");
    field.setAccessible(true);
    field.set(pluginState, mockOffloadExecutor);

    pluginState
        .getExecutor()
        .execute(
            () -> {
              Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(200));
            });

    when(mockOffloadExecutor.awaitTermination(any(Long.class), any(TimeUnit.class)))
        .thenReturn(true);

    pluginState.close().test().awaitDone(2, SECONDS);

    ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
    verify(mockOffloadExecutor).awaitTermination(timeoutCaptor.capture(), any(TimeUnit.class));

    long capturedTimeout = timeoutCaptor.getValue();
    assertTrue("Timeout should be less than 400", capturedTimeout < 400);
    assertTrue("Timeout should be greater than 100", capturedTimeout > 100);
  }

  @Test
  public void close_closesGcsOffloader() throws Exception {
    GcsOffloader mockOffloader = mock(GcsOffloader.class);
    BigQueryLoggerConfig gcsConfig = config.toBuilder().gcsBucketName("test-bucket").build();
    PluginState gcsState =
        new TestPluginState(gcsConfig) {
          @Override
          protected GcsOffloader getGcsOffloader(BigQueryLoggerConfig config) {
            return mockOffloader;
          }
        };

    gcsState.close().test().assertComplete();

    verify(mockOffloader).close();
  }

  @Test
  public void appendRow_writerCreationFails_countsDropAndAllowsRetry() throws IOException {
    TestPluginState failingState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            throw new IllegalStateException("writer construction failed");
          }
        };

    failingState.appendRow(
        failingState.getLifecycle("inv-writer-fail"),
        "inv-writer-fail",
        ImmutableMap.of("event_type", "LLM_REQUEST"));

    assertEquals(1L, (long) failingState.getDropStats().get("writer_create_error"));
    // The processor mapping must not be populated on failure, so a later event retries
    // construction instead of being permanently broken.
    assertTrue(failingState.getBatchProcessors().isEmpty());
  }

  @Test
  public void appendRow_afterFinalize_dropsWithoutRecreatingProcessor() {
    String invocationId = "inv-late";
    PluginState.InvocationLifecycle lifecycle = pluginState.getLifecycle(invocationId);
    pluginState.markProcessed(invocationId);

    // A parse/offload continuation completing after the invocation was finalized must not
    // recreate a BatchProcessor that nothing will ever close.
    pluginState.appendRow(lifecycle, invocationId, ImmutableMap.of("event_type", "LLM_REQUEST"));

    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertEquals(1L, (long) pluginState.getDropStats().get("late_after_finalize"));
  }

  @Test
  public void appendRow_finalizedToken_dropsEvenAfterTombstoneEviction() throws Exception {
    String invocationId = "inv-evicted";
    // The continuation captures its lifecycle token at logEvent time, while the invocation is
    // active.
    PluginState.InvocationLifecycle lifecycle = pluginState.getLifecycle(invocationId);

    pluginState.ensureInvocationCompleted(invocationId).blockingAwait();

    // Simulate processed-cache eviction (size/TTL): invalidate the tombstone entirely, so the
    // bounded cache can no longer gate the late continuation.
    Field cacheField = PluginState.class.getDeclaredField("processedInvocations");
    cacheField.setAccessible(true);
    ((com.google.common.cache.Cache<?, ?>) cacheField.get(pluginState)).invalidateAll();
    assertTrue(!pluginState.isProcessed(invocationId));

    // The captured token is durable: the late continuation must still be dropped and must not
    // resurrect a processor (writer, allocator, periodic task) for the finalized invocation.
    pluginState.appendRow(lifecycle, invocationId, ImmutableMap.of("event_type", "LLM_REQUEST"));

    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertEquals(1L, (long) pluginState.getDropStats().get("late_after_finalize"));
  }

  @Test
  public void manyCompletedInvocations_leaveNoRetainedProcessorsOrTraceManagers() {
    for (int i = 0; i < 100; i++) {
      String invocationId = "inv-" + i;
      var unusedProcessor = pluginState.getBatchProcessor(invocationId);
      var unusedTraceManager = pluginState.getTraceManager(invocationId);
      pluginState.ensureInvocationCompleted(invocationId).blockingAwait();
    }

    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertTrue(pluginState.getTraceManagers().isEmpty());
  }

  @Test
  public void appendRow_admissionIsAtomicWithFinalization() throws Exception {
    String invocationId = "inv-atomic";
    PluginState.InvocationLifecycle lifecycle = pluginState.getLifecycle(invocationId);

    // Model a continuation inside the admission critical section (post token-gate, mid-append):
    // finalization must block on the token monitor until the admission completes, so the admitted
    // row is drained by close rather than stranded or double-counted after the final snapshot.
    java.util.concurrent.CountDownLatch inAdmission = new java.util.concurrent.CountDownLatch(1);
    Thread admitting =
        new Thread(
            () ->
                lifecycle.runIfActive(
                    () -> {
                      inAdmission.countDown();
                      Uninterruptibles.sleepUninterruptibly(java.time.Duration.ofMillis(300));
                    }));
    admitting.start();
    assertTrue(inAdmission.await(2, TimeUnit.SECONDS));

    long start = System.nanoTime();
    pluginState.ensureInvocationCompleted(invocationId).blockingAwait();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    admitting.join(2000);

    assertTrue(
        "finalization must wait for the in-flight admission, took " + elapsedMs + "ms",
        elapsedMs >= 250);

    // A continuation arriving after finalization is refused atomically and accounted.
    pluginState.appendRow(lifecycle, invocationId, ImmutableMap.of("event_type", "LLM_REQUEST"));
    assertEquals(1L, (long) pluginState.getDropStats().get("late_after_finalize"));
  }

  @Test
  public void ensureInvocationCompleted_multiBatchDrain_boundedByOneShutdownTimeout()
      throws IOException {
    // Every queued batch must drain under ONE close-owned deadline; a pre-close flush would grant
    // the first batch a separate full append budget, doubling the effective bound.
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(500)).build();
    TestPluginState slowState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            StreamWriter writer = mock(StreamWriter.class);
            // Appends never complete in time; each get() must be capped by the REMAINING budget.
            when(writer.append(any(ArrowRecordBatch.class)))
                .thenReturn(com.google.api.core.SettableApiFuture.create());
            return writer;
          }
        };
    String invocationId = "inv-multibatch";
    BatchProcessor processor = slowState.getBatchProcessor(invocationId);
    java.util.Map<String, Object> row1 = new java.util.HashMap<>();
    row1.put("event_type", "A");
    java.util.Map<String, Object> row2 = new java.util.HashMap<>();
    row2.put("event_type", "B");
    processor.queue.offer(row1);
    processor.queue.offer(row2);

    long start = System.nanoTime();
    slowState.ensureInvocationCompleted(invocationId).blockingAwait();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // Tight bound: the old per-phase restart behavior took ~2x (>=1000ms with a 500ms timeout);
    // one shared absolute deadline finishes in ~one timeout plus scheduling slack.
    assertTrue(
        "multi-batch drain must fit one shutdownTimeout bound, took " + elapsedMs + "ms",
        elapsedMs < 900);
  }

  @Test
  public void ensureInvocationCompleted_pendingTaskPlusDrain_shareOneDeadline() throws IOException {
    // A stuck pending task consumes the budget; the processor drain must NOT get a fresh one.
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(500)).build();
    TestPluginState slowState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            StreamWriter writer = mock(StreamWriter.class);
            when(writer.append(any(ArrowRecordBatch.class)))
                .thenReturn(com.google.api.core.SettableApiFuture.create());
            return writer;
          }
        };
    String invocationId = "inv-task-plus-drain";
    slowState.addPendingTask(invocationId, new CompletableFuture<>()); // never completes
    BatchProcessor processor = slowState.getBatchProcessor(invocationId);
    java.util.Map<String, Object> row = new java.util.HashMap<>();
    row.put("event_type", "A");
    processor.queue.offer(row);

    long start = System.nanoTime();
    slowState.ensureInvocationCompleted(invocationId).blockingAwait();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        "pending-task wait plus drain must share one shutdownTimeout, took " + elapsedMs + "ms",
        elapsedMs < 900);
  }

  @Test
  public void close_multipleStuckProcessors_shareOneDeadline() throws IOException {
    // N processors with never-completing appends must not take N sequential timeouts.
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(500)).build();
    TestPluginState slowState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            StreamWriter writer = mock(StreamWriter.class);
            when(writer.append(any(ArrowRecordBatch.class)))
                .thenReturn(com.google.api.core.SettableApiFuture.create());
            return writer;
          }
        };
    for (int i = 0; i < 2; i++) {
      BatchProcessor processor = slowState.getBatchProcessor("inv-close-" + i);
      java.util.Map<String, Object> row = new java.util.HashMap<>();
      row.put("event_type", "A");
      processor.queue.offer(row);
    }

    long start = System.nanoTime();
    slowState.close().blockingAwait();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // Old behavior was ~one deadline PER processor (>=1000ms for two 500ms drains) before
    // executor waits; the shared absolute deadline finishes in ~one timeout plus slack.
    assertTrue(
        "multi-processor close must share one shutdownTimeout, took " + elapsedMs + "ms",
        elapsedMs < 900);
  }

  @Test
  public void ensureInvocationCompleted_doesNotCompleteBeforeCleanupFinishes() throws Exception {
    // RxJava's doFinally notifies the downstream BEFORE running its action; finalization must be
    // completion-ordered so callers cannot observe success while cleanup is still running.
    String invocationId = "inv-ordered";
    java.util.concurrent.CountDownLatch cleanupStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch cleanupRelease = new java.util.concurrent.CountDownLatch(1);
    BatchProcessor blockingProcessor = mock(BatchProcessor.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              cleanupStarted.countDown();
              cleanupRelease.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(blockingProcessor)
        .closeAndFold(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    TestPluginState orderedState =
        new TestPluginState(config) {
          @Override
          protected BatchProcessor removeProcessor(String id) {
            return id.equals(invocationId) ? blockingProcessor : super.removeProcessor(id);
          }
        };
    CompletableFuture<Void> pending = new CompletableFuture<>();
    orderedState.addPendingTask(invocationId, pending);

    java.util.concurrent.atomic.AtomicBoolean observedComplete =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    var unused =
        orderedState
            .ensureInvocationCompleted(invocationId)
            .subscribe(() -> observedComplete.set(true));

    // Complete the pending task ASYNCHRONOUSLY so the chain advances on another thread and
    // blocks inside the (latched) cleanup.
    Thread completer = new Thread(() -> pending.complete(null));
    completer.start();
    assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS));

    // Cleanup is running but blocked: the returned Completable must NOT have completed.
    Thread.sleep(100);
    assertTrue(
        "completion must not be observable before cleanup finishes", !observedComplete.get());

    cleanupRelease.countDown();
    completer.join(2000);
    long deadline = Instant.now().plusMillis(2000).toEpochMilli();
    while (!observedComplete.get() && Instant.now().toEpochMilli() < deadline) {
      Thread.sleep(10);
    }
    assertTrue("completion must be observable after cleanup finishes", observedComplete.get());
  }

  @Test
  public void manyInvocationsWithBlockedWriterClose_boundedCloserThreads() throws Exception {
    // A Storage outage makes every StreamWriter.close() block. Detached closes must run on the
    // plugin-owned BOUNDED service: invocation throughput must not translate into raw-thread
    // growth (one blocked closer per completed invocation would exhaust native threads).
    java.util.concurrent.CountDownLatch closeRelease = new java.util.concurrent.CountDownLatch(1);
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(300)).build();
    TestPluginState blockedState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            StreamWriter writer = mock(StreamWriter.class);
            when(writer.append(any(ArrowRecordBatch.class)))
                .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      closeRelease.await(10, TimeUnit.SECONDS);
                      return null;
                    })
                .when(writer)
                .close();
            return writer;
          }
        };

    // Other plugin instances in this JVM (from sibling tests) may have idle closer threads;
    // assert on the DELTA this instance produces across 25 blocked-close invocations.
    long closerThreadsBefore =
        Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().startsWith("bq-analytics-writer-close-"))
            .count();

    for (int i = 0; i < 25; i++) {
      String invocationId = "inv-blocked-close-" + i;
      var unusedProcessor = blockedState.getBatchProcessor(invocationId);
      blockedState.ensureInvocationCompleted(invocationId).blockingAwait();
    }

    long closerThreadsAfter =
        Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().startsWith("bq-analytics-writer-close-"))
            .count();
    long delta = closerThreadsAfter - closerThreadsBefore;
    assertTrue(
        "closer thread growth must be bounded by the pool size, grew by " + delta, delta <= 2);
    // All processors are gone despite the blocked closes.
    assertTrue(blockedState.getBatchProcessors().isEmpty());

    closeRelease.countDown();
    // Plugin shutdown completes within its bound even with a close backlog.
    blockedState.close().test().awaitDone(5, SECONDS).assertComplete();
  }

  @Test
  public void writerPermitCap_boundsLiveWritersAndPreservesCleanupOwnership() throws Exception {
    // Every StreamWriter owns an internal client and a NON-DAEMON append thread; the permit cap
    // must refuse new writers (with accounting) once closes back up, and every writer that WAS
    // constructed must be closed exactly once when the backlog drains.
    java.util.concurrent.CountDownLatch closeRelease = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger writersCreated =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger writersClosed =
        new java.util.concurrent.atomic.AtomicInteger();
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(200)).build();
    TestPluginState cappedState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            writersCreated.incrementAndGet();
            StreamWriter writer = mock(StreamWriter.class);
            when(writer.append(any(ArrowRecordBatch.class)))
                .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      closeRelease.await(20, TimeUnit.SECONDS);
                      writersClosed.incrementAndGet();
                      return null;
                    })
                .when(writer)
                .close();
            return writer;
          }
        };

    // Exhaust the permit cap: every finalized invocation's writer close is blocked, so permits
    // are never returned.
    for (int i = 0; i < PluginState.MAX_LIVE_WRITERS; i++) {
      String invocationId = "inv-permit-" + i;
      cappedState.appendRow(
          cappedState.getLifecycle(invocationId),
          invocationId,
          ImmutableMap.of("event_type", "LLM_REQUEST"));
      cappedState.ensureInvocationCompleted(invocationId).blockingAwait();
    }
    assertEquals(PluginState.MAX_LIVE_WRITERS, writersCreated.get());

    // One more invocation: refused BEFORE construction, with accounting — no new writer exists
    // that could lose its cleanup owner.
    cappedState.appendRow(
        cappedState.getLifecycle("inv-over-cap"),
        "inv-over-cap",
        ImmutableMap.of("event_type", "LLM_REQUEST"));
    assertEquals(PluginState.MAX_LIVE_WRITERS, writersCreated.get());
    assertEquals(1L, (long) cappedState.getDropStats().get("writer_permit_exhausted"));

    // Drain the backlog: every constructed writer is closed exactly once.
    closeRelease.countDown();
    long deadline = Instant.now().plusMillis(10_000).toEpochMilli();
    while (writersClosed.get() < PluginState.MAX_LIVE_WRITERS
        && Instant.now().toEpochMilli() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(PluginState.MAX_LIVE_WRITERS, writersClosed.get());
  }

  @Test
  public void close_pastDeadline_queuedWriterClosesRetainCleanupOwnership() throws Exception {
    // Plugin close() past its deadline drains the closer's unstarted queue to a bounded reclaim
    // owner WITHOUT interrupting active closes. No writer may lose its cleanup owner.
    java.util.concurrent.CountDownLatch closeRelease = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger writersClosed =
        new java.util.concurrent.atomic.AtomicInteger();
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(300)).build();
    TestPluginState blockedState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            StreamWriter writer = mock(StreamWriter.class);
            when(writer.append(any(ArrowRecordBatch.class)))
                .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      closeRelease.await(20, TimeUnit.SECONDS);
                      writersClosed.incrementAndGet();
                      return null;
                    })
                .when(writer)
                .close();
            return writer;
          }
        };

    // 5 finalized invocations: 2 closes become active (and block), 3 sit queued.
    int writers = 5;
    for (int i = 0; i < writers; i++) {
      String invocationId = "inv-owned-" + i;
      var unusedProcessor = blockedState.getBatchProcessor(invocationId);
      blockedState.ensureInvocationCompleted(invocationId).blockingAwait();
    }

    // Plugin shutdown times out on the blocked closers and drains the unstarted queue to the
    // bounded reclaim owner, leaving the two active closes uninterrupted.
    blockedState.close().test().awaitDone(5, SECONDS).assertComplete();

    // Release: active closes finish naturally AND the drained queue runs via the reclaim owner.
    closeRelease.countDown();
    long deadline = Instant.now().plusMillis(10_000).toEpochMilli();
    while (writersClosed.get() < writers && Instant.now().toEpochMilli() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(
        "every constructed writer must be closed exactly once", writers, writersClosed.get());
  }

  @Test
  public void close_racingWriterConstruction_writerClosedExactlyOnce() throws Exception {
    // Plugin close() can run while a creator holds a permit and is still inside createWriter().
    // The lease registered before construction must ensure the writer — constructed AFTER the
    // close drained the leases — is still closed exactly once and never published.
    java.util.concurrent.CountDownLatch constructionStarted =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch constructionRelease =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger writersClosed =
        new java.util.concurrent.atomic.AtomicInteger();
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(300)).build();
    TestPluginState racingState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            constructionStarted.countDown();
            try {
              constructionRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            StreamWriter writer = mock(StreamWriter.class);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      writersClosed.incrementAndGet();
                      return null;
                    })
                .when(writer)
                .close();
            return writer;
          }
        };

    String invocationId = "inv-racing";
    Thread creator =
        new Thread(
            () ->
                racingState.appendRow(
                    racingState.getLifecycle(invocationId),
                    invocationId,
                    ImmutableMap.of("event_type", "LLM_REQUEST")));
    creator.start();
    assertTrue(constructionStarted.await(2, TimeUnit.SECONDS));

    // Plugin close runs while construction is blocked; it must stay bounded.
    long start = System.nanoTime();
    racingState.close().test().awaitDone(5, SECONDS).assertComplete();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertTrue("plugin close must stay bounded, took " + elapsedMs + "ms", elapsedMs < 3_000);

    // Release construction: the creator observes the drained lease and dispatches the close
    // itself instead of publishing.
    constructionRelease.countDown();
    creator.join(3000);
    long deadline = Instant.now().plusMillis(3000).toEpochMilli();
    while (writersClosed.get() < 1 && Instant.now().toEpochMilli() < deadline) {
      Thread.sleep(10);
    }
    assertEquals("the racing writer must be closed exactly once", 1, writersClosed.get());
    assertTrue(
        "no processor may be published after close", racingState.getBatchProcessors().isEmpty());
  }

  @Test
  public void startupRejection_writerStillClosedExactlyOnce() throws Exception {
    // p.start() fails when the shared scheduler has concurrently shut down. The already
    // constructed writer must be routed to the detached closer through its lease, not abandoned
    // with a directly released permit.
    java.util.concurrent.atomic.AtomicInteger writersClosed =
        new java.util.concurrent.atomic.AtomicInteger();
    TestPluginState rejectingState =
        new TestPluginState(config) {
          @Override
          protected StreamWriter createWriter() {
            StreamWriter writer = mock(StreamWriter.class);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      writersClosed.incrementAndGet();
                      return null;
                    })
                .when(writer)
                .close();
            return writer;
          }
        };
    // Force start() to throw RejectedExecutionException.
    rejectingState.getExecutor().shutdownNow();

    String invocationId = "inv-start-reject";
    rejectingState.appendRow(
        rejectingState.getLifecycle(invocationId),
        invocationId,
        ImmutableMap.of("event_type", "LLM_REQUEST"));

    assertEquals(1L, (long) rejectingState.getDropStats().get("writer_create_error"));
    assertTrue(rejectingState.getBatchProcessors().isEmpty());
    long deadline = Instant.now().plusMillis(3000).toEpochMilli();
    while (writersClosed.get() < 1 && Instant.now().toEpochMilli() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(
        "the writer from the failed startup must be closed exactly once", 1, writersClosed.get());
  }
}
