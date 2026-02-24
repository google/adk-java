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
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility methods for normalizing agent callbacks. */
public final class CallbackUtil {
  private static final Logger logger = LoggerFactory.getLogger(CallbackUtil.class);

  /**
   * Normalizes before-agent callbacks.
   *
   * @param beforeAgentCallbacks Callback list (sync or async).
   * @return normalized async callbacks, or empty list if input is null.
   */
  @CanIgnoreReturnValue
  public static ImmutableList<BeforeAgentCallback> getBeforeAgentCallbacks(
      List<BeforeAgentCallbackBase> beforeAgentCallbacks) {
    return getCallbacks(
        beforeAgentCallbacks,
        BeforeAgentCallback.class,
        BeforeAgentCallbackSync.class,
        sync -> (callbackContext -> Maybe.fromOptional(sync.call(callbackContext))),
        "beforeAgentCallbacks");
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
    return getCallbacks(
        afterAgentCallback,
        AfterAgentCallback.class,
        AfterAgentCallbackSync.class,
        sync -> (callbackContext -> Maybe.fromOptional(sync.call(callbackContext))),
        "afterAgentCallback");
  }

  private static <B, A extends B, S extends B> ImmutableList<A> getCallbacks(
      List<B> callbacks,
      Class<A> asyncClass,
      Class<S> syncClass,
      Function<S, A> converter,
      String callbackTypeForLogging) {
    if (callbacks == null) {
      return ImmutableList.of();
    }
    return callbacks.stream()
        .flatMap(
            callback -> {
              if (asyncClass.isInstance(callback)) {
                return Stream.of(asyncClass.cast(callback));
              } else if (syncClass.isInstance(callback)) {
                return Stream.of(converter.apply(syncClass.cast(callback)));
              } else {
                logger.warn(
                    "Invalid {} callback type: {}. Ignoring this callback.",
                    callbackTypeForLogging,
                    callback.getClass().getName());
                return Stream.empty();
              }
            })
        .collect(ImmutableList.toImmutableList());
  }

  private CallbackUtil() {}
}
