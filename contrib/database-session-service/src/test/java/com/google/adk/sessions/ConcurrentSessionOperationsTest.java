package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.adk.events.Event;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConcurrentSessionOperationsTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:concurrency_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private static final String TEST_APP_NAME = "concurrency-test-app";
  private static final String TEST_USER_ID = "concurrency-test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
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
    int eventsPerThread = 5;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < eventsPerThread; i++) {
                Event event =
                    Event.builder()
                        .id(UUID.randomUUID().toString())
                        .author("thread-" + threadId)
                        .content(Content.fromParts(Part.fromText("Event from thread " + threadId)))
                        .timestamp(Instant.now().toEpochMilli())
                        .build();

                Session session =
                    sessionService
                        .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                        .blockingGet();

                sessionService.appendEvent(session, event).blockingGet();
                TimeUnit.MILLISECONDS.sleep(10);
              }
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(session);
    assertEquals(threadCount * eventsPerThread, session.events().size());
  }

  @Test
  public void testConcurrentSessionCreations() throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<String> sessionIds = new ArrayList<>();

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              String sessionId = "session-" + threadId;
              sessionIds.add(sessionId);
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put("thread", threadId);

              sessionService
                  .createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId)
                  .blockingGet();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    for (String sessionId : sessionIds) {
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      assertNotNull(session);
    }
  }

  @Test
  public void testConcurrentReadsAndWrites() throws InterruptedException {
    String sessionId = "read-write-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    int readerCount = 5;
    int writerCount = 5;
    ExecutorService executor = Executors.newFixedThreadPool(readerCount + writerCount);
    CountDownLatch latch = new CountDownLatch(readerCount + writerCount);

    for (int i = 0; i < writerCount; i++) {
      final int writerId = i;
      executor.submit(
          () -> {
            try {
              for (int j = 0; j < 3; j++) {
                Event event =
                    Event.builder()
                        .id(UUID.randomUUID().toString())
                        .author("writer-" + writerId)
                        .content(Content.fromParts(Part.fromText("Event")))
                        .timestamp(Instant.now().toEpochMilli())
                        .build();

                Session session =
                    sessionService
                        .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                        .blockingGet();

                sessionService.appendEvent(session, event).blockingGet();
                TimeUnit.MILLISECONDS.sleep(20);
              }
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    for (int i = 0; i < readerCount; i++) {
      executor.submit(
          () -> {
            try {
              for (int j = 0; j < 5; j++) {
                sessionService
                    .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                    .blockingGet();
                TimeUnit.MILLISECONDS.sleep(10);
              }
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(session);
    assertEquals(writerCount * 3, session.events().size());
  }

  @Test
  public void testConcurrentAppStateUpdates() throws InterruptedException {
    int threadCount = 5;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              String sessionId = "app-state-" + threadId;
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put("_app_counter", threadId);

              sessionService
                  .createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId)
                  .blockingGet();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, "app-state-0", Optional.empty())
            .blockingGet();

    assertNotNull(session);
    assertNotNull(session.state().get("_app_counter"));
  }

  @Test
  public void testConcurrentCreateSessionsWithSameAppName_noStateCorruption()
      throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      TimeUnit.MILLISECONDS.sleep(10);
      executor.submit(
          () -> {
            try {
              String sessionId = "concurrent-app-state-" + threadId;
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put(State.APP_PREFIX + "key_" + threadId, "value_" + threadId);

              sessionService
                  .createSession(TEST_APP_NAME, "user-" + threadId, state, sessionId)
                  .blockingGet();
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session verifySession =
        sessionService
            .getSession(TEST_APP_NAME, "user-0", "concurrent-app-state-0", Optional.empty())
            .blockingGet();

    assertNotNull(verifySession);
    for (int i = 0; i < threadCount; i++) {
      String key = State.APP_PREFIX + "key_" + i;
      assertEquals(
          "value_" + i,
          verifySession.state().get(key),
          "App state should contain all keys from concurrent creates without corruption");
    }
  }

  @Test
  public void testConcurrentCreateSessionsWithSameUser_noUserStateCorruption()
      throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      TimeUnit.MILLISECONDS.sleep(10);
      executor.submit(
          () -> {
            try {
              String sessionId = "concurrent-user-state-" + threadId;
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put(State.USER_PREFIX + "pref_" + threadId, threadId * 100);

              sessionService
                  .createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId)
                  .blockingGet();
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session verifySession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, "concurrent-user-state-0", Optional.empty())
            .blockingGet();

    assertNotNull(verifySession);
    for (int i = 0; i < threadCount; i++) {
      String key = State.USER_PREFIX + "pref_" + i;
      assertEquals(
          i * 100,
          verifySession.state().get(key),
          "User state should contain all keys from concurrent creates without corruption");
    }
  }

  @Test
  public void testConcurrentCreateSessionsWithMixedStateUpdates() throws InterruptedException {
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              String sessionId = "mixed-state-" + threadId;
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put(State.APP_PREFIX + "shared_app_key", "app_value_" + threadId);
              state.put(State.USER_PREFIX + "user_pref_" + threadId, threadId);
              state.put("session_local", "local_" + threadId);

              sessionService
                  .createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId)
                  .blockingGet();
              TimeUnit.MILLISECONDS.sleep(10);
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session session0 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, "mixed-state-0", Optional.empty())
            .blockingGet();

    assertNotNull(session0);
    assertNotNull(session0.state().get(State.APP_PREFIX + "shared_app_key"));

    for (int i = 0; i < threadCount; i++) {
      assertEquals(i, session0.state().get(State.USER_PREFIX + "user_pref_" + i), "User pref " + i);
    }

    for (int i = 0; i < threadCount; i++) {
      Session sessionI =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, "mixed-state-" + i, Optional.empty())
              .blockingGet();
      assertEquals("local_" + i, sessionI.state().get("session_local"));
    }
  }

  @Test
  public void testConcurrentCreateSessionsWithPreExistingAppState_noLag()
      throws InterruptedException {
    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "key_initial", "initial_value");
    sessionService
        .createSession(TEST_APP_NAME, "initial-user", initialState, "initial-session")
        .blockingGet();

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              String sessionId = "pre-existing-app-state-" + threadId;
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put(State.APP_PREFIX + "key_" + threadId, "value_" + threadId);

              sessionService
                  .createSession(TEST_APP_NAME, "user-" + threadId, state, sessionId)
                  .blockingGet();
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session verifySession =
        sessionService
            .getSession(TEST_APP_NAME, "user-0", "pre-existing-app-state-0", Optional.empty())
            .blockingGet();

    assertNotNull(verifySession);
    assertEquals(
        "initial_value",
        verifySession.state().get(State.APP_PREFIX + "key_initial"),
        "Initial app state key should be preserved");
    for (int i = 0; i < threadCount; i++) {
      String key = State.APP_PREFIX + "key_" + i;
      assertEquals(
          "value_" + i,
          verifySession.state().get(key),
          "App state should contain all keys when row pre-exists (SELECT FOR UPDATE works)");
    }
  }

  @Test
  public void testConcurrentCreateSessionsWithPreExistingUserState_noLag()
      throws InterruptedException {
    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.USER_PREFIX + "pref_initial", -1);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, "initial-session")
        .blockingGet();

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              String sessionId = "pre-existing-user-state-" + threadId;
              ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
              state.put(State.USER_PREFIX + "pref_" + threadId, threadId * 100);

              sessionService
                  .createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId)
                  .blockingGet();
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    Session verifySession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, "pre-existing-user-state-0", Optional.empty())
            .blockingGet();

    assertNotNull(verifySession);
    assertEquals(
        -1,
        verifySession.state().get(State.USER_PREFIX + "pref_initial"),
        "Initial user state key should be preserved");
    for (int i = 0; i < threadCount; i++) {
      String key = State.USER_PREFIX + "pref_" + i;
      assertEquals(
          i * 100,
          verifySession.state().get(key),
          "User state should contain all keys when row pre-exists (SELECT FOR UPDATE works)");
    }
  }
}
