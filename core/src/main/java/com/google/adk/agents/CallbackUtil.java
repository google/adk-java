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
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackBase;
import com.google.adk.agents.Callbacks.AfterAgentCallbackBase;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class for processing and converting different types of callback interfaces.
 * This class provides static methods to normalize various callback implementations (synchronous or asynchronous)
 * into a consistent asynchronous {@link Maybe}-based format, suitable for integration into agent workflows.
 * It also handles cases where callback inputs are null or empty.
 */
public final class CallbackUtil {
  private static final Logger logger = LoggerFactory.getLogger(CallbackUtil.class);

  /**
   * Converts a list of {@link BeforeAgentCallbackBase} into an {@link ImmutableList} of
   * {@link BeforeAgentCallback}.
   * This method handles both {@link BeforeAgentCallback} (asynchronous) and
   * {@link BeforeAgentCallbackSync} (synchronous) instances,
   * wrapping synchronous callbacks into an asynchronous {@link Maybe} for unified processing.
   * Invalid callback types will be logged as warnings and ignored.
   *
   * @param beforeAgentCallback A list of {@link BeforeAgentCallbackBase} instances, which may
   * contain either asynchronous or synchronous callback implementations.
   * @return An {@link ImmutableList} of {@link BeforeAgentCallback}, or {@code null} if the input list is {@code null}.
   * Returns an empty list if the input list is empty or if no valid callbacks are found.
   */
  @CanIgnoreReturnValue
  public static @Nullable ImmutableList<BeforeAgentCallback> getBeforeAgentCallbacks(
      List<BeforeAgentCallbackBase> beforeAgentCallback) {
    if (beforeAgentCallback == null) {
      return null;
    } else if (beforeAgentCallback.isEmpty()) {
      return ImmutableList.of();
    } else {
      ImmutableList.Builder<BeforeAgentCallback> builder = ImmutableList.builder();
      for (BeforeAgentCallbackBase callback : beforeAgentCallback) {
        if (callback instanceof BeforeAgentCallback beforeAgentCallbackInstance) {
          builder.add(beforeAgentCallbackInstance);
        } else if (callback instanceof BeforeAgentCallbackSync beforeAgentCallbackSyncInstance) {
          builder.add(
              (BeforeAgentCallback)
                  (callbackContext) ->
                      Maybe.fromOptional(beforeAgentCallbackSyncInstance.call(callbackContext)));
        } else {
          logger.warn(
              "Invalid beforeAgentCallback callback type: %s. Ignoring this callback.",
              callback.getClass().getName());
        }
      }
      return builder.build();
    }
  }

  /**
   * Converts a list of {@link AfterAgentCallbackBase} into an {@link ImmutableList} of
   * {@link AfterAgentCallback}.
   * This method handles both {@link AfterAgentCallback} (asynchronous) and
   * {@link AfterAgentCallbackSync} (synchronous) instances,
   * wrapping synchronous callbacks into an asynchronous {@link Maybe} for unified processing.
   * Invalid callback types will be logged as warnings and ignored.
   *
   * @param afterAgentCallback A list of {@link AfterAgentCallbackBase} instances, which may
   * contain either asynchronous or synchronous callback implementations.
   * @return An {@link ImmutableList} of {@link AfterAgentCallback}, or {@code null} if the input list is {@code null}.
   * Returns an empty list if the input list is empty or if no valid callbacks are found.
   */
  @CanIgnoreReturnValue
  public static @Nullable ImmutableList<AfterAgentCallback> getAfterAgentCallbacks(
      List<AfterAgentCallbackBase> afterAgentCallback) {
    if (afterAgentCallback == null) {
      return null;
    } else if (afterAgentCallback.isEmpty()) {
      return ImmutableList.of();
    } else {
      ImmutableList.Builder<AfterAgentCallback> builder = ImmutableList.builder();
      for (AfterAgentCallbackBase callback : afterAgentCallback) {
        if (callback instanceof AfterAgentCallback afterAgentCallbackInstance) {
          builder.add(afterAgentCallbackInstance);
        } else if (callback instanceof AfterAgentCallbackSync afterAgentCallbackSyncInstance) {
          builder.add(
              (AfterAgentCallback)
                  (callbackContext) ->
                      Maybe.fromOptional(afterAgentCallbackSyncInstance.call(callbackContext)));
        } else {
          logger.warn(
              "Invalid afterAgentCallback callback type: %s. Ignoring this callback.",
              callback.getClass().getName());
        }
      }
      return builder.build();
    }
  }

  /**
   * Private constructor to prevent instantiation of this utility class.
   * This class provides only static helper methods and should not be instantiated.
   */
  private CallbackUtil() {}
}
