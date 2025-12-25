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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StateManagementTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:state_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "state-test-app";
  private static final String TEST_USER_ID_1 = "user-1";
  private static final String TEST_USER_ID_2 = "user-2";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

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
    state1.put("app:global_setting", "shared_value");
    state1.put("session:local", "private_value_1");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("app:global_setting", "updated_value");
    state2.put("session:local", "private_value_2");

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

    assertEquals("updated_value", retrieved1.state().get("app:global_setting"));
    assertEquals("updated_value", retrieved2.state().get("app:global_setting"));

    assertEquals("private_value_1", retrieved1.state().get("session:local"));
    assertEquals("private_value_2", retrieved2.state().get("session:local"));
  }

  @Test
  public void testUserStateSharing() {
    String sessionId1 = "user-session-1";
    String sessionId2 = "user-session-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("user:preference", "dark_mode");
    state1.put("session:data", "session_specific_1");

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("user:preference", "light_mode");
    state2.put("session:data", "session_specific_2");

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

    assertEquals("light_mode", session1.state().get("user:preference"));
    assertEquals("light_mode", session2.state().get("user:preference"));

    assertEquals("session_specific_1", session1.state().get("session:data"));
    assertEquals("session_specific_2", session2.state().get("session:data"));
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
  public void testAllStateIsPersisted() {
    String sessionId = "persist-test";

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("data1", "value1");
    state.put("data2", "value2");

    Session created =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state, sessionId).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertTrue(retrieved.state().containsKey("data1"));
    assertTrue(retrieved.state().containsKey("data2"));
    assertEquals("value1", retrieved.state().get("data1"));
    assertEquals("value2", retrieved.state().get("data2"));
  }

  @Test
  public void testMultipleStateKeys() {
    String sessionId = "multiple-keys-test";

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("setting1", "value1");
    state.put("setting2", "value2");
    state.put("setting3", "value3");
    state.put("setting4", "value4");

    Session created =
        sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state, sessionId).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertTrue(retrieved.state().containsKey("setting1"));
    assertTrue(retrieved.state().containsKey("setting2"));
    assertTrue(retrieved.state().containsKey("setting3"));
    assertTrue(retrieved.state().containsKey("setting4"));
  }

  @Test
  public void testEmptyStateCreation() {
    String sessionId = "empty-state";

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, null, sessionId).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertNotNull(retrieved.state());
    assertTrue(retrieved.state().isEmpty());
  }

  @Test
  public void testStateRetrieval() {
    String sessionId = "state-retrieval";

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("config", "config_value");
    state.put("setting", "setting_value");
    state.put("data", "data_value");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state, sessionId).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertTrue(retrieved.state().containsKey("config"));
    assertTrue(retrieved.state().containsKey("setting"));
    assertTrue(retrieved.state().containsKey("data"));
    assertEquals("config_value", retrieved.state().get("config"));
    assertEquals("setting_value", retrieved.state().get("setting"));
    assertEquals("data_value", retrieved.state().get("data"));
  }

  @Test
  public void testThreeTierStateMergingPriority() {
    String sessionId = "merge-test";

    ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    state.put("app:key", "app_value");
    state.put("user:key", "user_value");
    state.put("session:key", "session_value");
    state.put("key", "unprefixed_value");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state, sessionId).blockingGet();

    Session retrieved =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID_1, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrieved);
    assertEquals("app_value", retrieved.state().get("app:key"));
    assertEquals("user_value", retrieved.state().get("user:key"));
    assertEquals("session_value", retrieved.state().get("session:key"));
    assertEquals("unprefixed_value", retrieved.state().get("key"));
  }

  @Test
  public void testStateMergingOverridePriority() {
    String sessionId1 = "priority-test-1";
    String sessionId2 = "priority-test-2";

    ConcurrentHashMap<String, Object> state1 = new ConcurrentHashMap<>();
    state1.put("app:shared", "app_level");
    state1.put("user:shared", "user_level");

    sessionService.createSession(TEST_APP_NAME, TEST_USER_ID_1, state1, sessionId1).blockingGet();

    ConcurrentHashMap<String, Object> state2 = new ConcurrentHashMap<>();
    state2.put("session:shared", "session_level");

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

    assertEquals("app_level", session1.state().get("app:shared"));
    assertEquals("user_level", session1.state().get("user:shared"));

    assertEquals("app_level", session2.state().get("app:shared"));
    assertEquals("user_level", session2.state().get("user:shared"));
    assertEquals("session_level", session2.state().get("session:shared"));
  }
}
