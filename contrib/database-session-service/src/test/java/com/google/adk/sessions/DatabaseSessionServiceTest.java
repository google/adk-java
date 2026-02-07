package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DatabaseSessionServiceTest {

  private static final String TEST_DB_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private static final String TEST_APP_NAME = "test-app";
  private static final String TEST_USER_ID = "test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      try (Connection conn = DriverManager.getConnection(TEST_DB_URL);
          Statement stmt = conn.createStatement()) {
        stmt.execute("DELETE FROM events");
        stmt.execute("DELETE FROM sessions");
        stmt.execute("DELETE FROM app_states");
        stmt.execute("DELETE FROM user_states");
      } catch (Exception e) {
      }
      sessionService.close();
    }
  }

  private long countEventsInDatabase(String sessionId) throws Exception {
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM events WHERE session_id = '" + sessionId + "'")) {
      if (rs.next()) {
        return rs.getLong(1);
      }
      return 0;
    }
  }

  private long countSessionsInDatabase(String sessionId) throws Exception {
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE id = '" + sessionId + "'")) {
      if (rs.next()) {
        return rs.getLong(1);
      }
      return 0;
    }
  }

  @Test
  public void testCreateSession() {
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key1", "value1");
    state.put("key2", 42);

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, null).blockingGet();

    assertNotNull(session);
    assertNotNull(session.id());
    assertEquals(TEST_APP_NAME, session.appName());
    assertEquals(TEST_USER_ID, session.userId());
    assertEquals("value1", session.state().get("key1"));
    assertEquals(42, session.state().get("key2"));
    assertTrue(session.events().isEmpty());
  }

  @Test
  public void testCreateSessionWithId() {
    String sessionId = "custom-session-id";
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertEquals(sessionId, session.id());
    assertEquals(TEST_APP_NAME, session.appName());
    assertEquals(TEST_USER_ID, session.userId());
  }

  @Test
  public void testGetSession() {
    String sessionId = "get-session-test";
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key", "value");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrievedSession);
    assertEquals(sessionId, retrievedSession.id());
    assertEquals(TEST_APP_NAME, retrievedSession.appName());
    assertEquals(TEST_USER_ID, retrievedSession.userId());
    assertEquals("value", retrievedSession.state().get("key"));
  }

  @Test
  public void testGetSessionNotFound() {
    String nonExistentId = "non-existent";

    assertNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, nonExistentId, Optional.empty())
            .blockingGet());
  }

  @Test
  public void testListSessionsEmpty() {
    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();

    assertNotNull(response);
    assertEquals(0, response.sessions().size());
  }

  @Test
  public void testListSessions() {
    String sessionId1 = "list-test-1";
    String sessionId2 = "list-test-2";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, TEST_USER_ID).blockingGet();

    assertNotNull(response);
    List<Session> sessions = response.sessions();
    assertEquals(2, sessions.size());
    assertTrue(sessions.stream().anyMatch(s -> s.id().equals(sessionId1)));
    assertTrue(sessions.stream().anyMatch(s -> s.id().equals(sessionId2)));
  }

  @Test
  public void testAppendEvent() {
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
    assertEquals(1, updatedSession.events().size());
    Event retrievedEvent = updatedSession.events().get(0);
    assertEquals(event.id(), retrievedEvent.id());
    assertEquals(event.author(), retrievedEvent.author());
  }

  @Test
  public void testAppendEventToNonExistentSession() {
    String nonExistentId = "non-existent";
    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Hello, world!")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session nonExistentSession =
        Session.builder(nonExistentId)
            .appName(TEST_APP_NAME)
            .userId(TEST_USER_ID)
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .build();
    assertThrows(
        SessionNotFoundException.class,
        () -> sessionService.appendEvent(nonExistentSession, event).blockingGet());
  }

  @Test
  public void testDeleteSession() {
    String sessionId = "delete-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    assertNotNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet());

    sessionService.deleteSession(TEST_APP_NAME, TEST_USER_ID, sessionId).blockingAwait();

    assertNull(
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet());
  }

  @Test
  public void testListEvents() {
    String sessionId = "list-events-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("index: " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      sessionService.appendEvent(session, event).blockingGet();
      try {
        TimeUnit.MILLISECONDS.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    ListEventsResponse response =
        sessionService.listEvents(TEST_APP_NAME, TEST_USER_ID, sessionId).blockingGet();

    assertNotNull(response);
    assertEquals(5, response.events().size());
  }

  @Test
  public void testGetSessionWithNumRecentEvents() {
    String sessionId = "filter-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant startTime = Instant.now();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("index: " + i)))
              .timestamp(startTime.plusSeconds(i).toEpochMilli())
              .build();

      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      sessionService.appendEvent(session, event).blockingGet();
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(2).build();
    Session sessionWithRecentEvents =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(sessionWithRecentEvents);
    assertEquals(2, sessionWithRecentEvents.events().size());
  }

  @Test
  public void testAppendEventUpdatesSessionState() {
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("sessionKey", "sessionValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertNotNull(retrievedSession);
    assertEquals("sessionValue", retrievedSession.state().get("sessionKey"));
  }

  @Test
  public void testAppendEventUpdatesAppState() {
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), "session2")
            .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("_app_appKey", "appValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertNotNull(retrievedSession);
    assertEquals("appValue", retrievedSession.state().get("_app_appKey"));
  }

  @Test
  public void testAppendEventUpdatesUserState() {
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), "session3")
            .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("_user_userKey", "userValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertNotNull(retrievedSession);
    assertEquals("userValue", retrievedSession.state().get("_user_userKey"));
  }

  @Test
  public void testDeleteSessionRemovesAllRelatedData() throws Exception {
    String sessionId = "delete-cascade-test";
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

    long eventsBefore = countEventsInDatabase(sessionId);
    assertEquals(5, eventsBefore, "Should have 5 events before deletion");

    long sessionsBefore = countSessionsInDatabase(sessionId);
    assertEquals(1, sessionsBefore, "Should have 1 session before deletion");

    sessionService.deleteSession(TEST_APP_NAME, TEST_USER_ID, sessionId).blockingAwait();

    long eventsAfter = countEventsInDatabase(sessionId);
    assertEquals(0, eventsAfter, "All events should be deleted from database");

    long sessionsAfter = countSessionsInDatabase(sessionId);
    assertEquals(0, sessionsAfter, "Session should be deleted from database");
  }

  @Test
  public void testEventsPersistAfterMultipleReads() throws Exception {
    String sessionId = "persist-after-reads-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 3; i++) {
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

    long eventsBeforeReads = countEventsInDatabase(sessionId);
    assertEquals(3, eventsBeforeReads);

    for (int i = 0; i < 5; i++) {
      sessionService
          .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
          .blockingGet();
    }

    long eventsAfterReads = countEventsInDatabase(sessionId);
    assertEquals(3, eventsAfterReads, "Events should persist in database after multiple reads");
  }

  @Test
  public void testAppendEventWithNullContent() {
    String sessionId = "null-content-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Event eventWithNullContent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Optional.empty())
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, eventWithNullContent).blockingGet();
    Session updatedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updatedSession);
    assertEquals(1, updatedSession.events().size());
  }

  @Test
  public void testEmptyStateDelta() {
    String sessionId = "empty-delta-test";
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    Event eventWithEmptyDelta =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(EventActions.builder().stateDelta(new ConcurrentHashMap<>()).build())
            .build();

    sessionService.appendEvent(session, eventWithEmptyDelta).blockingGet();
    Session updatedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updatedSession);
    assertEquals(1, updatedSession.events().size());
  }

  @Test
  public void testNullStateDeltaHandling() {
    String sessionId = "null-delta-test";
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    Event eventWithNullActions =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(null)
            .build();

    sessionService.appendEvent(session, eventWithNullActions).blockingGet();
    Session updatedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updatedSession);
    assertEquals(1, updatedSession.events().size());
  }

  @Test
  public void testAppendEventWithRemovedDeletesKeys() throws Exception {
    String sessionId = UUID.randomUUID().toString();

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "app_key", "app_value");
    initialState.put(State.USER_PREFIX + "user_key", "user_value");
    initialState.put("session_key", "session_value");

    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
            .blockingGet();

    assertNotNull(session);
    assertEquals("app_value", session.state().get(State.APP_PREFIX + "app_key"));
    assertEquals("user_value", session.state().get(State.USER_PREFIX + "user_key"));
    assertEquals("session_value", session.state().get("session_key"));

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "app_key", State.REMOVED);
    delta.put(State.USER_PREFIX + "user_key", State.REMOVED);
    delta.put("session_key", State.REMOVED);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Remove keys")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertFalse(updated.state().containsKey(State.APP_PREFIX + "app_key"));
    assertFalse(updated.state().containsKey(State.USER_PREFIX + "user_key"));
    assertFalse(updated.state().containsKey("session_key"));

    try (Connection conn = DriverManager.getConnection(TEST_DB_URL);
        Statement stmt = conn.createStatement()) {

      ResultSet rs =
          stmt.executeQuery(
              "SELECT state FROM app_states WHERE app_name = '" + TEST_APP_NAME + "'");
      if (rs.next()) {
        String appStateJson = rs.getString("state");
        assertFalse(appStateJson.contains("app_key"), "app_key should be removed from database");
      }

      rs =
          stmt.executeQuery(
              "SELECT state FROM user_states WHERE app_name = '"
                  + TEST_APP_NAME
                  + "' AND user_id = '"
                  + TEST_USER_ID
                  + "'");
      if (rs.next()) {
        String userStateJson = rs.getString("state");
        assertFalse(userStateJson.contains("user_key"), "user_key should be removed from database");
      }

      rs = stmt.executeQuery("SELECT state FROM sessions WHERE id = '" + sessionId + "'");
      if (rs.next()) {
        String sessionStateJson = rs.getString("state");
        assertFalse(
            sessionStateJson.contains("session_key"),
            "session_key should be removed from database");
      }
    }
  }

  @Test
  public void testRemovedOnlyAffectsSpecifiedTier() throws Exception {
    String sessionId = UUID.randomUUID().toString();

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "app_keep", "app_value");
    initialState.put(State.APP_PREFIX + "app_remove", "remove_this");
    initialState.put(State.USER_PREFIX + "user_keep", "user_value");
    initialState.put(State.USER_PREFIX + "user_remove", "remove_this");
    initialState.put("session_keep", "session_value");
    initialState.put("session_remove", "remove_this");

    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
            .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "app_remove", State.REMOVED);
    delta.put(State.USER_PREFIX + "user_remove", State.REMOVED);
    delta.put("session_remove", State.REMOVED);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Selective removal")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);

    assertEquals("app_value", updated.state().get(State.APP_PREFIX + "app_keep"));
    assertFalse(updated.state().containsKey(State.APP_PREFIX + "app_remove"));

    assertEquals("user_value", updated.state().get(State.USER_PREFIX + "user_keep"));
    assertFalse(updated.state().containsKey(State.USER_PREFIX + "user_remove"));

    assertEquals("session_value", updated.state().get("session_keep"));
    assertFalse(updated.state().containsKey("session_remove"));
  }
}
