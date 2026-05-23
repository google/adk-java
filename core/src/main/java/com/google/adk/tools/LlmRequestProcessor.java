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

package com.google.adk.tools;

import com.google.adk.models.LlmRequest;
import io.reactivex.rxjava3.core.Completable;

/**
 * Interface for components that can intercept and modify an outgoing {@link LlmRequest} before it
 * is sent to the LLM.
 *
 * <p>During flow execution, if a {@link BaseToolset} implements this, the toolset's {@link
 * #processLlmRequest} method will be executed, followed by executing all the tools' {@link
 * #processLlmRequest} method of tools returned by the toolset.
 */
public interface LlmRequestProcessor {

  /**
   * Intercepts and modifies the outgoing {@link LlmRequest} using the provided builder.
   *
   * <p>Implementations can mutate the {@code llmRequestBuilder} to alter the request that will be
   * sent to the LLM.
   *
   * @param llmRequestBuilder The builder for the outgoing {@link LlmRequest}.
   * @param toolContext The context in which the tool or toolset is being executed.
   * @return A {@link Completable} that completes when the request processing is done.
   */
  Completable processLlmRequest(LlmRequest.Builder llmRequestBuilder, ToolContext toolContext);
}
