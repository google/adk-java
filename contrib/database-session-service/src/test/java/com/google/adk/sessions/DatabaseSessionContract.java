/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.google.adk.JsonBaseModel;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.DatabaseSessionService.Dialect;
import com.google.adk.testing.TestLlm;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DynamicTest;

/** One backend-parameterized contract; no copies of individual engine test methods. */
final class DatabaseSessionContract {
  private static final GetSessionConfig ALL = GetSessionConfig.builder().build();
  private final DataSource source;
  private final Dialect dialect;

  DatabaseSessionContract(DataSource source, Dialect dialect) {
    this.source = source;
    this.dialect = dialect;
  }

  DatabaseSessionService service() {
    return DatabaseSessionService.builder(source)
        .dialect(dialect)
        .instantSource(InstantSource.fixed(Instant.ofEpochMilli(123).plusNanos(456_789)))
        .build();
  }

  Stream<DynamicTest> tests() {
    // What this database shares with the in-memory reference, then what is specific to JDBC.
    return Stream.concat(new SessionServiceContract(this::service).tests(), databaseTests());
  }

  private Stream<DynamicTest> databaseTests() {
    return Stream.of(
        dynamicTest("lifecycle and precise injected time", this::lifecycle),
        dynamicTest("duplicate create rolls back shared state", this::duplicate),
        dynamicTest("full scope and shared state isolation", this::scope),
        dynamicTest("listing orders by update time then ID", this::listOrder),
        dynamicTest("shared state update times use the database UTC clock", this::sharedTimes),
        dynamicTest("global removal cannot be resurrected", this::sharedRemoval),
        dynamicTest("append preserves unrelated and typed live state", this::liveState),
        dynamicTest("append order with equal and backwards time", this::order),
        dynamicTest("duplicate event ID rolls back all scopes", this::duplicateEvent),
        dynamicTest("limit before precise inclusive cursor", this::filters),
        dynamicTest("real deletion vs literal sentinel", this::removal),
        dynamicTest("temp values reach the supplied session but are never persisted", this::temp),
        dynamicTest("entire Java event payload", this::fullEvent),
        dynamicTest("invocation IDs and nullable payload column", this::eventColumns),
        dynamicTest("partial is a complete no-op", this::partial),
        dynamicTest("stale metadata-only snapshots", this::stale),
        dynamicTest("filtered snapshots must contain the persisted tail", this::filteredAppend),
        dynamicTest("delete and recreate can have an indistinguishable tail", this::recreatedTail),
        dynamicTest("append from detached JSON and Java builder copies", this::detachedAppend),
        dynamicTest("case accents unicode trailing spaces", this::keys),
        dynamicTest("uniform key limits and invalid Unicode", this::invalidKeys),
        dynamicTest("large JSON documents", this::largeJson),
        dynamicTest(
            "JSON values retain signed zero, floating exponent and escaped NUL", this::jsonValues),
        dynamicTest("UTC timestamp range and precision", this::timeRange),
        dynamicTest("Runner restart persists conversation without cloud LLM", this::runner),
        dynamicTest("same-session concurrent append rejects one writer", this::concurrentStale),
        dynamicTest("independent shared updates are not lost", this::concurrentShared),
        dynamicTest("concurrent first create preserves all shared keys", this::concurrentCreate),
        dynamicTest("concurrent duplicate create uses standard error", this::concurrentDuplicate),
        dynamicTest("delete/append race has no orphan history", this::concurrentDelete));
  }

  private void lifecycle() {
    DatabaseSessionService first = service();
    String app = app();
    Session session = first.createSession(app, "user").blockingGet();
    assertThat(session.id()).isNotEmpty();
    Instant created = Instant.ofEpochMilli(123).plusNanos(456_000);
    assertThat(session.lastUpdateTime()).isEqualTo(created);
    assertThat(load(service(), session).lastUpdateTime()).isEqualTo(created);
    assertThat(load(service(), session).state()).isEmpty();
    assertThat(first.listSessions(app, "user").blockingGet().sessionIds())
        .containsExactly(session.id());
    first.deleteSession(session.sessionKey()).blockingAwait();
    assertThat(first.getSession(session.sessionKey(), ALL).blockingGet()).isNull();
    assertThrows(
        SessionNotFoundException.class,
        () -> first.deleteSession(session.sessionKey()).blockingAwait());
    assertThrows(
        SessionNotFoundException.class, () -> first.listEvents(session.sessionKey()).blockingGet());
  }

  private void duplicate() {
    DatabaseSessionService service = service();
    Session original = create(service, app(), "u", "id", Map.of("app:k", "original", "user:k", 2));
    service.appendEvent(original, event("e", 10, Map.of())).blockingGet();
    SessionException error =
        assertThrows(
            SessionException.class,
            () ->
                create(
                    service,
                    original.appName(),
                    "u",
                    "id",
                    Map.of("app:k", "wrong", "user:k", 99)));
    assertThat(error).hasMessageThat().isEqualTo(SessionException.SESSION_ALREADY_EXISTS);
    assertThat(error).hasCauseThat().isNull();
    Session reloaded = load(service, original);
    assertThat(reloaded.state()).containsExactly("app:k", "original", "user:k", 2);
    assertThat(reloaded.immutableEvents()).hasSize(1);
  }

  private void scope() {
    DatabaseSessionService service = service();
    String app = app();
    Session a = create(service, app, "u", "id", Map.of("app:a", 1, "user:u", 2, "local", 3));
    Session b = create(service, app, "u", "other", Map.of());
    Session c = create(service, app, "other-user", "id", Map.of());
    Session d = create(service, app(), "u", "id", Map.of());
    assertThat(load(service, b).state()).containsExactly("app:a", 1, "user:u", 2);
    assertThat(load(service, c).state()).containsExactly("app:a", 1);
    assertThat(load(service, d).state()).isEmpty();
    service.deleteSession(a.sessionKey()).blockingAwait();
    assertThat(load(service, b).state()).containsExactly("app:a", 1, "user:u", 2);
  }

  private void sharedRemoval() {
    DatabaseSessionService service = service();
    String app = app();
    Session old = create(service, app, "u", "old", Map.of("app:k", 1, "user:k", 2));
    Session other = create(service, app, "u", "other", Map.of());
    service
        .appendEvent(
            other, event("delete", 10, Map.of("app:k", State.REMOVED, "user:k", State.REMOVED)))
        .blockingGet();
    service.appendEvent(old, event("local", 20, Map.of("local", true))).blockingGet();
    assertThat(old.state()).containsExactly("app:k", 1, "user:k", 2, "local", true);
    assertThat(load(service, old).state()).containsExactly("local", true);
  }

  private void listOrder() {
    DatabaseSessionService service = service();
    String app = app();
    Session a = create(service, app, "u", "a", Map.of());
    create(service, app, "u", "b", Map.of());
    Session z = create(service, app, "u", "z", Map.of());
    service.appendEvent(z, event("oldest", -1, Map.of())).blockingGet();
    assertThat(service.listSessions(app, "u").blockingGet().sessionIds())
        .containsExactly("z", "a", "b")
        .inOrder();
    service.appendEvent(a, event("newest", 1000, Map.of())).blockingGet();
    assertThat(service.listSessions(app, "u").blockingGet().sessionIds())
        .containsExactly("z", "b", "a")
        .inOrder();
  }

  private void sharedTimes() throws Exception {
    DatabaseSessionService service = service();
    Instant start = Instant.now().minusSeconds(5);
    Session session = create(service, app(), "u", "id", Map.of());
    assertSharedTimes(session, start, Instant.now().plusSeconds(5));
    // Reset the shared clocks to prove that append also writes the database time.
    try (Connection connection = source.getConnection()) {
      for (String table : List.of("app_states", "user_states")) {
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE " + table + " SET update_time=? WHERE app_name=?")) {
          DatabaseTime.bind(update, 1, Instant.EPOCH, dialect);
          update.setString(2, session.appName());
          assertThat(update.executeUpdate()).isEqualTo(1);
        }
      }
    }
    service
        .appendEvent(session, event("historic", 1, Map.of("app:key", 1, "user:key", 2)))
        .blockingGet();
    assertSharedTimes(session, start, Instant.now().plusSeconds(5));
    assertThat(load(service, session).lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(1));
  }

  private void assertSharedTimes(Session session, Instant start, Instant end) throws Exception {
    try (Connection connection = source.getConnection()) {
      for (String table : List.of("app_states", "user_states")) {
        try (PreparedStatement query =
            connection.prepareStatement("SELECT update_time FROM " + table + " WHERE app_name=?")) {
          query.setString(1, session.appName());
          try (ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            Instant time = DatabaseTime.read(rows, "update_time", dialect);
            assertThat(time).isAtLeast(start);
            assertThat(time).isAtMost(end);
          }
        }
      }
    }
  }

  private void order() {
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of());
    int index = 0;
    for (long time : new long[] {3000, 1000, 1000, -1}) {
      service.appendEvent(session, event("event-" + index++, time, Map.of())).blockingGet();
    }
    assertThat(load(service, session).immutableEvents().stream().map(Event::timestamp).toList())
        .containsExactly(3000L, 1000L, 1000L, -1L)
        .inOrder();
    assertThat(session.lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(-1));
    assertThat(load(service, session).lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(-1));
  }

  private void duplicateEvent() {
    DatabaseSessionService service = service();
    Session session =
        create(service, app(), "u", "id", Map.of("app:k", 1, "user:k", 2, "local", 3));
    service.appendEvent(session, event("existing", 1, Map.of())).blockingGet();
    String before = session.toJson();
    SessionException failure =
        assertThrows(
            SessionException.class,
            () ->
                service
                    .appendEvent(
                        session,
                        event("existing", 2, Map.of("app:k", 10, "user:k", 20, "local", 30)))
                    .blockingGet());
    assertThat(failure)
        .hasMessageThat()
        .isEqualTo("An event with this ID already exists in the session.");
    assertThat(session.toJson()).isEqualTo(before);
    Session restored = load(service, session);
    assertThat(restored.state()).containsExactly("app:k", 1, "user:k", 2, "local", 3);
    assertThat(restored.immutableEvents()).hasSize(1);
    Session other = create(service, session.appName(), "u", "other", Map.of());
    service.appendEvent(other, event("existing", 3, Map.of())).blockingGet();
    assertThat(load(service, other).immutableEvents()).hasSize(1);
  }

  private void liveState() {
    DatabaseSessionService service = service();
    Session session =
        create(
            service,
            app(),
            "u",
            "id",
            Map.of("app:gone", 1, "app:pending", 2, "stable", List.of(1)));
    Session other = create(service, session.appName(), "u", "other", Map.of());
    Object unchanged = session.state().get("stable");
    Map<String, Object> pendingDelta = EventActions.builder().build().stateDelta();
    // Like an agent Context, this view writes live state before its event is appended.
    State pending = new State(session.state(), pendingDelta);
    pending.put("new-key", "waiting");
    pending.put("app:pending", "waiting");
    service
        .appendEvent(
            other, event("other", 1, Map.of("app:gone", State.REMOVED, "app:pending", "database")))
        .blockingGet();
    byte[] bytes = {1, 2, 3};
    Long count = 42L;
    service()
        .appendEvent(session, event("first", 2, Map.of("bytes", bytes, "count", count)))
        .blockingGet();
    service.appendEvent(session, event("unrelated", 3, Map.of())).blockingGet();
    assertThat(session.state().get("stable")).isSameInstanceAs(unchanged);
    assertThat(session.state().get("bytes")).isSameInstanceAs(bytes);
    assertThat(session.state().get("count")).isSameInstanceAs(count);
    assertThat(session.state()).containsEntry("app:gone", 1);
    assertThat(session.state()).containsEntry("app:pending", "waiting");
    assertThat(session.state()).containsEntry("new-key", "waiting");
    assertThat(load(service, session).state()).containsEntry("app:pending", "database");
    assertThat(load(service, session).state()).doesNotContainKey("new-key");
    assertThat(load(service, session).state()).doesNotContainKey("app:gone");
    assertThat(load(service, session).state()).containsEntry("bytes", "AQID");
    service.appendEvent(session, event("pending", 4, pendingDelta)).blockingGet();
    assertThat(load(service, session).state()).containsEntry("new-key", "waiting");
    assertThat(load(service, session).state()).containsEntry("app:pending", "waiting");
  }

  private void filters() {
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of());
    for (long time : new long[] {3000, 1000, 2000}) {
      service.appendEvent(session, event("e-" + time, time, Map.of())).blockingGet();
    }
    Session filtered =
        service
            .getSession(
                session.sessionKey(),
                GetSessionConfig.builder()
                    .numRecentEvents(2)
                    .afterTimestamp(Instant.ofEpochMilli(1500))
                    .build())
            .blockingGet();
    assertThat(filtered.immutableEvents().stream().map(Event::timestamp).toList())
        .containsExactly(2000L);
    assertThat(
            service
                .getSession(
                    session.sessionKey(),
                    GetSessionConfig.builder()
                        .afterTimestamp(Instant.ofEpochMilli(1000).plusNanos(1))
                        .build())
                .blockingGet()
                .immutableEvents()
                .stream()
                .map(Event::timestamp)
                .toList())
        .containsExactly(3000L, 2000L)
        .inOrder();
    assertThat(
            service
                .getSession(
                    session.sessionKey(), GetSessionConfig.builder().numRecentEvents(0).build())
                .blockingGet()
                .immutableEvents())
        .isEmpty();
    assertThat(
            service
                .listSessions(session.appName(), session.userId())
                .blockingGet()
                .sessions()
                .get(0)
                .immutableEvents())
        .isEmpty();
  }

  private void removal() {
    DatabaseSessionService service = service();
    Session session =
        create(
            service,
            app(),
            "u",
            "id",
            Map.of("app:k", 1, "user:k", 2, "a.b", 3, "map", Map.of("old", 1)));
    Event event =
        event(
            "remove",
            1,
            Map.of(
                "app:k",
                State.REMOVED,
                "user:k",
                State.REMOVED,
                "a.b",
                State.REMOVED,
                "map",
                Map.of("new", 2),
                "literal",
                "__ADK_SENTINEL_REMOVED__"));
    service.appendEvent(session, event).blockingGet();
    Session reloaded = load(service, session);
    assertThat(reloaded.state())
        .containsExactly("map", Map.of("new", 2), "literal", "__ADK_SENTINEL_REMOVED__");
    assertThat(reloaded.immutableEvents().get(0).actions().stateDelta().get("app:k"))
        .isSameInstanceAs(State.REMOVED);
    assertThat(reloaded.immutableEvents().get(0).actions().stateDelta().get("literal"))
        .isEqualTo("__ADK_SENTINEL_REMOVED__");
  }

  private void temp() {
    DatabaseSessionService service = service();
    Object initial = new Object();
    Session session = create(service, app(), "u", "id", Map.of("temp:initial", initial));
    assertThat(session.state().get("temp:initial")).isSameInstanceAs(initial);
    session.state().put("temp:caller", new Object());
    session.state().put("temp:dropped", new Object());
    // Values that no JSON encoder could write: they are handed over, never inspected.
    Object runtime = new Object();
    Event event =
        event(
            "e",
            1,
            Map.of("temp:runtime", runtime, "temp:dropped", State.REMOVED, "app:temp:literal", 2));
    assertThat(service.appendEvent(session, event).blockingGet()).isSameInstanceAs(event);
    assertThat(session.state()).containsKey("temp:caller");
    // As with InMemorySessionService, a later step of the same invocation can read the value.
    assertThat(session.state().get("temp:runtime")).isSameInstanceAs(runtime);
    assertThat(session.state()).doesNotContainKey("temp:dropped");
    assertThat(event.actions().stateDelta().get("temp:runtime")).isSameInstanceAs(runtime);
    Session loaded = load(service, session);
    assertThat(loaded.state()).containsExactly("app:temp:literal", 2);
    assertThat(loaded.immutableEvents().get(0).actions().stateDelta())
        .containsExactly("app:temp:literal", 2);
    // A failed append hands over nothing, temp values included.
    SessionException duplicate =
        assertThrows(
            SessionException.class,
            () ->
                service
                    .appendEvent(session, event("e", 2, Map.of("temp:rejected", new Object())))
                    .blockingGet());
    assertThat(duplicate).hasMessageThat().contains("already exists");
    assertThat(session.state()).doesNotContainKey("temp:rejected");
  }

  private void fullEvent() throws Exception {
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of());
    Event original = SessionDataCodecTest.fullEvent();
    service.appendEvent(session, original).blockingGet();
    Event reloaded = service().listEvents(session.sessionKey()).blockingGet().events().get(0);
    assertThat(JsonBaseModel.getMapper().readTree(reloaded.toJson()))
        .isEqualTo(JsonBaseModel.getMapper().readTree(original.toJson()));
    assertThat(reloaded.cacheMetadata()).isEqualTo(original.cacheMetadata());
  }

  private void partial() {
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of("k", 1));
    Event event =
        event("partial", 999, Map.of("app:wrong", new Object(), "k", 2)).toBuilder()
            .partial(true)
            .build();
    assertThat(service.appendEvent(session, event).blockingGet()).isSameInstanceAs(event);
    assertThat(session.lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(123).plusNanos(456_000));
    assertThat(session.state()).containsExactly("k", 1);
    assertThat(load(service, session).immutableEvents()).isEmpty();
  }

  private void eventColumns() throws Exception {
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of());
    Event absent = Event.builder().id("absent-invocation").timestamp(1).author("user").build();
    assertThat(absent.invocationId()).isNull();
    String maxInvocation = "😀".repeat(256);
    Event max =
        event("max-invocation", 2, Map.of()).toBuilder().invocationId(maxInvocation).build();
    service.appendEvent(session, absent).blockingGet();
    service.appendEvent(session, max).blockingGet();

    Event overlong =
        event("overlong", 3, Map.of("app:unexpected", true)).toBuilder()
            .invocationId(maxInvocation + "x")
            .build();
    SessionException failure =
        assertThrows(
            SessionException.class, () -> service.appendEvent(session, overlong).blockingGet());
    assertThat(failure).hasMessageThat().contains("at most 256 code points");
    Session restored = load(service, session);
    assertThat(restored.state()).isEmpty();
    assertThat(restored.immutableEvents()).hasSize(2);
    assertThat(restored.immutableEvents().get(0).invocationId()).isNull();
    assertThat(restored.immutableEvents().get(1).invocationId()).isEqualTo(maxInvocation);
    try (Connection connection = source.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT invocation_id FROM events WHERE app_name=? ORDER BY event_sequence")) {
      query.setString(1, session.appName());
      try (ResultSet rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEmpty();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(maxInvocation);
        assertThat(rows.next()).isFalse();
      }
      try (PreparedStatement update =
          connection.prepareStatement("UPDATE events SET event_data=NULL WHERE app_name=?")) {
        update.setString(1, session.appName());
        assertThat(update.executeUpdate()).isEqualTo(2);
      }
    }
    SessionException nullPayload =
        assertThrows(SessionException.class, () -> load(service, session));
    assertThat(nullPayload).hasMessageThat().isEqualTo("Unable to decode session event.");
    assertThat(nullPayload).hasCauseThat().isNull();
  }

  private void stale() {
    DatabaseSessionService service = service();
    Session first = create(service, app(), "u", "id", Map.of());
    Session old =
        service
            .getSession(first.sessionKey(), GetSessionConfig.builder().numRecentEvents(0).build())
            .blockingGet();
    service.appendEvent(first, event("first", 123, Map.of())).blockingGet();
    String before = old.toJson();
    SessionException error =
        assertThrows(
            SessionException.class,
            () ->
                service()
                    .appendEvent(old, event("stale", 123, Map.of("app:wrong", 1)))
                    .blockingGet());
    assertThat(error).hasMessageThat().contains("stale");
    assertThat(old.toJson()).isEqualTo(before);
    assertThat(load(service, first).state()).isEmpty();
    assertThat(load(service, first).immutableEvents()).hasSize(1);
  }

  private void filteredAppend() {
    DatabaseSessionService service = service();
    Session original = create(service, app(), "u", "id", Map.of());
    service.appendEvent(original, event("newer-time", 20, Map.of())).blockingGet();
    service.appendEvent(original, event("actual-tail", 10, Map.of())).blockingGet();
    Session missingTail =
        service
            .getSession(
                original.sessionKey(),
                GetSessionConfig.builder().afterTimestamp(Instant.ofEpochMilli(20)).build())
            .blockingGet();
    assertThat(missingTail.immutableEvents().stream().map(Event::id).toList())
        .containsExactly("newer-time");
    SessionException error =
        assertThrows(
            SessionException.class,
            () -> service.appendEvent(missingTail, event("stale", 10, Map.of())).blockingGet());
    assertThat(error).hasMessageThat().contains("reload");
    Session tail =
        service
            .getSession(
                original.sessionKey(), GetSessionConfig.builder().numRecentEvents(1).build())
            .blockingGet();
    service.appendEvent(tail, event("accepted", 10, Map.of())).blockingGet();
    assertThat(load(service, original).immutableEvents().stream().map(Event::id).toList())
        .containsExactly("newer-time", "actual-tail", "accepted")
        .inOrder();
  }

  private void recreatedTail() {
    DatabaseSessionService service = service();
    for (boolean reusedId : List.of(false, true)) {
      Session old = create(service, app(), "u", "id", Map.of());
      if (reusedId) {
        service.appendEvent(old, event("reused", 1, Map.of())).blockingGet();
      }
      service.deleteSession(old.sessionKey()).blockingAwait();
      Session recreated = create(service, old.appName(), "u", "id", Map.of());
      if (reusedId) {
        service.appendEvent(recreated, event("reused", 2, Map.of())).blockingGet();
      }
      // A matching tail alone cannot distinguish these two incarnations.
      service.appendEvent(old, event("from-old", 3, Map.of())).blockingGet();
      List<Long> times =
          load(service, recreated).immutableEvents().stream().map(Event::timestamp).toList();
      assertThat(times)
          .containsExactlyElementsIn(reusedId ? List.of(2L, 3L) : List.of(3L))
          .inOrder();
    }
  }

  private void detachedAppend() {
    DatabaseSessionService service = service();
    Session original = create(service, app(), "u", "id", Map.of());
    Session manual = Session.builder("id").appName(original.appName()).userId("u").build();
    service.appendEvent(manual, event("manual-empty", 1, Map.of())).blockingGet();
    original = load(service, original);
    Session copy = Session.fromJson(original.toJson());
    service().appendEvent(copy, event("json", 2, Map.of())).blockingGet();
    // Copy public fields as the Kotlin bridge does; no JDBC-only fields are needed.
    Session builderCopy =
        Session.builder(copy.id())
            .appName(copy.appName())
            .userId(copy.userId())
            .state(Map.copyOf(copy.state()))
            .events(copy.immutableEvents())
            .lastUpdateTime(copy.lastUpdateTime())
            .build();
    service.appendEvent(builderCopy, event("builder", 3, Map.of())).blockingGet();
    Session missingHistory = Session.builder("id").appName(copy.appName()).userId("u").build();
    SessionException error =
        assertThrows(
            SessionException.class,
            () ->
                service.appendEvent(missingHistory, event("untracked", 4, Map.of())).blockingGet());
    assertThat(error).hasMessageThat().contains("reload");
    assertThat(load(service, original).immutableEvents().stream().map(Event::id).toList())
        .containsExactly("manual-empty", "json", "builder")
        .inOrder();
  }

  private void keys() {
    DatabaseSessionService service = service();
    String app = app();
    List<String> keys = List.of("a", "A", "á", "a ", "é", "e\u0301", "雪😀", "😀".repeat(128));
    for (String id : keys) {
      Session session = create(service, app, "u", id, Map.of("id", id));
      assertThat(load(service, session).state()).containsExactly("id", id);
    }
    assertThat(service.listSessions(app, "u").blockingGet().sessionIds())
        .containsExactlyElementsIn(keys);
    Session distinctEvents = create(service, app, "u", "events", Map.of());
    for (String id : keys) {
      service.appendEvent(distinctEvents, event(id, 1, Map.of())).blockingGet();
    }
    assertThat(load(service, distinctEvents).immutableEvents().stream().map(Event::id).toList())
        .containsExactlyElementsIn(keys)
        .inOrder();
    Session maximal =
        create(service, "😀".repeat(128), "😀".repeat(128), "😀".repeat(128), Map.of());
    service.appendEvent(maximal, event("😀".repeat(128), 1, Map.of())).blockingGet();
    assertThat(load(service, maximal).immutableEvents().get(0).id()).isEqualTo("😀".repeat(128));
  }

  private void invalidKeys() {
    DatabaseSessionService service = service();
    for (String invalid :
        new String[] {"private-value".repeat(20), "private\0value", "\ud800", "\udc00"}) {
      SessionException error =
          assertThrows(
              SessionException.class, () -> create(service, app(), "u", invalid, Map.of()));
      assertThat(error).hasCauseThat().isNull();
      assertThat(error).hasMessageThat().doesNotContain("private");
    }
  }

  private void largeJson() {
    DatabaseSessionService service = service();
    String payload = "雪😀".repeat(20_000);
    BigInteger large = new BigInteger("123456789012345678901234567890");
    Session session = create(service, app(), "u", "id", Map.of("payload", payload, "large", large));
    service.appendEvent(session, event("large", 1, Map.of("app:payload", payload))).blockingGet();
    assertThat(load(service, session).state())
        .containsExactly("payload", payload, "app:payload", payload, "large", large);
  }

  private void timeRange() {
    Instant earliest = Instant.parse("1000-01-01T00:00:00Z");
    Instant latest = Instant.parse("9999-12-31T23:59:59.999999Z");
    for (Instant created : List.of(earliest, Instant.ofEpochMilli(-1), Instant.EPOCH, latest)) {
      DatabaseSessionService service =
          DatabaseSessionService.builder(source)
              .dialect(dialect)
              .instantSource(InstantSource.fixed(created))
              .build();
      Session session = create(service, app(), "u", "id", Map.of());
      assertThat(session.lastUpdateTime()).isEqualTo(created);
      long time = created.toEpochMilli();
      service.appendEvent(session, event("extreme", time, Map.of())).blockingGet();
      assertThat(load(service, session).lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(time));
      assertThat(load(service, session).immutableEvents().get(0).timestamp()).isEqualTo(time);
      assertThat(
              service
                  .getSession(
                      session.sessionKey(),
                      GetSessionConfig.builder().afterTimestamp(Instant.ofEpochMilli(time)).build())
                  .blockingGet()
                  .immutableEvents())
          .hasSize(1);
      assertThat(
              service
                  .getSession(
                      session.sessionKey(),
                      GetSessionConfig.builder()
                          .afterTimestamp(Instant.ofEpochMilli(time).plusNanos(1))
                          .build())
                  .blockingGet()
                  .immutableEvents())
          .isEmpty();
    }
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of());
    for (Instant outside : List.of(earliest.minusMillis(1), latest.plusNanos(1_000))) {
      DatabaseSessionService invalidClock =
          DatabaseSessionService.builder(source)
              .dialect(dialect)
              .instantSource(InstantSource.fixed(outside))
              .build();
      SessionException createFailure =
          assertThrows(
              SessionException.class, () -> create(invalidClock, app(), "u", "id", Map.of()));
      assertThat(createFailure).hasMessageThat().contains("1000 through 9999");
      SessionException appendFailure =
          assertThrows(
              SessionException.class,
              () ->
                  service
                      .appendEvent(session, event("invalid", outside.toEpochMilli(), Map.of()))
                      .blockingGet());
      assertThat(appendFailure).hasMessageThat().contains("1000 through 9999");
    }
    assertThat(load(service, session).immutableEvents()).isEmpty();
  }

  private void jsonValues() {
    DatabaseSessionService service = service();
    Map<String, Object> values =
        Map.of("negativeZero", -0.0, "exponent", 1e23, "escapedNul", "before\0after");
    Session session = create(service, app(), "u", "id", values);
    service.appendEvent(session, event("values", 1, values)).blockingGet();
    Session restored = load(service(), session);
    assertThat(restored.state()).containsExactlyEntriesIn(values);
    assertThat(restored.immutableEvents().get(0).actions().stateDelta())
        .containsExactlyEntriesIn(values);
  }

  private void runner() {
    TestLlm llm =
        new TestLlm(
            List.of(
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            .role("model")
                            .parts(Part.fromText("first answer"))
                            .build())
                    .build(),
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            .role("model")
                            .parts(Part.fromText("second answer"))
                            .build())
                    .build()));
    LlmAgent agent = LlmAgent.builder().name("test_agent").model(llm).build();
    DatabaseSessionService first = service();
    Session session = create(first, app(), "u", "id", Map.of("retained", true));
    Runner.builder()
        .appName(session.appName())
        .agent(agent)
        .sessionService(first)
        .build()
        .runAsync("u", "id", Content.fromParts(Part.fromText("first question")))
        .toList()
        .blockingGet();
    assertThat(load(service(), session).immutableEvents()).hasSize(2);
    DatabaseSessionService restarted = service();
    Runner.builder()
        .appName(session.appName())
        .agent(agent)
        .sessionService(restarted)
        .build()
        .runAsync("u", "id", Content.fromParts(Part.fromText("second question")))
        .toList()
        .blockingGet();
    assertThat(load(service(), session).immutableEvents()).hasSize(4);
    assertThat(load(service(), session).state()).containsEntry("retained", true);
    assertThat(llm.getRequests()).hasSize(2);
    assertThat(llm.getLastRequest().contents()).hasSize(3);
  }

  private void concurrentStale() throws Exception {
    for (int iteration = 0; iteration < 5; iteration++) {
      DatabaseSessionService service = service();
      Session first = create(service, app(), "u", "id", Map.of());
      Session second = load(service(), first);
      List<Boolean> outcomes =
          together(
              () -> appendOutcome(service(), first, "a"),
              () -> appendOutcome(service(), second, "b"));
      assertThat(outcomes).containsExactly(true, false);
      assertThat(load(service, first).immutableEvents()).hasSize(1);
      assertThat(first.immutableEvents().size() + second.immutableEvents().size()).isEqualTo(1);
    }
  }

  private void concurrentShared() throws Exception {
    for (int iteration = 0; iteration < 5; iteration++) {
      DatabaseSessionService service = service();
      String app = app();
      Session a = create(service, app, "u", "a", Map.of());
      Session b = create(service, app, "u", "b", Map.of());
      together(
          () ->
              service()
                  .appendEvent(a, event("a", 1, Map.of("app:a", 1, "user:a", 2)))
                  .blockingGet(),
          () ->
              service()
                  .appendEvent(b, event("b", 1, Map.of("app:b", 3, "user:b", 4)))
                  .blockingGet());
      assertThat(load(service, a).state())
          .containsExactly("app:a", 1, "user:a", 2, "app:b", 3, "user:b", 4);
    }
  }

  private void concurrentCreate() throws Exception {
    for (int iteration = 0; iteration < 5; iteration++) {
      String app = app();
      List<Session> sessions =
          together(
              () -> create(service(), app, "u", "a", Map.of("app:a", 1, "user:a", 2)),
              () -> create(service(), app, "u", "b", Map.of("app:b", 3, "user:b", 4)));
      assertThat(load(service(), sessions.get(0)).state())
          .containsExactly("app:a", 1, "user:a", 2, "app:b", 3, "user:b", 4);
    }
  }

  private void concurrentDelete() throws Exception {
    DatabaseSessionService service = service();
    Session session = create(service, app(), "u", "id", Map.of());
    together(
        () -> {
          service().deleteSession(session.sessionKey()).blockingAwait();
          return true;
        },
        () -> {
          try {
            service().appendEvent(session, event("race", 1, Map.of())).blockingGet();
          } catch (SessionNotFoundException expected) {
            assertThat(session.immutableEvents()).isEmpty();
          }
          return true;
        });
    assertThat(service.getSession(session.sessionKey(), ALL).blockingGet()).isNull();
    Session recreated = create(service, session.appName(), "u", "id", Map.of());
    assertThat(load(service, recreated).immutableEvents()).isEmpty();
  }

  private void concurrentDuplicate() throws Exception {
    for (int iteration = 0; iteration < 5; iteration++) {
      String app = app();
      List<Boolean> outcomes = together(() -> createOutcome(app), () -> createOutcome(app));
      assertThat(outcomes).containsExactly(true, false);
      assertThat(service().listSessions(app, "u").blockingGet().sessions()).hasSize(1);
    }
  }

  private boolean createOutcome(String app) {
    try {
      create(service(), app, "u", "same-id", Map.of());
      return true;
    } catch (SessionException duplicate) {
      assertThat(duplicate).hasMessageThat().isEqualTo(SessionException.SESSION_ALREADY_EXISTS);
      return false;
    }
  }

  private static boolean appendOutcome(DatabaseSessionService service, Session session, String id) {
    try {
      service.appendEvent(session, event(id, 123, Map.of("local", id))).blockingGet();
      return true;
    } catch (SessionException stale) {
      assertThat(stale).hasMessageThat().contains("stale");
      return false;
    }
  }

  static <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<T> a =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("Start barrier timed out");
                return first.call();
              });
      Future<T> b =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("Start barrier timed out");
                return second.call();
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static String app() {
    return "test-" + UUID.randomUUID();
  }

  private static Session create(
      DatabaseSessionService service,
      String app,
      String user,
      String id,
      Map<String, Object> state) {
    return service.createSession(app, user, state, id).blockingGet();
  }

  private static Session load(DatabaseSessionService service, Session session) {
    return service.getSession(session.sessionKey(), ALL).blockingGet();
  }

  static Event event(String id, long timestamp, Map<String, Object> delta) {
    return Event.builder()
        .id(id)
        .invocationId("invocation")
        .author("agent")
        .timestamp(timestamp)
        .actions(EventActions.builder().stateDelta(delta).build())
        .build();
  }
}
