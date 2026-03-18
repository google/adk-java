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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.Exceptions.AppendSerializationError;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.annotations.VisibleForTesting;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/** Handles asynchronous batching and writing of events to BigQuery. */
class BatchProcessor implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(BatchProcessor.class.getName());

  private final StreamWriter writer;
  private final int batchSize;
  private final Duration flushInterval;
  @VisibleForTesting final BlockingQueue<Map<String, Object>> queue;
  private final ScheduledExecutorService executor;
  @VisibleForTesting final BufferAllocator allocator;
  final AtomicBoolean flushLock = new AtomicBoolean(false);
  private final Schema arrowSchema;
  private final VectorSchemaRoot root;

  public BatchProcessor(
      StreamWriter writer,
      int batchSize,
      Duration flushInterval,
      int queueMaxSize,
      ScheduledExecutorService executor) {
    this.writer = writer;
    this.batchSize = batchSize;
    this.flushInterval = flushInterval;
    this.queue = new LinkedBlockingQueue<>(queueMaxSize);
    this.executor = executor;
    // It's safe to use Long.MAX_VALUE here as this is a top-level RootAllocator,
    // and memory is properly managed via try-with-resources in the flush() method.
    // The actual memory usage is bounded by the batchSize and individual row sizes.
    this.allocator = new RootAllocator(Long.MAX_VALUE);
    this.arrowSchema = BigQuerySchema.getArrowSchema();
    this.root = VectorSchemaRoot.create(arrowSchema, allocator);
  }

  public void start() {
    @SuppressWarnings("unused")
    var unused =
        executor.scheduleWithFixedDelay(
            () -> {
              try {
                flush();
              } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Error in background flush", e);
              }
            },
            flushInterval.toMillis(),
            flushInterval.toMillis(),
            MILLISECONDS);
  }

  public void append(Map<String, Object> row) {
    if (!queue.offer(row)) {
      logger.warning("BigQuery event queue is full, dropping event.");
      return;
    }
    if (queue.size() >= batchSize && !flushLock.get()) {
      executor.execute(this::flush);
    }
  }

  public void flush() {
    // Acquire the flushLock. If another flush is already in progress, return immediately.
    if (!flushLock.compareAndSet(false, true)) {
      return;
    }
    try {
      if (queue.isEmpty()) {
        return;
      }
      List<Map<String, Object>> batch = new ArrayList<>();
      queue.drainTo(batch, batchSize);
      if (batch.isEmpty()) {
        return;
      }
      try {
        root.allocateNew();
        for (int i = 0; i < batch.size(); i++) {
          Map<String, Object> row = batch.get(i);
          for (Field field : arrowSchema.getFields()) {
            populateVector(root.getVector(field.getName()), i, row.get(field.getName()));
          }
        }
        root.setRowCount(batch.size());
        try (ArrowRecordBatch recordBatch = new VectorUnloader(root).getRecordBatch()) {
          AppendRowsResponse result = writer.append(recordBatch).get();
          if (result.hasError()) {
            logger.severe("BigQuery append error: " + result.getError().getMessage());
            for (var error : result.getRowErrorsList()) {
              logger.severe(
                  String.format("Row error at index %d: %s", error.getIndex(), error.getMessage()));
            }
          } else {
            logger.fine("Successfully wrote " + batch.size() + " rows to BigQuery.");
          }
        } catch (AppendSerializationError ase) {
          logger.log(
              Level.SEVERE, "Failed to write batch to BigQuery due to serialization error", ase);
          Map<Integer, String> rowIndexToErrorMessage = ase.getRowIndexToErrorMessage();
          if (rowIndexToErrorMessage != null && !rowIndexToErrorMessage.isEmpty()) {
            logger.severe("Row-level errors found:");
            for (Map.Entry<Integer, String> entry : rowIndexToErrorMessage.entrySet()) {
              logger.severe(
                  String.format("Row error at index %d: %s", entry.getKey(), entry.getValue()));
            }
          } else {
            logger.severe(
                "AppendSerializationError occurred, but no row-specific errors were provided.");
          }
        }
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        logger.log(Level.SEVERE, "Failed to write batch to BigQuery", e);
      } finally {
        // Clear the vectors to release the memory.
        root.clear();
      }
    } finally {
      flushLock.set(false);
      if (queue.size() >= batchSize && !flushLock.get()) {
        executor.execute(this::flush);
      }
    }
  }

  private void populateVector(FieldVector vector, int index, Object value) {
    if (value == null || (value instanceof JsonNode jsonNode && jsonNode.isNull())) {
      vector.setNull(index);
      return;
    }
    if (vector instanceof VarCharVector varCharVector) {
      String strValue = (value instanceof JsonNode jsonNode) ? jsonNode.asText() : value.toString();
      varCharVector.setSafe(index, strValue.getBytes(UTF_8));
    } else if (vector instanceof BigIntVector bigIntVector) {
      long longValue;
      if (value instanceof JsonNode jsonNode) {
        longValue = jsonNode.asLong();
      } else if (value instanceof Number number) {
        longValue = number.longValue();
      } else {
        longValue = Long.parseLong(value.toString());
      }
      bigIntVector.setSafe(index, longValue);
    } else if (vector instanceof BitVector bitVector) {
      boolean boolValue =
          (value instanceof JsonNode jsonNode) ? jsonNode.asBoolean() : (Boolean) value;
      bitVector.setSafe(index, boolValue ? 1 : 0);
    } else if (vector instanceof TimeStampVector timeStampVector) {
      if (value instanceof Instant instant) {
        long micros =
            SECONDS.toMicros(instant.getEpochSecond()) + NANOSECONDS.toMicros(instant.getNano());
        timeStampVector.setSafe(index, micros);
      } else if (value instanceof JsonNode jsonNode) {
        timeStampVector.setSafe(index, jsonNode.asLong());
      } else if (value instanceof Long longValue) {
        timeStampVector.setSafe(index, longValue);
      }
    } else if (vector instanceof ListVector listVector) {
      int start = listVector.startNewValue(index);
      if (value instanceof ArrayNode arrayNode) {
        for (int i = 0; i < arrayNode.size(); i++) {
          populateVector(listVector.getDataVector(), start + i, arrayNode.get(i));
        }
        listVector.endValue(index, arrayNode.size());
      } else if (value instanceof List) {
        List<?> list = (List<?>) value;
        for (int i = 0; i < list.size(); i++) {
          populateVector(listVector.getDataVector(), start + i, list.get(i));
        }
        listVector.endValue(index, list.size());
      }
    } else if (vector instanceof StructVector structVector) {
      structVector.setIndexDefined(index);
      if (value instanceof ObjectNode objectNode) {
        for (FieldVector child : structVector.getChildrenFromFields()) {
          populateVector(child, index, objectNode.get(child.getName()));
        }
      } else if (value instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) value;
        for (FieldVector child : structVector.getChildrenFromFields()) {
          populateVector(child, index, map.get(child.getName()));
        }
      }
    }
  }

  @Override
  public void close() {
    if (this.queue != null && !this.queue.isEmpty()) {
      this.flush();
    }
    if (this.allocator != null) {
      try {
        this.allocator.close();
      } catch (RuntimeException e) {
        logger.log(Level.SEVERE, "Failed to close Buffer allocator", e);
      }
    }
    if (this.root != null) {
      try {
        this.root.close();
      } catch (RuntimeException e) {
        logger.log(Level.SEVERE, "Failed to close VectorSchemaRoot", e);
      }
    }
    if (this.writer != null) {
      try {
        this.writer.close();
      } catch (RuntimeException e) {
        logger.log(Level.SEVERE, "Failed to close BigQuery writer", e);
      }
    }
  }
}
