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

package com.google.adk.agents;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Optional;

/** Functional interfaces for agent lifecycle callbacks. */
public final class Callbacks {

  interface BeforeModelCallbackBase {}

  @FunctionalInterface
  public interface BeforeModelCallback extends BeforeModelCallbackBase {
    /**
     * Async callback before LLM invocation.
     *
     * @param callbackContext Callback context.
     * @param llmRequestBuilder LLM request builder.
     * @return response override, or empty to continue.
     */
    Maybe<LlmResponse> call(CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder);
  }

  /**
   * Helper interface to allow for sync beforeModelCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface BeforeModelCallbackSync extends BeforeModelCallbackBase {
    Optional<LlmResponse> call(
        CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder);
  }

  interface AfterModelCallbackBase {}

  @FunctionalInterface
  public interface AfterModelCallback extends AfterModelCallbackBase {
    /**
     * Async callback after LLM response.
     *
     * @param callbackContext Callback context.
     * @param llmResponse LLM response.
     * @return modified response, or empty to keep original.
     */
    Maybe<LlmResponse> call(CallbackContext callbackContext, LlmResponse llmResponse);
  }

  /**
   * Helper interface to allow for sync afterModelCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface AfterModelCallbackSync extends AfterModelCallbackBase {
    Optional<LlmResponse> call(CallbackContext callbackContext, LlmResponse llmResponse);
  }

  interface OnModelErrorCallbackBase {}

  /** Async callback interface for handling errors that occur during an LLM model call. */
  @FunctionalInterface
  public interface OnModelErrorCallback extends OnModelErrorCallbackBase {
    /**
     * Async callback when model call fails.
     *
     * @param callbackContext Callback context.
     * @param llmRequest LLM request.
     * @param error The exception that occurred.
     * @return response override, or empty to continue with error.
     */
    Maybe<LlmResponse> call(
        CallbackContext callbackContext, LlmRequest llmRequest, Exception error);
  }

  /**
   * Helper interface to allow for sync onModelErrorCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface OnModelErrorCallbackSync extends OnModelErrorCallbackBase {
    Optional<LlmResponse> call(
        CallbackContext callbackContext, LlmRequest llmRequest, Exception error);
  }

  interface BeforeAgentCallbackBase {}

  /** Async callback interface for actions to be performed before an agent starts running. */
  @FunctionalInterface
  public interface BeforeAgentCallback extends BeforeAgentCallbackBase {
    /**
     * Async callback before agent runs.
     *
     * @param callbackContext Callback context.
     * @return content override, or empty to continue.
     */
    Maybe<Content> call(CallbackContext callbackContext);
  }

  /**
   * Helper interface to allow for sync beforeAgentCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface BeforeAgentCallbackSync extends BeforeAgentCallbackBase {
    Optional<Content> call(CallbackContext callbackContext);
  }

  interface AfterAgentCallbackBase {}

  /** Async callback interface for actions to be performed after an agent has finished running. */
  @FunctionalInterface
  public interface AfterAgentCallback extends AfterAgentCallbackBase {
    /**
     * Async callback after agent runs.
     *
     * @param callbackContext Callback context.
     * @return modified content, or empty to keep original.
     */
    Maybe<Content> call(CallbackContext callbackContext);
  }

  /**
   * Helper interface to allow for sync afterAgentCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface AfterAgentCallbackSync extends AfterAgentCallbackBase {
    Optional<Content> call(CallbackContext callbackContext);
  }

  interface BeforeToolCallbackBase {}

  /** Async callback interface for actions to be performed before a tool is invoked. */
  @FunctionalInterface
  public interface BeforeToolCallback extends BeforeToolCallbackBase {
    /**
     * Async callback before tool runs.
     *
     * @param invocationContext Invocation context.
     * @param baseTool Tool instance.
     * @param input Tool input arguments.
     * @param toolContext Tool context.
     * @return override result, or empty to continue.
     */
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext);
  }

  /**
   * Helper interface to allow for sync beforeToolCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface BeforeToolCallbackSync extends BeforeToolCallbackBase {
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext);
  }

  interface AfterToolCallbackBase {}

  /** Async callback interface for actions to be performed after a tool has been invoked. */
  @FunctionalInterface
  public interface AfterToolCallback extends AfterToolCallbackBase {
    /**
     * Async callback after tool runs.
     *
     * @param invocationContext Invocation context.
     * @param baseTool Tool instance.
     * @param input Tool input arguments.
     * @param toolContext Tool context.
     * @param response Raw tool response.
     * @return processed result, or empty to keep original.
     */
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Object response);
  }

  /**
   * Helper interface to allow for sync afterToolCallback. The function is wrapped into an async one
   * before being processed further.
   */
  @FunctionalInterface
  public interface AfterToolCallbackSync extends AfterToolCallbackBase {
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Object response);
  }

  interface OnToolErrorCallbackBase {}

  /** Async callback interface for handling errors that occur during a tool invocation. */
  @FunctionalInterface
  public interface OnToolErrorCallback extends OnToolErrorCallbackBase {
    /**
     * Async callback when tool call fails.
     *
     * @param invocationContext Invocation context.
     * @param baseTool Tool instance.
     * @param input Tool input arguments.
     * @param toolContext Tool context.
     * @param error The exception that occurred.
     * @return override result, or empty to continue with error.
     */
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Exception error);
  }

  /**
   * Helper interface to allow for sync onToolErrorCallback. The function is wrapped into an async
   * one before being processed further.
   */
  @FunctionalInterface
  public interface OnToolErrorCallbackSync extends OnToolErrorCallbackBase {
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Exception error);
  }

  private Callbacks() {}
}
