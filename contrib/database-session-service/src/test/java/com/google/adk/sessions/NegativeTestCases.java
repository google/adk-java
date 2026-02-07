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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NegativeTestCases {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:negative_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "negative-test-app";
  private static final String TEST_USER_ID = "negative-test-user";

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
  public void testCreateSessionWithNullAppName() {
    assertThrows(
        NullPointerException.class,
        () ->
            sessionService
                .createSession(null, TEST_USER_ID, new ConcurrentHashMap<>(), "session-1")
                .blockingGet());
  }

  @Test
  public void testCreateSessionWithNullUserId() {
    assertThrows(
        NullPointerException.class,
        () ->
            sessionService
                .createSession(TEST_APP_NAME, null, new ConcurrentHashMap<>(), "session-1")
                .blockingGet());
  }

  @Test
  public void testCreateSessionWithNullState() {
    // Null state should be accepted and treated as empty state
    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, null, "session-1").blockingGet();
    assertNotNull(session);
    assertNotNull(session.state());
    assertTrue(session.state().isEmpty());
  }

  @Test
  public void testGetSessionWithNullAppName() {
    assertThrows(
        NullPointerException.class,
        () ->
            sessionService
                .getSession(null, TEST_USER_ID, "session-1", Optional.empty())
                .blockingGet());
  }

  @Test
  public void testGetSessionWithNullUserId() {
    assertThrows(
        NullPointerException.class,
        () ->
            sessionService
                .getSession(TEST_APP_NAME, null, "session-1", Optional.empty())
                .blockingGet());
  }

  @Test
  public void testGetSessionWithNullSessionId() {
    assertThrows(
        NullPointerException.class,
        () ->
            sessionService
                .getSession(TEST_APP_NAME, TEST_USER_ID, null, Optional.empty())
                .blockingGet());
  }

  @Test
  public void testAppendEventToDeletedSession() {
    String sessionId = "deleted-session";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    sessionService.deleteSession(TEST_APP_NAME, TEST_USER_ID, sessionId).blockingAwait();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    assertThrows(
        SessionNotFoundException.class,
        () ->
            sessionService
                .appendEvent(
                    Session.builder(sessionId)
                        .appName(TEST_APP_NAME)
                        .userId(TEST_USER_ID)
                        .state(new ConcurrentHashMap<>())
                        .events(new ArrayList<>())
                        .build(),
                    event)
                .blockingGet());
  }

  @Test
  public void testDeleteNonExistentSession() {
    sessionService.deleteSession(TEST_APP_NAME, TEST_USER_ID, "non-existent").blockingAwait();
  }

  @Test
  public void testGetNonExistentSession() {
    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, "non-existent", Optional.empty())
            .blockingGet();
    assertNull(session);
  }

  @Test
  public void testCreateSessionWithEmptySessionId() {
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), "")
            .blockingGet();
    assertNotNull(session);
  }

  @Test
  public void testCreateSessionWithVeryLongSessionId() {
    String longId = "a".repeat(200);

    Exception exception =
        assertThrows(
            Exception.class,
            () ->
                sessionService
                    .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), longId)
                    .blockingGet());

    assertTrue(
        exception.getMessage().contains("too long") || exception.getCause() != null,
        "Should fail with constraint violation for long session ID");
  }

  @Test
  public void testCreateDuplicateSession() {
    String sessionId = "duplicate-session";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    assertThrows(
        Exception.class,
        () ->
            sessionService
                .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
                .blockingGet());
  }

  @Test
  public void testAppendEventWithDuplicateId() {
    String sessionId = "duplicate-event-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    String eventId = "duplicate-event-id";
    Event event1 =
        Event.builder()
            .id(eventId)
            .author("author-1")
            .content(Content.fromParts(Part.fromText("Event 1")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, event1).blockingGet();

    Event event2 =
        Event.builder()
            .id(eventId)
            .author("author-2")
            .content(Content.fromParts(Part.fromText("Event 2")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    assertThrows(
        Exception.class,
        () ->
            sessionService
                .appendEvent(
                    Session.builder(sessionId)
                        .appName(TEST_APP_NAME)
                        .userId(TEST_USER_ID)
                        .state(new ConcurrentHashMap<>())
                        .events(new ArrayList<>())
                        .build(),
                    event2)
                .blockingGet());
  }

  @Test
  public void testStateDeltaWithComplexNestedStructures() {
    String sessionId = "complex-state-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> complexDelta = new ConcurrentHashMap<>();
    complexDelta.put("level1", Map.of("level2", Map.of("level3", "deep-value")));
    complexDelta.put("array", java.util.List.of(1, 2, 3, 4, 5));
    complexDelta.put("mixed", Map.of("num", 42, "str", "text", "bool", true));

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Complex state")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(com.google.adk.events.EventActions.builder().stateDelta(complexDelta).build())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, event).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(updatedSession);

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(retrieved);
    assertTrue(retrieved.state().containsKey("level1"));
    assertTrue(retrieved.state().containsKey("array"));
    assertTrue(retrieved.state().containsKey("mixed"));
  }

  @Test
  public void testGetSessionWithInvalidConfig() {
    String sessionId = "invalid-config-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    // Test negative numRecentEvents: -1 should be treated as abs(-1) = 1 (last 1 event)
    GetSessionConfig negativeNumEvents = GetSessionConfig.builder().numRecentEvents(-1).build();
    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(negativeNumEvents))
            .blockingGet();

    assertNotNull(session);
    // Should return exactly 1 event (the most recent one)
    assertTrue(
        session.events().size() == 1,
        "Expected 1 event for numRecentEvents=-1, got " + session.events().size());
    // Should be the last event added (event-5)
    assertTrue(
        session.events().get(0).id().equals("event-5"),
        "Expected most recent event (event-5), got " + session.events().get(0).id());
  }

  @Test
  public void testConcurrentDeleteAndRead() throws InterruptedException {
    String sessionId = "concurrent-delete-read-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    Thread deleter =
        new Thread(
            () -> {
              try {
                Thread.sleep(50);
                sessionService
                    .deleteSession(TEST_APP_NAME, TEST_USER_ID, sessionId)
                    .blockingAwait();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    Thread reader =
        new Thread(
            () -> {
              for (int i = 0; i < 10; i++) {
                try {
                  sessionService
                      .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                      .blockingGet();
                  Thread.sleep(20);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                } catch (Exception e) {
                }
              }
            });

    deleter.start();
    reader.start();

    deleter.join();
    reader.join();

    Session finalCheck =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNull(finalCheck, "Session should be deleted");
  }
}
