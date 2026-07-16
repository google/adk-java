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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link HttpClientFactory}. */
@RunWith(JUnit4.class)
public final class HttpClientFactoryTest {

  @Test
  public void getOrCreateSharedHttpClient_sameName_returnsCachedInstance() {
    OkHttpClient first = HttpClientFactory.getOrCreateSharedHttpClient("cacheByName");
    OkHttpClient second = HttpClientFactory.getOrCreateSharedHttpClient("cacheByName");

    assertSame(first, second);
  }

  @Test
  public void getOrCreateSharedHttpClient_differentNames_returnDistinctInstances() {
    OkHttpClient first = HttpClientFactory.getOrCreateSharedHttpClient("nameA");
    OkHttpClient second = HttpClientFactory.getOrCreateSharedHttpClient("nameB");

    assertNotSame(first, second);
  }

  @Test
  public void createHttpClient_usesInjectedExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      OkHttpClient client = HttpClientFactory.createHttpClient(executor);

      assertSame(executor, client.dispatcher().executorService());
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void createHttpClient_isNotCached_returnsDistinctInstances() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      OkHttpClient first = HttpClientFactory.createHttpClient(executor);
      OkHttpClient second = HttpClientFactory.createHttpClient(executor);

      assertNotSame(first, second);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void daemonExecutor_producesDaemonThreads() {
    ExecutorService executor = HttpClientFactory.daemonExecutor("daemonPool");
    try {
      ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
      assertEquals(0, pool.getCorePoolSize());
      assertEquals(Integer.MAX_VALUE, pool.getMaximumPoolSize());
      assertEquals(60L, pool.getKeepAliveTime(TimeUnit.SECONDS));

      Thread thread = pool.getThreadFactory().newThread(() -> {});
      assertTrue(thread.isDaemon());
    } finally {
      executor.shutdown();
    }
  }
}
