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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.sessions.DatabaseDiagnostics.Phase;
import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

final class DatabaseDiagnosticsTest {
  @Test
  void extractsOnlySqlStateAndNumericVendorCodeFromWrappedFailure() {
    SQLException sql = new SQLException("private-jdbc-url-and-payload", "08006", 77);
    sql.addSuppressed(new SQLException("private-cleanup"));
    RuntimeException driverWrapper = new RuntimeException("private-wrapper", sql);
    assertThat(DatabaseDiagnostics.sqlDiagnostic(driverWrapper))
        .isEqualTo(new DatabaseDiagnostics.SqlDiagnostic("08006", 77));
    SessionException safe =
        DatabaseDiagnostics.failure(
            "test", Phase.CONNECTION, Dialect.SQLITE, "Safe.", driverWrapper);
    assertThat(safe).hasMessageThat().isEqualTo("Safe.");
    assertThat(safe).hasCauseThat().isNull();
    assertThat(safe.getSuppressed()).isEmpty();
  }

  @Test
  void anySqlStateCodeIsReportedButOtherTextInItsPlaceIsNot() {
    // Engines define their own classes, such as PostgreSQL's 53300 (too many connections).
    assertThat(DatabaseDiagnostics.sqlDiagnostic(new SQLException("private-value", "53300", 12)))
        .isEqualTo(new DatabaseDiagnostics.SqlDiagnostic("53300", 12));
    for (String notACode : new String[] {null, "", "5330", "533000", "token", "53 00", "53_00"}) {
      assertThat(DatabaseDiagnostics.sqlDiagnostic(new SQLException("private-value", notACode, 12)))
          .isEqualTo(new DatabaseDiagnostics.SqlDiagnostic("unavailable", 12));
    }
  }

  @Test
  void onlyLockAndSerializationFailuresCountAsConcurrencyConflicts() {
    for (Dialect dialect : Dialect.values()) {
      // The standard transaction-rollback class, also when a library wraps the driver's exception.
      assertThat(conflict(new SQLException("private", "40001", 0), dialect)).isTrue();
      assertThat(conflict(new RuntimeException(new SQLException("private", "40P01", 0)), dialect))
          .isTrue();
      assertThat(conflict(new SQLException("private", "23505", 0), dialect)).isFalse();
      assertThat(conflict(new SQLException("private", "08006", 0), dialect)).isFalse();
      assertThat(conflict(new SQLException("private"), dialect)).isFalse();
      assertThat(conflict(new IllegalStateException("private"), dialect)).isFalse();
    }
    assertThat(conflict(new SQLException("private", "55P03", 0), Dialect.POSTGRESQL)).isTrue();
    assertThat(conflict(new SQLException("private", "HY000", 1205), Dialect.MYSQL)).isTrue();
    assertThat(conflict(new SQLException("private", "HY000", 1213), Dialect.MARIADB)).isTrue();
    assertThat(conflict(new SQLException("private", null, 5), Dialect.SQLITE)).isTrue();
    assertThat(conflict(new SQLException("private", null, 517), Dialect.SQLITE)).isTrue();
    assertThat(conflict(new SQLException("private", null, 262), Dialect.SQLITE)).isTrue();
    // The same vendor numbers mean something else on another engine.
    assertThat(conflict(new SQLException("private", "HY000", 1205), Dialect.POSTGRESQL)).isFalse();
    assertThat(conflict(new SQLException("private", null, 5), Dialect.MYSQL)).isFalse();
    assertThat(conflict(new SQLException("private", null, 19), Dialect.SQLITE)).isFalse();
  }

  private static boolean conflict(Throwable failure, Dialect dialect) {
    return DatabaseDiagnostics.concurrencyConflict(failure, dialect);
  }

  @Test
  void aCallerDataSourceSessionExceptionIsNotTrustedAsASafeCodecFailure() throws Exception {
    DataSource source = mock(DataSource.class);
    SessionException original =
        new SessionException("private-data-source", new SQLException("private-driver-cause"));
    when(source.getConnection()).thenThrow(original);
    SessionException failure =
        assertThrows(
            SessionException.class,
            () -> service(source).getSession("app", "user", "id", Optional.empty()).blockingGet());
    assertThat(failure).isNotSameInstanceAs(original);
    assertThat(failure).hasMessageThat().isEqualTo("Unable to borrow a database connection.");
    assertThat(failure).hasCauseThat().isNull();
    assertThat(failure.getSuppressed()).isEmpty();
  }

  @Test
  void unexpectedRuntimeFailureIsMappedWithoutItsOriginalMessage() throws Exception {
    DataSource source = mock(DataSource.class);
    when(source.getConnection()).thenThrow(new NullPointerException("private-npe"));
    SessionException failure =
        assertThrows(
            SessionException.class,
            () -> service(source).getSession("app", "user", "id", Optional.empty()).blockingGet());
    assertThat(failure).hasMessageThat().isEqualTo("Unable to borrow a database connection.");
    assertThat(failure).hasCauseThat().isNull();
  }

  @Test
  void callerTransactionsHaveASpecificSafeDiagnostic() throws Exception {
    Connection connection = mock(Connection.class);
    DataSource source = mock(DataSource.class);
    when(source.getConnection()).thenReturn(connection);
    SessionException failure =
        assertThrows(
            SessionException.class,
            () -> service(source).getSession("app", "user", "id", Optional.empty()).blockingGet());
    assertThat(failure).hasMessageThat().contains("auto-commit");
    verify(connection).close();
  }

  @Test
  void readOnlyConnectionRejectionIsDistinctFromSchemaOrSqlErrors() throws Exception {
    Connection connection = mock(Connection.class);
    DataSource source = mock(DataSource.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.isReadOnly()).thenReturn(true);
    SessionException failure =
        assertThrows(
            SessionException.class,
            () -> service(source).createSession("app", "user").blockingGet());
    assertThat(failure).hasMessageThat().contains("writable");
    verify(connection).close();
  }

  private static DatabaseSessionService service(DataSource source) {
    return DatabaseSessionService.builder(source).dialect(Dialect.SQLITE).build();
  }
}
