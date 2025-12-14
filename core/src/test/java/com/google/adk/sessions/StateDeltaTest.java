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
      "jdbc:h2:mem:delta_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
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

    Session updated =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    assertNotNull(updated);
    assertEquals(1, updated.state().get("counter"));
    assertEquals("added", updated.state().get("new_field"));
  }

  @Test
  public void testAppStateDeltaInEvent() {
    String sessionId = "app-delta-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("app:config_version", "v1");
    delta.put("app:feature_flag", true);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session updated =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    assertNotNull(updated);
    assertEquals("v1", updated.state().get("app:config_version"));
    assertEquals(true, updated.state().get("app:feature_flag"));
  }

  @Test
  public void testUserStateDeltaInEvent() {
    String sessionId = "user-delta-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("user:preference", "new_preference");
    delta.put("user:score", 100);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session updated =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    assertNotNull(updated);
    assertEquals("new_preference", updated.state().get("user:preference"));
    assertEquals(100, updated.state().get("user:score"));
  }

  @Test
  public void testMixedStateDeltaInEvent() {
    String sessionId = "mixed-delta-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("app:app_field", "app_value");
    delta.put("user:user_field", "user_value");
    delta.put("session_field", "session_value");
    delta.put("temp:temp_field", "temp_value");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session updated =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    assertNotNull(updated);
    assertEquals("app_value", updated.state().get("app:app_field"));
    assertEquals("user_value", updated.state().get("user:user_field"));
    assertEquals("session_value", updated.state().get("session_field"));
    assertFalse(updated.state().containsKey("temp:temp_field"));
  }

  @Test
  public void testMultipleDeltaUpdates() {
    String sessionId = "multi-delta-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("counter", 0);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
      delta.put("counter", i);

      EventActions actions = EventActions.builder().stateDelta(delta).build();

      Event event =
          Event.builder()
              .id(UUID.randomUUID().toString())
              .author("test")
              .content(Content.fromParts(Part.fromText("Update " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .actions(actions)
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    Session finalSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(finalSession);
    assertEquals(5, finalSession.state().get("counter"));
    assertEquals(5, finalSession.events().size());
  }

  @Test
  public void testStateDeltaWithNullValues() {
    String sessionId = "null-delta-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("field1", "value1");
    initialState.put("field2", "value2");
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("field3", "value3");

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session updated =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    assertNotNull(updated);
    assertEquals("value1", updated.state().get("field1"));
    assertEquals("value2", updated.state().get("field2"));
    assertEquals("value3", updated.state().get("field3"));
  }

  @Test
  public void testStateDeltaOverwritesExisting() {
    String sessionId = "overwrite-test";

    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("name", "old_name");
    initialState.put("version", 1);
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, initialState, sessionId)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("name", "new_name");
    delta.put("version", 2);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    Session updated =
        sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();

    assertNotNull(updated);
    assertEquals("new_name", updated.state().get("name"));
    assertEquals(2, updated.state().get("version"));
  }

  @Test
  public void testAppStateDeltaPropagation() {
    String sessionId1 = "app-propagate-1";
    String sessionId2 = "app-propagate-2";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, "other-user", new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("app:global_counter", 42);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId1, event).blockingGet();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId1, Optional.empty())
            .blockingGet();
    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, "other-user", sessionId2, Optional.empty())
            .blockingGet();

    assertEquals(42, session1.state().get("app:global_counter"));
    assertEquals(42, session2.state().get("app:global_counter"));
  }

  @Test
  public void testUserStateDeltaPropagation() {
    String sessionId1 = "user-propagate-1";
    String sessionId2 = "user-propagate-2";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId1)
        .blockingGet();
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId2)
        .blockingGet();

    ConcurrentHashMap<String, Object> delta = new ConcurrentHashMap<>();
    delta.put("user:points", 999);

    EventActions actions = EventActions.builder().stateDelta(delta).build();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .actions(actions)
            .build();

    sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId1, event).blockingGet();

    Session session1 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId1, Optional.empty())
            .blockingGet();
    Session session2 =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId2, Optional.empty())
            .blockingGet();

    assertEquals(999, session1.state().get("user:points"));
    assertEquals(999, session2.state().get("user:points"));
  }
}
