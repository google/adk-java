/*
 * Copyright 2025 Google LLC
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
package com.google.adk.models.springai.observability;

import com.google.adk.models.springai.properties.SpringAIProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles observability features for Spring AI integration.
 *
 * <p>This class provides:
 *
 * <ul>
 *   <li>Metrics collection for request latency, token counts, and error rates
 *   <li>Request/response logging with configurable content inclusion
 *   <li>Performance monitoring for streaming and non-streaming requests
 * </ul>
 */
public class SpringAIObservabilityHandler {

  private static final Logger logger = LoggerFactory.getLogger(SpringAIObservabilityHandler.class);

  private final SpringAIProperties.Observability config;
  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private final Map<String, Double> timers = new ConcurrentHashMap<>();

  public SpringAIObservabilityHandler(SpringAIProperties.Observability config) {
    this.config = config;
  }

  /**
   * Records the start of a request.
   *
   * @param modelName the name of the model being used
   * @param requestType the type of request (e.g., "chat", "streaming")
   * @return a request context for tracking the request
   */
  public RequestContext startRequest(String modelName, String requestType) {
    if (!config.isEnabled()) {
      return new RequestContext(modelName, requestType, Instant.now(), false);
    }

    RequestContext context = new RequestContext(modelName, requestType, Instant.now(), true);

    if (config.isMetricsEnabled()) {
      incrementCounter("spring_ai_requests_total", modelName, requestType);
      logger.debug("Started {} request for model: {}", requestType, modelName);
    }

    return context;
  }

  /**
   * Records the completion of a successful request.
   *
   * @param context the request context
   * @param tokenCount the number of tokens processed (input + output)
   * @param inputTokens the number of input tokens
   * @param outputTokens the number of output tokens
   */
  public void recordSuccess(
      RequestContext context, int tokenCount, int inputTokens, int outputTokens) {
    if (!context.isObservable()) {
      return;
    }

    Duration duration = Duration.between(context.getStartTime(), Instant.now());

    if (config.isMetricsEnabled()) {
      recordTimer(
          "spring_ai_request_duration", duration, context.getModelName(), context.getRequestType());
      incrementCounter(
          "spring_ai_requests_success", context.getModelName(), context.getRequestType());
      recordGauge("spring_ai_tokens_total", tokenCount, context.getModelName());
      recordGauge("spring_ai_tokens_input", inputTokens, context.getModelName());
      recordGauge("spring_ai_tokens_output", outputTokens, context.getModelName());
    }

    logger.info(
        "Request completed successfully: model={}, type={}, duration={}ms, tokens={}",
        context.getModelName(),
        context.getRequestType(),
        duration.toMillis(),
        tokenCount);
  }

  /**
   * Records a failed request.
   *
   * @param context the request context
   * @param error the error that occurred
   */
  public void recordError(RequestContext context, Throwable error) {
    if (!context.isObservable()) {
      return;
    }

    Duration duration = Duration.between(context.getStartTime(), Instant.now());

    if (config.isMetricsEnabled()) {
      recordTimer(
          "spring_ai_request_duration", duration, context.getModelName(), context.getRequestType());
      incrementCounter(
          "spring_ai_requests_error", context.getModelName(), context.getRequestType());
      incrementCounter("spring_ai_errors_by_type", error.getClass().getSimpleName());
    }

    logger.error(
        "Request failed: model={}, type={}, duration={}ms, error={}",
        context.getModelName(),
        context.getRequestType(),
        duration.toMillis(),
        error.getMessage());
  }

  /**
   * Logs request content if enabled.
   *
   * @param content the request content
   * @param modelName the model name
   */
  public void logRequest(String content, String modelName) {
    if (config.isEnabled() && config.isIncludeContent()) {
      logger.debug("Request to {}: {}", modelName, truncateContent(content));
    }
  }

  /**
   * Logs response content if enabled.
   *
   * @param content the response content
   * @param modelName the model name
   */
  public void logResponse(String content, String modelName) {
    if (config.isEnabled() && config.isIncludeContent()) {
      logger.debug("Response from {}: {}", modelName, truncateContent(content));
    }
  }

  /**
   * Gets current metrics as a map for external monitoring systems.
   *
   * @return map of metric names to values
   */
  public Map<String, Number> getMetrics() {
    if (!config.isMetricsEnabled()) {
      return Map.of();
    }

    Map<String, Number> metrics = new ConcurrentHashMap<>();
    counters.forEach((key, value) -> metrics.put(key, value.get()));
    timers.forEach(metrics::put);
    return metrics;
  }

  private void incrementCounter(String name, String... tags) {
    String key = buildMetricKey(name, tags);
    counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
  }

  private void recordTimer(String name, Duration duration, String... tags) {
    String key = buildMetricKey(name, tags);
    timers.put(key, (double) duration.toMillis());
  }

  private void recordGauge(String name, double value, String... tags) {
    String key = buildMetricKey(name, tags);
    timers.put(key, value);
  }

  private String buildMetricKey(String name, String... tags) {
    if (tags.length == 0) {
      return name;
    }
    StringBuilder sb = new StringBuilder(name);
    for (String tag : tags) {
      sb.append("_").append(tag.replaceAll("[^a-zA-Z0-9_]", "_"));
    }
    return sb.toString();
  }

  private String truncateContent(String content) {
    if (content == null) {
      return "null";
    }
    return content.length() > 500 ? content.substring(0, 500) + "..." : content;
  }

  /** Context for tracking a single request. */
  public static class RequestContext {
    private final String modelName;
    private final String requestType;
    private final Instant startTime;
    private final boolean observable;

    public RequestContext(
        String modelName, String requestType, Instant startTime, boolean observable) {
      this.modelName = modelName;
      this.requestType = requestType;
      this.startTime = startTime;
      this.observable = observable;
    }

    public String getModelName() {
      return modelName;
    }

    public String getRequestType() {
      return requestType;
    }

    public Instant getStartTime() {
      return startTime;
    }

    public boolean isObservable() {
      return observable;
    }
  }
}
