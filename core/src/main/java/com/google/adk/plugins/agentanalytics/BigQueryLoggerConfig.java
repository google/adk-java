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
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Configuration for the BigQueryAgentAnalyticsPlugin. */
@AutoValue
public abstract class BigQueryLoggerConfig {
  // Whether the plugin is enabled.
  public abstract boolean enabled();

  // List of event types to log. If None, all are allowed.
  public abstract ImmutableList<String> eventAllowlist();

  // List of event types to ignore.
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

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_BigQueryLoggerConfig.Builder()
        .enabled(true)
        .maxContentLength(500 * 1024)
        .datasetId("agent_analytics")
        .tableName("events")
        .clusteringFields(ImmutableList.of("event_type", "agent", "user_id"))
        .logMultiModalContent(true)
        .retryConfig(RetryConfig.builder().build())
        .batchSize(1)
        .batchFlushInterval(Duration.ofSeconds(1))
        .shutdownTimeout(Duration.ofSeconds(10))
        .queueMaxSize(10000)
        .logSessionMetadata(true)
        .customTags(ImmutableMap.of())
        .eventAllowlist(ImmutableList.of())
        .eventDenylist(ImmutableList.of())
        // TODO(b/491851868): Enable auto-schema upgrade once implemented.
        .autoSchemaUpgrade(false);
  }

  /** Builder for {@link BigQueryLoggerConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setEnabled(boolean enabled) {
      return enabled(enabled);
    }

    @CanIgnoreReturnValue
    public abstract Builder enabled(boolean enabled);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setEventAllowlist(@Nullable List<String> eventAllowlist) {
      return eventAllowlist(eventAllowlist);
    }

    @CanIgnoreReturnValue
    public abstract Builder eventAllowlist(@Nullable List<String> eventAllowlist);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setEventDenylist(@Nullable List<String> eventDenylist) {
      return eventDenylist(eventDenylist);
    }

    @CanIgnoreReturnValue
    public abstract Builder eventDenylist(@Nullable List<String> eventDenylist);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setMaxContentLength(int maxContentLength) {
      return maxContentLength(maxContentLength);
    }

    @CanIgnoreReturnValue
    public abstract Builder maxContentLength(int maxContentLength);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setProjectId(String projectId) {
      return projectId(projectId);
    }

    @CanIgnoreReturnValue
    public abstract Builder projectId(String projectId);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setDatasetId(String datasetId) {
      return datasetId(datasetId);
    }

    @CanIgnoreReturnValue
    public abstract Builder datasetId(String datasetId);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setTableName(String tableName) {
      return tableName(tableName);
    }

    @CanIgnoreReturnValue
    public abstract Builder tableName(String tableName);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setClusteringFields(List<String> clusteringFields) {
      return clusteringFields(clusteringFields);
    }

    @CanIgnoreReturnValue
    public abstract Builder clusteringFields(List<String> clusteringFields);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setLogMultiModalContent(boolean logMultiModalContent) {
      return logMultiModalContent(logMultiModalContent);
    }

    @CanIgnoreReturnValue
    public abstract Builder logMultiModalContent(boolean logMultiModalContent);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setRetryConfig(RetryConfig retryConfig) {
      return retryConfig(retryConfig);
    }

    @CanIgnoreReturnValue
    public abstract Builder retryConfig(RetryConfig retryConfig);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setBatchSize(int batchSize) {
      return batchSize(batchSize);
    }

    @CanIgnoreReturnValue
    public abstract Builder batchSize(int batchSize);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setBatchFlushInterval(Duration batchFlushInterval) {
      return batchFlushInterval(batchFlushInterval);
    }

    @CanIgnoreReturnValue
    public abstract Builder batchFlushInterval(Duration batchFlushInterval);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setShutdownTimeout(Duration shutdownTimeout) {
      return shutdownTimeout(shutdownTimeout);
    }

    @CanIgnoreReturnValue
    public abstract Builder shutdownTimeout(Duration shutdownTimeout);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setQueueMaxSize(int queueMaxSize) {
      return queueMaxSize(queueMaxSize);
    }

    @CanIgnoreReturnValue
    public abstract Builder queueMaxSize(int queueMaxSize);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setContentFormatter(
        @Nullable BiFunction<Object, String, Object> contentFormatter) {
      return contentFormatter(contentFormatter);
    }

    @CanIgnoreReturnValue
    public abstract Builder contentFormatter(
        @Nullable BiFunction<Object, String, Object> contentFormatter);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setConnectionId(String connectionId) {
      return connectionId(connectionId);
    }

    @CanIgnoreReturnValue
    public abstract Builder connectionId(String connectionId);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setLogSessionMetadata(boolean logSessionMetadata) {
      return logSessionMetadata(logSessionMetadata);
    }

    @CanIgnoreReturnValue
    public abstract Builder logSessionMetadata(boolean logSessionMetadata);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setCustomTags(Map<String, Object> customTags) {
      return customTags(customTags);
    }

    @CanIgnoreReturnValue
    public abstract Builder customTags(Map<String, Object> customTags);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setAutoSchemaUpgrade(boolean autoSchemaUpgrade) {
      return autoSchemaUpgrade(autoSchemaUpgrade);
    }

    @CanIgnoreReturnValue
    public abstract Builder autoSchemaUpgrade(boolean autoSchemaUpgrade);

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setCredentials(Credentials credentials) {
      return credentials(credentials);
    }

    @CanIgnoreReturnValue
    public abstract Builder credentials(Credentials credentials);

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
          .maxRetries(3)
          .initialDelay(Duration.ofSeconds(1))
          .multiplier(2.0)
          .maxDelay(Duration.ofSeconds(10));
    }

    /** Builder for {@link RetryConfig}. */
    @AutoValue.Builder
    public abstract static class Builder {

      @Deprecated
      @CanIgnoreReturnValue
      public final Builder setMaxRetries(int maxRetries) {
        return maxRetries(maxRetries);
      }

      @CanIgnoreReturnValue
      public abstract Builder maxRetries(int maxRetries);

      @Deprecated
      @CanIgnoreReturnValue
      public final Builder setInitialDelay(Duration initialDelay) {
        return initialDelay(initialDelay);
      }

      @CanIgnoreReturnValue
      public abstract Builder initialDelay(Duration initialDelay);

      @Deprecated
      @CanIgnoreReturnValue
      public final Builder setMultiplier(double multiplier) {
        return multiplier(multiplier);
      }

      @CanIgnoreReturnValue
      public abstract Builder multiplier(double multiplier);

      @Deprecated
      @CanIgnoreReturnValue
      public final Builder setMaxDelay(Duration maxDelay) {
        return maxDelay(maxDelay);
      }

      @CanIgnoreReturnValue
      public abstract Builder maxDelay(Duration maxDelay);

      public abstract RetryConfig build();
    }
  }
}
