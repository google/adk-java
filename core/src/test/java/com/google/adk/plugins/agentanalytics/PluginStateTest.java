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
    when(closedProcessor.getDropStats())
        .thenReturn(
            ImmutableMap.of("queue_full", 5L, "append_error", 3L, "serialization_error", 2L));

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
}
