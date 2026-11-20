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

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * The session behavior that {@link InMemorySessionService}, the reference implementation in core,
 * and the JDBC service have in common. The same checks run against the reference itself and against
 * every database, so a divergence shows up as a failure rather than in production.
 *
 * <p>Where the two deliberately differ (missing sessions, stale writers, duplicate event IDs, what
 * survives a reload), the JDBC behavior is in {@link DatabaseSessionContract} and the README.
 */
final class SessionServiceContract {
  private static final GetSessionConfig ALL = GetSessionConfig.builder().build();
  private final Supplier<? extends BaseSessionService> services;

  /**
   * @param services a service over the same storage on every call; it may be the same instance
   */
  SessionServiceContract(Supplier<? extends BaseSessionService> services) {
    this.services = services;
  }

  Stream<DynamicTest> tests() {
    return Stream.of(
        dynamicTest("shared: create, get, list and delete", this::lifecycle),
        dynamicTest("shared: a blank session ID asks for a generated one", this::identifiers),
        dynamicTest("shared: a duplicate session ID is rejected", this::duplicate),
        dynamicTest("shared: sessions are scoped by app, user and ID", this::scope),
        dynamicTest("shared: history keeps append order, not time order", this::order),
        dynamicTest("shared: the last N events, then the inclusive time cursor", this::filters),
        dynamicTest("shared: a delta reaches the app, user and session scopes", this::scopes),
        dynamicTest("shared: a removal empties every scope", this::removal),
        dynamicTest("shared: temp values reach the supplied session", this::temp),
        dynamicTest("shared: a partial event is not stored", this::partial));
  }

  private void lifecycle() {
    BaseSessionService service = services.get();
    String app = app();
    Session created = service.createSession(app, "user").blockingGet();
    assertThat(created.id()).isNotEmpty();
    assertThat(created.appName()).isEqualTo(app);
    assertThat(created.userId()).isEqualTo("user");
    assertThat(created.state()).isEmpty();
    assertThat(created.immutableEvents()).isEmpty();
    assertThat(load(created).id()).isEqualTo(created.id());
    assertThat(service.listSessions(app, "user").blockingGet().sessionIds())
        .containsExactly(created.id());

    service.deleteSession(created.sessionKey()).blockingAwait();

    assertThat(services.get().getSession(created.sessionKey(), ALL).blockingGet()).isNull();
    assertThat(service.listSessions(app, "user").blockingGet().sessions()).isEmpty();
  }

  private void identifiers() {
    BaseSessionService service = services.get();
    String app = app();
    Session generated = service.createSession(app, "user", Map.of(), "   ").blockingGet();
    assertThat(generated.id().trim()).isNotEmpty();
    assertThat(create(app, "user", "given", Map.of()).id()).isEqualTo("given");
    assertThat(service.listSessions(app, "user").blockingGet().sessionIds())
        .containsExactly(generated.id(), "given");
  }

  private void duplicate() {
    String app = app();
    create(app, "user", "id", Map.of("local", "original"));
    SessionException error =
        assertThrows(
            SessionException.class, () -> create(app, "user", "id", Map.of("local", "second")));
    assertThat(error).hasMessageThat().isEqualTo(SessionException.SESSION_ALREADY_EXISTS);
    assertThat(load(app, "user", "id").state()).containsExactly("local", "original");
  }

  private void scope() {
    BaseSessionService service = services.get();
    String app = app();
    Session a = create(app, "user", "id", Map.of("local", 1));
    Session b = create(app, "other-user", "id", Map.of("local", 2));
    Session c = create(app(), "user", "id", Map.of("local", 3));
    assertThat(load(a).state()).containsExactly("local", 1);

    service.deleteSession(a.sessionKey()).blockingAwait();

    assertThat(load(b).state()).containsExactly("local", 2);
    assertThat(load(c).state()).containsExactly("local", 3);
    assertThat(service.listSessions(app, "user").blockingGet().sessions()).isEmpty();
  }

  private void order() {
    BaseSessionService service = services.get();
    Session session = create(app(), "user", "id", Map.of());
    int index = 0;
    for (long time : new long[] {3000, 1000, 1000, 2000}) {
      service.appendEvent(session, event("e-" + index++, time, Map.of())).blockingGet();
    }
    List<String> appended = List.of("e-0", "e-1", "e-2", "e-3");
    assertThat(ids(session.immutableEvents())).isEqualTo(appended);
    assertThat(ids(load(session).immutableEvents())).isEqualTo(appended);
    assertThat(ids(service.listEvents(session.sessionKey()).blockingGet().events()))
        .isEqualTo(appended);
    assertThat(session.lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(2000));
    assertThat(load(session).lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(2000));
  }

  private void filters() {
    BaseSessionService service = services.get();
    Session session = create(app(), "user", "id", Map.of());
    for (long time : new long[] {3000, 1000, 2000}) {
      service.appendEvent(session, event("e-" + time, time, Map.of())).blockingGet();
    }
    assertThat(
            times(
                GetSessionConfig.builder()
                    .numRecentEvents(2)
                    .afterTimestamp(Instant.ofEpochMilli(1500))
                    .build(),
                session))
        .containsExactly(2000L);
    assertThat(
            times(
                GetSessionConfig.builder().afterTimestamp(Instant.ofEpochMilli(1000)).build(),
                session))
        .containsExactly(3000L, 1000L, 2000L)
        .inOrder();
    assertThat(times(GetSessionConfig.builder().numRecentEvents(10).build(), session))
        .containsExactly(3000L, 1000L, 2000L)
        .inOrder();
    assertThat(times(GetSessionConfig.builder().numRecentEvents(0).build(), session)).isEmpty();
    assertThat(
            service
                .listSessions(session.appName(), "user")
                .blockingGet()
                .sessions()
                .get(0)
                .immutableEvents())
        .isEmpty();
  }

  private void scopes() {
    String app = app();
    Session a = create(app, "user", "a", Map.of());
    Session b = create(app, "user", "b", Map.of());
    Session c = create(app, "other-user", "c", Map.of());

    services
        .get()
        .appendEvent(a, event("e", 1, Map.of("app:k", 1, "user:k", 2, "local", 3)))
        .blockingGet();

    assertThat(a.state()).containsExactly("app:k", 1, "user:k", 2, "local", 3);
    assertThat(load(a).state()).containsExactly("app:k", 1, "user:k", 2, "local", 3);
    assertThat(load(b).state()).containsExactly("app:k", 1, "user:k", 2);
    assertThat(load(c).state()).containsExactly("app:k", 1);
  }

  private void removal() {
    BaseSessionService service = services.get();
    String app = app();
    Session a = create(app, "user", "a", Map.of());
    Session b = create(app, "user", "b", Map.of());
    service
        .appendEvent(a, event("set", 1, Map.of("app:k", 1, "user:k", 2, "local", 3, "kept", 4)))
        .blockingGet();

    service
        .appendEvent(
            a,
            event(
                "remove",
                2,
                Map.of("app:k", State.REMOVED, "user:k", State.REMOVED, "local", State.REMOVED)))
        .blockingGet();

    assertThat(a.state()).containsExactly("kept", 4);
    assertThat(load(a).state()).containsExactly("kept", 4);
    assertThat(load(b).state()).isEmpty();
  }

  private void temp() {
    Session session = create(app(), "user", "id", Map.of());
    // Not something a JSON encoder could write: temp values are handed over as they are.
    Object runtime = new Object();

    services
        .get()
        .appendEvent(session, event("e", 1, Map.of("temp:runtime", runtime, "local", 1)))
        .blockingGet();

    assertThat(session.state().get("temp:runtime")).isSameInstanceAs(runtime);
    assertThat(session.state()).containsEntry("local", 1);
  }

  private void partial() {
    BaseSessionService service = services.get();
    Session session = create(app(), "user", "id", Map.of());
    Event partial = event("partial", 5, Map.of()).toBuilder().partial(true).build();

    assertThat(service.appendEvent(session, partial).blockingGet()).isSameInstanceAs(partial);

    assertThat(session.immutableEvents()).isEmpty();
    assertThat(load(session).immutableEvents()).isEmpty();
    service.appendEvent(session, event("complete", 6, Map.of())).blockingGet();
    assertThat(ids(load(session).immutableEvents())).containsExactly("complete");
  }

  private Session create(String app, String user, String id, Map<String, Object> state) {
    return services.get().createSession(app, user, state, id).blockingGet();
  }

  private Session load(Session session) {
    return load(session.appName(), session.userId(), session.id());
  }

  private Session load(String app, String user, String id) {
    return services.get().getSession(new SessionKey(app, user, id), ALL).blockingGet();
  }

  private List<Long> times(GetSessionConfig config, Session session) {
    return services
        .get()
        .getSession(session.sessionKey(), config)
        .blockingGet()
        .immutableEvents()
        .stream()
        .map(Event::timestamp)
        .toList();
  }

  private static List<String> ids(List<Event> events) {
    return events.stream().map(Event::id).toList();
  }

  private static String app() {
    return "shared-" + UUID.randomUUID();
  }

  private static Event event(String id, long timestamp, Map<String, Object> delta) {
    return Event.builder()
        .id(id)
        .invocationId("invocation")
        .author("agent")
        .timestamp(timestamp)
        .actions(EventActions.builder().stateDelta(delta).build())
        .build();
  }
}
