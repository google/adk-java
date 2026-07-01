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
import static com.google.adk.plugins.agentanalytics.BigQueryUtils.getVersionHeaderValue;
import static com.google.adk.plugins.agentanalytics.BigQueryUtils.maybeUpgradeSchema;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.convertToJsonNode;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.smartTruncate;
import static com.google.adk.plugins.agentanalytics.JsonFormatter.toJavaObject;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
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
import com.google.api.gax.rpc.FixedHeaderProvider;
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
import com.google.cloud.bigquery.TimePartitioning;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
  private final Object tableEnsuredLock = new Object();
  private final PluginState state;
  private volatile boolean tableEnsured = false;

  public BigQueryAgentAnalyticsPlugin(BigQueryLoggerConfig config) throws IOException {
    this(config, createBigQuery(config));
  }

  public BigQueryAgentAnalyticsPlugin(BigQueryLoggerConfig config, BigQuery bigQuery)
      throws IOException {
    this(config, bigQuery, new PluginState(config));
    // Register on the public construction paths only (not the package-private test constructor),
    // so a host that never calls close() still gets a best-effort drain at JVM exit.
    registerShutdownHook();
  }

  BigQueryAgentAnalyticsPlugin(BigQueryLoggerConfig config, BigQuery bigQuery, PluginState state) {
    super("bigquery_agent_analytics");
    this.config = config;
    this.bigQuery = bigQuery;
    this.state = state;
  }

  private void registerShutdownHook() {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    state
                        .close()
                        .blockingAwait(config.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
                  } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Error draining BQAA analytics on JVM shutdown", e);
                  }
                },
                "bq-analytics-shutdown"));
  }

  /**
   * Returns aggregated dropped-row counters keyed by reason ({@code queue_full}, {@code
   * append_error}, {@code serialization_error}). Non-zero values indicate analytics rows that never
   * reached BigQuery.
   */
  public ImmutableMap<String, Long> getDropStats() {
    return state.getDropStats();
  }

  private static BigQuery createBigQuery(BigQueryLoggerConfig config) throws IOException {
    BigQueryOptions.Builder builder = BigQueryOptions.newBuilder();
    builder.setHeaderProvider(
        FixedHeaderProvider.create(ImmutableMap.of("user-agent", getVersionHeaderValue())));
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
          // Only mark the table as ensured after a successful setup, so a transient first-run
          // failure (auth blip, missing dataset, quota) is retried on subsequent events instead
          // of permanently disabling table creation/upgrade for this plugin instance.
          if (ensureTableExists(bigQuery, config)) {
            tableEnsured = true;
          }
        }
      }
    }
  }

  /** Returns true if the events table is present (created or already existed) and ready. */
  private boolean ensureTableExists(BigQuery bigQuery, BigQueryLoggerConfig config) {
    TableId tableId = TableId.of(config.projectId(), config.datasetId(), config.tableName());
    Schema schema = BigQuerySchema.getEventsSchema();
    boolean tableReady = false;
    try {
      Table table = bigQuery.getTable(tableId);
      logger.fine("BigQuery table: " + tableId);
      if (table == null) {
        logger.info("Creating BigQuery table: " + tableId);
        StandardTableDefinition.Builder tableDefinitionBuilder =
            StandardTableDefinition.newBuilder()
                .setSchema(schema)
                // Day-partition on the event timestamp for cost/pruning parity with the Python
                // plugin. Time-filtered analytics queries prune partitions instead of full scans.
                .setTimePartitioning(
                    TimePartitioning.newBuilder(TimePartitioning.Type.DAY)
                        .setField("timestamp")
                        .build());
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
        try {
          bigQuery.create(tableInfo);
        } catch (BigQueryException e) {
          // Another writer may have created the table concurrently; treat that as success.
          String msg = e.getMessage();
          if (msg != null && msg.toLowerCase(Locale.ROOT).contains("already exists")) {
            logger.info("BigQuery table already exists (concurrent create): " + tableId);
          } else {
            throw e;
          }
        }
        tableReady = true;
      } else if (config.autoSchemaUpgrade()) {
        // Only treat the table as ready if the schema upgrade actually succeeded, so a failed
        // upgrade is retried on a later event instead of being masked by tableEnsured=true.
        tableReady = maybeUpgradeSchema(bigQuery, table);
      } else {
        tableReady = true;
      }
    } catch (BigQueryException e) {
      processBigQueryException(e, "Failed to check or create/upgrade BigQuery table: " + tableId);
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to check or create/upgrade BigQuery table: " + tableId, e);
    }

    try {
      if (config.createViews()) {
        var unused = state.getExecutor().submit(() -> createAnalyticsViews(bigQuery, config));
      }
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to create/update BigQuery views for table: " + tableId, e);
    }
    return tableReady;
  }

  private void processBigQueryException(BigQueryException e, String logMessage) {
    if (e.getMessage().contains("invalid_grant")) {
      logger.log(Level.SEVERE, "Failed to authenticate with BigQuery.", e);
    } else {
      logger.log(Level.WARNING, logMessage, e);
    }
  }

  private Completable logEvent(
      String eventType,
      InvocationContext invocationContext,
      @Nullable Object content,
      Optional<EventData> eventData) {
    return logEvent(eventType, invocationContext, content, false, eventData);
  }

  private Completable logEvent(
      String eventType,
      InvocationContext invocationContext,
      @Nullable Object content,
      boolean isContentTruncated,
      Optional<EventData> eventData) {
    if (!config.enabled()) {
      return Completable.complete();
    }
    if (!config.eventAllowlist().isEmpty() && !config.eventAllowlist().contains(eventType)) {
      return Completable.complete();
    }
    if (config.eventDenylist().contains(eventType)) {
      return Completable.complete();
    }
    if (state.isProcessed(invocationContext.invocationId())) {
      return Completable.complete();
    }
    if (config.contentFormatter() != null && content != null) {
      try {
        content = config.contentFormatter().apply(content, eventType);
      } catch (RuntimeException e) {
        logger.log(
            Level.WARNING,
            "Failed to format content for invocation ID: " + invocationContext.invocationId(),
            e);
        content = null; // Fail-closed to avoid leaking unmasked sensitive data
      }
    }

    // Resolve IDs before going async
    ResolvedTraceIds traceIds = getResolvedTraceIds(invocationContext, eventData);
    // Ensure table exists before logging.
    ensureTableExistsOnce();
    // Log common fields
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", Instant.now());
    row.put("event_type", eventType);
    row.put("agent", resolveAgentName(invocationContext, eventData));
    row.put("session_id", invocationContext.session().id());
    row.put("invocation_id", invocationContext.invocationId());
    row.put("user_id", invocationContext.userId());
    row.put("trace_id", traceIds.traceId());
    row.put("span_id", traceIds.spanId());
    row.put("parent_span_id", traceIds.parentSpanId());

    EventData data = eventData.orElse(EventData.builder().build());
    row.put("status", data.status());
    data.errorMessage().ifPresent(msg -> row.put("error_message", msg));

    Map<String, Object> latencyMap = extractLatency(data);
    if (latencyMap != null) {
      row.put("latency_ms", convertToJsonNode(latencyMap));
    }
    row.put("attributes", convertToJsonNode(getAttributes(data, invocationContext)));

    CompletableFuture<Void> parseFuture;
    if (content != null) {
      parseFuture =
          state
              .getParser()
              .parse(
                  content,
                  traceIds.traceId(),
                  traceIds.spanId() != null ? traceIds.spanId() : "no_span")
              .thenAccept(
                  parsedContent -> {
                    row.put(
                        "content_parts",
                        config.logMultiModalContent() ? parsedContent.parts() : ImmutableList.of());
                    row.put("content", parsedContent.content());
                    row.put("is_truncated", isContentTruncated || parsedContent.isTruncated());
                  })
              .exceptionally(
                  ex -> {
                    logger.log(
                        Level.WARNING,
                        "Failed to parse content for invocation ID: "
                            + invocationContext.invocationId(),
                        ex);
                    row.put("content", "Failed to parse content.");
                    row.put("content_parts", ImmutableList.of());
                    row.put("is_truncated", true);
                    return null;
                  });
    } else {
      parseFuture = CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> appendFuture =
        parseFuture.thenRun(
            () -> {
              BatchProcessor processor = state.getBatchProcessor(invocationContext.invocationId());
              processor.append(row);
            });
    state.addPendingTask(invocationContext.invocationId(), appendFuture);
    return Completable.complete();
  }

  /**
   * Resolves the agent name defensively. Workflow-driven callbacks may have no current agent; fall
   * back to the event author (via {@code EventData.fallbackAgentName}, mirroring the Python plugin)
   * and finally to a sentinel, rather than letting an NPE drop the row.
   */
  private static String resolveAgentName(
      InvocationContext invocationContext, Optional<EventData> eventData) {
    try {
      BaseAgent agent = invocationContext.agent();
      if (agent != null && agent.name() != null) {
        return agent.name();
      }
    } catch (RuntimeException e) {
      // Fall through to the author/sentinel fallback below.
    }
    return eventData.flatMap(EventData::fallbackAgentName).orElse("unknown");
  }

  private static EventData.Builder withFallbackAgent(
      EventData.Builder builder, @Nullable String author) {
    if (author != null && !author.isEmpty()) {
      builder.setFallbackAgentName(author);
    }
    return builder;
  }

  private ResolvedTraceIds getResolvedTraceIds(
      InvocationContext invocationContext, Optional<EventData> eventData) {
    TraceManager traceManager = state.getTraceManager(invocationContext.invocationId());
    String traceId =
        eventData
            .flatMap(EventData::traceIdOverride)
            .orElseGet(() -> traceManager.getTraceId(invocationContext));
    Optional<SpanIds> ambientSpanIds = traceManager.getAmbientSpanAndParent();
    SpanIds spanIds = ambientSpanIds.orElse(traceManager.getCurrentSpanAndParent());

    return new ResolvedTraceIds(
        traceId,
        eventData.flatMap(EventData::spanIdOverride).orElse(spanIds.spanId().orElse(null)),
        eventData
            .flatMap(EventData::parentSpanIdOverride)
            .orElse(spanIds.parentSpanId().orElse(null)));
  }

  private record ResolvedTraceIds(
      String traceId, @Nullable String spanId, @Nullable String parentSpanId) {}

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
    TraceManager traceManager = state.getTraceManager(invocationContext.invocationId());
    // Populate the root agent name from the invocation context if it has not been set yet, so
    // attributes.root_agent_name is a real name rather than the sentinel default.
    traceManager.initTraceIfNeeded(invocationContext);
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
        logger.log(
            Level.WARNING,
            "Failed to log session metadata for invocation ID: " + invocationContext.invocationId(),
            e);
      }
    }

    if (!config.customTags().isEmpty()) {
      attributes.put("custom_tags", config.customTags());
    }

    return attributes;
  }

  @Override
  public Completable close() {
    return state.close();
  }

  @VisibleForTesting
  PluginState getState() {
    return state;
  }

  private Optional<EventData> getCompletedEventData(InvocationContext invocationContext) {
    TraceManager traceManager = state.getTraceManager(invocationContext.invocationId());
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
    if (state.isProcessed(invocationContext.invocationId())) {
      return Maybe.empty();
    }
    state.getTraceManager(invocationContext.invocationId()).ensureInvocationSpan(invocationContext);
    Completable logCompletable =
        logEvent("USER_MESSAGE_RECEIVED", invocationContext, userMessage, Optional.empty());

    if (userMessage.parts().isPresent()) {
      for (Part part : userMessage.parts().get()) {
        if (part.functionCall().isPresent()
            && HITL_EVENT_TYPES.containsKey(part.functionCall().get().name().orElse(""))) {
          String hitlEvent = HITL_EVENT_TYPES.get(part.functionCall().get().name().get());
          TruncationResult truncatedResult = smartTruncate(part, config.maxContentLength());
          logCompletable =
              logCompletable.andThen(
                  logEvent(
                      hitlEvent + "_COMPLETED",
                      invocationContext,
                      ImmutableMap.of(
                          "tool",
                          part.functionCall().get().name().get(),
                          "result",
                          truncatedResult.node()),
                      truncatedResult.isTruncated(),
                      Optional.empty()));
        }
      }
    }
    return logCompletable.andThen(Maybe.empty());
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    if (state.isProcessed(invocationContext.invocationId())) {
      return Maybe.empty();
    }
    // Only emit STATE_DELTA when there is an actual state change, matching the Python plugin
    // (which does not write a STATE_DELTA row for events with an empty state delta).
    Completable logCompletable = Completable.complete();
    if (!event.actions().stateDelta().isEmpty()) {
      EventData.Builder eventDataBuilder =
          withFallbackAgent(
              EventData.builder()
                  .setExtraAttributes(
                      ImmutableMap.<String, Object>builder()
                          .put("state_delta", event.actions().stateDelta())
                          .put("author", event.author())
                          .buildOrThrow()),
              event.author());
      logCompletable =
          logEvent(
              "STATE_DELTA",
              invocationContext,
              event.content().orElse(null),
              Optional.of(eventDataBuilder.build()));
    }

    if (event.content().isPresent() && event.content().get().parts().isPresent()) {
      for (Part part : event.content().get().parts().get()) {
        if (part.functionCall().isPresent()
            && HITL_EVENT_TYPES.containsKey(part.functionCall().get().name().orElse(""))) {
          String hitlEvent = HITL_EVENT_TYPES.get(part.functionCall().get().name().get());
          TruncationResult truncatedResult =
              smartTruncate(part.functionCall().get().args(), config.maxContentLength());
          logCompletable =
              logCompletable.andThen(
                  logEvent(
                      hitlEvent + "_COMPLETED",
                      invocationContext,
                      ImmutableMap.of(
                          "tool",
                          part.functionCall().get().name().get(),
                          "args",
                          truncatedResult.node()),
                      truncatedResult.isTruncated(),
                      Optional.empty()));
        }
        if (part.functionResponse().isPresent()
            && HITL_EVENT_TYPES.containsKey(part.functionResponse().get().name().orElse(""))) {
          String hitlEvent = HITL_EVENT_TYPES.get(part.functionResponse().get().name().get());
          TruncationResult truncatedResult =
              smartTruncate(part.functionResponse().get().response(), config.maxContentLength());
          logCompletable =
              logCompletable.andThen(
                  logEvent(
                      hitlEvent + "_COMPLETED",
                      invocationContext,
                      ImmutableMap.of(
                          "tool",
                          part.functionResponse().get().name().get(),
                          "response",
                          truncatedResult.node()),
                      truncatedResult.isTruncated(),
                      Optional.empty()));
        }
      }
    }

    // --- A2A interaction logging ---
    if (event.customMetadata().isPresent()) {
      Map<String, Object> a2aKeys = new HashMap<>();
      for (CustomMetadata cm : event.customMetadata().get()) {
        if (cm.key().isPresent() && cm.key().get().startsWith(BigQueryUtils.A2A_PREFIX)) {
          cm.stringValue().ifPresent(val -> a2aKeys.put(cm.key().get(), val));
        }
      }
      if (a2aKeys.containsKey(BigQueryUtils.A2A_REQUEST_KEY)
          || a2aKeys.containsKey(BigQueryUtils.A2A_RESPONSE_KEY)) {
        Object responsePayload = a2aKeys.get(BigQueryUtils.A2A_RESPONSE_KEY);
        Object contentObject = null;
        boolean contentTruncated = false;
        if (responsePayload != null) {
          TruncationResult responseTruncated =
              smartTruncate(responsePayload, config.maxContentLength());
          contentObject = toJavaObject(responseTruncated.node());
          contentTruncated = responseTruncated.isTruncated();
        }

        // Exclude a2a:response from a2a_metadata to save storage space and avoid duplication
        Map<String, Object> a2aMetaKeys = new HashMap<>(a2aKeys);
        a2aMetaKeys.remove(BigQueryUtils.A2A_RESPONSE_KEY);
        TruncationResult a2aTruncated = smartTruncate(a2aMetaKeys, config.maxContentLength());

        Map<String, Object> extraAttributes = new HashMap<>();
        Object a2aMeta = toJavaObject(a2aTruncated.node());
        if (a2aMeta != null) {
          extraAttributes.put("a2a_metadata", a2aMeta);
        }

        logCompletable =
            logCompletable.andThen(
                logEvent(
                    "A2A_INTERACTION",
                    invocationContext,
                    contentObject,
                    a2aTruncated.isTruncated() || contentTruncated,
                    Optional.of(
                        withFallbackAgent(
                                EventData.builder().setExtraAttributes(extraAttributes),
                                event.author())
                            .build())));
      }
    }

    // --- Final agent response logging ---
    if (isFinalAgentResponse(event)) {
      List<Part> visibleParts = new ArrayList<>();
      for (Part part : event.content().get().parts().get()) {
        if (part.text().isPresent() && !part.thought().orElse(false)) {
          visibleParts.add(part);
        }
      }
      if (!visibleParts.isEmpty()) {
        Content visibleContent =
            Content.builder()
                .role(event.content().get().role().orElse("model"))
                .parts(visibleParts)
                .build();

        Map<String, Object> extraAttributes = new HashMap<>();
        if (event.id() != null) {
          extraAttributes.put("source_event_id", event.id());
        }
        if (event.author() != null) {
          extraAttributes.put("source_event_author", event.author());
        }
        event.branch().ifPresent(branch -> extraAttributes.put("source_event_branch", branch));

        logCompletable =
            logCompletable.andThen(
                logEvent(
                    "AGENT_RESPONSE",
                    invocationContext,
                    visibleContent,
                    false,
                    Optional.of(
                        withFallbackAgent(
                                EventData.builder().setExtraAttributes(extraAttributes),
                                event.author())
                            .build())));
      }
    }

    return logCompletable.andThen(Maybe.empty());
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    if (state.isProcessed(invocationContext.invocationId())) {
      return Maybe.empty();
    }
    state.getTraceManager(invocationContext.invocationId()).ensureInvocationSpan(invocationContext);
    return logEvent("INVOCATION_STARTING", invocationContext, null, Optional.empty())
        .andThen(Maybe.empty());
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return logEvent(
            "INVOCATION_COMPLETED",
            invocationContext,
            null,
            getCompletedEventData(invocationContext))
        .andThen(state.ensureInvocationCompleted(invocationContext.invocationId()));
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    if (state.isProcessed(callbackContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
    state
        .getTraceManager(callbackContext.invocationContext().invocationId())
        .pushSpan("agent:" + agent.name());
    return logEvent("AGENT_STARTING", callbackContext.invocationContext(), null, Optional.empty())
        .andThen(Maybe.empty());
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return logEvent(
            "AGENT_COMPLETED",
            callbackContext.invocationContext(),
            null,
            getCompletedEventData(callbackContext.invocationContext()))
        .andThen(Maybe.empty());
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
    if (state.isProcessed(callbackContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
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
        EventData.builder().setModel(req.model().orElse("")).setExtraAttributes(attributes).build();
    state
        .getTraceManager(callbackContext.invocationContext().invocationId())
        .pushSpan("llm_request");
    return logEvent("LLM_REQUEST", callbackContext.invocationContext(), req, Optional.of(eventData))
        .andThen(Maybe.empty());
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    if (state.isProcessed(callbackContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
    TraceManager traceManager =
        state.getTraceManager(callbackContext.invocationContext().invocationId());

    Map<String, Object> usageDict = new HashMap<>();
    llmResponse
        .usageMetadata()
        .ifPresent(
            usage -> {
              usage.promptTokenCount().ifPresent(c -> usageDict.put("prompt", c));
              usage.candidatesTokenCount().ifPresent(c -> usageDict.put("completion", c));
              usage.totalTokenCount().ifPresent(c -> usageDict.put("total", c));
              usage
                  .cachedContentTokenCount()
                  .ifPresent(c -> usageDict.put("cached_content_token_count", c));
            });

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

    return logEvent(
            "LLM_RESPONSE",
            invocationContext,
            llmResponse,
            false,
            Optional.of(eventDataBuilder.build()))
        .andThen(Maybe.empty());
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    if (state.isProcessed(callbackContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
    TraceManager traceManager =
        state.getTraceManager(callbackContext.invocationContext().invocationId());
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
    return logEvent("LLM_ERROR", invocationContext, null, Optional.of(eventDataBuilder.build()))
        .andThen(Maybe.empty());
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    if (state.isProcessed(toolContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
    ImmutableMap<String, Object> contentMap =
        ImmutableMap.of("tool_origin", getToolOrigin(tool), "tool", tool.name(), "args", toolArgs);
    state.getTraceManager(toolContext.invocationContext().invocationId()).pushSpan("tool");
    return logEvent("TOOL_STARTING", toolContext.invocationContext(), contentMap, Optional.empty())
        .andThen(Maybe.empty());
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    if (state.isProcessed(toolContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
    state
        .getTraceManager(toolContext.invocationContext().invocationId())
        .ensureInvocationSpan(toolContext.invocationContext());
    TraceManager traceManager =
        state.getTraceManager(toolContext.invocationContext().invocationId());
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

    return logEvent(
            "TOOL_COMPLETED",
            toolContext.invocationContext(),
            contentMap,
            truncationResult.isTruncated(),
            Optional.of(eventDataBuilder.build()))
        .andThen(Maybe.empty());
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    if (state.isProcessed(toolContext.invocationContext().invocationId())) {
      return Maybe.empty();
    }
    state
        .getTraceManager(toolContext.invocationContext().invocationId())
        .ensureInvocationSpan(toolContext.invocationContext());
    TraceManager traceManager =
        state.getTraceManager(toolContext.invocationContext().invocationId());
    Optional<RecordData> popped = traceManager.popSpan();

    TruncationResult truncationResult = smartTruncate(toolArgs, config.maxContentLength());
    ImmutableMap<String, Object> contentMap =
        ImmutableMap.<String, Object>builder()
            .put("tool", tool.name())
            .put("args", truncationResult.node())
            .put("tool_origin", getToolOrigin(tool))
            .buildOrThrow();

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

    return logEvent(
            "TOOL_ERROR",
            toolContext.invocationContext(),
            contentMap,
            truncationResult.isTruncated(),
            Optional.of(eventDataBuilder.build()))
        .andThen(Maybe.empty());
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

  /**
   * Returns true if the event represents a final agent response.
   *
   * <p>We verify finalResponse() along with empty checks for partial, function calls/responses, and
   * long-running tool IDs. This is required because finalResponse() would otherwise return true
   * even for thought-only, short-circuited skipSummarization() events (which ADK treats as
   * invisible internal reasoning and should not be logged as agent responses).
   */
  private boolean isFinalAgentResponse(Event event) {
    return event.content().isPresent()
        && event.content().get().parts().isPresent()
        && event.finalResponse()
        && !event.partial().orElse(false)
        && event.functionCalls().isEmpty()
        && event.functionResponses().isEmpty()
        && event.longRunningToolIds().map(Set::isEmpty).orElse(true);
  }
}
