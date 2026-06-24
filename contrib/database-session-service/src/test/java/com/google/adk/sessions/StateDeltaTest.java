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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class StateDeltaTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:jdbc_delta_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "delta-test-app";
  private static final String TEST_USER_ID = "delta-user";

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
  public void testStateDeltaInEvent() {
    String sessionId = "delta-event-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("counter", 0);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("counter", 1);
    delta.put("new_field", "added");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertEquals(1, updated.state().get("counter"));
    assertEquals("added", updated.state().get("new_field"));
  }

  @Test
  public void testAppStateDeltaInEvent() {
    String sessionId = "app-delta-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "app_counter", 0);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "app_counter", 10);
    delta.put(State.APP_PREFIX + "app_field", "app_value");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertEquals(10, updated.state().get(State.APP_PREFIX + "app_counter"));
    assertEquals("app_value", updated.state().get(State.APP_PREFIX + "app_field"));
  }

  @Test
  public void testUserStateDeltaInEvent() {
    String sessionId = "user-delta-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.USER_PREFIX + "user_counter", 0);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.USER_PREFIX + "user_counter", 5);
    delta.put(State.USER_PREFIX + "user_field", "user_value");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertEquals(5, updated.state().get(State.USER_PREFIX + "user_counter"));
    assertEquals("user_value", updated.state().get(State.USER_PREFIX + "user_field"));
  }

  @Test
  public void testMixedStateDeltaInEvent() {
    String sessionId = "mixed-delta-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "app_value", "initial_app");
    initialState.put(State.USER_PREFIX + "user_value", "initial_user");
    initialState.put("session_value", "initial_session");

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "app_value", "updated_app");
    delta.put(State.USER_PREFIX + "user_value", "updated_user");
    delta.put("session_value", "updated_session");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertEquals("updated_app", updated.state().get(State.APP_PREFIX + "app_value"));
    assertEquals("updated_user", updated.state().get(State.USER_PREFIX + "user_value"));
    assertEquals("updated_session", updated.state().get("session_value"));
  }

  @Test
  public void testStateRemovalViaRemoved() {
    String sessionId = "session-removal-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("key_to_remove", "value");
    initialState.put("key_to_keep", "keep_this");
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("key_to_remove", State.REMOVED);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertFalse(updated.state().containsKey("key_to_remove"));
    assertEquals("keep_this", updated.state().get("key_to_keep"));
  }

  @Test
  public void testAppStateRemovalViaRemoved() {
    String sessionId = "app-removal-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "deprecated", "old_value");
    initialState.put(State.APP_PREFIX + "current", "keep_this");
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "deprecated", State.REMOVED);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertFalse(updated.state().containsKey(State.APP_PREFIX + "deprecated"));
    assertEquals("keep_this", updated.state().get(State.APP_PREFIX + "current"));
  }

  @Test
  public void testUserStateRemovalViaRemoved() {
    String sessionId = "user-removal-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.USER_PREFIX + "old_pref", "remove_me");
    initialState.put(State.USER_PREFIX + "new_pref", "keep_this");
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.USER_PREFIX + "old_pref", State.REMOVED);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertFalse(updated.state().containsKey(State.USER_PREFIX + "old_pref"));
    assertEquals("keep_this", updated.state().get(State.USER_PREFIX + "new_pref"));
  }

  @Test
  public void testMixedStateRemovalViaRemoved() {
    String sessionId = "mixed-removal-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put(State.APP_PREFIX + "app_deprecated", "remove");
    initialState.put(State.APP_PREFIX + "app_current", "keep");
    initialState.put(State.USER_PREFIX + "user_old", "remove");
    initialState.put(State.USER_PREFIX + "user_new", "keep");
    initialState.put("session_temp", "remove");
    initialState.put("session_data", "keep");

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put(State.APP_PREFIX + "app_deprecated", State.REMOVED);
    delta.put(State.USER_PREFIX + "user_old", State.REMOVED);
    delta.put("session_temp", State.REMOVED);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();
    Session updated =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(updated);
    assertFalse(updated.state().containsKey(State.APP_PREFIX + "app_deprecated"));
    assertFalse(updated.state().containsKey(State.USER_PREFIX + "user_old"));
    assertFalse(updated.state().containsKey("session_temp"));
    assertEquals("keep", updated.state().get(State.APP_PREFIX + "app_current"));
    assertEquals("keep", updated.state().get(State.USER_PREFIX + "user_new"));
    assertEquals("keep", updated.state().get("session_data"));
  }
}
