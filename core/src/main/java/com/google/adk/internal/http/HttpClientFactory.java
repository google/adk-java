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

package com.google.adk.internal.http;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * Creates {@link OkHttpClient}s for the ADK. The default clients are cached per name so the
 * dispatcher and connection pool are reused across the ADK; a caller that supplies its own executor
 * gets a fresh, non-cached client it owns.
 */
public final class HttpClientFactory {

  private static final Map<String, OkHttpClient> sharedClients = new ConcurrentHashMap<>();

  private HttpClientFactory() {}

  /**
   * Returns the shared {@link OkHttpClient} cached by {@code name}, using OkHttp's default
   * threading.
   */
  public static OkHttpClient getOrCreateSharedHttpClient(String name) {
    return sharedClients.computeIfAbsent(name, unused -> new OkHttpClient());
  }

  /**
   * Returns a new {@link OkHttpClient} whose dispatcher runs on {@code executorService}. Pass
   * {@link #daemonExecutor} so a standalone JVM can exit once work is done, or a container-managed
   * executor in a managed environment. The client is not cached: the caller owns the executor and
   * the returned client.
   *
   * @param executorService executor for the dispatcher.
   */
  public static OkHttpClient createHttpClient(ExecutorService executorService) {
    return new OkHttpClient.Builder().dispatcher(new Dispatcher(executorService)).build();
  }

  /**
   * Returns an unbounded pool of daemon threads, matching OkHttp's own dispatcher pool but with
   * daemon threads so a standalone JVM can exit once work is done. Managed container environments
   * should inject their own executor instead of calling this.
   *
   * @param name prefix for the dispatcher thread names.
   */
  public static ExecutorService daemonExecutor(String name) {
    return new ThreadPoolExecutor(
        0, Integer.MAX_VALUE, 60L, SECONDS, new SynchronousQueue<>(), daemonThreadFactory(name));
  }

  private static ThreadFactory daemonThreadFactory(String name) {
    AtomicInteger count = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
