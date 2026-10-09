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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RetryConfig;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmCallsLimitExceededException;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.opentelemetry.api.trace.Span;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes retries around a single LLM provider call without replaying agent-side effects. */
final class LlmRetryPolicy {
  private static final Logger logger = LoggerFactory.getLogger(LlmRetryPolicy.class);

  private LlmRetryPolicy() {}

  static Flowable<LlmResponse> execute(
      InvocationContext context, BaseLlm llm, LlmRequest request, boolean stream) {
    return executeAttempt(context, llm, request, stream, /* attempt= */ 1);
  }

  private static Flowable<LlmResponse> executeAttempt(
      InvocationContext context, BaseLlm llm, LlmRequest request, boolean stream, int attempt) {
    return Flowable.defer(
        () -> {
          AtomicBoolean emittedResponse = new AtomicBoolean(false);
          return llm.generateContent(request, stream)
              .doOnNext(unused -> emittedResponse.set(true))
              .onErrorResumeNext(
                  error -> {
                    RetryConfig config = context.runConfig().retryConfig();
                    if (emittedResponse.get()
                        || attempt >= config.maxAttempts()
                        || !isRetryable(llm, error, config)) {
                      return Flowable.error(error);
                    }

                    long delayMillis = retryDelay(config, attempt);
                    int nextAttempt = attempt + 1;
                    recordRetry(llm, context, error, nextAttempt, delayMillis);
                    return Flowable.timer(delayMillis, TimeUnit.MILLISECONDS)
                        .flatMap(
                            unused -> {
                              try {
                                context.incrementLlmCallsCount();
                              } catch (LlmCallsLimitExceededException e) {
                                return Flowable.error(e);
                              }
                              return executeAttempt(context, llm, request, stream, nextAttempt);
                            });
                  });
        });
  }

  private static boolean isRetryable(BaseLlm llm, Throwable error, RetryConfig config) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = error;
        current != null && visited.add(current);
        current = current.getCause()) {
      if (current instanceof LlmCallsLimitExceededException) {
        return false;
      }
      if (llm.isExceptionRetryable(current, config.retryableStatusCodes())) {
        return true;
      }
    }
    return false;
  }

  private static long retryDelay(RetryConfig config, int attempt) {
    Duration initial = config.initialBackoff();
    Duration maximum = config.maxBackoff();
    double exponential = initial.toMillis() * Math.pow(config.multiplier(), attempt - 1);
    double bounded = Math.min(exponential, maximum.toMillis());
    if (config.jitterRatio() > 0.0 && bounded > 0.0) {
      double jitter =
          ThreadLocalRandom.current().nextDouble(-config.jitterRatio(), config.jitterRatio());
      bounded *= 1.0 + jitter;
    }
    return Math.max(0L, Math.round(Math.min(bounded, maximum.toMillis())));
  }

  private static void recordRetry(
      BaseLlm llm, InvocationContext context, Throwable error, int nextAttempt, long delayMillis) {
    logger.warn(
        "Retrying LLM call for model {} and agent {} (attempt {}, delay {} ms) after {}",
        llm.model(),
        context.agent().name(),
        nextAttempt,
        delayMillis,
        error.getClass().getName());

    Span span = Span.current();
    span.setAttribute("adk.llm.retry.model", llm.model());
    span.setAttribute("adk.llm.retry.agent", context.agent().name());
    span.setAttribute("adk.llm.retry.attempt", nextAttempt);
    span.setAttribute("adk.llm.retry.delay_ms", delayMillis);
    span.setAttribute("adk.llm.retry.error_type", error.getClass().getName());
  }
}
