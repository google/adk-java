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

package com.google.adk.plugins;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides self-healing error recovery for tool failures.
 *
 * <p>This plugin intercepts tool failures, hands the model structured guidance for reflection and
 * correction, and lets it retry up to a configurable limit. Failure counts are tracked per tool
 * within a scope, so a success with one tool resets that tool's counter without forgiving
 * another's.
 *
 * <p>A failure is an error a tool emits, such as {@code Single.error}, or an error the {@link
 * ResultErrorExtractor} finds in a result. {@code FunctionTool} returns an exception thrown by its
 * method as a {@code {"status": "error"}} result, so such failures reach this plugin only through a
 * result error extractor. Calls to a tool name the agent does not have never reach this plugin.
 *
 * <p>Port of adk-python's {@code ReflectAndRetryToolPlugin} ({@code
 * plugins/reflect_retry_tool_plugin.py}).
 *
 * <p>Example:
 *
 * <pre>{@code
 * Runner runner =
 *     new InMemoryRunner(
 *         agent,
 *         APP_NAME,
 *         ImmutableList.of(ReflectAndRetryToolPlugin.builder().maxRetries(3).build()));
 * }</pre>
 */
@Experimental
public final class ReflectAndRetryToolPlugin extends BasePlugin {

  private static final String DEFAULT_NAME = "reflect_retry_tool_plugin";
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final String GLOBAL_SCOPE_KEY = "__global_reflect_and_retry_scope__";
  private static final String NEGATIVE_RETRIES = "maxRetries must be non-negative, but was %s";

  /** The {@code error_type} reported for an error that is not a {@link Throwable}. */
  private static final String TOOL_ERROR_TYPE = "ToolError";

  private static final String RESULT_ERROR_EXCEEDED =
      "A tool result reported an error and the retry limit was exceeded.";

  private static final ResultErrorExtractor NO_RESULT_ERRORS =
      (tool, toolArgs, toolContext, result) -> Optional.empty();

  /** Marks a tool result as this plugin's own output rather than a real tool response. */
  private static final String REFLECT_AND_RETRY_RESPONSE_TYPE =
      "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN";

  private static final String RESPONSE_TYPE_KEY = "response_type";
  private static final String ERROR_TYPE_KEY = "error_type";
  private static final String ERROR_DETAILS_KEY = "error_details";
  private static final String RETRY_COUNT_KEY = "retry_count";
  private static final String REFLECTION_GUIDANCE_KEY = "reflection_guidance";

  private static final String ERROR_DETAILS_FORMAT = "%s: %s";

  /** Written in place of a map or list that contains itself. */
  private static final String CIRCULAR_REFERENCE = "<circular reference>";

  private static final String RETRY_GUIDANCE =
      """
      The call to tool `%s` failed.

      **Error Details:**
      ```
      %s
      ```

      **Tool Arguments Used:**
      ```json
      %s
      ```

      **Reflection Guidance:**
      This is retry attempt **%d of %d**. Analyze the error and the arguments you provided. Do not \
      repeat the exact same call. Consider the following before your next attempt:

      1.  **Invalid Parameters**: Does the error suggest that one or more arguments are incorrect, \
      badly formatted, or missing? Review the tool's schema and your arguments.
      2.  **State or Preconditions**: Did a previous step fail or not produce the necessary \
      state/resource for this tool to succeed?
      3.  **Alternative Approach**: Is this the right tool for the job? Could another tool or a \
      different sequence of steps achieve the goal?
      4.  **Simplify the Task**: Can you break the problem down into smaller, simpler steps?
      5.  **Wrong Function Name**: Does the error indicates the tool is not found? Please check \
      again and only use available tools.

      Formulate a new plan based on your analysis and try a corrected or different approach.""";

  private static final String EXHAUSTED_GUIDANCE =
      """
      The tool `%s` has failed consecutively %d times and the retry limit has been exceeded.

      **Last Error:**
      ```
      %s
      ```

      **Last Arguments Used:**
      ```json
      %s
      ```

      **Final Instruction:**
      **Do not attempt to use the `%s` tool again for this task.** You must now try a different \
      approach. Acknowledge the failure and devise a new strategy, potentially using other \
      available tools or informing the user that the task cannot be completed.""";

  private final int maxRetries;
  private final boolean throwExceptionIfRetryExceeded;
  private final TrackingScope trackingScope;
  private final ResultErrorExtractor resultErrorExtractor;

  /** Consecutive-failure counts, one per tool per tracking scope, updated atomically. */
  private final ConcurrentHashMap<ToolInScope, Integer> failureCounts = new ConcurrentHashMap<>();

  /** Defines the lifecycle scope for tracking tool failure counts. */
  @Experimental
  public enum TrackingScope {
    /** Failure counts live for one invocation and are keyed by its invocation id. */
    INVOCATION,

    /** Failure counts live for the lifetime of the plugin instance, shared across invocations. */
    GLOBAL;
  }

  /** Finds an error that a tool reported in its result instead of throwing. */
  @Experimental
  @FunctionalInterface
  public interface ResultErrorExtractor {

    /**
     * Returns the error in {@code result}, such as {@code result} itself when it holds {@code
     * "status": "error"}, or empty if the call succeeded.
     *
     * <p>An error that is not a {@link Throwable} is reported to the model with {@code error_type}
     * {@code "ToolError"}; a {@link Map} error is passed as data, so like a tool result it must
     * hold only JSON-native values. Past the limit, when {@code throwExceptionIfRetryExceeded} is
     * set, a {@link Throwable} error is emitted as is and any other error as an {@link
     * IllegalStateException} with a generic message.
     */
    Optional<Object> extractErrorFromResult(
        BaseTool tool,
        Map<String, Object> toolArgs,
        ToolContext toolContext,
        Map<String, Object> result);
  }

  private ReflectAndRetryToolPlugin(Builder builder) {
    super(builder.name);
    this.maxRetries = builder.maxRetries;
    this.throwExceptionIfRetryExceeded = builder.throwExceptionIfRetryExceeded;
    this.trackingScope = builder.trackingScope;
    this.resultErrorExtractor = builder.resultErrorExtractor;
  }

  /**
   * Returns a builder defaulting to three retries, throwing once they are exceeded, counted per
   * invocation, with no result errors.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ReflectAndRetryToolPlugin}. */
  public static final class Builder {
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private boolean throwExceptionIfRetryExceeded = true;
    private TrackingScope trackingScope = TrackingScope.INVOCATION;
    private ResultErrorExtractor resultErrorExtractor = NO_RESULT_ERRORS;
    private String name = DEFAULT_NAME;

    private Builder() {}

    /**
     * Sets the number of consecutive failures of one tool answered with retry guidance; {@code 0}
     * disables retrying.
     *
     * @throws IllegalArgumentException if {@code maxRetries} is negative
     */
    @CanIgnoreReturnValue
    public Builder maxRetries(int maxRetries) {
      checkArgument(maxRetries >= 0, NEGATIVE_RETRIES, maxRetries);
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Sets whether, past the limit, the error propagates rather than the model being told to stop
     * using the tool.
     */
    @CanIgnoreReturnValue
    public Builder throwExceptionIfRetryExceeded(boolean throwExceptionIfRetryExceeded) {
      this.throwExceptionIfRetryExceeded = throwExceptionIfRetryExceeded;
      return this;
    }

    /** Sets whether failures are counted per invocation or across invocations. */
    @CanIgnoreReturnValue
    public Builder trackingScope(TrackingScope trackingScope) {
      this.trackingScope = checkNotNull(trackingScope);
      return this;
    }

    /** Sets how errors reported inside tool results are found. */
    @CanIgnoreReturnValue
    public Builder resultErrorExtractor(ResultErrorExtractor resultErrorExtractor) {
      this.resultErrorExtractor = checkNotNull(resultErrorExtractor);
      return this;
    }

    /** Sets the plugin instance name. */
    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    public ReflectAndRetryToolPlugin build() {
      return new ReflectAndRetryToolPlugin(this);
    }
  }

  /**
   * Resets the tool's failure count on success, or routes an error carried inside an otherwise
   * successful result into the retry logic. A response this plugin produced is passed through.
   */
  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    if (isReflection(result)) {
      return Maybe.empty();
    }
    return Maybe.defer(() -> checkResult(tool, toolArgs, toolContext, result));
  }

  /** Drops the ended invocation's failure counts. */
  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(() -> clearInvocation(invocationContext));
  }

  /** Drops the failed invocation's failure counts. */
  @Override
  public Completable onRunErrorCallback(InvocationContext invocationContext, Throwable error) {
    return Completable.fromAction(() -> clearInvocation(invocationContext));
  }

  /** Turns a thrown tool error into reflection guidance for the model. */
  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return Maybe.defer(() -> handleToolError(tool, toolArgs, toolContext, error));
  }

  /** The key failure counts are grouped under, per the configured {@link TrackingScope}. */
  private String scopeKey(ToolContext toolContext) {
    return switch (trackingScope) {
      case INVOCATION -> toolContext.invocationId();
      case GLOBAL -> GLOBAL_SCOPE_KEY;
    };
  }

  /** Resets the tool's count if its result reports no error, else handles the reported error. */
  private Maybe<Map<String, Object>> checkResult(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    Optional<Object> error =
        resultErrorExtractor.extractErrorFromResult(tool, toolArgs, toolContext, result);
    if (error.isEmpty()) {
      resetFailures(tool, toolContext);
      return Maybe.empty();
    }
    return handleResultError(tool, toolArgs, toolContext, error.get());
  }

  /**
   * Handles an error found inside a tool result, emitting the error where {@link #handleToolError}
   * completes empty.
   */
  private Maybe<Map<String, Object>> handleResultError(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Object error) {
    return handleToolError(tool, toolArgs, toolContext, error)
        .switchIfEmpty(Maybe.error(asThrowable(error)));
  }

  /**
   * Counts the failure and returns guidance or the give-up message. Completes empty when the limit
   * is reached and the original error should propagate.
   */
  private Maybe<Map<String, Object>> handleToolError(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Object error) {
    if (maxRetries == 0) {
      return exhausted(tool, toolArgs, error);
    }
    int attempt = recordFailure(scopeKey(toolContext), tool.name());
    if (attempt <= maxRetries) {
      return Maybe.just(reflection(tool, toolArgs, error, attempt));
    }
    return exhausted(tool, toolArgs, error);
  }

  /** Completes empty to let the error propagate, or hands back the give-up message. */
  private Maybe<Map<String, Object>> exhausted(
      BaseTool tool, Map<String, Object> toolArgs, Object error) {
    return throwExceptionIfRetryExceeded
        ? Maybe.empty()
        : Maybe.just(retryExceeded(tool, toolArgs, error));
  }

  /** Records one more consecutive failure and returns the new count, atomically. */
  private int recordFailure(String scopeKey, String toolName) {
    return failureCounts.merge(new ToolInScope(scopeKey, toolName), 1, Integer::sum);
  }

  /** Clears one tool's failure count within its scope, leaving every other tool's untouched. */
  private void resetFailures(BaseTool tool, ToolContext toolContext) {
    failureCounts.remove(new ToolInScope(scopeKey(toolContext), tool.name()));
  }

  /**
   * Removes every count keyed by the invocation's id. Counts kept under any other key, such as
   * {@link TrackingScope#GLOBAL}'s, are untouched.
   */
  private void clearInvocation(InvocationContext invocationContext) {
    String invocationId = invocationContext.invocationId();
    failureCounts.keySet().removeIf(key -> key.scopeKey().equals(invocationId));
  }

  private ImmutableMap<String, Object> reflection(
      BaseTool tool, Map<String, Object> toolArgs, Object error, int attempt) {
    return response(error, attempt, retryGuidance(tool, toolArgs, error, attempt));
  }

  private ImmutableMap<String, Object> retryExceeded(
      BaseTool tool, Map<String, Object> toolArgs, Object error) {
    return response(error, maxRetries, exhaustedGuidance(tool, toolArgs, error));
  }

  /** Asks the model to analyze the failure and try a corrected call. */
  private String retryGuidance(
      BaseTool tool, Map<String, Object> toolArgs, Object error, int attempt) {
    return RETRY_GUIDANCE.formatted(
        tool.name(), formatErrorDetails(error), asJson(toolArgs), attempt, maxRetries);
  }

  /** Tells the model to stop calling the tool and change approach. */
  private String exhaustedGuidance(BaseTool tool, Map<String, Object> toolArgs, Object error) {
    return EXHAUSTED_GUIDANCE.formatted(
        tool.name(), maxRetries, formatErrorDetails(error), asJson(toolArgs), tool.name());
  }

  /** The error as shown inside the guidance text. */
  private static String formatErrorDetails(Object error) {
    if (error instanceof Throwable throwable) {
      return ERROR_DETAILS_FORMAT.formatted(
          errorType(throwable), Strings.nullToEmpty(throwable.getMessage()));
    }
    return error instanceof Map<?, ?> map ? asJson(map) : String.valueOf(error);
  }

  /**
   * Pretty-prints a map as JSON for the guidance message. A value that cannot be serialized is
   * written as its string form.
   */
  private static String asJson(Map<?, ?> map) {
    try {
      return writeJson(map);
    } catch (JsonProcessingException e) {
      return asJsonWithTextFallback(map);
    }
  }

  private static String asJsonWithTextFallback(Map<?, ?> map) {
    try {
      return writeJson(jsonSafe(map, Collections.newSetFromMap(new IdentityHashMap<>())));
    } catch (JsonProcessingException e) {
      return String.valueOf(map);
    }
  }

  /**
   * Copies maps, lists and arrays, replacing each leaf that cannot be serialized with its string
   * form, and a container that contains itself with {@link #CIRCULAR_REFERENCE}.
   *
   * @param path the containers currently being copied, compared by identity
   */
  private static Object jsonSafe(Object value, Set<Object> path) {
    if (!isContainer(value)) {
      return isSerializable(value) ? value : String.valueOf(value);
    }
    if (!path.add(value)) {
      return CIRCULAR_REFERENCE;
    }
    Object copy =
        value instanceof Map<?, ?> map ? jsonSafeMap(map, path) : jsonSafeList(items(value), path);
    path.remove(value);
    return copy;
  }

  private static boolean isContainer(Object value) {
    return value instanceof Map<?, ?> || value instanceof Iterable<?> || value instanceof Object[];
  }

  private static Iterable<?> items(Object container) {
    return container instanceof Object[] array ? Arrays.asList(array) : (Iterable<?>) container;
  }

  private static Map<String, Object> jsonSafeMap(Map<?, ?> map, Set<Object> path) {
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, item) -> copy.put(String.valueOf(key), jsonSafe(item, path)));
    return Collections.unmodifiableMap(copy);
  }

  private static List<Object> jsonSafeList(Iterable<?> items, Set<Object> path) {
    List<Object> copy = new ArrayList<>();
    items.forEach(item -> copy.add(jsonSafe(item, path)));
    return Collections.unmodifiableList(copy);
  }

  private static boolean isSerializable(Object value) {
    try {
      JsonBaseModel.getMapper().writeValueAsString(value);
      return true;
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  private static String writeJson(Object value) throws JsonProcessingException {
    return JsonBaseModel.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
  }

  /** The tool response substituted for a failed call. */
  private static ImmutableMap<String, Object> response(
      Object error, int retryCount, String guidance) {
    return ImmutableMap.of(
        RESPONSE_TYPE_KEY, REFLECT_AND_RETRY_RESPONSE_TYPE,
        ERROR_TYPE_KEY, errorType(error),
        ERROR_DETAILS_KEY, errorDetails(error),
        RETRY_COUNT_KEY, retryCount,
        REFLECTION_GUIDANCE_KEY, guidance);
  }

  private static String errorType(Object error) {
    return error instanceof Throwable ? error.getClass().getSimpleName() : TOOL_ERROR_TYPE;
  }

  /** A {@link Throwable}'s message, a {@link Map} error as data, anything else as text. */
  private static Object errorDetails(Object error) {
    if (error instanceof Throwable throwable) {
      return Strings.nullToEmpty(throwable.getMessage());
    }
    return error instanceof Map<?, ?> ? error : String.valueOf(error);
  }

  /** The error to emit past the limit; one that is not a {@link Throwable} is tool output. */
  private static Throwable asThrowable(Object error) {
    return error instanceof Throwable throwable
        ? throwable
        : new IllegalStateException(RESULT_ERROR_EXCEEDED);
  }

  /** Whether {@code result} is a response this plugin produced earlier. */
  private static boolean isReflection(Object result) {
    return result instanceof Map<?, ?> map
        && REFLECT_AND_RETRY_RESPONSE_TYPE.equals(map.get(RESPONSE_TYPE_KEY));
  }

  /** The counter key: one tool, within one tracking scope. */
  private record ToolInScope(String scopeKey, String toolName) {}
}
