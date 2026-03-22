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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Clustering;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteSettings;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.threeten.bp.Duration;

/**
 * BigQuery Agent Analytics Plugin for Java.
 *
 * <p>Logs agent execution events directly to a BigQuery table using the Storage Write API.
 */
public class BigQueryAgentAnalyticsPlugin extends BasePlugin {
  private static final Logger logger =
      Logger.getLogger(BigQueryAgentAnalyticsPlugin.class.getName());
  private static final ImmutableList<String> DEFAULT_AUTH_SCOPES =
      ImmutableList.of("https://www.googleapis.com/auth/cloud-platform");
  private static final AtomicLong threadCounter = new AtomicLong(0);

  private final BigQueryLoggerConfig config;
  private final BigQuery bigQuery;
  private final BigQueryWriteClient writeClient;
  private final ScheduledExecutorService executor;
  private final Object tableEnsuredLock = new Object();
  @VisibleForTesting final BatchProcessor batchProcessor;
  private volatile boolean tableEnsured = false;

  public BigQueryAgentAnalyticsPlugin(BigQueryLoggerConfig config) throws IOException {
    this(config, createBigQuery(config));
  }

  public BigQueryAgentAnalyticsPlugin(BigQueryLoggerConfig config, BigQuery bigQuery)
      throws IOException {
    super("bigquery_agent_analytics");
    this.config = config;
    this.bigQuery = bigQuery;
    ThreadFactory threadFactory =
        r -> new Thread(r, "bq-analytics-plugin-" + threadCounter.getAndIncrement());
    this.executor = Executors.newScheduledThreadPool(1, threadFactory);
    this.writeClient = createWriteClient(config);

    if (config.enabled()) {
      StreamWriter writer = createWriter(config);
      this.batchProcessor =
          new BatchProcessor(
              writer,
              config.batchSize(),
              config.batchFlushInterval(),
              config.queueMaxSize(),
              executor);
      this.batchProcessor.start();
    } else {
      this.batchProcessor = null;
    }
  }

  private static BigQuery createBigQuery(BigQueryLoggerConfig config) throws IOException {
    BigQueryOptions.Builder builder = BigQueryOptions.newBuilder();
    if (config.credentials() != null) {
      builder.setCredentials(config.credentials());
    } else {
      builder.setCredentials(
          GoogleCredentials.getApplicationDefault().createScoped(DEFAULT_AUTH_SCOPES));
    }
    return builder.build().getService();
  }

  private void ensureTableExistsOnce() {
    if (!tableEnsured) {
      synchronized (tableEnsuredLock) {
        if (!tableEnsured) {
          // Table creation is expensive, so we only do it once per plugin instance.
          tableEnsured = true;
          ensureTableExists(bigQuery, config);
        }
      }
    }
  }

  private void ensureTableExists(BigQuery bigQuery, BigQueryLoggerConfig config) {
    TableId tableId = TableId.of(config.projectId(), config.datasetId(), config.tableName());
    Schema schema = BigQuerySchema.getEventsSchema();
    try {
      Table table = bigQuery.getTable(tableId);
      logger.info("BigQuery table: " + tableId);
      if (table == null) {
        logger.info("Creating BigQuery table: " + tableId);
        StandardTableDefinition.Builder tableDefinitionBuilder =
            StandardTableDefinition.newBuilder().setSchema(schema);
        if (!config.clusteringFields().isEmpty()) {
          tableDefinitionBuilder.setClustering(
              Clustering.newBuilder().setFields(config.clusteringFields()).build());
        }
        TableInfo tableInfo = TableInfo.newBuilder(tableId, tableDefinitionBuilder.build()).build();
        bigQuery.create(tableInfo);
      } else if (config.autoSchemaUpgrade()) {
        // TODO(b/491851868): Implement auto-schema upgrade.
        logger.info("BigQuery table already exists and auto-schema upgrade is enabled: " + tableId);
        logger.info("Auto-schema upgrade is not implemented yet.");
      }
    } catch (BigQueryException e) {
      if (e.getMessage().contains("invalid_grant")) {
        logger.log(
            Level.SEVERE,
            "Failed to authenticate with BigQuery. Please run 'gcloud auth application-default"
                + " login' to refresh your credentials or provide valid credentials in"
                + " BigQueryLoggerConfig.",
            e);
      } else {
        logger.log(
            Level.WARNING, "Failed to check or create/upgrade BigQuery table: " + tableId, e);
      }
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to check or create/upgrade BigQuery table: " + tableId, e);
    }
  }

  protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) throws IOException {
    if (config.credentials() != null) {
      return BigQueryWriteClient.create(
          BigQueryWriteSettings.newBuilder()
              .setCredentialsProvider(FixedCredentialsProvider.create(config.credentials()))
              .build());
    }
    return BigQueryWriteClient.create();
  }

  protected String getStreamName(BigQueryLoggerConfig config) {
    return String.format(
        "projects/%s/datasets/%s/tables/%s/streams/_default",
        config.projectId(), config.datasetId(), config.tableName());
  }

  protected StreamWriter createWriter(BigQueryLoggerConfig config) {
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
          .setRetrySettings(retrySettings)
          .setWriterSchema(BigQuerySchema.getArrowSchema())
          .build();
    } catch (Exception e) {
      throw new VerifyException("Failed to create StreamWriter for " + streamName, e);
    }
  }

  private void logEvent(
      String eventType,
      InvocationContext invocationContext,
      Optional<CallbackContext> callbackContext,
      Object content,
      Map<String, Object> extraAttributes) {
    if (batchProcessor == null) {
      return;
    }

    ensureTableExistsOnce();

    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());
    row.put("event_type", eventType);
    row.put(
        "agent",
        callbackContext.map(CallbackContext::agentName).orElse(invocationContext.agent().name()));
    row.put("session_id", invocationContext.session().id());
    row.put("invocation_id", invocationContext.invocationId());
    row.put("user_id", invocationContext.userId());

    if (content instanceof Content contentParts) {
      row.put(
          "content_parts",
          JsonFormatter.formatContentParts(Optional.of(contentParts), config.maxContentLength()));
      row.put(
          "content", JsonFormatter.smartTruncate(content, config.maxContentLength()).toString());
    } else if (content != null) {
      row.put(
          "content", JsonFormatter.smartTruncate(content, config.maxContentLength()).toString());
    }

    Map<String, Object> attributes = new HashMap<>(config.customTags());
    if (extraAttributes != null) {
      attributes.putAll(extraAttributes);
    }
    row.put(
        "attributes",
        JsonFormatter.smartTruncate(attributes, config.maxContentLength()).toString());

    addTraceDetails(row);
    batchProcessor.append(row);
  }

  // TODO(b/491849911): Implement own trace management functionality.
  private void addTraceDetails(Map<String, Object> row) {
    SpanContext spanContext = Span.current().getSpanContext();
    if (spanContext.isValid()) {
      row.put("trace_id", spanContext.getTraceId());
      row.put("span_id", spanContext.getSpanId());
    }
  }

  @Override
  public Completable close() {
    if (batchProcessor != null) {
      batchProcessor.close();
    }
    if (writeClient != null) {
      writeClient.close();
    }
    try {
      executor.shutdown();
      if (!executor.awaitTermination(config.shutdownTimeout().toMillis(), MILLISECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    return Completable.complete();
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return Maybe.fromAction(
        () -> logEvent("USER_MESSAGE", invocationContext, Optional.empty(), userMessage, null));
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    return Maybe.fromAction(
        () -> logEvent("INVOCATION_START", invocationContext, Optional.empty(), null, null));
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          attrs.put("event_author", event.author());
          logEvent(
              "EVENT", invocationContext, Optional.empty(), event.content().orElse(null), attrs);
        });
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(
        () -> {
          logEvent("INVOCATION_END", invocationContext, Optional.empty(), null, null);
          batchProcessor.flush();
        });
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () ->
            logEvent(
                "AGENT_START",
                callbackContext.invocationContext(),
                Optional.of(callbackContext),
                null,
                null));
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () ->
            logEvent(
                "AGENT_END",
                callbackContext.invocationContext(),
                Optional.of(callbackContext),
                null,
                null));
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          LlmRequest req = llmRequest.build();
          attrs.put("model", req.model().orElse("unknown"));
          logEvent(
              "MODEL_REQUEST",
              callbackContext.invocationContext(),
              Optional.of(callbackContext),
              req,
              attrs);
        });
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          llmResponse.usageMetadata().ifPresent(u -> attrs.put("usage_metadata", u));
          logEvent(
              "MODEL_RESPONSE",
              callbackContext.invocationContext(),
              Optional.of(callbackContext),
              llmResponse,
              attrs);
        });
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          attrs.put("error_message", error.getMessage());
          logEvent(
              "MODEL_ERROR",
              callbackContext.invocationContext(),
              Optional.of(callbackContext),
              null,
              attrs);
        });
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          attrs.put("tool_name", tool.name());
          logEvent(
              "TOOL_START",
              toolContext.invocationContext(),
              Optional.of(toolContext),
              toolArgs,
              attrs);
        });
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          attrs.put("tool_name", tool.name());
          logEvent(
              "TOOL_END", toolContext.invocationContext(), Optional.of(toolContext), result, attrs);
        });
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attrs = new HashMap<>();
          attrs.put("tool_name", tool.name());
          attrs.put("error_message", error.getMessage());
          logEvent(
              "TOOL_ERROR", toolContext.invocationContext(), Optional.of(toolContext), null, attrs);
        });
  }
}
