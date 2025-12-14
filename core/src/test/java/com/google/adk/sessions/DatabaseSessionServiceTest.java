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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for DatabaseSessionService. */
public class DatabaseSessionServiceTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "test-app";
  private static final String TEST_USER_ID = "test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    // Initialize schema with Flyway
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();
    flyway.clean(); // Clean the database (safe in tests with in-memory DB)
    flyway.migrate(); // Apply migrations

    // Create service with H2 in-memory database
    Map<String, Object> properties =
        Map.of(
            "hibernate.show_sql", "true",
            "hibernate.format_sql", "true",
            "hibernate.hbm2ddl.auto", "validate"); // Set to validate mode

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);
  }

  @AfterEach
  public void tearDown() {
    // Clean up resources
    sessionService.close();
  }

  @Test
  public void testCreateSession() {
    // Arrange
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key1", "value1");
    state.put("key2", 42);

    // Act
    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, null).blockingGet();

    // Assert
    assertNotNull(session);
    assertNotNull(session.id());
    assertEquals(TEST_APP_NAME, session.appName());
    assertEquals(TEST_USER_ID, session.userId());
    assertEquals("value1", session.state().get("key1"));
    assertEquals(42, session.state().get("key2"));
    assertTrue(session.events().isEmpty());
    // Note: Session no longer has appState/userState accessors
    // Database still stores these values but they aren't exposed in the Session model
  }

  @Test
  public void testCreateSessionWithId() {
    // Arrange
    String sessionId = "custom-session-id";
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    // Act
    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Assert
    assertNotNull(session);
    assertEquals(sessionId, session.id());
    assertEquals(TEST_APP_NAME, session.appName());
    assertEquals(TEST_USER_ID, session.userId());
  }

  @Test
  public void testGetSession() {
    // Arrange
    String sessionId = "get-session-test";
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key", "value");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Act
    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    // Assert
    assertNotNull(retrievedSession);
    assertEquals(sessionId, retrievedSession.id());
    assertEquals(TEST_APP_NAME, retrievedSession.appName());
    assertEquals(TEST_USER_ID, retrievedSession.userId());
    assertEquals("value", retrievedSession.state().get("key"));
  }

  @Test
  public void testGetSessionNotFound() {
    // Arrange
    String nonExistentId = "non-existent";

    // Act & Assert
    assertNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, nonExistentId, Optional.empty())
            .blockingGet());
  }

  @Test
  public void testLifecycleNoSession() {
    assertNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, "non-existent-session", Optional.empty())
            .blockingGet());

    ListSessionsResponse sessionsResponse =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();
    assertEquals(0, sessionsResponse.sessions().size());

    ListEventsResponse eventsResponse =
        sessionService
            .listEvents(TEST_APP_NAME, TEST_USER_ID, "non-existent-session")
            .blockingGet();
    assertEquals(0, eventsResponse.events().size());
  }

  @Test
  public void testListSessionsEmpty() {
    // Act
    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();

    // Assert
    assertNotNull(response);
    assertEquals(0, response.sessions().size());
  }

  @Test
  public void testListSessions() {
    // Arrange
    String sessionId1 = "list-test-1";
    String sessionId2 = "list-test-2";

    // Create two sessions
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    // Act
    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();

    // Assert
    assertNotNull(response);
    List<Session> sessions = response.sessions();
    assertEquals(2, sessions.size());
    assertTrue(sessions.stream().anyMatch(s -> s.id().equals(sessionId1)));
    assertTrue(sessions.stream().anyMatch(s -> s.id().equals(sessionId2)));
  }

  @Test
  public void testAppendEvent() {
    // Arrange
    String sessionId = "event-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Hello, world!")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    // Act
    Session updatedSession =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    // Assert
    assertNotNull(updatedSession);
    assertEquals(1, updatedSession.events().size());
    Event retrievedEvent = updatedSession.events().get(0);
    assertEquals(event.id(), retrievedEvent.id());
    assertEquals(event.author(), retrievedEvent.author());
    assertEquals(
        "Hello, world!",
        retrievedEvent.content().flatMap(c -> c.parts()).stream()
            .flatMap(List::stream)
            .flatMap(p -> p.text().stream())
            .findFirst()
            .orElse(""));
  }

  @Test
  public void testAppendEventToNonExistentSession() {
    // Arrange
    String nonExistentId = "non-existent";
    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Hello, world!")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    // Act & Assert
    assertThrows(
        SessionNotFoundException.class,
        () ->
            sessionService
                .appendEvent(TEST_APP_NAME, TEST_USER_ID, nonExistentId, event)
                .blockingGet());
  }

  @Test
  public void testDeleteSession() {
    // Arrange
    String sessionId = "delete-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    // Verify session exists
    assertNotNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet());

    // Act
    sessionService.deleteSession(TEST_APP_NAME, TEST_USER_ID, sessionId).blockingAwait();

    // Assert
    assertNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet());
  }

  @Test
  public void testListEvents() {
    // Arrange
    String sessionId = "list-events-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    // Create events
    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("index: " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
      // Add small delay to ensure different timestamps
      try {
        TimeUnit.MILLISECONDS.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Act
    ListEventsResponse response =
        sessionService.listEvents(TEST_APP_NAME, TEST_USER_ID, sessionId, 3, null).blockingGet();

    // Assert
    assertNotNull(response);
    assertEquals(3, response.events().size());
    assertNotNull(response.nextPageToken());

    // Test pagination
    ListEventsResponse page2 =
        sessionService
            .listEvents(
                TEST_APP_NAME, TEST_USER_ID, sessionId, 3, response.nextPageToken().orElse(null))
            .blockingGet();
    assertNotNull(page2);
    assertEquals(2, page2.events().size()); // 5 total - 3 from first page = 2 events
    assertTrue(page2.nextPageToken().isEmpty()); // No more pages
  }

  @Test
  public void testGetSessionWithFiltering() {
    // Arrange
    String sessionId = "filter-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant startTime = Instant.now();

    // Create events with incrementing timestamps
    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("index: " + i)))
              .timestamp(startTime.plusSeconds(i).toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    // Act - Get only recent events
    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(2).build();
    Session sessionWithRecentEvents =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, java.util.Optional.of(config))
            .blockingGet();

    // Assert - Should have only the 2 most recent events
    assertNotNull(sessionWithRecentEvents);
    assertEquals(2, sessionWithRecentEvents.events().size());
    // Content structure has changed, content is now Optional<Content> instead of Map

    // Act - Get events after a specific timestamp
    GetSessionConfig timestampConfig =
        GetSessionConfig.builder().afterTimestamp(startTime.plusSeconds(3)).build();
    Session sessionWithTimestampFilter =
        sessionService
            .getSession(
                TEST_APP_NAME, TEST_USER_ID, sessionId, java.util.Optional.of(timestampConfig))
            .blockingGet();

    // Assert - Should have only events after the specified timestamp
    assertNotNull(sessionWithTimestampFilter);
    assertEquals(2, sessionWithTimestampFilter.events().size());
    // Content structure has changed, content is now Optional<Content> instead of Map
  }

  @Test
  public void testAppendEventUpdatesSessionState() {
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("sessionKey", "sessionValue");
    stateDelta.put("_app_appKey", "appValue");
    stateDelta.put("_user_userKey", "userValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(com.google.adk.events.EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertNotNull(retrievedSession);
    assertEquals("sessionValue", retrievedSession.state().get("sessionKey"));
    assertEquals("appValue", retrievedSession.state().get("_app_appKey"));
    assertEquals("userValue", retrievedSession.state().get("_user_userKey"));
  }
}
