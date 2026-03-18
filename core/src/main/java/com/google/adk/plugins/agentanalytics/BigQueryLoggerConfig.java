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

import com.google.auth.Credentials;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** Configuration for the BigQueryAgentAnalyticsPlugin. */
@AutoValue
public abstract class BigQueryLoggerConfig {
  // Whether the plugin is enabled.
  public abstract boolean enabled();

  // List of event types to log. If None, all are allowed
  // TODO(b/491852782): Implement allowlist/denylist for event types.
  @Nullable
  public abstract ImmutableList<String> eventAllowlist();

  // List of event types to ignore.
  // TODO(b/491852782): Implement allowlist/denylist for event types.
  @Nullable
  public abstract ImmutableList<String> eventDenylist();

  // Max length for text content before truncation.
  public abstract int maxContentLength();

  // Project ID for the BigQuery table.
  public abstract String projectId();

  // Dataset ID for the BigQuery table.
  public abstract String datasetId();

  // Table name for the BigQuery table.
  public abstract String tableName();

  // Fields to cluster the table by.
  public abstract ImmutableList<String> clusteringFields();

  // Whether to log multi-modal content.
  // TODO(b/491852782): Implement logging of multi-modal content.
  public abstract boolean logMultiModalContent();

  // Retry configuration for BigQuery writes.
  public abstract RetryConfig retryConfig();

  // Number of rows to batch before flushing.
  public abstract int batchSize();

  // Duration to wait before flushing the queue.
  public abstract Duration batchFlushInterval();

  // Max time to wait for shutdown.
  public abstract Duration shutdownTimeout();

  // Max size of the batch processor queue.
  public abstract int queueMaxSize();

  // Optional custom formatter for content.
  // TODO(b/491852782): Implement content formatter.
  @Nullable
  public abstract BiFunction<Object, String, Object> contentFormatter();

  // TODO(b/491852782): Implement connection id.
  public abstract Optional<String> connectionId();

  // Toggle for session metadata (e.g. gchat thread-id).
  // TODO(b/491852782): Implement logging of session metadata.
  public abstract boolean logSessionMetadata();

  // Static custom tags (e.g. {"agent_role": "sales"}).
  // TODO(b/491852782): Implement custom tags.
  public abstract ImmutableMap<String, Object> customTags();

  // Automatically add new columns to existing tables when the plugin
  // schema evolves.  Only additive changes are made (columns are never
  // dropped or altered).
  // TODO(b/491852782): Implement auto-schema upgrade.
  public abstract boolean autoSchemaUpgrade();

  @Nullable
  public abstract Credentials credentials();

  public static Builder builder() {
    return new AutoValue_BigQueryLoggerConfig.Builder()
        .setEnabled(true)
        .setMaxContentLength(500 * 1024)
        .setDatasetId("agent_analytics")
        .setTableName("events")
        .setClusteringFields(ImmutableList.of("event_type", "agent", "user_id"))
        .setLogMultiModalContent(true)
        .setRetryConfig(RetryConfig.builder().build())
        .setBatchSize(1)
        .setBatchFlushInterval(Duration.ofSeconds(1))
        .setShutdownTimeout(Duration.ofSeconds(10))
        .setQueueMaxSize(10000)
        .setLogSessionMetadata(true)
        .setCustomTags(ImmutableMap.of())
        // TODO(b/491851868): Enable auto-schema upgrade once implemented.
        .setAutoSchemaUpgrade(false);
  }

  /** Builder for {@link BigQueryLoggerConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setEnabled(boolean enabled);

    public abstract Builder setEventAllowlist(@Nullable List<String> eventAllowlist);

    public abstract Builder setEventDenylist(@Nullable List<String> eventDenylist);

    public abstract Builder setMaxContentLength(int maxContentLength);

    public abstract Builder setProjectId(String projectId);

    public abstract Builder setDatasetId(String datasetId);

    public abstract Builder setTableName(String tableName);

    public abstract Builder setClusteringFields(List<String> clusteringFields);

    public abstract Builder setLogMultiModalContent(boolean logMultiModalContent);

    public abstract Builder setRetryConfig(RetryConfig retryConfig);

    public abstract Builder setBatchSize(int batchSize);

    public abstract Builder setBatchFlushInterval(Duration batchFlushInterval);

    public abstract Builder setShutdownTimeout(Duration shutdownTimeout);

    public abstract Builder setQueueMaxSize(int queueMaxSize);

    public abstract Builder setContentFormatter(
        @Nullable BiFunction<Object, String, Object> contentFormatter);

    public abstract Builder setConnectionId(String connectionId);

    public abstract Builder setLogSessionMetadata(boolean logSessionMetadata);

    public abstract Builder setCustomTags(Map<String, Object> customTags);

    public abstract Builder setAutoSchemaUpgrade(boolean autoSchemaUpgrade);

    public abstract Builder setCredentials(Credentials credentials);

    public abstract BigQueryLoggerConfig build();
  }

  /** Retry configuration for BigQuery writes. */
  @AutoValue
  public abstract static class RetryConfig {
    public abstract int maxRetries();

    public abstract Duration initialDelay();

    public abstract double multiplier();

    public abstract Duration maxDelay();

    public static Builder builder() {
      return new AutoValue_BigQueryLoggerConfig_RetryConfig.Builder()
          .setMaxRetries(3)
          .setInitialDelay(Duration.ofSeconds(1))
          .setMultiplier(2.0)
          .setMaxDelay(Duration.ofSeconds(10));
    }

    /** Builder for {@link RetryConfig}. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setMaxRetries(int maxRetries);

      public abstract Builder setInitialDelay(Duration initialDelay);

      public abstract Builder setMultiplier(double multiplier);

      public abstract Builder setMaxDelay(Duration maxDelay);

      public abstract RetryConfig build();
    }
  }
}
