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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Lifecycle/read vertical slice against a real SQLite database. */
@Timeout(30)
final class DatabaseSessionStoreTest {
  @TempDir Path directory;
  private SQLiteDataSource source;
  private DatabaseSessionStore store;
  private final SessionKey key = new SessionKey("app", "user", "session");

  @BeforeEach
  void setUp() {
    source = DatabaseSchemaMigratorTest.source(directory.resolve("store.db"));
    new DatabaseSessionService(source).migrate().blockingAwait();
    store = new DatabaseSessionStore(source, DatabaseSessionService.Dialect.SQLITE, 15);
  }

  @Test
  void lifecycleSharesOnlyTheCorrectScopesAndListingHasNoHistory() throws Exception {
    Session created =
        create(
            key,
            Map.of(
                "app:theme", "dark", "user:name", "Ada", "local", 1, "temp:secret", new Object()));
    create(new SessionKey("app", "user", "other"), Map.of());
    create(new SessionKey("app", "other-user", "session"), Map.of());
    create(new SessionKey("other-app", "user", "session"), Map.of());
    seed(1, 1000);

    assertThat(created.lastUpdateTime()).isEqualTo(Instant.ofEpochMilli(123));
    assertThat(created.state())
        .containsExactly("app:theme", "dark", "user:name", "Ada", "local", 1);
    assertThat(load(key).immutableEvents()).hasSize(1);
    assertThat(load(new SessionKey("app", "other-user", "session")).state())
        .containsExactly("app:theme", "dark");
    assertThat(load(new SessionKey("other-app", "user", "session")).state()).isEmpty();
    assertThat(store.list("app", "user")).hasSize(2);
    assertThat(
            store.list("app", "user").stream()
                .allMatch(session -> session.immutableEvents().isEmpty()))
        .isTrue();
  }

  @Test
  void duplicateCreationRollsBackSharedStateAndKeepsTheStandardError() {
    create(key, Map.of("app:theme", "original", "user:name", "Ada"));

    SessionException error =
        assertThrows(
            SessionException.class,
            () -> create(key, Map.of("app:theme", "changed", "user:name", "changed")));

    assertThat(error).hasMessageThat().isEqualTo(SessionException.SESSION_ALREADY_EXISTS);
    assertThat(error).hasCauseThat().isNull();
    assertThat(load(key).state()).containsExactly("app:theme", "original", "user:name", "Ada");
  }

  @Test
  void filteringLimitsAppendPositionsBeforeApplyingInclusivePreciseCursor() throws Exception {
    create(key, Map.of());
    seed(1, 3000);
    seed(2, 1000);
    seed(3, 2000);

    Session filtered =
        store.get(
            key,
            GetSessionConfig.builder()
                .numRecentEvents(2)
                .afterTimestamp(Instant.ofEpochMilli(1500))
                .build());
    assertThat(filtered.immutableEvents().stream().map(Event::timestamp).toList())
        .containsExactly(2000L);
    assertThat(
            store
                .get(
                    key,
                    GetSessionConfig.builder()
                        .afterTimestamp(Instant.ofEpochMilli(1000).plusNanos(1))
                        .build())
                .immutableEvents()
                .stream()
                .map(Event::timestamp)
                .toList())
        .containsExactly(3000L, 2000L)
        .inOrder();
    assertThat(
            store.get(key, GetSessionConfig.builder().numRecentEvents(0).build()).immutableEvents())
        .isEmpty();
    assertThat(store.listEvents(key).stream().map(Event::id).toList())
        .containsExactly("event-1", "event-2", "event-3")
        .inOrder();
  }

  @Test
  void deleteCascadesHistoryButRetainsSharedStateAndMissingErrorsFollowTheInterface()
      throws Exception {
    create(key, Map.of("app:shared", 1, "user:shared", 2));
    seed(1, 1000);
    store.delete(key);

    assertThat(store.get(key, GetSessionConfig.builder().build())).isNull();
    assertThrows(SessionNotFoundException.class, () -> store.listEvents(key));
    assertThrows(SessionNotFoundException.class, () -> store.delete(key));
    assertThat(create(key, Map.of()).state()).containsExactly("app:shared", 1, "user:shared", 2);
    assertThat(store.listEvents(key)).isEmpty();
  }

  @Test
  void keysUseExactUnicodeCaseAndTrailingSpaces() {
    for (String id : new String[] {"a", "A", "á", "a ", "雪😀"}) {
      create(new SessionKey("app", "user", id), Map.of("key", id));
    }
    assertThat(store.list("app", "user")).hasSize(5);
    assertThat(load(new SessionKey("app", "user", "a ")).state()).containsExactly("key", "a ");
  }

  @Test
  void readSnapshotsAreDetachedAndVersionMismatchFailsSafely() throws Exception {
    create(key, Map.of("nested", Map.of("value", 1)));
    load(key).state().clear();
    assertThat(load(key).state()).containsEntry("nested", Map.of("value", 1));
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE adk_internal_metadata SET value='private-future-version' WHERE key='schema_version'");
    }
    SessionException error = assertThrows(SessionException.class, () -> load(key));
    assertThat(error).hasCauseThat().isNull();
    assertThat(error).hasMessageThat().doesNotContain("private-future-version");
    assertThat(error).hasMessageThat().contains("schema version");
  }

  @Test
  void missingSharedRowIsCorruptionNotASilentlyMissingSession() throws Exception {
    create(key, Map.of("user:retained", 1));
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM user_states");
    }
    SessionException error = assertThrows(SessionException.class, () -> load(key));
    assertThat(error).hasCauseThat().isNull();
    assertThat(error).hasMessageThat().isEqualTo("Unable to decode session state.");
  }

  @Test
  void listingReadsSharedStateOnceAndStillGivesEverySessionItsOwnValues() throws Exception {
    create(key, Map.of("app:shared", List.of("original"), "user:name", "Ada", "local", 1));
    create(new SessionKey("app", "user", "other"), Map.of("local", 2));
    JdbcProbe probe = new JdbcProbe(source, DatabaseSessionService.Dialect.SQLITE);
    List<Session> listed =
        new DatabaseSessionStore(probe, DatabaseSessionService.Dialect.SQLITE, 15)
            .list("app", "user");

    assertThat(probe.sql.stream().filter(sql -> sql.contains("app_states")).count()).isEqualTo(1);
    assertThat(listed.stream().map(Session::id).toList())
        .containsExactly("other", "session")
        .inOrder();
    assertThat(listed.get(0).state())
        .containsExactly("app:shared", List.of("original"), "user:name", "Ada", "local", 2);
    ((List<?>) listed.get(0).state().get("app:shared")).clear();
    assertThat(listed.get(1).state())
        .containsExactly("app:shared", List.of("original"), "user:name", "Ada", "local", 1);
    // A user without sessions has no shared rows to read, and that is not an error.
    assertThat(store.list("app", "nobody")).isEmpty();
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM app_states");
    }
    SessionException error = assertThrows(SessionException.class, () -> store.list("app", "user"));
    assertThat(error).hasMessageThat().isEqualTo("Unable to decode session state.");
  }

  @Test
  void corruptStoredEventKeepsSafeCodecMessageWithoutSuppressedCloseDetails() throws Exception {
    create(key, Map.of());
    seed(1, 1000);
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      // An object passes the schema CHECK but does not have the required native Event fields.
      statement.executeUpdate("UPDATE events SET event_data='{}'");
    }
    JdbcProbe probe = new JdbcProbe(source, DatabaseSessionService.Dialect.SQLITE);
    probe.hook =
        (sql, after) -> {
          if (after && sql.equals("CLOSE CONNECTION")) {
            throw new SQLException("private-codec-close");
          }
        };
    DatabaseSessionStore failingStore =
        new DatabaseSessionStore(probe, DatabaseSessionService.Dialect.SQLITE, 15);
    SessionException failure =
        assertThrows(
            SessionException.class,
            () -> failingStore.get(key, GetSessionConfig.builder().build()));
    assertThat(failure.getClass()).isEqualTo(SessionException.class);
    assertThat(failure).hasMessageThat().isEqualTo("Unable to decode session event.");
    assertThat(failure).hasCauseThat().isNull();
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(probe.borrowed.get()).isEqualTo(probe.closed.get());
  }

  @Test
  void sqliteOverridesWeakReadIsolationOnlyDuringItsTransaction() throws Exception {
    DataSource weak = mock(DataSource.class);
    var current = new java.util.concurrent.atomic.AtomicReference<Connection>();
    when(weak.getConnection())
        .thenAnswer(
            ignored -> {
              Connection connection = source.getConnection();
              connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
              current.set(connection);
              return connection;
            });
    JdbcProbe probe = new JdbcProbe(weak, DatabaseSessionService.Dialect.SQLITE);
    probe.hook =
        (sql, after) -> {
          if (!after && sql.contains("FROM adk_internal_metadata")) {
            assertThat(current.get().getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
          }
        };
    DatabaseSessionService service =
        DatabaseSessionService.builder(probe)
            .dialect(DatabaseSessionService.Dialect.SQLITE)
            .build();
    Session session = service.createSession("app", "user").blockingGet();
    assertThat(service.getSession(session.sessionKey(), null).blockingGet()).isNotNull();
    assertThat(probe.closed.get()).isEqualTo(2);
  }

  private Session create(SessionKey key, Map<String, Object> state) {
    return store.create(key, state, Instant.ofEpochMilli(123));
  }

  private Session load(SessionKey key) {
    return store.get(key, GetSessionConfig.builder().build());
  }

  private void seed(long sequence, long timestamp) throws Exception {
    try (Connection connection = source.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO events VALUES ('app','user','session',?,?,'',?,?)")) {
      String id = "event-" + sequence;
      statement.setLong(1, sequence);
      statement.setString(2, id);
      DatabaseTime.bind(
          statement, 3, Instant.ofEpochMilli(timestamp), DatabaseSessionService.Dialect.SQLITE);
      statement.setString(
          4,
          new SessionDataCodec().writeEvent(Event.builder().id(id).timestamp(timestamp).build()));
      statement.executeUpdate();
    }
  }
}
