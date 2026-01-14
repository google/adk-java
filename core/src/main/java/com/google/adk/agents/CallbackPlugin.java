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
package com.google.adk.agents;

import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.AfterAgentCallbackBase;
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.AfterModelCallbackBase;
import com.google.adk.agents.Callbacks.AfterModelCallbackSync;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.AfterToolCallbackBase;
import com.google.adk.agents.Callbacks.AfterToolCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackBase;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallbackBase;
import com.google.adk.agents.Callbacks.BeforeModelCallbackSync;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallbackBase;
import com.google.adk.agents.Callbacks.BeforeToolCallbackSync;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A plugin that wraps callbacks and exposes them as a plugin. */
public class CallbackPlugin extends PluginManager {

  private static final Logger logger = LoggerFactory.getLogger(CallbackPlugin.class);

  private final ImmutableListMultimap<Class<?>, Object> callbacks;

  private CallbackPlugin(
      ImmutableList<? extends BasePlugin> plugins,
      ImmutableListMultimap<Class<?>, Object> callbacks) {
    super(plugins);
    this.callbacks = callbacks;
  }

  @Override
  public String getName() {
    return "CallbackPlugin";
  }

  @SuppressWarnings("unchecked") // The builder ensures that the type is correct.
  private <T> ImmutableList<T> getCallbacks(Class<T> type) {
    return (ImmutableList<T>) callbacks.get(type);
  }

  public ImmutableList<? extends Callbacks.BeforeAgentCallback> getBeforeAgentCallback() {
    return getCallbacks(Callbacks.BeforeAgentCallback.class);
  }

  public ImmutableList<? extends Callbacks.AfterAgentCallback> getAfterAgentCallback() {
    return getCallbacks(Callbacks.AfterAgentCallback.class);
  }

  public ImmutableList<? extends Callbacks.BeforeModelCallback> getBeforeModelCallback() {
    return getCallbacks(Callbacks.BeforeModelCallback.class);
  }

  public ImmutableList<? extends Callbacks.AfterModelCallback> getAfterModelCallback() {
    return getCallbacks(Callbacks.AfterModelCallback.class);
  }

  public ImmutableList<? extends Callbacks.BeforeToolCallback> getBeforeToolCallback() {
    return getCallbacks(Callbacks.BeforeToolCallback.class);
  }

  public ImmutableList<? extends Callbacks.AfterToolCallback> getAfterToolCallback() {
    return getCallbacks(Callbacks.AfterToolCallback.class);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link CallbackPlugin}. */
  public static class Builder {
    // Ensures a unique name for each callback.
    private static final AtomicInteger callbackId = new AtomicInteger(0);

    private final ImmutableList.Builder<BasePlugin> plugins = ImmutableList.builder();
    private final ListMultimap<Class<?>, Object> callbacks = ArrayListMultimap.create();

    Builder() {}

    @CanIgnoreReturnValue
    public Builder addBeforeAgentCallback(Callbacks.BeforeAgentCallback callback) {
      callbacks.put(Callbacks.BeforeAgentCallback.class, callback);
      plugins.add(
          new BasePlugin("BeforeAgentCallback_" + callbackId.getAndIncrement()) {
            @Override
            public Maybe<Content> beforeAgentCallback(
                BaseAgent agent, CallbackContext callbackContext) {
              return callback.call(callbackContext);
            }
          });
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addBeforeAgentCallbackSync(Callbacks.BeforeAgentCallbackSync callback) {
      return addBeforeAgentCallback(
          callbackContext -> Maybe.fromOptional(callback.call(callbackContext)));
    }

    @CanIgnoreReturnValue
    public Builder addAfterAgentCallback(Callbacks.AfterAgentCallback callback) {
      callbacks.put(Callbacks.AfterAgentCallback.class, callback);
      plugins.add(
          new BasePlugin("AfterAgentCallback_" + callbackId.getAndIncrement()) {
            @Override
            public Maybe<Content> afterAgentCallback(
                BaseAgent agent, CallbackContext callbackContext) {
              return callback.call(callbackContext);
            }
          });
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addAfterAgentCallbackSync(Callbacks.AfterAgentCallbackSync callback) {
      return addAfterAgentCallback(
          callbackContext -> Maybe.fromOptional(callback.call(callbackContext)));
    }

    @CanIgnoreReturnValue
    public Builder addBeforeModelCallback(Callbacks.BeforeModelCallback callback) {
      callbacks.put(Callbacks.BeforeModelCallback.class, callback);
      plugins.add(
          new BasePlugin("BeforeModelCallback_" + callbackId.getAndIncrement()) {
            @Override
            public Maybe<LlmResponse> beforeModelCallback(
                CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
              return callback.call(callbackContext, llmRequest);
            }
          });
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addBeforeModelCallbackSync(Callbacks.BeforeModelCallbackSync callback) {
      return addBeforeModelCallback(
          (callbackContext, llmRequest) ->
              Maybe.fromOptional(callback.call(callbackContext, llmRequest)));
    }

    @CanIgnoreReturnValue
    public Builder addAfterModelCallback(Callbacks.AfterModelCallback callback) {
      callbacks.put(Callbacks.AfterModelCallback.class, callback);
      plugins.add(
          new BasePlugin("AfterModelCallback_" + callbackId.getAndIncrement()) {
            @Override
            public Maybe<LlmResponse> afterModelCallback(
                CallbackContext callbackContext, LlmResponse llmResponse) {
              return callback.call(callbackContext, llmResponse);
            }
          });
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addAfterModelCallbackSync(Callbacks.AfterModelCallbackSync callback) {
      return addAfterModelCallback(
          (callbackContext, llmResponse) ->
              Maybe.fromOptional(callback.call(callbackContext, llmResponse)));
    }

    @CanIgnoreReturnValue
    public Builder addBeforeToolCallback(Callbacks.BeforeToolCallback callback) {
      callbacks.put(Callbacks.BeforeToolCallback.class, callback);
      plugins.add(
          new BasePlugin("BeforeToolCallback_" + callbackId.getAndIncrement()) {
            @Override
            public Maybe<Map<String, Object>> beforeToolCallback(
                BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
              return callback.call(toolContext.invocationContext(), tool, toolArgs, toolContext);
            }
          });
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addBeforeToolCallbackSync(Callbacks.BeforeToolCallbackSync callback) {
      return addBeforeToolCallback(
          (invocationContext, tool, toolArgs, toolContext) ->
              Maybe.fromOptional(callback.call(invocationContext, tool, toolArgs, toolContext)));
    }

    @CanIgnoreReturnValue
    public Builder addAfterToolCallback(Callbacks.AfterToolCallback callback) {
      callbacks.put(Callbacks.AfterToolCallback.class, callback);
      plugins.add(
          new BasePlugin("AfterToolCallback_" + callbackId.getAndIncrement()) {
            @Override
            public Maybe<Map<String, Object>> afterToolCallback(
                BaseTool tool,
                Map<String, Object> toolArgs,
                ToolContext toolContext,
                Map<String, Object> result) {
              return callback.call(
                  toolContext.invocationContext(), tool, toolArgs, toolContext, result);
            }
          });
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addAfterToolCallbackSync(Callbacks.AfterToolCallbackSync callback) {
      return addAfterToolCallback(
          (invocationContext, tool, toolArgs, toolContext, result) ->
              Maybe.fromOptional(
                  callback.call(invocationContext, tool, toolArgs, toolContext, result)));
    }

    @CanIgnoreReturnValue
    public Builder addCallback(BeforeAgentCallbackBase callback) {
      if (callback instanceof BeforeAgentCallback beforeAgentCallbackInstance) {
        addBeforeAgentCallback(beforeAgentCallbackInstance);
      } else if (callback instanceof BeforeAgentCallbackSync beforeAgentCallbackSyncInstance) {
        addBeforeAgentCallbackSync(beforeAgentCallbackSyncInstance);
      } else {
        logger.warn(
            "Invalid beforeAgentCallback callback type: %s. Ignoring this callback.",
            callback.getClass().getName());
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addCallback(AfterAgentCallbackBase callback) {
      if (callback instanceof AfterAgentCallback afterAgentCallbackInstance) {
        addAfterAgentCallback(afterAgentCallbackInstance);
      } else if (callback instanceof AfterAgentCallbackSync afterAgentCallbackSyncInstance) {
        addAfterAgentCallbackSync(afterAgentCallbackSyncInstance);
      } else {
        logger.warn(
            "Invalid afterAgentCallback callback type: %s. Ignoring this callback.",
            callback.getClass().getName());
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addCallback(BeforeModelCallbackBase callback) {
      if (callback instanceof BeforeModelCallback beforeModelCallbackInstance) {
        addBeforeModelCallback(beforeModelCallbackInstance);
      } else if (callback instanceof BeforeModelCallbackSync beforeModelCallbackSyncInstance) {
        addBeforeModelCallbackSync(beforeModelCallbackSyncInstance);
      } else {
        logger.warn(
            "Invalid beforeModelCallback callback type: %s. Ignoring this callback.",
            callback.getClass().getName());
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addCallback(AfterModelCallbackBase callback) {
      if (callback instanceof AfterModelCallback afterModelCallbackInstance) {
        addAfterModelCallback(afterModelCallbackInstance);
      } else if (callback instanceof AfterModelCallbackSync afterModelCallbackSyncInstance) {
        addAfterModelCallbackSync(afterModelCallbackSyncInstance);
      } else {
        logger.warn(
            "Invalid afterModelCallback callback type: %s. Ignoring this callback.",
            callback.getClass().getName());
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addCallback(BeforeToolCallbackBase callback) {
      if (callback instanceof BeforeToolCallback beforeToolCallbackInstance) {
        addBeforeToolCallback(beforeToolCallbackInstance);
      } else if (callback instanceof BeforeToolCallbackSync beforeToolCallbackSyncInstance) {
        addBeforeToolCallbackSync(beforeToolCallbackSyncInstance);
      } else {
        logger.warn(
            "Invalid beforeToolCallback callback type: {}. Ignoring this callback.",
            callback.getClass().getName());
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addCallback(AfterToolCallbackBase callback) {
      if (callback instanceof AfterToolCallback afterToolCallbackInstance) {
        addAfterToolCallback(afterToolCallbackInstance);
      } else if (callback instanceof AfterToolCallbackSync afterToolCallbackSyncInstance) {
        addAfterToolCallbackSync(afterToolCallbackSyncInstance);
      } else {
        logger.warn(
            "Invalid afterToolCallback callback type: {}. Ignoring this callback.",
            callback.getClass().getName());
      }
      return this;
    }

    public CallbackPlugin build() {
      return new CallbackPlugin(plugins.build(), ImmutableListMultimap.copyOf(callbacks));
    }
  }
}
