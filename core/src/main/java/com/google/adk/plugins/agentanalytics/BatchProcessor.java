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
import com.google.api.core.ApiFuture;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.Exceptions.AppendSerializationError;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
import org.jspecify.annotations.Nullable;

/** Handles asynchronous batching and writing of events to BigQuery. */
class BatchProcessor implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(BatchProcessor.class.getName());

  private final StreamWriter writer;
  private final int batchSize;
  private final Duration flushInterval;
  private final Duration shutdownTimeout;
  @VisibleForTesting final BlockingQueue<Map<String, Object>> queue;
  private final ScheduledExecutorService executor;
  @VisibleForTesting final BufferAllocator allocator;
  // Mutual exclusion for flush; a ReentrantLock (not a CAS flag) so close() can WAIT, bounded,
  // for an in-flight flush instead of guessing from queue emptiness.
  private final ReentrantLock flushMutex = new ReentrantLock();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  // Set by close() before it waits for the flush mutex: whichever party releases the mutex last
  // (close, or an in-flight flush that outlived close's deadline) performs the actual teardown.
  private final AtomicBoolean teardownRequested = new AtomicBoolean(false);
  private final AtomicBoolean tornDown = new AtomicBoolean(false);
  // While closing, bounds every drain/in-flight append to the remaining close budget.
  private volatile @Nullable Instant closeDeadline;
  // Delivered the FINAL drop-stat snapshot when teardown actually completes; see closeAndFold.
  private volatile @Nullable Consumer<ImmutableMap<String, Long>> onFinalStats;
  // Owner-preserving detached close for the StreamWriter (see teardownOnce). PluginState provides
  // an implementation that is bounded, never blocks, and guarantees every writer's close
  // eventually runs (a StreamWriter owns an internal client and a NON-DAEMON append thread that
  // only ConnectionWorker.close() stops — abandoning one leaks process-level resources).
  private final Consumer<StreamWriter> writerCloser;
  // The periodic flush task; stored so per-invocation close() can cancel it instead of leaving a
  // scheduled task retaining this (closed) processor until plugin-wide shutdown.
  private volatile @Nullable ScheduledFuture<?> flushTask;
  private final Schema arrowSchema;
  private final VectorSchemaRoot root;

  // Drop accounting so hosts can programmatically detect lost analytics rows.
  private final AtomicLong droppedQueueFull = new AtomicLong();
  private final AtomicLong droppedAppendError = new AtomicLong();
  private final AtomicLong droppedSerializationError = new AtomicLong();
  private final AtomicLong droppedAfterClose = new AtomicLong();
  private final AtomicLong droppedShutdownTimeout = new AtomicLong();

  public BatchProcessor(
      StreamWriter writer,
      int batchSize,
      Duration flushInterval,
      int queueMaxSize,
      ScheduledExecutorService executor,
      Duration shutdownTimeout,
      Consumer<StreamWriter> writerCloser) {
    this.writer = writer;
    this.writerCloser = writerCloser;
    this.batchSize = batchSize;
    this.flushInterval = flushInterval;
    this.shutdownTimeout = shutdownTimeout;
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
    this.flushTask =
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
    if (closed.get()) {
      // The owning invocation has already been finalized; accept-and-drop with accounting rather
      // than silently enqueueing into a processor whose final drain has already run.
      droppedAfterClose.incrementAndGet();
      logger.warning("BatchProcessor is closed, dropping late event.");
      return;
    }
    if (!queue.offer(row)) {
      droppedQueueFull.incrementAndGet();
      logger.warning("BigQuery event queue is full, dropping event.");
      return;
    }
    if (queue.size() >= batchSize && !flushMutex.isLocked()) {
      executor.execute(this::flush);
    }
  }

  public void flush() {
    // Acquire the flush mutex. If another flush is already in progress, return immediately.
    if (!flushMutex.tryLock()) {
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
          // Bound the append so one stuck Storage Write RPC cannot block the flush path (and,
          // during close(), the final drain) indefinitely.
          ApiFuture<AppendRowsResponse> appendFuture = writer.append(recordBatch);
          AppendRowsResponse result;
          try {
            result = appendFuture.get(appendTimeoutMillis(), MILLISECONDS);
          } catch (TimeoutException e) {
            appendFuture.cancel(true);
            throw e;
          }
          if (result.hasError()) {
            droppedAppendError.addAndGet(batch.size());
            logger.severe("BigQuery append error: " + result.getError().getMessage());
            for (var error : result.getRowErrorsList()) {
              logger.severe(
                  String.format("Row error at index %d: %s", error.getIndex(), error.getMessage()));
            }
          } else {
            logger.fine("Successfully wrote " + batch.size() + " rows to BigQuery.");
          }
        }
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        if (e.getCause() instanceof AppendSerializationError ase) {
          droppedSerializationError.addAndGet(batch.size());
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
        } else {
          droppedAppendError.addAndGet(batch.size());
          logger.log(Level.SEVERE, "Failed to write batch to BigQuery", e);
        }
      } finally {
        // Clear the vectors to release the memory.
        root.clear();
      }
    } finally {
      flushMutex.unlock();
      // Deferred teardown: close() timed out waiting for this flush, transferring ownership of
      // the final resource teardown (and drop-stat delivery) to us.
      if (teardownRequested.get()) {
        teardownOnce();
      }
      if (queue.size() >= batchSize && !flushMutex.isLocked()) {
        executor.execute(this::flush);
      }
    }
  }

  /**
   * Per-append deadline: normally {@code shutdownTimeout}; once close() has started, capped to the
   * remaining close budget so the final drain cannot exceed the caller's bound.
   */
  private long appendTimeoutMillis() {
    long timeoutMillis = shutdownTimeout.toMillis();
    Instant deadline = this.closeDeadline;
    if (deadline != null) {
      long remaining = Duration.between(Instant.now(), deadline).toMillis();
      timeoutMillis = Math.max(1, Math.min(timeoutMillis, remaining));
    }
    return timeoutMillis;
  }

  private void populateVector(FieldVector vector, int index, Object value) {
    if (value == null || (value instanceof JsonNode jsonNode && jsonNode.isNull())) {
      vector.setNull(index);
      return;
    }
    if (vector instanceof VarCharVector varCharVector) {
      String strValue;
      if (value instanceof JsonNode jsonNode) {
        strValue = jsonNode.isTextual() ? jsonNode.asText() : jsonNode.toString();
      } else {
        strValue = value.toString();
      }
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

  /**
   * Returns a snapshot of dropped-row counters keyed by reason ({@code queue_full}, {@code
   * append_error}, {@code serialization_error}). Non-zero values indicate lost analytics rows.
   */
  ImmutableMap<String, Long> getDropStats() {
    return ImmutableMap.of(
        "queue_full", droppedQueueFull.get(),
        "append_error", droppedAppendError.get(),
        "serialization_error", droppedSerializationError.get(),
        "after_close", droppedAfterClose.get(),
        "shutdown_timeout", droppedShutdownTimeout.get());
  }

  /**
   * Closes the processor and delivers the FINAL drop-stat snapshot to {@code statsConsumer} when
   * teardown actually completes — which may be after this call returns, if an in-flight flush still
   * owns the resources when the shutdownTimeout deadline expires (ownership of the teardown then
   * transfers to that flush). This guarantees counters recorded by that last flush (e.g. its append
   * failure) are included in the delivered snapshot exactly once.
   */
  void closeAndFold(Consumer<ImmutableMap<String, Long>> statsConsumer) {
    closeAndFold(statsConsumer, Instant.now().plus(shutdownTimeout));
  }

  /**
   * Deadline-accepting variant: the caller passes ONE absolute deadline shared across a larger
   * shutdown operation (pending-task waits, sibling processors, executor termination), so this
   * processor's drain consumes only the remaining budget instead of restarting a fresh
   * shutdownTimeout.
   */
  void closeAndFold(Consumer<ImmutableMap<String, Long>> statsConsumer, Instant deadline) {
    this.onFinalStats = statsConsumer;
    close(deadline);
  }

  @Override
  public void close() {
    close(Instant.now().plus(shutdownTimeout));
  }

  private void close(Instant drainDeadline) {
    // Idempotent: ensureInvocationCompleted and plugin-wide shutdown may both close a processor.
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    // Cancel the periodic flush task so a completed invocation does not leave a scheduled task
    // retaining this processor (and its writer) until plugin-wide shutdown.
    ScheduledFuture<?> task = this.flushTask;
    if (task != null) {
      task.cancel(false);
    }
    // Final drain, bounded by the caller's absolute deadline rather than looping until empty.
    // Publishing the deadline caps every drain/in-flight append to the remaining close budget.
    this.closeDeadline = drainDeadline;
    while (!this.queue.isEmpty() && Instant.now().isBefore(drainDeadline)) {
      this.flush();
      if (!this.queue.isEmpty()) {
        // Another thread may hold the flush mutex; back off briefly instead of spinning.
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    int remaining = this.queue.size();
    if (remaining > 0) {
      droppedShutdownTimeout.addAndGet(remaining);
      this.queue.clear();
      logger.severe(
          "Dropping " + remaining + " rows: final drain did not complete within shutdownTimeout.");
    }
    // Teardown ownership: request it, then try to acquire the flush mutex within the remaining
    // budget. If acquired, no flush is active and we tear down here. If the wait expires, the
    // in-flight flush performs the teardown (and final stats delivery) when it releases the mutex
    // — resources are never destroyed underneath an active flush, and counters that flush records
    // are still included in the final snapshot.
    teardownRequested.set(true);
    boolean acquired = false;
    long waitMillis = Math.max(1, Duration.between(Instant.now(), drainDeadline).toMillis());
    try {
      acquired = flushMutex.tryLock(waitMillis, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (acquired) {
      try {
        teardownOnce();
      } finally {
        flushMutex.unlock();
      }
    } else {
      logger.severe(
          "Deferring resource teardown to the in-flight flush: it did not release the flush mutex"
              + " within shutdownTimeout.");
      // The flush may have released the mutex between the timed wait expiring and the request
      // flag becoming visible to it; re-check so the teardown is never lost.
      if (flushMutex.tryLock()) {
        try {
          teardownOnce();
        } finally {
          flushMutex.unlock();
        }
      }
    }
  }

  /**
   * Tears down Arrow and writer resources and delivers the final drop-stat snapshot, exactly once,
   * regardless of whether close() or a deferred in-flight flush gets here first.
   */
  private void teardownOnce() {
    if (!tornDown.compareAndSet(false, true)) {
      return;
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
      // StreamWriter.close() can block far beyond any shutdownTimeout (it joins the writer's
      // internal non-daemon append thread, then may wait minutes on its internal client and
      // callback pools). Delegate to the plugin-owned closer, which detaches the close without
      // ever abandoning the writer: cleanup ownership is guaranteed by writer admission permits
      // acquired before construction. No further work touches the writer here: teardown only
      // runs after appends have stopped (closed gate + flush-mutex ownership), and drop counters
      // are final below.
      writerCloser.accept(this.writer);
    }
    // Deliver the final snapshot only now, when no flush can mutate the counters anymore.
    Consumer<ImmutableMap<String, Long>> statsConsumer = this.onFinalStats;
    if (statsConsumer != null) {
      try {
        statsConsumer.accept(getDropStats());
      } catch (RuntimeException e) {
        logger.log(Level.WARNING, "Failed to deliver final drop stats", e);
      }
    }
  }
}
