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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * The places where the four engines need different SQL or a different JDBC call. Everything else in
 * {@link DatabaseSessionStore} is the same statement for all of them.
 *
 * <p>The table definitions differ too; those are in the per-dialect migration scripts under {@code
 * com/google/adk/sessions/migration}.
 */
final class DatabaseDialect {
  private DatabaseDialect() {}

  /**
   * The statement that reads the two rows identifying the schema: the layout version, which the
   * Python v1 schema defines, and the marker saying that this module's Java format is stored in it.
   * {@code key} is a reserved word in MySQL and MariaDB and has to be quoted there.
   */
  static String metadataQuery(Dialect dialect) {
    String key = dialect == Dialect.MYSQL || dialect == Dialect.MARIADB ? "`key`" : "key";
    return "SELECT "
        + key
        + ",value FROM adk_internal_metadata WHERE "
        + key
        + " IN ('schema_version','adk_java_storage_format')";
  }

  /**
   * Binds JSON text to a state or event column. PostgreSQL's {@code json} column does not accept a
   * parameter typed as text, so the value is sent untyped and the server takes it as JSON. The
   * other engines keep JSON in text columns.
   */
  static void bindJson(PreparedStatement statement, int index, String json, Dialect dialect)
      throws SQLException {
    if (dialect == Dialect.POSTGRESQL) {
      statement.setObject(index, json, Types.OTHER);
    } else {
      statement.setString(index, json);
    }
  }

  /**
   * The clause that makes a {@code SELECT} lock the rows it reads until the transaction ends.
   * SQLite has no such clause and needs none: a write transaction there is the only writer.
   */
  static String lock(Dialect dialect) {
    return dialect == Dialect.SQLITE ? "" : " FOR UPDATE";
  }

  /**
   * The statement that inserts an empty shared-state row unless the row is already there.
   *
   * <p>Parameters: the app name, the user ID if {@code user}, then the JSON document. The update
   * time is the database's clock.
   *
   * <p>MySQL and MariaDB have no {@code ON CONFLICT} clause. Their counterpart is {@code ON
   * DUPLICATE KEY UPDATE} with an assignment that changes nothing. On an existing row it takes an
   * exclusive lock right away, where a plain {@code INSERT} that hits a duplicate takes a shared
   * lock first, which is how two concurrent inserters of the same key come to deadlock.
   *
   * @param table {@code app_states} or {@code user_states}
   * @param user whether the row is per user
   */
  static String insertShared(String table, boolean user, Dialect dialect) {
    String columns = user ? "app_name,user_id,state,update_time" : "app_name,state,update_time";
    String values = (user ? "?,?,?," : "?,?,") + utcNow(dialect);
    String conflict =
        dialect == Dialect.MYSQL || dialect == Dialect.MARIADB
            ? " ON DUPLICATE KEY UPDATE app_name=app_name"
            : " ON CONFLICT DO NOTHING";
    return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")" + conflict;
  }

  /**
   * An expression for the database's current time as a UTC value without a zone, at the microsecond
   * precision of the time columns. It does not depend on the time zone of the session, the server
   * or the JVM.
   */
  static String utcNow(Dialect dialect) {
    return switch (dialect) {
      case POSTGRESQL -> "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')";
      case MYSQL, MARIADB -> "UTC_TIMESTAMP(6)";
      // SQLite's %f has three fractional digits; use the same six-digit format as JDBC writes.
      case SQLITE -> "strftime('%Y-%m-%d %H:%M:%f','now') || '000'";
    };
  }

  /**
   * Detects the dialect from the product name the JDBC driver reports, borrowing one connection.
   * The JDBC URL is never looked at: a pool or proxy may not expose it, and it can hold
   * credentials.
   *
   * @throws SessionException if no connection could be borrowed or the product is not one of the
   *     four supported ones; the message does not repeat the product name
   */
  static Dialect detect(DataSource dataSource) {
    String productName;
    try (Connection connection = dataSource.getConnection()) {
      productName = connection.getMetaData().getDatabaseProductName();
    } catch (SQLException | RuntimeException driverFailure) {
      throw DatabaseDiagnostics.failure(
          "detectDialect",
          Phase.CONNECTION,
          null,
          "Unable to determine the database dialect from JDBC metadata.",
          driverFailure);
    }

    if (productName != null) {
      switch (productName.strip().toLowerCase(Locale.ROOT)) {
        case "sqlite":
          return Dialect.SQLITE;
        case "postgresql":
          return Dialect.POSTGRESQL;
        case "mysql":
          return Dialect.MYSQL;
        case "mariadb":
          return Dialect.MARIADB;
        default:
          break;
      }
    }
    throw new SessionException(
        "Unsupported database product; select a supported dialect explicitly.");
  }
}
