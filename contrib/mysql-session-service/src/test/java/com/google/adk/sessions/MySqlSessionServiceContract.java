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
import com.google.adk.events.EventCompaction;
import com.google.adk.events.ToolConfirmation;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contract tests aligned with the behavior of {@link InMemorySessionService}. */
abstract class MySqlSessionServiceContract {

  // MySQL TIMESTAMP cannot represent the first second after the Unix epoch.
  private static final long EVENT_EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

  protected DataSource dataSource;
  protected MySqlSessionService sessionService;

  protected abstract DataSource createDataSource();

  @BeforeEach
  void setUp() throws Exception {
    dataSource = createDataSource();
    MySqlTestSchema.reset(dataSource);
    sessionService = new MySqlSessionService(dataSource);
  }

  @AfterEach
  void tearDown() {
    closeDataSource();
  }

  protected void closeDataSource() {
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
            .afterTimestamp(Instant.ofEpochMilli(EVENT_EPOCH + 300))
            .build();

    Session retrieved =
        sessionService.getSession("app", "user", session.id(), Optional.of(config)).blockingGet();

    assertThat(retrieved.immutableEvents().stream().map(Event::timestamp))
        .containsExactly(EVENT_EPOCH + 300, EVENT_EPOCH + 400, EVENT_EPOCH + 500)
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

    assertThat(session.immutableEvents()).isEmpty();
    assertThat(session.state()).doesNotContainKey("key");
    assertThat(sessionService.listEvents("app", "user", session.id()).blockingGet().events())
        .isEmpty();
  }

  @Test
  void staleSessionFailsOptimisticLockAndRollsBackEvent() {
    Session created = sessionService.createSession("app", "user").blockingGet();
    Session firstWriter = get(created);
    Session staleWriter = get(created);
    Instant previousUpdateTime = staleWriter.lastUpdateTime();

    sessionService
        .appendEvent(firstWriter, event("committed", 1000, Map.of("key", "committed")))
        .blockingGet();
    sessionService
        .appendEvent(
            staleWriter,
            event("rolled-back", 2000, Map.of("key", "stale", "user:rolled-back", "stale")))
        .test()
        .assertError(java.util.ConcurrentModificationException.class);

    Session reloaded = get(created);
    assertThat(reloaded.state()).containsEntry("key", "committed");
    assertThat(reloaded.immutableEvents().stream().map(Event::id)).containsExactly("committed");
    assertThat(reloaded.state()).doesNotContainKey("user:rolled-back");
    assertThat(staleWriter.state()).doesNotContainKey("key");
    assertThat(staleWriter.state()).doesNotContainKey("user:rolled-back");
    assertThat(staleWriter.immutableEvents()).isEmpty();
    assertThat(staleWriter.lastUpdateTime()).isEqualTo(previousUpdateTime);
  }

  @Test
  void appendEventDoesNotMutateOrPersistUntilSubscribed() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    Instant previousUpdateTime = session.lastUpdateTime();
    Event event = event("deferred", 1000, Map.of("key", "value"));

    var pending = sessionService.appendEvent(session, event);

    assertThat(session.immutableEvents()).isEmpty();
    assertThat(session.state()).doesNotContainKey("key");
    assertThat(session.lastUpdateTime()).isEqualTo(previousUpdateTime);
    assertThat(get(session).immutableEvents()).isEmpty();

    assertThat(pending.blockingGet()).isSameInstanceAs(event);
    assertThat(session.immutableEvents()).containsExactly(event);
    assertThat(session.state()).containsEntry("key", "value");
    assertThat(get(session).immutableEvents()).hasSize(1);
  }

  @Test
  void duplicateSessionUsesCoreErrorContractWithoutChangingScopedState() {
    Session session =
        sessionService
            .createSession("app", "user", Map.of("user:name", "original"), "same")
            .blockingGet();

    sessionService
        .createSession("app", "user", Map.of("user:name", "replacement"), "same")
        .test()
        .assertError(
            error ->
                error instanceof SessionException
                    && SessionException.SESSION_ALREADY_EXISTS.equals(error.getMessage()));

    assertThat(get(session).state()).containsEntry("user:name", "original");
  }

  @Test
  void afterTimestampKeepsExactBoundaryAndExcludesEarlierMillisecondForFinerCursors() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    sessionService.appendEvent(session, event("boundary", 1400, Map.of())).blockingGet();
    sessionService.appendEvent(session, event("later", 1401, Map.of())).blockingGet();

    for (long extraNanos : new long[] {0, 1, 999, 1000, 300_000, 999_999}) {
      GetSessionConfig config =
          GetSessionConfig.builder()
              .afterTimestamp(Instant.ofEpochMilli(EVENT_EPOCH + 1400).plusNanos(extraNanos))
              .build();
      Session filtered =
          sessionService.getSession("app", "user", session.id(), Optional.of(config)).blockingGet();
      assertThat(filtered.immutableEvents().stream().map(Event::id))
          .containsExactlyElementsIn(
              extraNanos == 0 ? List.of("boundary", "later") : List.of("later"))
          .inOrder();
    }
  }

  @Test
  void wholeSecondAndFractionalEventsKeepTimeOrderWithStableTies() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    sessionService.appendEvent(session, event("whole", 1000, Map.of())).blockingGet();
    sessionService.appendEvent(session, event("fraction", 1400, Map.of())).blockingGet();
    sessionService.appendEvent(session, event("tie", 1400, Map.of())).blockingGet();
    sessionService.appendEvent(session, event("next", 2000, Map.of())).blockingGet();

    assertThat(get(session).immutableEvents().stream().map(Event::id))
        .containsExactly("whole", "fraction", "tie", "next")
        .inOrder();
    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(2).build();
    Session recent =
        sessionService.getSession("app", "user", session.id(), Optional.of(config)).blockingGet();
    assertThat(recent.immutableEvents().stream().map(Event::id))
        .containsExactly("tie", "next")
        .inOrder();
  }

  @Test
  void listEventsDoesNotExposeAnotherUserOrAppsHistory() {
    Session session = sessionService.createSession("app", "user").blockingGet();
    sessionService.appendEvent(session, event("private", 1000, Map.of())).blockingGet();

    assertThat(sessionService.listEvents("app", "other-user", session.id()).blockingGet().events())
        .isEmpty();
    assertThat(sessionService.listEvents("other-app", "user", session.id()).blockingGet().events())
        .isEmpty();
  }

  @Test
  void sharedMapValuesAreReplacedAndDottedKeysRemainLiteral() {
    Session first =
        sessionService
            .createSession(
                "app", "user", Map.of("user:prefs", Map.of("lang", "ko", "theme", "dark")), "first")
            .blockingGet();
    Session second = sessionService.createSession("app", "user", Map.of(), "second").blockingGet();
    sessionService
        .appendEvent(
            first,
            event(
                "replace",
                1000,
                Map.of(
                    "user:prefs",
                    Map.of("lang", "en"),
                    "user:settings.v2",
                    Map.of("enabled", true))))
        .blockingGet();

    assertThat(get(second).state()).containsEntry("user:prefs", Map.of("lang", "en"));
    assertThat(get(second).state()).containsEntry("user:settings.v2", Map.of("enabled", true));
  }

  @Test
  void deletedSharedValuesAndTemporaryContextStateDoNotReappearAfterLocalWrite() {
    Session first =
        sessionService
            .createSession("app", "user", Map.of("app:theme", "dark", "user:name", "Jae"), "first")
            .blockingGet();
    Session second = sessionService.createSession("app", "user", Map.of(), "second").blockingGet();
    second.state().put("temp:context", "ephemeral");
    sessionService.appendEvent(second, event("local", 1000, Map.of("counter", 1))).blockingGet();
    sessionService
        .appendEvent(
            first,
            event("remove", 2000, Map.of("app:theme", State.REMOVED, "user:name", State.REMOVED)))
        .blockingGet();

    Session reloaded = get(second);
    assertThat(reloaded.state()).containsEntry("counter", 1);
    assertThat(reloaded.state()).doesNotContainKey("app:theme");
    assertThat(reloaded.state()).doesNotContainKey("user:name");
    assertThat(reloaded.state()).doesNotContainKey("temp:context");
    assertThat(
            sessionService.listSessions("app", "user").blockingGet().sessions().stream()
                .allMatch(s -> !s.state().containsKey("user:name")))
        .isTrue();
  }

  @Test
  void fullEventPreservesIdsAuthorSignaturesAndInlineDataOnReload() throws Exception {
    Session session = sessionService.createSession("app", "account-42").blockingGet();
    Event original =
        Event.builder()
            .id("stable-id")
            .invocationId("invocation")
            .author("user")
            .branch("root.child")
            .timestamp(EVENT_EPOCH + 1000)
            .longRunningToolIds(Set.of("call-1"))
            .actions(
                EventActions.builder()
                    .requestedToolConfirmations(
                        Map.of("call-1", ToolConfirmation.builder().hint("Approve?").build()))
                    .build())
            .content(
                Content.fromParts(
                    Part.builder()
                        .text("thought")
                        .thought(true)
                        .thoughtSignature(new byte[] {1, 2, 3})
                        .build(),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("call-1")
                                .name("tool")
                                .args(Map.of("input", "value"))
                                .build())
                        .build(),
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("call-1")
                                .name("tool")
                                .response(Map.of("result", "value"))
                                .build())
                        .build(),
                    Part.builder()
                        .inlineData(
                            Blob.builder().data(new byte[] {4, 5, 6}).mimeType("image/png").build())
                        .build()))
            .build();
    sessionService.appendEvent(session, original).blockingGet();

    assertSameEventJson(original, get(session).immutableEvents().get(0));
    assertSameEventJson(
        original,
        sessionService.listEvents("app", "account-42", session.id()).blockingGet().events().get(0));
  }

  @Test
  void toolConfirmationWithStructuredArgsAndCompactionSurviveReload() throws Exception {
    Session session = sessionService.createSession("app", "user").blockingGet();
    Event confirmation =
        event("confirmation", 1000, Map.of()).toBuilder()
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("confirm-1")
                                .name("adk_request_confirmation")
                                .args(
                                    Map.of(
                                        "originalFunctionCall",
                                        FunctionCall.builder().id("call-1").name("tool").build(),
                                        "toolConfirmation",
                                        ToolConfirmation.builder().hint("Approve?").build()))
                                .build())
                        .build()))
            .build();
    Event compaction =
        event("compaction", 2000, Map.of()).toBuilder()
            .actions(
                EventActions.builder()
                    .compaction(
                        EventCompaction.builder()
                            .startTimestamp(EVENT_EPOCH)
                            .endTimestamp(EVENT_EPOCH + 1000)
                            .compactedContent(Content.fromParts(Part.fromText("summary")))
                            .build())
                    .build())
            .build();
    sessionService.appendEvent(session, confirmation).blockingGet();
    sessionService.appendEvent(session, compaction).blockingGet();

    List<Event> reloaded = get(session).immutableEvents();
    assertSameEventJson(confirmation, reloaded.get(0));
    assertSameEventJson(compaction, reloaded.get(1));
  }

  private static void assertSameEventJson(Event expected, Event actual) throws Exception {
    assertThat(JsonBaseModel.getMapper().readTree(actual.toJson()))
        .isEqualTo(JsonBaseModel.getMapper().readTree(expected.toJson()));
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
        .timestamp(EVENT_EPOCH + timestamp)
        .actions(EventActions.builder().stateDelta(stateDelta).build())
        .build();
  }
}
