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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.adk.platform.UuidProvider;
import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Verifies configuration and borrowed-connection ownership before persistence is wired in. */
final class DatabaseSessionServiceConfigurationTest {
  @Test
  void constructorDoesNotBorrowAConnectionOrMigrate() {
    DataSource dataSource = mock(DataSource.class);

    DatabaseSessionService service = new DatabaseSessionService(dataSource);

    assertThat(service.dataSource()).isSameInstanceAs(dataSource);
    assertThat(service.autoMigrate()).isFalse();
    assertThat(service.instantSource()).isNotNull();
    assertThat(service.uuidProvider()).isSameInstanceAs(UuidProvider.SYSTEM);
    assertThat(service.statementTimeout()).isEqualTo(Duration.ofSeconds(15));
    verifyNoInteractions(dataSource);
  }

  @Test
  void statementTimeoutIsWholeSecondsWithinWhatEveryDialectCanExpress() {
    DataSource dataSource = mock(DataSource.class);
    DatabaseSessionService.Builder builder = DatabaseSessionService.builder(dataSource);

    assertThat(builder.statementTimeout(Duration.ofSeconds(1)).build().statementTimeout())
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(builder.statementTimeout(Duration.ofMinutes(2)).build().statementTimeout())
        .isEqualTo(Duration.ofSeconds(120));
    assertThat(builder.statementTimeout(Duration.ofSeconds(2_147_483)).build().statementTimeout())
        .isEqualTo(Duration.ofSeconds(2_147_483));
    for (Duration invalid :
        new Duration[] {
          null,
          Duration.ZERO,
          Duration.ofSeconds(-1),
          Duration.ofMillis(999),
          Duration.ofMillis(1500),
          Duration.ofSeconds(2_147_484)
        }) {
      SessionException error =
          assertThrows(SessionException.class, () -> builder.statementTimeout(invalid));
      assertThat(error).hasMessageThat().contains("statementTimeout");
    }
    // A rejected value leaves the last accepted one in place.
    assertThat(builder.build().statementTimeout()).isEqualTo(Duration.ofSeconds(2_147_483));
    verifyNoInteractions(dataSource);
  }

  @Test
  void explicitDialectDoesNotInspectTheDataSource() {
    DataSource dataSource = mock(DataSource.class);

    DatabaseSessionService service =
        DatabaseSessionService.builder(dataSource).dialect(Dialect.MARIADB).build();

    assertThat(service.resolveDialect()).isEqualTo(Dialect.MARIADB);
    verifyNoInteractions(dataSource);
  }

  @Test
  void autoMigrationIsOnlyAnOptInSettingAtConstructionTime() {
    DataSource dataSource = mock(DataSource.class);

    DatabaseSessionService service =
        DatabaseSessionService.builder(dataSource).autoMigrate(true).build();

    assertThat(service.autoMigrate()).isTrue();
    verifyNoInteractions(dataSource);
  }

  @Test
  void providersAreRetainedButNotInvokedDuringConstruction() {
    DataSource dataSource = mock(DataSource.class);
    InstantSource clock = mock(InstantSource.class);
    UuidProvider identifiers = mock(UuidProvider.class);

    DatabaseSessionService service =
        DatabaseSessionService.builder(dataSource)
            .instantSource(clock)
            .uuidProvider(identifiers)
            .build();

    assertThat(service.instantSource()).isSameInstanceAs(clock);
    assertThat(service.uuidProvider()).isSameInstanceAs(identifiers);
    verifyNoInteractions(dataSource, clock, identifiers);
  }

  @Test
  void reusingABuilderDoesNotMutatePreviouslyBuiltServices() {
    DataSource dataSource = mock(DataSource.class);
    InstantSource firstClock = mock(InstantSource.class);
    InstantSource secondClock = mock(InstantSource.class);
    UuidProvider firstIds = mock(UuidProvider.class);
    UuidProvider secondIds = mock(UuidProvider.class);
    DatabaseSessionService.Builder builder =
        DatabaseSessionService.builder(dataSource)
            .dialect(Dialect.SQLITE)
            .instantSource(firstClock)
            .uuidProvider(firstIds);
    DatabaseSessionService first = builder.build();

    DatabaseSessionService second =
        builder
            .dialect(Dialect.POSTGRESQL)
            .autoMigrate(true)
            .instantSource(secondClock)
            .uuidProvider(secondIds)
            .build();

    assertThat(first.resolveDialect()).isEqualTo(Dialect.SQLITE);
    assertThat(first.autoMigrate()).isFalse();
    assertThat(first.instantSource()).isSameInstanceAs(firstClock);
    assertThat(first.uuidProvider()).isSameInstanceAs(firstIds);
    assertThat(second.resolveDialect()).isEqualTo(Dialect.POSTGRESQL);
    assertThat(second.autoMigrate()).isTrue();
    assertThat(second.instantSource()).isSameInstanceAs(secondClock);
    assertThat(second.uuidProvider()).isSameInstanceAs(secondIds);
    verifyNoInteractions(dataSource, firstClock, secondClock, firstIds, secondIds);
  }

  @Test
  void nullDataSourceIsRejectedByBothEntryPoints() {
    assertThrows(SessionException.class, () -> new DatabaseSessionService((DataSource) null));
    assertThrows(SessionException.class, () -> DatabaseSessionService.builder(null));
  }

  @Test
  void nullBuilderComponentsAreRejected() {
    DatabaseSessionService.Builder builder = DatabaseSessionService.builder(mock(DataSource.class));

    assertThrows(SessionException.class, () -> builder.dialect(null));
    assertThrows(SessionException.class, () -> builder.instantSource(null));
    assertThrows(SessionException.class, () -> builder.uuidProvider(null));
  }

  @Test
  void defaultDialectDetectionIsLazyAndCachedPerService() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
    DatabaseSessionService service = new DatabaseSessionService(dataSource);
    verifyNoInteractions(dataSource);

    assertThat(service.resolveDialect()).isEqualTo(Dialect.POSTGRESQL);
    assertThat(service.resolveDialect()).isEqualTo(Dialect.POSTGRESQL);

    verify(dataSource).getConnection();
    verify(connection).close();
  }

  @Test
  void failedDetectionDoesNotPoisonTheServicePermanently() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection())
        .thenThrow(new SQLException("private driver details"))
        .thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("MySQL");
    DatabaseSessionService service = new DatabaseSessionService(dataSource);

    assertThrows(SessionException.class, service::resolveDialect);
    assertThat(service.resolveDialect()).isEqualTo(Dialect.MYSQL);
    assertThat(service.resolveDialect()).isEqualTo(Dialect.MYSQL);

    verify(dataSource, times(2)).getConnection();
    verify(connection).close();
  }

  @Test
  void dialectIsNotCachedWhenReturningTheConnectionFails() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection failedConnection = mock(Connection.class);
    Connection recoveredConnection = mock(Connection.class);
    DatabaseMetaData firstMetadata = mock(DatabaseMetaData.class);
    DatabaseMetaData recoveredMetadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(failedConnection, recoveredConnection);
    when(failedConnection.getMetaData()).thenReturn(firstMetadata);
    when(recoveredConnection.getMetaData()).thenReturn(recoveredMetadata);
    when(firstMetadata.getDatabaseProductName()).thenReturn("SQLite");
    when(recoveredMetadata.getDatabaseProductName()).thenReturn("PostgreSQL");
    doThrow(new SQLException("private cleanup details")).when(failedConnection).close();
    DatabaseSessionService service = new DatabaseSessionService(dataSource);

    assertThrows(SessionException.class, service::resolveDialect);
    assertThat(service.resolveDialect()).isEqualTo(Dialect.POSTGRESQL);
    assertThat(service.resolveDialect()).isEqualTo(Dialect.POSTGRESQL);

    verify(dataSource, times(2)).getConnection();
    verify(failedConnection).close();
    verify(recoveredConnection).close();
  }

  @Test
  void concurrentResolutionBorrowsOnlyOneConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("SQLite");
    DatabaseSessionService service = new DatabaseSessionService(dataSource);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Dialect> first = workers.submit(service::resolveDialect);
      Future<Dialect> second = workers.submit(service::resolveDialect);

      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(Dialect.SQLITE);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(Dialect.SQLITE);
      verify(dataSource).getConnection();
      verify(connection).close();
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
