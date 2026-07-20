package com.google.adk.sessions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.redis.RedisConnection;
import com.google.adk.utils.PostgresDBHelper;
import com.google.adk.utils.PropertiesHelper;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject; // Used for the return type of getSession in the helper
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Service implementation for managing sessions using PostgreSQL as the backend. */
public class PostgresSessionService implements BaseSessionService, AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(PostgresSessionService.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RedisConnection redisConnection;

  public PostgresSessionService() {
    String redisUri = PropertiesHelper.getInstance().getValue("redis_uri");
    this.redisConnection = new RedisConnection(redisUri);
  }

  @SuppressWarnings("null")
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

    ConcurrentMap<String, Object> initialState =
        state == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(state);
    List<Event> initialEvents = new ArrayList<>();
    Instant now = Instant.now();
    Session newSession =
        Session.builder(resolvedSessionId)
            .appName(appName)
            .userId(userId)
            .state(initialState)
            .events(initialEvents)
            .lastUpdateTime(now)
            .build();

    logger.info("Attempting to create session: {}", resolvedSessionId);

    try {
      /*
       * Save the session to the database.
       * This will handle both insert and update (upsert) logic.
       */
      PostgresDBHelper.getInstance().saveSession(resolvedSessionId, newSession);
      logger.info("Session {} created successfully.", resolvedSessionId);
      return Single.just(this.copySession(newSession));
    } catch (Exception ex) {
      logger.error("Error creating session {}: {}", resolvedSessionId, ex.getMessage(), ex);
      return Single.error(
          new RuntimeException("Failed to create session: " + resolvedSessionId, ex));
    }
  }

  /**
   * Creates a deep copy of a Session object. Necessary because Session objects might be modified,
   * and we want to return immutable copies to callers from persistence layers.
   *
   * @param original The original Session object.
   * @return A new Session object with copied mutable fields.
   */
  private Session copySession(Session original) {
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .state(new ConcurrentHashMap<>(original.state())) // Deep copy for state map
        .events(new ArrayList<>(original.events())) // Deep copy for events list
        .lastUpdateTime(original.lastUpdateTime())
        .build();
  }

  @Override
  public Maybe<Session> getSession(
      String appName,
      String userId,
      String sessionId,
      @Nullable Optional<GetSessionConfig> config) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    logger.info(
        "Attempting to get session: {} for app: {} and user: {}", sessionId, appName, userId);

    try {
      JSONObject storedSessionJson = getSessionFromRedisOrPostgres(sessionId);
      if (storedSessionJson != null) {
        // Deserialize the JSONObject back into a Session object using ObjectMapper
        Session session = objectMapper.readValue(storedSessionJson.toString(), Session.class);

        // Validate that the session belongs to the specified app and user
        if (!appName.equals(session.appName()) || !userId.equals(session.userId())) {
          logger.warn(
              "Session {} found but belongs to different app/user. Expected: {}/{}, Found: {}/{}",
              sessionId,
              appName,
              userId,
              session.appName(),
              session.userId());
          return Maybe.empty();
        }

        logger.info("Session {} retrieved successfully.", sessionId);
        return Maybe.just(this.copySession(session));
      } else {
        logger.debug("Session {} not found.", sessionId);
        return Maybe.empty();
      }
    } catch (Exception ex) {
      logger.error("Error getting session {}: {}", sessionId, ex.getMessage(), ex);
      return Maybe.error(new RuntimeException("Failed to get session: " + sessionId, ex));
    }
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    // TODO: Implement listSessions functionality
    // This would require adding a method to PostgresDBHelper to query sessions by
    // appName and userId
    logger.warn(
        "listSessions method is not yet implemented for PostgresSessionService. Returning empty response.");
    return Single.just(ListSessionsResponse.builder().build()); // Return empty response for now
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    logger.info("Attempting to delete session: {}", sessionId);

    try {
      /*
       * Delete the session from the database.
       * This will also handle deleting associated events.
       */
      PostgresDBHelper.getInstance().deleteSession(sessionId);
      logger.info("Session {} deleted successfully (or not found).", sessionId);
      return Completable.complete();
    } catch (Exception ex) {
      logger.error("Error deleting session {}: {}", sessionId, ex.getMessage(), ex);
      return Completable.error(new RuntimeException("Failed to delete session: " + sessionId, ex));
    }
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    logger.info("Attempting to list events for session: {}", sessionId);

    try {
      JSONObject storedSessionJson = PostgresDBHelper.getInstance().getSession(sessionId);

      if (storedSessionJson != null) {
        Session session = objectMapper.readValue(storedSessionJson.toString(), Session.class);
        // Return an immutable copy of events
        ImmutableList<Event> eventsCopy = ImmutableList.copyOf(session.events());
        logger.info("Found {} events for session {}.", eventsCopy.size(), sessionId);
        return Single.just(ListEventsResponse.builder().events(eventsCopy).build());
      } else {
        logger.debug("Session {} not found, cannot list events.", sessionId);
        return Single.just(
            ListEventsResponse.builder().build()); // Return empty list if session not found
      }
    } catch (Exception ex) {
      logger.error("Error listing events for session {}: {}", sessionId, ex.getMessage(), ex);
      return Single.error(
          new RuntimeException("Failed to list events for session: " + sessionId, ex));
    }
  }

  @Override
  public Completable closeSession(Session session) {
    // Your original code delegates to super, which likely does nothing or logs.
    // If 'closing' a session implies updating its status in DB or similar,
    // that logic would go here. For now, just logging and completing.
    logger.info("Closing session: {}", session.id());
    return BaseSessionService.super.closeSession(session);
  }

  @CanIgnoreReturnValue
  @Override // Ensure this overrides the appendEvent from BaseSessionService if it's there
  public Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");

    String sessionId = session.id();
    logger.debug("Attempting to append event to session: {}", sessionId);

    try {
      JSONObject sessionJson = getSessionFromRedisOrPostgres(sessionId);
      // Get the latest session state from DB to append event correctly
      // JSONObject sessionJson = PostgresDBHelper.getInstance().getSession(sessionId);

      if (sessionJson == null) {
        logger.warn(
            "appendEvent called for session {} which is not found in PostgresDbSessionService",
            sessionId);
        return Single.error(new IllegalArgumentException("Session not found: " + sessionId));
      } else {
        Session storedSession = objectMapper.readValue(sessionJson.toString(), Session.class);
        if (storedSession.events() != null) {
          // Create a new list with the appended event to avoid mutating the original
          List<Event> updatedEvents = new ArrayList<>(storedSession.events());
          trimTempDeltaState(event);
          updatedEvents.add(event);

          // Apply state delta from the event to the stored session's state
          ConcurrentMap<String, Object> updatedState = new ConcurrentHashMap<>();
          if (storedSession.state() != null) {
            updatedState.putAll(storedSession.state());
          }

          // Apply state delta from event actions
          EventActions actions = event.actions();
          if (actions != null) {
            Map<String, Object> stateDelta = actions.stateDelta();
            if (stateDelta != null && !stateDelta.isEmpty()) {
              stateDelta.forEach(
                  (key, value) -> {
                    if (value == State.REMOVED) {
                      updatedState.remove(key);
                    } else {
                      updatedState.put(key, value);
                    }
                  });
            }
          }

          // Create a new session with updated events, updated state, and timestamp
          Instant now = Instant.now();
          Session updatedSession =
              Session.builder(storedSession.id())
                  .appName(storedSession.appName())
                  .userId(storedSession.userId())
                  .state(updatedState)
                  .events(updatedEvents)
                  .lastUpdateTime(now)
                  .build();

          /*
           * Save the updated session back to the database.
           * This will handle both insert and update (upsert) logic.
           */

          PostgresDBHelper.getInstance().saveSession(sessionId, updatedSession);

          logger.debug("Event appended successfully to session {} with state updates.", sessionId);
          // Call super implementation to update the in-memory session object as well
          BaseSessionService.super.appendEvent(session, event);
          return Single.just(event);
        } else {
          logger.error("Stored session {} events list is null!", sessionId);
          return Single.error(new IllegalStateException("Stored session events list is null"));
        }
      }
    } catch (Exception ex) {
      logger.error("Error appending event to session {}: {}", sessionId, ex.getMessage(), ex);
      return Single.error(
          new RuntimeException("Failed to append event to session: " + sessionId, ex));
    }
  }

  /**
   * Extracts an Instant from an Event's timestamp. Assumes Event.timestamp() returns epoch seconds
   * (long or double).
   *
   * @param event The event.
   * @return The Instant representing the event's timestamp.
   */
  private Instant getInstantFromEvent(Event event) {
    // Replicating original logic; if Event.timestamp() is a double of epoch
    // seconds.
    // Adjust if Event.timestamp() is directly a long of epoch milliseconds or
    // seconds.
    double epochSeconds = (double) event.timestamp();
    long seconds = (long) epochSeconds;
    long nanos = (long) ((epochSeconds - (double) seconds) * 1.0E9);
    return Instant.ofEpochSecond(seconds, nanos);
  }

  @Override
  public void close() throws Exception {
    // If PostgresDBHelper had a connection pool to close, you'd close it here.
    // For DriverManager, there's no explicit global close.
    // Individual connections are closed by try-with-resources.
    logger.info("PostgresSessionService closing.");
  }

  /**
   * Removes temporary state delta keys from the event. Filters out all keys that start with
   * State.TEMP_PREFIX from the event's actions state delta.
   *
   * @param event The event to trim.
   * @return The event with temporary state delta keys removed.
   */
  private void trimTempDeltaState(Event event) {
    if (event == null || event.actions() == null || event.actions().stateDelta() == null) {
      return;
    }
    Map<String, Object> stateDelta = event.actions().stateDelta();
    stateDelta.entrySet().removeIf(entry -> entry.getKey().startsWith(State.TEMP_PREFIX));
  }

  public JSONObject getSessionFromRedisOrPostgres(String sessionId) throws Exception {
    JSONObject storedSessionJson = null;
    String redisSessionStr = null;
    String useRedis = PropertiesHelper.getInstance().getValue("use_redis");
    if (Boolean.parseBoolean(useRedis)) {
      redisSessionStr = redisConnection.get(sessionId);
    }
    if (redisSessionStr != null && !redisSessionStr.isEmpty()) {
      JSONObject redisSessionJson = new JSONObject(redisSessionStr);
      String id = redisSessionJson.getString("id");
      String appName = redisSessionJson.getString("appName");
      String userId = redisSessionJson.getString("userId");
      String stateDataJson = redisSessionJson.getJSONObject("state").toString();
      Instant lastUpdateTime = Instant.ofEpochSecond(redisSessionJson.getLong("lastUpdateTime"));
      JSONObject eventJson = new JSONObject(redisSessionJson.getString("event_data"));
      JSONArray eventArr = new JSONArray(eventJson.getString("events"));

      storedSessionJson = new JSONObject();
      storedSessionJson.put("id", id);
      storedSessionJson.put("appName", appName);
      storedSessionJson.put("userId", userId);
      storedSessionJson.put("state", new JSONObject(stateDataJson));
      storedSessionJson.put("events", eventArr);
      storedSessionJson.put(
          "lastUpdateTime",
          (double) lastUpdateTime.getEpochSecond() + lastUpdateTime.getNano() / 1_000_000_000.0);
    } else {
      storedSessionJson = PostgresDBHelper.getInstance().getSession(sessionId);
      /*
       * If Redis is enabled and the session is not found in Redis, save it to redis cache.
       */
      if (Boolean.parseBoolean(useRedis) && (storedSessionJson != null)) {
        Session session = objectMapper.readValue(storedSessionJson.toString(), Session.class);
        JSONObject eventJson = new JSONObject();
        eventJson.put("events", session.events().toString());
        PostgresDBHelper.getInstance().saveToRedisCache(session, eventJson);
        logger.debug("Session {} saved to Redis cache.", sessionId);
      }
    }
    return storedSessionJson;
  }

  private Session deserializeSession(String sessionJsonString) throws Exception {
    return objectMapper.readValue(sessionJsonString, new TypeReference<Session>() {});
  }
}
