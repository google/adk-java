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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Unit tests for {@link MySqlSessionService} using Testcontainers. */
@Testcontainers
public final class MySqlSessionServiceIT {

  @Container
  private static final MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.3.0").withDatabaseName("adk_test");

  private static DataSource dataSource;
  private MySqlSessionService sessionService;

  @BeforeAll
  public static void setUpClass() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(mysql.getJdbcUrl());
    config.setUsername(mysql.getUsername());
    config.setPassword(mysql.getPassword());
    dataSource = new HikariDataSource(config);
  }

  @AfterAll
  public static void tearDownClass() {
    if (dataSource instanceof HikariDataSource) {
      ((HikariDataSource) dataSource).close();
    }
  }

  @BeforeEach
  public void setUp() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
      stmt.execute("DROP TABLE IF EXISTS adk_events");
      stmt.execute("DROP TABLE IF EXISTS adk_sessions");
      stmt.execute("DROP TABLE IF EXISTS adk_app_state");
      stmt.execute("DROP TABLE IF EXISTS adk_user_state");
      stmt.execute("SET FOREIGN_KEY_CHECKS = 1");

      stmt.execute(
          "CREATE TABLE adk_sessions ("
              + "session_id VARCHAR(255) PRIMARY KEY, "
              + "app_name VARCHAR(255) NOT NULL, "
              + "user_id VARCHAR(255) NOT NULL, "
              + "state JSON, "
              + "created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), "
              + "updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE"
              + " CURRENT_TIMESTAMP(3), "
              + "INDEX idx_app_user (app_name, user_id))");

      stmt.execute(
          "CREATE TABLE adk_events ("
              + "event_id VARCHAR(255) PRIMARY KEY, "
              + "session_id VARCHAR(255) NOT NULL, "
              + "event_data JSON, "
              + "created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), "
              + "FOREIGN KEY (session_id) REFERENCES adk_sessions(session_id) ON DELETE CASCADE, "
              + "INDEX idx_session_created (session_id, created_at))");

      stmt.execute(
          "CREATE TABLE adk_app_state ("
              + "app_name VARCHAR(255) NOT NULL, "
              + "state_key VARCHAR(255) NOT NULL, "
              + "state_value JSON, "
              + "PRIMARY KEY (app_name, state_key))");

      stmt.execute(
          "CREATE TABLE adk_user_state ("
              + "app_name VARCHAR(255) NOT NULL, "
              + "user_id VARCHAR(255) NOT NULL, "
              + "state_key VARCHAR(255) NOT NULL, "
              + "state_value JSON, "
              + "PRIMARY KEY (app_name, user_id, state_key))");
    }

    sessionService = new MySqlSessionService(dataSource);
  }

  @Test
  public void lifecycle_noSession() {
    assertThat(
            sessionService
                .getSession("app-name", "user-id", "session-id", Optional.empty())
                .blockingGet())
        .isNull();

    assertThat(sessionService.listSessions("app-name", "user-id").blockingGet().sessions())
        .isEmpty();

    assertThat(
            sessionService.listEvents("app-name", "user-id", "session-id").blockingGet().events())
        .isEmpty();
  }

  @Test
  public void lifecycle_createSession() {
    Single<Session> sessionSingle = sessionService.createSession("app-name", "user-id");

    Session session = sessionSingle.blockingGet();

    assertThat(session.id()).isNotNull();
    assertThat(session.appName()).isEqualTo("app-name");
    assertThat(session.userId()).isEqualTo("user-id");
    assertThat(session.state()).isEmpty();
  }

  @Test
  public void lifecycle_getSession() {
    Session session = sessionService.createSession("app-name", "user-id").blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();

    assertThat(retrievedSession).isNotNull();
    assertThat(retrievedSession.id()).isEqualTo(session.id());
  }

  @Test
  public void lifecycle_listSessions() {
    Session session = sessionService.createSession("app-name", "user-id").blockingGet();

    ListSessionsResponse response =
        sessionService.listSessions(session.appName(), session.userId()).blockingGet();

    assertThat(response.sessions()).hasSize(1);
    assertThat(response.sessions().get(0).id()).isEqualTo(session.id());
  }

  @Test
  public void lifecycle_deleteSession() {
    Session session = sessionService.createSession("app-name", "user-id").blockingGet();

    sessionService.deleteSession(session.appName(), session.userId(), session.id()).blockingAwait();

    assertThat(
            sessionService
                .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet())
        .isNull();
  }

  @Test
  public void appendEvent_updatesSessionState() {
    Session session =
        sessionService
            .createSession("app", "user", new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("sessionKey", "sessionValue");
    stateDelta.put(State.APP_PREFIX + "appKey", "appValue");
    stateDelta.put(State.USER_PREFIX + "userKey", "userValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString()) // Assign a unique ID
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    assertThat(session.state()).containsEntry("sessionKey", "sessionValue");

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();

    assertThat(retrievedSession.state()).containsEntry("sessionKey", "sessionValue");
    assertThat(retrievedSession.state()).containsEntry(State.APP_PREFIX + "appKey", "appValue");
    assertThat(retrievedSession.state()).containsEntry(State.USER_PREFIX + "userKey", "userValue");
  }

  @Test
  public void listSessions_includesGlobalState() {
    Session session =
        sessionService
            .createSession("app", "user", new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.APP_PREFIX + "appKey", "appValue");
    stateDelta.put(State.USER_PREFIX + "userKey", "userValue");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    ListSessionsResponse response = sessionService.listSessions("app", "user").blockingGet();

    assertThat(response.sessions()).hasSize(1);
    Session listedSession = response.sessions().get(0);

    // This asserts that the list operation merged the global state correctly
    assertThat(listedSession.state()).containsEntry(State.APP_PREFIX + "appKey", "appValue");
    assertThat(listedSession.state()).containsEntry(State.USER_PREFIX + "userKey", "userValue");
  }

  @Test
  public void storesCleanKeysInDb_integration() throws Exception {
    Session session =
        sessionService
            .createSession("app", "user", new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.APP_PREFIX + "cleanAppKey", "val1");
    stateDelta.put(State.USER_PREFIX + "cleanUserKey", "val2");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    // Verify DB storage directly
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      // Check App State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_app_state WHERE app_name = 'app' AND state_key ="
                  + " 'cleanAppKey'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("state_key")).isEqualTo("cleanAppKey"); // Should NOT have prefix
      }

      // Check User State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_user_state WHERE app_name = 'app' AND user_id = 'user'"
                  + " AND state_key = 'cleanUserKey'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("state_key")).isEqualTo("cleanUserKey"); // Should NOT have prefix
      }
    }

    // Verify retrieval adds prefix back
    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();

    assertThat(retrievedSession.state()).containsEntry(State.APP_PREFIX + "cleanAppKey", "val1");
    assertThat(retrievedSession.state()).containsEntry(State.USER_PREFIX + "cleanUserKey", "val2");
  }

  @Test
  public void removesKeysFromDb_integration() throws Exception {
    Session session =
        sessionService
            .createSession("app", "user", new ConcurrentHashMap<>(), "session1")
            .blockingGet();

    // 1. Setup existing state in DB directly
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES ('app',"
              + " 'keyToRemove', '\"val\"')");
      stmt.execute(
          "INSERT INTO adk_user_state (app_name, user_id, state_key, state_value) VALUES ('app',"
              + " 'user', 'keyToRemove', '\"val\"')");
    }

    // 2. Create event with REMOVED delta
    ConcurrentMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put(State.APP_PREFIX + "keyToRemove", State.REMOVED);
    stateDelta.put(State.USER_PREFIX + "keyToRemove", State.REMOVED);

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session, event).blockingGet();

    // 3. Verify deletion in DB
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      // Check App State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_app_state WHERE app_name = 'app' AND state_key ="
                  + " 'keyToRemove'")) {
        assertThat(rs.next()).isFalse();
      }

      // Check User State
      try (java.sql.ResultSet rs =
          stmt.executeQuery(
              "SELECT state_key FROM adk_user_state WHERE app_name = 'app' AND user_id = 'user'"
                  + " AND state_key = 'keyToRemove'")) {
        assertThat(rs.next()).isFalse();
      }
    }

    // 4. Verify retrieval does not have keys
    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();

    assertThat(retrievedSession.state()).doesNotContainKey(State.APP_PREFIX + "keyToRemove");
    assertThat(retrievedSession.state()).doesNotContainKey(State.USER_PREFIX + "keyToRemove");
  }
}
