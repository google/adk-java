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
package com.google.adk.utils;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.common.util.concurrent.MoreExecutors;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for converting ApiFuture to RxJava Single and Maybe types. */
public class ApiFutureUtils {

  /** Logger for this class. */
  private static final Logger logger = LoggerFactory.getLogger(ApiFutureUtils.class);

  // Executor for async operations. Package-private for testing.
  static Executor executor = MoreExecutors.directExecutor();

  private ApiFutureUtils() {}

  /**
   * Converts an ApiFuture to an RxJava Single.
   *
   * @param future the ApiFuture to convert
   * @param <T> the type of the result
   * @return a Single that emits the result of the ApiFuture
   */
  public static <T> Single<T> toSingle(ApiFuture<T> future) {
    return Single.create(
        emitter -> {
          ApiFutures.addCallback(
              future,
              new ApiFutureCallback<T>() {
                @Override
                public void onSuccess(T result) {
                  emitter.onSuccess(result);
                }

                @Override
                public void onFailure(Throwable t) {
                  // Log the failure of the future before passing it down the reactive chain.
                  logger.error("ApiFuture failed with an exception.", t);
                  emitter.onError(t);
                }
              },
              executor);
        });
  }

  /**
   * Converts an ApiFuture to an RxJava Maybe.
   *
   * @param future the ApiFuture to convert
   * @param <T> the type of the result
   * @return a Maybe that emits the result of the ApiFuture or completes if the future fails
   */
  public static <T> Maybe<T> toMaybe(ApiFuture<T> future) {
    return toSingle(future).toMaybe();
  }
}
