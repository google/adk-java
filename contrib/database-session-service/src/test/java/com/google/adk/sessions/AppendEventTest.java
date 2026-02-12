package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AppendEventTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:append_failure_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private static final String TEST_APP_NAME = "failure-test-app";
  private static final String TEST_USER_ID = "failure-test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testAppendEvent_dbWriteFailsDueToClosedService_memoryUnchanged() {
    String sessionId = "db-fail-test";
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    assertEquals(0, session.events().size());

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    sessionService.close();

    try {
      sessionService.appendEvent(session, event).blockingGet();
      fail("Expected IllegalStateException when service is closed");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("closed"));
    }

    assertEquals(0, session.events().size(), "Memory should remain unchanged when DB write fails");
  }

  @Test
  public void testAppendEvent_nonExistentSession_throwsException() {
    String sessionId = "non-existent-session";
    Session fakeSession =
        Session.builder(sessionId)
            .appName(TEST_APP_NAME)
            .userId(TEST_USER_ID)
            .state(new ConcurrentHashMap<>())
            .build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test event")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    try {
      sessionService.appendEvent(fakeSession, event).blockingGet();
      fail("Expected SessionNotFoundException for non-existent session");
    } catch (SessionNotFoundException e) {
      assertTrue(e.getMessage().contains("Session not found"));
    }
  }

  @Test
  public void testAppendEvent_validStateDelta_persistsCorrectly() throws Exception {
    String sessionId = "valid-state-test";
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "valid_key", "valid_value");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Event appendedEvent = sessionService.appendEvent(session, event).blockingGet();

    assertNotNull(appendedEvent);

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertEquals("valid_value", retrieved.state().get(State.APP_PREFIX + "valid_key"));
    assertEquals(1, retrieved.events().size());
  }

  @Test
  public void testAppendEvent_successfulAppendAndRetrieval() throws Exception {
    String sessionId = "success-test";
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Event appended = sessionService.appendEvent(session, event).blockingGet();
    assertNotNull(appended);

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertEquals(1, retrieved.events().size());
  }

  @Test
  public void testAppendEvent_concurrentModificationWithRollback() throws Exception {
    String sessionId = "concurrent-rollback-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "counter", 0);

    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
            .blockingGet();

    ConcurrentHashMap<String, Object> delta1 = new ConcurrentHashMap<>();
    delta1.put(State.APP_PREFIX + "counter", 1);

    EventActions actions1 = EventActions.builder().stateDelta(delta1).build();

    Event event1 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Event 1")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions1)
            .build();

    sessionService.appendEvent(session, event1).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertEquals(1, retrieved.state().get(State.APP_PREFIX + "counter"));
    assertEquals(1, retrieved.events().size());
  }

  @Test
  public void testAppendEvent_multipleConcurrentFailures() throws Exception {
    String sessionId = "multi-failure-test";
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    Event event1 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Event 1")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    sessionService.appendEvent(session, event1).blockingGet();

    assertEquals(1, session.events().size());

    sessionService.close();

    Event event2 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Event 2")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    try {
      sessionService.appendEvent(session, event2).blockingGet();
      fail("Expected IllegalStateException after close");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("closed"));
    }

    assertEquals(1, session.events().size(), "Event count should remain at 1 after failed append");
  }

  @Test
  public void testAppendEvent_errorLogging() {
    String sessionId = "error-logging-test";
    Session session =
        Session.builder(sessionId)
            .appName(TEST_APP_NAME)
            .userId(TEST_USER_ID)
            .state(new ConcurrentHashMap<>())
            .build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    try {
      sessionService.appendEvent(session, event).blockingGet();
      fail("Should throw SessionNotFoundException");
    } catch (SessionNotFoundException e) {
      assertTrue(
          e.getMessage().contains("Session not found"), "Error message should be descriptive");
    }
  }
}
