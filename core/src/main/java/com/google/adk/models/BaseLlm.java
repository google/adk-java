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

package com.google.adk.models;

import com.google.genai.errors.GenAiIOException;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base class for Large Language Models (LLMs).
 *
 * <p>Provides a common interface for interacting with different LLMs.
 */
public abstract class BaseLlm {

  /** The name of the LLM model, e.g. gemini-1.5-flash or gemini-1.5-flash-001. */
  private final String model;

  public BaseLlm(String model) {
    this.model = model;
  }

  /**
   * Returns the name of the LLM model.
   *
   * @return The name of the LLM model.
   */
  public String model() {
    return model;
  }

  /**
   * Generates one content from the given LLM request and tools.
   *
   * @param llmRequest The LLM request containing the input prompt and parameters.
   * @param stream A boolean flag indicating whether to stream the response.
   * @return A Flowable of LlmResponses. For non-streaming calls, it will only yield one
   *     LlmResponse. For streaming calls, it may yield more than one LlmResponse, but all yielded
   *     LlmResponses should be treated as one content by merging their parts.
   */
  public abstract Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream);

  /**
   * Returns whether a provider failure is safe to retry.
   *
   * <p>Implementations may override this method for provider-specific exception types. The default
   * implementation recognizes common network failures and exceptions exposing an HTTP status code
   * through {@code code()}, {@code statusCode()}, or {@code getStatusCode()}.
   */
  public boolean isExceptionRetryable(Throwable exception, Set<Integer> retryableStatusCodes) {
    if (exception instanceof IOException
        || exception instanceof TimeoutException
        || exception instanceof GenAiIOException) {
      return true;
    }

    Integer statusCode = extractStatusCode(exception);
    return statusCode != null && retryableStatusCodes.contains(statusCode);
  }

  private static Integer extractStatusCode(Throwable exception) {
    for (String methodName : new String[] {"code", "statusCode", "getStatusCode"}) {
      try {
        Method method = exception.getClass().getMethod(methodName);
        Object value = method.invoke(exception);
        if (value instanceof Number number) {
          return number.intValue();
        }
      } catch (ReflectiveOperationException | SecurityException ignored) {
        // Try the next provider convention.
      }
    }
    return null;
  }

  /** Creates a live connection to the LLM. */
  public abstract BaseLlmConnection connect(LlmRequest llmRequest);
}
