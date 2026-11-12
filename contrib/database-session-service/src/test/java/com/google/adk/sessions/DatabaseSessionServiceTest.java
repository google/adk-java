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

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The shared contract's fast, real SQLite execution (Docker is not required). */
@Timeout(120)
final class DatabaseSessionServiceTest {
  @TempDir Path directory;

  /** Keeps the shared contract honest: it must describe what the reference in core really does. */
  @TestFactory
  Stream<DynamicTest> inMemoryReferenceContract() {
    InMemorySessionService reference =
        new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
    return new SessionServiceContract(() -> reference).tests();
  }

  @TestFactory
  Stream<DynamicTest> sqliteContract() throws Exception {
    var source = DatabaseSchemaMigratorTest.source(directory.resolve("contract.db"));
    new DatabaseSessionService(source).migrate().blockingAwait();
    printVersion(source, "default");
    return Stream.concat(
        new DatabaseSessionContract(source, DatabaseSessionService.Dialect.SQLITE).tests(),
        new DatabaseSessionJdbcContract(source, DatabaseSessionService.Dialect.SQLITE).tests());
  }

  @TestFactory
  Stream<DynamicTest> sqliteWalContract() throws Exception {
    var source = DatabaseSchemaMigratorTest.source(directory.resolve("wal-contract.db"));
    new DatabaseSessionService(source).migrate().blockingAwait();
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
    }
    printVersion(source, "wal");
    return Stream.concat(
        new DatabaseSessionContract(source, DatabaseSessionService.Dialect.SQLITE).tests(),
        new DatabaseSessionJdbcContract(source, DatabaseSessionService.Dialect.SQLITE).tests());
  }

  private static void printVersion(javax.sql.DataSource source, String journal) throws Exception {
    try (var connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      System.out.printf(
          "ADK_DATABASE_TEST backend=sqlite journal=%s engine=%s driver=%s java=%s%n",
          journal,
          metadata.getDatabaseProductVersion(),
          metadata.getDriverVersion(),
          System.getProperty("java.version"));
    }
  }
}
