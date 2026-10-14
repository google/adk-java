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

import com.google.adk.events.EventActions;
import org.jspecify.annotations.Nullable;

/**
 * The context of various callbacks for an agent invocation.
 *
 * <p>Extends {@link Context} for backward compatibility; agent and model callback signatures still
 * use this type.
 */
public class CallbackContext extends Context {

  /**
   * Initializes callback context.
   *
   * @param invocationContext Current invocation context.
   * @param eventActions Callback event actions, or null for new empty ones.
   */
  public CallbackContext(InvocationContext invocationContext, @Nullable EventActions eventActions) {
    super(invocationContext, eventActions, /* eventId= */ null);
  }

  /**
   * Initializes callback context.
   *
   * @param invocationContext Current invocation context.
   * @param eventActions Callback event actions, or null for new empty ones.
   * @param eventId The ID of the event associated with this context, or null if there is none.
   */
  public CallbackContext(
      InvocationContext invocationContext,
      @Nullable EventActions eventActions,
      @Nullable String eventId) {
    super(invocationContext, eventActions, eventId);
  }
}
