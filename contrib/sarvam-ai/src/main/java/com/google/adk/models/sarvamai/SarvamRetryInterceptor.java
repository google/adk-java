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

package com.google.adk.models.sarvamai;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OkHttp interceptor that implements exponential backoff with jitter for retryable Sarvam API
 * errors (429 rate limit, 5xx server errors).
 *
 * @author Sandeep Belgavi
 */
final class SarvamRetryInterceptor implements Interceptor {

  private static final Logger logger = LoggerFactory.getLogger(SarvamRetryInterceptor.class);
  private static final long BASE_DELAY_MS = 500;
  private static final long MAX_DELAY_MS = 30_000;

  private final int maxRetries;

  SarvamRetryInterceptor(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  @Override
  public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    IOException lastException = null;

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        Response response = chain.proceed(request);

        if (response.isSuccessful() || !isRetryable(response.code()) || attempt == maxRetries) {
          return response;
        }

        response.close();
        long delay = calculateDelay(attempt);
        logger.warn(
            "Sarvam API returned {} for {}. Retrying in {}ms (attempt {}/{})",
            response.code(),
            request.url(),
            delay,
            attempt + 1,
            maxRetries);

        sleep(delay);
      } catch (IOException e) {
        lastException = e;
        if (attempt == maxRetries) {
          break;
        }
        long delay = calculateDelay(attempt);
        logger.warn(
            "Sarvam API request failed: {}. Retrying in {}ms (attempt {}/{})",
            e.getMessage(),
            delay,
            attempt + 1,
            maxRetries);
        sleep(delay);
      }
    }

    throw lastException != null ? lastException : new IOException("Request failed after retries");
  }

  private static boolean isRetryable(int statusCode) {
    return statusCode == 429 || statusCode == 503 || statusCode >= 500;
  }

  static long calculateDelay(int attempt) {
    long delay = BASE_DELAY_MS * (1L << attempt);
    delay = Math.min(delay, MAX_DELAY_MS);
    long jitter = (long) (delay * 0.2 * Math.random());
    return delay + jitter;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
