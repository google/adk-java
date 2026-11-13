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
import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DynamicTest;

/** Same transactional failure and query-shape checks on every actual JDBC engine. */
final class DatabaseSessionJdbcContract {
  private final DataSource source;
  private final Dialect dialect;

  DatabaseSessionJdbcContract(DataSource source, Dialect dialect) {
    this.source = source;
    this.dialect = dialect;
  }

  Stream<DynamicTest> tests() {
    Stream<DynamicTest> common =
        Stream.of(
            dynamicTest("cold API and injected providers", this::cold),
            dynamicTest("SQL and pre-commit failure rolls back everything", this::rollback),
            dynamicTest("uncertain commit is never blindly retried", this::uncertainCommit),
            dynamicTest(
                "confirmed commit is distinguished from cleanup failure",
                this::confirmedCommitCleanup),
            dynamicTest(
                "publication preserves unrelated writes during commit",
                this::liveWriteDuringCommit),
            dynamicTest("local append takes no shared-row write lock", this::localQueries),
            dynamicTest(
                "append without a local delta leaves the state document untouched",
                this::untouchedLocalState),
            dynamicTest("listing has no N+1 queries", this::listing),
            dynamicTest("a conflict reported by the database is safe to repeat", this::conflict),
            dynamicTest(
                "the configured timeout reaches every prepared statement", this::statementTimeout),
            dynamicTest("state and history share a consistent read snapshot", this::snapshot),
            dynamicTest("connections restored on reads writes and errors", this::connectionState));
    if (dialect == Dialect.SQLITE) {
      return common;
    }
    Stream<DynamicTest> server =
        Stream.concat(
            common,
            Stream.of(
                dynamicTest(
                    "unrelated app row lock does not block local append", this::unrelatedLock)));
    return dialect == Dialect.MYSQL || dialect == Dialect.MARIADB
        ? Stream.concat(
            server,
            Stream.of(
                dynamicTest("existing shared rows do not serialize create", this::createLock)))
        : server;
  }

  private DatabaseSessionService service(DataSource source) {
    return DatabaseSessionService.builder(source).dialect(dialect).build();
  }

  private Session create(DatabaseSessionService service) {
    return service
        .createSession(
            "jdbc-" + UUID.randomUUID(), "u", Map.of("app:original", 1, "user:original", 2), "id")
        .blockingGet();
  }

  private Session load(Session session) {
    return service(source).getSession(session.sessionKey(), null).blockingGet();
  }

  private void cold() {
    JdbcProbe probe = new JdbcProbe(source, dialect);
    AtomicInteger ids = new AtomicInteger();
    AtomicInteger times = new AtomicInteger();
    DatabaseSessionService service =
        DatabaseSessionService.builder(probe)
            .dialect(dialect)
            .uuidProvider(() -> "identity-" + ids.incrementAndGet())
            .instantSource(
                () -> {
                  times.incrementAndGet();
                  return Instant.ofEpochMilli(123);
                })
            .build();
    String app = "cold-" + UUID.randomUUID();
    var create = service.createSession(app, "u", Map.of(), "id");
    var get = service.getSession(app, "u", "id", Optional.empty());
    var list = service.listSessions(app, "u");
    var events = service.listEvents(app, "u", "id");
    var delete = service.deleteSession(app, "u", "id");
    var invalid = service.appendEvent(null, null);
    assertThat(probe.borrowed.get()).isEqualTo(0);
    assertThat(ids.get()).isEqualTo(0);
    assertThat(times.get()).isEqualTo(0);
    create.blockingGet();
    get.blockingGet();
    list.blockingGet();
    events.blockingGet();
    delete.blockingAwait();
    assertThrows(SessionException.class, invalid::blockingGet);
    assertThat(ids.get()).isEqualTo(0);
    assertThat(times.get()).isEqualTo(1);
    assertThat(probe.closed.get()).isEqualTo(probe.borrowed.get());
  }

  private void rollback() {
    for (String point : List.of("UPDATE sessions", "INSERT INTO events", "COMMIT")) {
      Session session = create(service(source));
      String before = session.toJson();
      JdbcProbe probe = new JdbcProbe(source, dialect);
      AtomicBoolean armed = new AtomicBoolean(true);
      probe.hook =
          (sql, after) -> {
            if (!after && sql.startsWith(point) && armed.compareAndSet(true, false))
              throw new SQLException("private-value");
          };
      SessionException failure =
          assertThrows(
              SessionException.class,
              () ->
                  service(probe)
                      .appendEvent(
                          session,
                          DatabaseSessionContract.event(
                              "failure", 1, Map.of("app:wrong", 3, "user:wrong", 4, "local", 5)))
                      .blockingGet());
      assertThat(failure).hasCauseThat().isNull();
      assertThat(failure.getSuppressed()).isEmpty();
      assertThat(failure).hasMessageThat().doesNotContain("private");
      assertThat(session.toJson()).isEqualTo(before);
      Session loaded = load(session);
      assertThat(loaded.immutableEvents()).isEmpty();
      assertThat(loaded.state()).containsExactly("app:original", 1, "user:original", 2);
      assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
    }
  }

  private void uncertainCommit() {
    Session session = create(service(source));
    String before = session.toJson();
    JdbcProbe probe = new JdbcProbe(source, dialect);
    AtomicBoolean armed = new AtomicBoolean(true);
    probe.hook =
        (sql, after) -> {
          if (after && sql.equals("COMMIT") && armed.compareAndSet(true, false))
            throw new SQLException("private-value after server commit");
        };
    SessionException uncertain =
        assertThrows(
            SessionException.class,
            () ->
                service(probe)
                    .appendEvent(
                        session,
                        DatabaseSessionContract.event("committed", 1, Map.of("local", true)))
                    .blockingGet());
    assertThat(uncertain).hasMessageThat().contains("may have committed");
    assertThat(uncertain).hasMessageThat().doesNotContain("private-value");
    assertThat(uncertain).hasCauseThat().isNull();
    assertThat(probe.sql.stream().filter(sql -> sql.equals("COMMIT")).count()).isEqualTo(1);
    assertThat(session.toJson()).isEqualTo(before);
    assertThat(load(session).immutableEvents()).hasSize(1);
    assertThrows(
        SessionException.class,
        () ->
            service(source)
                .appendEvent(session, DatabaseSessionContract.event("unsafe-retry", 2, Map.of()))
                .blockingGet());
    assertThat(load(session).immutableEvents()).hasSize(1);
    assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
  }

  private void localQueries() {
    Session session = create(service(source));
    JdbcProbe probe = new JdbcProbe(source, dialect);
    service(probe)
        .appendEvent(session, DatabaseSessionContract.event("local", 1, Map.of("local", 1)))
        .blockingGet();
    assertThat(
            probe.sql.stream()
                .noneMatch(
                    sql ->
                        (sql.contains("app_states") || sql.contains("user_states"))
                            && (sql.startsWith("UPDATE")
                                || sql.startsWith("INSERT")
                                || sql.contains("FOR UPDATE"))))
        .isTrue();
    assertThat(probe.sql.stream().filter(sql -> sql.startsWith("SELECT s.id")).count())
        .isEqualTo(0);
    assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
  }

  private void untouchedLocalState() throws Exception {
    Session session = create(service(source));
    // Valid JSON that any decode/encode cycle would rewrite in a normalized form.
    String stored = "{ \"kept\" : 1.50 }";
    try (Connection connection = source.getConnection();
        var update = connection.prepareStatement("UPDATE sessions SET state=? WHERE app_name=?")) {
      DatabaseDialect.bindJson(update, 1, stored, dialect);
      update.setString(2, session.appName());
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
    JdbcProbe probe = new JdbcProbe(source, dialect);
    service(probe)
        .appendEvent(
            session, DatabaseSessionContract.event("shared-only", 5, Map.of("app:shared", 1)))
        .blockingGet();
    assertThat(
            probe.sql.stream()
                .noneMatch(sql -> sql.contains(" sessions ") && sql.contains("state")))
        .isTrue();
    assertThat(storedState(session)).isEqualTo(stored);
    assertThat(load(session).lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(5));
    assertThat(load(session).state()).containsEntry("app:shared", 1);
    service(source)
        .appendEvent(session, DatabaseSessionContract.event("local", 6, Map.of("local", 1)))
        .blockingGet();
    assertThat(load(session).state()).containsAtLeast("kept", 1.5, "local", 1);
    assertThat(storedState(session)).isNotEqualTo(stored);
    assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
  }

  private String storedState(Session session) throws Exception {
    try (Connection connection = source.getConnection();
        var query = connection.prepareStatement("SELECT state FROM sessions WHERE app_name=?")) {
      query.setString(1, session.appName());
      try (var rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private void conflict() {
    Session session = create(service(source));
    String before = session.toJson();
    Event event = DatabaseSessionContract.event("victim", 1, Map.of("app:k", 3, "local", 5));
    JdbcProbe probe = new JdbcProbe(source, dialect);
    AtomicBoolean armed = new AtomicBoolean(true);
    probe.hook =
        (sql, after) -> {
          if (!after && sql.startsWith("INSERT INTO events") && armed.compareAndSet(true, false))
            throw new SQLException("private-deadlock", "40001", 1213);
        };
    SessionException failure =
        assertThrows(
            SessionException.class, () -> service(probe).appendEvent(session, event).blockingGet());
    assertThat(failure).hasMessageThat().isEqualTo(DatabaseSessionStore.CONFLICT);
    assertThat(failure).hasCauseThat().isNull();
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(session.toJson()).isEqualTo(before);
    assertThat(load(session).immutableEvents()).isEmpty();
    assertThat(load(session).state()).doesNotContainKey("app:k");

    // The very same call, repeated without reloading anything.
    service(probe).appendEvent(session, event).blockingGet();
    assertThat(load(session).immutableEvents().stream().map(Event::id).toList())
        .containsExactly("victim");
    assertThat(load(session).state()).containsAtLeast("app:k", 3, "local", 5);

    // The same code from the commit itself says nothing about whether the write was applied.
    armed.set(true);
    probe.hook =
        (sql, after) -> {
          if (after && sql.equals("COMMIT") && armed.compareAndSet(true, false))
            throw new SQLException("private-deadlock", "40001", 1213);
        };
    SessionException uncertain =
        assertThrows(
            SessionException.class,
            () ->
                service(probe)
                    .appendEvent(session, DatabaseSessionContract.event("unknown", 2, Map.of()))
                    .blockingGet());
    assertThat(uncertain).hasMessageThat().contains("may have committed");
    assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
  }

  private void statementTimeout() {
    JdbcProbe configured = new JdbcProbe(source, dialect);
    DatabaseSessionService service =
        DatabaseSessionService.builder(configured)
            .dialect(dialect)
            .statementTimeout(Duration.ofSeconds(7))
            .build();
    Session session = create(service);
    service
        .appendEvent(
            session, DatabaseSessionContract.event("timed", 1, Map.of("app:k", 1, "local", 2)))
        .blockingGet();
    service.getSession(session.sessionKey(), null).blockingGet();
    service.listSessions(session.appName(), "u").blockingGet();
    service.listEvents(session.sessionKey()).blockingGet();
    service.deleteSession(session.sessionKey()).blockingAwait();
    assertThat(configured.timeouts.stream().distinct().toList()).containsExactly(7);
    if (dialect == Dialect.SQLITE) {
      assertThat(configured.sql).contains("PRAGMA busy_timeout=7000");
    }

    JdbcProbe standard = new JdbcProbe(source, dialect);
    create(service(standard));
    assertThat(standard.timeouts.stream().distinct().toList()).containsExactly(15);
    if (dialect == Dialect.SQLITE) {
      assertThat(standard.sql).contains("PRAGMA busy_timeout=15000");
    }
    assertThat(configured.borrowed.get()).isEqualTo(configured.closed.get());
    assertThat(standard.borrowed.get()).isEqualTo(standard.closed.get());
  }

  private void confirmedCommitCleanup() {
    List<String> faultPoints =
        dialect == Dialect.SQLITE
            ? List.of("CLOSE CONNECTION", "CLOSE COMMIT STATEMENT")
            : List.of("CLOSE CONNECTION");
    for (String faultPoint : faultPoints) {
      Session session = create(service(source));
      String before = session.toJson();
      JdbcProbe probe = new JdbcProbe(source, dialect);
      probe.hook =
          (sql, after) -> {
            if (after && sql.equals(faultPoint)) {
              throw new SQLException("private-confirmed-cleanup");
            }
          };
      SessionException failure =
          assertThrows(
              SessionException.class,
              () ->
                  service(probe)
                      .appendEvent(
                          session,
                          DatabaseSessionContract.event("acknowledged", 1, Map.of("local", true)))
                      .blockingGet());
      assertThat(failure)
          .hasMessageThat()
          .isEqualTo("The database write committed, but cleanup failed; reload the session.");
      assertThat(failure).hasCauseThat().isNull();
      assertThat(failure.getSuppressed()).isEmpty();
      assertThat(probe.sql).doesNotContain("ROLLBACK");
      assertThat(session.toJson()).isEqualTo(before);
      Session reloaded = load(session);
      assertThat(reloaded.state()).containsEntry("local", true);
      assertThat(reloaded.immutableEvents().stream().map(Event::id).toList())
          .containsExactly("acknowledged");
      assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
    }
  }

  private void liveWriteDuringCommit() throws Exception {
    Session session =
        service(source)
            .createSession(
                "publish-" + UUID.randomUUID(), "u", Map.of("unrelated", Map.of("k", 1)), "id")
            .blockingGet();
    var executor = Executors.newSingleThreadExecutor();
    CountDownLatch committed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    JdbcProbe probe = new JdbcProbe(source, dialect);
    probe.hook =
        (sql, after) -> {
          if (after && sql.equals("COMMIT")) {
            committed.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
              throw new AssertionError("Publication barrier timed out");
            }
          }
        };
    try {
      var append =
          executor.submit(
              () ->
                  service(probe)
                      .appendEvent(
                          session,
                          DatabaseSessionContract.event("stored", 1, Map.of("value", "new")))
                      .blockingGet());
      assertThat(committed.await(20, TimeUnit.SECONDS)).isTrue();
      // An equal-but-distinct assignment outside the event delta is not replaced on publication.
      Map<String, Object> pending = new HashMap<>(Map.of("k", 1));
      session.state().put("unrelated", pending);
      release.countDown();
      append.get(20, TimeUnit.SECONDS);
      assertThat(session.state().get("unrelated")).isSameInstanceAs(pending);
      assertThat(session.state()).containsEntry("value", "new");
      assertThat(load(session).state()).containsEntry("value", "new");
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void listing() {
    Session session = create(service(source));
    JdbcProbe probe = new JdbcProbe(source, dialect);
    DatabaseSessionService service = service(probe);
    service.listSessions(session.appName(), "u").blockingGet();
    long one = probe.sql.stream().filter(sql -> sql.startsWith("SELECT")).count();
    for (int i = 0; i < 12; i++)
      service(source).createSession(session.appName(), "u", Map.of(), "other-" + i).blockingGet();
    probe.sql.clear();
    assertThat(service.listSessions(session.appName(), "u").blockingGet().sessions()).hasSize(13);
    assertThat(probe.sql.stream().filter(sql -> sql.startsWith("SELECT")).count()).isEqualTo(one);
    assertThat(probe.sql.stream().noneMatch(sql -> sql.contains("FROM events"))).isTrue();
    assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
  }

  private void connectionState() {
    JdbcProbe probe = new JdbcProbe(source, dialect);
    DatabaseSessionService service = service(probe);
    Session session = create(service);
    service
        .appendEvent(session, DatabaseSessionContract.event("event", 1, Map.of("app:k", 1)))
        .blockingGet();
    service.getSession(session.sessionKey(), null).blockingGet();
    service.listSessions(session.appName(), "u").blockingGet();
    service.listEvents(session.sessionKey()).blockingGet();
    service.deleteSession(session.sessionKey()).blockingAwait();
    assertThrows(
        SessionNotFoundException.class,
        () -> service.deleteSession(session.sessionKey()).blockingAwait());
    assertThat(probe.borrowed.get()).isEqualTo(7);
    assertThat(probe.closed.get()).isEqualTo(7);
  }

  private void snapshot() throws Exception {
    String oldJournal = null;
    if (dialect == Dialect.SQLITE) {
      oldJournal = journal(null);
      journal("wal");
    }
    var executor = Executors.newFixedThreadPool(2);
    CountDownLatch viewed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    try {
      Session session =
          service(source)
              .createSession("snapshot-" + UUID.randomUUID(), "u", Map.of("version", 0), "id")
              .blockingGet();
      JdbcProbe reader = new JdbcProbe(source, dialect);
      reader.hook =
          (sql, after) -> {
            if (after && sql.startsWith("SELECT s.id")) {
              viewed.countDown();
              if (!release.await(20, TimeUnit.SECONDS))
                throw new AssertionError("Read barrier timed out");
            }
          };
      var reading =
          executor.submit(
              () -> service(reader).getSession(session.sessionKey(), null).blockingGet());
      assertThat(viewed.await(10, TimeUnit.SECONDS)).isTrue();
      JdbcProbe writer = new JdbcProbe(source, dialect);
      writer.hook =
          (sql, after) -> {
            if (after && sql.equals("COMMIT")) committed.countDown();
          };
      var writing =
          executor.submit(
              () ->
                  service(writer)
                      .appendEvent(
                          session,
                          DatabaseSessionContract.event("concurrent", 1, Map.of("version", 1)))
                      .blockingGet());
      assertThat(committed.await(20, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      Session snapshot = reading.get(20, TimeUnit.SECONDS);
      writing.get(20, TimeUnit.SECONDS);
      assertThat(snapshot.state()).containsExactly("version", 0);
      assertThat(snapshot.immutableEvents()).isEmpty();
      assertThat(load(session).state()).containsExactly("version", 1);
      assertThat(load(session).immutableEvents()).hasSize(1);
      assertThat(reader.borrowed.get()).isEqualTo(reader.closed.get());
      assertThat(writer.borrowed.get()).isEqualTo(writer.closed.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      if (oldJournal != null) journal(oldJournal);
    }
  }

  private String journal(String mode) throws Exception {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                mode == null ? "PRAGMA journal_mode" : "PRAGMA journal_mode=" + mode)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private void unrelatedLock() throws Exception {
    Session session = create(service(source));
    whileAppRowLocked(
        session,
        () ->
            service(source)
                .appendEvent(session, DatabaseSessionContract.event("local", 1, Map.of("local", 1)))
                .blockingGet());
  }

  private void createLock() throws Exception {
    DatabaseSessionService service = service(source);
    Session session = create(service);
    whileAppRowLocked(
        session,
        () -> {
          Session created =
              service.createSession(session.appName(), "u", Map.of(), "another").blockingGet();
          assertThat(created.state()).containsEntry("app:original", 1);
          SessionException duplicate =
              assertThrows(
                  SessionException.class,
                  () ->
                      service
                          .createSession(
                              session.appName(), "u", Map.of("app:wrong", 2), session.id())
                          .blockingGet());
          assertThat(duplicate).hasMessageThat().isEqualTo(SessionException.SESSION_ALREADY_EXISTS);
          return created;
        });
  }

  private void whileAppRowLocked(Session session, Callable<?> work) throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try (Connection blocker = source.getConnection()) {
      blocker.setAutoCommit(false);
      try (var update =
          blocker.prepareStatement(
              "UPDATE app_states SET update_time=update_time WHERE app_name=?")) {
        update.setString(1, session.appName());
        update.executeUpdate();
      }
      try {
        // A deadline for proving lock independence, not a latency/performance benchmark.
        executor.submit(work).get(5, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
        blocker.setAutoCommit(true);
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
  }
}
