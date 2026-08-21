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
package com.google.adk.models.springai.error;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Spring AI exceptions to appropriate ADK exceptions and error handling strategies.
 *
 * <p>This class provides:
 *
 * <ul>
 *   <li>Exception classification and mapping
 *   <li>Retry strategy recommendations
 *   <li>Error message normalization
 *   <li>Rate limiting detection
 * </ul>
 */
public class SpringAIErrorMapper {

  private static final Logger logger = LoggerFactory.getLogger(SpringAIErrorMapper.class);

  /** Error categories for different types of failures. */
  public enum ErrorCategory {
    /** Authentication or authorization errors */
    AUTH_ERROR,
    /** Rate limiting or quota exceeded */
    RATE_LIMITED,
    /** Network connectivity issues */
    NETWORK_ERROR,
    /** Invalid request parameters or format */
    CLIENT_ERROR,
    /** Server-side errors from the AI provider */
    SERVER_ERROR,
    /** Timeout errors */
    TIMEOUT_ERROR,
    /** Model-specific errors (model not found, unsupported features) */
    MODEL_ERROR,
    /** Unknown or unclassified errors */
    UNKNOWN_ERROR
  }

  /** Retry strategy recommendations. */
  public enum RetryStrategy {
    /** Do not retry - permanent failure */
    NO_RETRY,
    /** Retry with exponential backoff */
    EXPONENTIAL_BACKOFF,
    /** Retry with fixed delay */
    FIXED_DELAY,
    /** Retry immediately (for transient network issues) */
    IMMEDIATE_RETRY
  }

  /**
   * Maps a Spring AI exception to an error category and retry strategy.
   *
   * @param exception the Spring AI exception
   * @return mapped error information
   */
  public static MappedError mapError(Throwable exception) {
    if (exception == null) {
      return new MappedError(ErrorCategory.UNKNOWN_ERROR, RetryStrategy.NO_RETRY, "Unknown error");
    }

    String message = exception.getMessage();
    String className = exception.getClass().getSimpleName();

    logger.debug("Mapping Spring AI error: {} - {}", className, message);

    // Network and timeout errors
    if (exception instanceof TimeoutException || exception instanceof SocketTimeoutException) {
      return new MappedError(
          ErrorCategory.TIMEOUT_ERROR,
          RetryStrategy.EXPONENTIAL_BACKOFF,
          "Request timed out: " + message);
    }

    // Analyze error message for common patterns
    if (message != null) {
      String lowerMessage = message.toLowerCase();

      // Authentication errors
      if (lowerMessage.contains("unauthorized")
          || lowerMessage.contains("authentication")
          || lowerMessage.contains("api key")
          || lowerMessage.contains("invalid key")
          || lowerMessage.contains("401")) {
        return new MappedError(
            ErrorCategory.AUTH_ERROR, RetryStrategy.NO_RETRY, "Authentication failed: " + message);
      }

      // Rate limiting
      if (lowerMessage.contains("rate limit")
          || lowerMessage.contains("quota exceeded")
          || lowerMessage.contains("too many requests")
          || lowerMessage.contains("429")) {
        return new MappedError(
            ErrorCategory.RATE_LIMITED,
            RetryStrategy.EXPONENTIAL_BACKOFF,
            "Rate limited: " + message);
      }

      // Client errors (4xx)
      if (lowerMessage.contains("bad request")
          || lowerMessage.contains("invalid")
          || lowerMessage.contains("400")
          || lowerMessage.contains("404")
          || lowerMessage.contains("model not found")
          || lowerMessage.contains("unsupported")) {
        return new MappedError(
            ErrorCategory.CLIENT_ERROR, RetryStrategy.NO_RETRY, "Client error: " + message);
      }

      // Server errors (5xx)
      if (lowerMessage.contains("internal server error")
          || lowerMessage.contains("service unavailable")
          || lowerMessage.contains("502")
          || lowerMessage.contains("503")
          || lowerMessage.contains("500")) {
        return new MappedError(
            ErrorCategory.SERVER_ERROR,
            RetryStrategy.EXPONENTIAL_BACKOFF,
            "Server error: " + message);
      }

      // Network errors
      if (lowerMessage.contains("connection")
          || lowerMessage.contains("network")
          || lowerMessage.contains("host")
          || lowerMessage.contains("dns")) {
        return new MappedError(
            ErrorCategory.NETWORK_ERROR, RetryStrategy.FIXED_DELAY, "Network error: " + message);
      }

      // Model-specific errors
      if (lowerMessage.contains("model")
          && (lowerMessage.contains("not found")
              || lowerMessage.contains("unavailable")
              || lowerMessage.contains("deprecated"))) {
        return new MappedError(
            ErrorCategory.MODEL_ERROR, RetryStrategy.NO_RETRY, "Model error: " + message);
      }
    }

    // Analyze exception class name
    if (className.toLowerCase().contains("timeout")) {
      return new MappedError(
          ErrorCategory.TIMEOUT_ERROR,
          RetryStrategy.EXPONENTIAL_BACKOFF,
          "Timeout error: " + message);
    }

    if (className.toLowerCase().contains("network")
        || className.toLowerCase().contains("connection")) {
      return new MappedError(
          ErrorCategory.NETWORK_ERROR, RetryStrategy.FIXED_DELAY, "Network error: " + message);
    }

    // Default to unknown error with no retry
    return new MappedError(
        ErrorCategory.UNKNOWN_ERROR,
        RetryStrategy.NO_RETRY,
        "Unknown error: " + className + " - " + message);
  }

  /**
   * Determines if an error is retryable based on its category.
   *
   * @param category the error category
   * @return true if the error is potentially retryable
   */
  public static boolean isRetryable(ErrorCategory category) {
    switch (category) {
      case RATE_LIMITED:
      case NETWORK_ERROR:
      case TIMEOUT_ERROR:
      case SERVER_ERROR:
        return true;
      case AUTH_ERROR:
      case CLIENT_ERROR:
      case MODEL_ERROR:
      case UNKNOWN_ERROR:
      default:
        return false;
    }
  }

  /**
   * Gets the recommended delay before retrying based on the retry strategy.
   *
   * @param strategy the retry strategy
   * @param attempt the retry attempt number (0-based)
   * @return delay in milliseconds
   */
  public static long getRetryDelay(RetryStrategy strategy, int attempt) {
    switch (strategy) {
      case IMMEDIATE_RETRY:
        return 0;
      case FIXED_DELAY:
        return 1000; // 1 second
      case EXPONENTIAL_BACKOFF:
        return Math.min(1000 * (1L << attempt), 30000); // Max 30 seconds
      case NO_RETRY:
      default:
        return -1; // No retry
    }
  }

  /** Container for mapped error information. */
  public static class MappedError {
    private final ErrorCategory category;
    private final RetryStrategy retryStrategy;
    private final String normalizedMessage;

    public MappedError(
        ErrorCategory category, RetryStrategy retryStrategy, String normalizedMessage) {
      this.category = category;
      this.retryStrategy = retryStrategy;
      this.normalizedMessage = normalizedMessage;
    }

    public ErrorCategory getCategory() {
      return category;
    }

    public RetryStrategy getRetryStrategy() {
      return retryStrategy;
    }

    public String getNormalizedMessage() {
      return normalizedMessage;
    }

    public boolean isRetryable() {
      return SpringAIErrorMapper.isRetryable(category);
    }

    public long getRetryDelay(int attempt) {
      return SpringAIErrorMapper.getRetryDelay(retryStrategy, attempt);
    }

    @Override
    public String toString() {
      return "MappedError{"
          + "category="
          + category
          + ", retryStrategy="
          + retryStrategy
          + ", message='"
          + normalizedMessage
          + '\''
          + '}';
    }
  }
}
