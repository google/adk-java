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

package com.google.adk.reasoning;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

/**
 * Base contract for reasoning bank services.
 *
 * <p>The service provides functionalities to store and retrieve reasoning strategies that can be
 * used to augment LLM prompts with relevant problem-solving approaches.
 *
 * <p>Based on the ReasoningBank paper (arXiv:2509.25140).
 */
public interface BaseReasoningBankService {

  /**
   * Stores a reasoning strategy in the bank.
   *
   * @param appName The name of the application.
   * @param strategy The strategy to store.
   * @return A Completable that completes when the strategy is stored.
   */
  Completable storeStrategy(String appName, ReasoningStrategy strategy);

  /**
   * Stores a reasoning trace for later distillation into strategies.
   *
   * @param appName The name of the application.
   * @param trace The trace to store.
   * @return A Completable that completes when the trace is stored.
   */
  Completable storeTrace(String appName, ReasoningTrace trace);

  /**
   * Searches for reasoning strategies that match the given query.
   *
   * @param appName The name of the application.
   * @param query The query to search for (typically a task description).
   * @return A {@link SearchReasoningResponse} containing matching strategies.
   */
  Single<SearchReasoningResponse> searchStrategies(String appName, String query);

  /**
   * Searches for reasoning strategies that match the given query with a limit.
   *
   * @param appName The name of the application.
   * @param query The query to search for.
   * @param maxResults Maximum number of strategies to return.
   * @return A {@link SearchReasoningResponse} containing matching strategies.
   */
  Single<SearchReasoningResponse> searchStrategies(String appName, String query, int maxResults);
}
