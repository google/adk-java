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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.RowError;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.rpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

  @Before
  public void setUp() {
    executor = Executors.newScheduledThreadPool(1);
    batchProcessor = new BatchProcessor(mockWriter, 10, Duration.ofMinutes(1), 100, executor);
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
  }

  @Test
  public void append_triggersFlushWhenBatchSizeReached() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    BatchProcessor bp = new BatchProcessor(mockWriter, 2, Duration.ofMinutes(1), 10, mockExecutor);

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
        new BatchProcessor(mockWriter, 10, Duration.ofMinutes(1), 100, executor)) {
      Map<String, Object> row = new HashMap<>();
      row.put("event_type", "CLOSE_EVENT");
      bp.append(row);
    }

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    verify(mockWriter).close();
  }
}
