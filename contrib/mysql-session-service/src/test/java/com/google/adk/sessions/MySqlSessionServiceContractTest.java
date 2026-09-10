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

import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contract tests aligned with the behavior of {@link InMemorySessionService}. */
class MySqlSessionServiceContractTest {

  private DataSource dataSource;
  private MySqlSessionService sessionService;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:contractdb;MODE=MySQL;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    dataSource = new HikariDataSource(config);
    MySqlTestSchema.reset(dataSource);
    sessionService = new MySqlSessionService(dataSource);
  }

  @AfterEach
  void tearDown() {
    if (dataSource instanceof HikariDataSource hikariDataSource) {
      hikariDataSource.close();
    }
  }

  @Test
  void sameSessionIdCanExistInDifferentScopes() {
    Session first =
        sessionService.createSession("app-1", "user-1", Map.of(), "shared").blockingGet();
    Session second =
        sessionService.createSession("app-2", "user-1", Map.of(), "shared").blockingGet();
    Session third =
        sessionService.createSession("app-1", "user-2", Map.of(), "shared").blockingGet();

    sessionService.appendEvent(first, event("first", 100, Map.of("value", 1))).blockingGet();
    sessionService.appendEvent(second, event("second", 100, Map.of("value", 2))).blockingGet();
    sessionService.appendEvent(third, event("third", 100, Map.of("value", 3))).blockingGet();

    assertThat(get(first).state()).containsEntry("value", 1);
    assertThat(get(second).state()).containsEntry("value", 2);
    assertThat(get(third).state()).containsEntry("value", 3);

    sessionService.deleteSession("app-1", "user-1", "shared").blockingAwait();
    assertThat(getOrNull("app-1", "user-1", "shared")).isNull();
    assertThat(get(second)).isNotNull();
    assertThat(get(third)).isNotNull();
  }

  @Test
  void initialScopedStateIsPersistedAndTemporaryStateIsEphemeral() {
    Map<String, Object> initialState = new HashMap<>();
    initialState.put("local", "local-value");
    initialState.put(State.APP_PREFIX + "setting", "app-value");
    initialState.put(State.USER_PREFIX + "setting", "user-value");
    initialState.put(State.TEMP_PREFIX + "request", "temporary-value");

    Session created =
        sessionService.createSession("app", "user", initialState, "session-1").blockingGet();
    assertThat(created.state()).containsEntry(State.TEMP_PREFIX + "request", "temporary-value");

    Session reloaded = get(created);
    assertThat(reloaded.state()).containsEntry("local", "local-value");
    assertThat(reloaded.state()).containsEntry(State.APP_PREFIX + "setting", "app-value");
    assertThat(reloaded.state()).containsEntry(State.USER_PREFIX + "setting", "user-value");
    assertThat(reloaded.state()).doesNotContainKey(State.TEMP_PREFIX + "request");

    Session another =
        sessionService.createSession("app", "user", Map.of(), "session-2").blockingGet();
    assertThat(another.state()).containsEntry(State.APP_PREFIX + "setting", "app-value");
    assertThat(another.state()).containsEntry(State.USER_PREFIX + "setting", "user-value");
    assertThat(another.state()).doesNotContainKey("local");
  }

  @Test
  void appStateIsSharedAcrossUsersButUserStateIsIsolated() {
    sessionService
        .createSession(
            "app",
            "user-1",
            Map.of(
                State.APP_PREFIX + "shared", "app-value",
                State.USER_PREFIX + "private", "user-value"),
            "first")
        .blockingGet();

    Session anotherUser =
        sessionService.createSession("app", "user-2", Map.of(), "second").blockingGet();
    assertThat(anotherUser.state()).containsEntry(State.APP_PREFIX + "shared", "app-value");
    assertThat(anotherUser.state()).doesNotContainKey(State.USER_PREFIX + "private");

    Session anotherApp =
        sessionService.createSession("other-app", "user-1", Map.of(), "third").blockingGet();
    assertThat(anotherApp.state()).doesNotContainKey(State.APP_PREFIX + "shared");
    assertThat(anotherApp.state()).doesNotContainKey(State.USER_PREFIX + "private");
  }

  @Test
  void getSessionComposesRecentAndInclusiveTimestampFilters() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    for (long timestamp : new long[] {100, 200, 300, 400, 500}) {
      sessionService
          .appendEvent(session, event("event-" + timestamp, timestamp, Map.of()))
          .blockingGet();
    }
    GetSessionConfig config =
        GetSessionConfig.builder()
            .numRecentEvents(4)
            .afterTimestamp(Instant.ofEpochMilli(300))
            .build();

    Session retrieved =
        sessionService.getSession("app", "user", session.id(), Optional.of(config)).blockingGet();

    assertThat(retrieved.events().stream().map(Event::timestamp))
        .containsExactly(300L, 400L, 500L)
        .inOrder();
  }

  @Test
  void equalTimestampsRetainInsertionOrder() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    for (String id : List.of("first", "second", "third")) {
      sessionService.appendEvent(session, event(id, 1000, Map.of())).blockingGet();
    }

    assertThat(
            sessionService.listEvents("app", "user", session.id()).blockingGet().events().stream()
                .map(Event::id))
        .containsExactly("first", "second", "third")
        .inOrder();
  }

  @Test
  void removedStateRoundTripsAsSentinelAndUsesJsonNull() throws Exception {
    Session session =
        sessionService
            .createSession(
                "app",
                "user",
                Map.of(
                    "local",
                    "value",
                    State.APP_PREFIX + "app-key",
                    "value",
                    State.USER_PREFIX + "user-key",
                    "value"),
                "session")
            .blockingGet();
    ConcurrentMap<String, Object> removals = new ConcurrentHashMap<>();
    removals.put("local", State.REMOVED);
    removals.put(State.APP_PREFIX + "app-key", State.REMOVED);
    removals.put(State.USER_PREFIX + "user-key", State.REMOVED);
    sessionService.appendEvent(session, event("removed", 1000, removals)).blockingGet();

    Event persisted =
        sessionService.listEvents("app", "user", "session").blockingGet().events().get(0);
    assertThat(persisted.actions().stateDelta().get("local")).isSameInstanceAs(State.REMOVED);
    assertThat(persisted.actions().stateDelta().get(State.APP_PREFIX + "app-key"))
        .isSameInstanceAs(State.REMOVED);
    assertThat(get(session).state()).doesNotContainKey("local");

    String eventJson = readOnlyEventJson();
    assertThat(eventJson).contains("\"local\":null");
    assertThat(eventJson).doesNotContain("__ADK_SENTINEL_REMOVED__");
  }

  @Test
  void legacyRemovedSentinelStillDeserializes() throws Exception {
    Session session = sessionService.createSession("app", "user").blockingGet();
    ConcurrentMap<String, Object> removals = new ConcurrentHashMap<>();
    removals.put("legacy", State.REMOVED);
    sessionService.appendEvent(session, event("legacy", 1000, removals)).blockingGet();

    String currentJson = readOnlyEventJson();
    String directEventJson =
        JsonBaseModel.getMapper()
            .writeValueAsString(JsonBaseModel.getMapper().readTree(currentJson).get("event"));
    String legacyJson =
        directEventJson.replace("\"legacy\":null", "\"legacy\":\"__ADK_SENTINEL_REMOVED__\"");
    assertThat(legacyJson).isNotEqualTo(directEventJson);
    try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt =
            conn.prepareStatement("UPDATE adk_events SET event_data = ? WHERE event_id = ?")) {
      pstmt.setString(1, legacyJson);
      pstmt.setString(2, "legacy");
      pstmt.executeUpdate();
    }

    Event persisted =
        sessionService.listEvents("app", "user", session.id()).blockingGet().events().get(0);
    assertThat(persisted.actions().stateDelta().get("legacy")).isSameInstanceAs(State.REMOVED);
  }

  @Test
  void literalLegacySentinelStringIsPreservedInCurrentFormat() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    sessionService
        .appendEvent(session, event("literal", 1000, Map.of("literal", "__ADK_SENTINEL_REMOVED__")))
        .blockingGet();

    Event persisted =
        sessionService.listEvents("app", "user", session.id()).blockingGet().events().get(0);
    assertThat(persisted.actions().stateDelta())
        .containsEntry("literal", "__ADK_SENTINEL_REMOVED__");
  }

  @Test
  void partialEventIsNotPersistedOrApplied() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    Event partial =
        Event.builder()
            .id("partial")
            .partial(true)
            .actions(EventActions.builder().stateDelta(Map.of("key", "value")).build())
            .build();

    sessionService.appendEvent(session, partial).blockingGet();

    assertThat(session.events()).isEmpty();
    assertThat(session.state()).doesNotContainKey("key");
    assertThat(sessionService.listEvents("app", "user", session.id()).blockingGet().events())
        .isEmpty();
  }

  @Test
  void staleSessionFailsOptimisticLockAndRollsBackEvent() {
    Session created = sessionService.createSession("app", "user").blockingGet();
    Session firstWriter = get(created);
    Session staleWriter = get(created);

    sessionService
        .appendEvent(firstWriter, event("committed", 1000, Map.of("key", "committed")))
        .blockingGet();
    sessionService
        .appendEvent(staleWriter, event("rolled-back", 2000, Map.of("key", "stale")))
        .test()
        .assertError(java.util.ConcurrentModificationException.class);

    Session reloaded = get(created);
    assertThat(reloaded.state()).containsEntry("key", "committed");
    assertThat(reloaded.events().stream().map(Event::id)).containsExactly("committed");
  }

  @Test
  void duplicateEventIdsAreAllowedLikeInMemoryService() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    sessionService.appendEvent(session, event("duplicate", 1000, Map.of())).blockingGet();
    sessionService.appendEvent(session, event("duplicate", 2000, Map.of())).blockingGet();

    assertThat(sessionService.listEvents("app", "user", session.id()).blockingGet().events())
        .hasSize(2);
  }

  @Test
  void missingSessionOperationsAreNoOpsOrEmpty() {
    assertThat(getOrNull("app", "user", "missing")).isNull();
    assertThat(sessionService.listEvents("app", "user", "missing").blockingGet().events())
        .isEmpty();
    sessionService.deleteSession("app", "user", "missing").blockingAwait();
  }

  private Session get(Session session) {
    return sessionService
        .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
        .blockingGet();
  }

  private Session getOrNull(String appName, String userId, String sessionId) {
    return sessionService.getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
  }

  private String readOnlyEventJson() throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt =
            conn.prepareStatement("SELECT event_data FROM adk_events ORDER BY event_sequence");
        ResultSet rs = pstmt.executeQuery()) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }

  private static Event event(String id, long timestamp, Map<String, Object> stateDelta) {
    return Event.builder()
        .id(id)
        .timestamp(timestamp)
        .actions(EventActions.builder().stateDelta(stateDelta).build())
        .build();
  }
}
