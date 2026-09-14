package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test to verify that app/user state is stored WITHOUT prefixes in the database but
 * retrieved WITH prefixes in the session state.
 *
 * <p>This ensures compatibility with:
 *
 * <ul>
 *   <li>Python DatabaseSessionService implementation
 *   <li>Java InMemorySessionService implementation
 *   <li>Proper state isolation and namespace handling
 * </ul>
 */
public class StatePrefixHandlingTest {

  private static final String TEST_DB_URL = "jdbc:h2:mem:testdb_prefix;DB_CLOSE_DELAY=-1";
  private static final String TEST_APP_NAME = "test-app";
  private static final String TEST_USER_ID_1 = "user1";
  private static final String TEST_USER_ID_2 = "user2";

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

  @Test
  public void testAppStatePrefixStrippedInDatabase() throws Exception {
    String sessionId = "app-prefix-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "counter", 42);
    delta.put(State.APP_PREFIX + "theme", "dark");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();

    try (Connection conn = DriverManager.getConnection(TEST_DB_URL);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT state FROM app_states WHERE app_name = '" + TEST_APP_NAME + "'")) {

      assertTrue(rs.next(), "App state should exist in database");
      String stateJson = rs.getString("state");
      assertNotNull(stateJson);
      assertFalse(
          stateJson.contains("\"app:counter\""), "Database should NOT contain 'app:' prefix");
      assertFalse(stateJson.contains("\"app:theme\""), "Database should NOT contain 'app:' prefix");
      assertTrue(stateJson.contains("\"counter\""), "Database should contain unprefixed 'counter'");
      assertTrue(stateJson.contains("\"theme\""), "Database should contain unprefixed 'theme'");
    }
  }

  @Test
  public void testUserStatePrefixStrippedInDatabase() throws Exception {
    String sessionId = "user-prefix-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.USER_PREFIX + "preference", "enabled");
    delta.put(State.USER_PREFIX + "language", "en");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();

    try (Connection conn = DriverManager.getConnection(TEST_DB_URL);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT state FROM user_states WHERE app_name = '"
                    + TEST_APP_NAME
                    + "' AND user_id = '"
                    + TEST_USER_ID_1
                    + "'")) {

      assertTrue(rs.next(), "User state should exist in database");
      String stateJson = rs.getString("state");
      assertNotNull(stateJson);
      assertFalse(
          stateJson.contains("\"user:preference\""), "Database should NOT contain 'user:' prefix");
      assertFalse(
          stateJson.contains("\"user:language\""), "Database should NOT contain 'user:' prefix");
      assertTrue(
          stateJson.contains("\"preference\""), "Database should contain unprefixed 'preference'");
      assertTrue(
          stateJson.contains("\"language\""), "Database should contain unprefixed 'language'");
    }
  }

  @Test
  public void testSessionStatePrefixAddedDuringRetrieval() {
    String sessionId = "retrieval-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "global_value", "shared");
    delta.put(State.USER_PREFIX + "user_value", "personal");
    delta.put("session_value", "private");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals(
        "shared",
        retrieved.state().get(State.APP_PREFIX + "global_value"),
        "App state should have 'app:' prefix");
    assertEquals(
        "personal",
        retrieved.state().get(State.USER_PREFIX + "user_value"),
        "User state should have 'user:' prefix");
    assertEquals(
        "private", retrieved.state().get("session_value"), "Session state should NOT have prefix");
  }

  @Test
  public void testAppStateSharedAcrossUsers() {
    String sessionId1 = "session1";
    String sessionId2 = "session2";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_2, new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "feature_flag", true);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session1, event).blockingGet();

    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_2, sessionId2, Optional.empty())
            .blockingGet();

    assertTrue(
        (Boolean) session2.state().get(State.APP_PREFIX + "feature_flag"),
        "User 2 should see app state set by User 1");
  }

  @Test
  public void testUserStateIsolatedBetweenUsers() {
    String sessionId1 = "session1";
    String sessionId2 = "session2";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_2, new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.USER_PREFIX + "timezone", "UTC");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session1, event).blockingGet();

    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_2, sessionId2, Optional.empty())
            .blockingGet();

    assertNull(
        session2.state().get(State.USER_PREFIX + "timezone"),
        "User 2 should NOT see user state set by User 1");
  }

  @Test
  public void testUserStateSharedAcrossSessionsForSameUser() {
    String sessionId1 = "session1";
    String sessionId2 = "session2";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.USER_PREFIX + "notification_enabled", true);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session1, event).blockingGet();

    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertTrue(
        (Boolean) session2.state().get(State.USER_PREFIX + "notification_enabled"),
        "Same user should see user state across different sessions");
  }
}
