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
package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PessimisticLockingTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:locking_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "locking-test-app";
  private static final String TEST_USER_ID = "locking-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testSerializedEventAppends() throws InterruptedException {
    String sessionId = "serialized-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    int threadCount = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      final int eventNum = i;
      executor.submit(
          () -> {
            try {
              Event event =
                  Event.builder()
                      .id("event-" + eventNum)
                      .author("thread-" + eventNum)
                      .content(Content.fromParts(Part.fromText("Message " + eventNum)))
                      .timestamp(Instant.now().toEpochMilli())
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();
              successCount.incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(threadCount, successCount.get());

    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(finalSession);
    assertEquals(threadCount, finalSession.events().size());
  }

  @Test
  public void testNoLostUpdates() throws InterruptedException {
    String sessionId = "no-lost-updates";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("counter", 0);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      final int eventNum = i;
      executor.submit(
          () -> {
            try {
              Event event =
                  Event.builder()
                      .id("event-" + eventNum)
                      .author("thread-" + eventNum)
                      .content(Content.fromParts(Part.fromText("Message " + eventNum)))
                      .timestamp(Instant.now().toEpochMilli())
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS));
    executor.shutdown();

    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(finalSession);
    assertEquals(threadCount, finalSession.events().size());
  }

  @Test
  public void testConcurrentAppendDifferentSessions() throws InterruptedException {
    int sessionCount = 5;
    int eventsPerSession = 10;

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "session-" + i;
      sessionService
          .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
          .blockingGet();
    }

    ExecutorService executor = Executors.newFixedThreadPool(sessionCount * eventsPerSession);
    CountDownLatch latch = new CountDownLatch(sessionCount * eventsPerSession);

    for (int i = 0; i < sessionCount; i++) {
      final String sessionId = "session-" + i;
      for (int j = 0; j < eventsPerSession; j++) {
        final int eventNum = j;
        executor.submit(
            () -> {
              try {
                Event event =
                    Event.builder()
                        .id(UUID.randomUUID().toString())
                        .author("test")
                        .content(Content.fromParts(Part.fromText("Event " + eventNum)))
                        .timestamp(Instant.now().toEpochMilli())
                        .build();

                Session session =
                    sessionService
                        .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                        .blockingGet();

                sessionService.appendEvent(session, event).blockingGet();
              } catch (Exception e) {
                throw new RuntimeException(e);
              } finally {
                latch.countDown();
              }
            });
      }
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS));
    executor.shutdown();

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "session-" + i;
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      assertNotNull(session);
      assertEquals(eventsPerSession, session.events().size());
    }
  }

  @Test
  public void testAppendEventUnderLoad() throws InterruptedException {
    String sessionId = "load-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    int threadCount = 50;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      final int eventNum = i;
      executor.submit(
          () -> {
            try {
              Event event =
                  Event.builder()
                      .id("event-" + eventNum)
                      .author("thread-" + eventNum)
                      .content(Content.fromParts(Part.fromText("Load test " + eventNum)))
                      .timestamp(Instant.now().toEpochMilli())
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();
              successCount.incrementAndGet();
            } catch (Exception e) {
              failureCount.incrementAndGet();
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(120, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(threadCount, successCount.get());
    assertEquals(0, failureCount.get());

    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(finalSession);
    assertEquals(threadCount, finalSession.events().size());
  }

  @Test
  public void testEventOrderingConsistency() throws InterruptedException {
    String sessionId = "ordering-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    int eventCount = 100;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch latch = new CountDownLatch(eventCount);

    for (int i = 0; i < eventCount; i++) {
      final int eventNum = i;
      executor.submit(
          () -> {
            try {
              Event event =
                  Event.builder()
                      .id("event-" + String.format("%03d", eventNum))
                      .author("test")
                      .content(Content.fromParts(Part.fromText("Message " + eventNum)))
                      .timestamp(Instant.now().toEpochMilli() + eventNum)
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(120, TimeUnit.SECONDS));
    executor.shutdown();

    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(finalSession);
    assertEquals(eventCount, finalSession.events().size());
  }
}
