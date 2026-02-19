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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import io.reactivex.rxjava3.core.Single;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InMemorySessionService}. */
@RunWith(JUnit4.class)
public final class InMemorySessionServiceTest {

  @Test
  public void lifecycle_noSession() {
    InMemorySessionService sessionService = new InMemorySessionService();

    assertThat(
            sessionService
                .getSession("app-name", "user-id", "session-id", Optional.empty())
                .blockingGet())
        .isNull();

    assertThat(sessionService.listSessions("app-name", "user-id").blockingGet().sessions())
        .isEmpty();

    assertThat(
            sessionService.listEvents("app-name", "user-id", "session-id").blockingGet().events())
        .isEmpty();
  }

  @Test
  public void lifecycle_createSession() {
    InMemorySessionService sessionService = new InMemorySessionService();

    Single<Session> sessionSingle = sessionService.createSession("app-name", "user-id");

    Session session = sessionSingle.blockingGet();

    assertThat(session.id()).isNotNull();
    assertThat(session.appName()).isEqualTo("app-name");
    assertThat(session.userId()).isEqualTo("user-id");
    assertThat(session.state()).isEmpty();
  }

  @Test
  public void lifecycle_getSession() {
    InMemorySessionService sessionService = new InMemorySessionService();

    Session session = sessionService.createSession("app-name", "user-id").blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();

    assertThat(retrievedSession).isNotNull();
    assertThat(retrievedSession.id()).isEqualTo(session.id());
  }

  @Test
  public void lifecycle_listSessions() {
    InMemorySessionService sessionService = new InMemorySessionService();

    Session session =
        sessionService
            .createSession("app-name", "user-id", new HashMap<>(), "session-1")
            .blockingGet();

    Map<String, Object> stateDelta = new HashMap<>();
    stateDelta.put("sessionKey", "sessionValue");
    stateDelta.put("_app_appKey", "appValue");
    stateDelta.put("_user_userKey", "userValue");
    stateDelta.put("temp:tempKey", "tempValue");

    Event event =
        Event.builder().actions(EventActions.builder().stateDelta(stateDelta).build()).build();

    var unused = sessionService.appendEvent(session, event).blockingGet();

    ListSessionsResponse response =
        sessionService.listSessions(session.appName(), session.userId()).blockingGet();
    Session listedSession = response.sessions().get(0);

    assertThat(response.sessions()).hasSize(1);
    assertThat(listedSession.id()).isEqualTo(session.id());
    assertThat(listedSession.events()).isEmpty();
    assertThat(listedSession.state()).containsEntry("sessionKey", "sessionValue");
    assertThat(listedSession.state()).containsEntry("_app_appKey", "appValue");
    assertThat(listedSession.state()).containsEntry("_user_userKey", "userValue");
    assertThat(listedSession.state()).containsEntry("temp:tempKey", "tempValue");
  }

  @Test
  public void lifecycle_deleteSession() {
    InMemorySessionService sessionService = new InMemorySessionService();

    Session session = sessionService.createSession("app-name", "user-id").blockingGet();

    sessionService.deleteSession(session.appName(), session.userId(), session.id()).blockingAwait();

    assertThat(
            sessionService
                .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet())
        .isNull();
  }

  @Test
  public void appendEvent_updatesSessionState() {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session =
        sessionService.createSession("app", "user", new HashMap<>(), "session1").blockingGet();

    Map<String, Object> stateDelta = new HashMap<>();
    stateDelta.put("sessionKey", "sessionValue");
    stateDelta.put("_app_appKey", "appValue");
    stateDelta.put("_user_userKey", "userValue");
    stateDelta.put("temp:tempKey", "tempValue");

    Event event =
        Event.builder().actions(EventActions.builder().stateDelta(stateDelta).build()).build();

    var unused = sessionService.appendEvent(session, event).blockingGet();

    // After appendEvent, session state in memory should contain session-specific state from delta
    // and merged global state.
    assertThat(session.state()).containsEntry("sessionKey", "sessionValue");
    assertThat(session.state()).containsEntry("_app_appKey", "appValue");
    assertThat(session.state()).containsEntry("_user_userKey", "userValue");
    assertThat(session.state()).containsEntry("temp:tempKey", "tempValue");

    // getSession should return session with merged state.
    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(retrievedSession.state()).containsEntry("sessionKey", "sessionValue");
    assertThat(retrievedSession.state()).containsEntry("_app_appKey", "appValue");
    assertThat(retrievedSession.state()).containsEntry("_user_userKey", "userValue");
    assertThat(retrievedSession.state()).containsEntry("temp:tempKey", "tempValue");
  }

  @Test
  public void appendEvent_removesState() {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session =
        sessionService.createSession("app", "user", new HashMap<>(), "session1").blockingGet();

    Map<String, Object> stateDeltaAdd = new HashMap<>();
    stateDeltaAdd.put("sessionKey", "sessionValue");
    stateDeltaAdd.put("_app_appKey", "appValue");
    stateDeltaAdd.put("_user_userKey", "userValue");
    stateDeltaAdd.put("temp:tempKey", "tempValue");

    Event eventAdd =
        Event.builder().actions(EventActions.builder().stateDelta(stateDeltaAdd).build()).build();

    var unused = sessionService.appendEvent(session, eventAdd).blockingGet();

    // Verify state is added
    Session retrievedSessionAdd =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(retrievedSessionAdd.state()).containsEntry("sessionKey", "sessionValue");
    assertThat(retrievedSessionAdd.state()).containsEntry("_app_appKey", "appValue");
    assertThat(retrievedSessionAdd.state()).containsEntry("_user_userKey", "userValue");
    assertThat(retrievedSessionAdd.state()).containsEntry("temp:tempKey", "tempValue");

    // Prepare and append event to remove state
    Map<String, Object> stateDeltaRemove = new HashMap<>();
    stateDeltaRemove.put("sessionKey", null);
    stateDeltaRemove.put("_app_appKey", null);
    stateDeltaRemove.put("_user_userKey", null);
    stateDeltaRemove.put("temp:tempKey", null);

    Event eventRemove =
        Event.builder()
            .actions(EventActions.builder().stateDelta(stateDeltaRemove).build())
            .build();

    unused = sessionService.appendEvent(session, eventRemove).blockingGet();

    // Verify state is removed
    Session retrievedSessionRemove =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(retrievedSessionRemove.state()).doesNotContainKey("sessionKey");
    assertThat(retrievedSessionRemove.state()).doesNotContainKey("_app_appKey");
    assertThat(retrievedSessionRemove.state()).doesNotContainKey("_user_userKey");
    assertThat(retrievedSessionRemove.state()).doesNotContainKey("temp:tempKey");
  }

  @Test
  public void sequentialAgents_shareTempState() {
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session =
        sessionService.createSession("app", "user", new HashMap<>(), "session1").blockingGet();

    // Agent 1 writes to temp state
    Map<String, Object> stateDelta1 = new HashMap<>();
    stateDelta1.put("temp:agent1_output", "data");
    Event event1 =
        Event.builder().actions(EventActions.builder().stateDelta(stateDelta1).build()).build();
    var unused = sessionService.appendEvent(session, event1).blockingGet();

    // Verify agent 1 output is in session state
    assertThat(session.state()).containsEntry("temp:agent1_output", "data");

    // Agent 2 reads "agent1_output", processes it, writes "agent2_output", and removes
    // "agent1_output"
    Map<String, Object> stateDelta2 = new HashMap<>();
    stateDelta2.put("temp:agent2_output", "processed_data");
    stateDelta2.put("temp:agent1_output", null);
    Event event2 =
        Event.builder().actions(EventActions.builder().stateDelta(stateDelta2).build()).build();
    unused = sessionService.appendEvent(session, event2).blockingGet();

    // Verify final state after agent 2 processing
    Session retrievedSession =
        sessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(retrievedSession.state()).doesNotContainKey("temp:agent1_output");
    assertThat(retrievedSession.state()).containsEntry("temp:agent2_output", "processed_data");
  }
}
