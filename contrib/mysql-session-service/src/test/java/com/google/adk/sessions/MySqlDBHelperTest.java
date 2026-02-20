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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.reactivex.rxjava3.core.Single;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MySqlDBHelper} using H2 in MySQL compatibility mode. This ensures SQL
 * syntax is valid without requiring a real MySQL container.
 */
class MySqlDBHelperTest {

  private DataSource dataSource;
  private MySqlDBHelper dbHelper;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    dataSource = new HikariDataSource(config);

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      stmt.execute("DROP TABLE IF EXISTS adk_events");
      stmt.execute("DROP TABLE IF EXISTS adk_sessions");
      stmt.execute("DROP TABLE IF EXISTS adk_app_state");
      stmt.execute("DROP TABLE IF EXISTS adk_user_state");

      stmt.execute(
          "CREATE TABLE adk_sessions ("
              + "session_id VARCHAR(255) PRIMARY KEY, "
              + "app_name VARCHAR(255) NOT NULL, "
              + "user_id VARCHAR(255) NOT NULL, "
              + "state TEXT, "
              + "created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), "
              + "updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), "
              + "INDEX idx_app_user (app_name, user_id))");

      stmt.execute(
          "CREATE TABLE adk_events ("
              + "event_id VARCHAR(255) PRIMARY KEY, "
              + "session_id VARCHAR(255) NOT NULL, "
              + "event_data TEXT, "
              + "created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), "
              + "FOREIGN KEY (session_id) REFERENCES adk_sessions(session_id) ON DELETE CASCADE, "
              + "INDEX idx_session_created (session_id, created_at))");

      stmt.execute(
          "CREATE TABLE adk_app_state ("
              + "app_name VARCHAR(255) NOT NULL, "
              + "state_key VARCHAR(255) NOT NULL, "
              + "state_value TEXT, "
              + "PRIMARY KEY (app_name, state_key))");

      stmt.execute(
          "CREATE TABLE adk_user_state ("
              + "app_name VARCHAR(255) NOT NULL, "
              + "user_id VARCHAR(255) NOT NULL, "
              + "state_key VARCHAR(255) NOT NULL, "
              + "state_value TEXT, "
              + "PRIMARY KEY (app_name, user_id, state_key))");
    }

    dbHelper = new MySqlDBHelper(dataSource);
  }

  @AfterEach
  void tearDown() {
    if (dataSource instanceof HikariDataSource) {
      ((HikariDataSource) dataSource).close();
    }
  }

  @Test
  void appendEvent_withAppState_executesSqlSuccessfully() {
    // Create a session first
    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    // Create event with App State delta
    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.APP_PREFIX + "someKey", "someValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    // This triggers the upsertAppStateSql.
    // If there is a parameter mismatch (3 columns vs 4 placeholders), this will fail.
    dbHelper.appendEventAndUpdateState(session, event).blockingAwait();

    // Verify it was inserted
    Single<ConcurrentHashMap<String, Object>> stateSingle =
        dbHelper.getInitialState("testApp", "testUser");
    ConcurrentHashMap<String, Object> state = stateSingle.blockingGet();
    assertThat(state).containsEntry(State.APP_PREFIX + "someKey", "someValue");
  }

  @Test
  void appendEvent_withUserState_executesSqlSuccessfully() {
    // Create a session
    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    // Create event with User State delta
    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.USER_PREFIX + "someUserKey", "someUserValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    dbHelper.appendEventAndUpdateState(session, event).blockingAwait();

    // Verify it was inserted
    ConcurrentHashMap<String, Object> state =
        dbHelper.getInitialState("testApp", "testUser").blockingGet();
    assertThat(state).containsEntry(State.USER_PREFIX + "someUserKey", "someUserValue");
  }

  @Test
  void listSessions_retrievesSessionState() {
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key1", "value1");

    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(state)
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    java.util.List<Session> sessions = dbHelper.listSessions("testApp", "testUser").blockingGet();

    assertThat(sessions).hasSize(1);
    assertThat(sessions.get(0).state()).containsEntry("key1", "value1");
  }

  @Test
  void listEvents_enforcesOwnership() {
    String sessionId = UUID.randomUUID().toString();
    Session session =
        Session.builder(sessionId)
            .appName("correctApp")
            .userId("correctUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .timestamp(System.currentTimeMillis())
            .build();

    dbHelper.appendEventAndUpdateState(session, event).blockingAwait();

    // 1. Correct credentials should return the event
    java.util.List<Event> events =
        dbHelper.listEvents("correctApp", "correctUser", sessionId).blockingGet();
    assertThat(events).hasSize(1);

    // 2. Wrong App Name should return empty
    java.util.List<Event> eventsWrongApp =
        dbHelper.listEvents("wrongApp", "correctUser", sessionId).blockingGet();
    assertThat(eventsWrongApp).isEmpty();

    // 3. Wrong User ID should return empty
    java.util.List<Event> eventsWrongUser =
        dbHelper.listEvents("correctApp", "wrongUser", sessionId).blockingGet();
    assertThat(eventsWrongUser).isEmpty();
  }

  @Test
  void getSession_mergesStateCorrectly() throws Exception {
    // 1. Setup Global App State directly in DB
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES ('testApp', 'sharedKey', '\"globalValue\"')");
      stmt.execute(
          "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES ('testApp', 'globalOnly', '\"globalOnlyValue\"')");
    }

    // 2. Create Session with conflicting key
    ConcurrentMap<String, Object> sessionState = new ConcurrentHashMap<>();
    sessionState.put("sharedKey", "sessionValue");

    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(sessionState)
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    // 3. Retrieve Session
    Session retrievedSession =
        dbHelper
            .getSession("testApp", "testUser", session.id(), java.util.Optional.empty())
            .blockingGet();

    // 4. Verify Merge Logic
    // Session-specific value should override Global value
    assertThat(retrievedSession.state()).containsEntry("sharedKey", "sessionValue");
    // Non-conflicting Global value should be present with prefix
    assertThat(retrievedSession.state())
        .containsEntry(State.APP_PREFIX + "globalOnly", "globalOnlyValue");
    // The shared key from global state should also be present with prefix
    assertThat(retrievedSession.state())
        .containsEntry(State.APP_PREFIX + "sharedKey", "globalValue");
  }

  @Test
  void deleteSession_executesSqlSuccessfully() {
    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    dbHelper.deleteSession("testApp", "testUser", session.id()).blockingAwait();

    Session deletedSession =
        dbHelper
            .getSession("testApp", "testUser", session.id(), java.util.Optional.empty())
            .blockingGet();
    assertThat(deletedSession).isNull();
  }

  @Test
  public void listSessions_mergesGlobalState() throws Exception {
    // 1. Setup Global App State directly in DB
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES ('testApp', 'globalKey', '\"globalValue\"')");
      stmt.execute(
          "INSERT INTO adk_user_state (app_name, user_id, state_key, state_value) VALUES ('testApp', 'testUser', 'userKey', '\"userValue\"')");
    }

    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    java.util.List<Session> sessions = dbHelper.listSessions("testApp", "testUser").blockingGet();

    assertThat(sessions).hasSize(1);
    assertThat(sessions.get(0).state())
        .containsEntry(State.APP_PREFIX + "globalKey", "globalValue");
    assertThat(sessions.get(0).state()).containsEntry(State.USER_PREFIX + "userKey", "userValue");
  }

  @Test
  void prefixHandling_storesCleanKeysInDb() throws Exception {
    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.APP_PREFIX + "cleanAppKey", "val1");
    stateDelta.put(State.USER_PREFIX + "cleanUserKey", "val2");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    dbHelper.appendEventAndUpdateState(session, event).blockingAwait();

    // Verify DB storage directly
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      // Check App State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_app_state WHERE app_name = 'testApp' AND state_key = 'cleanAppKey'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("state_key")).isEqualTo("cleanAppKey"); // Should NOT have prefix
      }

      // Check User State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_user_state WHERE app_name = 'testApp' AND user_id = 'testUser' AND state_key = 'cleanUserKey'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("state_key")).isEqualTo("cleanUserKey"); // Should NOT have prefix
      }
    }
  }

  @Test
  void prefixHandling_removesKeysFromDb() throws Exception {
    // 1. Setup existing state in DB
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES ('testApp', 'keyToRemove', '\"val\"')");
      stmt.execute(
          "INSERT INTO adk_user_state (app_name, user_id, state_key, state_value) VALUES ('testApp', 'testUser', 'keyToRemove', '\"val\"')");
    }

    Session session =
        Session.builder(UUID.randomUUID().toString())
            .appName("testApp")
            .userId("testUser")
            .state(new ConcurrentHashMap<>())
            .events(new ArrayList<>())
            .lastUpdateTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build();

    dbHelper.saveSession(session).blockingAwait();

    // 2. Create event with REMOVED delta
    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.APP_PREFIX + "keyToRemove", State.REMOVED);
    stateDelta.put(State.USER_PREFIX + "keyToRemove", State.REMOVED);

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    dbHelper.appendEventAndUpdateState(session, event).blockingAwait();

    // 3. Verify deletion in DB
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      // Check App State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_app_state WHERE app_name = 'testApp' AND state_key = 'keyToRemove'")) {
        assertThat(rs.next()).isFalse(); // Should be gone
      }

      // Check User State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_user_state WHERE app_name = 'testApp' AND user_id = 'testUser' AND state_key = 'keyToRemove'")) {
        assertThat(rs.next()).isFalse(); // Should be gone
      }
    }
  }
}
