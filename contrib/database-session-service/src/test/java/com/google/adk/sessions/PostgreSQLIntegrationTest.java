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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.testing.TestDatabaseConfig;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that verify PostgreSQL database table operations directly.
 *
 * <p>This test suite validates: - sessions table: CRUD operations, timestamps, state storage -
 * events table: event persistence, foreign key relationships, cascading deletes - app_states table:
 * application-wide state management - user_states table: user-specific state management - JSONB
 * storage and retrieval - Timestamp tracking (create_time, update_time)
 *
 * <p>Prerequisites: Start PostgreSQL test database with:
 *
 * <pre>{@code
 * docker run -d -p 5432:5432 \
 *   -e POSTGRES_DB=adk_test \
 *   -e POSTGRES_USER=adk_user \
 *   -e POSTGRES_PASSWORD=adk_password \
 *   postgres:15
 * }</pre>
 */
@Tag("integration")
public class PostgreSQLIntegrationTest {

  private static final String TEST_DB_URL = TestDatabaseConfig.POSTGRES_JDBC_URL;
  private static final String TEST_APP_NAME = "table-test-app";
  private static final String TEST_USER_ID = "table-test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    assumeTrue(
        TestDatabaseConfig.isPostgreSQLAvailable(),
        TestDatabaseConfig.getDatabaseNotAvailableMessage("PostgreSQL"));

    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  // ==================== SESSIONS TABLE TESTS ====================

  @Test
  public void testSessionsTableCreation() throws SQLException {
    String sessionId = "session-" + System.currentTimeMillis();
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("test_key", "test_value");

    // Create session via service
    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Verify in database directly
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT app_name, user_id, id, state, create_time, update_time "
              + "FROM sessions WHERE app_name = ? AND user_id = ? AND id = ?";

      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        stmt.setString(3, sessionId);

        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "Session should exist in sessions table");

          assertEquals(TEST_APP_NAME, rs.getString("app_name"));
          assertEquals(TEST_USER_ID, rs.getString("user_id"));
          assertEquals(sessionId, rs.getString("id"));

          // Verify JSONB state
          String stateJson = rs.getString("state");
          assertNotNull(stateJson);
          assertTrue(stateJson.contains("test_key"));
          assertTrue(stateJson.contains("test_value"));

          // Verify timestamps
          Timestamp createTime = rs.getTimestamp("create_time");
          Timestamp updateTime = rs.getTimestamp("update_time");
          assertNotNull(createTime, "create_time should not be null");
          assertNotNull(updateTime, "update_time should not be null");

          assertFalse(rs.next(), "Should only have one session with this ID");
        }
      }
    }
  }

  @Test
  public void testSessionsTableUpdate() throws SQLException {
    String sessionId = "session-update-" + System.currentTimeMillis();

    // Create initial session
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    Timestamp initialUpdateTime;
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT update_time FROM sessions WHERE id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          initialUpdateTime = rs.getTimestamp("update_time");
        }
      }
    }

    // Wait a bit to ensure timestamp difference
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Update session by appending event
    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("updated_key", "updated_value");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Update event")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    // Verify update_time changed
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT state, update_time FROM sessions WHERE id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());

          String stateJson = rs.getString("state");
          assertTrue(stateJson.contains("updated_key"));
          assertTrue(stateJson.contains("updated_value"));

          Timestamp newUpdateTime = rs.getTimestamp("update_time");
          assertTrue(
              newUpdateTime.after(initialUpdateTime),
              "update_time should be updated after state change");
        }
      }
    }
  }

  @Test
  public void testSessionsTablePrimaryKey() throws SQLException {
    String sessionId = "session-pk-" + System.currentTimeMillis();

    // Create session
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    // Verify primary key constraint (app_name, user_id, id)
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      // Count sessions with this ID
      String query = "SELECT COUNT(*) FROM sessions WHERE id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1), "Should have exactly one session with this ID");
        }
      }
    }
  }

  // ==================== EVENTS TABLE TESTS ====================

  @Test
  public void testEventsTableCreation() throws SQLException {
    String sessionId = "session-events-" + System.currentTimeMillis();

    // Create session
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    // Create event
    String eventId = UUID.randomUUID().toString();
    String invocationId = "inv-" + UUID.randomUUID().toString();
    long timestamp = Instant.now().toEpochMilli();

    Event event =
        Event.builder()
            .id(eventId)
            .invocationId(invocationId)
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test event content")))
            .timestamp(timestamp)
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    // Verify in database
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT id, app_name, user_id, session_id, invocation_id, timestamp, event_data "
              + "FROM events WHERE id = ?";

      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, eventId);

        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "Event should exist in events table");

          assertEquals(eventId, rs.getString("id"));
          assertEquals(TEST_APP_NAME, rs.getString("app_name"));
          assertEquals(TEST_USER_ID, rs.getString("user_id"));
          assertEquals(sessionId, rs.getString("session_id"));
          assertEquals(invocationId, rs.getString("invocation_id"));

          Timestamp eventTimestamp = rs.getTimestamp("timestamp");
          assertNotNull(eventTimestamp);

          // Verify JSONB event_data
          String eventData = rs.getString("event_data");
          assertNotNull(eventData);
          assertTrue(eventData.contains("Test event content"));
          assertTrue(eventData.contains("test-author"));

          assertFalse(rs.next(), "Should only have one event with this ID");
        }
      }
    }
  }

  @Test
  public void testEventsTableMultipleEvents() throws SQLException {
    String sessionId = "session-multi-events-" + System.currentTimeMillis();

    // Create session
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    // Create multiple events
    int numEvents = 5;
    for (int i = 0; i < numEvents; i++) {
      Event event =
          Event.builder()
              .id("event-" + i + "-" + UUID.randomUUID())
              .author("author-" + i)
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      sessionService.appendEvent(session, event).blockingGet();
    }

    // Verify event count in database
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT COUNT(*) FROM events WHERE app_name = ? AND user_id = ? AND session_id = ?";

      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        stmt.setString(3, sessionId);

        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(numEvents, rs.getInt(1), "Should have " + numEvents + " events");
        }
      }
    }
  }

  @Test
  public void testEventsTableForeignKeyConstraint() throws SQLException {
    String sessionId = "session-fk-" + System.currentTimeMillis();

    // Create session
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    // Add event
    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("FK test event")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    // Verify events exist
    int eventCountBefore;
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT COUNT(*) FROM events WHERE session_id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          eventCountBefore = rs.getInt(1);
          assertTrue(eventCountBefore > 0, "Should have at least one event");
        }
      }
    }

    // Delete session (should cascade delete events)
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String deleteQuery = "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(deleteQuery)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        stmt.setString(3, sessionId);
        stmt.executeUpdate();
      }
    }

    // Verify events were cascade deleted
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT COUNT(*) FROM events WHERE session_id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(0, rs.getInt(1), "Events should be cascade deleted with session");
        }
      }
    }
  }

  @Test
  public void testEventsTableTimestampOrdering() throws SQLException {
    String sessionId = "session-timestamp-" + System.currentTimeMillis();

    // Create session
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    // Create events with specific timestamps
    long baseTime = Instant.now().toEpochMilli();
    for (int i = 0; i < 3; i++) {
      Event event =
          Event.builder()
              .id("event-" + i + "-" + UUID.randomUUID())
              .author("author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(baseTime + (i * 1000))
              .build();

      session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      sessionService.appendEvent(session, event).blockingGet();
    }

    // Verify events are ordered by timestamp
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT event_data->>'id' as event_id, timestamp "
              + "FROM events WHERE session_id = ? ORDER BY timestamp ASC";

      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, sessionId);

        try (ResultSet rs = stmt.executeQuery()) {
          Timestamp previousTimestamp = null;
          int count = 0;

          while (rs.next()) {
            Timestamp currentTimestamp = rs.getTimestamp("timestamp");
            assertNotNull(currentTimestamp);

            if (previousTimestamp != null) {
              assertTrue(
                  currentTimestamp.after(previousTimestamp)
                      || currentTimestamp.equals(previousTimestamp),
                  "Events should be ordered by timestamp");
            }

            previousTimestamp = currentTimestamp;
            count++;
          }

          assertEquals(3, count, "Should have 3 events");
        }
      }
    }
  }

  // ==================== APP_STATES TABLE TESTS ====================

  @Test
  public void testAppStatesTableCreation() throws SQLException {
    String sessionId = "session-app-state-" + System.currentTimeMillis();

    // Create session with app state
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("app:api_endpoint", "https://api.example.com");
    state.put("app:version", "2.0");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Verify in app_states table
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT app_name, state, update_time FROM app_states WHERE app_name = ?";

      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);

        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "App state should exist in app_states table");

          assertEquals(TEST_APP_NAME, rs.getString("app_name"));

          String stateJson = rs.getString("state");
          assertNotNull(stateJson);
          assertTrue(stateJson.contains("api_endpoint"));
          assertTrue(stateJson.contains("https://api.example.com"));
          assertTrue(stateJson.contains("version"));
          assertTrue(stateJson.contains("2.0"));

          Timestamp updateTime = rs.getTimestamp("update_time");
          assertNotNull(updateTime, "update_time should not be null");
        }
      }
    }
  }

  @Test
  public void testAppStatesTableUpsert() throws SQLException {
    String sessionId1 = "session-app-upsert-1-" + System.currentTimeMillis();
    String sessionId2 = "session-app-upsert-2-" + System.currentTimeMillis();

    // Create first session with app state
    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("app:config", "version1");
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state1, sessionId1).blockingGet();

    Timestamp firstUpdateTime;
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT update_time FROM app_states WHERE app_name = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          firstUpdateTime = rs.getTimestamp("update_time");
        }
      }
    }

    // Wait to ensure timestamp difference
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Create second session with updated app state
    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("app:config", "version2");
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state2, sessionId2).blockingGet();

    // Verify app state was updated (not duplicated)
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT COUNT(*), state, update_time FROM app_states WHERE app_name = ? GROUP BY state, update_time";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "Should have app state");
          assertEquals(1, rs.getInt(1), "Should have only one row for this app");

          String stateJson = rs.getString("state");
          assertTrue(stateJson.contains("version2"), "State should be updated to version2");

          Timestamp newUpdateTime = rs.getTimestamp("update_time");
          assertTrue(
              newUpdateTime.after(firstUpdateTime) || newUpdateTime.equals(firstUpdateTime),
              "update_time should be updated");

          assertFalse(rs.next(), "Should only have one app state row");
        }
      }
    }
  }

  @Test
  public void testAppStatesTablePrimaryKey() throws SQLException {
    String sessionId = "session-app-pk-" + System.currentTimeMillis();

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("app:key", "value");
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Verify only one row per app_name
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT COUNT(*) FROM app_states WHERE app_name = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1), "Should have exactly one row per app_name");
        }
      }
    }
  }

  // ==================== USER_STATES TABLE TESTS ====================

  @Test
  public void testUserStatesTableCreation() throws SQLException {
    String sessionId = "session-user-state-" + System.currentTimeMillis();

    // Create session with user state
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("user:language", "English");
    state.put("user:timezone", "UTC");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Verify in user_states table
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT app_name, user_id, state, update_time FROM user_states WHERE app_name = ? AND user_id = ?";

      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);

        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "User state should exist in user_states table");

          assertEquals(TEST_APP_NAME, rs.getString("app_name"));
          assertEquals(TEST_USER_ID, rs.getString("user_id"));

          String stateJson = rs.getString("state");
          assertNotNull(stateJson);
          assertTrue(stateJson.contains("language"));
          assertTrue(stateJson.contains("English"));
          assertTrue(stateJson.contains("timezone"));
          assertTrue(stateJson.contains("UTC"));

          Timestamp updateTime = rs.getTimestamp("update_time");
          assertNotNull(updateTime, "update_time should not be null");
        }
      }
    }
  }

  @Test
  public void testUserStatesTableUpsert() throws SQLException {
    String sessionId1 = "session-user-upsert-1-" + System.currentTimeMillis();
    String sessionId2 = "session-user-upsert-2-" + System.currentTimeMillis();

    // Create first session with user state
    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("user:preference", "dark");
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state1, sessionId1).blockingGet();

    Timestamp firstUpdateTime;
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT update_time FROM user_states WHERE app_name = ? AND user_id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          firstUpdateTime = rs.getTimestamp("update_time");
        }
      }
    }

    // Wait to ensure timestamp difference
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Create second session with updated user state
    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("user:preference", "light");
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state2, sessionId2).blockingGet();

    // Verify user state was updated (not duplicated)
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT COUNT(*), state, update_time FROM user_states "
              + "WHERE app_name = ? AND user_id = ? GROUP BY state, update_time";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "Should have user state");
          assertEquals(1, rs.getInt(1), "Should have only one row for this user");

          String stateJson = rs.getString("state");
          assertTrue(stateJson.contains("light"), "State should be updated to 'light'");

          Timestamp newUpdateTime = rs.getTimestamp("update_time");
          assertTrue(
              newUpdateTime.after(firstUpdateTime) || newUpdateTime.equals(firstUpdateTime),
              "update_time should be updated");

          assertFalse(rs.next(), "Should only have one user state row");
        }
      }
    }
  }

  @Test
  public void testUserStatesTablePrimaryKey() throws SQLException {
    String sessionId = "session-user-pk-" + System.currentTimeMillis();

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("user:key", "value");
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Verify only one row per (app_name, user_id) combination
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query = "SELECT COUNT(*) FROM user_states WHERE app_name = ? AND user_id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1), "Should have exactly one row per (app_name, user_id)");
        }
      }
    }
  }

  @Test
  public void testUserStatesTableIsolationBetweenUsers() throws SQLException {
    String user1 = "user-1-" + System.currentTimeMillis();
    String user2 = "user-2-" + System.currentTimeMillis();

    // Create sessions for two different users
    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("user:language", "French");
    sessionService.createSession(TEST_APP_NAME, user1, state1, "session-1").blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("user:language", "Spanish");
    sessionService.createSession(TEST_APP_NAME, user2, state2, "session-2").blockingGet();

    // Verify both users have separate state
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      String query =
          "SELECT user_id, state FROM user_states WHERE app_name = ? AND user_id IN (?, ?)";
      try (PreparedStatement stmt = conn.prepareStatement(query)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, user1);
        stmt.setString(3, user2);
        try (ResultSet rs = stmt.executeQuery()) {
          int count = 0;
          while (rs.next()) {
            String userId = rs.getString("user_id");
            String stateJson = rs.getString("state");

            if (userId.equals(user1)) {
              assertTrue(stateJson.contains("French"), "User 1 should have French");
            } else if (userId.equals(user2)) {
              assertTrue(stateJson.contains("Spanish"), "User 2 should have Spanish");
            }
            count++;
          }
          assertEquals(2, count, "Should have state for both users");
        }
      }
    }
  }

  // ==================== CROSS-TABLE INTEGRATION TESTS ====================

  @Test
  public void testAllTablesIntegration() throws SQLException {
    String sessionId = "session-integration-" + System.currentTimeMillis();

    // Create session with all state types
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("session_key", "session_value"); // session state
    state.put("app:api_key", "app-12345"); // app state
    state.put("user:theme", "dark"); // user state

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    // Add event
    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("integration-test")
            .content(Content.fromParts(Part.fromText("Integration test event")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    // Verify all tables have data
    try (Connection conn = DriverManager.getConnection(TEST_DB_URL)) {
      // Check sessions table
      String sessionQuery = "SELECT COUNT(*) FROM sessions WHERE id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(sessionQuery)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1), "Session should exist");
        }
      }

      // Check events table
      String eventQuery = "SELECT COUNT(*) FROM events WHERE session_id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(eventQuery)) {
        stmt.setString(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertTrue(rs.getInt(1) > 0, "Should have at least one event");
        }
      }

      // Check app_states table
      String appQuery = "SELECT state FROM app_states WHERE app_name = ?";
      try (PreparedStatement stmt = conn.prepareStatement(appQuery)) {
        stmt.setString(1, TEST_APP_NAME);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "App state should exist");
          String appState = rs.getString("state");
          assertTrue(appState.contains("api_key"), "App state should contain api_key");
        }
      }

      // Check user_states table
      String userQuery = "SELECT state FROM user_states WHERE app_name = ? AND user_id = ?";
      try (PreparedStatement stmt = conn.prepareStatement(userQuery)) {
        stmt.setString(1, TEST_APP_NAME);
        stmt.setString(2, TEST_USER_ID);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "User state should exist");
          String userState = rs.getString("state");
          assertTrue(userState.contains("theme"), "User state should contain theme");
        }
      }
    }
  }
}
