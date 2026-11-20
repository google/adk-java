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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Actual file-backed SQLite migrations, not H2 or mocked JDBC. */
@Timeout(30)
final class DatabaseSchemaMigratorTest {
  @TempDir Path directory;

  @Test
  void migrationIsColdAndRepeatableAndConstructionDoesNotCreateTheFile() throws Exception {
    Path file = directory.resolve("sessions.db");
    SQLiteDataSource source = source(file);
    DatabaseSessionService service =
        DatabaseSessionService.builder(source).autoMigrate(true).build();
    var migration = service.migrate();
    assertThat(Files.exists(file)).isFalse();

    migration.blockingAwait();
    migration.blockingAwait();

    assertThat(tables(source))
        .containsAtLeast(
            "sessions",
            "events",
            "app_states",
            "user_states",
            "adk_internal_metadata",
            "adk_schema_history");
    assertThat(scalar(source, "SELECT value FROM adk_internal_metadata WHERE key='schema_version'"))
        .isEqualTo(DatabaseSchemaMigrator.SCHEMA_VERSION);
    assertThat(
            scalar(
                source,
                "SELECT value FROM adk_internal_metadata WHERE key='adk_java_storage_format'"))
        .isEqualTo(DatabaseSchemaMigrator.STORAGE_FORMAT);
    assertThat(scalar(source, "SELECT COUNT(*) FROM adk_schema_history WHERE success=1"))
        .isEqualTo("1");
  }

  @Test
  void optInInitializationMigratesButDefaultInitializationDoesNot() throws Exception {
    SQLiteDataSource defaultSource = source(directory.resolve("default.db"));
    DatabaseSessionService defaultService = new DatabaseSessionService(defaultSource);
    defaultService.initialize();
    assertThat(tables(defaultSource)).isEmpty();

    SQLiteDataSource optedIn = source(directory.resolve("opted-in.db"));
    DatabaseSessionService service =
        DatabaseSessionService.builder(optedIn).autoMigrate(true).build();
    service.initialize();
    service.initialize();
    assertThat(scalar(optedIn, "SELECT COUNT(*) FROM adk_schema_history")).isEqualTo("1");
  }

  @Test
  void collidingForeignTableIsNotAdoptedOrDeleted() throws Exception {
    SQLiteDataSource source = source(directory.resolve("foreign.db"));
    execute(source, "CREATE TABLE sessions (private_value TEXT)");
    execute(source, "INSERT INTO sessions VALUES ('private-value')");

    SessionException error =
        assertThrows(
            SessionException.class,
            () -> new DatabaseSessionService(source).migrate().blockingAwait());

    assertThat(error).hasCauseThat().isNull();
    assertThat(error).hasMessageThat().doesNotContain("private-value");
    assertThat(tables(source)).containsExactly("sessions");
    assertThat(scalar(source, "SELECT private_value FROM sessions")).isEqualTo("private-value");
  }

  @Test
  void applicationFlywayAndDefaultLocationsRemainIndependent() throws Exception {
    SQLiteDataSource source = source(directory.resolve("application.db"));
    new FluentConfiguration().dataSource(source).loggers(new String[0]).load().migrate();

    new DatabaseSessionService(source).migrate().blockingAwait();
    new FluentConfiguration().dataSource(source).loggers(new String[0]).load().migrate();

    assertThat(tables(source))
        .containsAtLeast(
            "application_data", "flyway_schema_history", "adk_schema_history", "sessions");
    assertThat(scalar(source, "SELECT COUNT(*) FROM flyway_schema_history")).isEqualTo("1");
    // ADK baselines the nonempty application schema at 0 and then applies its own V1.
    assertThat(scalar(source, "SELECT COUNT(*) FROM adk_schema_history")).isEqualTo("2");
  }

  @Test
  void unknownMetadataVersionFailsBeforeFurtherMigration() throws Exception {
    SQLiteDataSource source = migrated();
    execute(
        source,
        "UPDATE adk_internal_metadata SET value='future-private-version' WHERE key='schema_version'");

    SessionException error =
        assertThrows(
            SessionException.class,
            () -> new DatabaseSessionService(source).migrate().blockingAwait());

    assertThat(error).hasCauseThat().isNull();
    assertThat(error).hasMessageThat().doesNotContain("future-private-version");
    assertThat(scalar(source, "SELECT value FROM adk_internal_metadata WHERE key='schema_version'"))
        .isEqualTo("future-private-version");
  }

  @Test
  void pythonStyleVersionAloneIsNotAnOwnershipMarker() throws Exception {
    SQLiteDataSource source = source(directory.resolve("python-v1.db"));
    execute(
        source,
        "CREATE TABLE adk_internal_metadata (key VARCHAR(128) PRIMARY KEY, value VARCHAR(256))");
    execute(source, "INSERT INTO adk_internal_metadata VALUES ('schema_version','1')");
    for (String table : List.of("sessions", "events", "app_states", "user_states")) {
      execute(source, "CREATE TABLE " + table + " (id VARCHAR(128))");
    }
    execute(source, "INSERT INTO sessions VALUES ('untouched')");
    DatabaseSessionService service = new DatabaseSessionService(source);

    SessionException migrate =
        assertThrows(SessionException.class, () -> service.migrate().blockingAwait());
    SessionException crud =
        assertThrows(
            SessionException.class, () -> service.createSession("app", "user").blockingGet());
    assertThat(migrate).hasMessageThat().contains("not created by ADK Java");
    assertThat(crud).hasMessageThat().contains("not created by ADK Java");
    assertThat(tables(source))
        .containsExactly(
            "adk_internal_metadata", "sessions", "events", "app_states", "user_states");
    assertThat(scalar(source, "SELECT COUNT(*) FROM adk_internal_metadata")).isEqualTo("1");
    assertThat(scalar(source, "SELECT id FROM sessions")).isEqualTo("untouched");
  }

  @Test
  void unknownStorageFormatIsRejectedEvenWithCorrectVersion() throws Exception {
    SQLiteDataSource source = migrated();
    execute(
        source,
        "UPDATE adk_internal_metadata SET value='other' WHERE key='adk_java_storage_format'");
    DatabaseSessionService service = new DatabaseSessionService(source);
    SessionException failure =
        assertThrows(
            SessionException.class, () -> service.listSessions("app", "user").blockingGet());
    assertThat(failure).hasMessageThat().contains("not created by ADK Java");
  }

  @Test
  void incompleteSchemaIsNotSilentlyRecreated() throws Exception {
    SQLiteDataSource source = migrated();
    execute(source, "DROP TABLE events");

    assertThrows(
        SessionException.class, () -> new DatabaseSessionService(source).migrate().blockingAwait());
    assertThat(tables(source)).doesNotContain("events");
  }

  @Test
  void realSchemaEnforcesKeysJsonAndForeignKeyCascade() throws Exception {
    SQLiteDataSource source = migrated();
    String time = "'1970-01-01 00:00:00.000000'";
    String times = time + "," + time;
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "INSERT INTO sessions VALUES ('a','u','s','[]'," + times + ")"));
      assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("INSERT INTO app_states VALUES ('a','[]'," + time + ")"));
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "INSERT INTO app_states VALUES ('" + "x".repeat(129) + "','{}'," + time + ")"));
      String insertSession = "INSERT INTO sessions VALUES ('a','u','s','{}'," + times + ")";
      statement.executeUpdate(insertSession);
      assertThrows(SQLException.class, () -> statement.executeUpdate(insertSession));
      String eventSuffix = ",''," + time + ",'{}')";
      statement.executeUpdate("INSERT INTO events VALUES ('a','u','s',1,'id'" + eventSuffix);
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "INSERT INTO events VALUES ('a','u','s',2,'id'" + eventSuffix));
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "INSERT INTO events VALUES ('a','u','s',1,'other'" + eventSuffix));
      statement.executeUpdate("INSERT INTO events VALUES ('a','u','s',2,'other'" + eventSuffix);
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "INSERT INTO events VALUES ('a','u','missing',1,'id'" + eventSuffix));
      statement.executeUpdate("DELETE FROM sessions WHERE app_name='a'");
      try (ResultSet events = statement.executeQuery("SELECT COUNT(*) FROM events")) {
        assertThat(events.next()).isTrue();
        assertThat(events.getInt(1)).isEqualTo(0);
      }
    }
  }

  @Test
  void schemaIndexesEventTimestampsWithoutAnUnusedSessionTimeIndex() throws Exception {
    SQLiteDataSource source = migrated();
    assertThat(
            scalar(
                source,
                "SELECT group_concat(column_order, ',') FROM (SELECT name || ':' || \"desc\" AS column_order FROM pragma_index_xinfo('idx_events_app_user_session_ts_id') WHERE \"key\"=1 ORDER BY seqno)"))
        .isEqualTo("app_name:0,user_id:0,session_id:0,timestamp:1,id:1");
    assertThat(
            scalar(
                source,
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('idx_sessions_app_user_updated','idx_events_app_user_session_ts') AND type='index'"))
        .isEqualTo("0");
  }

  @Test
  void connectionLocalMemoryCannotPretendToHaveMigratedADurableSource() {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite::memory:");
    SessionException failure =
        assertThrows(
            SessionException.class,
            () -> new DatabaseSessionService(source).migrate().blockingAwait());
    assertThat(failure).hasCauseThat().isNull();
  }

  @Test
  void namedSharedMemoryWorksWithAnApplicationOwnedKeeperConnection() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl(
        "jdbc:sqlite:file:adk-" + java.util.UUID.randomUUID() + "?mode=memory&cache=shared");
    try (Connection keeper = source.getConnection()) {
      DatabaseSessionService service = new DatabaseSessionService(source);
      service.migrate().blockingAwait();
      Session session = service.createSession("app", "user").blockingGet();
      assertThat(service.getSession(session.sessionKey(), null).blockingGet()).isNotNull();
      assertThat(keeper.isClosed()).isFalse();
    }
  }

  private SQLiteDataSource migrated() {
    SQLiteDataSource source = source(directory.resolve("schema.db"));
    new DatabaseSessionService(source).migrate().blockingAwait();
    return source;
  }

  static SQLiteDataSource source(Path file) {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
    return source;
  }

  private static Set<String> tables(SQLiteDataSource source) throws Exception {
    Set<String> names = new HashSet<>();
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
      while (result.next()) {
        names.add(result.getString(1));
      }
    }
    return names;
  }

  private static String scalar(SQLiteDataSource source, String query) throws Exception {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(query)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static void execute(SQLiteDataSource source, String query) throws Exception {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(query);
    }
  }
}
