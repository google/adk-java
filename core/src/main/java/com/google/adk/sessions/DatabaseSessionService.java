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

import com.google.adk.events.Event;
import com.google.adk.sessions.db.entity.SessionId;
import com.google.adk.sessions.db.entity.StorageAppState;
import com.google.adk.sessions.db.entity.StorageEvent;
import com.google.adk.sessions.db.entity.StorageSession;
import com.google.adk.sessions.db.entity.StorageUserState;
import com.google.adk.sessions.db.entity.UserStateId;
import com.google.adk.sessions.db.util.DatabaseDialectDetector;
import com.google.adk.sessions.db.util.EntityManagerFactoryProvider;
import com.google.common.annotations.VisibleForTesting;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseSessionService} implementation that stores sessions in a relational database.
 * Supports automatic table creation on initialization using flyway.
 */
public class DatabaseSessionService implements BaseSessionService {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseSessionService.class);

  // Default database options if not specified
  private static final int DEFAULT_FETCH_LIMIT = 1000;

  // The Entity Manager Factory for database access
  private final EntityManagerFactory emf;

  // The database dialect being used
  private final String dialect;

  /**
   * Creates a new DatabaseSessionService with the specified database URL.
   *
   * @param dbUrl The database URL to connect to
   */
  public DatabaseSessionService(String dbUrl) {
    this(dbUrl, Collections.emptyMap());
  }

  /**
   * Creates a new DatabaseSessionService with the specified database URL and configuration.
   *
   * <p>The database URL should include all connection parameters, including credentials and SSL
   * configuration, following standard JDBC URL format:
   *
   * <ul>
   *   <li>PostgreSQL: {@code
   *       jdbc:postgresql://host:5432/dbname?user=username&password=pass&ssl=true&sslmode=verify-full}
   *   <li>MySQL: {@code
   *       jdbc:mysql://host:3306/dbname?user=username&password=pass&useSSL=true&requireSSL=true}
   *   <li>H2 (in-memory): {@code jdbc:h2:mem:testdb}
   *   <li>SQLite: {@code jdbc:sqlite:./sessions.db}
   * </ul>
   *
   * <p>For production use, load the database URL from environment variables to avoid hardcoding
   * credentials:
   *
   * <pre>{@code
   * String dbUrl = System.getenv("DATABASE_URL");
   * DatabaseSessionService service = new DatabaseSessionService(dbUrl);
   * }</pre>
   *
   * @param dbUrl The database URL to connect to (including credentials)
   * @param properties Additional Hibernate configuration properties (optional)
   */
  public DatabaseSessionService(String dbUrl, Map<String, Object> properties) {
    Objects.requireNonNull(dbUrl, "Database URL cannot be null");

    // Detect dialect from URL
    this.dialect = DatabaseDialectDetector.detectDialect(dbUrl);

    // Apply Flyway migrations first
    try {
      // Use dialect-specific migration folder to avoid duplicate version conflicts
      String dialectFolder = extractDialectFolderName(this.dialect);
      String flywayLocation = "classpath:db/migration/" + dialectFolder;

      // Configure Flyway with the database URL
      // Flyway will extract credentials from the JDBC URL automatically
      // Use system property or environment variable for FLYWAY_BASELINE_ON_MIGRATE (default: false)
      String baselineOnMigrateStr =
          System.getProperty(
              "FLYWAY_BASELINE_ON_MIGRATE",
              System.getenv().getOrDefault("FLYWAY_BASELINE_ON_MIGRATE", "false"));
      boolean baselineOnMigrate = Boolean.parseBoolean(baselineOnMigrateStr);

      Flyway flyway =
          Flyway.configure()
              .dataSource(dbUrl, null, null)
              .locations(flywayLocation)
              .cleanDisabled(true)
              .lockRetryCount(50)
              .baselineOnMigrate(baselineOnMigrate)
              .load();

      // Run migrations with retry logic for multi-pod scenarios
      MigrateResult migrateResult = flyway.migrate();

      if (migrateResult.migrationsExecuted > 0) {
        logger.info(
            "Flyway migrations completed successfully. Applied {} new migrations.",
            migrateResult.migrationsExecuted);
      } else {
        logger.info("Database schema is already up to date. No migrations applied.");
      }
    } catch (FlywayException e) {
      logger.warn("Flyway migration issue: {}", e.getMessage());

      if (e.getMessage() != null && e.getMessage().contains("lock")) {
        logger.warn(
            "Migration lock detected - another instance may be migrating. Validating schema...");
        try {
          String dialectFolder = extractDialectFolderName(this.dialect);
          String flywayLocation = "classpath:db/migration/" + dialectFolder;

          Flyway flyway =
              Flyway.configure()
                  .dataSource(dbUrl, null, null)
                  .locations(flywayLocation)
                  .cleanDisabled(true)
                  .baselineOnMigrate(true)
                  .load();

          flyway.validate();
        } catch (Exception validationEx) {
          logger.error("Schema validation failed after migration lock", validationEx);
          throw new RuntimeException(
              "Failed to validate database schema after migration lock", validationEx);
        }
      } else {
        logger.error("Error applying Flyway migrations", e);
        throw new RuntimeException("Failed to apply database migrations", e);
      }
    }

    // Create entity manager factory with specified options, changing schema mode to 'none'
    Map<String, Object> config = new HashMap<>(properties);
    // Override hbm2ddl.auto to none since Flyway handles the schema creation and validation
    config.put("hibernate.hbm2ddl.auto", "none");
    this.emf = EntityManagerFactoryProvider.createEntityManagerFactory(dbUrl, config);
  }

  /**
   * Creates a new DatabaseSessionService with a pre-configured EntityManagerFactory. This
   * constructor is primarily used for testing.
   *
   * @param emf The EntityManagerFactory to use
   */
  @VisibleForTesting
  DatabaseSessionService(EntityManagerFactory emf) {
    this.emf = emf;
    this.dialect = "Test";
  }

  @Override
  public Single<Session> createSession(
      String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {

    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    return Single.fromCallable(
            () ->
                executeInTransaction(
                    em -> {
                      // Split state by prefix
                      Map<String, Object> appStateMap = new HashMap<>();
                      Map<String, Object> userStateMap = new HashMap<>();
                      Map<String, Object> sessionStateMap = new HashMap<>();

                      if (state != null) {
                        for (Map.Entry<String, Object> entry : state.entrySet()) {
                          String key = entry.getKey();
                          // Skip temp: prefixed keys
                          if (key.startsWith(State.TEMP_PREFIX)) {
                            continue;
                          }

                          // Route keys to different tables based on prefix
                          if (key.startsWith(State.APP_PREFIX)) {
                            appStateMap.put(key, entry.getValue());
                          } else if (key.startsWith(State.USER_PREFIX)) {
                            userStateMap.put(key, entry.getValue());
                          } else {
                            sessionStateMap.put(key, entry.getValue());
                          }
                        }
                      }

                      // Get or create app state and merge new keys
                      StorageAppState appState = getOrCreateAppState(em, appName);
                      if (!appStateMap.isEmpty()) {
                        appState.getState().putAll(appStateMap);
                        appState.setUpdateTime(Instant.now());
                        em.merge(appState);
                      }

                      // Get or create user state and merge new keys
                      UserStateId userStateId = new UserStateId(appName, userId);
                      StorageUserState userState = getOrCreateUserState(em, userStateId);
                      if (!userStateMap.isEmpty()) {
                        userState.getState().putAll(userStateMap);
                        userState.setUpdateTime(Instant.now());
                        em.merge(userState);
                      }

                      // Create session entity with only session-specific state
                      StorageSession session = new StorageSession();
                      session.setAppName(appName);
                      session.setUserId(userId);
                      session.setId(sessionId != null ? sessionId : UUID.randomUUID().toString());
                      session.setState(sessionStateMap);
                      session.setCreateTime(Instant.now());
                      session.setUpdateTime(Instant.now());

                      em.persist(session);

                      // Convert to domain object with merged state
                      return createDomainSessionWithMergedState(session, appState, userState);
                    },
                    "Error creating session"))
        .subscribeOn(Schedulers.io());
  }

  /** {@inheritDoc} */
  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {

    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(config, "configOpt cannot be null");

    return Maybe.fromCallable(
            () ->
                executeInTransaction(
                    em -> {
                      // Create composite key for session lookup
                      SessionId id = new SessionId(appName, userId, sessionId);

                      // Find session
                      StorageSession session = em.find(StorageSession.class, id);
                      if (session == null) {
                        return null; // No session found, Maybe will be empty
                      }

                      // Load events for the session with optional filtering
                      CriteriaBuilder cb = em.getCriteriaBuilder();
                      CriteriaQuery<StorageEvent> eventQuery = cb.createQuery(StorageEvent.class);
                      Root<StorageEvent> eventRoot = eventQuery.from(StorageEvent.class);

                      // Base filters for session identification
                      jakarta.persistence.criteria.Predicate basePredicate =
                          cb.and(
                              cb.equal(eventRoot.get("appName"), appName),
                              cb.equal(eventRoot.get("userId"), userId),
                              cb.equal(eventRoot.get("sessionId"), sessionId));

                      // Apply timestamp filter if present in config
                      if (config.isPresent() && config.get().afterTimestamp().isPresent()) {
                        Instant afterTimestamp = config.get().afterTimestamp().get();
                        basePredicate =
                            cb.and(
                                basePredicate,
                                cb.greaterThan(eventRoot.get("timestamp"), afterTimestamp));
                      }

                      eventQuery.where(basePredicate);

                      // Determine sort order based on whether we need to limit results
                      boolean needsReverse = false;
                      if (config.isPresent() && config.get().numRecentEvents().isPresent()) {
                        // Order descending to get most recent events first
                        eventQuery.orderBy(cb.desc(eventRoot.get("timestamp")));
                        needsReverse = true;
                      } else {
                        // Order ascending for chronological order
                        eventQuery.orderBy(cb.asc(eventRoot.get("timestamp")));
                      }

                      // Execute query with optional limit
                      TypedQuery<StorageEvent> query = em.createQuery(eventQuery);
                      if (config.isPresent() && config.get().numRecentEvents().isPresent()) {
                        int numEvents = config.get().numRecentEvents().get();
                        if (numEvents >= 0) {
                          query.setMaxResults(numEvents);
                        }
                      }

                      List<StorageEvent> events = query.getResultList();

                      // Reverse if we fetched in descending order
                      if (needsReverse) {
                        Collections.reverse(events);
                      }

                      // IMPORTANT: Use clear() + addAll() to maintain Hibernate's collection
                      // tracking.
                      // Replacing the collection reference (e.g., setEvents()) would break cascade
                      // operations
                      // and cause the EntityManager to lose change tracking on this relationship.
                      session.getEvents().clear();
                      session.getEvents().addAll(events);

                      // Find app state
                      StorageAppState appState = em.find(StorageAppState.class, appName);

                      // Find user state
                      UserStateId userStateId = new UserStateId(appName, userId);
                      StorageUserState userState = em.find(StorageUserState.class, userStateId);

                      // Convert to domain object with merged state
                      return createDomainSessionWithMergedState(session, appState, userState);
                    },
                    "Error getting session"))
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
            () ->
                executeReadOnly(
                    em -> {
                      List<Session> sessions = new ArrayList<>();

                      // Create query to find sessions
                      CriteriaBuilder cb = em.getCriteriaBuilder();
                      CriteriaQuery<StorageSession> cq = cb.createQuery(StorageSession.class);
                      Root<StorageSession> root = cq.from(StorageSession.class);

                      // Apply filters
                      cq.where(
                          cb.and(
                              cb.equal(root.get("appName"), appName),
                              cb.equal(root.get("userId"), userId)));

                      // Order by update time descending
                      cq.orderBy(cb.desc(root.get("updateTime")));

                      // Execute query
                      List<StorageSession> results =
                          em.createQuery(cq).setMaxResults(DEFAULT_FETCH_LIMIT).getResultList();

                      // Find app and user state
                      StorageAppState appState = em.find(StorageAppState.class, appName);
                      if (appState == null) {
                        appState = new StorageAppState();
                        appState.setAppName(appName);
                        appState.setState(new HashMap<>());
                        appState.setUpdateTime(Instant.now());
                      }

                      UserStateId userStateId = new UserStateId(appName, userId);
                      StorageUserState userState = em.find(StorageUserState.class, userStateId);
                      if (userState == null) {
                        userState = new StorageUserState();
                        userState.setAppName(appName);
                        userState.setUserId(userId);
                        userState.setState(new HashMap<>());
                        userState.setUpdateTime(Instant.now());
                      }

                      // Convert to domain objects
                      for (StorageSession result : results) {
                        // For listing, we don't need to load all events
                        result.setEvents(Collections.emptyList());
                        sessions.add(
                            createDomainSessionWithMergedState(result, appState, userState));
                      }

                      return ListSessionsResponse.builder().sessions(sessions).build();
                    },
                    "Error listing sessions"))
        .subscribeOn(Schedulers.io());
  }

  /**
   * Lists the events within a specific session.
   *
   * <p>This implementation delegates to the 5-parameter version with default values:
   *
   * <ul>
   *   <li>pageSize: {@link #DEFAULT_FETCH_LIMIT} (1000)
   *   <li>pageToken: null (start from the beginning)
   * </ul>
   *
   * @param appName The name of the application
   * @param userId The identifier of the user
   * @param sessionId The unique identifier of the session whose events are to be listed
   * @return A {@link ListEventsResponse} containing a list of events and an optional token for
   *     retrieving the next page
   * @throws SessionNotFoundException if the session doesn't exist
   * @throws RuntimeException for other listing errors
   */
  /** {@inheritDoc} */
  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return listEvents(appName, userId, sessionId, DEFAULT_FETCH_LIMIT, null);
  }

  /**
   * Lists the events within a specific session with pagination support.
   *
   * @param appName The name of the application
   * @param userId The identifier of the user
   * @param sessionId The unique identifier of the session whose events are to be listed
   * @param pageSize The maximum number of events to return in a single page
   * @param pageToken A token for pagination, representing the offset
   * @return A ListEventsResponse containing a list of events and an optional token for the next
   *     page
   */
  public Single<ListEventsResponse> listEvents(
      String appName, String userId, String sessionId, int pageSize, @Nullable String pageToken) {
    return Single.fromCallable(
            () ->
                executeReadOnly(
                    em -> {
                      // Parse page token once with proper error handling
                      int offset = 0;
                      if (pageToken != null) {
                        try {
                          offset = Integer.parseInt(pageToken);
                        } catch (NumberFormatException e) {
                          logger.warn("Invalid page token: {}. Defaulting to offset 0.", pageToken);
                        }
                      }

                      // Create query to find events
                      CriteriaBuilder cb = em.getCriteriaBuilder();
                      CriteriaQuery<StorageEvent> cq = cb.createQuery(StorageEvent.class);
                      Root<StorageEvent> root = cq.from(StorageEvent.class);

                      // Apply filters
                      cq.where(
                          cb.and(
                              cb.equal(root.get("appName"), appName),
                              cb.equal(root.get("userId"), userId),
                              cb.equal(root.get("sessionId"), sessionId)));

                      // Order by timestamp ascending
                      cq.orderBy(cb.asc(root.get("timestamp")));

                      // Execute query with pagination
                      TypedQuery<StorageEvent> query = em.createQuery(cq);
                      if (offset > 0) {
                        query.setFirstResult(offset);
                      }
                      query.setMaxResults(pageSize);

                      List<StorageEvent> results = query.getResultList();

                      // Convert to domain objects
                      List<Event> events =
                          results.stream()
                              .map(StorageEvent::toDomainEvent)
                              .collect(Collectors.toList());

                      // Calculate next page token
                      String nextPageToken = null;
                      if (results.size() >= pageSize) {
                        nextPageToken = String.valueOf(offset + pageSize);
                      }

                      ListEventsResponse.Builder responseBuilder =
                          ListEventsResponse.builder().events(events);
                      if (nextPageToken != null) {
                        responseBuilder.nextPageToken(nextPageToken);
                      }
                      return responseBuilder.build();
                    },
                    "Error listing events"))
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");

    return BaseSessionService.super
        .appendEvent(session, event)
        .flatMap(
            updatedEvent ->
                appendEvent(session.appName(), session.userId(), session.id(), event)
                    .map(dbSession -> updatedEvent));
  }

  /**
   * Appends an event to a session identified by app name, user ID, and session ID.
   *
   * @param appName The name of the application
   * @param userId The identifier of the user
   * @param sessionId The unique identifier of the session
   * @param event The event to append
   * @return The updated session
   */
  public Single<Session> appendEvent(String appName, String userId, String sessionId, Event event) {
    return Single.fromCallable(
            () ->
                executeInTransaction(
                    em -> {
                      // Find session
                      SessionId id = new SessionId(appName, userId, sessionId);
                      StorageSession session =
                          em.find(StorageSession.class, id, LockModeType.PESSIMISTIC_WRITE);
                      if (session == null) {
                        throw new SessionNotFoundException(
                            String.format(
                                "Session not found: appName=%s, userId=%s, sessionId=%s",
                                appName, userId, sessionId));
                      }

                      // Find app state , create new entry in app_states table if not present
                      StorageAppState appState = em.find(StorageAppState.class, appName);
                      if (appState == null) {
                        appState = getOrCreateAppState(em, appName);
                      }

                      // Find user state , create new entry in user_states table if not present
                      UserStateId userStateId = new UserStateId(appName, userId);
                      StorageUserState userState = em.find(StorageUserState.class, userStateId);
                      if (userState == null) {
                        userState = getOrCreateUserState(em, userStateId);
                      }

                      // Process state delta if present in event
                      if (event.actions() != null && event.actions().stateDelta() != null) {
                        ConcurrentMap<String, Object> stateDelta = event.actions().stateDelta();

                        Map<String, Object> appStateDelta = new HashMap<>();
                        Map<String, Object> userStateDelta = new HashMap<>();
                        Map<String, Object> sessionStateDelta = new HashMap<>();

                        // Split delta by prefix
                        for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
                          String key = entry.getKey();
                          if (key.startsWith(State.APP_PREFIX)) {
                            appStateDelta.put(key, entry.getValue());
                          } else if (key.startsWith(State.USER_PREFIX)) {
                            userStateDelta.put(key, entry.getValue());
                          } else if (!key.startsWith(State.TEMP_PREFIX)) {
                            sessionStateDelta.put(key, entry.getValue());
                          }
                        }

                        // Update app_states - state , update_time column
                        if (!appStateDelta.isEmpty()) {
                          appState.getState().putAll(appStateDelta);
                          appState.setUpdateTime(Instant.now());
                          em.merge(appState);
                        }

                        // Update user_states - state , update_time column
                        if (!userStateDelta.isEmpty()) {
                          userState.getState().putAll(userStateDelta);
                          userState.setUpdateTime(Instant.now());
                          em.merge(userState);
                        }

                        // Update session state
                        if (!sessionStateDelta.isEmpty()) {
                          session.getState().putAll(sessionStateDelta);
                        }
                      }

                      // Create event entity
                      StorageEvent storageEvent = StorageEvent.fromDomainEvent(event, session);
                      session.addEvent(storageEvent);

                      // Update session timestamp
                      session.setUpdateTime(Instant.now());

                      // Save changes
                      em.persist(storageEvent);
                      em.merge(session);

                      // Convert to domain object with merged state
                      return createDomainSessionWithMergedState(session, appState, userState);
                    },
                    "Error appending event"))
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromCallable(
            () -> {
              executeInTransaction(
                  em -> {
                    // Find session
                    SessionId id = new SessionId(appName, userId, sessionId);
                    StorageSession session = em.find(StorageSession.class, id);
                    if (session == null) {
                      throw new SessionNotFoundException(
                          String.format(
                              "Session not found: appName=%s, userId=%s, sessionId=%s",
                              appName, userId, sessionId));
                    }

                    // Delete session (cascade will delete events)
                    em.remove(session);

                    return null;
                  },
                  "Error deleting session");
              return null;
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Cleans up resources used by this service. This method should be called when the service is no
   * longer needed.
   */
  public void close() {
    if (emf != null && emf.isOpen()) {
      emf.close();
    }
  }

  /**
   * Executes a database operation within a transaction.
   *
   * @param operation The operation to execute
   * @param errorMessage The error message to log on failure
   * @param <T> The return type
   * @return The result of the operation
   */
  private <T> T executeInTransaction(
      java.util.function.Function<EntityManager, T> operation, String errorMessage) {
    EntityManager em = emf.createEntityManager();
    try {
      em.getTransaction().begin();
      T result = operation.apply(em);
      em.getTransaction().commit();
      return result;
    } catch (Exception e) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      if (e instanceof SessionNotFoundException) {
        throw e;
      }
      logger.error(errorMessage, e);
      throw new RuntimeException(errorMessage, e);
    } finally {
      em.close();
    }
  }

  /**
   * Executes a read-only database operation without an explicit transaction.
   *
   * @param operation The operation to execute
   * @param errorMessage The error message to log on failure
   * @param <T> The return type
   * @return The result of the operation
   */
  private <T> T executeReadOnly(
      java.util.function.Function<EntityManager, T> operation, String errorMessage) {
    EntityManager em = emf.createEntityManager();
    try {
      return operation.apply(em);
    } catch (Exception e) {
      logger.error(errorMessage, e);
      throw new RuntimeException(errorMessage, e);
    } finally {
      em.close();
    }
  }

  /**
   * Gets or creates an app state entity.
   *
   * @param em The EntityManager
   * @param appName The application name
   * @return The app state entity
   */
  private StorageAppState getOrCreateAppState(EntityManager em, String appName) {
    StorageAppState appState = em.find(StorageAppState.class, appName);
    if (appState == null) {
      appState = new StorageAppState();
      appState.setAppName(appName);
      appState.setState(new HashMap<>());
      appState.setUpdateTime(Instant.now());
      try {
        em.persist(appState);
        em.flush();
      } catch (PersistenceException e) {
        StorageAppState existingState = em.find(StorageAppState.class, appName);
        if (existingState != null) {
          return existingState;
        }
        throw e;
      }
    }
    return appState;
  }

  /**
   * Gets or creates a user state entity.
   *
   * @param em The EntityManager
   * @param userStateId The user state ID
   * @return The user state entity
   */
  private StorageUserState getOrCreateUserState(EntityManager em, UserStateId userStateId) {
    StorageUserState userState = em.find(StorageUserState.class, userStateId);
    if (userState == null) {
      userState = new StorageUserState();
      userState.setAppName(userStateId.getAppName());
      userState.setUserId(userStateId.getUserId());
      userState.setState(new HashMap<>());
      userState.setUpdateTime(Instant.now());
      try {
        em.persist(userState);
        em.flush();
      } catch (PersistenceException e) {
        StorageUserState existingState = em.find(StorageUserState.class, userStateId);
        if (existingState != null) {
          return existingState;
        }
        throw e;
      }
    }
    return userState;
  }

  private Session createDomainSessionWithMergedState(
      StorageSession storage, StorageAppState appState, StorageUserState userState) {
    // Merge state from all three tables: app -> user -> session
    ConcurrentHashMap<String, Object> mergedState = new ConcurrentHashMap<>();

    // 1. Add app state (lowest priority)
    if (appState != null && appState.getState() != null) {
      mergedState.putAll(appState.getState());
    }

    // 2. Add user state (medium priority, overwrites app state)
    if (userState != null && userState.getState() != null) {
      mergedState.putAll(userState.getState());
    }

    // 3. Add session state (highest priority, overwrites user and app state)
    if (storage.getState() != null) {
      mergedState.putAll(storage.getState());
    }

    // Convert storage entity to domain object with merged state
    Session.Builder sessionBuilder =
        Session.builder(storage.getId())
            .appName(storage.getAppName())
            .userId(storage.getUserId())
            .state(mergedState)
            .lastUpdateTime(storage.getUpdateTime());

    // Convert events if needed - use ArrayList for mutability
    if (storage.getEvents() != null && !storage.getEvents().isEmpty()) {
      List<Event> events =
          storage.getEvents().stream()
              .map(StorageEvent::toDomainEvent)
              .collect(Collectors.toCollection(ArrayList::new));
      sessionBuilder.events(events);
    }

    return sessionBuilder.build();
  }

  /**
   * Extracts the dialect folder name from the Hibernate dialect class name.
   *
   * @param dialect The Hibernate dialect class name
   * @return The simplified dialect name for folder lookup
   */
  private String extractDialectFolderName(String dialect) {
    if (dialect == null) {
      return "postgresql"; // Default to PostgreSQL if unknown
    }

    // Extract the database name from the dialect class
    if (dialect.contains("PostgreSQL")) {
      return "postgresql";
    } else if (dialect.contains("MySQL")) {
      return "mysql";
    } else if (dialect.contains("H2")) {
      return "h2";
    } else if (dialect.contains("SQLite")) {
      return "sqlite";
    } else if (dialect.contains("Spanner")) {
      return "spanner";
    }

    logger.warn("Unknown dialect '{}', defaulting to PostgreSQL", dialect);
    return "postgresql";
  }
}
