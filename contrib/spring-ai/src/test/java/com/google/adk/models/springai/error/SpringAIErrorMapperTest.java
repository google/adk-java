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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class SpringAIErrorMapperTest {

  @Test
  void testTimeoutException() {
    Exception exception = new TimeoutException("Request timed out");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory())
        .isEqualTo(SpringAIErrorMapper.ErrorCategory.TIMEOUT_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF);
    assertThat(mappedError.isRetryable()).isTrue();
    assertThat(mappedError.getNormalizedMessage()).contains("Request timed out");
  }

  @Test
  void testSocketTimeoutException() {
    Exception exception = new SocketTimeoutException("Connection timed out");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory())
        .isEqualTo(SpringAIErrorMapper.ErrorCategory.TIMEOUT_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF);
    assertThat(mappedError.isRetryable()).isTrue();
  }

  @Test
  void testAuthenticationError() {
    Exception exception = new RuntimeException("Unauthorized: Invalid API key");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory()).isEqualTo(SpringAIErrorMapper.ErrorCategory.AUTH_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.NO_RETRY);
    assertThat(mappedError.isRetryable()).isFalse();
    assertThat(mappedError.getNormalizedMessage()).contains("Authentication failed");
  }

  @Test
  void testRateLimitError() {
    Exception exception = new RuntimeException("Rate limit exceeded. Try again later.");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory()).isEqualTo(SpringAIErrorMapper.ErrorCategory.RATE_LIMITED);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF);
    assertThat(mappedError.isRetryable()).isTrue();
    assertThat(mappedError.getNormalizedMessage()).contains("Rate limited");
  }

  @Test
  void testClientError() {
    Exception exception = new RuntimeException("Bad Request: Invalid model parameter");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory()).isEqualTo(SpringAIErrorMapper.ErrorCategory.CLIENT_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.NO_RETRY);
    assertThat(mappedError.isRetryable()).isFalse();
  }

  @Test
  void testServerError() {
    Exception exception = new RuntimeException("Internal Server Error (500)");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory()).isEqualTo(SpringAIErrorMapper.ErrorCategory.SERVER_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF);
    assertThat(mappedError.isRetryable()).isTrue();
  }

  @Test
  void testNetworkError() {
    Exception exception = new RuntimeException("Connection refused to host example.com");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory())
        .isEqualTo(SpringAIErrorMapper.ErrorCategory.NETWORK_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.FIXED_DELAY);
    assertThat(mappedError.isRetryable()).isTrue();
  }

  @Test
  void testModelError() {
    Exception exception = new RuntimeException("Model deprecated: gpt-3");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory()).isEqualTo(SpringAIErrorMapper.ErrorCategory.MODEL_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.NO_RETRY);
    assertThat(mappedError.isRetryable()).isFalse();
  }

  @Test
  void testUnknownError() {
    Exception exception = new RuntimeException("Some unknown error");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getCategory())
        .isEqualTo(SpringAIErrorMapper.ErrorCategory.UNKNOWN_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.NO_RETRY);
    assertThat(mappedError.isRetryable()).isFalse();
  }

  @Test
  void testNullException() {
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(null);

    assertThat(mappedError.getCategory())
        .isEqualTo(SpringAIErrorMapper.ErrorCategory.UNKNOWN_ERROR);
    assertThat(mappedError.getRetryStrategy())
        .isEqualTo(SpringAIErrorMapper.RetryStrategy.NO_RETRY);
    assertThat(mappedError.isRetryable()).isFalse();
  }

  @Test
  void testRetryDelayCalculation() {
    assertThat(
            SpringAIErrorMapper.getRetryDelay(SpringAIErrorMapper.RetryStrategy.IMMEDIATE_RETRY, 0))
        .isEqualTo(0);
    assertThat(SpringAIErrorMapper.getRetryDelay(SpringAIErrorMapper.RetryStrategy.FIXED_DELAY, 0))
        .isEqualTo(1000);
    assertThat(
            SpringAIErrorMapper.getRetryDelay(
                SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF, 0))
        .isEqualTo(1000);
    assertThat(
            SpringAIErrorMapper.getRetryDelay(
                SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF, 3))
        .isEqualTo(8000);
    assertThat(
            SpringAIErrorMapper.getRetryDelay(
                SpringAIErrorMapper.RetryStrategy.EXPONENTIAL_BACKOFF, 10))
        .isEqualTo(30000); // Max 30 seconds
    assertThat(SpringAIErrorMapper.getRetryDelay(SpringAIErrorMapper.RetryStrategy.NO_RETRY, 0))
        .isEqualTo(-1);
  }

  @Test
  void testErrorCategoryRetryability() {
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.RATE_LIMITED))
        .isTrue();
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.NETWORK_ERROR))
        .isTrue();
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.TIMEOUT_ERROR))
        .isTrue();
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.SERVER_ERROR))
        .isTrue();

    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.AUTH_ERROR))
        .isFalse();
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.CLIENT_ERROR))
        .isFalse();
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.MODEL_ERROR))
        .isFalse();
    assertThat(SpringAIErrorMapper.isRetryable(SpringAIErrorMapper.ErrorCategory.UNKNOWN_ERROR))
        .isFalse();
  }

  @Test
  void testMappedErrorMethods() {
    Exception exception = new RuntimeException("Rate limit exceeded");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(exception);

    assertThat(mappedError.getRetryDelay(1)).isEqualTo(2000);
    assertThat(mappedError.toString()).contains("RATE_LIMITED");
    assertThat(mappedError.toString()).contains("EXPONENTIAL_BACKOFF");
  }

  @Test
  void testClassNameBasedDetection() {
    // Test timeout detection based on class name
    class TimeoutTestException extends Exception {
      public TimeoutTestException(String message) {
        super(message);
      }

      @Override
      public String toString() {
        return "TimeoutException: " + getMessage();
      }
    }

    Exception timeoutException = new TimeoutTestException("Some timeout error");
    SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(timeoutException);

    // This should detect timeout based on the class name containing "Timeout"
    assertThat(mappedError.getCategory())
        .isEqualTo(SpringAIErrorMapper.ErrorCategory.TIMEOUT_ERROR);
  }
}
