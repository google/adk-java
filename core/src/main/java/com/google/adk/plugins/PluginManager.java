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
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the registration and execution of plugins.
 *
 * <p>The PluginManager is an internal class that orchestrates the invocation of plugin callbacks at
 * key points in the SDK's execution lifecycle.
 */
public class PluginManager extends BasePlugin {
  private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
  private final List<Plugin> plugins = new ArrayList<>();

  public PluginManager(List<? extends Plugin> plugins) {
    super("PluginManager");
    if (plugins != null) {
      plugins.forEach(this::registerPlugin);
    }
  }

  public PluginManager() {
    this(null);
  }

  /**
   * Registers a new plugin.
   *
   * @param plugin The plugin instance to register.
   * @throws IllegalArgumentException If a plugin with the same name is already registered.
   */
  public void registerPlugin(Plugin plugin) {
    if (plugins.stream().anyMatch(p -> p.getName().equals(plugin.getName()))) {
      throw new IllegalArgumentException(
          "Plugin with name '" + plugin.getName() + "' already registered.");
    }
    plugins.add(plugin);
    logger.trace("Plugin '{}' registered.", plugin.getName());
  }

  /**
   * Retrieves a registered plugin by its name.
   *
   * @param pluginName The name of the plugin to retrieve.
   * @return The plugin instance if found, otherwise {@link Optional#empty()}.
   */
  public Optional<Plugin> getPlugin(String pluginName) {
    return plugins.stream().filter(p -> p.getName().equals(pluginName)).findFirst();
  }

  // --- Callback Runners ---

  public Maybe<Content> runOnUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return onUserMessageCallback(invocationContext, userMessage);
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return runMaybeCallbacks(
        plugin -> plugin.onUserMessageCallback(invocationContext, userMessage),
        "onUserMessageCallback");
  }

  public Maybe<Content> runBeforeRunCallback(InvocationContext invocationContext) {
    return beforeRunCallback(invocationContext);
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    return runMaybeCallbacks(
        plugin -> plugin.beforeRunCallback(invocationContext), "beforeRunCallback");
  }

  public Completable runAfterRunCallback(InvocationContext invocationContext) {
    return afterRunCallback(invocationContext);
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Flowable.fromIterable(plugins)
        .concatMapCompletable(
            plugin ->
                plugin
                    .afterRunCallback(invocationContext)
                    .doOnError(
                        e ->
                            logger.error(
                                "[{}] Error during callback 'afterRunCallback'",
                                plugin.getName(),
                                e)));
  }

  @Override
  public Completable close() {
    return Flowable.fromIterable(plugins)
        .concatMapCompletableDelayError(
            plugin ->
                plugin
                    .close()
                    .doOnError(
                        e ->
                            logger.error(
                                "[{}] Error during callback 'close'", plugin.getName(), e)));
  }

  public Maybe<Event> runOnEventCallback(InvocationContext invocationContext, Event event) {
    return onEventCallback(invocationContext, event);
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    return runMaybeCallbacks(
        plugin -> plugin.onEventCallback(invocationContext, event), "onEventCallback");
  }

  public Maybe<Content> runBeforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return beforeAgentCallback(agent, callbackContext);
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return runMaybeCallbacks(
        plugin -> plugin.beforeAgentCallback(agent, callbackContext), "beforeAgentCallback");
  }

  public Maybe<Content> runAfterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return afterAgentCallback(agent, callbackContext);
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return runMaybeCallbacks(
        plugin -> plugin.afterAgentCallback(agent, callbackContext), "afterAgentCallback");
  }

  public Maybe<LlmResponse> runBeforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return beforeModelCallback(callbackContext, llmRequest);
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return runMaybeCallbacks(
        plugin -> plugin.beforeModelCallback(callbackContext, llmRequest), "beforeModelCallback");
  }

  public Maybe<LlmResponse> runAfterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return afterModelCallback(callbackContext, llmResponse);
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return runMaybeCallbacks(
        plugin -> plugin.afterModelCallback(callbackContext, llmResponse), "afterModelCallback");
  }

  public Maybe<LlmResponse> runOnModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    return onModelErrorCallback(callbackContext, llmRequest, error);
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    return runMaybeCallbacks(
        plugin -> plugin.onModelErrorCallback(callbackContext, llmRequest, error),
        "onModelErrorCallback");
  }

  public Maybe<Map<String, Object>> runBeforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return beforeToolCallback(tool, toolArgs, toolContext);
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return runMaybeCallbacks(
        plugin -> plugin.beforeToolCallback(tool, toolArgs, toolContext), "beforeToolCallback");
  }

  public Maybe<Map<String, Object>> runAfterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return afterToolCallback(tool, toolArgs, toolContext, result);
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return runMaybeCallbacks(
        plugin -> plugin.afterToolCallback(tool, toolArgs, toolContext, result),
        "afterToolCallback");
  }

  public Maybe<Map<String, Object>> runOnToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return onToolErrorCallback(tool, toolArgs, toolContext, error);
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return runMaybeCallbacks(
        plugin -> plugin.onToolErrorCallback(tool, toolArgs, toolContext, error),
        "onToolErrorCallback");
  }

  /**
   * Executes a specific Maybe-returning callback for all registered plugins with early exit.
   *
   * @param callbackExecutor Function to execute the callback on a single plugin.
   * @param callbackName Name of the callback for logging.
   * @return Maybe with the first non-empty result from a plugin, or Empty if all return Empty.
   */
  private <T> Maybe<T> runMaybeCallbacks(
      Function<Plugin, Maybe<T>> callbackExecutor, String callbackName) {

    return Flowable.fromIterable(this.plugins)
        .concatMapMaybe(
            plugin ->
                callbackExecutor
                    .apply(plugin)
                    .doOnSuccess(
                        unused ->
                            logger.debug(
                                "Plugin '{}' returned a value for callback '{}', exiting "
                                    + "early.",
                                plugin.getName(),
                                callbackName))
                    .doOnError(
                        e ->
                            logger.error(
                                "[{}] Error during callback '{}'",
                                plugin.getName(),
                                callbackName,
                                e)))
        .firstElement();
  }
}
