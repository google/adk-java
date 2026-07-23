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

package com.google.adk.models.chat;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;

/**
 * A client for interacting with OpenAI-compatible chat completions endpoints.
 *
 * <p>Supports both non-streaming responses (single {@link LlmResponse} emission) and streaming
 * Server-Sent Events (SSE) responses (multiple incremental {@link LlmResponse} emissions). See the
 * <a href="https://developers.openai.com/api/reference/resources/chat">OpenAI Chat Completions API
 * reference</a> for the wire protocol.
 */
public interface ChatCompletionsClient {

  /**
   * Generates a conversational response from the chat completions endpoint based on the provided
   * messages. This encapsulates building the payload, sending the request to the completions
   * endpoint, and initiating the handling of complete calls.
   *
   * @param llmRequest The request containing the model, configuration, and sequence of messages.
   * @param stream Whether to request a streaming response.
   * @return A {@link Flowable} emitting the discrete (or combined) {@link LlmResponse} objects.
   */
  Flowable<LlmResponse> complete(LlmRequest llmRequest, boolean stream);
}
