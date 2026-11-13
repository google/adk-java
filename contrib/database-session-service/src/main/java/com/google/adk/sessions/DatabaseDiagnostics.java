/*
 * Copyright 2026 Google LLC
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

import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports stable diagnostic fields without passing a driver exception to a caller. The driver's own
 * exception, whose message can quote SQL or data, is logged only at DEBUG, which an operator turns
 * on deliberately.
 *
 * <p>A database failure therefore reaches two audiences in two forms. The caller gets a {@link
 * SessionException} with a message from this module and no cause. The operator gets one WARN line
 * with the operation, the phase it failed in, the dialect, the exception's class, its SQLSTATE and
 * its vendor code, which is what is needed to look the failure up.
 */
final class DatabaseDiagnostics {
  private static final Logger logger = LoggerFactory.getLogger(DatabaseDiagnostics.class);

  private DatabaseDiagnostics() {}

  /** The step of an operation in which a failure happened, in the order the steps run. */
  enum Phase {
    /** Borrowing a connection from the data source. */
    CONNECTION,
    /** Checking the borrowed connection, setting isolation, and beginning the transaction. */
    SETUP,
    /** Checking that the schema is the one this version reads and writes. */
    SCHEMA,
    /** Running the statements of the operation. */
    STATEMENT,
    /** Committing. */
    COMMIT,
    /** Restoring the connection's settings and returning it. */
    CLEANUP,
    /** Running the schema migration, which is not part of an operation. */
    MIGRATION
  }

  /**
   * Requires a connection this module can run its own transaction on.
   *
   * <p>A connection with auto-commit off is, as far as can be told, in the middle of the caller's
   * transaction: committing on it would commit the caller's work, and rolling back would discard
   * it. It is refused before anything is executed.
   *
   * @param write whether the operation changes data, which a read-only connection cannot do
   * @throws Configuration with a message naming what the data source has to provide
   */
  static void requireConnection(Connection connection, boolean write) throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new Configuration("ADK requires a borrowed connection with auto-commit enabled.");
    }
    if (write && connection.isReadOnly()) {
      throw new Configuration("ADK writes require a writable database connection.");
    }
  }

  /**
   * Logs a failure and returns the exception to throw in its place.
   *
   * @param message the text for the caller; written by this module, without data from {@code
   *     failure}
   * @param failure what actually went wrong; reported to the log, and deliberately not attached as
   *     the cause
   */
  static SessionException failure(
      String operation, Phase phase, @Nullable Dialect dialect, String message, Throwable failure) {
    report(operation, phase, dialect, failure);
    return new SessionException(message);
  }

  /**
   * Logs a failure: its coded fields at WARN, and the exception itself with its stack trace at
   * DEBUG.
   *
   * @param dialect {@code null} while the dialect is still being detected
   */
  static void report(String operation, Phase phase, @Nullable Dialect dialect, Throwable failure) {
    SqlDiagnostic code = sqlDiagnostic(failure);
    logger.warn(
        "ADK JDBC failure: operation={} phase={} dialect={} exceptionType={} sqlState={} vendorCode={}",
        operation,
        phase,
        dialect,
        failure.getClass().getName(),
        code.state(),
        code.vendorCode());
    if (logger.isDebugEnabled()) {
      logger.debug("ADK JDBC failure detail: operation={} phase={}", operation, phase, failure);
    }
  }

  /**
   * The two coded fields of a JDBC failure.
   *
   * @param state the five-character SQLSTATE, or {@code "unavailable"}
   * @param vendorCode the engine's own error number, or 0
   */
  record SqlDiagnostic(String state, int vendorCode) {}

  /**
   * Extracts the coded fields of the first {@link SQLException} in the cause chain. Messages are
   * never read.
   */
  static SqlDiagnostic sqlDiagnostic(Throwable failure) {
    SQLException sql = sqlException(failure);
    if (sql == null) {
      return new SqlDiagnostic("unavailable", 0);
    }
    String state = sql.getSQLState();
    return new SqlDiagnostic(isSqlState(state) ? state : "unavailable", sql.getErrorCode());
  }

  /**
   * Whether the database itself gave up on the transaction because another one held conflicting
   * locks: a deadlock victim, a serialization failure, or a lock wait that timed out.
   */
  static boolean concurrencyConflict(Throwable failure, Dialect dialect) {
    SQLException sql = sqlException(failure);
    if (sql == null) {
      return false;
    }
    String state = sql.getSQLState();
    // Class 40 is the standard "transaction rollback" class on every engine.
    if (state != null && state.startsWith("40")) {
      return true;
    }
    int code = sql.getErrorCode();
    return switch (dialect) {
      case POSTGRESQL -> "55P03".equals(state); // lock_not_available, including lock_timeout
      case MYSQL, MARIADB -> code == 1205 || code == 1213; // lock wait timeout, deadlock
      // SQLITE_BUSY and SQLITE_LOCKED; the low byte also matches their extended codes.
      case SQLITE -> (code & 0xFF) == 5 || (code & 0xFF) == 6;
    };
  }

  /** Finds the first {@link SQLException} among a failure and its causes, a few levels deep. */
  private static @Nullable SQLException sqlException(Throwable failure) {
    // Flyway may wrap the SQLException. Bound traversal and never read exception messages.
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
      if (cause instanceof SQLException sql) {
        return sql;
      }
    }
    return null;
  }

  /** SQLSTATE is five upper-case letters or digits; anything else is not a code to repeat. */
  private static boolean isSqlState(@Nullable String state) {
    if (state == null || state.length() != 5) {
      return false;
    }
    for (int i = 0; i < 5; i++) {
      char c = state.charAt(i);
      if (!(c >= '0' && c <= '9') && !(c >= 'A' && c <= 'Z')) {
        return false;
      }
    }
    return true;
  }

  /**
   * A known configuration error whose message is defined by this module. Because the text is the
   * module's own, it is the one failure whose message is handed to the caller unchanged.
   */
  static final class Configuration extends RuntimeException {
    Configuration(String message) {
      super(message);
    }
  }
}
