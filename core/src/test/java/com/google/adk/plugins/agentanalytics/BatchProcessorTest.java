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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.RowError;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.collect.ImmutableMap;
import com.google.rpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class BatchProcessorTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private StreamWriter mockWriter;
  private ScheduledExecutorService executor;
  private BatchProcessor batchProcessor;
  private Schema schema;
  private Handler mockHandler;

  private ExecutorService closePool;
  private Consumer<StreamWriter> writerCloser;

  @Before
  public void setUp() {
    executor = Executors.newScheduledThreadPool(1);
    closePool = Executors.newCachedThreadPool();
    // Mirror production: the plugin-owned closer detaches writer closes off the caller thread.
    writerCloser =
        w ->
            closePool.execute(
                () -> {
                  try {
                    w.close();
                  } catch (RuntimeException ignored) {
                    // Best-effort close in tests.
                  }
                });
    batchProcessor =
        new BatchProcessor(
            mockWriter,
            10,
            Duration.ofMinutes(1),
            100,
            executor,
            Duration.ofSeconds(10),
            writerCloser);
    schema = BigQuerySchema.getArrowSchema();

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));

    Logger logger = Logger.getLogger(BatchProcessor.class.getName());
    mockHandler = mock(Handler.class);
    logger.addHandler(mockHandler);
  }

  @After
  public void tearDown() {
    batchProcessor.close();
    executor.shutdown();
  }

  @Test
  public void flush_populatesTimestampFieldCorrectly() throws Exception {
    Instant now = Instant.parse("2026-03-02T19:11:49.631Z");
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", now);
    row.put("event_type", "TEST_EVENT");

    final boolean[] checksPassed = {false};
    final String[] failureMessage = {null};

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                if (root.getRowCount() != 1) {
                  failureMessage[0] = "Expected 1 row, got " + root.getRowCount();
                  return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
                }

                var timestampVector = root.getVector("timestamp");
                if (!(timestampVector instanceof TimeStampMicroTZVector tzVector)) {
                  failureMessage[0] = "Vector should be an instance of TimeStampMicroTZVector";
                  return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
                }
                if (tzVector.isNull(0)) {
                  failureMessage[0] = "Timestamp should NOT be null";
                } else if (tzVector.get(0) != now.toEpochMilli() * 1000) {
                  failureMessage[0] =
                      "Expected " + (now.toEpochMilli() * 1000) + ", got " + tzVector.get(0);
                } else {
                  checksPassed[0] = true;
                }
              } catch (RuntimeException e) {
                failureMessage[0] = "Exception during check: " + e.getMessage();
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    assertTrue(failureMessage[0], checksPassed[0]);
  }

  @Test
  public void flush_populatesAllBasicFields() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());
    row.put("event_type", "BASIC_EVENT");
    row.put("is_truncated", true);

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                assertEquals("BASIC_EVENT", root.getVector("event_type").getObject(0).toString());
                assertEquals(1, ((BitVector) root.getVector("is_truncated")).get(0));
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void flush_populatesJsonFields() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());
    row.put("content", "{\"key\": \"value\"}");
    row.put("attributes", "{\"attr\": 123}");

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                assertEquals(
                    "{\"key\": \"value\"}", root.getVector("content").getObject(0).toString());
                assertEquals(
                    "{\"attr\": 123}", root.getVector("attributes").getObject(0).toString());
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void flush_populatesNestedStructs() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());

    List<Map<String, Object>> contentParts = new ArrayList<>();
    Map<String, Object> part = new HashMap<>();
    part.put("mime_type", "text/plain");
    part.put("text", "hello world");
    part.put("part_index", 0L);
    contentParts.add(part);
    row.put("content_parts", contentParts);

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                ListVector contentPartsVector = (ListVector) root.getVector("content_parts");
                StructVector structVector = (StructVector) contentPartsVector.getDataVector();

                assertEquals(1, ((List<?>) contentPartsVector.getObject(0)).size());
                VarCharVector mimeTypeVector = (VarCharVector) structVector.getChild("mime_type");
                assertEquals("text/plain", mimeTypeVector.getObject(0).toString());

                VarCharVector textVector = (VarCharVector) structVector.getChild("text");
                assertEquals("hello world", textVector.getObject(0).toString());

                BigIntVector partIndexVector = (BigIntVector) structVector.getChild("part_index");
                assertEquals(0L, partIndexVector.get(0));
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void flush_handlesBigQueryErrorResponse() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "ERROR_EVENT");

    AppendRowsResponse responseWithError =
        AppendRowsResponse.newBuilder()
            .setError(Status.newBuilder().setMessage("Global error").build())
            .addRowErrors(RowError.newBuilder().setIndex(0).setMessage("Row error").build())
            .build();

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenReturn(ApiFutures.immediateFuture(responseWithError));

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    // A BigQuery error response must count the batch as dropped under "append_error".
    assertEquals(1L, (long) batchProcessor.getDropStats().get("append_error"));
  }

  @Test
  public void flush_handlesGenericExceptionDuringAppend() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "EXCEPTION_EVENT");

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenThrow(new RuntimeException("Simulated failure"));

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    // A generic append exception must count the batch as dropped under "append_error".
    assertEquals(1L, (long) batchProcessor.getDropStats().get("append_error"));
  }

  @Test
  public void append_triggersFlushWhenBatchSizeReached() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            2,
            Duration.ofMinutes(1),
            10,
            mockExecutor,
            Duration.ofSeconds(10),
            writerCloser);

    Map<String, Object> row = new HashMap<>();
    bp.append(row);
    verify(mockExecutor, never()).execute(any(Runnable.class));

    bp.append(row);
    verify(mockExecutor).execute(any(Runnable.class));
  }

  @Test
  public void flush_doesNothingWhenQueueIsEmpty() throws Exception {
    batchProcessor.flush();
    verify(mockWriter, never()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void flush_handlesNullValues() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());
    row.put("event_type", null);
    row.put("is_truncated", null);

    final boolean[] checksPassed = {false};
    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                assertTrue(root.getVector("event_type").isNull(0));
                assertTrue(root.getVector("is_truncated").isNull(0));
                checksPassed[0] = true;
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    assertTrue("Null checks failed", checksPassed[0]);
  }

  @Test
  public void flush_handlesAllocationFailure() throws Exception {
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "ALLOC_FAIL_EVENT");
    batchProcessor.append(row);
    batchProcessor.allocator.setLimit(1);

    batchProcessor.flush();

    verify(mockWriter, never()).append(any(ArrowRecordBatch.class));
    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockHandler, atLeastOnce()).publish(captor.capture());
    boolean foundError = false;
    for (LogRecord record : captor.getAllValues()) {
      if (record.getLevel().equals(Level.SEVERE)
          && record.getMessage().contains("Failed to write batch to BigQuery")) {
        foundError = true;
        break;
      }
    }
    assertTrue("Expected SEVERE error log not found", foundError);
  }

  @Test
  public void close_flushesAndClosesResources() throws Exception {
    try (BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            10,
            Duration.ofMinutes(1),
            100,
            executor,
            Duration.ofSeconds(10),
            writerCloser)) {
      Map<String, Object> row = new HashMap<>();
      row.put("event_type", "CLOSE_EVENT");
      bp.append(row);
    }

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    verify(mockWriter, Mockito.timeout(2000)).close();
  }

  @Test
  public void close_cancelsPeriodicFlushTask() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledExecutorService realScheduler = Executors.newSingleThreadScheduledExecutor();
    // DoNotMock forbids mocking Future types; use a real, unstarted scheduled task instead.
    ScheduledFuture<?> flushFuture = realScheduler.schedule(() -> {}, 1, TimeUnit.HOURS);
    when(mockExecutor.scheduleWithFixedDelay(
            any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenAnswer(invocation -> flushFuture);
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            10,
            Duration.ofMinutes(1),
            10,
            mockExecutor,
            Duration.ofSeconds(1),
            writerCloser);
    bp.start();

    bp.close();

    // A completed invocation must not leave its periodic flush task scheduled (retaining the
    // closed processor and writer) until plugin-wide shutdown.
    assertTrue(flushFuture.isCancelled());
    realScheduler.shutdownNow();
  }

  @Test
  public void close_isIdempotent() {
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            10,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofSeconds(1),
            writerCloser);

    bp.close();
    bp.close();

    verify(mockWriter, Mockito.timeout(2000).times(1)).close();
  }

  @Test
  public void append_afterClose_dropsWithAccounting() {
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            10,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofSeconds(1),
            writerCloser);
    bp.close();

    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "LATE_EVENT");
    bp.append(row);

    assertEquals(1L, (long) bp.getDropStats().get("after_close"));
    assertEquals(0, bp.queue.size());
  }

  @Test
  public void flush_neverCompletingAppend_isBoundedByShutdownTimeout() {
    when(mockWriter.append(any(ArrowRecordBatch.class))).thenReturn(SettableApiFuture.create());
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofMillis(200),
            writerCloser);
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "STUCK_EVENT");
    bp.queue.offer(row);

    long start = System.nanoTime();
    bp.flush();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        "flush must be bounded by the append deadline, took " + elapsedMs + "ms",
        elapsedMs < 5_000);
    assertEquals(1L, (long) bp.getDropStats().get("append_error"));
  }

  @Test
  public void close_waitsForInFlightFlushBeforeTeardown() throws Exception {
    // A REAL blocked append: the writer call itself parks for 300ms, so the flush thread holds
    // the flush mutex while blocked in Storage Write, with the queue already drained.
    CountDownLatch appendStarted = new CountDownLatch(1);
    Mockito.doAnswer(
            invocation -> {
              appendStarted.countDown();
              Thread.sleep(300);
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            })
        .when(mockWriter)
        .append(any(ArrowRecordBatch.class));
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofSeconds(5),
            writerCloser);

    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "IN_FLIGHT_EVENT");
    bp.queue.offer(row);
    Thread inFlight = new Thread(bp::flush);
    inFlight.start();
    assertTrue(appendStarted.await(2, TimeUnit.SECONDS));

    long start = System.nanoTime();
    bp.close();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    inFlight.join(2000);

    // close() must wait for the in-flight flush to release the mutex before tearing down the
    // writer and Arrow resources underneath it, and still complete within its bound.
    assertTrue(
        "close should have waited for the in-flight flush, took " + elapsedMs + "ms",
        elapsedMs >= 100);
    assertTrue("close should be bounded, took " + elapsedMs + "ms", elapsedMs < 5_000);
    verify(mockWriter, Mockito.timeout(2000)).close();
  }

  @Test
  public void close_deadlineExpired_defersTeardownAndStatsToInFlightFlush() throws Exception {
    // The writer call blocks for LONGER than the close deadline, forcing the deferred-ownership
    // path: close() returns at its bound without tearing down, and the in-flight flush performs
    // the teardown and delivers the final stats (including its own append failure) afterward.
    CountDownLatch appendStarted = new CountDownLatch(1);
    Mockito.doAnswer(
            invocation -> {
              appendStarted.countDown();
              Thread.sleep(1200);
              throw new RuntimeException("append failed after close deadline");
            })
        .when(mockWriter)
        .append(any(ArrowRecordBatch.class));
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofMillis(300),
            writerCloser);

    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "STUCK_EVENT");
    bp.queue.offer(row);
    Thread inFlight = new Thread(bp::flush);
    inFlight.start();
    assertTrue(appendStarted.await(2, TimeUnit.SECONDS));

    CountDownLatch statsDelivered = new CountDownLatch(1);
    AtomicReference<ImmutableMap<String, Long>> finalStats = new AtomicReference<>();
    long start = System.nanoTime();
    bp.closeAndFold(
        stats -> {
          finalStats.set(stats);
          statsDelivered.countDown();
        });
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // close() is bounded by its deadline even though the flush is still blocked.
    assertTrue("close should be bounded, took " + elapsedMs + "ms", elapsedMs < 3_000);
    // Ownership transferred: the in-flight flush finishes, tears down, and delivers the final
    // snapshot INCLUDING the append failure it recorded after close() had already returned.
    assertTrue(
        "final stats should be delivered by the deferred flush",
        statsDelivered.await(5, TimeUnit.SECONDS));
    inFlight.join(2000);
    assertEquals(1L, (long) finalStats.get().get("append_error"));
    verify(mockWriter, Mockito.timeout(2000)).close();
  }

  @Test
  public void close_blockingWriterClose_doesNotBlockTeardown() throws Exception {
    // The real StreamWriter.close() can block for minutes (thread join + client/pool waits); the
    // public close() must not inherit that, while the writer still gets closed eventually.
    CountDownLatch writerClosed = new CountDownLatch(1);
    Mockito.doAnswer(
            invocation -> {
              Thread.sleep(1500);
              writerClosed.countDown();
              return null;
            })
        .when(mockWriter)
        .close();
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            10,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofMillis(300),
            writerCloser);

    long start = System.nanoTime();
    bp.close();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        "close() must not block on StreamWriter.close(), took " + elapsedMs + "ms",
        elapsedMs < 1_000);
    assertTrue(
        "the writer must still be closed eventually", writerClosed.await(5, TimeUnit.SECONDS));
  }

  @Test
  public void flush_normalAppend_boundedByAppendTimeoutNotShutdownTimeout() throws Exception {
    // Regression for the retry-budget issue: in normal operation the per-append deadline is
    // appendTimeout (sized to cover the writer's retry budget), NOT the shorter shutdownTimeout. A
    // batch that completes after shutdownTimeout but within appendTimeout must still succeed rather
    // than being cancelled mid-retry and miscounted as append_error.
    SettableApiFuture<AppendRowsResponse> future = SettableApiFuture.create();
    when(mockWriter.append(any(ArrowRecordBatch.class))).thenReturn(future);
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            /* appendTimeout= */ Duration.ofSeconds(5),
            /* shutdownTimeout= */ Duration.ofMillis(200),
            writerCloser);
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "SLOW_OK");
    bp.queue.offer(row);
    // Completes after the 200ms shutdownTimeout would have fired, but well within appendTimeout.
    var unused =
        executor.schedule(
            () -> future.set(AppendRowsResponse.getDefaultInstance()), 400, TimeUnit.MILLISECONDS);

    bp.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    assertEquals(0L, (long) bp.getDropStats().get("append_error"));
    bp.close();
  }

  @Test
  public void flush_normalOperation_doesNotBoundByCloseDeadline() throws Exception {
    // With no close in progress, appendTimeoutMillis must not consult a (null) close deadline: the
    // append simply uses appendTimeout and succeeds. The mutant that flips the null-check would NPE
    // here and miscount the row as append_error.
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofSeconds(10),
            writerCloser);
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "OK");
    bp.queue.offer(row);

    bp.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    assertEquals(0L, (long) bp.getDropStats().get("append_error"));
    bp.close();
  }

  @Test
  public void flush_withoutClose_doesNotTearDownWriter() throws Exception {
    // teardownRequested must start false: a normal flush (no close) must NOT trigger teardown, or a
    // live processor would close its own writer and Arrow resources after its very first flush.
    AtomicBoolean tornDown = new AtomicBoolean(false);
    Consumer<StreamWriter> markingCloser = w -> tornDown.set(true);
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            Duration.ofSeconds(10),
            markingCloser);
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "NORMAL");
    bp.queue.offer(row);

    bp.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    assertFalse("a normal flush must not tear down a still-open processor", tornDown.get());
    bp.close();
  }

  @Test
  public void close_boundsAppendByRemainingCloseBudgetNotAppendTimeout() throws Exception {
    // During close the per-append deadline must be capped to the REMAINING close budget even when
    // the normal appendTimeout is far larger. Dropping the cap would let the final drain block up
    // to
    // the full appendTimeout.
    when(mockWriter.append(any(ArrowRecordBatch.class))).thenReturn(SettableApiFuture.create());
    BatchProcessor bp =
        new BatchProcessor(
            mockWriter,
            1,
            Duration.ofMinutes(1),
            10,
            executor,
            /* appendTimeout= */ Duration.ofSeconds(30),
            /* shutdownTimeout= */ Duration.ofMillis(200),
            writerCloser);
    Map<String, Object> row = new HashMap<>();
    row.put("event_type", "STUCK");
    bp.queue.offer(row);

    long start = System.nanoTime();
    bp.close();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        "close drain must be bounded by the remaining close budget, took " + elapsedMs + "ms",
        elapsedMs < 5_000);
    assertEquals(1L, (long) bp.getDropStats().get("append_error"));
  }
}
