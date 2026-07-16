/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.sessions;

/**
 * @author manoj.kumar
 */
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MapDB implementation of {@link BaseSessionService} for persistent storage. Stores sessions,
 * user state, and app state in a MapDB file.
 *
 * <p>Note: Requires Session, Event, and all objects stored in state maps to be serializable by
 * MapDB's Serializer.java. State merging (app/user state prefixed with {@code _app_} / {@code
 * _user_}) occurs during retrieval operations ({@code getSession}, {@code createSession}).
 */
public final class MapDbSessionService implements BaseSessionService, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(MapDbSessionService.class);
  private final DB db;
  // Key: sessionId -> Value: Session
  private final ConcurrentMap<String, String> sessionsMap;
  // Key: appName:userId -> Value: Map<String, Object> (user state)
  private final ConcurrentMap<String, Map<String, Object>> userStateMap;
  // Key: appName -> Value: Map<String, Object> (app state)
  private final ConcurrentMap<String, Map<String, Object>> appStateMap;

  private static final String SESSIONS_MAP_NAME = "sessions";
  private static final String USER_STATE_MAP_NAME = "userState";
  private static final String APP_STATE_MAP_NAME = "appState";

  /**
   * Creates a new instance of the MapDB session service.
   *
   * @param filePath The path to the MapDB database file.
   * @throws IOException if the database file cannot be opened or created.
   */
  public MapDbSessionService(String filePath) throws IOException {
    Objects.requireNonNull(filePath, "filePath cannot be null");

    // Configure MapDB - use a file, enable transactions, enable MVStore for concurrency/durability
    this.db =
        DBMaker.fileDB(new File(filePath))
            .transactionEnable() // Use transactions for ACID properties
            .executorEnable() // Optional: use separate thread pool for background tasks
            .closeOnJvmShutdown() // Ensure database is closed on JVM shutdown
            .make();

    // Get or create maps using Serializer.java (requires Serializable objects)
    this.sessionsMap =
        db.hashMap(SESSIONS_MAP_NAME, Serializer.STRING, Serializer.JAVA).createOrOpen();
    this.userStateMap =
        db.hashMap(USER_STATE_MAP_NAME, Serializer.STRING, Serializer.JAVA).createOrOpen();
    this.appStateMap =
        db.hashMap(APP_STATE_MAP_NAME, Serializer.STRING, Serializer.JAVA).createOrOpen();

    logger.info("MapDbSessionService initialized with file: {}", filePath);
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    String resolvedSessionId =
        Optional.ofNullable(sessionId)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .orElseGet(() -> UUID.randomUUID().toString());

    // Ensure state map and events list are mutable for the new session
    ConcurrentMap<String, Object> initialState =
        (state == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(state);
    List<Event> initialEvents = new ArrayList<>();

    // Build the Session object (assumes Session.builder creates a mutable state/events)
    Session newSession =
        Session.builder(resolvedSessionId)
            .appName(appName)
            .userId(userId)
            .state(initialState) // Store initial state in session
            .events(initialEvents)
            .lastUpdateTime(Instant.now())
            .build();

    logger.info(newSession.toJson());
    // Store the new session
    sessionsMap.put(resolvedSessionId, newSession.toJson());
    db.commit(); // Commit the change

    // Create a mutable copy for the return value and merge global state
    Session returnCopy = copySession(newSession);
    // Merge global state into the copy before returning
    return Single.just(mergeWithGlobalState(appName, userId, returnCopy));
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(configOpt, "configOpt cannot be null");

    ObjectMapper objectMapper = new ObjectMapper();

    // Retrieve the session by ID
    String sessionJson = sessionsMap.get(sessionId);
    if (sessionJson == null) {
      return Maybe.empty();
    }

    Session storedSession = null;
    try {
      storedSession = objectMapper.readValue(sessionJson, Session.class);
    } catch (JsonProcessingException ex) {
      java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
          .log(Level.SEVERE, null, ex);
    }

    // Also check appName and userId match, although sessionId is the primary key
    if (storedSession == null
        || !appName.equals(storedSession.appName())
        || !userId.equals(storedSession.userId())) {
      return Maybe.empty();
    }

    // Create a mutable copy to apply filters and merge state
    Session sessionCopy = copySession(storedSession);

    // Apply filtering based on config directly to the mutable list in the copy
    GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
    List<Event> eventsInCopy = sessionCopy.events(); // Assumes events() returns mutable list

    config
        .numRecentEvents()
        .ifPresent(
            num -> {
              if (!eventsInCopy.isEmpty() && num < eventsInCopy.size()) {
                // Keep the last 'num' events by removing older ones
                // Create sublist view (modifications affect original list)

                List<Event> eventsToRemove = eventsInCopy.subList(0, eventsInCopy.size() - num);
                eventsToRemove.clear(); // Clear the sublist view, modifying eventsInCopy
              }
            });

    // Only apply timestamp filter if numRecentEvents was not applied
    if (!config.numRecentEvents().isPresent() && config.afterTimestamp().isPresent()) {
      Instant threshold = config.afterTimestamp().get();

      eventsInCopy.removeIf(
          event -> getEventTimestampEpochSeconds(event) < threshold.getEpochSecond());
    }

    // Merge global state into the potentially filtered copy and return
    return Maybe.just(mergeWithGlobalState(appName, userId, sessionCopy));
  }

  // Helper to get event timestamp as epoch seconds (adapt based on Event.timestamp() actual type)
  private long getEventTimestampEpochSeconds(Event event) {
    // Assuming Event.timestamp() returns a value compatible with epoch seconds
    // If it returns Instant, use event.timestamp().getEpochSecond()
    return event.timestamp();
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    // Assume sessionsMap, appName, and userId are already defined
    // Assume sessionsMap is available here (Map<String, Session>)
    System.out.println("Printing details for all sessions:");
    sessionsMap.forEach(
        (sessionId, sessiont) -> {
          ObjectMapper objectMapper = new ObjectMapper();
          Session session = null;
          try {
            session = objectMapper.readValue(sessiont, Session.class);
          } catch (JsonProcessingException ex) {
            java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
                .log(Level.SEVERE, null, ex);
          }
          System.out.println(
              "Session ID: "
                  + sessionId
                  + ", App Name: "
                  + session.appName()
                  + ", User ID: "
                  + session.userId());
        });
    // Iterate through all sessions and filter by appName and userId
    List<Session> sessionCopies =
        sessionsMap.values().stream()
            // .filter(session -> appName.equals(session.appName()) &&
            // userId.equals(session.userId()))
            .map(this::copySessionMetadata) // Create metadata copies
            .collect(Collectors.toCollection(ArrayList::new));

    return Single.just(ListSessionsResponse.builder().sessions(sessionCopies).build());
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    // Check if the session exists and belongs to the correct app/user before deleting
    ObjectMapper objectMapper = new ObjectMapper();
    Session storedSession = null;
    try {
      storedSession = objectMapper.readValue(sessionsMap.get(sessionId), Session.class);
    } catch (JsonProcessingException ex) {
      java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
          .log(Level.SEVERE, null, ex);
    }
    ;
    if (storedSession != null
        && appName.equals(storedSession.appName())
        && userId.equals(storedSession.userId())) {
      sessionsMap.remove(sessionId);
      // Note: This implementation, like the InMemory one, does NOT delete
      // associated user/app state when a session is deleted.
      db.commit(); // Commit the change
    } else {
      logger.warn(
          "Attempted to delete session {} for user {} in app {}, but it was not found or did not match criteria.",
          sessionId,
          userId,
          appName);
    }
    return Completable.complete(); // Operation completes even if session wasn't found
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    // Retrieve the session by ID
    ObjectMapper objectMapper = new ObjectMapper();
    Session storedSession = null;
    try {
      storedSession = objectMapper.readValue(sessionsMap.get(sessionId), Session.class);
    } catch (JsonProcessingException ex) {
      java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
          .log(Level.SEVERE, null, ex);
    }
    ;

    // Also check appName and userId match
    if (storedSession == null
        || !appName.equals(storedSession.appName())
        || !userId.equals(storedSession.userId())) {
      return Single.just(ListEventsResponse.builder().build());
    }

    // Return a copy of the events list (ImmutableList is safe)
    ImmutableList<Event> eventsCopy =
        ImmutableList.copyOf(storedSession.events()); // Assumes events() returns a List
    return Single.just(ListEventsResponse.builder().events(eventsCopy).build());
  }

  @CanIgnoreReturnValue
  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");

    String appName = session.appName();
    String userId = session.userId();
    String sessionId = session.id();

    // Retrieve the *actual* stored session from MapDB
    // We need to modify the stored session's event list and possibly state
    ObjectMapper objectMapper = new ObjectMapper();
    Session storedSession = null;
    try {
      storedSession = objectMapper.readValue(sessionsMap.get(sessionId), Session.class);
    } catch (JsonProcessingException ex) {
      java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
          .log(Level.SEVERE, null, ex);
    }
    ;

    if (storedSession == null) {
      logger.warn(
          String.format(
              "appendEvent called for session %s which is not found in MapDbSessionService",
              sessionId));
      // Should we create it? The InMemory implementation just logs and does nothing.
      // Let's follow that behavior for now.
      return Single.error(new IllegalArgumentException("Session not found: " + sessionId));
    }

    // --- Update User/App State ---
    EventActions actions = event.actions();
    if (actions != null) {
      Map<String, Object> stateDelta = actions.stateDelta();
      if (stateDelta != null && !stateDelta.isEmpty()) {
        stateDelta.forEach(
            (key, value) -> {
              if (key.startsWith(State.APP_PREFIX)) {
                String appStateKey = key.substring(State.APP_PREFIX.length());
                // Get, modify, and re-put the app state map
                Map<String, Object> currentAppState =
                    appStateMap.computeIfAbsent(appName, k -> new ConcurrentHashMap<>());
                currentAppState.put(appStateKey, value);
                appStateMap.put(appName, currentAppState); // Re-put to ensure persistence
              } else if (key.startsWith(State.USER_PREFIX)) {
                String userStateKey = key.substring(State.USER_PREFIX.length());
                // Get, modify, and re-put the user state map
                Map<String, Object> currentUserState =
                    userStateMap.computeIfAbsent(
                        appName + ":" + userId, k -> new ConcurrentHashMap<>());
                currentUserState.put(userStateKey, value);
                userStateMap.put(
                    appName + ":" + userId, currentUserState); // Re-put to ensure persistence
              }
            });
        // Commit state changes
        db.commit();
      }
    }

    // --- Append Event to Stored Session ---
    // Get the mutable events list from the stored session
    List<Event> storedEvents = storedSession.events(); // Assumes events() returns mutable list
    if (storedEvents != null) {
      storedEvents.add(event); // Append the event

      // Update the last update time
      storedSession.lastUpdateTime(getInstantFromEvent(event));

      // Put the modified session back into the map
      sessionsMap.put(sessionId, storedSession.toJson());

      // Commit the session changes
      db.commit();

      // The event should also be added to the *passed-in* session object, as per BaseSessionService
      // contract
      // (though the stored session is the persistent one)
      BaseSessionService.super.appendEvent(session, event);

      return Single.just(event);
    } else {
      // This case should ideally not happen if Session is constructed correctly
      logger.error("Stored session {} events list is null!", sessionId);
      return Single.error(new IllegalStateException("Stored session events list is null"));
    }
  }

  /** Converts an event's timestamp to an Instant. Adapt based on actual Event structure. */
  // TODO: have Event.timestamp() return Instant directly
  private Instant getInstantFromEvent(Event event) {
    // Assuming Event.timestamp() returns a double representing epoch seconds
    double epochSeconds = event.timestamp();
    long seconds = (long) epochSeconds;
    long nanos = (long) ((epochSeconds - seconds) * 1_000_000_000L);
    return Instant.ofEpochSecond(seconds, nanos);
  }

  /**
   * Creates a shallow copy of the session, but with deep copies of the mutable state map and events
   * list. Assumes Session provides necessary getters and a suitable constructor/setters that result
   * in mutable collections.
   *
   * @param original The session to copy.
   * @return A new Session instance with copied data, including mutable collections.
   */
  private Session copySession(Session original) {
    // Assumes original.state() and original.events() return collections that
    // can be copied into new mutable ones (ConcurrentHashMap, ArrayList).
    // Assumes Session.builder can accept these mutable copies.
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        // Create mutable copies of the state map and events list
        .state(new ConcurrentHashMap<>(original.state()))
        .events(new ArrayList<>(original.events()))
        .lastUpdateTime(original.lastUpdateTime())
        .build();
  }

  /**
   * Creates a copy of the session containing only metadata fields (ID, appName, userId, timestamp).
   * State and Events are explicitly *not* copied.
   *
   * @param original The session whose metadata to copy.
   * @return A new Session instance with only metadata fields populated.
   */
  private Session copySessionMetadata(Session original) {
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .lastUpdateTime(original.lastUpdateTime())
        // Explicitly set state and events to empty/null for metadata copy
        .state(new ConcurrentHashMap<>())
        .events(new ArrayList<>()) // Or ImmutableList.of() or null if builder handles null
        .build();
  }

  private Session copySessionMetadata(String Session_original) {
    ObjectMapper objectMapper = new ObjectMapper();
    Session original = null;
    try {
      original = objectMapper.readValue(Session_original, Session.class);
    } catch (JsonProcessingException ex) {
      java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
          .log(Level.SEVERE, null, ex);
    }

    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .lastUpdateTime(original.lastUpdateTime())
        // Explicitly set state and events to empty/null for metadata copy
        .state(new ConcurrentHashMap<>())
        .events(new ArrayList<>()) // Or ImmutableList.of() or null if builder handles null
        .build();
  }

  /**
   * Merges the app-specific and user-specific state (stored separately) into the provided *mutable*
   * session's state map.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param session The mutable session whose state map will be augmented.
   * @return The same session instance passed in, now with merged state.
   */
  @CanIgnoreReturnValue
  private Session mergeWithGlobalState(String appName, String userId, Session session) {
    Map<String, Object> sessionState =
        session.state(); // Assumes session.state() returns a mutable map

    // Merge App State
    Map<String, Object> currentAppState = appStateMap.get(appName);
    if (currentAppState != null) {
      currentAppState.forEach((key, value) -> sessionState.put(State.APP_PREFIX + key, value));
    }

    // Merge User State
    Map<String, Object> currentUserState = userStateMap.get(appName + ":" + userId);
    if (currentUserState != null) {
      currentUserState.forEach((key, value) -> sessionState.put(State.USER_PREFIX + key, value));
    }

    return session;
  }

  /** Closes the MapDB database connection. Should be called on application shutdown. */
  @Override
  public void close() throws IOException {
    if (db != null && !db.isClosed()) {
      logger.info("Closing MapDbSessionService database.");
      db.close();
    }
  }

  // Add a finalize method as a safety net, though try-with-resources and shutdown hook are
  // preferred
  @Override
  protected void finalize() throws Throwable {
    try {
      close();
    } finally {
      super.finalize();
    }
  }
}
