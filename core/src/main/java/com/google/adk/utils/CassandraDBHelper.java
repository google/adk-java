package com.google.adk.utils;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraDBHelper {
  private static final Logger logger = LoggerFactory.getLogger(CassandraDBHelper.class);
  private static volatile CassandraDBHelper instance;
  private final CqlSession session;
  private final ObjectMapper objectMapper;

  private static final String CASSANDRA_HOST = "cassandra_host";
  private static final String CASSANDRA_PORT = "cassandra_port";
  private static final String CASSANDRA_USER = "cassandra_user";
  private static final String CASSANDRA_PASSWORD = "cassandra_password";
  private static final String CASSANDRA_KEYSPACE = "cassandra_keyspace";

  private CassandraDBHelper() {
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new Jdk8Module());
    this.objectMapper.registerModule(new GuavaModule());
    this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.objectMapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
    this.objectMapper.configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, false);

    this.session = initializeCqlSession();
  }

  public static CassandraDBHelper getInstance() {
    if (instance == null) {
      synchronized (CassandraDBHelper.class) {
        if (instance == null) {
          instance = new CassandraDBHelper();
        }
      }
    }
    return instance;
  }

  private CqlSession initializeCqlSession() {
    String host = System.getProperty(CASSANDRA_HOST);
    int port = Integer.parseInt(System.getProperty(CASSANDRA_PORT));
    String username = System.getProperty(CASSANDRA_USER);
    String password = System.getProperty(CASSANDRA_PASSWORD);
    String keyspace = System.getProperty(CASSANDRA_KEYSPACE);

    if (host == null || username == null || password == null || keyspace == null) {
      throw new IllegalArgumentException("Missing Cassandra environment variables");
    }

    return CqlSession.builder()
        .addContactPoint(new InetSocketAddress(host, port))
        .withAuthCredentials(username, password)
        .withKeyspace(keyspace)
        .withLocalDatacenter("datacenter1")
        .build();
  }

  public CqlSession getSession() {
    return session;
  }

  // --- Session Service Methods ---

  public void saveSession(Session sessionObj) throws JsonProcessingException {
    String insertCql =
        "INSERT INTO sessions (app_name, user_id, session_id, state, events, last_update_time) VALUES (?, ?, ?, ?, ?, ?)";
    session.execute(
        insertCql,
        sessionObj.appName(),
        sessionObj.userId(),
        sessionObj.id(),
        objectMapper.writeValueAsString(sessionObj.state()),
        objectMapper.writeValueAsString(sessionObj.events()),
        sessionObj.lastUpdateTime());
  }

  public Optional<Session> getSession(String appName, String userId, String sessionId)
      throws JsonProcessingException {
    String selectCql =
        "SELECT app_name, user_id, session_id, state, events, last_update_time FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
    Row row = session.execute(selectCql, appName, userId, sessionId).one();

    if (row == null) {
      return Optional.empty();
    }

    String storedAppName = row.getString("app_name");
    String storedUserId = row.getString("user_id");
    String storedSessionId = row.getString("session_id");
    String stateJson = row.getString("state");
    String eventsJson = row.getString("events");
    Instant lastUpdateTime = row.getInstant("last_update_time");

    ConcurrentMap<String, Object> state =
        objectMapper.readValue(
            stateJson,
            objectMapper
                .getTypeFactory()
                .constructMapType(ConcurrentMap.class, String.class, Object.class));
    List<Event> events =
        objectMapper.readValue(
            eventsJson,
            objectMapper.getTypeFactory().constructCollectionType(List.class, Event.class));

    Session retrievedSession =
        Session.builder(storedSessionId)
            .appName(storedAppName)
            .userId(storedUserId)
            .state(state)
            .events(events)
            .lastUpdateTime(lastUpdateTime)
            .build();

    return Optional.of(retrievedSession);
  }

  public List<Session> listSessions(String appName, String userId) {
    String selectCql =
        "SELECT app_name, user_id, session_id, last_update_time FROM sessions WHERE app_name = ? AND user_id = ?";
    ResultSet resultSet = session.execute(selectCql, appName, userId);
    List<Session> sessionCopies = new ArrayList<>();
    for (Row row : resultSet) {
      String storedAppName = row.getString("app_name");
      String storedUserId = row.getString("user_id");
      String storedSessionId = row.getString("session_id");
      Instant lastUpdateTime = row.getInstant("last_update_time");

      sessionCopies.add(
          Session.builder(storedSessionId)
              .appName(storedAppName)
              .userId(storedUserId)
              .lastUpdateTime(lastUpdateTime)
              .build());
    }
    return sessionCopies;
  }

  public void deleteSession(String appName, String userId, String sessionId) {
    String deleteCql = "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
    session.execute(deleteCql, appName, userId, sessionId);
  }

  public List<Event> listEvents(String appName, String userId, String sessionId)
      throws JsonProcessingException {
    String selectCql =
        "SELECT events FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
    Row row = session.execute(selectCql, appName, userId, sessionId).one();
    if (row == null) {
      return ImmutableList.of();
    }
    String eventsJson = row.getString("events");
    return objectMapper.readValue(
        eventsJson, objectMapper.getTypeFactory().constructCollectionType(List.class, Event.class));
  }

  public void updateSessionEvents(
      String appName, String userId, String sessionId, List<Event> events, Instant lastUpdateTime)
      throws JsonProcessingException {
    String updateCql =
        "UPDATE sessions SET events = ?, last_update_time = ? WHERE app_name = ? AND user_id = ? AND session_id = ?";
    session.execute(
        updateCql,
        objectMapper.writeValueAsString(events),
        lastUpdateTime,
        appName,
        userId,
        sessionId);
  }

  // --- Artifact Service Methods ---

  private static final String ARTIFACT_TABLE = "artifacts";
  private static final String FILENAMES_TABLE = "session_filenames_by_user";

  public void deleteArtifact(String appName, String userId, String sessionId, String filename) {
    String deleteCql =
        "DELETE FROM "
            + ARTIFACT_TABLE
            + " WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?";
    session.execute(deleteCql, appName, userId, sessionId, filename);
  }

  public List<String> listArtifactKeys(String appName, String userId, String sessionId) {
    String selectCql =
        "SELECT filename FROM "
            + FILENAMES_TABLE
            + " WHERE app_name = ? AND user_id = ? AND session_id = ?";
    ResultSet resultSet = session.execute(selectCql, appName, userId, sessionId);
    List<String> filenames = new ArrayList<>();
    for (Row row : resultSet) {
      filenames.add(row.getString("filename"));
    }
    return filenames;
  }

  public List<Integer> listArtifactVersions(
      String appName, String userId, String sessionId, String filename) {
    String selectCql =
        "SELECT version_number FROM "
            + ARTIFACT_TABLE
            + " WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?";
    ResultSet resultSet = session.execute(selectCql, appName, userId, sessionId, filename);
    List<Integer> versions = new ArrayList<>();
    for (Row row : resultSet) {
      versions.add(row.getInt("version_number"));
    }
    return versions;
  }

  public Optional<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version)
      throws JsonProcessingException {
    String selectCql;
    Row row;
    if (version.isPresent()) {
      selectCql =
          "SELECT artifact_data FROM "
              + ARTIFACT_TABLE
              + " WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version_number = ?";
      row = session.execute(selectCql, appName, userId, sessionId, filename, version.get()).one();
    } else {
      selectCql =
          "SELECT artifact_data FROM "
              + ARTIFACT_TABLE
              + " WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? ORDER BY version_number DESC LIMIT 1";
      row = session.execute(selectCql, appName, userId, sessionId, filename).one();
    }

    if (row == null) {
      return Optional.empty();
    }
    String artifactJson = row.getString("artifact_data");

    // Convert JSON string to Map<String, Object>
    Map<String, Object> artifactMap =
        objectMapper.readValue(artifactJson, new TypeReference<Map<String, Object>>() {});

    // Manually construct Part from the map
    Part.Builder builder = Part.builder();

    if (artifactMap.containsKey("text") && artifactMap.get("text") != null) {
      builder.text((String) artifactMap.get("text"));
    }
    if (artifactMap.containsKey("inlineData") && artifactMap.get("inlineData") != null) {
      // Need to convert map to Blob object
      builder.inlineData(objectMapper.convertValue(artifactMap.get("inlineData"), Blob.class));
    }
    if (artifactMap.containsKey("fileData") && artifactMap.get("fileData") != null) {
      // Need to convert map to FileData object
      builder.fileData(objectMapper.convertValue(artifactMap.get("fileData"), FileData.class));
    }
    if (artifactMap.containsKey("functionCall") && artifactMap.get("functionCall") != null) {
      // Need to convert map to FunctionCall object
      builder.functionCall(
          objectMapper.convertValue(artifactMap.get("functionCall"), FunctionCall.class));
    }
    if (artifactMap.containsKey("functionResponse")
        && artifactMap.get("functionResponse") != null) {
      // Need to convert map to FunctionResponse object
      builder.functionResponse(
          objectMapper.convertValue(artifactMap.get("functionResponse"), FunctionResponse.class));
    }
    // The 'thought' field is a boolean Optional<Boolean>
    if (artifactMap.containsKey("thought") && artifactMap.get("thought") != null) {
      builder.thought((Boolean) artifactMap.get("thought"));
    }

    return Optional.of(builder.build());
  }

  public int saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact)
      throws JsonProcessingException {
    String selectMaxVersionCql =
        "SELECT MAX(version_number) FROM "
            + ARTIFACT_TABLE
            + " WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?";
    int nextVersion = 0;
    Row row = session.execute(selectMaxVersionCql, appName, userId, sessionId, filename).one();
    if (row != null && !row.isNull(0)) {
      nextVersion = row.getInt(0) + 1;
    }

    // Convert Part to Map<String, Object> to avoid direct Part serialization issues
    Map<String, Object> artifactMap =
        objectMapper.convertValue(artifact, new TypeReference<Map<String, Object>>() {});

    String insertCql =
        "INSERT INTO "
            + ARTIFACT_TABLE
            + " (app_name, user_id, session_id, filename, version_number, artifact_data) VALUES (?, ?, ?, ?, ?, ?)";
    String insertFilenamesCql =
        "INSERT INTO "
            + FILENAMES_TABLE
            + " (app_name, user_id, session_id, filename) VALUES (?, ?, ?, ?)";

    session.execute(
        insertCql,
        appName,
        userId,
        sessionId,
        filename,
        nextVersion,
        objectMapper.writeValueAsString(artifactMap)); // Save the map as JSON string
    session.execute(insertFilenamesCql, appName, userId, sessionId, filename);
    return nextVersion;
  }

  public void close() {
    if (session != null) {
      session.close();
    }
  }
}
