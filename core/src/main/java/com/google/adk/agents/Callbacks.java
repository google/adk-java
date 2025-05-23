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

public final class Callbacks {

  @FunctionalInterface
  public interface BeforeModelCallback {
    Maybe<LlmResponse> call(CallbackContext callbackContext, LlmRequest llmRequest);
  }

  // Helper interface to allow for sync beforeModelCallback. The function is wrapped into an async
  // one before being processed further.
  @FunctionalInterface
  public interface BeforeModelCallbackSync {
    Optional<LlmResponse> call(CallbackContext callbackContext, LlmRequest llmRequest);
  }

  @FunctionalInterface
  public interface AfterModelCallback {
    Maybe<LlmResponse> call(CallbackContext callbackContext, LlmResponse llmResponse);
  }

  // Helper interface to allow for sync afterModelCallback. The function is wrapped into an async
  // one before being processed further.
  @FunctionalInterface
  public interface AfterModelCallbackSync {
    Optional<LlmResponse> call(CallbackContext callbackContext, LlmResponse llmResponse);
  }

  @FunctionalInterface
  public interface BeforeAgentCallback {
    Maybe<Content> call(CallbackContext callbackContext);
  }

  // Helper interface to allow for sync beforeAgentCallback. The function is wrapped into an async
  // one before being processed further.
  @FunctionalInterface
  public interface BeforeAgentCallbackSync {
    Optional<Content> call(CallbackContext callbackContext);
  }

  @FunctionalInterface
  public interface AfterAgentCallback {
    Maybe<Content> call(CallbackContext callbackContext);
  }

  // Helper interface to allow for sync afterAgentCallback. The function is wrapped into an async
  // one before being processed further.
  @FunctionalInterface
  public interface AfterAgentCallbackSync {
    Optional<Content> call(CallbackContext callbackContext);
  }

  @FunctionalInterface
  public interface BeforeToolCallback {
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext);
  }

  // Helper interface to allow for sync beforeToolCallback. The function is wrapped into an async
  // one before being processed further.
  @FunctionalInterface
  public interface BeforeToolCallbackSync {
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext);
  }

  @FunctionalInterface
  public interface AfterToolCallback {
    Maybe<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Object response);
  }

  // Helper interface to allow for sync afterToolCallback. The function is wrapped into an async
  // one before being processed further.
  @FunctionalInterface
  public interface AfterToolCallbackSync {
    Optional<Map<String, Object>> call(
        InvocationContext invocationContext,
        BaseTool baseTool,
        Map<String, Object> input,
        ToolContext toolContext,
        Object response);
  }
}
