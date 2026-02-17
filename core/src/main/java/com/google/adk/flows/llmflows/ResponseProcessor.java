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
import com.google.adk.models.LlmResponse;
import com.google.auto.value.AutoValue;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

/** Interface for processing LLM responses. */
public interface ResponseProcessor {

  /** Result of response processing. */
  @AutoValue
  public abstract static class ResponseProcessingResult {
    /**
     * Updated LLM response.
     *
     * <p>This is the LLM response that will be returned to the client.
     */
    public abstract LlmResponse updatedResponse();

    /**
     * Events generated during processing.
     *
     * <p>These events are not necessarily part of the LLM response.
     */
    public abstract Iterable<Event> events();

    /**
     * The agent to transfer to.
     *
     * <p>If present, the invocation will be transferred to the specified agent.
     */
    public abstract Optional<String> transferToAgent();

    /** Creates a new {@link ResponseProcessingResult}. */
    public static ResponseProcessingResult create(
        LlmResponse updatedResponse, Iterable<Event> events, Optional<String> transferToAgent) {
      return new AutoValue_ResponseProcessor_ResponseProcessingResult(
          updatedResponse, events, transferToAgent);
    }
  }

  /**
   * Process the LLM response as part of the post-processing stage.
   *
   * @param context the invocation context.
   * @param response the LLM response to process.
   * @return a list of events generated during processing (if any).
   */
  Single<ResponseProcessingResult> processResponse(InvocationContext context, LlmResponse response);
}
