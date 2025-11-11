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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.auto.value.AutoValue;
import io.reactivex.rxjava3.core.Single;

/** Interface for processing LLM requests. */
public interface RequestProcessor {

  /** Result of request processing. */
  @AutoValue
  public abstract static class RequestProcessingResult {
    /**
     * Updated LLM request.
     *
     * <p>This is the LLM request that will be used to generate the LLM response.
     */
    public abstract LlmRequest updatedRequest();

    /**
     * Events generated during processing.
     *
     * <p>These events are not necessarily part of the LLM request.
     */
    public abstract Iterable<Event> events();

    /** Creates a new {@link RequestProcessingResult}. */
    public static RequestProcessingResult create(
        LlmRequest updatedRequest, Iterable<Event> events) {
      return new AutoValue_RequestProcessor_RequestProcessingResult(updatedRequest, events);
    }
  }

  /**
   * Process the LLM request as part of the pre-processing stage.
   *
   * @param context the invocation context.
   * @param request the LLM request to process.
   * @return a list of events generated during processing (if any).
   */
  Single<RequestProcessingResult> processRequest(InvocationContext context, LlmRequest request);
}
