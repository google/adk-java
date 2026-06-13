package com.google.adk.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

public class HttpUtilsTest {

  @Test
  public void testSharedHttpClientDaemonThreads() {
    ThreadFactory tf =
        ((java.util.concurrent.ThreadPoolExecutor)
                HttpUtils.createSharedHttpClient("Test").dispatcher().executorService())
            .getThreadFactory();
    // Usually OkHttp uses a specific thread factory. We passed our own DaemonThreadFactory
    Thread t = tf.newThread(() -> {});
    assertTrue(t.isDaemon(), "HttpUtils thread factory should produce daemon threads");
  }

  @Test
  public void testSharedHttpClientTimeouts() {
    okhttp3.OkHttpClient client = HttpUtils.createSharedHttpClient("Test");
    assertEquals(300000, client.connectTimeoutMillis());
    assertEquals(300000, client.readTimeoutMillis());
    assertEquals(300000, client.writeTimeoutMillis());
  }
}
