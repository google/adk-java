package com.google.adk.sessions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.sessions.dao.EventDao;
import com.google.adk.sessions.dao.SessionDao;
import com.google.adk.sessions.dao.StateDao;
import com.google.adk.sessions.dialect.DialectDetector;
import com.google.adk.sessions.dialect.SqlDialect;
import com.google.adk.sessions.model.AppStateRow;
import com.google.adk.sessions.model.EventRow;
import com.google.adk.sessions.model.SessionRow;
import com.google.adk.sessions.model.UserStateRow;
import com.google.adk.sessions.util.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-based implementation of {@link BaseSessionService}.
 *
 * <p>This service provides persistent session management using JDBC and HikariCP connection
 * pooling. It supports multiple databases with automatic dialect detection and schema management
 * via Flyway migrations.
 *
 * <p><b>Features:</b>
 *
 * <ul>
 *   <li>JDBC implementation with HikariCP connection pooling
 *   <li>Multi-database support: PostgreSQL, MySQL, H2, Cloud Spanner
 *   <li>Automatic schema management via Flyway migrations
 *   <li>3-tier state storage (app-level, user-level, session-level)
 *   <li>Reactive API with RxJava 3 (Single, Maybe, Completable)
 *   <li>Transaction management with automatic rollback on errors
 *   <li>Pessimistic locking support (FOR UPDATE) for concurrent operations
 * </ul>
 *
 * <p><b>Supported Databases:</b>
 *
 * <ul>
 *   <li>PostgreSQL: {@code jdbc:postgresql://localhost:5432/adk?user=postgres&password=secret}
 *   <li>MySQL: {@code jdbc:mysql://localhost:3306/adk?user=root&password=secret}
 *   <li>H2: {@code jdbc:h2:mem:testdb} or {@code jdbc:h2:file:./data/mydb}
 *   <li>Cloud Spanner: {@code
 *       jdbc:cloudspanner:/projects/PROJECT_ID/instances/INSTANCE_ID/databases/DATABASE_ID}
 * </ul>
 *
 * <p><b>State Management:</b>
 *
 * <p>This service implements a 3-tier state storage model:
 *
 * <ul>
 *   <li><b>App State</b>: Shared across all users and sessions (prefix: {@code app:})
 *   <li><b>User State</b>: Shared across all sessions for a specific user (prefix: {@code user:})
 *   <li><b>Session State</b>: Specific to a single session (no prefix)
 * </ul>
 *
 * <p>State is merged with priority: App → User → Session (higher priority overwrites lower).
 *
 * <p><b>Thread Safety:</b>
 *
 * <p>This class is thread-safe. All database operations use connection pooling and database
 * transactions. Concurrent operations on the same session are serialized via pessimistic locking
 * (SELECT ... FOR UPDATE).
 *
 * <p><b>Resource Management:</b>
 *
 * <p>This class implements {@link AutoCloseable}. Always use try-with-resources or explicitly call
 * {@link #close()} to release database connections:
 *
 * <pre>{@code
 * try (DatabaseSessionService service = new DatabaseSessionService(jdbcUrl)) {
 *     // Use the service
 * } // Connections automatically closed
 * }</pre>
 *
 * <p><b>Example Usage:</b>
 *
 * <pre>{@code
 * // Create service with PostgreSQL
 * String jdbcUrl = "jdbc:postgresql://localhost:5432/adk?user=postgres&password=secret";
 * try (DatabaseSessionService service = new DatabaseSessionService(jdbcUrl)) {
 *
 *     // Create a session with initial state
 *     ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
 *     state.put("app:version", "1.0");      // App-level state
 *     state.put("user:theme", "dark");       // User-level state
 *     state.put("currentStep", 1);           // Session-level state
 *
 *     Session session = service.createSession("myApp", "user123", state, null).blockingGet();
 *
 *     // Append an event with state delta
 *     Event event = Event.builder()
 *         .id(UUID.randomUUID().toString())
 *         .invocationId("inv-1")
 *         .timestamp(System.currentTimeMillis())
 *         .actions(EventActions.builder()
 *             .stateDelta(Map.of("currentStep", 2))
 *             .build())
 *         .build();
 *
 *     Event appendedEvent = service.appendEvent(session, event).blockingGet();
 *
 *     // Retrieve updated session
 *     Session updated = service.getSession("myApp", "user123", session.id(), Optional.empty())
 *         .blockingGet();
 * }
 * }</pre>
 *
 * @see BaseSessionService
 * @see Session
 * @see Event
 * @see State
 */
public class DatabaseSessionService implements BaseSessionService, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseSessionService.class);

  private final HikariDataSource dataSource;
  private final JdbcTemplate jdbcTemplate;
  private final SqlDialect dialect;
  private final ObjectMapper objectMapper;
  private final SessionDao sessionDao;
  private final EventDao eventDao;
  private final StateDao stateDao;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Creates a new DatabaseSessionService with default configuration.
   *
   * <p>This constructor uses default HikariCP connection pool settings: max pool size = 10, min
   * idle = 2, connection timeout = 30s.
   *
   * <p>The database dialect is automatically detected from the JDBC URL, and Flyway migrations are
   * run automatically to create/update the schema.
   *
   * @param jdbcUrl the JDBC connection URL (e.g., {@code
   *     jdbc:postgresql://localhost:5432/adk?user=postgres&password=secret})
   * @throws NullPointerException if jdbcUrl is null
   * @throws IllegalArgumentException if the database dialect cannot be detected from the JDBC URL
   * @throws SessionException if database migration fails
   * @see #DatabaseSessionService(String, Map)
   */
  public DatabaseSessionService(String jdbcUrl) {
    this(jdbcUrl, Collections.emptyMap());
  }

  /**
   * Creates a new DatabaseSessionService with custom configuration.
   *
   * <p>This constructor allows customization of both HikariCP connection pool settings and
   * database-specific properties.
   *
   * <p><b>Example with custom connection pool settings:</b>
   *
   * <pre>{@code
   * Map<String, Object> props = new HashMap<>();
   * props.put("hikari.connectionTimeout", 60000);  // 60 seconds
   * props.put("hikari.maximumPoolSize", 20);       // 20 connections
   * DatabaseSessionService service = new DatabaseSessionService(jdbcUrl, props);
   * }</pre>
   *
   * <p><b>Supported HikariCP properties (prefix with "hikari."):</b>
   *
   * <ul>
   *   <li>{@code hikari.maximumPoolSize} (Integer) - Maximum pool size (default: 10)
   *   <li>{@code hikari.minimumIdle} (Integer) - Minimum idle connections (default: 2)
   *   <li>{@code hikari.connectionTimeout} (Long) - Connection timeout in ms (default: 30000)
   *   <li>{@code hikari.idleTimeout} (Long) - Idle timeout in ms (default: 600000)
   *   <li>{@code hikari.maxLifetime} (Long) - Max connection lifetime in ms (default: 1800000)
   * </ul>
   *
   * <p>All properties without the "hikari." prefix are passed to the underlying DataSource.
   *
   * @param jdbcUrl the JDBC connection URL
   * @param properties configuration properties for HikariCP and the DataSource
   * @throws NullPointerException if jdbcUrl is null
   * @throws IllegalArgumentException if the database dialect cannot be detected from the JDBC URL
   * @throws SessionException if database migration fails
   */
  public DatabaseSessionService(String jdbcUrl, Map<String, Object> properties) {
    Objects.requireNonNull(jdbcUrl, "JDBC URL cannot be null");

    this.dialect = DialectDetector.detectFromJdbcUrl(jdbcUrl);
    logger.info("Detected SQL dialect: {}", dialect.dialectName());

    runMigrations(jdbcUrl);

    this.dataSource = createDataSource(jdbcUrl, properties);

    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.objectMapper = com.google.adk.JsonBaseModel.getMapper();
    this.sessionDao = new SessionDao(dialect);
    this.eventDao = new EventDao(dialect);
    this.stateDao = new StateDao(dialect);

    logger.info(
        "DatabaseSessionService initialized with {} (JDBC implementation)", dialect.dialectName());
  }

  private void runMigrations(String jdbcUrl) {
    try {
      String dialectFolder = extractDialectFolder(dialect.dialectName());
      String flywayLocation = "classpath:db/migration/" + dialectFolder;

      logger.info("Starting Flyway database migration");
      logger.info("Dialect: {}", dialect.dialectName());
      logger.info("Migration location: {}", flywayLocation);
      logger.info("JDBC URL: {}", jdbcUrl.replaceAll("password=[^&;]*", "password=***"));

      String baselineOnMigrateStr =
          System.getProperty(
              "FLYWAY_BASELINE_ON_MIGRATE",
              System.getenv().getOrDefault("FLYWAY_BASELINE_ON_MIGRATE", "false"));
      boolean baselineOnMigrate = Boolean.parseBoolean(baselineOnMigrateStr);

      String lockRetryCountStr =
          System.getProperty(
              "FLYWAY_LOCK_RETRY_COUNT",
              System.getenv().getOrDefault("FLYWAY_LOCK_RETRY_COUNT", "120"));
      int lockRetryCount = Integer.parseInt(lockRetryCountStr);

      logger.info(
          "Flyway configuration: baselineOnMigrate={}, lockRetryCount={}",
          baselineOnMigrate,
          lockRetryCount);

      Flyway flyway =
          Flyway.configure()
              .dataSource(jdbcUrl, null, null)
              .locations(flywayLocation)
              .cleanDisabled(true)
              .lockRetryCount(lockRetryCount)
              .baselineOnMigrate(baselineOnMigrate)
              .load();

      MigrateResult result = flyway.migrate();

      if (result.migrationsExecuted > 0) {
        logger.info(
            "Flyway migration completed: {} migration(s) applied successfully",
            result.migrationsExecuted);
      } else {
        logger.info("Database schema is up to date (no migrations applied)");
      }
      logger.info("Flyway migration complete");
    } catch (FlywayException e) {
      throw new SessionException("Failed to run database migrations", e);
    }
  }

  private String extractDialectFolder(String dialectName) {
    String lower = dialectName.toLowerCase();
    if (lower.contains("postgres")) return "postgresql";
    if (lower.contains("mysql")) return "mysql";
    if (lower.contains("h2")) return "h2";
    if (lower.contains("spanner")) return "spanner";
    throw new IllegalArgumentException("Unsupported dialect: " + dialectName);
  }

  private HikariDataSource createDataSource(String jdbcUrl, Map<String, Object> properties) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);

    int maxPoolSize = getIntProperty(properties, "hikari.maximumPoolSize", 10);
    int minIdle = getIntProperty(properties, "hikari.minimumIdle", 2);
    long connTimeout = getLongProperty(properties, "hikari.connectionTimeout", 30000L);
    long idleTimeout = getLongProperty(properties, "hikari.idleTimeout", 600000L);
    long maxLifetime = getLongProperty(properties, "hikari.maxLifetime", 1800000L);

    config.setMaximumPoolSize(maxPoolSize);
    config.setMinimumIdle(minIdle);
    config.setConnectionTimeout(connTimeout);
    config.setIdleTimeout(idleTimeout);
    config.setMaxLifetime(maxLifetime);

    properties.entrySet().stream()
        .filter(e -> !e.getKey().startsWith("hikari."))
        .forEach(e -> config.addDataSourceProperty(e.getKey(), e.getValue()));

    logger.debug("Initializing HikariCP connection pool");
    logger.debug(
        "Pool configuration: maxPoolSize={}, minIdle={}, connectionTimeout={}ms, idleTimeout={}ms, maxLifetime={}ms",
        maxPoolSize,
        minIdle,
        connTimeout,
        idleTimeout,
        maxLifetime);

    HikariDataSource dataSource = new HikariDataSource(config);
    logger.debug("HikariCP connection pool created successfully");
    return dataSource;
  }

  /**
   * Creates a new session with the specified parameters.
   *
   * <p>This method creates a new session and initializes the 3-tier state storage (app, user,
   * session). If the provided state map contains keys with prefixes {@code app:} or {@code user:},
   * those entries are stored in the corresponding state tables.
   *
   * <p><b>State Handling:</b>
   *
   * <ul>
   *   <li>Keys starting with {@code app:} are stored in app_states table (shared across all users)
   *   <li>Keys starting with {@code user:} are stored in user_states table (shared across user's
   *       sessions)
   *   <li>Keys starting with {@code temp:} are ignored (not persisted)
   *   <li>All other keys are stored in sessions table (session-specific)
   * </ul>
   *
   * <p>If app or user state already exists, it is updated with the new values.
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @param state the initial state map, can be null or empty
   * @param sessionId optional session ID; if null or empty, a UUID is generated
   * @return a Single that emits the created Session
   * @throws NullPointerException if appName or userId is null (checked by BaseSessionService)
   * @see State#APP_PREFIX
   * @see State#USER_PREFIX
   * @see State#TEMP_PREFIX
   */
  @Override
  public Single<Session> createSession(
      String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
    checkNotClosed();
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    return Single.fromCallable(() -> createSessionInTransaction(appName, userId, state, sessionId))
        .subscribeOn(Schedulers.io());
  }

  /**
   * Retrieves a session by its identifiers.
   *
   * <p>This method fetches the session from the database and merges the 3-tier state (app → user →
   * session) before returning. The returned session includes all events up to the specified limit.
   *
   * <p><b>Event Filtering:</b>
   *
   * <ul>
   *   <li>If {@code config.numRecentEvents} is specified, returns that many most recent events
   *   <li>If {@code config.afterTimestamp} is specified, returns events after that timestamp
   *   <li>Default: returns the 1000 most recent events
   * </ul>
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @param sessionId the session identifier (must not be null)
   * @param config optional configuration for event filtering
   * @return a Maybe that emits the Session if found, or completes empty if not found
   * @throws NullPointerException if appName, userId, or sessionId is null
   * @see GetSessionConfig
   */
  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    checkNotClosed();
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(config, "config cannot be null");

    return Maybe.fromCallable(
            () ->
                jdbcTemplate.inTransaction(
                    ops -> {
                      Optional<SessionRow> sessionOpt =
                          sessionDao.findSession(ops, appName, userId, sessionId);

                      if (!sessionOpt.isPresent()) {
                        return null;
                      }

                      return buildSessionFromRow(ops, sessionOpt.get(), config);
                    }))
        .subscribeOn(Schedulers.io());
  }

  /**
   * Lists all sessions for a specific application and user.
   *
   * <p>The sessions are returned without events and without merged app/user state (state maps will
   * be empty). Use {@link #getSession} to retrieve full session details.
   *
   * <p>Sessions are ordered by update_time descending (most recently updated first).
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @return a Single that emits a ListSessionsResponse containing the sessions
   * @throws NullPointerException if appName or userId is null
   */
  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    checkNotClosed();
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    return Single.fromCallable(
            () -> {
              return jdbcTemplate.inTransaction(
                  ops -> {
                    List<SessionRow> sessionRows = sessionDao.listSessions(ops, appName, userId);

                    List<Session> sessions =
                        sessionRows.stream()
                            .map(
                                row ->
                                    toDomainSession(
                                        row,
                                        Collections.emptyList(),
                                        new ConcurrentHashMap<>(),
                                        new ConcurrentHashMap<>()))
                            .collect(Collectors.toList());

                    return ListSessionsResponse.builder().sessions(sessions).build();
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Deletes a session and all its associated events.
   *
   * <p>This operation cascades to delete all events associated with the session. App-level and
   * user-level state are NOT deleted (they may be shared with other sessions).
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @param sessionId the session identifier (must not be null)
   * @return a Completable that completes when the session is deleted
   * @throws SessionNotFoundException if the session does not exist
   * @throws NullPointerException if any parameter is null
   */
  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    checkNotClosed();
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    return Completable.fromAction(
            () -> {
              jdbcTemplate.inTransaction(
                  ops -> {
                    // Idempotent delete - no error if session doesn't exist
                    sessionDao.deleteSession(ops, appName, userId, sessionId);
                    return null;
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Lists all events for a session.
   *
   * <p>This method fetches ALL events for the session in chronological order (oldest first).
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @param sessionId the session identifier (must not be null)
   * @return a Single that emits a ListEventsResponse containing all events
   * @throws SessionNotFoundException if the session does not exist
   * @throws IllegalStateException if the service has been closed
   */
  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    checkNotClosed();
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    return Single.fromCallable(
            () -> {
              return jdbcTemplate.inTransaction(
                  ops -> {
                    Optional<SessionRow> sessionOpt =
                        sessionDao.findSession(ops, appName, userId, sessionId);

                    if (!sessionOpt.isPresent()) {
                      throw new SessionNotFoundException(
                          "Session not found: " + appName + "/" + userId + "/" + sessionId);
                    }

                    List<EventRow> eventRows = eventDao.listEvents(ops, appName, userId, sessionId);

                    List<Event> events =
                        eventRows.stream().map(this::toEvent).collect(Collectors.toList());

                    return ListEventsResponse.builder().events(events).build();
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Appends an event to a session and persists it to the database.
   *
   * <p>This method processes the event's state delta (if present) and applies it to the appropriate
   * state tier (app, user, or session). The session's update_time is refreshed even if there is no
   * state delta.
   *
   * <p><b>State Delta Processing:</b>
   *
   * <ul>
   *   <li>Keys starting with {@code app:} update app-level state
   *   <li>Keys starting with {@code user:} update user-level state
   *   <li>Keys starting with {@code temp:} are ignored (not persisted)
   *   <li>All other keys update session-level state
   *   <li>Use {@link State#REMOVED} as a value to delete a state key
   * </ul>
   *
   * <p>This operation uses pessimistic locking (SELECT ... FOR UPDATE) to prevent concurrent
   * modifications to the same session.
   *
   * @param session the session to append the event to (must not be null)
   * @param event the event to append (must not be null)
   * @return a Single that emits the updated Event after processing
   * @throws NullPointerException if session or event is null
   * @throws SessionNotFoundException if the session does not exist
   * @throws IllegalStateException if the service has been closed
   * @see State#REMOVED
   */
  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    checkNotClosed();
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");

    // DB first, then memory
    // If DB fails, transaction rolls back and memory is never updated
    return persistEventToDatabase(session.appName(), session.userId(), session.id(), event)
        .andThen(BaseSessionService.super.appendEvent(session, event))
        .doOnError(
            throwable -> {
              logger.error(
                  "Failed to append event to session {}/{}/{}: {}",
                  session.appName(),
                  session.userId(),
                  session.id(),
                  throwable.getMessage(),
                  throwable);
            });
  }

  /**
   * Persists an event to the database.
   *
   * <p>This method handles event persistence and state delta updates. It acquires row-level locks
   * on the session, app state, and user state during the transaction.
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @param sessionId the session identifier (must not be null)
   * @param event the event to append (must not be null)
   * @return a Completable that completes when the event is persisted
   * @throws SessionNotFoundException if the session does not exist
   */
  private Completable persistEventToDatabase(
      String appName, String userId, String sessionId, Event event) {
    return Completable.fromAction(
            () -> {
              jdbcTemplate.inTransaction(
                  ops -> {
                    Optional<SessionRow> sessionOpt =
                        sessionDao.findSessionForUpdate(ops, appName, userId, sessionId);

                    if (!sessionOpt.isPresent()) {
                      throw new SessionNotFoundException(
                          "Session not found: " + appName + "/" + userId + "/" + sessionId);
                    }

                    SessionRow sessionRow = sessionOpt.get();

                    Optional<AppStateRow> appStateOpt = stateDao.getAppStateForUpdate(ops, appName);
                    Map<String, Object> appState =
                        appStateOpt
                            .map(s -> fromJson(s.getState()))
                            .orElse(new ConcurrentHashMap<>());

                    Optional<UserStateRow> userStateOpt =
                        stateDao.getUserStateForUpdate(ops, appName, userId);
                    Map<String, Object> userState =
                        userStateOpt
                            .map(s -> fromJson(s.getState()))
                            .orElse(new ConcurrentHashMap<>());

                    if (event.actions() != null && event.actions().stateDelta() != null) {
                      ConcurrentMap<String, Object> stateDelta = event.actions().stateDelta();

                      Map<String, Object> appStateDelta = new ConcurrentHashMap<>();
                      Map<String, Object> userStateDelta = new ConcurrentHashMap<>();
                      Map<String, Object> sessionStateDelta = new ConcurrentHashMap<>();

                      for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
                        String key = entry.getKey();
                        if (key.startsWith(State.APP_PREFIX)) {
                          String unprefixedKey = key.substring(State.APP_PREFIX.length());
                          appStateDelta.put(unprefixedKey, entry.getValue());
                        } else if (key.startsWith(State.USER_PREFIX)) {
                          String unprefixedKey = key.substring(State.USER_PREFIX.length());
                          userStateDelta.put(unprefixedKey, entry.getValue());
                        } else if (!key.startsWith(State.TEMP_PREFIX)) {
                          sessionStateDelta.put(key, entry.getValue());
                        }
                      }

                      if (!appStateDelta.isEmpty()) {
                        for (Map.Entry<String, Object> entry : appStateDelta.entrySet()) {
                          if (entry.getValue() == State.REMOVED) {
                            appState.remove(entry.getKey());
                          } else {
                            appState.put(entry.getKey(), entry.getValue());
                          }
                        }
                        AppStateRow updatedAppState = new AppStateRow();
                        updatedAppState.setAppName(appName);
                        updatedAppState.setState(toJson(appState));
                        updatedAppState.setUpdateTime(Instant.now());
                        stateDao.upsertAppState(ops, updatedAppState);
                      }

                      if (!userStateDelta.isEmpty()) {
                        for (Map.Entry<String, Object> entry : userStateDelta.entrySet()) {
                          if (entry.getValue() == State.REMOVED) {
                            userState.remove(entry.getKey());
                          } else {
                            userState.put(entry.getKey(), entry.getValue());
                          }
                        }
                        UserStateRow updatedUserState = new UserStateRow();
                        updatedUserState.setAppName(appName);
                        updatedUserState.setUserId(userId);
                        updatedUserState.setState(toJson(userState));
                        updatedUserState.setUpdateTime(Instant.now());
                        stateDao.upsertUserState(ops, updatedUserState);
                      }

                      if (!sessionStateDelta.isEmpty()) {
                        Map<String, Object> sessionState = fromJson(sessionRow.getState());
                        for (Map.Entry<String, Object> entry : sessionStateDelta.entrySet()) {
                          if (entry.getValue() == State.REMOVED) {
                            sessionState.remove(entry.getKey());
                          } else {
                            sessionState.put(entry.getKey(), entry.getValue());
                          }
                        }
                        sessionRow.setState(toJson(sessionState));
                      }
                    }

                    sessionRow.setUpdateTime(Instant.now());
                    sessionDao.updateSession(ops, sessionRow);

                    EventRow eventRow = fromDomainEvent(event, appName, userId, sessionId);
                    eventDao.insertEvent(ops, eventRow);

                    return null;
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Retrieves the app-level state for an application.
   *
   * <p>App-level state is shared across all users and sessions for the specified application. This
   * is typically used for application-wide configuration or data.
   *
   * @param appName the application name (must not be null)
   * @return a Single that emits the app state map, or null if no app state exists
   */
  public Single<Map<String, Object>> getAppState(String appName) {
    return Single.fromCallable(
            () -> {
              return jdbcTemplate.inTransaction(
                  ops -> {
                    Optional<AppStateRow> appStateOpt = stateDao.getAppState(ops, appName);
                    return appStateOpt
                        .map(s -> (Map<String, Object>) fromJson(s.getState()))
                        .orElse(null);
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Sets or replaces the app-level state for an application.
   *
   * <p>This operation completely replaces the existing app state. If you need to update specific
   * keys, retrieve the current state first, modify it, and then set it back.
   *
   * <p><b>Warning:</b> This affects all users and sessions for the application.
   *
   * @param appName the application name (must not be null)
   * @param state the new app state map (must not be null)
   * @return a Completable that completes when the state is updated
   */
  public Completable setAppState(String appName, Map<String, Object> state) {
    return Completable.fromAction(
            () -> {
              jdbcTemplate.inTransaction(
                  ops -> {
                    AppStateRow row = new AppStateRow();
                    row.setAppName(appName);
                    row.setState(toJson(state));
                    row.setUpdateTime(Instant.now());

                    stateDao.upsertAppState(ops, row);
                    return null;
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Retrieves the user-level state for a specific user in an application.
   *
   * <p>User-level state is shared across all sessions for the specified user. This is typically
   * used for user preferences or data that should persist across sessions.
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @return a Single that emits the user state map, or null if no user state exists
   */
  public Single<Map<String, Object>> getUserState(String appName, String userId) {
    return Single.fromCallable(
            () -> {
              return jdbcTemplate.inTransaction(
                  ops -> {
                    Optional<UserStateRow> userStateOpt =
                        stateDao.getUserState(ops, appName, userId);
                    return userStateOpt
                        .map(s -> (Map<String, Object>) fromJson(s.getState()))
                        .orElse(null);
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Sets or replaces the user-level state for a specific user in an application.
   *
   * <p>This operation completely replaces the existing user state. If you need to update specific
   * keys, retrieve the current state first, modify it, and then set it back.
   *
   * <p><b>Warning:</b> This affects all sessions for the specified user.
   *
   * @param appName the application name (must not be null)
   * @param userId the user identifier (must not be null)
   * @param state the new user state map (must not be null)
   * @return a Completable that completes when the state is updated
   */
  public Completable setUserState(String appName, String userId, Map<String, Object> state) {
    return Completable.fromAction(
            () -> {
              jdbcTemplate.inTransaction(
                  ops -> {
                    UserStateRow row = new UserStateRow();
                    row.setAppName(appName);
                    row.setUserId(userId);
                    row.setState(toJson(state));
                    row.setUpdateTime(Instant.now());

                    stateDao.upsertUserState(ops, row);
                    return null;
                  });
            })
        .subscribeOn(Schedulers.io());
  }

  private Session toDomainSession(
      SessionRow row,
      List<EventRow> eventRows,
      Map<String, Object> appState,
      Map<String, Object> userState) {
    ConcurrentMap<String, Object> mergedState = new ConcurrentHashMap<>();

    if (appState != null) {
      for (Map.Entry<String, Object> entry : appState.entrySet()) {
        mergedState.put(State.APP_PREFIX + entry.getKey(), entry.getValue());
      }
    }

    if (userState != null) {
      for (Map.Entry<String, Object> entry : userState.entrySet()) {
        mergedState.put(State.USER_PREFIX + entry.getKey(), entry.getValue());
      }
    }

    Map<String, Object> sessionStateMap = fromJson(row.getState());
    if (sessionStateMap != null) {
      mergedState.putAll(sessionStateMap);
    }

    List<Event> events = eventRows.stream().map(this::toEvent).collect(Collectors.toList());

    return Session.builder(row.getId())
        .appName(row.getAppName())
        .userId(row.getUserId())
        .state(mergedState)
        .events(events)
        .lastUpdateTime(row.getUpdateTime())
        .build();
  }

  private Event toEvent(EventRow row) {
    try {
      Event event = objectMapper.readValue(row.getEventData(), Event.class);

      event.setId(row.getId());
      event.setInvocationId(row.getInvocationId());
      event.setTimestamp(row.getTimestamp().toEpochMilli());

      return event;
    } catch (Exception e) {
      logger.error("Failed to deserialize event {}: {}", row.getId(), e.getMessage(), e);
      throw new SessionException("Failed to convert EventRow to Event", e);
    }
  }

  private EventRow fromDomainEvent(Event event, String appName, String userId, String sessionId) {
    EventRow row = new EventRow();
    row.setId(event.id());
    row.setAppName(appName);
    row.setUserId(userId);
    row.setSessionId(sessionId);
    row.setInvocationId(event.invocationId());
    row.setTimestamp(Instant.ofEpochMilli(event.timestamp()));

    try {
      Map<String, Object> eventDataMap =
          objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {});

      eventDataMap.remove("id");
      eventDataMap.remove("invocationId");
      eventDataMap.remove("timestamp");

      String eventDataJson = objectMapper.writeValueAsString(eventDataMap);
      row.setEventData(eventDataJson);
    } catch (Exception e) {
      logger.error("Failed to serialize event {}: {}", event.id(), e.getMessage(), e);
      throw new SessionException("Failed to convert Event to EventRow", e);
    }

    return row;
  }

  private String toJson(Map<String, Object> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (Exception e) {
      throw new SessionException("Failed to serialize to JSON", e);
    }
  }

  private Map<String, Object> fromJson(String json) {
    if (json == null || json.isEmpty()) {
      return new ConcurrentHashMap<>();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new SessionException("Failed to deserialize from JSON", e);
    }
  }

  private void checkNotClosed() {
    if (closed.get()) {
      throw new IllegalStateException(
          "DatabaseSessionService is closed. Create a new instance or ensure close() is not called prematurely.");
    }
  }

  private static int getIntProperty(Map<String, Object> properties, String key, int defaultValue) {
    Object value = properties.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Integer) {
      return (Integer) value;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt((String) value);
      } catch (NumberFormatException e) {
        logger.warn(
            "Invalid integer value for property {}: {}. Using default: {}",
            key,
            value,
            defaultValue);
        return defaultValue;
      }
    }
    logger.warn(
        "Unsupported type for property {}: {}. Using default: {}",
        key,
        value.getClass().getName(),
        defaultValue);
    return defaultValue;
  }

  private static long getLongProperty(
      Map<String, Object> properties, String key, long defaultValue) {
    Object value = properties.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Long) {
      return (Long) value;
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong((String) value);
      } catch (NumberFormatException e) {
        logger.warn(
            "Invalid long value for property {}: {}. Using default: {}", key, value, defaultValue);
        return defaultValue;
      }
    }
    logger.warn(
        "Unsupported type for property {}: {}. Using default: {}",
        key,
        value.getClass().getName(),
        defaultValue);
    return defaultValue;
  }

  /**
   * Helper method to create a session within a transaction.
   *
   * <p>Extracted from {@link #createSession} to improve testability and reduce lambda nesting.
   *
   * @param appName the application name
   * @param userId the user identifier
   * @param state initial state map (may be null)
   * @param sessionId session ID (generates UUID if null/empty)
   * @return the created Session
   */
  private Session createSessionInTransaction(
      String appName, String userId, ConcurrentMap<String, Object> state, String sessionId)
      throws java.sql.SQLException {
    String id =
        (sessionId != null && !sessionId.isEmpty()) ? sessionId : UUID.randomUUID().toString();

    Instant now = Instant.now();

    return jdbcTemplate.inTransaction(
        ops -> {
          Map<String, Object> appStateMap = new ConcurrentHashMap<>();
          Map<String, Object> userStateMap = new ConcurrentHashMap<>();
          Map<String, Object> sessionStateMap = new ConcurrentHashMap<>();

          if (state != null) {
            for (Map.Entry<String, Object> entry : state.entrySet()) {
              String key = entry.getKey();
              if (key.startsWith(State.TEMP_PREFIX)) {
                continue;
              }

              if (key.startsWith(State.APP_PREFIX)) {
                String unprefixedKey = key.substring(State.APP_PREFIX.length());
                appStateMap.put(unprefixedKey, entry.getValue());
              } else if (key.startsWith(State.USER_PREFIX)) {
                String unprefixedKey = key.substring(State.USER_PREFIX.length());
                userStateMap.put(unprefixedKey, entry.getValue());
              } else {
                sessionStateMap.put(key, entry.getValue());
              }
            }
          }

          Map<String, Object> appState = upsertAppStateIfNeeded(ops, appName, appStateMap, now);
          Map<String, Object> userState =
              upsertUserStateIfNeeded(ops, appName, userId, userStateMap, now);

          SessionRow row = new SessionRow();
          row.setAppName(appName);
          row.setUserId(userId);
          row.setId(id);
          row.setState(toJson(sessionStateMap));
          row.setCreateTime(now);
          row.setUpdateTime(now);

          sessionDao.insertSession(ops, row);

          return toDomainSession(row, Collections.emptyList(), appState, userState);
        });
  }

  /**
   * Helper method to build a Session from a SessionRow within a transaction.
   *
   * <p>Extracted from {@link #getSession} to improve testability and reduce lambda nesting.
   *
   * @param ops transaction operations
   * @param sessionRow the session row from database
   * @param config optional configuration for event filtering
   * @return the built Session
   */
  private Session buildSessionFromRow(
      JdbcTemplate.JdbcOperations ops, SessionRow sessionRow, Optional<GetSessionConfig> config)
      throws java.sql.SQLException {
    String appName = sessionRow.getAppName();
    String userId = sessionRow.getUserId();
    String sessionId = sessionRow.getId();

    // Convert negative values to positive: -N becomes N (last N events)
    Optional<Integer> limit = config.flatMap(c -> c.numRecentEvents().map(Math::abs));

    List<EventRow> eventRows;
    if (config.isPresent() && config.get().afterTimestamp().isPresent()) {
      Instant afterTimestamp = config.get().afterTimestamp().get();
      eventRows =
          eventDao.listEventsAfterTimestamp(
              ops, appName, userId, sessionId, afterTimestamp, limit, 0);
    } else {
      eventRows = eventDao.listEvents(ops, appName, userId, sessionId, limit);
    }

    Optional<AppStateRow> appStateOpt = stateDao.getAppState(ops, appName);
    Map<String, Object> appState =
        appStateOpt.map(s -> fromJson(s.getState())).orElse(new ConcurrentHashMap<>());

    Optional<UserStateRow> userStateOpt = stateDao.getUserState(ops, appName, userId);
    Map<String, Object> userState =
        userStateOpt.map(s -> fromJson(s.getState())).orElse(new ConcurrentHashMap<>());

    return toDomainSession(sessionRow, eventRows, appState, userState);
  }

  /**
   * Helper method to upsert app state if needed.
   *
   * @param ops transaction operations
   * @param appName application name
   * @param appStateMap state map to upsert (may be empty)
   * @param now current timestamp
   * @return the merged app state map
   */
  private Map<String, Object> upsertAppStateIfNeeded(
      JdbcTemplate.JdbcOperations ops, String appName, Map<String, Object> appStateMap, Instant now)
      throws java.sql.SQLException {
    Optional<AppStateRow> appStateOpt = stateDao.getAppStateForUpdate(ops, appName);
    Map<String, Object> appState;

    if (appStateOpt.isPresent()) {
      appState = fromJson(appStateOpt.get().getState());
      if (!appStateMap.isEmpty()) {
        appState.putAll(appStateMap);
        AppStateRow updatedAppState = new AppStateRow();
        updatedAppState.setAppName(appName);
        updatedAppState.setState(toJson(appState));
        updatedAppState.setUpdateTime(now);
        stateDao.upsertAppState(ops, updatedAppState);
      }
    } else if (!appStateMap.isEmpty()) {
      appState = new ConcurrentHashMap<>(appStateMap);
      AppStateRow newAppState = new AppStateRow();
      newAppState.setAppName(appName);
      newAppState.setState(toJson(appState));
      newAppState.setUpdateTime(now);
      stateDao.upsertAppState(ops, newAppState);
    } else {
      appState = new ConcurrentHashMap<>();
    }

    return appState;
  }

  /**
   * Helper method to upsert user state if needed.
   *
   * @param ops transaction operations
   * @param appName application name
   * @param userId user identifier
   * @param userStateMap state map to upsert (may be empty)
   * @param now current timestamp
   * @return the merged user state map
   */
  private Map<String, Object> upsertUserStateIfNeeded(
      JdbcTemplate.JdbcOperations ops,
      String appName,
      String userId,
      Map<String, Object> userStateMap,
      Instant now)
      throws java.sql.SQLException {
    Optional<UserStateRow> userStateOpt = stateDao.getUserStateForUpdate(ops, appName, userId);
    Map<String, Object> userState;

    if (userStateOpt.isPresent()) {
      userState = fromJson(userStateOpt.get().getState());
      if (!userStateMap.isEmpty()) {
        userState.putAll(userStateMap);
        UserStateRow updatedUserState = new UserStateRow();
        updatedUserState.setAppName(appName);
        updatedUserState.setUserId(userId);
        updatedUserState.setState(toJson(userState));
        updatedUserState.setUpdateTime(now);
        stateDao.upsertUserState(ops, updatedUserState);
      }
    } else if (!userStateMap.isEmpty()) {
      userState = new ConcurrentHashMap<>(userStateMap);
      UserStateRow newUserState = new UserStateRow();
      newUserState.setAppName(appName);
      newUserState.setUserId(userId);
      newUserState.setState(toJson(userState));
      newUserState.setUpdateTime(now);
      stateDao.upsertUserState(ops, newUserState);
    } else {
      userState = new ConcurrentHashMap<>();
    }

    return userState;
  }

  /**
   * Closes this service and releases all database connections.
   *
   * <p>This method shuts down the HikariCP connection pool and releases all associated resources.
   * After calling this method, the service cannot be used again - create a new instance if needed.
   *
   * <p>This method is idempotent - calling it multiple times has no additional effect.
   *
   * <p><b>Thread Safety:</b> This method uses atomic compare-and-set to ensure the connection pool
   * is closed exactly once, even if called concurrently from multiple threads.
   *
   * <p><b>Best Practice:</b> Use try-with-resources to ensure automatic cleanup:
   *
   * <pre>{@code
   * try (DatabaseSessionService service = new DatabaseSessionService(jdbcUrl)) {
   *     // Use the service
   * } // Automatically closed
   * }</pre>
   *
   * @throws IllegalStateException if any operations are attempted after closing
   */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      logger.info("Closing DatabaseSessionService");
      if (dataSource != null && !dataSource.isClosed()) {
        dataSource.close();
        logger.info("HikariCP connection pool closed");
      }
    }
  }
}
