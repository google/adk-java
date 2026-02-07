package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.testing.TestDatabaseConfig;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
public class SpannerFunctionalTest {

  private static final String TEST_DB_URL = TestDatabaseConfig.SPANNER_JDBC_URL;
  private String TEST_APP_NAME;
  private String TEST_USER_ID;

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    assumeTrue(
        TestDatabaseConfig.isSpannerAvailable(),
        TestDatabaseConfig.getDatabaseNotAvailableMessage("Spanner"));

    TEST_APP_NAME = "jdbc-spanner-test-app-" + System.currentTimeMillis();
    TEST_USER_ID = "jdbc-spanner-test-user-" + System.currentTimeMillis();

    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testBasicSessionOperations() {
    String sessionId = "spanner-basic-test-" + System.currentTimeMillis();
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key", "value");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertEquals(sessionId, session.id());
    assertEquals("value", session.state().get("key"));
  }

  @Test
  public void testEventActionsWithStateDelta() {
    String sessionId = "spanner-actions-test-" + System.currentTimeMillis();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("count", 1);
    stateDelta.put("_app_shared", "global");

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, event).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals(1, retrieved.state().get("count"));
    assertEquals("global", retrieved.state().get("_app_shared"));
  }

  @Test
  public void testJSONStorageAndRetrieval() {
    String sessionId = "spanner-json-test-" + System.currentTimeMillis();
    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("nested", java.util.Map.of("inner", "value"));

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertNotNull(retrieved.state().get("nested"));
  }

  @Test
  public void testUpsertAppState() {
    String sessionId1 = "spanner-upsert-1-" + System.currentTimeMillis();
    String sessionId2 = "spanner-upsert-2-" + System.currentTimeMillis();

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("app:config", "value1");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("app:config", "value2");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state2, sessionId2).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId1, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("value2", retrieved.state().get("app:config"));
  }

  @Test
  public void testGetSessionWithInvalidConfig() {
    String sessionId = "spanner-invalid-config-test-" + System.currentTimeMillis();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    // Add 5 events
    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    // Test negative numRecentEvents: -1 should be treated as abs(-1) = 1 (last 1 event)
    GetSessionConfig negativeNumEvents = GetSessionConfig.builder().numRecentEvents(-1).build();
    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(negativeNumEvents))
            .blockingGet();

    assertNotNull(session);
    // Should return exactly 1 event (the most recent one)
    assertEquals(
        1,
        session.events().size(),
        "Expected 1 event for numRecentEvents=-1, got " + session.events().size());
    // Should be the last event added (event-5)
    assertEquals(
        "event-5",
        session.events().get(0).id(),
        "Expected most recent event (event-5), got " + session.events().get(0).id());
  }
}
