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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/** Utility class for common HTTP client configuration across the ADK. */
public final class HttpUtils {

  private HttpUtils() {}

  /**
   * Configures a custom OkHttp dispatcher that uses daemon threads. By default, OkHttp uses
   * non-daemon threads for its async call thread pool, which prevents the JVM from shutting down
   * for 60 seconds (the default keep-alive) after the last streaming request completes.
   *
   * @param name The prefix name to use for the dispatcher threads.
   * @return A pre-configured Dispatcher using daemon threads.
   */
  private static Dispatcher createDaemonDispatcher(String name) {
    ThreadFactory daemonThreadFactory =
        r -> {
          Thread t = new Thread(r, name + "-Dispatcher");
          t.setDaemon(true);
          return t;
        };
    return new Dispatcher(Executors.newCachedThreadPool(daemonThreadFactory));
  }

  /**
   * Creates a shared OkHttpClient instance equipped with a daemon thread dispatcher.
   *
   * @param threadName The prefix name to use for the dispatcher threads.
   * @return A pre-configured OkHttpClient.
   */
  public static OkHttpClient createSharedHttpClient(String threadName) {
    return new OkHttpClient.Builder()
        .dispatcher(createDaemonDispatcher(threadName))
        .connectTimeout(Duration.ofMinutes(5))
        .readTimeout(Duration.ofMinutes(5))
        .writeTimeout(Duration.ofMinutes(5))
        .build();
  }
}
