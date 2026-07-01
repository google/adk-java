package com.google.adk.plugins.bigquery;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BatchProcessorTest {

  private FakeAnalyticsWriter fakeWriter;
  private BatchProcessor processor;

  @Before
  public void setUp() {
    fakeWriter = new FakeAnalyticsWriter();
    processor = new BatchProcessor(fakeWriter, 2, 50, 2, 100);
  }

  @After
  public void tearDown() {
    if (processor != null) {
      processor.shutdown();
    }
  }

  @Test
  public void start_startsWriter() throws Exception {
    processor.start();
    assertThat(fakeWriter.isReady()).isTrue();
  }

  private void waitForFlush(int waitMs) throws InterruptedException {
    long startTime = System.currentTimeMillis();
    while (fakeWriter.batch.isEmpty() && System.currentTimeMillis() - startTime < waitMs) {
      Thread.sleep(10);
    }
  }

  @Test
  public void flush_writesBatchIfReady() throws Exception {
    processor.start();

    ImmutableMap<String, Object> event1 = ImmutableMap.of("k1", "v1");
    ImmutableMap<String, Object> event2 = ImmutableMap.of("k2", "v2");

    processor.enqueue(event1);
    processor.enqueue(event2);

    waitForFlush(1000);

    assertThat(fakeWriter.batch).containsExactly(event1, event2);
  }

  @Test
  public void flush_doesNotWriteIfNotReady() throws Exception {
    processor.start();
    // Force writer to not be ready after it was started
    fakeWriter.ready = false;

    processor.enqueue(ImmutableMap.of("k1", "v1"));

    // Wait a bit to ensure flush would have happened
    Thread.sleep(150);

    assertThat(fakeWriter.batch).isEmpty();
  }

  @Test
  public void flush_doesNotWriteIfQueueEmpty() throws Exception {
    processor.start();

    // No events enqueued

    // Wait a bit to ensure flush would have happened
    Thread.sleep(150);

    assertThat(fakeWriter.batch).isEmpty();
  }

  @Test
  public void enqueue_dropsWhenQueueFull() throws Exception {
    processor.shutdown(); // Stop the default instance from setUp

    // Queue max size = 1, interval = 200ms
    processor = new BatchProcessor(fakeWriter, 2, 200, 1, 100);
    processor.start();

    ImmutableMap<String, Object> event1 = ImmutableMap.of("k1", "v1");
    ImmutableMap<String, Object> event2 = ImmutableMap.of("k2", "v2");

    // The first event succeeds
    processor.enqueue(event1);
    // The second event should be dropped because queue is full
    processor.enqueue(event2);

    waitForFlush(1000);

    // Only event1 was kept, event2 was dropped
    assertThat(fakeWriter.batch).containsExactly(event1);
  }

  @Test
  public void shutdown_cancelsSchedulerAndClosesWriter() throws Exception {
    processor.start();
    processor.shutdown();
    assertThat(fakeWriter.isReady()).isFalse();
  }
}
