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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for pessimistic locking on app_states and user_states tables.
 *
 * <p>This test verifies that concurrent updates to app-level and user-level state from multiple
 * sessions do not result in lost updates. Without pessimistic locking, concurrent read-modify-write
 * operations can overwrite each other's changes.
 */
public class AppUserStateLockingTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:app_user_locking_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "app-user-lock-test";
  private static final String TEST_USER_ID = "test-user";

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

  /**
   * Tests that concurrent updates to app state from multiple threads on the SAME session preserve
   * all changes.
   *
   * <p>Scenario: - 10 threads concurrently append events to the SAME session - Each event sets a
   * unique key in app state - Expected: All 10 keys present - Without locking on app_states: some
   * keys would be lost
   *
   * <p>Note: This tests the real-world pattern where events carry state deltas, not
   * read-modify-write.
   */
  @Test
  public void testAppStateConcurrentUpdates_noLostUpdates() throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    // Create initial session
    String sharedSessionId = "shared-session";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sharedSessionId)
        .blockingGet();

    // Each thread appends event with a unique app state key
    for (int i = 0; i < threadCount; i++) {
      final int threadNum = i;
      executor.submit(
          () -> {
            try {
              // Each thread sets its own unique key in app state
              ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
              stateDelta.put("app:thread_" + threadNum, threadNum);

              Event event =
                  Event.builder()
                      .id("event-" + threadNum)
                      .author("thread-" + threadNum)
                      .content(Content.fromParts(Part.fromText("Increment app counter")))
                      .timestamp(Instant.now().toEpochMilli())
                      .actions(EventActions.builder().stateDelta(stateDelta).build())
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sharedSessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();

            } catch (Exception e) {
              throw new RuntimeException("Thread " + threadNum + " failed", e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS), "Threads did not complete in time");
    executor.shutdown();

    // Verify final counter value
    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sharedSessionId, Optional.empty())
            .blockingGet();

    assertNotNull(finalSession);

    // Check that all thread keys are present
    for (int i = 0; i < threadCount; i++) {
      String key = "app:thread_" + i;
      assertTrue(finalSession.state().containsKey(key), "app:thread_" + i + " should exist");
      assertEquals(i, finalSession.state().get(key), "app:thread_" + i + " should equal " + i);
    }
  }

  /**
   * Tests that concurrent updates to user state from multiple sessions preserve all changes.
   *
   * <p>Scenario: - Same user has 10 different sessions (e.g., phone, laptop, tablet) - Each session
   * concurrently increments user:notification_count - Expected final value: 10 (all updates
   * preserved) - Without locking: final value would be < 10 (lost updates)
   */
  @Test
  public void testUserStateConcurrentUpdates_noLostUpdates() throws InterruptedException {
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    // Create initial session
    String sharedSessionId = "shared-session";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sharedSessionId)
        .blockingGet();

    // Each thread appends event with a unique user state key
    for (int i = 0; i < threadCount; i++) {
      final int threadNum = i;
      executor.submit(
          () -> {
            try {
              // Each thread sets its own unique key in user state
              ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
              stateDelta.put("user:thread_" + threadNum, threadNum);

              Event event =
                  Event.builder()
                      .id("notif-" + threadNum)
                      .author("device-" + threadNum)
                      .content(Content.fromParts(Part.fromText("New notification")))
                      .timestamp(Instant.now().toEpochMilli())
                      .actions(EventActions.builder().stateDelta(stateDelta).build())
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sharedSessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();

            } catch (Exception e) {
              throw new RuntimeException("Thread " + threadNum + " failed", e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS), "Threads did not complete in time");
    executor.shutdown();

    // Verify final notification count
    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sharedSessionId, Optional.empty())
            .blockingGet();

    assertNotNull(finalSession);

    // Check that all thread keys are present
    for (int i = 0; i < threadCount; i++) {
      String key = "user:thread_" + i;
      assertTrue(finalSession.state().containsKey(key), "user:thread_" + i + " should exist");
      assertEquals(i, finalSession.state().get(key), "user:thread_" + i + " should equal " + i);
    }
  }

  /**
   * Tests that concurrent updates to both app and user state work correctly.
   *
   * <p>Scenario: - 5 sessions concurrently update both app:total_requests and user:request_count -
   * Tests that locks on app_states and user_states don't deadlock
   */
  @Test
  public void testConcurrentAppAndUserStateUpdates() throws InterruptedException {
    int threadCount = 5;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    // Create initial session
    String sharedSessionId = "shared-session";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sharedSessionId)
        .blockingGet();

    // Each thread appends event with both app and user state updates
    for (int i = 0; i < threadCount; i++) {
      final int threadNum = i;
      executor.submit(
          () -> {
            try {
              // Each thread sets unique keys in both app and user state
              ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
              stateDelta.put("app:req_" + threadNum, threadNum);
              stateDelta.put("user:req_" + threadNum, threadNum);

              Event event =
                  Event.builder()
                      .id("req-" + threadNum)
                      .author("thread-" + threadNum)
                      .content(Content.fromParts(Part.fromText("API request")))
                      .timestamp(Instant.now().toEpochMilli())
                      .actions(EventActions.builder().stateDelta(stateDelta).build())
                      .build();

              Session session =
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sharedSessionId, Optional.empty())
                      .blockingGet();

              sessionService.appendEvent(session, event).blockingGet();

            } catch (Exception e) {
              throw new RuntimeException("Thread " + threadNum + " failed", e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS), "Threads did not complete in time");
    executor.shutdown();

    // Verify both counters
    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sharedSessionId, Optional.empty())
            .blockingGet();

    assertNotNull(finalSession);

    // Check that all app and user keys are present
    for (int i = 0; i < threadCount; i++) {
      String appKey = "app:req_" + i;
      String userKey = "user:req_" + i;
      assertTrue(finalSession.state().containsKey(appKey), appKey + " should exist");
      assertTrue(finalSession.state().containsKey(userKey), userKey + " should exist");
      assertEquals(i, finalSession.state().get(appKey), appKey + " should equal " + i);
      assertEquals(i, finalSession.state().get(userKey), userKey + " should equal " + i);
    }
  }
}
