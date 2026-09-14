package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CreateSessionInTransactionTest {

  private static final String TEST_DB_URL = "jdbc:h2:mem:create_session_test;DB_CLOSE_DELAY=-1";
  private static final String TEST_APP_NAME = "test-app";
  private static final String TEST_USER_ID = "test-user";

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
  public void testStatePrefixSplitting_appPrefix() {
    String sessionId = "prefix-split-app";
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put(State.APP_PREFIX + "app_key", "app_value");
    state.put("session_key", "session_value");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertEquals("app_value", session.state().get(State.APP_PREFIX + "app_key"));
    assertEquals("session_value", session.state().get("session_key"));

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertEquals("app_value", retrieved.state().get(State.APP_PREFIX + "app_key"));
    assertEquals("session_value", retrieved.state().get("session_key"));
  }

  @Test
  public void testStatePrefixSplitting_userPrefix() {
    String sessionId = "prefix-split-user";
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put(State.USER_PREFIX + "user_key", "user_value");
    state.put("session_key", "session_value");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertEquals("user_value", session.state().get(State.USER_PREFIX + "user_key"));
    assertEquals("session_value", session.state().get("session_key"));

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertEquals("user_value", retrieved.state().get(State.USER_PREFIX + "user_key"));
    assertEquals("session_value", retrieved.state().get("session_key"));
  }

  @Test
  public void testStatePrefixSplitting_allThreeTiers() {
    String sessionId = "prefix-split-all";
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put(State.APP_PREFIX + "app_setting", "global");
    state.put(State.USER_PREFIX + "user_pref", "personal");
    state.put("session_data", "private");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertEquals("global", session.state().get(State.APP_PREFIX + "app_setting"));
    assertEquals("personal", session.state().get(State.USER_PREFIX + "user_pref"));
    assertEquals("private", session.state().get("session_data"));
  }

  @Test
  public void testTempPrefixIgnored() {
    String sessionId = "temp-ignored";
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put(State.TEMP_PREFIX + "temp_key", "should_be_ignored");
    state.put("persisted_key", "should_persist");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertEquals("should_persist", session.state().get("persisted_key"));

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNull(retrieved.state().get(State.TEMP_PREFIX + "temp_key"));
    assertEquals("should_persist", retrieved.state().get("persisted_key"));
  }

  @Test
  public void testUuidGeneratedWhenSessionIdNull() {
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key", "value");

    Session session1 =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, null).blockingGet();
    Session session2 =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, null).blockingGet();

    assertNotNull(session1.id());
    assertNotNull(session2.id());
    assertNotEquals(session1.id(), session2.id());
    assertTrue(session1.id().length() > 0);
    assertTrue(session2.id().length() > 0);
  }

  @Test
  public void testUuidGeneratedWhenSessionIdEmpty() {
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("key", "value");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, "").blockingGet();

    assertNotNull(session.id());
    assertNotEquals("", session.id());
    assertTrue(session.id().length() > 0);
  }

  @Test
  public void testNullStateHandled() {
    String sessionId = "null-state";

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, null, sessionId).blockingGet();

    assertNotNull(session);
    assertNotNull(session.state());
  }

  @Test
  public void testEmptyStateHandled() {
    String sessionId = "empty-state";
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);
    assertNotNull(session.state());
  }

  @Test
  public void testAppStateSharedAcrossSessions() {
    String sessionId1 = "shared-app-1";
    String sessionId2 = "shared-app-2";

    ConcurrentMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.APP_PREFIX + "shared_counter", 10);

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state1, sessionId1).blockingGet();

    ConcurrentMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.APP_PREFIX + "shared_counter", 20);

    sessionService.createSession(TEST_APP_NAME, "other-user", state2, sessionId2).blockingGet();

    Session retrieved1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId1, Optional.empty())
            .blockingGet();

    assertEquals(20, retrieved1.state().get(State.APP_PREFIX + "shared_counter"));
  }

  @Test
  public void testUserStateIsolatedBetweenUsers() {
    String sessionId1 = "user-isolated-1";
    String sessionId2 = "user-isolated-2";

    ConcurrentMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.USER_PREFIX + "preference", "dark");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state1, sessionId1).blockingGet();

    ConcurrentMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.USER_PREFIX + "preference", "light");

    sessionService.createSession(TEST_APP_NAME, "other-user", state2, sessionId2).blockingGet();

    Session retrieved1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId1, Optional.empty())
            .blockingGet();
    Session retrieved2 =
        sessionService
            .getSession(TEST_APP_NAME, "other-user", sessionId2, Optional.empty())
            .blockingGet();

    assertEquals("dark", retrieved1.state().get(State.USER_PREFIX + "preference"));
    assertEquals("light", retrieved2.state().get(State.USER_PREFIX + "preference"));
  }

  @Test
  public void testComplexNestedState() {
    String sessionId = "nested-state";
    ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();

    ConcurrentHashMap<String, Object> nestedObject = new ConcurrentHashMap<>();
    nestedObject.put("nested_key", "nested_value");
    nestedObject.put("nested_number", 42);

    state.put(State.APP_PREFIX + "config", nestedObject);
    state.put("simple", "value");

    Session session =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID, state, sessionId).blockingGet();

    assertNotNull(session);

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    Object configObj = retrieved.state().get(State.APP_PREFIX + "config");
    assertNotNull(configObj);
  }
}
