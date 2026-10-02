package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StateManagementTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:state_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private static final String TEST_APP_NAME = "state-test-app";
  private static final String TEST_USER_ID_1 = "user-1";
  private static final String TEST_USER_ID_2 = "user-2";

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
  public void testAppStateSharing() {
    String sessionId1 = "session-1";
    String sessionId2 = "session-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.APP_PREFIX + "global_setting", "shared_value");
    state1.put("local", "private_value_1");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.APP_PREFIX + "global_setting", "updated_value");
    state2.put("local", "private_value_2");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_2, state2, sessionId2).blockingGet();

    Session retrieved1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    Session retrieved2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_2, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved1);
    assertNotNull(retrieved2);

    assertEquals("updated_value", retrieved1.state().get(State.APP_PREFIX + "global_setting"));
    assertEquals("updated_value", retrieved2.state().get(State.APP_PREFIX + "global_setting"));

    assertEquals("private_value_1", retrieved1.state().get("local"));
    assertEquals("private_value_2", retrieved2.state().get("local"));
  }

  @Test
  public void testUserStateSharing() {
    String sessionId1 = "user-session-1";
    String sessionId2 = "user-session-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.USER_PREFIX + "preference", "dark_mode");
    state1.put("data", "session_specific_1");

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.USER_PREFIX + "preference", "light_mode");
    state2.put("data", "session_specific_2");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(session1);
    assertNotNull(session2);

    assertEquals("light_mode", session1.state().get(State.USER_PREFIX + "preference"));
    assertEquals("light_mode", session2.state().get(State.USER_PREFIX + "preference"));

    assertEquals("session_specific_1", session1.state().get("data"));
    assertEquals("session_specific_2", session2.state().get("data"));
  }

  @Test
  public void testSessionStateIsolation() {
    String sessionId1 = "isolated-1";
    String sessionId2 = "isolated-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("private_key", "value_1");

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("private_key", "value_2");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();
    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(session1);
    assertNotNull(session2);

    assertEquals("value_1", session1.state().get("private_key"));
    assertEquals("value_2", session2.state().get("private_key"));
  }

  @Test
  public void testStatePriorityMerging() {
    String sessionId = "priority-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("_app_key", "app_value");
    initialState.put("_user_key", "user_value");
    initialState.put("key", "session_value");

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, initialState, sessionId)
        .blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("app_value", retrieved.state().get("_app_key"));
    assertEquals("user_value", retrieved.state().get("_user_key"));
    assertEquals("session_value", retrieved.state().get("key"));
  }

  @Test
  public void testTempStateIsIgnored() {
    String sessionId = "temp-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("temp:ignored", "should_not_persist");
    initialState.put("persisted", "should_persist");

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, initialState, sessionId)
        .blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("should_persist", retrieved.state().get("persisted"));
    assertEquals(null, retrieved.state().get("temp:ignored"));
  }

  @Test
  public void testStateMerge_putAllDoesNotLoseData() {
    String sessionId1 = "merge-test-1";
    String sessionId2 = "merge-test-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.APP_PREFIX + "key1", "value1");
    state1.put(State.APP_PREFIX + "key2", "value2");
    state1.put(State.APP_PREFIX + "key3", "value3");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.APP_PREFIX + "key4", "value4");
    state2.put(State.APP_PREFIX + "key5", "value5");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("value1", retrieved.state().get(State.APP_PREFIX + "key1"));
    assertEquals("value2", retrieved.state().get(State.APP_PREFIX + "key2"));
    assertEquals("value3", retrieved.state().get(State.APP_PREFIX + "key3"));
    assertEquals("value4", retrieved.state().get(State.APP_PREFIX + "key4"));
    assertEquals("value5", retrieved.state().get(State.APP_PREFIX + "key5"));
  }

  @Test
  public void testStateMerge_nestedObjectsPreserved() {
    String sessionId1 = "nested-merge-1";
    String sessionId2 = "nested-merge-2";

    ConcurrentHashMap<String, Object> nestedMap1 = new ConcurrentHashMap<>();
    nestedMap1.put("nested_key_1", "nested_value_1");
    nestedMap1.put("nested_key_2", 42);

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.APP_PREFIX + "config", nestedMap1);

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> nestedMap2 = new ConcurrentHashMap<>();
    nestedMap2.put("another_nested_key", "another_value");

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.APP_PREFIX + "other_config", nestedMap2);

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertNotNull(retrieved.state().get(State.APP_PREFIX + "config"));
    assertNotNull(retrieved.state().get(State.APP_PREFIX + "other_config"));
  }

  @Test
  public void testStateMerge_overwriteExistingKeys() {
    String sessionId1 = "overwrite-1";
    String sessionId2 = "overwrite-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.APP_PREFIX + "shared_key", "original_value");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.APP_PREFIX + "shared_key", "updated_value");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("updated_value", retrieved.state().get(State.APP_PREFIX + "shared_key"));
  }

  @Test
  public void testStateMerge_userStateDoesNotLoseData() {
    String sessionId1 = "user-merge-1";
    String sessionId2 = "user-merge-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put(State.USER_PREFIX + "pref1", "value1");
    state1.put(State.USER_PREFIX + "pref2", "value2");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put(State.USER_PREFIX + "pref3", "value3");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("value1", retrieved.state().get(State.USER_PREFIX + "pref1"));
    assertEquals("value2", retrieved.state().get(State.USER_PREFIX + "pref2"));
    assertEquals("value3", retrieved.state().get(State.USER_PREFIX + "pref3"));
  }

  @Test
  public void testStateMerge_sessionStateRemainsIsolated() {
    String sessionId1 = "session-isolated-1";
    String sessionId2 = "session-isolated-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("session_key1", "session_value1");
    state1.put(State.APP_PREFIX + "app_key", "shared");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("session_key2", "session_value2");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session retrieved1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId1, Optional.empty())
            .blockingGet();
    Session retrieved2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved1);
    assertNotNull(retrieved2);

    assertEquals("session_value1", retrieved1.state().get("session_key1"));
    assertEquals(null, retrieved1.state().get("session_key2"));

    assertEquals(null, retrieved2.state().get("session_key1"));
    assertEquals("session_value2", retrieved2.state().get("session_key2"));

    assertEquals("shared", retrieved1.state().get(State.APP_PREFIX + "app_key"));
    assertEquals("shared", retrieved2.state().get(State.APP_PREFIX + "app_key"));
  }

  @Test
  public void testStateMerge_largeStateDoesNotLoseData() {
    String sessionId1 = "large-state-1";
    String sessionId2 = "large-state-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    for (int i = 0; i < 50; i++) {
      state1.put(State.APP_PREFIX + "key_" + i, "value_" + i);
    }

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    for (int i = 50; i < 100; i++) {
      state2.put(State.APP_PREFIX + "key_" + i, "value_" + i);
    }

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state2, sessionId2).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId2, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);

    for (int i = 0; i < 100; i++) {
      assertEquals(
          "value_" + i,
          retrieved.state().get(State.APP_PREFIX + "key_" + i),
          "Key " + i + " should not be lost during merge");
    }
  }

  @Test
  public void testStateMerge_roundTripSerialization() {
    String sessionId = "roundtrip-test";

    ConcurrentHashMap<String, Object> originalState = new ConcurrentHashMap<>();
    originalState.put(State.APP_PREFIX + "string_key", "string_value");
    originalState.put(State.APP_PREFIX + "int_key", 42);
    originalState.put(State.APP_PREFIX + "double_key", 3.14);
    originalState.put(State.APP_PREFIX + "boolean_key", true);

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID_1, originalState, sessionId)
        .blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("string_value", retrieved.state().get(State.APP_PREFIX + "string_key"));
    assertEquals(42, retrieved.state().get(State.APP_PREFIX + "int_key"));
    assertEquals(3.14, retrieved.state().get(State.APP_PREFIX + "double_key"));
    assertEquals(true, retrieved.state().get(State.APP_PREFIX + "boolean_key"));
  }
}
