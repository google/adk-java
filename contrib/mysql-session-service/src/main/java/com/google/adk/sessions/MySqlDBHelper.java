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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class for MySQL database operations. */
public class MySqlDBHelper {

  private static final Logger logger = LoggerFactory.getLogger(MySqlDBHelper.class);
  private static final String LEGACY_REMOVED_SENTINEL = "__ADK_SENTINEL_REMOVED__";
  private static final int EVENT_FORMAT_VERSION = 2;

  private static final String UPSERT_APP_STATE_SQL =
      "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES (?, ?, ?)"
          + " ON DUPLICATE KEY UPDATE state_value = ?";
  private static final String UPSERT_USER_STATE_SQL =
      "INSERT INTO adk_user_state (app_name, user_id, state_key, state_value) VALUES"
          + " (?, ?, ?, ?) ON DUPLICATE KEY UPDATE state_value = ?";
  private static final String DELETE_APP_STATE_SQL =
      "DELETE FROM adk_app_state WHERE app_name = ? AND state_key = ?";
  private static final String DELETE_USER_STATE_SQL =
      "DELETE FROM adk_user_state WHERE app_name = ? AND user_id = ? AND state_key = ?";

  private final DataSource dataSource;
  private final ObjectMapper objectMapper;

  public MySqlDBHelper(DataSource dataSource) {
    this.dataSource = dataSource;
    this.objectMapper = JsonBaseModel.getMapper();
  }

  /** Saves a session and any app- or user-scoped values supplied in its initial state. */
  public Completable saveSession(Session session) {
    return Completable.fromAction(
        () -> {
          String insertSessionSql =
              "INSERT INTO adk_sessions (app_name, user_id, session_id, state, created_at,"
                  + " updated_at) VALUES (?, ?, ?, ?, ?, ?)";
          Connection conn = null;
          try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(insertSessionSql)) {
              String stateJson = objectMapper.writeValueAsString(localState(session.state()));
              pstmt.setString(1, session.appName());
              pstmt.setString(2, session.userId());
              pstmt.setString(3, session.id());
              pstmt.setString(4, stateJson);
              pstmt.setTimestamp(5, Timestamp.from(session.lastUpdateTime()));
              pstmt.setTimestamp(6, Timestamp.from(session.lastUpdateTime()));
              pstmt.executeUpdate();
            }

            applyGlobalStateChanges(conn, session.appName(), session.userId(), session.state());
            conn.commit();
          } catch (Exception e) {
            rollback(conn, e);
            if (e instanceof SQLException sqlException && isDuplicateKey(sqlException)) {
              throw new SessionException(
                  "Session " + session.sessionKey() + " already exists.", sqlException);
            }
            if (e instanceof SessionException sessionException) {
              throw sessionException;
            }
            throw new SessionException("Failed to save session", e);
          } finally {
            closeTransactionConnection(conn);
          }
        });
  }

  /** Persists an event and all resulting state changes in one transaction. */
  public Completable appendEventAndUpdateState(Session session, Event event) {
    return Completable.fromAction(
        () -> {
          String insertEventSql =
              "INSERT INTO adk_events (app_name, user_id, session_id, event_id, event_data,"
                  + " created_at) VALUES (?, ?, ?, ?, ?, ?)";
          String updateSessionSql =
              "UPDATE adk_sessions SET updated_at = ?, state = ? WHERE app_name = ? AND user_id ="
                  + " ? AND session_id = ? AND updated_at = ?";

          Connection conn = null;
          try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(insertEventSql)) {
              pstmt.setString(1, session.appName());
              pstmt.setString(2, session.userId());
              pstmt.setString(3, session.id());
              pstmt.setString(4, event.id());
              pstmt.setString(5, serializeEvent(event));
              pstmt.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(event.timestamp())));
              pstmt.executeUpdate();
            }

            Map<String, Object> stateDelta =
                event.actions() == null ? Map.of() : event.actions().stateDelta();
            applyGlobalStateChanges(conn, session.appName(), session.userId(), stateDelta);

            Map<String, Object> localState = localState(session.state());
            applyLocalStateChanges(localState, stateDelta);
            Instant newLastUpdateTime = nextUpdateTime(session.lastUpdateTime());
            try (PreparedStatement pstmt = conn.prepareStatement(updateSessionSql)) {
              pstmt.setTimestamp(1, Timestamp.from(newLastUpdateTime));
              pstmt.setString(2, objectMapper.writeValueAsString(localState));
              pstmt.setString(3, session.appName());
              pstmt.setString(4, session.userId());
              pstmt.setString(5, session.id());
              pstmt.setTimestamp(6, Timestamp.from(session.lastUpdateTime()));
              if (pstmt.executeUpdate() == 0) {
                throw new java.util.ConcurrentModificationException(
                    "Session has been modified by another transaction");
              }
            }

            conn.commit();
            session.lastUpdateTime(newLastUpdateTime);
          } catch (Exception e) {
            rollback(conn, e);
            if (e instanceof java.util.ConcurrentModificationException concurrentModification) {
              throw concurrentModification;
            }
            if (e instanceof SessionException sessionException) {
              throw sessionException;
            }
            throw new SessionException("Failed to append event and update state", e);
          } finally {
            closeTransactionConnection(conn);
          }
        });
  }

  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    return Maybe.fromCallable(
        () -> {
          String sessionSql =
              "SELECT session_id, app_name, user_id, updated_at, state FROM adk_sessions WHERE"
                  + " app_name = ? AND user_id = ? AND session_id = ?";
          GetSessionConfig config = configOpt.orElseGet(() -> GetSessionConfig.builder().build());

          try (Connection conn = dataSource.getConnection();
              PreparedStatement sessionPstmt = conn.prepareStatement(sessionSql)) {
            sessionPstmt.setString(1, appName);
            sessionPstmt.setString(2, userId);
            sessionPstmt.setString(3, sessionId);

            Session session;
            try (ResultSet rs = sessionPstmt.executeQuery()) {
              if (!rs.next()) {
                return null;
              }
              Map<String, Object> mergedState = loadGlobalState(conn, appName, userId);
              mergeNonNullValues(mergedState, readState(rs.getString("state")));
              session = sessionFromRow(rs, mergedState, new ArrayList<>());
            }

            loadEvents(conn, appName, userId, sessionId, config, session.events());
            return session;
          } catch (SQLException | JsonProcessingException e) {
            throw new SessionException("Failed to get session", e);
          }
        });
  }

  public Single<List<Event>> listEvents(String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          String sql =
              "SELECT event_data FROM adk_events WHERE app_name = ? AND user_id = ? AND"
                  + " session_id = ? ORDER BY created_at ASC, event_sequence ASC";
          List<Event> events = new ArrayList<>();
          try (Connection conn = dataSource.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, appName);
            pstmt.setString(2, userId);
            pstmt.setString(3, sessionId);
            try (ResultSet rs = pstmt.executeQuery()) {
              while (rs.next()) {
                events.add(deserializeEvent(rs.getString("event_data")));
              }
            }
          } catch (SQLException | JsonProcessingException e) {
            throw new SessionException("Failed to list events", e);
          }
          return events;
        });
  }

  public Single<List<Session>> listSessions(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          String sql =
              "SELECT session_id, app_name, user_id, updated_at, state FROM adk_sessions WHERE"
                  + " app_name = ? AND user_id = ? ORDER BY created_at DESC, session_id ASC";
          List<Session> sessions = new ArrayList<>();
          try (Connection conn = dataSource.getConnection()) {
            Map<String, Object> globalState = loadGlobalState(conn, appName, userId);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
              pstmt.setString(1, appName);
              pstmt.setString(2, userId);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  Map<String, Object> mergedState = new HashMap<>(globalState);
                  mergeNonNullValues(mergedState, readState(rs.getString("state")));
                  sessions.add(sessionFromRow(rs, mergedState, new ArrayList<>()));
                }
              }
            }
          } catch (SQLException | JsonProcessingException e) {
            throw new SessionException("Failed to list sessions", e);
          }
          return sessions;
        });
  }

  public Single<ConcurrentHashMap<String, Object>> getInitialState(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          try (Connection conn = dataSource.getConnection()) {
            return new ConcurrentHashMap<>(loadGlobalState(conn, appName, userId));
          } catch (SQLException | JsonProcessingException e) {
            throw new SessionException("Failed to get initial state", e);
          }
        });
  }

  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          String sql =
              "DELETE FROM adk_sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
          try (Connection conn = dataSource.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, appName);
            pstmt.setString(2, userId);
            pstmt.setString(3, sessionId);
            pstmt.executeUpdate();
          } catch (SQLException e) {
            throw new SessionException("Failed to delete session", e);
          }
        });
  }

  private void loadEvents(
      Connection conn,
      String appName,
      String userId,
      String sessionId,
      GetSessionConfig config,
      List<Event> destination)
      throws SQLException, JsonProcessingException {
    StringBuilder sql =
        new StringBuilder(
            "SELECT event_data FROM adk_events WHERE app_name = ? AND user_id = ? AND"
                + " session_id = ?");
    config.afterTimestamp().ifPresent(unused -> sql.append(" AND created_at >= ?"));
    if (config.numRecentEvents().isPresent()) {
      sql.append(" ORDER BY created_at DESC, event_sequence DESC LIMIT ?");
    } else {
      sql.append(" ORDER BY created_at ASC, event_sequence ASC");
    }

    try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
      int index = 1;
      pstmt.setString(index++, appName);
      pstmt.setString(index++, userId);
      pstmt.setString(index++, sessionId);
      if (config.afterTimestamp().isPresent()) {
        pstmt.setTimestamp(index++, Timestamp.from(config.afterTimestamp().get()));
      }
      if (config.numRecentEvents().isPresent()) {
        pstmt.setInt(index, config.numRecentEvents().get());
      }
      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          destination.add(deserializeEvent(rs.getString("event_data")));
        }
      }
    }
    if (config.numRecentEvents().isPresent()) {
      Collections.reverse(destination);
    }
  }

  private Map<String, Object> loadGlobalState(Connection conn, String appName, String userId)
      throws SQLException, JsonProcessingException {
    Map<String, Object> state = new HashMap<>();
    try (PreparedStatement pstmt =
        conn.prepareStatement(
            "SELECT state_key, state_value FROM adk_app_state WHERE app_name = ?")) {
      pstmt.setString(1, appName);
      loadStateRows(pstmt, State.APP_PREFIX, state);
    }
    try (PreparedStatement pstmt =
        conn.prepareStatement(
            "SELECT state_key, state_value FROM adk_user_state WHERE app_name = ? AND user_id ="
                + " ?")) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      loadStateRows(pstmt, State.USER_PREFIX, state);
    }
    return state;
  }

  private void loadStateRows(
      PreparedStatement pstmt, String prefix, Map<String, Object> destination)
      throws SQLException, JsonProcessingException {
    try (ResultSet rs = pstmt.executeQuery()) {
      while (rs.next()) {
        Object value = objectMapper.readValue(rs.getString("state_value"), Object.class);
        if (value != null) {
          destination.put(prefix + rs.getString("state_key"), value);
        }
      }
    }
  }

  private void applyGlobalStateChanges(
      Connection conn, String appName, String userId, Map<String, Object> stateDelta)
      throws SQLException, JsonProcessingException {
    if (stateDelta == null || stateDelta.isEmpty()) {
      return;
    }
    try (PreparedStatement upsertApp = conn.prepareStatement(UPSERT_APP_STATE_SQL);
        PreparedStatement upsertUser = conn.prepareStatement(UPSERT_USER_STATE_SQL);
        PreparedStatement deleteApp = conn.prepareStatement(DELETE_APP_STATE_SQL);
        PreparedStatement deleteUser = conn.prepareStatement(DELETE_USER_STATE_SQL)) {
      for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if (key.startsWith(State.APP_PREFIX)) {
          String dbKey = key.substring(State.APP_PREFIX.length());
          if (isRemoved(value)) {
            deleteApp.setString(1, appName);
            deleteApp.setString(2, dbKey);
            deleteApp.addBatch();
          } else {
            String valueJson = objectMapper.writeValueAsString(value);
            upsertApp.setString(1, appName);
            upsertApp.setString(2, dbKey);
            upsertApp.setString(3, valueJson);
            upsertApp.setString(4, valueJson);
            upsertApp.addBatch();
          }
        } else if (key.startsWith(State.USER_PREFIX)) {
          String dbKey = key.substring(State.USER_PREFIX.length());
          if (isRemoved(value)) {
            deleteUser.setString(1, appName);
            deleteUser.setString(2, userId);
            deleteUser.setString(3, dbKey);
            deleteUser.addBatch();
          } else {
            String valueJson = objectMapper.writeValueAsString(value);
            upsertUser.setString(1, appName);
            upsertUser.setString(2, userId);
            upsertUser.setString(3, dbKey);
            upsertUser.setString(4, valueJson);
            upsertUser.setString(5, valueJson);
            upsertUser.addBatch();
          }
        }
      }
      upsertApp.executeBatch();
      upsertUser.executeBatch();
      deleteApp.executeBatch();
      deleteUser.executeBatch();
    }
  }

  private String serializeEvent(Event event) throws JsonProcessingException {
    JsonNode eventJson = objectMapper.valueToTree(event);
    JsonNode stateDelta = eventJson.path("actions").path("stateDelta");
    if (stateDelta instanceof ObjectNode stateDeltaObject && event.actions() != null) {
      event
          .actions()
          .stateDelta()
          .forEach(
              (key, value) -> {
                if (value == State.REMOVED) {
                  stateDeltaObject.putNull(key);
                }
              });
    }
    ObjectNode storedEvent = objectMapper.createObjectNode();
    storedEvent.put("formatVersion", EVENT_FORMAT_VERSION);
    storedEvent.set("event", eventJson);
    return objectMapper.writeValueAsString(storedEvent);
  }

  private Event deserializeEvent(String eventJson) throws JsonProcessingException {
    JsonNode storedEvent = objectMapper.readTree(eventJson);
    boolean currentFormat =
        storedEvent.path("formatVersion").asInt() == EVENT_FORMAT_VERSION
            && storedEvent.has("event");
    JsonNode serializedEvent = currentFormat ? storedEvent.get("event") : storedEvent;
    Event event = objectMapper.treeToValue(serializedEvent, Event.class);
    if (!currentFormat && event.actions() != null) {
      event
          .actions()
          .stateDelta()
          .replaceAll(
              (unused, value) -> LEGACY_REMOVED_SENTINEL.equals(value) ? State.REMOVED : value);
    }
    return event;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readState(String stateJson) throws JsonProcessingException {
    return stateJson == null ? Map.of() : objectMapper.readValue(stateJson, Map.class);
  }

  private static Session sessionFromRow(ResultSet rs, Map<String, Object> state, List<Event> events)
      throws SQLException {
    return Session.builder(rs.getString("session_id"))
        .appName(rs.getString("app_name"))
        .userId(rs.getString("user_id"))
        .state(new State(state))
        .events(events)
        .lastUpdateTime(rs.getTimestamp("updated_at").toInstant())
        .build();
  }

  private static Map<String, Object> localState(Map<String, Object> state) {
    Map<String, Object> localState = new HashMap<>();
    state.forEach(
        (key, value) -> {
          if (!isGlobalOrTemporary(key) && !isRemoved(value)) {
            localState.put(key, value);
          }
        });
    return localState;
  }

  private static void applyLocalStateChanges(
      Map<String, Object> localState, Map<String, Object> stateDelta) {
    if (stateDelta == null) {
      return;
    }
    stateDelta.forEach(
        (key, value) -> {
          if (!isGlobalOrTemporary(key)) {
            if (isRemoved(value)) {
              localState.remove(key);
            } else {
              localState.put(key, value);
            }
          }
        });
  }

  private static boolean isGlobalOrTemporary(String key) {
    return key.startsWith(State.APP_PREFIX)
        || key.startsWith(State.USER_PREFIX)
        || key.startsWith(State.TEMP_PREFIX);
  }

  private static boolean isRemoved(Object value) {
    return value == null || value == State.REMOVED;
  }

  private static void mergeNonNullValues(
      Map<String, Object> destination, Map<String, Object> values) {
    values.forEach(
        (key, value) -> {
          if (value != null && value != State.REMOVED) {
            destination.put(key, value);
          }
        });
  }

  private static Instant nextUpdateTime(Instant previousUpdateTime) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return now.isAfter(previousUpdateTime) ? now : previousUpdateTime.plusMillis(1);
  }

  private static boolean isDuplicateKey(SQLException e) {
    return e.getErrorCode() == 1062 || "23505".equals(e.getSQLState());
  }

  private static void rollback(Connection conn, Exception original) {
    if (conn == null) {
      return;
    }
    try {
      conn.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  private static void closeTransactionConnection(Connection conn) {
    if (conn == null) {
      return;
    }
    try {
      conn.setAutoCommit(true);
    } catch (SQLException e) {
      logger.warn("Failed to reset auto-commit", e);
    }
    try {
      conn.close();
    } catch (SQLException e) {
      logger.warn("Failed to close connection", e);
    }
  }
}
