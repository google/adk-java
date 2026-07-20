package com.google.adk.sessions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bson.Document;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Harshavardhan A
 *     <p>A MongoDB implementation of {@link BaseSessionService} for persistent storage. Stores
 *     sessions, user state, and app state in a MongoDB collection.
 */
public class MongoDbSessionService implements BaseSessionService, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(MongoDbSessionService.class);

  private MongoClient mongoClient;
  private MongoDatabase database;

  private static final String HOST = "mongo_host"; // MongoDB host
  private static final String PORT = "mongo_port"; // MongoDB port
  private static final String DB = "mongo_db"; // DB name
  private static final String USERNAME = "mongo_username"; // DB username
  private static final String PASSWORD = "mongo_password";
  private static final String COLLECTION = "mongo_collection";
  private static final String AUTH_DB = "mongo_auth_collection";

  /** */

  /** Create connection to the Mongo DB , By fetching the details from Environment */
  public MongoDbSessionService() {
    String userName = System.getenv(USERNAME);
    String password = System.getenv(PASSWORD);
    String authDB = System.getenv(AUTH_DB);
    String host = System.getenv(HOST);
    Integer port = Integer.parseInt(System.getenv(PORT));

    MongoCredential credential =
        MongoCredential.createCredential(userName, authDB, password.toCharArray());
    MongoClientSettings settings =
        MongoClientSettings.builder()
            .applyToClusterSettings(
                builder -> builder.hosts(Collections.singletonList(new ServerAddress(host, port))))
            .credential(credential)
            .build();
    mongoClient = MongoClients.create(settings);
  }

  /**
   * creates and the date into the mongo DB, by keeping the SessionId as the unique reference. if
   * there is already entry for the given session Id then it update the events , if there is no
   * Session avialable for the given Session Id then it will create new entry
   *
   * @param sessionId
   * @param session
   */
  private void saveSessionToDB(String sessionId, String session) {
    String db = System.getenv(DB);
    String collection = System.getenv(COLLECTION);
    Document document =
        this.mongoClient
            .getDatabase(db)
            .getCollection(collection)
            .find(Filters.eq("id", sessionId))
            .first();
    if (document == null) {
      Document dbObject = Document.parse(session);
      this.mongoClient.getDatabase(db).getCollection(collection).insertOne(dbObject);
      System.out.println("saved");
    } else {
      Document dbObject = Document.parse(session);
      Document filter = new Document("_id", document.get("_id"));
      Document update = new Document("$set", dbObject);
      this.mongoClient.getDatabase(db).getCollection(collection).updateOne(filter, update);
    }
  }

  /**
   * fetch the Session from the mongo DB for the given Session ID. checks and update event's
   * timestamp data event of the events list if its in wrong format removes the "_id" since while
   * parsing into Object of {@link Session} Class throws error.
   *
   * @param id
   * @return
   */
  private JSONObject getSessionFromDB(String id) {
    String db = System.getenv(DB);
    String collection = System.getenv(COLLECTION);
    Document document =
        this.mongoClient
            .getDatabase(db)
            .getCollection(collection)
            .find(Filters.eq("id", id))
            .first();
    if (document != null) {
      JSONObject session = new JSONObject(document.toJson());
      session.remove("_id");
      for (int i = 0; session.getJSONArray("events").length() > i; i++) {
        String timestamp =
            session
                .getJSONArray("events")
                .getJSONObject(i)
                .getJSONObject("timestamp")
                .getString("$numberLong");
        session.getJSONArray("events").getJSONObject(i).remove("timestamp");
        session.getJSONArray("events").getJSONObject(i).put("timestamp", timestamp);
      }
      return session;
    } else return null;
  }

  /**
   * delete the entry of the session from the Mongo DB for the given Session ID
   *
   * @param sessionId
   */
  private void deleteSession(String sessionId) {
    String db = System.getenv(DB);
    String collection = System.getenv(COLLECTION);
    this.mongoClient
        .getDatabase(db)
        .getCollection(collection)
        .deleteOne(Filters.eq("id", sessionId));
  }

  /**
   * creates the copy of the {@link Session} Object.
   *
   * @param original
   * @return
   */
  private Session copySession(Session original) {
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .state(new ConcurrentHashMap(original.state()))
        .events(new ArrayList(original.events()))
        .lastUpdateTime(original.lastUpdateTime())
        .build();
  }

  /**
   * Check if the Session is available on Mongo DB if the {@param sessionId} is not null, if the
   * session exist then return the persisted Session, else it will create new Session for the given
   * sessionId if the sessionId is null then it will create new Session for the random UUID
   *
   * @param appName The name of the application associated with the session.
   * @param userId The identifier for the user associated with the session.
   * @param state An optional map representing the initial state of the session. Can be null or
   *     empty.
   * @param sessionId An optional client-provided identifier for the session. If empty or null, the
   *     service should generate a unique ID.
   * @return
   */
  @Override
  public Single<Session> createSession(
      String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    String resolvedSessionId =
        (String)
            Optional.ofNullable(sessionId)
                .map(String::trim)
                .filter(
                    (s) -> {
                      return !s.isEmpty();
                    })
                .orElseGet(
                    () -> {
                      return UUID.randomUUID().toString();
                    });
    ConcurrentMap<String, Object> initialState =
        state == null ? new ConcurrentHashMap() : new ConcurrentHashMap(state);
    List<Event> initialEvents = new ArrayList();
    Session newSession =
        Session.builder(resolvedSessionId)
            .appName(appName)
            .userId(userId)
            .state(initialState)
            .events(initialEvents)
            .lastUpdateTime(Instant.now())
            .build();
    logger.info(newSession.toJson());
    this.saveSessionToDB(resolvedSessionId, newSession.toJson());
    Session returnCopy = this.copySession(newSession);
    return Single.just(returnCopy);
  }

  /**
   * Fetch the Session from Mongo DB for the given sessionId.
   *
   * @param appName The name of the application.
   * @param userId The identifier of the user.
   * @param sessionId The unique identifier of the session to retrieve.
   * @param config Optional configuration to filter the events returned within the session (e.g.,
   *     limit number of recent events, filter by timestamp). If empty, default retrieval behavior
   *     is used (potentially all events or a service-defined limit).
   * @return
   */
  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    ObjectMapper objectMapper = new ObjectMapper();
    Session storedSession = null;
    try {
      JSONObject session = this.getSessionFromDB(sessionId);
      if (session != null) {
        session.remove("_id");
        return Maybe.just((Session) objectMapper.readValue(session.toString(), Session.class));
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return Maybe.empty();
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return null;
  }

  /**
   * @param appName The name of the application.
   * @param userId The identifier of the user.
   * @param sessionId The unique identifier of the session to delete.
   * @return
   */
  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    ObjectMapper objectMapper = new ObjectMapper();

    try {
      JSONObject storedSession = this.getSessionFromDB(sessionId);
      if (storedSession != null) {
        this.deleteSession(sessionId);
      } else {
        logger.warn(
            "Attempted to delete session {} for user {} in app {}, but it was not found or did not match criteria.",
            new Object[] {sessionId, userId, appName});
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return Completable.complete();
  }

  /**
   * @param appName The name of the application.
   * @param userId The identifier of the user.
   * @param sessionId The unique identifier of the session whose events are to be listed.
   * @return
   */
  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      JSONObject storedSession = this.getSessionFromDB(sessionId);
      if (storedSession != null) {
        Session session = (Session) objectMapper.readValue(storedSession.toString(), Session.class);
        ImmutableList<Event> eventsCopy = ImmutableList.copyOf(session.events());
        return Single.just(ListEventsResponse.builder().events(eventsCopy).build());
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return Single.just(ListEventsResponse.builder().build());
  }

  @Override
  public Completable closeSession(Session session) {
    return BaseSessionService.super.closeSession(session);
  }

  /**
   * fetch the session from the Mongo DB and Append the event to the session's Event list and save
   * the Session into Mongo DB
   *
   * @param session The {@link Session} object to which the event should be appended (will be
   *     mutated).
   * @param event The {@link Event} to append.
   * @return
   */
  @CanIgnoreReturnValue
  public Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");
    String appName = session.appName();
    String userId = session.userId();
    String sessionId = session.id();
    ObjectMapper objectMapper = new ObjectMapper();
    Session storedSession = null;

    try {
      JSONObject sessionJson = this.getSessionFromDB(sessionId);
      if (session != null) {
        storedSession = (Session) objectMapper.readValue(sessionJson.toString(), Session.class);
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }

    if (storedSession == null) {
      logger.warn(
          String.format(
              "appendEvent called for session %s which is not found in Mongo DbSessionService",
              sessionId));
      return Single.error(new IllegalArgumentException("Session not found: " + sessionId));
    } else {
      if (storedSession.events() != null) {
        storedSession.events().add(event);
        storedSession.lastUpdateTime(this.getInstantFromEvent(event));
        this.saveSessionToDB(sessionId, storedSession.toJson());
        BaseSessionService.super.appendEvent(session, event);
        return Single.just(event);
      } else {
        logger.error("Stored session {} events list is null!", sessionId);
        return Single.error(new IllegalStateException("Stored session events list is null"));
      }
    }
  }

  private Instant getInstantFromEvent(Event event) {
    double epochSeconds = (double) event.timestamp();
    long seconds = (long) epochSeconds;
    long nanos = (long) ((epochSeconds - (double) seconds) * 1.0E9);
    return Instant.ofEpochSecond(seconds, nanos);
  }

  @Override
  public void close() throws Exception {}
}
