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
package com.google.adk.plugins;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;

/**
 * Base class for creating plugins.
 *
 * <p>Plugins provide a structured way to intercept and modify agent, tool, and LLM behaviors at
 * critical execution points in a callback manner. While agent callbacks apply to a particular
 * agent, plugins applies globally to all agents added in the runner. Plugins are best used for
 * adding custom behaviors like logging, monitoring, caching, or modifying requests and responses at
 * key stages.
 *
 * <p>A plugin can implement one or more methods of callbacks, but should not implement the same
 * method of callback for multiple times.
 */
public abstract class BasePlugin {
  protected final String name;

  public BasePlugin(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  /**
   * Callback executed when a user message is received before an invocation starts.
   *
   * @param invocationContext The context for the entire invocation.
   * @param userMessage The message content input by user.
   * @return An optional Content to replace the user message. Returning Empty to proceed normally.
   */
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return Maybe.empty();
  }

  /**
   * Callback executed before the ADK runner runs.
   *
   * @param invocationContext The context for the entire invocation.
   * @return An optional Content to halt execution. Returning Empty to proceed normally.
   */
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    return Maybe.empty();
  }

  /**
   * Callback executed after an event is yielded from runner.
   *
   * @param invocationContext The context for the entire invocation.
   * @param event The event raised by the runner.
   * @return An optional Event to modify or replace the response. Returning Empty to proceed
   *     normally.
   */
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    return Maybe.empty();
  }

  /**
   * Callback executed after an ADK runner run has completed.
   *
   * @param invocationContext The context for the entire invocation.
   */
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.complete();
  }

  /**
   * Callback executed before an agent's primary logic is invoked.
   *
   * @param agent The agent that is about to run.
   * @param callbackContext The context for the agent invocation.
   * @return An optional Content object to bypass the agent's execution. Returning Empty to proceed
   *     normally.
   */
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.empty();
  }

  /**
   * Callback executed after an agent's primary logic has completed.
   *
   * @param agent The agent that has just run.
   * @param callbackContext The context for the agent invocation.
   * @return An optional Content object to replace the agent's original result. Returning Empty to
   *     use the original result.
   */
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.empty();
  }

  /**
   * Callback executed before a request is sent to the model.
   *
   * @param callbackContext The context for the current agent call.
   * @param llmRequest The prepared request object to be sent to the model.
   * @return An optional LlmResponse to trigger an early exit. Returning Empty to proceed normally.
   */
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest llmRequest) {
    return Maybe.empty();
  }

  /**
   * Callback executed after a response is received from the model.
   *
   * @param callbackContext The context for the current agent call.
   * @param llmResponse The response object received from the model.
   * @return An optional LlmResponse to modify or replace the response. Returning Empty to use the
   *     original response.
   */
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return Maybe.empty();
  }

  /**
   * Callback executed when a model call encounters an error.
   *
   * @param callbackContext The context for the current agent call.
   * @param llmRequest The request that was sent to the model.
   * @param error The exception that was raised.
   * @return An optional LlmResponse to use instead of propagating the error. Returning Empty to
   *     allow the original error to be raised.
   */
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest llmRequest, Throwable error) {
    return Maybe.empty();
  }

  /**
   * Callback executed before a tool is called.
   *
   * @param tool The tool instance that is about to be executed.
   * @param toolArgs The dictionary of arguments to be used for invoking the tool.
   * @param toolContext The context specific to the tool execution.
   * @return An optional Map to stop the tool execution and return this response immediately.
   *     Returning Empty to proceed normally.
   */
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return Maybe.empty();
  }

  /**
   * Callback executed after a tool has been called.
   *
   * @param tool The tool instance that has just been executed.
   * @param toolArgs The original arguments that were passed to the tool.
   * @param toolContext The context specific to the tool execution.
   * @param result The dictionary returned by the tool invocation.
   * @return An optional Map to replace the original result from the tool. Returning Empty to use
   *     the original result.
   */
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return Maybe.empty();
  }

  /**
   * Callback executed when a tool call encounters an error.
   *
   * @param tool The tool instance that encountered an error.
   * @param toolArgs The arguments that were passed to the tool.
   * @param toolContext The context specific to the tool execution.
   * @param error The exception that was raised during tool execution.
   * @return An optional Map to be used as the tool response instead of propagating the error.
   *     Returning Empty to allow the original error to be raised.
   */
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return Maybe.empty();
  }
}
