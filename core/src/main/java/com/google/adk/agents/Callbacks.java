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

/**
 * A utility class that provides a collection of functional interfaces for defining various
 * callback operations within an agent-based system. These interfaces are designed to
 * allow for custom logic execution at different stages of an agent's lifecycle,
 * including before and after model invocations, agent actions, and tool executions.
 *
 * Each callback interface allows for synchronous or asynchronous operations, providing
 * hooks to inspect, modify, or interrupt the flow of execution.
 *
 * This class itself is not meant to be instantiated, hence the private constructor.
 */
public final class Callbacks {

  /**
   * Base interface for {@code BeforeModelCallback} operations.
   * This acts as a marker interface for callback types executed before an LLM is invoked.
   */
  interface BeforeModelCallbackBase {}

  @FunctionalInterface
  public interface BeforeModelCallback extends BeforeModelCallbackBase {
    /**
    * Performs an asynchronous callback operation before the Large Language Model (LLM) is invoked.
    * This method allows for interception or modification of the LLM request
    * before it is sent to the model, or to bypass the model invocation entirely by providing a response.
    *
    * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
    * @param llmRequest The request intended for the Large Language Model.
    * @return A {@link Maybe} emitting an {@link LlmResponse} if the callback generates one (bypassing the model),
    * or completing without emitting if the model invocation should proceed.
    */
    Maybe<LlmResponse> call(CallbackContext callbackContext, LlmRequest llmRequest);
  }

  /**
   * Helper interface to allow for synchronous {@code beforeModelCallback} operations.
   * The function provided by this interface is typically wrapped into an asynchronous
   * one before being processed further by the system.
   */
  @FunctionalInterface
  public interface BeforeModelCallbackSync extends BeforeModelCallbackBase {
    /**
     * Performs a synchronous callback operation before the model is invoked.
     *
     * @param callbackContext The context for the callback, providing access to agent state.
     * @param llmRequest The request intended for the Large Language Model.
     * @return An Optional containing the LLM response if available, or empty if no response is generated.
     */
    Optional<LlmResponse> call(CallbackContext callbackContext, LlmRequest llmRequest);
  }

  /**
   * Base interface for {@code AfterModelCallback} operations.
   * This acts as a marker interface for callback types executed after an LLM has responded.
   */
  interface AfterModelCallbackBase {}

  @FunctionalInterface
  public interface AfterModelCallback extends AfterModelCallbackBase {
    /**
      * Performs an asynchronous callback operation after the Large Language Model (LLM) has responded.
      * This method allows for processing or modification of the LLM's response, or for generating
      * an alternative response that should be propagated further.
      *
      * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
      * @param llmResponse The response received from the Large Language Model.
      * @return A {@link Maybe} emitting an {@link LlmResponse} if the callback generates one,
      * or completing without emitting if the original LLM response should be propagated.
      */
    Maybe<LlmResponse> call(CallbackContext callbackContext, LlmResponse llmResponse);
  }

  /**
   * Helper interface to allow for synchronous {@code afterModelCallback} operations.
   * This interface provides a hook that is executed synchronously after a model has been invoked
   * and responded. The function provided by this interface is typically wrapped into an asynchronous
   * one before being processed further by the system, ensuring non-blocking behavior downstream.
   */
  @FunctionalInterface
  public interface AfterModelCallbackSync extends AfterModelCallbackBase {
    /**
     * Performs a synchronous callback operation after the Large Language Model (LLM) has responded.
     * This method is intended to process the LLM's response or perform post-processing tasks synchronously.
     *
     * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
     * @param llmResponse The response received from the Large Language Model.
     * @return An {@link Optional} containing a processed {@link LlmResponse} if the callback generates one,
     * or an empty Optional if no new response is generated or if the callback's output is not intended to be propagated.
     */
    Optional<LlmResponse> call(CallbackContext callbackContext, LlmResponse llmResponse);
  }

  /**
   * Base interface for {@code BeforeAgentCallback} operations.
   * This acts as a marker interface for callback types executed before an agent performs its primary action.
   */
  interface BeforeAgentCallbackBase {}

  @FunctionalInterface
  public interface BeforeAgentCallback extends BeforeAgentCallbackBase {
    /**
      * Performs an asynchronous callback operation before an agent's main execution.
      * This method allows for processing the agent's context, modifying its state,
      * or providing alternative content that should be propagated before the agent's primary action.
      *
      * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
      * @return A {@link Maybe} emitting {@link Content} if the callback generates content that
      * should be propagated (bypassing the agent's primary action), or completing without emitting
      * if the agent's primary action should proceed.
      */
    Maybe<Content> call(CallbackContext callbackContext);
  }

  /**
   * Helper interface to allow for synchronous {@code beforeAgentCallback} operations.
   * This interface provides a hook that is executed synchronously before an agent performs its primary action.
   * The function provided by this interface is typically wrapped into an asynchronous
   * one before being processed further by the system, ensuring non-blocking behavior downstream.
   */
  @FunctionalInterface
  public interface BeforeAgentCallbackSync extends BeforeAgentCallbackBase {
    /**
     * Performs a synchronous callback operation before an agent's main execution.
     * This method is intended to process the agent's context or modify its state synchronously.
     *
     * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
     * @return An {@link Optional} containing {@link Content} if the callback generates content that
     * should be propagated, or an empty Optional if no content is generated or propagated.
     */
    Optional<Content> call(CallbackContext callbackContext);
  }

  /**
   * Base interface for {@code AfterAgentCallback} operations.
   * This acts as a marker interface for callback types executed after an agent has performed its primary action.
   */
  interface AfterAgentCallbackBase {}

  @FunctionalInterface
  public interface AfterAgentCallback extends AfterAgentCallbackBase {
    /**
      * Performs an asynchronous callback operation after an agent's main execution.
      * This method allows for processing the agent's output, performing post-processing tasks,
      * or providing alternative content that should be propagated after the agent's primary action.
      *
      * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
      * @return A {@link Maybe} emitting {@link Content} if the callback generates content that
      * should be propagated (overriding or supplementing the agent's original output), or completing without emitting
      * if the agent's original output should be used directly.
      */
    Maybe<Content> call(CallbackContext callbackContext);
  }

  /**
   * Helper interface to allow for synchronous {@code afterAgentCallback} operations.
   * This interface provides a hook that is executed synchronously after an agent has performed its primary action.
   * The function provided by this interface is typically wrapped into an asynchronous
   * one before being processed further by the system, ensuring non-blocking behavior downstream.
   */
  @FunctionalInterface
  public interface AfterAgentCallbackSync extends AfterAgentCallbackBase {
    /**
     * Performs a synchronous callback operation after an agent's main execution.
     * This method is intended to process the agent's output or perform post-processing tasks synchronously.
     *
     * @param callbackContext The context for the callback, providing access to agent state and other relevant information.
     * @return An {@link Optional} containing {@link Content} if the callback generates content that
     * should be propagated, or an empty Optional if no content is generated or propagated.
     */
    Optional<Content> call(CallbackContext callbackContext);
  }

  /**
   * Base interface for {@code BeforeToolCallback} operations.
   * This acts as a marker interface for callback types executed before a tool is invoked.
   */
  interface BeforeToolCallbackBase {}

  @FunctionalInterface
  public interface BeforeToolCallback extends BeforeToolCallbackBase {
    /**
      * Performs an asynchronous callback operation before a tool is executed.
      * This method allows for interception or modification of the tool's input,
      * or for bypassing the tool's invocation entirely by providing an alternative output.
      *
      * @param invocationContext The context of the tool invocation, providing details about the current call.
      * @param baseTool The {@link BaseTool} instance that is about to be invoked.
      * @param input A {@link Map} representing the input arguments for the tool's execution.
      * @param toolContext The context specific to the tool's environment.
      * @return A {@link Maybe} emitting a {@link Map} of results if the callback generates an
      * alternative output that should override the tool's invocation, or completing without emitting
      * to proceed with the original tool invocation.
      */
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext);
  }

  /**
   * Helper interface to allow for synchronous {@code beforeToolCallback} operations.
   * This interface provides a hook that is executed synchronously before a tool is invoked.
   * The function provided by this interface is typically wrapped into an asynchronous
   * one before being processed further by the system, ensuring non-blocking behavior downstream.
   */
  @FunctionalInterface
  public interface BeforeToolCallbackSync extends BeforeToolCallbackBase {
    /**
     * Performs a synchronous callback operation before a tool is executed.
     * This method can be used to modify tool inputs, validate context, or perform
     * pre-invocation logging synchronously.
     *
     * @param invocationContext The context of the tool invocation, providing details about the current call.
     * @param baseTool The {@link BaseTool} instance that is about to be invoked.
     * @param input A {@link Map} representing the input arguments for the tool's execution.
     * @param toolContext The context specific to the tool's environment.
     * @return An {@link Optional} containing a {@link Map} of results if the callback generates an
     * alternative output that should override the tool's invocation, or an empty Optional
     * to proceed with the original tool invocation.
     */
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext);
  }

  /**
   * Base interface for {@code AfterToolCallback} operations.
   * This acts as a marker interface for callback types executed after a tool has completed its execution.
   */
  interface AfterToolCallbackBase {}

  @FunctionalInterface
  public interface AfterToolCallback extends AfterToolCallbackBase {
    /**
      * Performs an asynchronous callback operation after a tool has completed its execution.
      * This method allows for processing the tool's response, handling errors, or performing
      * post-invocation tasks asynchronously. It can also provide a processed output that
      * should override or supplement the tool's original response.
      *
      * @param invocationContext The context of the tool invocation, providing details about the current call.
      * @param baseTool The {@link BaseTool} instance that was invoked.
      * @param input A {@link Map} representing the input arguments originally passed to the tool's execution.
      * @param toolContext The context specific to the tool's environment.
      * @param response The raw {@link Object} response returned by the tool.
      * @return A {@link Maybe} emitting a {@link Map} of results if the callback generates a
      * processed output that should override or supplement the tool's original response,
      * or completing without emitting if the tool's original response should be used directly.
      */
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Object response);
  }

  /**
   * Helper interface to allow for synchronous {@code afterToolCallback} operations.
   * This interface provides a hook that is executed synchronously after a tool has been invoked
   * and returned a response. The function provided by this interface is typically wrapped into an asynchronous
   * one before being processed further by the system, ensuring non-blocking behavior downstream.
   */
  @FunctionalInterface
  public interface AfterToolCallbackSync extends AfterToolCallbackBase {
    /**
     * Performs a synchronous callback operation after a tool has completed its execution.
     * This method can be used to process the tool's response, handle errors, or perform
     * post-invocation logging synchronously.
     *
     * @param invocationContext The context of the tool invocation, providing details about the current call.
     * @param baseTool The {@link BaseTool} instance that was invoked.
     * @param input A {@link Map} representing the input arguments originally passed to the tool's execution.
     * @param toolContext The context specific to the tool's environment.
     * @param response The raw {@link Object} response returned by the tool.
     * @return An {@link Optional} containing a {@link Map} of results if the callback generates a
     * processed output that should override or supplement the tool's original response,
     * or an empty Optional if the tool's original response should be used directly.
     */
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Object response);
  }

  /**
   * Private constructor to prevent instantiation of this utility class.
   * This class provides only static functional interfaces and should not be instantiated.
   */
  private Callbacks() {}
}
