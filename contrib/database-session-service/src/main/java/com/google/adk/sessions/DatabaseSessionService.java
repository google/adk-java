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
import com.google.adk.platform.UuidProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * JDBC session persistence for SQLite, PostgreSQL, MySQL and MariaDB using an application-owned
 * {@link DataSource}.
 *
 * <p>Construction never borrows a connection, runs migrations, or creates a connection pool. The
 * application remains responsible for the lifetime of its data source and its providers.
 *
 * <pre>{@code
 * DatabaseSessionService sessions = new DatabaseSessionService(dataSource);
 * sessions.migrate().blockingAwait(); // once, before workers start
 *
 * Runner runner =
 *     Runner.builder().appName("my-app").agent(agent).sessionService(sessions).build();
 * }</pre>
 *
 * <p><b>Execution.</b> Every operation is cold: nothing happens until the returned {@code Single},
 * {@code Maybe} or {@code Completable} is subscribed, and each subscription runs the operation
 * again. The JDBC calls block the subscribing thread; move them with {@code subscribeOn} if that
 * thread must not block.
 *
 * <p><b>State.</b> Keys with the {@link State#APP_PREFIX} and {@link State#USER_PREFIX} prefixes
 * are shared by all sessions of the app, or of the app and user; other keys belong to one session.
 * Keys with {@link State#TEMP_PREFIX} are never stored.
 *
 * <p><b>Errors.</b> Operations fail only with {@link SessionException} or {@link
 * SessionNotFoundException}. Their messages are written by this module: they never quote a driver
 * message, an identifier, or a payload, and they carry no cause. What the driver reported is in the
 * log.
 *
 * <p>Instances are safe to share between threads. One instance per data source is enough.
 */
public final class DatabaseSessionService implements BaseSessionService {

  /** Database engines with built-in SQL dialects. */
  public enum Dialect {
    /** SQLite through a file-backed database; one writer at a time. */
    SQLITE,
    /** PostgreSQL. */
    POSTGRESQL,
    /** MySQL with InnoDB. */
    MYSQL,
    /** MariaDB with InnoDB. Its SQL differs from MySQL's in the collation of the keys. */
    MARIADB
  }

  private static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 15;
  // SQLite takes its busy timeout in milliseconds as an int.
  private static final int MAX_STATEMENT_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1000;

  private final DataSource dataSource;
  private final InstantSource instantSource;
  private final UuidProvider uuidProvider;
  private final boolean autoMigrate;
  private final int statementTimeoutSeconds;

  // The three fields below are resolved on first use, not at construction, and are guarded by
  // this instance's monitor.
  private @Nullable Dialect resolvedDialect;
  private boolean initialized;
  private @Nullable DatabaseSessionStore store;

  /**
   * Configures a service with migrations disabled and lazy JDBC metadata dialect detection.
   *
   * <p>The schema must already exist when the first operation runs: call {@link #migrate()} once,
   * or create the tables with the SQL shipped in this module.
   *
   * @throws SessionException if the data source is null
   */
  public DatabaseSessionService(DataSource dataSource) {
    this(builder(dataSource));
  }

  private DatabaseSessionService(Builder builder) {
    this.dataSource = builder.dataSource;
    this.instantSource = builder.instantSource;
    this.uuidProvider = builder.uuidProvider;
    this.autoMigrate = builder.autoMigrate;
    this.statementTimeoutSeconds = builder.statementTimeoutSeconds;
    this.resolvedDialect = builder.dialect;
  }

  /** Returns a builder without accessing the supplied data source. */
  public static Builder builder(DataSource dataSource) {
    return new Builder(dataSource);
  }

  /**
   * Migrates only this module's schema when subscribed. Run once before starting concurrent
   * workers. Does not run application migrations or take ownership of the data source.
   *
   * <p>The migration creates the five session tables and its own history table, {@code
   * adk_schema_history}, in the schema the data source connects to. Repeating it on an up-to-date
   * schema changes nothing. It refuses to touch tables of the same names that it did not create.
   *
   * <p>It needs Flyway on the classpath, together with Flyway's module for the database in use. The
   * user of the data source needs the right to create tables.
   *
   * @return a cold {@code Completable} that fails with {@link SessionException} if the schema could
   *     not be verified or migrated
   */
  public Completable migrate() {
    return Completable.fromAction(this::migrateNow);
  }

  /** Runs the migration now and remembers that this instance needs no automatic one. */
  private synchronized void migrateNow() {
    try {
      DatabaseSchemaMigrator.migrate(dataSource, resolveDialect());
    } catch (LinkageError missingMigrationLibrary) {
      // The migrator is the only class of this module that touches Flyway, so an application
      // that provisions the schema itself gets this far only if it calls migrate() after all.
      throw new SessionException(
          "Unable to load the database migration library; verify the consumer dependencies.");
    }
    initialized = true;
  }

  /**
   * Runs the opted-in migration before the first database operation of this instance. A failed
   * attempt is not remembered, so the next operation tries again.
   */
  synchronized void initialize() {
    if (!initialized) {
      if (autoMigrate) {
        migrateNow();
      }
      initialized = true;
    }
  }

  /**
   * Returns the store, creating it on first use. This is where a lazily detected dialect costs its
   * one borrowed connection.
   */
  private synchronized DatabaseSessionStore store() {
    initialize();
    if (store == null) {
      store = new DatabaseSessionStore(dataSource, resolveDialect(), statementTimeoutSeconds);
    }
    return store;
  }

  /**
   * {@inheritDoc}
   *
   * @deprecated Use {@link #createSession(String, String, Map, String)}, which this delegates to.
   */
  @Override
  @Deprecated
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    return createSession(appName, userId, (Map<String, Object>) state, sessionId);
  }

  /**
   * Creates a session and stores its initial state.
   *
   * <p>{@code app:} and {@code user:} keys of {@code state} are written to the shared state, where
   * every other session of the app or user sees them. {@code temp:} keys are returned on the
   * created session and not stored. A key whose value is {@code null} or {@link State#REMOVED} is
   * not stored, and removes an {@code app:} or {@code user:} key that was shared before.
   *
   * @param appName at most 128 code points; compared exactly, including case and trailing spaces
   * @param userId at most 128 code points, compared exactly
   * @param state the initial state, or {@code null} for none; it is not modified
   * @param sessionId the ID to use, at most 128 code points; {@code null} or blank for a generated
   *     one
   * @return a cold {@code Single} of the created session, whose state includes what the app and
   *     user already shared
   * @throws SessionException signaled through the returned {@code Single}: with {@link
   *     SessionException#SESSION_ALREADY_EXISTS} if the ID is taken for this app and user, with
   *     another message if an argument is invalid or the database fails
   */
  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable Map<String, Object> state,
      @Nullable String sessionId) {
    return Single.fromCallable(
        () -> {
          // As in InMemorySessionService, a blank ID asks for a generated one. An ID with any
          // other content is stored exactly as given, including its surrounding spaces.
          String id = sessionId == null || sessionId.trim().isEmpty() ? newIdentifier() : sessionId;
          SessionKey key = key(appName, userId, id);
          Instant now;
          try {
            now = instantSource.instant();
          } catch (RuntimeException failure) {
            throw new SessionException("Unable to obtain the session creation time.");
          }
          // temp: values go straight from the argument to the returned session. They are kept
          // by reference and never encoded, so they need not be something JSON can express.
          Map<String, Object> temporary = new HashMap<>();
          try {
            if (state != null)
              state.forEach(
                  (name, value) -> {
                    if (name.startsWith(State.TEMP_PREFIX)
                        && value != null
                        && value != State.REMOVED) {
                      temporary.put(name, value);
                    }
                  });
          } catch (RuntimeException invalidState) {
            throw new SessionException("Unable to snapshot initial session state.");
          }
          now = DatabaseTime.forStorage(now);
          Session created = store().create(key, state == null ? Map.of() : state, now);
          created.state().putAll(temporary);
          return created;
        });
  }

  /**
   * Loads a session with its state and history from one consistent snapshot of the database.
   *
   * <p>The state merges the session's own keys with the shared {@code app:} and {@code user:} keys
   * as they are now. The history is in the order the events were appended. With a {@code config},
   * the last {@code numRecentEvents} appended are taken first, and of those the events at or after
   * {@code afterTimestamp} are returned; {@code numRecentEvents(0)} returns the session without
   * history.
   *
   * <p>A session loaded with a filter that leaves out its last event cannot be appended to; see
   * {@link #appendEvent(Session, Event)}.
   *
   * @return a cold {@code Maybe} of a session that shares nothing with other loaded copies; empty
   *     if there is no such session
   * @throws SessionException signaled through the returned {@code Maybe} if an argument is invalid
   *     or the database fails
   */
  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    return Maybe.fromCallable(
        () -> {
          SessionKey key = key(appName, userId, sessionId);
          if (config == null) {
            throw new SessionException("Session configuration cannot be null.");
          }
          return store().get(key, config.orElseGet(() -> GetSessionConfig.builder().build()));
        });
  }

  /**
   * Lists the sessions of one user of one app, with their state and without their events.
   *
   * @return a cold {@code Single} of the sessions ordered by last update, oldest first, then by ID;
   *     an empty list if the user has none
   * @throws SessionException signaled through the returned {@code Single} if an argument is invalid
   *     or the database fails
   */
  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          validateKey(appName);
          validateKey(userId);
          return ListSessionsResponse.builder().sessions(store().list(appName, userId)).build();
        });
  }

  /**
   * Deletes a session and its events. The shared {@code app:} and {@code user:} state is kept,
   * since other sessions use it.
   *
   * @return a cold {@code Completable}
   * @throws SessionNotFoundException signaled through the returned {@code Completable} if there is
   *     no such session
   * @throws SessionException signaled the same way if an argument is invalid or the database fails
   */
  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          SessionKey key = key(appName, userId, sessionId);
          store().delete(key);
        });
  }

  /**
   * Lists every event of a session in the order they were appended. The response carries no page
   * token: the whole history is returned at once.
   *
   * @return a cold {@code Single} of the events
   * @throws SessionNotFoundException signaled through the returned {@code Single} if there is no
   *     such session
   * @throws SessionException signaled the same way if an argument is invalid or the database fails
   */
  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          SessionKey key = key(appName, userId, sessionId);
          return ListEventsResponse.builder().events(store().listEvents(key)).build();
        });
  }

  /**
   * Persists atomically, then updates the supplied session. The supplied history must contain the
   * last stored event (or both histories must be empty); reload a stale or filtered snapshot.
   *
   * <p>In one transaction the event is added to the history, its state delta is applied to the
   * session's own state and to the shared {@code app:} and {@code user:} state, and the session's
   * update time becomes the event's timestamp. Only after the commit is {@code session} changed:
   * the event is added to it and the delta applied to its state. If the call fails, {@code session}
   * is exactly as it was.
   *
   * <p>{@link State#TEMP_PREFIX} keys of the event's delta are applied to the supplied session but
   * never stored, so they are gone when the session is loaded again.
   *
   * <p>A partial event is returned as it is and nothing is stored. An event ID can be used once per
   * session. Do not change the event or its delta while the call is running.
   *
   * <p>When a failure message says the write may have been committed, load the session again before
   * deciding whether to repeat the call. When it says nothing was written, the same call can be
   * repeated with the same session.
   *
   * @param session a session of this service, as created or loaded; it is locked for the call
   * @param event the event to append; its ID is required, at most 128 code points
   * @return a cold {@code Single} of {@code event}
   * @throws SessionNotFoundException signaled through the returned {@code Single} if the session no
   *     longer exists
   * @throws SessionException signaled the same way if the supplied history is not current, the
   *     event ID is already used, an argument is invalid, or the database fails
   */
  @CanIgnoreReturnValue
  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    return Single.fromCallable(
        () -> {
          if (session == null || event == null) {
            throw new SessionException("Session and event cannot be null.");
          }
          if (event.partial().orElse(false)) {
            return event;
          }
          // One append at a time per Session object: the second of two concurrent calls has to
          // see the event the first one added, or it would be rejected as stale.
          synchronized (session) {
            SessionKey key = key(session.appName(), session.userId(), session.id());
            validateKey(event.id());
            // The invocation_id column is NOT NULL in the Python v1 layout; an event without one
            // is stored with an empty string there, and without the field in its JSON.
            String invocationId = Objects.requireNonNullElse(event.invocationId(), "");
            if (!validText(invocationId, 256)) {
              throw new SessionException(
                  "Invocation IDs must be valid Unicode without NUL and at most 256 code points.");
            }
            Instant timestamp = DatabaseTime.forStorage(Instant.ofEpochMilli(event.timestamp()));
            // Copy the delta once. What is stored and what is then applied to the session must be
            // the same entries, even if the caller's map changes in the meantime.
            Map<String, Object> delta = snapshotDelta(event);
            store().append(key, lastEventId(session), event, delta, timestamp, invocationId);
            // Reached only after a confirmed commit.
            // temp: values are never stored, but as in InMemorySessionService they reach the
            // supplied session, where later steps of the same invocation read them.
            delta.forEach(
                (name, value) -> {
                  if (value == null || value == State.REMOVED) {
                    session.state().remove(name);
                  } else {
                    session.state().put(name, value);
                  }
                });
            session.lastUpdateTime(timestamp);
            session.addEvent(event);
          }
          return event;
        });
  }

  /**
   * Returns the ID of the last event the caller's session holds, or {@code null} if it holds none.
   * The store compares it with the last stored event to tell a current copy from a stale one; no
   * extra field on {@link Session} is needed for that.
   */
  @SuppressWarnings("deprecation") // Use Session's list monitor without copying the whole history.
  private static @Nullable String lastEventId(Session session) {
    List<Event> events = session.events();
    synchronized (events) {
      if (events.isEmpty()) {
        return null;
      }
      Event tail = events.get(events.size() - 1);
      if (tail == null || tail.id() == null) {
        throw new SessionException("Session history is invalid; reload before appending.");
      }
      return tail.id();
    }
  }

  /** Copies the entries of the event's state delta; empty if the event has none. */
  private static Map<String, Object> snapshotDelta(Event event) {
    try {
      Map<String, Object> delta = event.actions() == null ? null : event.actions().stateDelta();
      return delta == null ? Map.of() : new HashMap<>(delta);
    } catch (RuntimeException invalidDelta) {
      throw new SessionException("Unable to snapshot session event state.");
    }
  }

  /** Asks the configured provider for a session ID and holds it to the rules for any other ID. */
  private String newIdentifier() {
    try {
      String value = uuidProvider.newUuid();
      validateKey(value);
      return value;
    } catch (RuntimeException failure) {
      throw new SessionException("Unable to obtain a valid session identifier.");
    }
  }

  /** Validates the three parts of a session's identity and combines them. */
  private static SessionKey key(String appName, String userId, String id) {
    validateKey(appName);
    validateKey(userId);
    validateKey(id);
    return new SessionKey(appName, userId, id);
  }

  /**
   * Requires an identifier that fits a key column, {@code VARCHAR(128)} on every engine. The
   * message names the rule and never the value, which may be private.
   */
  private static void validateKey(@Nullable String value) {
    if (!validText(value, 128)) {
      throw new SessionException(
          "Identifiers must be valid Unicode without NUL and at most 128 code points.");
    }
  }

  /**
   * Whether every supported engine stores the text and gives the same text back. Checked here so
   * that all four behave alike, instead of each rejecting, truncating or altering in its own way.
   *
   * <ul>
   *   <li>Length is counted in code points, as the engines count {@code VARCHAR} length, not in
   *       Java's UTF-16 units.
   *   <li>NUL is refused: PostgreSQL cannot store it in text.
   *   <li>A surrogate without its pair is refused: it is not valid Unicode and cannot be encoded as
   *       UTF-8.
   * </ul>
   */
  private static boolean validText(@Nullable String value, int maxCodePoints) {
    if (value == null
        || value.codePointCount(0, value.length()) > maxCodePoints
        || value.indexOf('\0') >= 0) {
      return false;
    }
    // A high surrogate must be followed by a low one, and a low one must follow a high one.
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
          return false;
        }
      } else if (Character.isLowSurrogate(current)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Configures immutable service settings; a builder itself is not thread-safe.
   *
   * <pre>{@code
   * DatabaseSessionService sessions =
   *     DatabaseSessionService.builder(dataSource)
   *         .dialect(DatabaseSessionService.Dialect.POSTGRESQL)
   *         .statementTimeout(Duration.ofSeconds(30))
   *         .build();
   * }</pre>
   *
   * <p>Each setter rejects {@code null} with a {@link SessionException}. A builder can be reused; a
   * service keeps the settings it was built with.
   */
  public static final class Builder {
    private final DataSource dataSource;
    private InstantSource instantSource = InstantSource.system();
    private UuidProvider uuidProvider = UuidProvider.SYSTEM;
    private boolean autoMigrate;
    private int statementTimeoutSeconds = DEFAULT_STATEMENT_TIMEOUT_SECONDS;
    private @Nullable Dialect dialect;

    private Builder(DataSource dataSource) {
      this.dataSource = requireComponent(dataSource, "dataSource");
    }

    /**
     * Selects a dialect explicitly, without inspecting JDBC URLs or metadata.
     *
     * <p>Without this, the dialect is detected on first use from the database product name that the
     * driver reports, which costs one borrowed connection. Set it when the product name is not one
     * of the four that are recognized, for example behind a proxy or a compatible engine.
     */
    @CanIgnoreReturnValue
    public Builder dialect(Dialect dialect) {
      this.dialect = requireComponent(dialect, "dialect");
      return this;
    }

    /**
     * Selects the source used when creating a session; this does not read the clock.
     *
     * <p>It supplies the creation time of a session, which is also its first update time. Later
     * update times come from the timestamps of the appended events, and the times of the shared
     * state come from the database's own clock.
     */
    @CanIgnoreReturnValue
    public Builder instantSource(InstantSource instantSource) {
      this.instantSource = requireComponent(instantSource, "instantSource");
      return this;
    }

    /**
     * Selects a thread-safe provider of distinct identifiers without invoking it.
     *
     * <p>It is asked for a session ID whenever a session is created without one. An ID it returns
     * has to meet the rules for any session ID, at most 128 code points.
     */
    @CanIgnoreReturnValue
    public Builder uuidProvider(UuidProvider uuidProvider) {
      this.uuidProvider = requireComponent(uuidProvider, "uuidProvider");
      return this;
    }

    /**
     * Enables schema migration before the first database operation; disabled by default.
     *
     * <p>The builder and constructor remain side-effect free even when this is enabled. Arrange for
     * one migration owner before starting workers. This flag does not coordinate bootstrap across
     * services or processes, particularly with SQLite or non-transactional DDL.
     *
     * <p>Leave it off when the schema is created by {@link DatabaseSessionService#migrate()} at
     * deployment time, or by running the module's SQL yourself.
     */
    @CanIgnoreReturnValue
    public Builder autoMigrate(boolean autoMigrate) {
      this.autoMigrate = autoMigrate;
      return this;
    }

    /**
     * Limits each SQL statement of an operation, and how long SQLite waits for a competing writer;
     * 15 seconds by default.
     *
     * <p>This is not an end-to-end deadline: one operation runs several statements, and connection
     * and network timeouts stay with the application's data source.
     *
     * @throws SessionException unless the timeout is a whole number of seconds from 1 to 2147483
     */
    @CanIgnoreReturnValue
    public Builder statementTimeout(Duration statementTimeout) {
      requireComponent(statementTimeout, "statementTimeout");
      long seconds = statementTimeout.getSeconds();
      if (statementTimeout.getNano() != 0
          || seconds < 1
          || seconds > MAX_STATEMENT_TIMEOUT_SECONDS) {
        throw new SessionException(
            "statementTimeout must be a whole number of seconds from 1 to "
                + MAX_STATEMENT_TIMEOUT_SECONDS
                + ".");
      }
      this.statementTimeoutSeconds = (int) seconds;
      return this;
    }

    /** Takes a snapshot of these settings without accessing the database or providers. */
    public DatabaseSessionService build() {
      return new DatabaseSessionService(this);
    }
  }

  // The settings this instance was built with, readable by the module's own tests.

  DataSource dataSource() {
    return dataSource;
  }

  InstantSource instantSource() {
    return instantSource;
  }

  UuidProvider uuidProvider() {
    return uuidProvider;
  }

  boolean autoMigrate() {
    return autoMigrate;
  }

  Duration statementTimeout() {
    return Duration.ofSeconds(statementTimeoutSeconds);
  }

  /** Resolves once per instance. A failed attempt is not cached, allowing a later retry. */
  synchronized Dialect resolveDialect() {
    if (resolvedDialect == null) {
      resolvedDialect = DatabaseDialect.detect(dataSource);
    }
    return resolvedDialect;
  }

  /** Rejects a missing setting with the one exception type this service uses. */
  private static <T> T requireComponent(@Nullable T value, String name) {
    if (value == null) {
      throw new SessionException(name + " cannot be null.");
    }
    return value;
  }
}
