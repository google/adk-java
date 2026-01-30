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

import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.AfterAgentCallbackBase;
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackBase;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility methods for normalizing agent callbacks. */
public final class CallbackUtil {
  private static final Logger logger = LoggerFactory.getLogger(CallbackUtil.class);

  /**
   * Normalizes before-agent callbacks.
   *
   * @param beforeAgentCallback Callback list (sync or async).
   * @return normalized async callbacks, or empty list if input is null.
   */
  @CanIgnoreReturnValue
  public static ImmutableList<BeforeAgentCallback> getBeforeAgentCallbacks(
      List<BeforeAgentCallbackBase> beforeAgentCallback) {
    if (beforeAgentCallback == null || beforeAgentCallback.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<BeforeAgentCallback> builder = ImmutableList.builder();
    for (BeforeAgentCallbackBase callback : beforeAgentCallback) {
      if (callback instanceof BeforeAgentCallback beforeAgentCallbackInstance) {
        builder.add(beforeAgentCallbackInstance);
      } else if (callback instanceof BeforeAgentCallbackSync beforeAgentCallbackSyncInstance) {
        builder.add(
            (callbackContext) ->
                Maybe.fromOptional(beforeAgentCallbackSyncInstance.call(callbackContext)));
      } else {
        logger.warn(
            "Invalid beforeAgentCallback callback type: {}. Ignoring this callback.",
            callback.getClass().getName());
      }
    }
    return builder.build();
  }

  /**
   * Normalizes after-agent callbacks.
   *
   * @param afterAgentCallback Callback list (sync or async).
   * @return normalized async callbacks, or empty list if input is null.
   */
  @CanIgnoreReturnValue
  public static ImmutableList<AfterAgentCallback> getAfterAgentCallbacks(
      List<AfterAgentCallbackBase> afterAgentCallback) {
    if (afterAgentCallback == null) {
      return ImmutableList.of();
    } else if (afterAgentCallback.isEmpty()) {
      return ImmutableList.of();
    } else {
      ImmutableList.Builder<AfterAgentCallback> builder = ImmutableList.builder();
      for (AfterAgentCallbackBase callback : afterAgentCallback) {
        if (callback instanceof AfterAgentCallback afterAgentCallbackInstance) {
          builder.add(afterAgentCallbackInstance);
        } else if (callback instanceof AfterAgentCallbackSync afterAgentCallbackSyncInstance) {
          builder.add(
              (callbackContext) ->
                  Maybe.fromOptional(afterAgentCallbackSyncInstance.call(callbackContext)));
        } else {
          logger.warn(
              "Invalid afterAgentCallback callback type: {}. Ignoring this callback.",
              callback.getClass().getName());
        }
      }
      return builder.build();
    }
  }

  private CallbackUtil() {}
}
