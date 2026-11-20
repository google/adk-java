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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Tests metadata-only selection, resource cleanup and safe JDBC error boundaries. */
final class DatabaseDialectTest {
  private static final String PRIVATE_DETAILS = "jdbc:private://caller:secret@host/database";

  @Test
  void recognizesAllFourProductNamesWithoutReadingUrlsOrCredentials() throws Exception {
    for (Map.Entry<String, Dialect> product :
        Map.of(
                " SQLite ", Dialect.SQLITE,
                "PostgreSQL", Dialect.POSTGRESQL,
                "MYSQL", Dialect.MYSQL,
                "mArIaDb", Dialect.MARIADB)
            .entrySet()) {
      DataSource dataSource = mock(DataSource.class);
      Connection connection = mock(Connection.class);
      DatabaseMetaData metadata = mock(DatabaseMetaData.class);
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.getMetaData()).thenReturn(metadata);
      when(metadata.getDatabaseProductName()).thenReturn(product.getKey());

      assertThat(DatabaseDialect.detect(dataSource)).isEqualTo(product.getValue());

      verify(dataSource).getConnection();
      verify(connection).getMetaData();
      verify(metadata).getDatabaseProductName();
      verify(connection).close();
      verifyNoMoreInteractions(dataSource, connection, metadata);
    }
  }

  @Test
  void unsupportedProductDoesNotLeakItsNameAndStillClosesTheConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn(PRIVATE_DETAILS);

    SessionException error =
        assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource));

    assertThat(error).hasMessageThat().startsWith("Unsupported database product");
    assertSafe(error);
    verify(connection).close();
  }

  @Test
  void nullProductIsNotTreatedAsASupportedDatabase() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(connection).close();
  }

  @Test
  void connectionFailureIsMappedWithoutExposingTheDriverCause() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException(PRIVATE_DETAILS));

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
  }

  @Test
  void nullConnectionFromABrokenDataSourceIsAlsoMapped() throws Exception {
    DataSource dataSource = mock(DataSource.class);

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(dataSource).getConnection();
    verifyNoMoreInteractions(dataSource);
  }

  @Test
  void nullMetadataFromABrokenDriverStillClosesTheConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(connection).close();
  }

  @Test
  void metadataFailureClosesTheBorrowedConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenThrow(new SQLException(PRIVATE_DETAILS));

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(connection).close();
  }

  @Test
  void productReadFailureClosesTheBorrowedConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenThrow(new SQLException(PRIVATE_DETAILS));

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(connection).close();
  }

  @Test
  void cleanupFailureIsNotMistakenForSuccessfulDetection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("MySQL");
    doThrow(new SQLException(PRIVATE_DETAILS)).when(connection).close();

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(connection).close();
  }

  @Test
  void uncheckedDriverFailureIsAlsoMappedAndClosesTheConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenThrow(new IllegalStateException(PRIVATE_DETAILS));

    assertSafe(assertThrows(SessionException.class, () -> DatabaseDialect.detect(dataSource)));
    verify(connection).close();
  }

  private static void assertSafe(SessionException error) {
    assertThat(error).hasMessageThat().doesNotContain(PRIVATE_DETAILS);
    assertThat(error).hasCauseThat().isNull();
    assertThat(error.getSuppressed()).isEmpty();
  }
}
