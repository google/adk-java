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

import com.google.adk.Version;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BigQuery Agent Analytics Plugin.
 *
 * <p>Logs agent events to BigQuery using the provided {@link AnalyticsWriter} implementation.
 */
public class BigQueryAgentAnalyticsPlugin extends BasePlugin {
  private static final Logger LOGGER = LoggerFactory.getLogger(BigQueryAgentAnalyticsPlugin.class);
  private static final String TRACER_NAME = "google.adk.plugins.bigquery_agent_analytics";

  private final Tracer tracer =
      GlobalOpenTelemetry.getTracer(TRACER_NAME, Version.JAVA_ADK_VERSION);
  private final BigQueryLoggerConfig config;
  private final BatchProcessor batchProcessor;

  // Trace management
  private final ThreadLocal<List<Span>> spanStack = ThreadLocal.withInitial(ArrayList::new);
  private final ThreadLocal<List<Scope>> scopeStack = ThreadLocal.withInitial(ArrayList::new);

  public BigQueryAgentAnalyticsPlugin(AnalyticsWriter analyticsWriter) throws IOException {
    this(analyticsWriter, BigQueryLoggerConfig.builder().build());
  }

  public BigQueryAgentAnalyticsPlugin(AnalyticsWriter analyticsWriter, BigQueryLoggerConfig config)
      throws IOException {
    super("bigquery_agent_analytics");
    this.config = config;
    this.batchProcessor =
        new BatchProcessor(
            analyticsWriter,
            config.batchSize,
            config.batchFlushIntervalMs,
            config.queueMaxSize,
            config.shutdownTimeoutMs);
    this.batchProcessor.start();
  }

  /** Configuration for BigQuery logging. */
  public static class BigQueryLoggerConfig {
    public final boolean enabled;
    public final ImmutableList<String> eventAllowlist;
    public final ImmutableList<String> eventDenylist;
    public final int batchSize;
    public final long batchFlushIntervalMs;
    public final int maxContentLength; // 0 means no truncation
    public final int queueMaxSize;
    public final long shutdownTimeoutMs;

    private BigQueryLoggerConfig(Builder builder) {
      this.enabled = builder.enabled;
      this.eventAllowlist = ImmutableList.copyOf(builder.eventAllowlist);
      this.eventDenylist = ImmutableList.copyOf(builder.eventDenylist);
      this.batchSize = builder.batchSize;
      this.batchFlushIntervalMs = builder.batchFlushIntervalMs;
      this.maxContentLength = builder.maxContentLength;
      this.queueMaxSize = builder.queueMaxSize;
      this.shutdownTimeoutMs = builder.shutdownTimeoutMs;
    }

    public static Builder builder() {
      return new Builder();
    }

    /** Builder for {@link BigQueryLoggerConfig}. */
    public static class Builder {
      private boolean enabled = true;
      private int batchSize = 10;
      private long batchFlushIntervalMs = 1000;
      private List<String> eventAllowlist = ImmutableList.of();
      private List<String> eventDenylist = ImmutableList.of();
      private int maxContentLength = 0;
      private int queueMaxSize = 10000;
      private long shutdownTimeoutMs = 10000;

      private Builder() {}

      @CanIgnoreReturnValue
      public Builder setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setBatchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setBatchFlushIntervalMs(long batchFlushIntervalMs) {
        this.batchFlushIntervalMs = batchFlushIntervalMs;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setEventAllowlist(List<String> eventAllowlist) {
        this.eventAllowlist = eventAllowlist;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setEventDenylist(List<String> eventDenylist) {
        this.eventDenylist = eventDenylist;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setMaxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
        return this;
      }

      public BigQueryLoggerConfig build() {
        return new BigQueryLoggerConfig(this);
      }
    }
  }

  // --- Trace Helpers ---

  @SuppressWarnings("MustBeClosed")
  private void pushSpan(String name) {
    Span span = tracer.spanBuilder(name).startSpan();
    Scope scope = span.makeCurrent();
    spanStack.get().add(span);
    scopeStack.get().add(scope);
  }

  private void popSpan() {
    List<Span> spans = spanStack.get();
    List<Scope> scopes = scopeStack.get();

    if (!spans.isEmpty()) {
      Span span = spans.remove(spans.size() - 1);
      span.end();
    }
    if (!scopes.isEmpty()) {
      Scope scope = scopes.remove(scopes.size() - 1);
      scope.close();
    }
  }

  private @Nullable String getCurrentSpanId() {
    List<Span> spans = spanStack.get();
    if (!spans.isEmpty()) {
      SpanContext ctx = spans.get(spans.size() - 1).getSpanContext();
      if (ctx.isValid()) {
        return ctx.getSpanId();
      }
    }
    return null;
  }

  private @Nullable String getCurrentTraceId() {
    List<Span> spans = spanStack.get();
    if (!spans.isEmpty()) {
      SpanContext ctx = spans.get(spans.size() - 1).getSpanContext();
      if (ctx.isValid()) {
        return ctx.getTraceId();
      }
    }
    return null;
  }

  @Override
  public Completable close() {
    return Completable.fromAction(
            () -> {
              batchProcessor.shutdown();
            })
        .andThen(super.close());
  }

  // --- Callbacks ---

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    return Maybe.fromAction(
        () -> {
          pushSpan("invocation_" + invocationContext.invocationId());
          logEvent(
              "INVOCATION_STARTING", invocationContext.agent().name(), null, invocationContext);
        });
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(
        () -> {
          logEvent(
              "INVOCATION_COMPLETED", invocationContext.agent().name(), null, invocationContext);
          popSpan();
        });
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          pushSpan("agent_" + agent.name());
          logEvent("AGENT_STARTING", agent.name(), null, callbackContext);
        });
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          logEvent("AGENT_COMPLETED", agent.name(), null, callbackContext);
          popSpan();
        });
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return Maybe.fromAction(
        () -> {
          pushSpan("llm_request");
          LlmRequest request = llmRequest.build();
          logEvent(
              "LLM_REQUEST",
              callbackContext.agentName(),
              ImmutableMap.of("model", request.model().orElse("default")),
              callbackContext);
        });
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return Maybe.fromAction(
        () -> {
          logEvent("LLM_RESPONSE", callbackContext.agentName(), ImmutableMap.of(), callbackContext);
          popSpan();
        });
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return Maybe.fromAction(
        () -> {
          pushSpan("tool_" + tool.name());
          logEvent(
              "TOOL_CALL",
              toolContext.agentName(),
              ImmutableMap.of("tool_name", tool.name(), "arguments", toolArgs),
              toolContext);
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
          logEvent(
              "TOOL_RESPONSE",
              toolContext.agentName(),
              ImmutableMap.of("tool_name", tool.name(), "result", result),
              toolContext);
          popSpan();
        });
  }

  private void logEvent(
      String eventType, String agentName, ImmutableMap<String, Object> content, Object context) {
    if (!config.enabled) {
      return;
    }

    if (!config.eventAllowlist.isEmpty() && !config.eventAllowlist.contains(eventType)) {
      return;
    }

    if (config.eventDenylist.contains(eventType)) {
      return;
    }

    ImmutableMap.Builder<String, Object> row = ImmutableMap.builder();
    row.put("timestamp", Instant.now().toString()); // ISO-8601
    row.put("event_type", eventType);
    row.put("agent", agentName);
    String traceId = getCurrentTraceId();
    if (traceId != null) {
      row.put("trace_id", traceId);
    }
    String spanId = getCurrentSpanId();
    if (spanId != null) {
      row.put("span_id", spanId);
    }

    if (context instanceof InvocationContext ctx) {
      row.put("session_id", ctx.session().id());
      row.put("invocation_id", ctx.invocationId());
      row.put("user_id", ctx.userId());
    } else if (context instanceof CallbackContext ctx) {
      row.put("invocation_id", ctx.invocationId());
    }

    if (content != null) {
      String str = content.toString();
      String truncated = truncateContent(str, config.maxContentLength);
      row.put("content", truncated);
      row.put("is_truncated", truncated.length() < str.length());
    }

    batchProcessor.enqueue(row.buildOrThrow());
  }

  private static String truncateContent(String content, int maxLength) {
    if (maxLength > 0 && content.length() > maxLength) {
      int truncateAt = Math.max(0, maxLength - 13); // "...[TRUNCATED]" length is 13
      return content.substring(0, truncateAt) + "...[TRUNCATED]";
    }
    return content;
  }
}
