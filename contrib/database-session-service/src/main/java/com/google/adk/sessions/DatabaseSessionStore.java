/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import com.google.adk.events.Event;
import com.google.adk.sessions.DatabaseDiagnostics.Phase;
import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The SQL of the session service: one short transaction per operation, and the mapping between rows
 * and {@link Session}s. No pool, executor, or ORM.
 *
 * <p><b>Tables.</b> A session's state is kept in three JSON documents, as in the Python v1 schema:
 * {@code app_states} (one row per app, the {@code app:} keys without their prefix), {@code
 * user_states} (one row per app and user, the {@code user:} keys) and {@code sessions.state} (the
 * unprefixed keys). {@code events} holds the history, one row per event, with the whole event as
 * JSON in {@code event_data}.
 *
 * <p><b>Transactions.</b> Every operation borrows one connection, runs inside {@link #transaction},
 * and returns the connection in the state it was borrowed in. Reads see one snapshot; writes lock
 * exactly the rows they change.
 *
 * <p><b>Lock order.</b> Writers take row locks in the order app, user, session, and skip the scopes
 * they do not change. Keeping to one order is what keeps two writers from deadlocking, so a new
 * statement that locks a row has to respect it. SQLite has no row locks; there a write transaction
 * is the only writer of the file ({@code BEGIN IMMEDIATE}).
 *
 * <p><b>Errors.</b> Callers get a {@link SessionException} whose message this class wrote. A
 * rejection that the caller can act on (duplicate, missing, stale) travels as {@link Rejected};
 * anything from the driver is reported by {@link DatabaseDiagnostics} and never passed on.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
final class DatabaseSessionStore {
  /** Primary key of {@code sessions}; bound by {@link #bindScope}. */
  private static final String SCOPE = "app_name=? AND user_id=? AND id=?";

  /** The same key as it appears in {@code events}, where the session's ID is a foreign key. */
  private static final String EVENT_SCOPE = "app_name=? AND user_id=? AND session_id=?";

  /**
   * One session together with the two shared documents it is merged with. The joins are outer so
   * that a missing shared row shows up as a NULL document, which fails to decode, instead of making
   * the session itself look absent. A {@code WHERE} clause on {@code s} follows.
   */
  private static final String VIEW =
      "SELECT s.id,s.state AS local_state,s.update_time,"
          + "a.state AS app_state,u.state AS user_state FROM sessions s "
          + "LEFT JOIN app_states a ON a.app_name=s.app_name "
          + "LEFT JOIN user_states u ON u.app_name=s.app_name AND u.user_id=s.user_id ";

  /** Message of a failure that left nothing behind and can be answered by calling again. */
  static final String CONFLICT =
      "The database rolled the operation back because of a concurrent transaction;"
          + " nothing was written, so it can be retried.";

  /**
   * Both shared documents of one app and user in a single row, for a listing: joining them to every
   * session, as {@link #VIEW} does for one, would send the same two documents once per session. A
   * scalar subquery yields NULL for a missing row.
   */
  private static final String SHARED =
      "SELECT (SELECT state FROM app_states WHERE app_name=?),"
          + "(SELECT state FROM user_states WHERE app_name=? AND user_id=?)";

  private final DataSource source;
  private final Dialect dialect;
  private final int timeoutSeconds;
  private final SessionDataCodec codec = new SessionDataCodec();

  /**
   * @param source the application's data source; a connection is borrowed per operation and never
   *     kept
   * @param dialect the engine behind {@code source}
   * @param timeoutSeconds limit for each statement, and for SQLite's wait on a competing writer
   */
  DatabaseSessionStore(DataSource source, Dialect dialect, int timeoutSeconds) {
    this.source = source;
    this.dialect = dialect;
    this.timeoutSeconds = timeoutSeconds;
  }

  /**
   * Inserts a session and writes the {@code app:} and {@code user:} keys of its initial state to
   * the shared documents, creating their rows if this is the first session of the app or user.
   *
   * @param state the initial state with prefixed keys; {@code temp:} keys are ignored
   * @param now creation time, already at storage precision
   * @return the session as stored, with the shared state merged in
   * @throws SessionException with {@link SessionException#SESSION_ALREADY_EXISTS} if the key is
   *     taken, in which case the shared documents are left untouched as well
   */
  Session create(SessionKey key, Map<String, Object> state, Instant now) {
    SessionDataCodec.ScopedState split = codec.splitState(state);
    return transaction(
        "create",
        true,
        connection -> {
          // A fast rejection is safe here; the insert still enforces uniqueness under races.
          if (sessionExists(connection, key)) {
            throw new Rejected(Reason.DUPLICATE);
          }
          // App before user, the lock order of this class. Both rows are created here, once, so
          // that an append can lock a row that is certain to exist instead of racing to insert it.
          ensureShared(connection, key, false);
          updateShared(connection, key, false, split.app());
          ensureShared(connection, key, true);
          updateShared(connection, key, true, split.user());
          try (PreparedStatement insert =
              prepare(
                  connection,
                  "INSERT INTO sessions (app_name,user_id,id,state,create_time,update_time) VALUES (?,?,?,?,?,?)")) {
            bindScope(insert, key);
            DatabaseDialect.bindJson(insert, 4, codec.writeState(split.local()), dialect);
            DatabaseTime.bind(insert, 5, now, dialect);
            DatabaseTime.bind(insert, 6, now, dialect);
            insert.executeUpdate();
          } catch (SQLException collision) {
            // Two creators of the same key can both pass the check above. The loser fails on the
            // primary key: unique_violation on PostgreSQL, ER_DUP_ENTRY on MySQL and MariaDB.
            // SQLite cannot get here, since its write transaction excludes the other creator.
            if ((dialect == Dialect.POSTGRESQL && "23505".equals(collision.getSQLState()))
                || ((dialect == Dialect.MYSQL || dialect == Dialect.MARIADB)
                    && collision.getErrorCode() == 1062)) {
              throw new Rejected(Reason.DUPLICATE);
            }
            throw collision;
          }
          // Read the row back rather than echo the arguments: the caller gets what the database
          // holds, including shared keys that other sessions wrote earlier.
          return load(connection, key);
        });
  }

  /**
   * Reads a session with its merged state and the part of its history that {@code config} asks for,
   * all from one snapshot.
   *
   * @return the session, or {@code null} if there is none under this key
   */
  @Nullable Session get(SessionKey key, GetSessionConfig config) {
    return transaction(
        "get",
        false,
        connection -> {
          Session session = load(connection, key);
          if (session != null) {
            session.addEvents(events(connection, key, config));
          }
          return session;
        });
  }

  /**
   * Lists the sessions of one user with their merged state and without their history, the least
   * recently updated first and by ID within the same update time.
   *
   * <p>Two statements whatever the number of sessions: the session rows, then the shared state
   * once.
   */
  List<Session> list(String app, String user) {
    return transaction(
        "list",
        false,
        connection -> {
          // The rows are held until the shared state is known; a session cannot be built without.
          record Row(String id, String state, Instant updateTime) {}
          List<Row> rows = new ArrayList<>();
          try (PreparedStatement query =
              prepare(
                  connection,
                  "SELECT id,state,update_time FROM sessions WHERE app_name=? AND user_id=?"
                      + " ORDER BY update_time,id")) {
            query.setString(1, app);
            query.setString(2, user);
            try (ResultSet found = query.executeQuery()) {
              while (found.next()) {
                rows.add(
                    new Row(
                        found.getString(1),
                        found.getString(2),
                        DatabaseTime.read(found, "update_time", dialect)));
              }
            }
          }
          List<Session> sessions = new ArrayList<>(rows.size());
          if (rows.isEmpty()) {
            // A user without sessions may have no shared rows at all; do not go looking for them.
            return sessions;
          }
          // The read transaction gives both statements the same snapshot.
          String appState;
          String userState;
          try (PreparedStatement query = prepare(connection, SHARED)) {
            query.setString(1, app);
            query.setString(2, app);
            query.setString(3, user);
            try (ResultSet shared = query.executeQuery()) {
              if (!shared.next()) {
                throw new SQLException();
              }
              appState = shared.getString(1);
              userState = shared.getString(2);
            }
          }
          for (Row row : rows) {
            sessions.add(
                session(row.id(), app, user, row.updateTime(), appState, userState, row.state()));
          }
          return sessions;
        });
  }

  /**
   * Deletes a session and, through the foreign key's {@code ON DELETE CASCADE}, its events. The
   * shared app and user documents stay: other sessions read them.
   *
   * @throws SessionNotFoundException if there is no session under this key
   */
  void delete(SessionKey key) {
    transaction(
        "delete",
        true,
        connection -> {
          try (PreparedStatement delete =
              prepare(connection, "DELETE FROM sessions WHERE " + SCOPE)) {
            bindScope(delete, key);
            if (delete.executeUpdate() == 0) {
              throw new Rejected(Reason.MISSING);
            }
          }
          return null;
        });
  }

  /**
   * Reads the whole history of a session in append order.
   *
   * @throws SessionNotFoundException if there is no session under this key, which an empty history
   *     alone could not tell apart from a session without events
   */
  List<Event> listEvents(SessionKey key) {
    return transaction(
        "listEvents",
        false,
        connection -> {
          if (!sessionExists(connection, key)) {
            throw new Rejected(Reason.MISSING);
          }
          return events(connection, key, GetSessionConfig.builder().build());
        });
  }

  /**
   * Appends one event and applies its state delta, atomically: the event row, the session's {@code
   * update_time} and state, and the shared documents its delta touches are committed together or
   * not at all.
   *
   * <p>The writer has to have seen the current end of the history. Its last known event is compared
   * with the stored one while the session row is locked, so of two writers that loaded the same
   * history the second is rejected rather than appended after an event it never saw.
   *
   * @param expectedTailId the ID of the last event in the caller's copy of the session, or {@code
   *     null} if that copy has no events
   * @param liveDelta the event's state delta as it was when the call started, with prefixed keys
   * @param timestamp the event's time at storage precision; it becomes the session's {@code
   *     update_time}
   * @param invocationId the value of the {@code invocation_id} column, empty if the event has none
   * @throws SessionNotFoundException if the session does not exist
   * @throws SessionException if the caller's history is stale, or the session already holds an
   *     event with this ID
   */
  void append(
      SessionKey key,
      @Nullable String expectedTailId,
      Event event,
      Map<String, Object> liveDelta,
      Instant timestamp,
      String invocationId) {
    // Encode before borrowing a connection: an event that cannot be written should not cost a
    // transaction, and no lock is held while JSON is produced.
    String json = codec.writeEvent(event);
    SessionDataCodec.ScopedState delta = codec.splitState(liveDelta);
    transaction(
        "append",
        true,
        connection -> {
          // Global lock order is app -> user -> session, matching create. Unchanged scopes are not
          // locked, ensured, or updated. In particular a local event never locks the app-wide row.
          updateShared(connection, key, false, delta.app());
          updateShared(connection, key, true, delta.user());
          // Most events carry no local delta. Lock the session either way, but leave its state
          // document unread and unwritten then: rewriting it would cost a full decode, encode and
          // row/TOAST write per event, growing with the size of the session state.
          boolean localChanged = !delta.local().isEmpty();
          Map<String, Object> local = Map.of();
          try (PreparedStatement query =
              prepare(
                  connection,
                  "SELECT "
                      + (localChanged ? "state" : "1")
                      + " FROM sessions WHERE "
                      + SCOPE
                      + DatabaseDialect.lock(dialect))) {
            bindScope(query, key);
            try (ResultSet row = query.executeQuery()) {
              if (!row.next()) {
                throw new Rejected(Reason.MISSING);
              }
              if (localChanged) {
                local = codec.readState(row.getString(1));
              }
            }
          }
          // The locked session (or SQLite's write transaction) serializes tail reads and inserts.
          long next = nextSequence(connection, key, expectedTailId);
          try (PreparedStatement update =
              prepare(
                  connection,
                  "UPDATE sessions SET "
                      + (localChanged ? "state=?," : "")
                      + "update_time=? WHERE "
                      + SCOPE)) {
            int index = 1;
            if (localChanged) {
              DatabaseDialect.bindJson(
                  update,
                  index++,
                  codec.writeState(codec.applyState(local, delta.local())),
                  dialect);
            }
            DatabaseTime.bind(update, index++, timestamp, dialect);
            bindScope(update, index, key);
            // The session exists and is locked; a no-op update can report zero changed rows.
            update.executeUpdate();
          }
          // An event ID may be used once per session (the primary key of events). PostgreSQL and
          // SQLite are asked to skip a duplicate, which shows as zero inserted rows; on PostgreSQL
          // a failed statement would otherwise abort the whole transaction. MySQL and MariaDB have
          // no ON CONFLICT clause and report ER_DUP_ENTRY, which is caught below.
          boolean mysqlFamily = dialect == Dialect.MYSQL || dialect == Dialect.MARIADB;
          String conflict =
              mysqlFamily ? "" : " ON CONFLICT (id,app_name,user_id,session_id) DO NOTHING";
          try (PreparedStatement insert =
              prepare(
                  connection,
                  "INSERT INTO events (app_name,user_id,session_id,event_sequence,id,invocation_id,timestamp,event_data) VALUES (?,?,?,?,?,?,?,?)"
                      + conflict)) {
            bindScope(insert, key);
            insert.setLong(4, next);
            insert.setString(5, event.id());
            insert.setString(6, invocationId);
            DatabaseTime.bind(insert, 7, timestamp, dialect);
            DatabaseDialect.bindJson(insert, 8, json, dialect);
            if (insert.executeUpdate() == 0) {
              throw new Rejected(Reason.DUPLICATE_EVENT);
            }
          } catch (SQLException collision) {
            if (mysqlFamily && collision.getErrorCode() == 1062) {
              throw new Rejected(Reason.DUPLICATE_EVENT);
            }
            throw collision;
          }
          return null;
        });
  }

  /** Reads one session with its merged state and no events; {@code null} if it does not exist. */
  private @Nullable Session load(Connection connection, SessionKey key) throws SQLException {
    try (PreparedStatement query =
        prepare(connection, VIEW + "WHERE s.app_name=? AND s.user_id=? AND s.id=?")) {
      bindScope(query, key);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next()
            ? session(
                rows.getString("id"),
                key.appName(),
                key.userId(),
                DatabaseTime.read(rows, "update_time", dialect),
                rows.getString("app_state"),
                rows.getString("user_state"),
                rows.getString("local_state"))
            : null;
      }
    }
  }

  /**
   * Builds a session from its three stored documents. They are decoded for this session alone, so
   * that no two sessions share a value, and a NULL document (a missing shared row) fails to decode
   * instead of being read as empty state.
   */
  private Session session(
      String id,
      String app,
      String user,
      Instant updateTime,
      @Nullable String appState,
      @Nullable String userState,
      @Nullable String localState) {
    return Session.builder(id)
        .appName(app)
        .userId(user)
        .lastUpdateTime(updateTime)
        .state(
            codec.mergeState(
                codec.readState(appState), codec.readState(userState), codec.readState(localState)))
        .build();
  }

  /**
   * Reads a session's events in append order, filtered the way {@code InMemorySessionService}
   * filters them: first keep the last {@code numRecentEvents} appended, then drop those older than
   * {@code afterTimestamp}. An event at exactly that time is kept.
   *
   * <p>The statement has one of two shapes, each with or without the time predicate in brackets:
   *
   * <pre>{@code
   * SELECT event_data FROM events WHERE scope [AND timestamp>=?] ORDER BY event_sequence
   *
   * SELECT event_data FROM (
   *     SELECT ... FROM events WHERE scope ORDER BY event_sequence DESC LIMIT ?) recent
   *   [WHERE timestamp>=?] ORDER BY event_sequence
   * }</pre>
   *
   * <p>Order and limit use {@code event_sequence}, never {@code timestamp}: event times have
   * millisecond resolution, tie often, and may run backwards.
   */
  private List<Event> events(Connection connection, SessionKey key, GetSessionConfig config)
      throws SQLException {
    List<Event> events = new ArrayList<>();
    if (config.numRecentEvents().orElse(-1) == 0) {
      // Asked for the session without history; there is nothing to query.
      return events;
    }
    Instant lowerBound = null;
    if (config.afterTimestamp().isPresent()) {
      Instant cursor = config.afterTimestamp().orElseThrow();
      // A cursor outside the range a column can hold cannot be bound. Past the range no stored
      // event can match; before it every stored event does, so no predicate is needed.
      if (cursor.isAfter(DatabaseTime.MAX)) {
        return events;
      }
      if (cursor.isAfter(DatabaseTime.MIN)) {
        // Columns hold microseconds. Rounding the cursor up keeps ">=" exact for a cursor with
        // nanoseconds: an event in the same microsecond but before the cursor is left out.
        lowerBound = DatabaseTime.ceilToMicros(cursor);
      }
    }
    boolean limited = config.numRecentEvents().isPresent();
    // The subquery limits append positions BEFORE timestamp filtering; pushing the predicate into
    // that subquery changes the Java contract for out-of-order timestamps.
    String sql =
        limited
            ? "SELECT event_data FROM (SELECT event_sequence,timestamp,event_data FROM events WHERE "
                + EVENT_SCOPE
                + " ORDER BY event_sequence DESC LIMIT ?) recent"
            : "SELECT event_data FROM events WHERE " + EVENT_SCOPE;
    if (lowerBound != null) {
      sql += (limited ? " WHERE " : " AND ") + "timestamp>=?";
    }
    sql += " ORDER BY event_sequence";
    try (PreparedStatement query = prepare(connection, sql)) {
      // Parameters in the order they appear in either shape: the scope, the limit, the cursor.
      bindScope(query, key);
      int index = 4;
      if (limited) {
        query.setInt(index++, config.numRecentEvents().orElseThrow());
      }
      if (lowerBound != null) {
        DatabaseTime.bind(query, index, lowerBound, dialect);
      }
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          events.add(codec.readEvent(rows.getString(1)));
        }
      }
    }
    return events;
  }

  /** Whether a session row exists, without locking it. */
  private boolean sessionExists(Connection connection, SessionKey key) throws SQLException {
    try (PreparedStatement query = prepare(connection, "SELECT 1 FROM sessions WHERE " + SCOPE)) {
      bindScope(query, key);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  /**
   * Checks that the caller's last event is the stored last event, and returns the position for the
   * next one. Positions start at 1 and are dense within a session.
   *
   * <p>Call it only while the session row is locked (on SQLite, inside the write transaction): that
   * is what keeps a second writer from reading the same tail and taking the same position. The
   * unique constraint on the position is a second line of defense, not the mechanism.
   *
   * @param expectedTailId the caller's last event ID, or {@code null} for an empty history
   */
  private long nextSequence(Connection connection, SessionKey key, @Nullable String expectedTailId)
      throws SQLException {
    try (PreparedStatement query =
        prepare(
            connection,
            "SELECT id,event_sequence FROM events WHERE "
                + EVENT_SCOPE
                + " ORDER BY event_sequence DESC LIMIT 1")) {
      bindScope(query, key);
      try (ResultSet rows = query.executeQuery()) {
        boolean hasTail = rows.next();
        String actual = hasTail ? rows.getString("id") : null;
        // Both empty, or the same last event. Anything else means the caller's copy is behind
        // the stored history, or was loaded with a filter that cut its end off.
        if (!Objects.equals(actual, expectedTailId)) {
          throw new Rejected(Reason.STALE);
        }
        long sequence = hasTail ? rows.getLong("event_sequence") : 0;
        if (sequence == Long.MAX_VALUE) {
          throw new Rejected(Reason.SEQUENCE_EXHAUSTED);
        }
        return sequence + 1;
      }
    }
  }

  /**
   * Makes sure the app-wide or per-user state row exists, inserting an empty document if it does
   * not. Two sessions created at the same moment may both try; the insert is written so that the
   * second one waits for the first and then does nothing.
   *
   * @param user {@code true} for the user's row in {@code user_states}, {@code false} for the app's
   *     row in {@code app_states}
   */
  private void ensureShared(Connection connection, SessionKey key, boolean user)
      throws SQLException {
    String table = user ? "user_states" : "app_states";
    if (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB) {
      // InnoDB takes an exclusive lock even for a no-op duplicate-key update. Existing shared
      // rows are never deleted by the service, so skip the upsert on a successful consistent read.
      String scope = "app_name=?" + (user ? " AND user_id=?" : "");
      try (PreparedStatement query =
          prepare(connection, "SELECT 1 FROM " + table + " WHERE " + scope)) {
        bindShared(query, 1, key, user);
        try (ResultSet rows = query.executeQuery()) {
          if (rows.next()) {
            return;
          }
        }
      }
    }
    try (PreparedStatement insert =
        prepare(connection, DatabaseDialect.insertShared(table, user, dialect))) {
      insert.setString(1, key.appName());
      int stateIndex = 2;
      if (user) {
        insert.setString(2, key.userId());
        stateIndex++;
      }
      DatabaseDialect.bindJson(insert, stateIndex, "{}", dialect);
      insert.executeUpdate();
    }
  }

  /**
   * Applies a delta to the app-wide or per-user document: lock the row, read the document, change
   * the keys of the delta, write the document back. The lock is what keeps two sessions that change
   * different keys of the same document from overwriting each other.
   *
   * <p>An empty delta does nothing at all. That is the common case, and the reason an ordinary
   * event never waits on the one row that every session of an app shares.
   *
   * @param user {@code true} for {@code user_states}, {@code false} for {@code app_states}
   * @param delta keys without their scope prefix; a {@link State#REMOVED} value deletes the key
   */
  private void updateShared(
      Connection connection, SessionKey key, boolean user, Map<String, Object> delta)
      throws SQLException {
    if (delta.isEmpty()) {
      return;
    }
    String table = user ? "user_states" : "app_states";
    String scope = "app_name=?" + (user ? " AND user_id=?" : "");
    Map<String, Object> current;
    try (PreparedStatement query =
        prepare(
            connection,
            "SELECT state FROM " + table + " WHERE " + scope + DatabaseDialect.lock(dialect))) {
      bindShared(query, 1, key, user);
      try (ResultSet rows = query.executeQuery()) {
        if (!rows.next()) {
          // createSession made this row and nothing in the service deletes it, so its absence
          // means the tables were changed from outside. Fail rather than quietly recreate it.
          throw new SQLException();
        }
        current = codec.readState(rows.getString(1));
      }
    }
    // update_time is the database's own UTC clock, not the event's time: it records when the
    // shared document last changed, whichever session and whichever application host wrote it.
    try (PreparedStatement update =
        prepare(
            connection,
            "UPDATE "
                + table
                + " SET state=?,update_time="
                + DatabaseDialect.utcNow(dialect)
                + " WHERE "
                + scope)) {
      DatabaseDialect.bindJson(
          update, 1, codec.writeState(codec.applyState(current, delta)), dialect);
      bindShared(update, 2, key, user);
      update.executeUpdate();
    }
  }

  /**
   * Binds the key of a shared row from {@code index} on: the app, then the user if there is one.
   */
  private void bindShared(PreparedStatement statement, int index, SessionKey key, boolean user)
      throws SQLException {
    statement.setString(index, key.appName());
    if (user) {
      statement.setString(index + 1, key.userId());
    }
  }

  /** Binds the three parts of a session key as the first three parameters. */
  private void bindScope(PreparedStatement statement, SessionKey key) throws SQLException {
    bindScope(statement, 1, key);
  }

  /** Binds app, user and session ID, in the order of {@link #SCOPE}, from {@code index} on. */
  private void bindScope(PreparedStatement statement, int index, SessionKey key)
      throws SQLException {
    statement.setString(index, key.appName());
    statement.setString(index + 1, key.userId());
    statement.setString(index + 2, key.id());
  }

  /**
   * Prepares a statement with the configured timeout. Every statement of this class goes through
   * here; SQL text is built only from constants of this module, and all values are bound.
   */
  private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      statement.setQueryTimeout(timeoutSeconds);
      return statement;
    } catch (SQLException | RuntimeException failure) {
      // The caller's try-with-resources never received the statement, so close it here.
      statement.close();
      throw failure;
    }
  }

  /**
   * Runs {@code work} in one transaction on a borrowed connection and maps every outcome to what a
   * caller may see.
   *
   * <p>In order: borrow; require a connection that is not inside someone else's transaction; set
   * the isolation level and begin; check that the schema is the one this version writes; run the
   * work; commit; put the connection's settings back; close.
   *
   * <ul>
   *   <li><b>Isolation.</b> A read uses REPEATABLE READ so that its statements see one snapshot. A
   *       write uses READ COMMITTED and explicit row locks. With a snapshot taken at its start, a
   *       writer that had to wait for a row lock would afterwards still read the history as it was
   *       before the wait (MySQL, MariaDB) or be refused outright (PostgreSQL). SQLite gets its
   *       consistency from being a single writer.
   *   <li><b>SQLite.</b> The transaction is opened with an explicit {@code BEGIN}, immediate for a
   *       write so that the file's write lock is taken before anything is read. Foreign keys are
   *       switched on, because the cascade from a session to its events depends on a per-connection
   *       setting that is off by default.
   *   <li><b>Schema check.</b> The metadata row is read inside the transaction, every time, so that
   *       a process still running after the schema has been migrated to a newer version stops
   *       instead of writing the old format into it.
   *   <li><b>Commit.</b> Whether the commit was not attempted, sent, or acknowledged decides what
   *       the caller is told: nothing written, possibly written, or written.
   *   <li><b>Cleanup.</b> Auto-commit, isolation and the SQLite settings are restored to what they
   *       were at borrow time, so the pool gets back the connection it handed out. If that fails
   *       the connection is aborted rather than returned in an unknown state.
   * </ul>
   *
   * @param operation name for diagnostics, such as {@code "append"}
   * @param write whether the work changes data; decides the isolation level and the lock taken on
   *     SQLite, and whether a failed commit is ambiguous
   * @throws SessionNotFoundException if the work rejected a missing session
   * @throws SessionException for every other failure, with a message written by this class
   */
  private <T> T transaction(String operation, boolean write, SqlWork<T> work) {
    Phase phase = Phase.CONNECTION;
    CommitOutcome commitOutcome = CommitOutcome.NOT_ATTEMPTED;
    // phase and commitOutcome are read by the catch blocks at the end: they say how far the
    // transaction got when it failed, which is what the message to the caller depends on.
    try (Connection connection = source.getConnection()) {
      phase = Phase.SETUP;
      DatabaseDiagnostics.requireConnection(connection, write);
      int isolation = connection.getTransactionIsolation();
      int required =
          dialect == Dialect.SQLITE
              ? Connection.TRANSACTION_SERIALIZABLE
              : write
                  ? Connection.TRANSACTION_READ_COMMITTED
                  : Connection.TRANSACTION_REPEATABLE_READ;
      // A server connection already at the required level needs neither the change nor the
      // restore, each a round trip of its own. SQLite's level is driver-side state that is cheap
      // to assert, so it is always set.
      boolean changeIsolation = dialect == Dialect.SQLITE || isolation != required;
      // The SQLite settings as borrowed, to restore afterwards; -1 until they have been read.
      int foreignKeys = -1;
      int busyTimeout = -1;
      boolean begun = false;
      @Nullable Throwable operationFailure = null;
      try {
        if (changeIsolation) {
          connection.setTransactionIsolation(required);
        }
        if (dialect == Dialect.SQLITE) {
          foreignKeys = pragma(connection, "foreign_keys");
          busyTimeout = pragma(connection, "busy_timeout");
          control(connection, "PRAGMA foreign_keys=ON");
          control(connection, "PRAGMA busy_timeout=" + timeoutSeconds * 1000);
          // SQLite accepts this PRAGMA silently when it cannot honor it, for instance inside an
          // open transaction or in a build without foreign keys. Deleting a session would then
          // leave its events behind, so read the setting back.
          if (pragma(connection, "foreign_keys") != 1) {
            throw new SQLException();
          }
          // Do not combine manual BEGIN with setAutoCommit(false): Xerial would issue nested BEGIN.
          control(connection, write ? "BEGIN IMMEDIATE" : "BEGIN");
        } else {
          connection.setAutoCommit(false);
        }
        begun = true;
        phase = Phase.SCHEMA;
        try (PreparedStatement query = prepare(connection, DatabaseDialect.metadataQuery(dialect));
            ResultSet metadata = query.executeQuery()) {
          DatabaseSchemaMigrator.requireMetadata(metadata);
        }
        phase = Phase.STATEMENT;
        T result = work.run(connection);
        phase = Phase.COMMIT;
        // UNCONFIRMED is set before the commit is sent and CONFIRMED only once it returns. A
        // failure in between leaves UNCONFIRMED: the server may have committed and the reply
        // been lost, so the caller must not be told that nothing was written.
        if (dialect == Dialect.SQLITE) {
          try (Statement commit = connection.createStatement()) {
            commitOutcome = CommitOutcome.UNCONFIRMED;
            commit.execute("COMMIT");
            // Closing the statement is cleanup, not part of the commit acknowledgement.
            commitOutcome = CommitOutcome.CONFIRMED;
            phase = Phase.CLEANUP;
          }
        } else {
          commitOutcome = CommitOutcome.UNCONFIRMED;
          connection.commit();
          commitOutcome = CommitOutcome.CONFIRMED;
          phase = Phase.CLEANUP;
        }
        return result;
      } catch (SQLException | RuntimeException failure) {
        operationFailure = failure;
        throw failure;
      } finally {
        // Runs on success and on failure alike: undo what was not committed, then give the
        // connection back the settings it was borrowed with.
        try {
          if (begun && commitOutcome != CommitOutcome.CONFIRMED) {
            if (dialect == Dialect.SQLITE) {
              control(connection, "ROLLBACK");
            } else {
              connection.rollback();
            }
          }
          if (dialect == Dialect.SQLITE) {
            if (foreignKeys >= 0) {
              control(connection, "PRAGMA foreign_keys=" + foreignKeys);
            }
            if (busyTimeout >= 0) {
              control(connection, "PRAGMA busy_timeout=" + busyTimeout);
            }
          } else {
            connection.setAutoCommit(true);
          }
          if (changeIsolation) {
            connection.setTransactionIsolation(isolation);
          }
        } catch (SQLException | RuntimeException cleanupFailure) {
          // Never enable auto-commit after a failed rollback: that could commit a partial write.
          try {
            connection.abort(Runnable::run);
          } catch (SQLException | RuntimeException ignored) {
            // Closing the borrowed connection is still attempted by try-with-resources.
          }
          // With no failure so far, or only a rejection the caller could have acted on, the
          // cleanup failure is the one to report: the connection is broken and the caller should
          // know. Otherwise the earlier failure stays the reported one and this is only logged.
          if (operationFailure == null
              || operationFailure instanceof Rejected
              || operationFailure instanceof DatabaseDiagnostics.Configuration) {
            phase = Phase.CLEANUP;
            throw cleanupFailure;
          }
          // Report cleanup separately without masking the original statement/commit failure.
          DatabaseDiagnostics.report(operation, Phase.CLEANUP, dialect, cleanupFailure);
        }
      }
    } catch (Rejected rejection) {
      // Rejections are decisions of this class, not database failures: nothing is logged, and
      // the messages are fixed texts without any of the caller's data.
      throw switch (rejection.reason) {
        case DUPLICATE -> new SessionException(SessionException.SESSION_ALREADY_EXISTS);
        case DUPLICATE_EVENT ->
            new SessionException("An event with this ID already exists in the session.");
        case MISSING -> new SessionNotFoundException("Session not found.");
        case STALE ->
            new SessionException(
                "Session history is stale or filtered; reload the session before appending.");
        case SEQUENCE_EXHAUSTED -> new SessionException("Session event sequence is exhausted.");
      };
    } catch (DatabaseDiagnostics.Configuration configuration) {
      throw new SessionException(configuration.getMessage());
    } catch (SessionDataCodec.StorageFailure failure) {
      // A resource close may have attached an unsafe suppressed driver exception.
      throw DatabaseDiagnostics.failure(operation, phase, dialect, failure.getMessage(), failure);
    } catch (SQLException | RuntimeException failure) {
      // What the caller needs to know first is whether the write happened. Only when it certainly
      // did not is the phase that failed worth naming.
      String message;
      if (write && commitOutcome == CommitOutcome.CONFIRMED) {
        message = "The database write committed, but cleanup failed; reload the session.";
      } else if (write && commitOutcome == CommitOutcome.UNCONFIRMED) {
        message = "The database write may have committed; reload the session before retrying.";
      } else if (commitOutcome == CommitOutcome.NOT_ATTEMPTED
          && DatabaseDiagnostics.concurrencyConflict(failure, dialect)) {
        // Nothing was committed and the supplied session is unchanged, so the same call can be
        // repeated as it is.
        message = CONFLICT;
      } else {
        message =
            switch (phase) {
              case CONNECTION -> "Unable to borrow a database connection.";
              case SETUP -> "Unable to configure the database session transaction.";
              case SCHEMA ->
                  "Unable to verify the ADK schema; check the database and run migrations if needed.";
              case CLEANUP -> "Unable to restore the borrowed database connection.";
              default -> "Unable to complete the database session operation.";
            };
      }
      throw DatabaseDiagnostics.failure(operation, phase, dialect, message, failure);
    }
  }

  /** Reads the current integer value of a SQLite per-connection setting. */
  private static int pragma(Connection connection, String name) throws SQLException {
    try (Statement query = connection.createStatement();
        ResultSet rows = query.executeQuery("PRAGMA " + name)) {
      if (!rows.next()) {
        throw new SQLException();
      }
      return rows.getInt(1);
    }
  }

  /**
   * Executes a SQLite transaction or settings command. These take no parameters, and their text
   * comes only from this class and from integers it read from the connection.
   */
  private static void control(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** How far a commit got, which is what a caller of a failed write most needs to know. */
  private enum CommitOutcome {
    /** The transaction failed, or is still running, before any commit was sent. */
    NOT_ATTEMPTED,
    /** The commit was sent and no answer has been seen: it may or may not have been applied. */
    UNCONFIRMED,
    /** The database acknowledged the commit; a later failure can only concern cleanup. */
    CONFIRMED
  }

  /** Why an operation was refused although the database itself worked. */
  private enum Reason {
    /** A session with this key exists. */
    DUPLICATE,
    /** The session already has an event with this ID. */
    DUPLICATE_EVENT,
    /** There is no session with this key. */
    MISSING,
    /** The caller's last event is not the stored last event. */
    STALE,
    /** The session has used every position a {@code BIGINT} can number. */
    SEQUENCE_EXHAUSTED
  }

  /**
   * Thrown inside {@link SqlWork} to abandon the transaction for a {@link Reason}. It is caught in
   * {@link #transaction}, after the rollback, and turned into the exception the caller sees; it
   * never leaves this class.
   */
  private static final class Rejected extends RuntimeException {
    final Reason reason;

    Rejected(Reason reason) {
      this.reason = reason;
    }
  }

  /** The statements of one operation, run by {@link #transaction} between begin and commit. */
  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
