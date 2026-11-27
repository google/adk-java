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

import com.google.adk.sessions.DatabaseDiagnostics.Phase;
import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Isolated, explicit Flyway bootstrap. Never adopts tables merely because their names match.
 *
 * <p>Flyway is used as an embedded library over the application's data source, kept apart from any
 * Flyway the application runs for itself:
 *
 * <ul>
 *   <li>The scripts are in {@code com/google/adk/sessions/migration/<dialect>}, not in Flyway's
 *       default {@code db/migration}, so an application's own Flyway does not pick them up.
 *   <li>The history is kept in {@code adk_schema_history}, not in the application's {@code
 *       flyway_schema_history}.
 *   <li>A schema that already holds other tables is baselined at version 0, so that version 1 of
 *       this module still runs in it.
 * </ul>
 *
 * <p>The table names are those of the Python v1 schema and are common words. Before and after
 * migrating, the tables found under those names are therefore checked to be this module's own; see
 * {@link #validateOwnership}.
 *
 * <p>This is the only class that refers to Flyway. A deployment that creates the tables from the
 * SQL scripts itself, and never calls {@code migrate()}, does not load it.
 */
final class DatabaseSchemaMigrator {
  /** Value of {@code schema_version}: the layout version, shared with the Python v1 schema. */
  static final String SCHEMA_VERSION = "1";

  /**
   * Value of {@code adk_java_storage_format}: says that the events and times in these tables were
   * written by this module. A schema created by ADK Python has the same {@link #SCHEMA_VERSION} but
   * stores events in another JSON form, and lacks this row.
   */
  static final String STORAGE_FORMAT = "jdbc-v1";

  /** The tables of the session schema together with this module's Flyway history table. */
  private static final Set<String> TABLES =
      Set.of(
          "adk_internal_metadata",
          "sessions",
          "events",
          "app_states",
          "user_states",
          "adk_schema_history");

  private DatabaseSchemaMigrator() {}

  /**
   * Brings the session schema to the current version, creating it in an empty or foreign schema and
   * doing nothing when it is up to date.
   *
   * @throws SessionException if tables of the same names exist that this module did not create, the
   *     schema is only partly there, or the migration itself fails
   */
  static void migrate(DataSource dataSource, Dialect dialect) {
    Phase phase = Phase.SCHEMA;
    try {
      // First, so that a foreign "sessions" or "events" table stops the migration before Flyway
      // creates anything next to it.
      validateOwnership(dataSource, dialect);
      phase = Phase.MIGRATION;
      // The class loader of this module, so that the scripts are found in its jar whatever the
      // thread's context class loader is.
      new FluentConfiguration(DatabaseSchemaMigrator.class.getClassLoader())
          .dataSource(dataSource)
          .locations(
              "classpath:com/google/adk/sessions/migration/"
                  + dialect.name().toLowerCase(Locale.ROOT))
          .table("adk_schema_history")
          // Without a baseline Flyway refuses a schema that has tables but no history table,
          // which is what an application's own database looks like on the first run.
          .baselineOnMigrate(true)
          .baselineVersion("0")
          // This module must never be able to drop the tables of the schema it shares.
          .cleanDisabled(true)
          .failOnMissingLocations(true)
          .validateMigrationNaming(true)
          // An empty list of loggers keeps Flyway quiet; its own log lines include the JDBC URL.
          .loggers(new String[0])
          .load()
          .migrate();
      phase = Phase.SCHEMA;
      // On a new connection, as the service will use: a data source that hands out private
      // in-memory databases would have migrated one that nobody can reach afterwards.
      if (!validateOwnership(dataSource, dialect)) {
        throw new DatabaseDiagnostics.Configuration(
            "The migrated ADK schema is not visible on a new database connection.");
      }
    } catch (DatabaseDiagnostics.Configuration configuration) {
      throw new SessionException(configuration.getMessage());
    } catch (SQLException | RuntimeException e) {
      throw DatabaseDiagnostics.failure(
          "migrate",
          phase,
          dialect,
          phase == Phase.SCHEMA
              ? "Unable to verify ADK schema ownership before or after migration."
              : "Unable to migrate the ADK database schema; verify database and Flyway configuration.",
          e);
    }
  }

  /**
   * Looks at the tables that carry one of this module's names in the schema the data source
   * connects to, and decides whose they are.
   *
   * <ul>
   *   <li>None of them exists: nothing to own yet.
   *   <li>All of them exist and the metadata rows are this module's: the schema is ours.
   *   <li>Anything in between is refused: tables without the metadata table belong to someone else,
   *       metadata without the Java marker is a Python schema, and an incomplete set is a migration
   *       that stopped half way or a table that was dropped.
   * </ul>
   *
   * @return whether the complete schema of this module is present
   * @throws DatabaseDiagnostics.Configuration if tables are present that are not this module's
   *     complete schema
   */
  private static boolean validateOwnership(DataSource dataSource, Dialect dialect)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      DatabaseDiagnostics.requireConnection(connection, true);
      // Only the catalog and schema of this connection: tables of the same names elsewhere on
      // the server are none of this module's business. Names are compared in lower case because
      // engines differ in how they report the case of an unquoted name.
      Set<String> existing = new HashSet<>();
      try (ResultSet tables =
          connection
              .getMetaData()
              .getTables(
                  connection.getCatalog(),
                  connection.getSchema(),
                  null,
                  new String[] {"TABLE", "VIEW"})) {
        while (tables.next()) {
          String name = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
          if (TABLES.contains(name)) {
            existing.add(name);
          }
        }
      }
      if (existing.isEmpty()) {
        return false;
      }
      if (!existing.contains("adk_internal_metadata")) {
        throw new DatabaseDiagnostics.Configuration(
            "The database session schema was not created by ADK Java.");
      }
      try (Statement statement = connection.createStatement();
          ResultSet metadata = statement.executeQuery(DatabaseDialect.metadataQuery(dialect))) {
        // A Python V1 schema has the same version but no Java storage-format marker.
        requireMetadata(metadata);
      }
      if (!existing.equals(TABLES)) {
        throw new DatabaseDiagnostics.Configuration(
            "ADK migration found a partial or conflicting schema.");
      }
      return true;
    }
  }

  /**
   * Requires both identifying rows of {@code adk_internal_metadata} to have the values this version
   * writes. The store calls it at the start of every transaction, with the rows read inside that
   * transaction.
   *
   * @param metadata key and value columns, as selected by {@link DatabaseDialect#metadataQuery}
   * @throws DatabaseDiagnostics.Configuration if the Java marker is missing or different, or the
   *     layout version is not the supported one
   */
  static void requireMetadata(ResultSet metadata) throws SQLException {
    String format = null;
    String version = null;
    while (metadata.next()) {
      switch (metadata.getString(1)) {
        case "adk_java_storage_format" -> format = metadata.getString(2);
        case "schema_version" -> version = metadata.getString(2);
        default -> {}
      }
    }
    if (!STORAGE_FORMAT.equals(format)) {
      throw new DatabaseDiagnostics.Configuration(
          "The database session schema was not created by ADK Java.");
    }
    if (!SCHEMA_VERSION.equals(version)) {
      throw new DatabaseDiagnostics.Configuration(
          "The ADK database schema version is missing or unsupported.");
    }
  }
}
