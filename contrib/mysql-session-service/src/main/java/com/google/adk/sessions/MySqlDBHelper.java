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

import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  private final DataSource dataSource;
  private final ObjectMapper objectMapper;

  public MySqlDBHelper(DataSource dataSource) {
    this.dataSource = dataSource;
    this.objectMapper = JsonBaseModel.getMapper();
  }

  public Completable saveSession(Session session) {
    return Completable.fromAction(
        () -> {
          String sql =
              "INSERT INTO adk_sessions (session_id, app_name, user_id, state, created_at,"
                  + " updated_at) VALUES (?, ?, ?, ?, ?, ?)";
          try (Connection conn = dataSource.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // Filter out global state keys to prevent shadowing
            Map<String, Object> localStateOnly =
                session.state().entrySet().stream()
                    .filter(
                        e ->
                            !e.getKey().startsWith(State.APP_PREFIX)
                                && !e.getKey().startsWith(State.USER_PREFIX))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            String stateJson = objectMapper.writeValueAsString(localStateOnly);

            pstmt.setString(1, session.id());
            pstmt.setString(2, session.appName());
            pstmt.setString(3, session.userId());
            pstmt.setString(4, stateJson);
            pstmt.setTimestamp(5, Timestamp.from(session.lastUpdateTime()));
            pstmt.setTimestamp(6, Timestamp.from(session.lastUpdateTime()));

            pstmt.executeUpdate();
          } catch (SQLException e) {
            if (e.getErrorCode() == 1062
                || (e.getSQLState() != null
                    && e.getSQLState().startsWith("23"))) { // Duplicate entry
              throw new SessionException("Session with id " + session.id() + " already exists.", e);
            }
            throw new SessionException("Failed to save session", e);
          } catch (JsonProcessingException e) {
            throw new SessionException("Failed to serialize session state", e);
          }
        });
  }

  public Completable appendEventAndUpdateState(Session session, Event event) {
    return Completable.fromAction(
        () -> {
          String insertEventSql =
              "INSERT INTO adk_events (event_id, session_id, event_data, created_at) VALUES (?,"
                  + " ?, ?, ?)";
          String updateSessionSql =
              "UPDATE adk_sessions SET updated_at = ?, state = ? WHERE session_id = ? AND updated_at"
                  + " = ?";

          String upsertAppStateSql =
              "INSERT INTO adk_app_state (app_name, state_key, state_value) VALUES (?, ?, ?)"
                  + " ON DUPLICATE KEY UPDATE state_value = ?";
          String upsertUserStateSql =
              "INSERT INTO adk_user_state (app_name, user_id, state_key, state_value) VALUES"
                  + " (?, ?, ?, ?) ON DUPLICATE KEY UPDATE state_value = ?";

          String deleteAppStateSql =
              "DELETE FROM adk_app_state WHERE app_name = ? AND state_key = ?";
          String deleteUserStateSql =
              "DELETE FROM adk_user_state WHERE app_name = ? AND user_id = ? AND state_key = ?";

          Connection conn = null;
          try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // 1. Insert the event
            try (PreparedStatement pstmt = conn.prepareStatement(insertEventSql)) {
              String eventJson = objectMapper.writeValueAsString(event);
              pstmt.setString(1, event.id());
              pstmt.setString(2, session.id());
              pstmt.setString(3, eventJson);
              pstmt.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(event.timestamp())));
              pstmt.executeUpdate();
            }

            // 2. Handle Global State Delta (App/User)
            if (event.actions() != null && event.actions().stateDelta() != null) {
              try (PreparedStatement upsertAppStmt = conn.prepareStatement(upsertAppStateSql);
                  PreparedStatement upsertUserStmt = conn.prepareStatement(upsertUserStateSql);
                  PreparedStatement deleteAppStmt = conn.prepareStatement(deleteAppStateSql);
                  PreparedStatement deleteUserStmt = conn.prepareStatement(deleteUserStateSql)) {

                for (Map.Entry<String, Object> entry : event.actions().stateDelta().entrySet()) {
                  String key = entry.getKey();
                  Object value = entry.getValue();

                  if (key.startsWith(State.USER_PREFIX)) {
                    // User State
                    String dbKey = key.substring(State.USER_PREFIX.length());
                    if (value == null || value == State.REMOVED) {
                      deleteUserStmt.setString(1, session.appName());
                      deleteUserStmt.setString(2, session.userId());
                      deleteUserStmt.setString(3, dbKey);
                      deleteUserStmt.addBatch();
                    } else {
                      String valueJson = objectMapper.writeValueAsString(value);
                      upsertUserStmt.setString(1, session.appName());
                      upsertUserStmt.setString(2, session.userId());
                      upsertUserStmt.setString(3, dbKey);
                      upsertUserStmt.setString(4, valueJson);
                      upsertUserStmt.setString(5, valueJson);
                      upsertUserStmt.addBatch();
                    }
                  } else if (key.startsWith(State.APP_PREFIX)) {
                    // App State
                    String dbKey = key.substring(State.APP_PREFIX.length());
                    if (value == null || value == State.REMOVED) {
                      deleteAppStmt.setString(1, session.appName());
                      deleteAppStmt.setString(2, dbKey);
                      deleteAppStmt.addBatch();
                    } else {
                      String valueJson = objectMapper.writeValueAsString(value);
                      upsertAppStmt.setString(1, session.appName());
                      upsertAppStmt.setString(2, dbKey);
                      upsertAppStmt.setString(3, valueJson);
                      upsertAppStmt.setString(4, valueJson);
                      upsertAppStmt.addBatch();
                    }
                  }
                }
                upsertUserStmt.executeBatch();
                upsertAppStmt.executeBatch();
                deleteUserStmt.executeBatch();
                deleteAppStmt.executeBatch();
              }
            }

            // 3. Update session (Last Update Time AND State)
            // Capture the new timestamp to be used for DB and Session object
            Instant newLastUpdateTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            try (PreparedStatement pstmt = conn.prepareStatement(updateSessionSql)) {
              // Start with local-only keys from the current session state (filtering out global
              // keys)
              Map<String, Object> localStateOnly =
                  session.state().entrySet().stream()
                      .filter(
                          e ->
                              !e.getKey().startsWith(State.APP_PREFIX)
                                  && !e.getKey().startsWith(State.USER_PREFIX))
                      .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

              // Merge session-local deltas from the event into the map before serializing.
              // This is necessary because the session object itself is updated only AFTER commit.
              if (event.actions() != null && event.actions().stateDelta() != null) {
                event
                    .actions()
                    .stateDelta()
                    .forEach(
                        (k, v) -> {
                          if (!k.startsWith(State.APP_PREFIX) && !k.startsWith(State.USER_PREFIX)) {
                            if (v == null || v == State.REMOVED) {
                              localStateOnly.remove(k);
                            } else {
                              localStateOnly.put(k, v);
                            }
                          }
                        });
              }
              String currentSessionStateJson = objectMapper.writeValueAsString(localStateOnly);

              pstmt.setTimestamp(1, Timestamp.from(newLastUpdateTime));
              pstmt.setString(2, currentSessionStateJson);
              pstmt.setString(3, session.id());
              pstmt.setTimestamp(4, Timestamp.from(session.lastUpdateTime()));
              int updatedRows = pstmt.executeUpdate();
              if (updatedRows == 0) {
                throw new java.util.ConcurrentModificationException(
                    "Session has been modified by another transaction");
              }
            }

            conn.commit();
            // Update the session object with the new timestamp so subsequent calls use the
            // correct version
            session.lastUpdateTime(newLastUpdateTime);
          } catch (SQLException | JsonProcessingException e) {
            if (conn != null) {
              try {
                conn.rollback();
              } catch (SQLException ex) {
                e.addSuppressed(ex);
              }
            }
            throw new SessionException("Failed to append event and update state", e);
          } finally {
            if (conn != null) {
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
        });
  }

  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    return Maybe.fromCallable(
        () -> {
          String sessionSql =
              "SELECT * FROM adk_sessions WHERE session_id = ? AND app_name = ? AND user_id = ?";

          GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
          StringBuilder eventsSqlBuilder =
              new StringBuilder("SELECT event_data FROM adk_events WHERE session_id = ?");

          config.afterTimestamp().ifPresent(ts -> eventsSqlBuilder.append(" AND created_at > ?"));

          if (config.numRecentEvents().isPresent()) {
            eventsSqlBuilder.append(" ORDER BY created_at DESC LIMIT ?");
          } else {
            eventsSqlBuilder.append(" ORDER BY created_at ASC");
          }

          try (Connection conn = dataSource.getConnection()) {
            Session session = null;
            ConcurrentHashMap<String, Object> finalState = new ConcurrentHashMap<>();

            // 1. Get Global App State
            try (PreparedStatement pstmt =
                conn.prepareStatement(
                    "SELECT state_key, state_value FROM adk_app_state WHERE app_name = ?")) {
              pstmt.setString(1, appName);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  finalState.put(
                      State.APP_PREFIX + rs.getString("state_key"),
                      objectMapper.readValue(rs.getString("state_value"), Object.class));
                }
              }
            }

            // 2. Get Global User State
            try (PreparedStatement pstmt =
                conn.prepareStatement(
                    "SELECT state_key, state_value FROM adk_user_state WHERE app_name = ? AND"
                        + " user_id = ?")) {
              pstmt.setString(1, appName);
              pstmt.setString(2, userId);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  finalState.put(
                      State.USER_PREFIX + rs.getString("state_key"),
                      objectMapper.readValue(rs.getString("state_value"), Object.class));
                }
              }
            }

            try (PreparedStatement sessionPstmt = conn.prepareStatement(sessionSql)) {
              sessionPstmt.setString(1, sessionId);
              sessionPstmt.setString(2, appName);
              sessionPstmt.setString(3, userId);
              try (ResultSet rs = sessionPstmt.executeQuery()) {
                if (rs.next()) {
                  // 3. Merge Session State
                  String stateJson = rs.getString("state");
                  Map<String, Object> sessionStateMap =
                      stateJson != null ? objectMapper.readValue(stateJson, Map.class) : Map.of();

                  ConcurrentHashMap<String, Object> mergedState = new ConcurrentHashMap<>();
                  finalState.forEach(
                      (k, v) -> {
                        if (v != null) mergedState.put(k, v);
                      });
                  sessionStateMap.forEach(
                      (k, v) -> {
                        if (v != null) mergedState.put(k, v);
                      });
                  finalState = mergedState;

                  Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
                  session =
                      Session.builder(rs.getString("session_id"))
                          .appName(rs.getString("app_name"))
                          .userId(rs.getString("user_id"))
                          .state(new State(finalState))
                          .events(new ArrayList<>())
                          .lastUpdateTime(updatedAt)
                          .build();
                }
              }
            }

            if (session != null) {
              try (PreparedStatement eventsPstmt =
                  conn.prepareStatement(eventsSqlBuilder.toString())) {
                int paramIndex = 1;
                eventsPstmt.setString(paramIndex++, sessionId);

                if (config.afterTimestamp().isPresent()) {
                  eventsPstmt.setTimestamp(
                      paramIndex++, Timestamp.from(config.afterTimestamp().get()));
                }
                if (config.numRecentEvents().isPresent()) {
                  eventsPstmt.setInt(paramIndex++, config.numRecentEvents().get());
                }

                try (ResultSet rs = eventsPstmt.executeQuery()) {
                  while (rs.next()) {
                    Event event = objectMapper.readValue(rs.getString("event_data"), Event.class);
                    session.events().add(event);
                  }
                }
              }
              // If we fetched in DESC order to get the last N, we must reverse to restore
              // chronological order.
              if (config.numRecentEvents().isPresent()) {
                java.util.Collections.reverse(session.events());
              }
              return session;
            }
            return null; // Will complete the Maybe
          } catch (SQLException | JsonProcessingException e) {
            throw new SessionException("Failed to get session", e);
          }
        });
  }

  public Single<List<Event>> listEvents(String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          String sql =
              "SELECT e.event_data FROM adk_events e JOIN adk_sessions s ON e.session_id ="
                  + " s.session_id WHERE s.session_id = ? AND s.app_name = ? AND s.user_id = ?"
                  + " ORDER BY e.created_at ASC";
          List<Event> events = new ArrayList<>();
          try (Connection conn = dataSource.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, appName);
            pstmt.setString(3, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
              while (rs.next()) {
                Event event = objectMapper.readValue(rs.getString("event_data"), Event.class);
                events.add(event);
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
              "SELECT session_id, app_name, user_id, created_at, updated_at, state FROM"
                  + " adk_sessions WHERE app_name = ? AND user_id = ? ORDER BY created_at DESC";
          List<Session> sessions = new ArrayList<>();
          try (Connection conn = dataSource.getConnection()) {
            ConcurrentHashMap<String, Object> globalState = new ConcurrentHashMap<>();

            // 1. Get Global App State
            try (PreparedStatement pstmt =
                conn.prepareStatement(
                    "SELECT state_key, state_value FROM adk_app_state WHERE app_name = ?")) {
              pstmt.setString(1, appName);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  globalState.put(
                      State.APP_PREFIX + rs.getString("state_key"),
                      objectMapper.readValue(rs.getString("state_value"), Object.class));
                }
              }
            }

            // 2. Get Global User State
            try (PreparedStatement pstmt =
                conn.prepareStatement(
                    "SELECT state_key, state_value FROM adk_user_state WHERE app_name = ? AND"
                        + " user_id = ?")) {
              pstmt.setString(1, appName);
              pstmt.setString(2, userId);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  globalState.put(
                      State.USER_PREFIX + rs.getString("state_key"),
                      objectMapper.readValue(rs.getString("state_value"), Object.class));
                }
              }
            }

            // 3. Get Sessions
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
              pstmt.setString(1, appName);
              pstmt.setString(2, userId);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
                  String stateJson = rs.getString("state");
                  Map<String, Object> sessionStateMap =
                      stateJson != null ? objectMapper.readValue(stateJson, Map.class) : Map.of();

                  ConcurrentHashMap<String, Object> mergedState = new ConcurrentHashMap<>();
                  globalState.forEach(
                      (k, v) -> {
                        if (v != null) mergedState.put(k, v);
                      });
                  sessionStateMap.forEach(
                      (k, v) -> {
                        if (v != null) mergedState.put(k, v);
                      });

                  sessions.add(
                      Session.builder(rs.getString("session_id"))
                          .appName(rs.getString("app_name"))
                          .userId(rs.getString("user_id"))
                          .state(new State(mergedState))
                          .events(new ArrayList<>())
                          .lastUpdateTime(updatedAt)
                          .build());
                }
              }
            }
          } catch (SQLException e) {
            throw new SessionException("Failed to list sessions", e);
          }
          return sessions;
        });
  }

  public Single<ConcurrentHashMap<String, Object>> getInitialState(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
          try (Connection conn = dataSource.getConnection()) {
            // 1. Get Global App State
            try (PreparedStatement pstmt =
                conn.prepareStatement(
                    "SELECT state_key, state_value FROM adk_app_state WHERE app_name = ?")) {
              pstmt.setString(1, appName);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  initialState.put(
                      State.APP_PREFIX + rs.getString("state_key"),
                      objectMapper.readValue(rs.getString("state_value"), Object.class));
                }
              }
            }

            // 2. Get Global User State and merge it, overwriting app state if keys conflict
            try (PreparedStatement pstmt =
                conn.prepareStatement(
                    "SELECT state_key, state_value FROM adk_user_state WHERE app_name = ? AND"
                        + " user_id = ?")) {
              pstmt.setString(1, appName);
              pstmt.setString(2, userId);
              try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                  initialState.put(
                      State.USER_PREFIX + rs.getString("state_key"),
                      objectMapper.readValue(rs.getString("state_value"), Object.class));
                }
              }
            }
          } catch (SQLException | JsonProcessingException e) {
            throw new SessionException("Failed to get initial state", e);
          }
          return initialState;
        });
  }

  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          String deleteSessionSql =
              "DELETE FROM adk_sessions WHERE session_id = ? AND app_name = ? AND user_id = ?";
          try (Connection conn = dataSource.getConnection();
              PreparedStatement pstmt = conn.prepareStatement(deleteSessionSql)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, appName);
            pstmt.setString(3, userId);
            pstmt.executeUpdate();
          } catch (SQLException e) {
            throw new SessionException("Failed to delete session", e);
          }
        });
  }
}
