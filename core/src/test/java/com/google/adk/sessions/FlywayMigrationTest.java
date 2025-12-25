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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

/** Tests for Flyway migrations to ensure they're applied correctly. */
public class FlywayMigrationTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:testdb_migrations;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";

  @Test
  public void testMigrations() throws Exception {
    // Initialize Flyway
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();

    // Clean and migrate
    flyway.clean();
    flyway.migrate();

    // Get migration info to verify
    MigrationInfo[] migrations = flyway.info().applied();

    // Check that all expected migrations were applied
    assertTrue(migrations.length >= 1, "Expected at least 1 migration to be applied");
    assertEquals("1", migrations[0].getVersion().toString(), "First migration should be V1");
    assertEquals(
        "Initial schema",
        migrations[0].getDescription(),
        "First migration should be Initial schema");

    // Verify tables and indexes exist via JDBC
    try (Connection conn = flyway.getConfiguration().getDataSource().getConnection();
        Statement stmt = conn.createStatement()) {

      // Check tables exist
      String[] expectedTables = {"sessions", "events", "app_states", "user_states"};
      for (String table : expectedTables) {
        ResultSet rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'PUBLIC' AND table_name = '"
                    + table.toUpperCase()
                    + "'");
        rs.next();
        assertEquals(1, rs.getInt(1), "Table " + table + " should exist");
      }
    }

    // Verify DatabaseSessionService can be created with validate mode
    Map<String, Object> properties =
        Map.of(
            "hibernate.hbm2ddl.auto", "validate",
            "hibernate.show_sql", "true");

    DatabaseSessionService service = new DatabaseSessionService(TEST_DB_URL, properties);
    assertNotNull(service, "Service should be created successfully");
    service.close();
  }

  @Test
  public void testMigrationIdempotency() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();

    flyway.clean();
    flyway.migrate();

    MigrationInfo[] firstRun = flyway.info().applied();
    int firstRunCount = firstRun.length;

    flyway.migrate();

    MigrationInfo[] secondRun = flyway.info().applied();
    int secondRunCount = secondRun.length;

    assertEquals(
        firstRunCount, secondRunCount, "Running migrations twice should not apply more migrations");
  }

  @Test
  public void testMigrationValidation() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();

    flyway.clean();
    flyway.migrate();

    flyway.validate();
  }

  @Test
  public void testConcurrentMigrationAttempts() throws Exception {
    String concurrentDbUrl =
        "jdbc:h2:mem:concurrent_migrations;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";

    Flyway flyway1 =
        Flyway.configure()
            .dataSource(concurrentDbUrl, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .lockRetryCount(5)
            .load();

    flyway1.clean();

    Flyway flyway2 =
        Flyway.configure()
            .dataSource(concurrentDbUrl, null, null)
            .locations("classpath:db/migration/h2")
            .lockRetryCount(5)
            .load();

    Thread thread1 =
        new Thread(
            () -> {
              try {
                flyway1.migrate();
              } catch (Exception e) {
              }
            });

    Thread thread2 =
        new Thread(
            () -> {
              try {
                flyway2.migrate();
              } catch (Exception e) {
              }
            });

    thread1.start();
    thread2.start();

    thread1.join(10000);
    thread2.join(10000);

    flyway1.validate();
  }

  @Test
  public void testSchemaVersioning() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();

    flyway.clean();
    flyway.migrate();

    MigrationInfo current = flyway.info().current();
    assertNotNull(current, "Current migration should not be null");
    assertTrue(
        Integer.parseInt(current.getVersion().toString()) >= 1,
        "Current version should be at least 1");
  }
}
