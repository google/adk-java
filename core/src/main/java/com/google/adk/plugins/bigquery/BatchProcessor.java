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
package com.google.adk.plugins.bigquery;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Processes analytics events in batches and writes them using the configured AnalyticsWriter. */
public class BatchProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(BatchProcessor.class);

  private final AnalyticsWriter analyticsWriter;
  private final int batchSize;
  private final long flushIntervalMs;
  private final long shutdownTimeoutMs;
  private final BlockingQueue<ImmutableMap<String, Object>> queue;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  public BatchProcessor(
      AnalyticsWriter analyticsWriter,
      int batchSize,
      long flushIntervalMs,
      int queueMaxSize,
      long shutdownTimeoutMs) {
    this.analyticsWriter = analyticsWriter;
    this.batchSize = batchSize;
    this.flushIntervalMs = flushIntervalMs;
    this.shutdownTimeoutMs = shutdownTimeoutMs;
    this.queue = new LinkedBlockingQueue<>(queueMaxSize);
  }

  public void start() throws IOException {
    synchronized (analyticsWriter) {
      analyticsWriter.start();
    }
    var unused =
        scheduler.scheduleAtFixedRate(
            this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
  }

  public void shutdown() {
    scheduler.shutdown();
    try {
      scheduler.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOGGER.warn("Interrupted while waiting for scheduler to terminate", e);
    }
    synchronized (analyticsWriter) {
      analyticsWriter.close();
    }
  }

  public void enqueue(ImmutableMap<String, Object> row) {
    if (!queue.offer(row)) {
      LOGGER.warn("Queue full, dropping event");
    }
  }

  private void flush() {
    synchronized (analyticsWriter) {
      if (!analyticsWriter.isReady()) {
        LOGGER.warn("Analytics writer is not ready, skipping flush");
        return;
      }
      List<ImmutableMap<String, Object>> batch = new ArrayList<>();
      queue.drainTo(batch, batchSize);
      if (batch.isEmpty()) {
        return;
      }

      analyticsWriter.writeBatch(batch);
    }
  }
}
