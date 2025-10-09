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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles observability features for Spring AI integration using Micrometer.
 *
 * <p>This class provides:
 *
 * <ul>
 *   <li>Metrics collection for request latency, token counts, and error rates via Micrometer
 *   <li>Request/response logging with configurable content inclusion
 *   <li>Performance monitoring for streaming and non-streaming requests
 *   <li>Integration with any Micrometer-compatible metrics backend (Prometheus, Datadog, etc.)
 * </ul>
 */
public class SpringAIObservabilityHandler {

  private static final Logger logger = LoggerFactory.getLogger(SpringAIObservabilityHandler.class);

  private final SpringAIProperties.Observability config;
  private final MeterRegistry meterRegistry;

  /**
   * Creates an observability handler with a default SimpleMeterRegistry.
   *
   * @param config the observability configuration
   */
  public SpringAIObservabilityHandler(SpringAIProperties.Observability config) {
    this(config, new SimpleMeterRegistry());
  }

  /**
   * Creates an observability handler with a custom MeterRegistry.
   *
   * @param config the observability configuration
   * @param meterRegistry the Micrometer meter registry to use for metrics
   */
  public SpringAIObservabilityHandler(
      SpringAIProperties.Observability config, MeterRegistry meterRegistry) {
    this.config = config;
    this.meterRegistry = meterRegistry;
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
      return new RequestContext(modelName, requestType, Instant.now(), false, null);
    }

    Timer.Sample timerSample = config.isMetricsEnabled() ? Timer.start(meterRegistry) : null;
    RequestContext context =
        new RequestContext(modelName, requestType, Instant.now(), true, timerSample);

    if (config.isMetricsEnabled()) {
      Counter.builder("spring.ai.requests.total")
          .tag("model", modelName)
          .tag("type", requestType)
          .description("Total number of Spring AI requests")
          .register(meterRegistry)
          .increment();
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
      // Record timer using Micrometer's Timer.Sample
      if (context.getTimerSample() != null) {
        context
            .getTimerSample()
            .stop(
                Timer.builder("spring.ai.request.duration")
                    .tag("model", context.getModelName())
                    .tag("type", context.getRequestType())
                    .tag("outcome", "success")
                    .description("Duration of Spring AI requests")
                    .register(meterRegistry));
      }

      // Increment success counter
      Counter.builder("spring.ai.requests.success")
          .tag("model", context.getModelName())
          .tag("type", context.getRequestType())
          .description("Number of successful Spring AI requests")
          .register(meterRegistry)
          .increment();

      // Record token gauges
      Gauge.builder("spring.ai.tokens.total", () -> tokenCount)
          .tag("model", context.getModelName())
          .description("Total tokens processed")
          .register(meterRegistry);

      Gauge.builder("spring.ai.tokens.input", () -> inputTokens)
          .tag("model", context.getModelName())
          .description("Input tokens processed")
          .register(meterRegistry);

      Gauge.builder("spring.ai.tokens.output", () -> outputTokens)
          .tag("model", context.getModelName())
          .description("Output tokens generated")
          .register(meterRegistry);
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
      // Record timer with error outcome
      if (context.getTimerSample() != null) {
        context
            .getTimerSample()
            .stop(
                Timer.builder("spring.ai.request.duration")
                    .tag("model", context.getModelName())
                    .tag("type", context.getRequestType())
                    .tag("outcome", "error")
                    .description("Duration of Spring AI requests")
                    .register(meterRegistry));
      }

      // Increment error counter
      Counter.builder("spring.ai.requests.error")
          .tag("model", context.getModelName())
          .tag("type", context.getRequestType())
          .description("Number of failed Spring AI requests")
          .register(meterRegistry)
          .increment();

      // Track errors by type
      Counter.builder("spring.ai.errors.by.type")
          .tag("error.type", error.getClass().getSimpleName())
          .description("Number of errors by exception type")
          .register(meterRegistry)
          .increment();
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
   * Gets the Micrometer MeterRegistry for direct access to metrics.
   *
   * <p>This allows users to export metrics to any Micrometer-compatible backend (Prometheus,
   * Datadog, CloudWatch, etc.) or query metrics programmatically.
   *
   * @return the MeterRegistry instance
   */
  public MeterRegistry getMeterRegistry() {
    return meterRegistry;
  }

  private String truncateContent(String content) {
    if (content == null) {
      return "null";
    }
    return content.length() > 500 ? content.substring(0, 500) + "..." : content;
  }

  /** Context for tracking a single request with Micrometer timer. */
  public static class RequestContext {
    private final String modelName;
    private final String requestType;
    private final Instant startTime;
    private final boolean observable;
    private final Timer.Sample timerSample;

    public RequestContext(
        String modelName,
        String requestType,
        Instant startTime,
        boolean observable,
        Timer.Sample timerSample) {
      this.modelName = modelName;
      this.requestType = requestType;
      this.startTime = startTime;
      this.observable = observable;
      this.timerSample = timerSample;
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

    public Timer.Sample getTimerSample() {
      return timerSample;
    }
  }
}
