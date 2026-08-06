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

package com.google.adk.plugins.reflectandretry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;

/**
 * Provides self-healing error recovery for tool failures.
 *
 * <p>This plugin intercepts tool failures, hands the model structured guidance for reflection and
 * correction, and lets it retry up to a configurable limit. Failure counts are tracked per tool
 * within a scope, so a success with one tool resets that tool's counter without forgiving
 * another's.
 *
 * <p>Port of adk-python's {@code ReflectAndRetryToolPlugin} ({@code
 * plugins/reflect_retry_tool_plugin.py}).
 *
 * <p>Example:
 *
 * <pre>{@code
 * Runner runner =
 *     new Runner(
 *         agent,
 *         APP_NAME,
 *         artifactService,
 *         sessionService,
 *         ImmutableList.of(new ReflectAndRetryToolPlugin(3)));
 * }</pre>
 *
 * <p>{@link #scopeKey} and {@link #extractErrorFromResult} are {@code protected} because adk-python
 * documents both as overridable.
 */
public class ReflectAndRetryToolPlugin extends BasePlugin {

  private static final String DEFAULT_NAME = "reflect_retry_tool_plugin";
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final String GLOBAL_SCOPE_KEY = "__global_reflect_and_retry_scope__";
  private static final String NEGATIVE_RETRIES = "maxRetries must be non-negative, but was %s";

  /** The observed failure count when retrying is disabled: the one failure being reported. */
  private static final int FIRST_FAILURE = 1;

  private final int maxRetries;
  private final boolean throwExceptionIfRetryExceeded;
  private final TrackingScope trackingScope;
  private final ToolFailureTracker failures = new ToolFailureTracker();

  /** Three retries, throwing when exceeded, tracked per invocation. */
  public ReflectAndRetryToolPlugin() {
    this(DEFAULT_NAME, DEFAULT_MAX_RETRIES, true, TrackingScope.INVOCATION);
  }

  /** As above, with a custom retry limit. */
  public ReflectAndRetryToolPlugin(int maxRetries) {
    this(DEFAULT_NAME, maxRetries, true, TrackingScope.INVOCATION);
  }

  /**
   * @param name plugin instance identifier
   * @param maxRetries maximum consecutive failures before giving up; {@code 0} disables retrying
   * @param throwExceptionIfRetryExceeded whether to propagate the final error once the limit is
   *     reached, rather than returning guidance
   * @param trackingScope lifetime of the failure counters
   * @throws IllegalArgumentException if {@code maxRetries} is negative
   * @throws NullPointerException if {@code trackingScope} is null
   */
  public ReflectAndRetryToolPlugin(
      String name,
      int maxRetries,
      boolean throwExceptionIfRetryExceeded,
      TrackingScope trackingScope) {
    super(name);
    checkArgument(maxRetries >= 0, NEGATIVE_RETRIES, maxRetries);
    this.maxRetries = maxRetries;
    this.throwExceptionIfRetryExceeded = throwExceptionIfRetryExceeded;
    this.trackingScope = checkNotNull(trackingScope, "trackingScope cannot be null");
  }

  /**
   * Resets the tool's failure count on success, or routes an error carried inside an otherwise
   * successful result into the retry logic.
   *
   * <p>A result this plugin produced earlier is passed straight through: reflecting on a reflection
   * would count a single tool failure twice.
   */
  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    if (ToolFailureResponse.isReflection(result)) {
      return Maybe.empty();
    }
    return extractErrorFromResult(tool, toolArgs, toolContext, result)
        .flatMap(error -> handleToolError(tool, toolArgs, toolContext, error))
        .switchIfEmpty(Maybe.fromRunnable(() -> resetFailures(tool, toolContext)));
  }

  /** Turns a thrown tool error into reflection guidance for the model. */
  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return handleToolError(tool, toolArgs, toolContext, error);
  }

  /**
   * Detects an error inside a tool result that did <em>not</em> throw — for example {@code
   * {"status": "error"}} — so it can drive the same retry logic.
   *
   * <p>Empty by default, exactly as in adk-python. Override to opt in.
   */
  protected Maybe<Throwable> extractErrorFromResult(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return Maybe.empty();
  }

  /**
   * The key failure counts are grouped under. Override to track per user or per session instead of
   * the configured {@link TrackingScope}.
   */
  protected String scopeKey(ToolContext toolContext) {
    return switch (trackingScope) {
      case INVOCATION -> toolContext.invocationId();
      case GLOBAL -> GLOBAL_SCOPE_KEY;
    };
  }

  /**
   * Counts the failure and decides between guidance, a final message, or propagating the error.
   *
   * <p>Never completes empty. {@link #afterToolCallback} treats an empty result as "the tool
   * succeeded" and resets the counter, so an empty return here would clear the count of the very
   * call that just failed.
   */
  private Maybe<Map<String, Object>> handleToolError(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    if (maxRetries == 0) {
      return exhausted(tool, toolArgs, error, FIRST_FAILURE);
    }
    int attempt = failures.recordFailure(scopeKey(toolContext), tool.name());
    if (attempt <= maxRetries) {
      return Maybe.just(reflection(tool, toolArgs, error, attempt));
    }
    return exhausted(tool, toolArgs, error, attempt);
  }

  /**
   * Either propagates the final error or hands back the give-up message, per configuration.
   *
   * <p>{@code failures} is the number of consecutive failures actually observed, which is what both
   * the give-up message and the response's {@code retry_count} report. Retrying is disabled at
   * {@code maxRetries == 0}, where nothing is counted at all — upstream returns before its counter
   * runs ({@code reflect_retry_tool_plugin.py:243-246}) and so does this — so the observed count
   * there is the one failure being reported.
   */
  private Maybe<Map<String, Object>> exhausted(
      BaseTool tool, Map<String, Object> toolArgs, Throwable error, int failures) {
    return throwExceptionIfRetryExceeded
        ? Maybe.error(error)
        : Maybe.just(retryExceeded(tool, toolArgs, error, failures));
  }

  private void resetFailures(BaseTool tool, ToolContext toolContext) {
    failures.reset(scopeKey(toolContext), tool.name());
  }

  private ImmutableMap<String, Object> reflection(
      BaseTool tool, Map<String, Object> toolArgs, Throwable error, int attempt) {
    return response(
        error, attempt, ReflectionGuidance.forRetry(tool, toolArgs, error, attempt, maxRetries));
  }

  private ImmutableMap<String, Object> retryExceeded(
      BaseTool tool, Map<String, Object> toolArgs, Throwable error, int failures) {
    return response(
        error, failures, ReflectionGuidance.forExhausted(tool, toolArgs, error, failures));
  }

  private static ImmutableMap<String, Object> response(
      Throwable error, int retryCount, String guidance) {
    return new ToolFailureResponse(
            error.getClass().getSimpleName(),
            Strings.nullToEmpty(error.getMessage()),
            retryCount,
            guidance)
        .toMap();
  }
}
