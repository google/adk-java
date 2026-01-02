package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the session's update_time is correctly updated in all scenarios when appending
 * events.
 *
 * <p>These tests address the bug where session update_time was not being updated when events
 * contained only app: or user: prefixed state deltas.
 */
class SessionUpdateTimeTest {

  private static final String APP_NAME = "testApp";
  private static final String USER_ID = "testUser";

  private DatabaseSessionService sessionService;

  @BeforeEach
  void setUp() {
    String jdbcUrl =
        "jdbc:h2:mem:session_update_time_test_"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    sessionService = new DatabaseSessionService(jdbcUrl);
  }

  @AfterEach
  void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  void testSessionUpdateTime_AppendEventWithNoStateDelta() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    Event eventWithNoState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-1")
            .timestamp(System.currentTimeMillis())
            .build();

    sessionService.appendEvent(session, eventWithNoState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated even with no state delta");
  }

  @Test
  void testSessionUpdateTime_AppendEventWithOnlyAppStateDelta() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    ConcurrentMap<String, Object> appOnlyDelta = new ConcurrentHashMap<>();
    appOnlyDelta.put("app:setting1", "value1");
    appOnlyDelta.put("app:setting2", 42);

    Event eventWithAppState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-2")
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(appOnlyDelta).build())
            .build();

    sessionService.appendEvent(session, eventWithAppState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated when event has only app: prefixed state delta");
  }

  @Test
  void testSessionUpdateTime_AppendEventWithOnlyUserStateDelta() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    ConcurrentMap<String, Object> userOnlyDelta = new ConcurrentHashMap<>();
    userOnlyDelta.put("user:preference1", "dark");
    userOnlyDelta.put("user:preference2", true);

    Event eventWithUserState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-3")
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(userOnlyDelta).build())
            .build();

    sessionService.appendEvent(session, eventWithUserState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated when event has only user: prefixed state delta");
  }

  @Test
  void testSessionUpdateTime_AppendEventWithSessionStateDelta() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    ConcurrentMap<String, Object> sessionDelta = new ConcurrentHashMap<>();
    sessionDelta.put("counter", 1);
    sessionDelta.put("status", "active");

    Event eventWithSessionState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-4")
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(sessionDelta).build())
            .build();

    sessionService.appendEvent(session, eventWithSessionState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated when event has session state delta");
  }

  @Test
  void testSessionUpdateTime_AppendEventWithMixedStateDelta() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    ConcurrentMap<String, Object> mixedDelta = new ConcurrentHashMap<>();
    mixedDelta.put("app:version", "2.0");
    mixedDelta.put("user:theme", "light");
    mixedDelta.put("currentStep", 5);

    Event eventWithMixedState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-5")
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(mixedDelta).build())
            .build();

    sessionService.appendEvent(session, eventWithMixedState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated when event has mixed state delta");
  }

  @Test
  void testSessionUpdateTime_AppendEventWithOnlyTempStateDelta() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    ConcurrentMap<String, Object> tempOnlyDelta = new ConcurrentHashMap<>();
    tempOnlyDelta.put("temp:cache", "somevalue");
    tempOnlyDelta.put("temp:ui_state", Map.of("x", 10));

    Event eventWithTempState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-6")
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(tempOnlyDelta).build())
            .build();

    sessionService.appendEvent(session, eventWithTempState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated even when event has only temp: prefixed state delta");
  }

  @Test
  void testSessionUpdateTime_MultipleEventsInSequence() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant time1 = session.lastUpdateTime();
    Thread.sleep(100);

    Event event1 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-seq-1")
            .timestamp(System.currentTimeMillis())
            .actions(
                EventActions.builder()
                    .stateDelta(new ConcurrentHashMap<>(Map.of("app:config", "v1")))
                    .build())
            .build();

    sessionService.appendEvent(session, event1).blockingGet();
    Session session2 =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();
    Instant time2 = session2.lastUpdateTime();
    assertTrue(time2.isAfter(time1), "Update time should increase after first event");

    Thread.sleep(100);

    Event event2 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-seq-2")
            .timestamp(System.currentTimeMillis())
            .actions(
                EventActions.builder()
                    .stateDelta(new ConcurrentHashMap<>(Map.of("user:pref", "v2")))
                    .build())
            .build();

    sessionService.appendEvent(session2, event2).blockingGet();
    Session session3 =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();
    Instant time3 = session3.lastUpdateTime();
    assertTrue(time3.isAfter(time2), "Update time should increase after second event");

    Thread.sleep(100);

    Event event3 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-seq-3")
            .timestamp(System.currentTimeMillis())
            .build();

    sessionService.appendEvent(session3, event3).blockingGet();
    Session session4 =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();
    Instant time4 = session4.lastUpdateTime();
    assertTrue(time4.isAfter(time3), "Update time should increase after third event with no state");
  }

  @Test
  void testSessionUpdateTime_AppendEventWithAppAndUserStateDeltaOnly() throws InterruptedException {
    Session session =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    Instant initialUpdateTime = session.lastUpdateTime();
    assertNotNull(initialUpdateTime, "Initial update time should not be null");

    Thread.sleep(100);

    ConcurrentMap<String, Object> appAndUserDelta = new ConcurrentHashMap<>();
    appAndUserDelta.put("app:globalSetting", "enabled");
    appAndUserDelta.put("user:personalSetting", "custom");

    Event eventWithAppAndUserState =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId("inv-7")
            .timestamp(System.currentTimeMillis())
            .actions(EventActions.builder().stateDelta(appAndUserDelta).build())
            .build();

    sessionService.appendEvent(session, eventWithAppAndUserState).blockingGet();

    Session updatedSession =
        sessionService
            .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty())
            .blockingGet();

    assertTrue(
        updatedSession.lastUpdateTime().isAfter(initialUpdateTime),
        "Session update_time should be updated when event has app and user state delta but no session state delta");
  }
}
