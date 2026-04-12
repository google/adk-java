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

import static com.google.adk.plugins.agentanalytics.BigQueryUtils.createAnalyticsViews;
import static com.google.adk.plugins.agentanalytics.BigQueryUtils.maybeUpgradeSchema;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.convertToJsonNode;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.smartTruncate;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.toJavaObject;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.agentanalytics.JsonFormatter.ParsedContent;
import com.google.adk.plugins.agentanalytics.JsonFormatter.TruncationResult;
import com.google.adk.plugins.agentanalytics.TraceManager.RecordData;
import com.google.adk.plugins.agentanalytics.TraceManager.SpanIds;
import com.google.adk.sessions.Session;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.tools.mcp.AbstractMcpTool;
import com.google.adk.utils.AgentEnums.AgentOrigin;
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
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.time.Duration;
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
import org.jspecify.annotations.Nullable;

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
  private static final ImmutableMap<String, String> HITL_EVENT_TYPES =
      ImmutableMap.of(
          "adk_request_credential",
          "HITL_CREDENTIAL_REQUEST",
          "adk_request_confirmation",
          "HITL_CONFIRMATION_REQUEST",
          "adk_request_input",
          "HITL_INPUT_REQUEST");

  private final BigQueryLoggerConfig config;
  private final BigQuery bigQuery;
  private final BigQueryWriteClient writeClient;
  private final ScheduledExecutorService executor;
  private final Object tableEnsuredLock = new Object();
  @VisibleForTesting final BatchProcessor batchProcessor;
  @VisibleForTesting final TraceManager traceManager;
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
    this.traceManager = createTraceManager();

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
    builder = builder.setLocation(config.location());
    builder.setProjectId(config.projectId());
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
        TableInfo tableInfo =
            TableInfo.newBuilder(tableId, tableDefinitionBuilder.build())
                .setLabels(
                    ImmutableMap.of(
                        BigQuerySchema.SCHEMA_VERSION_LABEL_KEY, BigQuerySchema.SCHEMA_VERSION))
                .build();
        bigQuery.create(tableInfo);
      } else if (config.autoSchemaUpgrade()) {
        maybeUpgradeSchema(bigQuery, table);
      }
    } catch (BigQueryException e) {
      processBigQueryException(e, "Failed to check or create/upgrade BigQuery table: " + tableId);
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to check or create/upgrade BigQuery table: " + tableId, e);
    }

    try {
      if (config.createViews()) {
        var unused = executor.submit(() -> createAnalyticsViews(bigQuery, config));
      }
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to create/update BigQuery views for table: " + tableId, e);
    }
  }

  private void processBigQueryException(BigQueryException e, String logMessage) {
    if (e.getMessage().contains("invalid_grant")) {
      logger.log(Level.SEVERE, "Failed to authenticate with BigQuery.", e);
    } else {
      logger.log(Level.WARNING, logMessage, e);
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
            .setInitialRetryDelay(
                org.threeten.bp.Duration.ofMillis(retryConfig.initialDelay().toMillis()))
            .setRetryDelayMultiplier(retryConfig.multiplier())
            .setMaxRetryDelay(org.threeten.bp.Duration.ofMillis(retryConfig.maxDelay().toMillis()))
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

  protected TraceManager createTraceManager() {
    return new TraceManager();
  }

  private void logEvent(
      String eventType,
      InvocationContext invocationContext,
      Object content,
      Optional<EventData> eventData) {
    logEvent(eventType, invocationContext, content, false, eventData);
  }

  private void logEvent(
      String eventType,
      InvocationContext invocationContext,
      Object content,
      boolean isContentTruncated,
      Optional<EventData> eventData) {
    if (!config.enabled() || batchProcessor == null) {
      return;
    }
    if (!config.eventAllowlist().isEmpty() && !config.eventAllowlist().contains(eventType)) {
      return;
    }
    if (config.eventDenylist().contains(eventType)) {
      return;
    }
    // Ensure table exists before logging.
    ensureTableExistsOnce();
    // Log common fields
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());
    row.put("event_type", eventType);
    row.put("agent", invocationContext.agent().name());
    row.put("session_id", invocationContext.session().id());
    row.put("invocation_id", invocationContext.invocationId());
    row.put("user_id", invocationContext.userId());
    // Parse and log content
    ParsedContent parsedContent = JsonFormatter.parse(content, config.maxContentLength());
    row.put("content_parts", parsedContent.parts());
    row.put("content", parsedContent.content());
    row.put("is_truncated", isContentTruncated || parsedContent.isTruncated());

    EventData data = eventData.orElse(EventData.builder().build());
    row.put("status", data.status());
    data.errorMessage().ifPresent(msg -> row.put("error_message", msg));

    Map<String, Object> latencyMap = extractLatency(data);
    if (latencyMap != null) {
      row.put("latency_ms", convertToJsonNode(latencyMap));
    }
    row.put("attributes", convertToJsonNode(getAttributes(data, invocationContext)));

    addTraceDetails(row, invocationContext, eventData);
    batchProcessor.append(row);
  }

  private void addTraceDetails(
      Map<String, Object> row, InvocationContext invocationContext, Optional<EventData> eventData) {
    String traceId =
        eventData
            .flatMap(EventData::traceIdOverride)
            .orElseGet(() -> traceManager.getTraceId(invocationContext));
    Optional<SpanIds> ambientSpanIds = traceManager.getAmbientSpanAndParent();
    SpanIds spanIds = ambientSpanIds.orElse(traceManager.getCurrentSpanAndParent());

    row.put("trace_id", traceId);
    row.put(
        "span_id",
        eventData.flatMap(EventData::spanIdOverride).orElse(spanIds.spanId().orElse(null)));
    row.put(
        "parent_span_id",
        eventData
            .flatMap(EventData::parentSpanIdOverride)
            .orElse(spanIds.parentSpanId().orElse(null)));
  }

  private @Nullable Map<String, Object> extractLatency(EventData eventData) {
    Map<String, Object> latencyMap = new HashMap<>();
    eventData.latency().ifPresent(v -> latencyMap.put("total_ms", v.toMillis()));
    eventData
        .timeToFirstToken()
        .ifPresent(v -> latencyMap.put("time_to_first_token_ms", v.toMillis()));
    return latencyMap.isEmpty() ? null : latencyMap;
  }

  private Map<String, Object> getAttributes(
      EventData eventData, InvocationContext invocationContext) {
    Map<String, Object> attributes = new HashMap<>(eventData.extraAttributes());

    attributes.put("root_agent_name", traceManager.getRootAgentName());
    eventData.model().ifPresent(m -> attributes.put("model", m));
    eventData.modelVersion().ifPresent(mv -> attributes.put("model_version", mv));
    eventData
        .usageMetadata()
        .ifPresent(
            um -> {
              TruncationResult result = smartTruncate(um, config.maxContentLength());
              attributes.put("usage_metadata", toJavaObject(result.node()));
            });

    if (config.logSessionMetadata()) {
      try {
        Session session = invocationContext.session();
        Map<String, Object> sessionMeta = new HashMap<>();
        sessionMeta.put("session_id", session.id());
        sessionMeta.put("app_name", session.appName());
        sessionMeta.put("user_id", session.userId());

        if (!session.state().isEmpty()) {
          TruncationResult result = smartTruncate(session.state(), config.maxContentLength());
          sessionMeta.put("state", toJavaObject(result.node()));
        }
        attributes.put("session_metadata", sessionMeta);
      } catch (RuntimeException e) {
        // Ignore session enrichment errors as in Python.
      }
    }

    if (!config.customTags().isEmpty()) {
      attributes.put("custom_tags", config.customTags());
    }

    return attributes;
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

  private Optional<EventData> getCompletedEventData(InvocationContext invocationContext) {
    String traceId = traceManager.getTraceId(invocationContext);
    // Pop the invocation span from the trace manager.
    Optional<RecordData> popped = traceManager.popSpan();
    if (popped.isEmpty()) {
      // No invocation span to pop.
      logger.info("No invocation span to pop.");
      return Optional.empty();
    }
    Optional<String> parentSpanId = traceManager.getCurrentSpanId();

    EventData.Builder eventDataBuilder = EventData.builder();
    eventDataBuilder.setTraceIdOverride(traceId);
    eventDataBuilder.setLatency(popped.get().duration());
    // Only override span IDs when no ambient OTel span exists.
    // Keep STARTING/COMPLETED pairs consistent.
    if (!traceManager.hasAmbientSpan()) {
      if (parentSpanId.isPresent()) {
        eventDataBuilder.setParentSpanIdOverride(parentSpanId.get());
      }
      if (popped.get().spanId() != null) {
        eventDataBuilder.setSpanIdOverride(popped.get().spanId());
      }
    }
    return Optional.of(eventDataBuilder.build());
  }

  // --- Plugin callbacks ---
  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return Maybe.fromAction(
        () -> {
          traceManager.ensureInvocationSpan(invocationContext);
          logEvent("USER_MESSAGE_RECEIVED", invocationContext, userMessage, Optional.empty());
          if (userMessage.parts().isPresent()) {
            for (Part part : userMessage.parts().get()) {
              if (part.functionCall().isPresent()
                  && HITL_EVENT_TYPES.containsKey(part.functionCall().get().name().orElse(""))) {
                String hitlEvent = HITL_EVENT_TYPES.get(part.functionCall().get().name().get());
                TruncationResult truncatedResult = smartTruncate(part, config.maxContentLength());
                logEvent(
                    hitlEvent + "_COMPLETED",
                    invocationContext,
                    ImmutableMap.of(
                        "tool",
                        part.functionCall().get().name().get(),
                        "result",
                        truncatedResult.node()),
                    truncatedResult.isTruncated(),
                    Optional.empty());
              }
            }
          }
        });
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    return Maybe.fromAction(
        () -> {
          EventData.Builder eventDataBuilder =
              EventData.builder()
                  .setExtraAttributes(
                      ImmutableMap.<String, Object>builder()
                          .put("state_delta", event.actions().stateDelta())
                          .put("author", event.author())
                          .buildOrThrow());
          logEvent(
              "STATE_DELTA",
              invocationContext,
              event.content().orElse(null),
              Optional.of(eventDataBuilder.build()));

          if (event.content().isPresent() && event.content().get().parts().isPresent()) {
            for (Part part : event.content().get().parts().get()) {
              if (part.functionCall().isPresent()
                  && HITL_EVENT_TYPES.containsKey(part.functionCall().get().name().orElse(""))) {
                String hitlEvent = HITL_EVENT_TYPES.get(part.functionCall().get().name().get());
                TruncationResult truncatedResult =
                    smartTruncate(part.functionCall().get().args(), config.maxContentLength());
                logEvent(
                    hitlEvent + "_COMPLETED",
                    invocationContext,
                    ImmutableMap.of(
                        "tool",
                        part.functionCall().get().name().get(),
                        "args",
                        truncatedResult.node()),
                    truncatedResult.isTruncated(),
                    Optional.empty());
              }
              if (part.functionResponse().isPresent()
                  && HITL_EVENT_TYPES.containsKey(
                      part.functionResponse().get().name().orElse(""))) {
                String hitlEvent = HITL_EVENT_TYPES.get(part.functionResponse().get().name().get());
                TruncationResult truncatedResult =
                    smartTruncate(
                        part.functionResponse().get().response(), config.maxContentLength());
                logEvent(
                    hitlEvent + "_COMPLETED",
                    invocationContext,
                    ImmutableMap.of(
                        "tool",
                        part.functionResponse().get().name().get(),
                        "response",
                        truncatedResult.node()),
                    truncatedResult.isTruncated(),
                    Optional.empty());
              }
            }
          }
        });
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    traceManager.ensureInvocationSpan(invocationContext);
    return Maybe.fromAction(
        () -> logEvent("INVOCATION_STARTING", invocationContext, null, Optional.empty()));
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(
        () -> {
          logEvent(
              "INVOCATION_COMPLETED",
              invocationContext,
              null,
              getCompletedEventData(invocationContext));
          batchProcessor.flush();
          traceManager.clearStack();
        });
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          traceManager.pushSpan("agent:" + agent.name());
          logEvent("AGENT_STARTING", callbackContext.invocationContext(), null, Optional.empty());
        });
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          logEvent(
              "AGENT_COMPLETED",
              callbackContext.invocationContext(),
              null,
              getCompletedEventData(callbackContext.invocationContext()));
        });
  }

  /**
   * Callback before LLM call.
   *
   * <p>Logs the LLM request details including: 1. Prompt content 2. System instruction (if
   * available)
   *
   * <p>The content is formatted as 'Prompt: {prompt} | System Prompt: {system_prompt}'.
   */
  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return Maybe.fromAction(
        () -> {
          Map<String, Object> attributes = new HashMap<>();
          Map<String, Object> llmConfig = new HashMap<>();
          LlmRequest req = llmRequest.build();
          if (req.config().isPresent()) {
            if (req.config().get().temperature().isPresent()) {
              llmConfig.put("temperature", req.config().get().temperature().get());
            }
            if (req.config().get().topP().isPresent()) {
              llmConfig.put("top_p", req.config().get().topP().get());
            }
            if (req.config().get().topK().isPresent()) {
              llmConfig.put("top_k", req.config().get().topK().get());
            }
            if (req.config().get().candidateCount().isPresent()) {
              llmConfig.put("candidate_count", req.config().get().candidateCount().get());
            }
            if (req.config().get().maxOutputTokens().isPresent()) {
              llmConfig.put("max_output_tokens", req.config().get().maxOutputTokens().get());
            }
            if (req.config().get().stopSequences().isPresent()) {
              llmConfig.put("stop_sequences", req.config().get().stopSequences().get());
            }
            if (req.config().get().presencePenalty().isPresent()) {
              llmConfig.put("presence_penalty", req.config().get().presencePenalty().get());
            }
            if (req.config().get().frequencyPenalty().isPresent()) {
              llmConfig.put("frequency_penalty", req.config().get().frequencyPenalty().get());
            }
            if (req.config().get().responseMimeType().isPresent()) {
              llmConfig.put("response_mime_type", req.config().get().responseMimeType().get());
            }
            if (req.config().get().responseSchema().isPresent()) {
              llmConfig.put("response_schema", req.config().get().responseSchema().get());
            }
            if (req.config().get().seed().isPresent()) {
              llmConfig.put("seed", req.config().get().seed().get());
            }
            if (req.config().get().responseLogprobs().isPresent()) {
              llmConfig.put("response_logprobs", req.config().get().responseLogprobs().get());
            }
            if (req.config().get().logprobs().isPresent()) {
              llmConfig.put("logprobs", req.config().get().logprobs().get());
            }
            // Put labels in attributes instead of LLM config.
            if (req.config().get().labels().isPresent()) {
              attributes.put("labels", req.config().get().labels().get());
            }
          }
          if (!llmConfig.isEmpty()) {
            attributes.put("llm_config", llmConfig);
          }
          if (!req.tools().isEmpty()) {
            attributes.put("tools", req.tools().keySet());
          }
          EventData eventData =
              EventData.builder()
                  .setModel(req.model().orElse(""))
                  .setExtraAttributes(attributes)
                  .build();
          traceManager.pushSpan("llm_request");
          logEvent("LLM_REQUEST", callbackContext.invocationContext(), req, Optional.of(eventData));
        });
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return Maybe.fromAction(
        () -> {
          // TODO(b/495809488): Add formatting of the content
          ParsedContent parsedContent =
              JsonFormatter.parse(llmResponse.content().orElse(null), config.maxContentLength());

          Map<String, Object> usageDict = new HashMap<>();
          llmResponse
              .usageMetadata()
              .ifPresent(
                  usage -> {
                    usage.promptTokenCount().ifPresent(c -> usageDict.put("prompt", c));
                    usage.candidatesTokenCount().ifPresent(c -> usageDict.put("completion", c));
                    usage.totalTokenCount().ifPresent(c -> usageDict.put("total", c));
                  });

          Map<String, Object> contentMap = new HashMap<>();
          if (parsedContent.content() != null && !parsedContent.content().isNull()) {
            contentMap.put("response", parsedContent.content());
          }
          if (!usageDict.isEmpty()) {
            contentMap.put("usage", usageDict);
          }

          InvocationContext invocationContext = callbackContext.invocationContext();
          Optional<String> spanId = traceManager.getCurrentSpanId();
          SpanIds spanIds = traceManager.getCurrentSpanAndParent();
          String parentSpanId = spanIds.parentSpanId().orElse(null);

          boolean isPopped = false;
          Duration duration = Duration.ZERO;
          Duration ttft = null;
          Optional<Instant> startTime = Optional.empty();
          Optional<Instant> firstTokenTime = Optional.empty();

          if (spanId.isPresent()) {
            traceManager.recordFirstToken(spanId.get());
            startTime = traceManager.getStartTime(spanId.get());
            firstTokenTime = traceManager.getFirstTokenTime(spanId.get());
            if (startTime.isPresent() && firstTokenTime.isPresent()) {
              ttft = Duration.between(startTime.get(), firstTokenTime.get());
            }
          }

          if (llmResponse.partial().orElse(false)) {
            // Streaming chunk - do NOT pop span yet
            if (startTime.isPresent()) {
              duration = Duration.between(startTime.get(), Instant.now());
            }
          } else {
            // Final response - pop span
            Optional<RecordData> popped = traceManager.popSpan();
            if (popped.isPresent()) {
              spanId = Optional.of(popped.get().spanId());
              duration = popped.get().duration();
              isPopped = true;
            }
          }

          boolean hasAmbient = traceManager.hasAmbientSpan();
          boolean useOverride = isPopped && !hasAmbient;

          EventData.Builder eventDataBuilder = EventData.builder();
          if (!duration.isZero()) {
            eventDataBuilder.setLatency(duration);
          }
          if (ttft != null) {
            eventDataBuilder.setTimeToFirstToken(ttft);
          }
          llmResponse.modelVersion().ifPresent(eventDataBuilder::setModelVersion);

          if (!usageDict.isEmpty()) {
            eventDataBuilder.setUsageMetadata(usageDict);
          }

          if (useOverride) {
            if (spanId.isPresent()) {
              eventDataBuilder.setSpanIdOverride(spanId.get());
            }
            if (parentSpanId != null) {
              eventDataBuilder.setParentSpanIdOverride(parentSpanId);
            }
          }

          logEvent(
              "LLM_RESPONSE",
              invocationContext,
              contentMap.isEmpty() ? null : contentMap,
              parsedContent.isTruncated(),
              Optional.of(eventDataBuilder.build()));
        });
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    return Maybe.fromAction(
        () -> {
          InvocationContext invocationContext = callbackContext.invocationContext();
          Optional<RecordData> popped = traceManager.popSpan();
          String spanId = popped.map(RecordData::spanId).orElse(null);

          SpanIds spanIds = traceManager.getCurrentSpanAndParent();
          String parentSpanId = spanIds.spanId().orElse(null);

          boolean hasAmbient = traceManager.hasAmbientSpan();
          EventData.Builder eventDataBuilder =
              EventData.builder().setStatus("ERROR").setErrorMessage(error.getMessage());
          if (popped.isPresent()) {
            eventDataBuilder.setLatency(popped.get().duration());
          }
          if (!hasAmbient) {
            if (spanId != null) {
              eventDataBuilder.setSpanIdOverride(spanId);
            }
            if (parentSpanId != null) {
              eventDataBuilder.setParentSpanIdOverride(parentSpanId);
            }
          }
          logEvent("LLM_ERROR", invocationContext, null, Optional.of(eventDataBuilder.build()));
        });
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return Maybe.fromAction(
        () -> {
          TruncationResult res = smartTruncate(toolArgs, config.maxContentLength());
          ImmutableMap<String, Object> contentMap =
              ImmutableMap.of(
                  "tool_origin", getToolOrigin(tool), "tool", tool.name(), "args", res.node());
          traceManager.pushSpan("tool");
          logEvent("TOOL_STARTING", toolContext.invocationContext(), contentMap, Optional.empty());
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
          Optional<RecordData> popped = traceManager.popSpan();
          TruncationResult truncationResult = smartTruncate(result, config.maxContentLength());
          ImmutableMap<String, Object> contentMap =
              ImmutableMap.of(
                  "tool",
                  tool.name(),
                  "result",
                  truncationResult.node(),
                  "tool_origin",
                  getToolOrigin(tool));

          SpanIds spanIds = traceManager.getCurrentSpanAndParent();
          boolean hasAmbient = traceManager.hasAmbientSpan();

          EventData.Builder eventDataBuilder = EventData.builder();
          if (popped.isPresent()) {
            eventDataBuilder.setLatency(popped.get().duration());
          }
          if (!hasAmbient) {
            popped.ifPresent(p -> eventDataBuilder.setSpanIdOverride(p.spanId()));
            spanIds.spanId().ifPresent(eventDataBuilder::setParentSpanIdOverride);
          }

          logEvent(
              "TOOL_COMPLETED",
              toolContext.invocationContext(),
              contentMap,
              truncationResult.isTruncated(),
              Optional.of(eventDataBuilder.build()));
        });
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return Maybe.fromAction(
        () -> {
          Optional<RecordData> popped = traceManager.popSpan();
          TruncationResult truncationResult = smartTruncate(toolArgs, config.maxContentLength());
          String toolOrigin = getToolOrigin(tool);
          ImmutableMap<String, Object> contentMap =
              ImmutableMap.of(
                  "tool", tool.name(), "args", truncationResult.node(), "tool_origin", toolOrigin);

          SpanIds spanIds = traceManager.getCurrentSpanAndParent();
          boolean hasAmbient = traceManager.hasAmbientSpan();

          EventData.Builder eventDataBuilder =
              EventData.builder().setStatus("ERROR").setErrorMessage(error.getMessage());

          if (popped.isPresent()) {
            eventDataBuilder.setLatency(popped.get().duration());
          }
          if (!hasAmbient) {
            popped.ifPresent(p -> eventDataBuilder.setSpanIdOverride(p.spanId()));
            spanIds.spanId().ifPresent(eventDataBuilder::setParentSpanIdOverride);
          }

          logEvent(
              "TOOL_ERROR",
              toolContext.invocationContext(),
              contentMap,
              truncationResult.isTruncated(),
              Optional.of(eventDataBuilder.build()));
        });
  }

  private String getToolOrigin(BaseTool tool) {
    if (tool instanceof AbstractMcpTool) {
      return "MCP";
    }
    if (tool instanceof AgentTool agentTool) {
      return agentTool.getAgent().toolOrigin().equals(AgentOrigin.BASE_AGENT)
          ? AgentOrigin.SUB_AGENT.toString()
          : agentTool.getAgent().toolOrigin().toString();
    }
    if (tool.name().equals("transfer_to_agent")) {
      return "TRANSFER_AGENT";
    }
    if (tool instanceof FunctionTool) {
      return "LOCAL";
    }
    return "UNKNOWN";
  }
}
