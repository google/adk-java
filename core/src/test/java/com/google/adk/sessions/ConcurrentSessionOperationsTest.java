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
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

public class ConcurrentSessionOperationsTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:concurrent_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "concurrent-test-app";
  private static final String TEST_USER_ID = "concurrent-user";

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
  public void testConcurrentEventAppends() throws InterruptedException {
    String sessionId = "concurrent-append-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    int threadCount = 10;
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

              sessionService
                  .appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event)
                  .blockingGet();
              successCount.incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
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
  public void testConcurrentSessionCreation() throws InterruptedException {
    int sessionCount = 10;

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "session-" + i;
      ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
      state.put("index", i);

      sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();
    }

    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();
    assertEquals(sessionCount, response.sessions().size());

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "session-" + i;
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      assertNotNull(session);
      assertEquals(i, session.state().get("index"));
    }
  }

  @Test
  public void testConcurrentStateDeltaUpdates() throws InterruptedException {
    String sessionId = "concurrent-delta-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("counter", 0);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      final int increment = i + 1;
      executor.submit(
          () -> {
            try {
              ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
              delta.put("field_" + increment, "value_" + increment);

              EventActions actions = EventActions.builder().stateDelta(delta).build();

              Event event =
                  Event.builder()
                      .id(UUID.randomUUID().toString())
                      .author("thread-" + increment)
                      .content(Content.fromParts(Part.fromText("Update " + increment)))
                      .timestamp(Instant.now().toEpochMilli())
                      .actions(actions)
                      .build();

              sessionService
                  .appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event)
                  .blockingGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(finalSession);
    assertEquals(threadCount, finalSession.events().size());

    for (int i = 1; i <= threadCount; i++) {
      assertTrue(finalSession.state().containsKey("field_" + i));
      assertEquals("value_" + i, finalSession.state().get("field_" + i));
    }
  }

  @Test
  public void testConcurrentAppStateUpdates() throws InterruptedException {
    int sessionCount = 5;
    List<String> sessionIds = new ArrayList<>();

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "app-state-session-" + i;
      sessionIds.add(sessionId);
      sessionService
          .createSession(TEST_APP_NAME, "user-" + i, new ConcurrentHashMap<>(), sessionId)
          .blockingGet();
    }

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      final int updateNum = i;
      final String sessionId = sessionIds.get(i % sessionCount);
      final String userId = "user-" + (i % sessionCount);

      executor.submit(
          () -> {
            try {
              ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
              delta.put("app:shared_counter", updateNum);

              EventActions actions = EventActions.builder().stateDelta(delta).build();

              Event event =
                  Event.builder()
                      .id(UUID.randomUUID().toString())
                      .author("thread-" + updateNum)
                      .content(Content.fromParts(Part.fromText("Update " + updateNum)))
                      .timestamp(Instant.now().toEpochMilli())
                      .actions(actions)
                      .build();

              sessionService.appendEvent(TEST_APP_NAME, userId, sessionId, event).blockingGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    for (int i = 0; i < sessionCount; i++) {
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, "user-" + i, sessionIds.get(i), Optional.empty())
              .blockingGet();
      assertNotNull(session);
      assertTrue(session.state().containsKey("app:shared_counter"));
    }
  }

  @Test
  public void testConcurrentUserStateUpdates() throws InterruptedException {
    int sessionCount = 5;
    List<String> sessionIds = new ArrayList<>();

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "user-state-session-" + i;
      sessionIds.add(sessionId);
      sessionService
          .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
          .blockingGet();
    }

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      final int updateNum = i;
      final String sessionId = sessionIds.get(i % sessionCount);

      executor.submit(
          () -> {
            try {
              ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
              delta.put("user:counter", updateNum);

              EventActions actions = EventActions.builder().stateDelta(delta).build();

              Event event =
                  Event.builder()
                      .id(UUID.randomUUID().toString())
                      .author("thread-" + updateNum)
                      .content(Content.fromParts(Part.fromText("Update " + updateNum)))
                      .timestamp(Instant.now().toEpochMilli())
                      .actions(actions)
                      .build();

              sessionService
                  .appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event)
                  .blockingGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    for (String sessionId : sessionIds) {
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      assertNotNull(session);
      assertTrue(session.state().containsKey("user:counter"));
    }
  }

  @Test
  public void testConcurrentReadAndWrite() throws InterruptedException {
    String sessionId = "read-write-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    int threadCount = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger readCount = new AtomicInteger(0);
    AtomicInteger writeCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      final int threadNum = i;
      executor.submit(
          () -> {
            try {
              if (threadNum % 2 == 0) {
                Session session =
                    sessionService
                        .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                        .blockingGet();
                if (session != null) {
                  readCount.incrementAndGet();
                }
              } else {
                Event event =
                    Event.builder()
                        .id("event-" + threadNum)
                        .author("thread-" + threadNum)
                        .content(Content.fromParts(Part.fromText("Message " + threadNum)))
                        .timestamp(Instant.now().toEpochMilli())
                        .build();

                sessionService
                    .appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event)
                    .blockingGet();
                writeCount.incrementAndGet();
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(threadCount / 2, readCount.get());
    assertEquals(threadCount / 2, writeCount.get());
  }

  @Test
  public void testConcurrentDeleteOperations() throws InterruptedException {
    int sessionCount = 10;
    List<String> sessionIds = new ArrayList<>();

    for (int i = 0; i < sessionCount; i++) {
      String sessionId = "delete-session-" + i;
      sessionIds.add(sessionId);
      sessionService
          .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
          .blockingGet();
    }

    ExecutorService executor = Executors.newFixedThreadPool(sessionCount);
    CountDownLatch latch = new CountDownLatch(sessionCount);
    AtomicInteger deleteCount = new AtomicInteger(0);

    for (String sessionId : sessionIds) {
      executor.submit(
          () -> {
            try {
              sessionService.deleteSession(TEST_APP_NAME, TEST_USER_ID, sessionId).blockingAwait();
              deleteCount.incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(sessionCount, deleteCount.get());

    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();
    assertEquals(0, response.sessions().size());
  }
}
