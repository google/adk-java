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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

final class DatabaseSessionValidationTest {
  @Test
  void invalidArgumentsDoNotTriggerEvenOptedInDdl() {
    DataSource source = mock(DataSource.class);
    DatabaseSessionService service =
        DatabaseSessionService.builder(source)
            .dialect(DatabaseSessionService.Dialect.SQLITE)
            .autoMigrate(true)
            .build();
    var create = service.createSession(null, "u", Map.of(), "id");
    var get = service.getSession("app", null, "id", Optional.empty());
    var delete = service.deleteSession("app", "u", null);
    var list = service.listSessions(null, "u");
    var events = service.listEvents("app", "u", null);
    verifyNoInteractions(source);
    assertThrows(SessionException.class, create::blockingGet);
    assertThrows(SessionException.class, get::blockingGet);
    assertThrows(SessionException.class, delete::blockingAwait);
    assertThrows(SessionException.class, list::blockingGet);
    assertThrows(SessionException.class, events::blockingGet);
    verifyNoInteractions(source);
  }

  @Test
  void brokenProvidersHaveSafeErrorsAndNoDatabaseSideEffects() {
    DataSource source = mock(DataSource.class);
    DatabaseSessionService uuid =
        DatabaseSessionService.builder(source)
            .uuidProvider(
                () -> {
                  throw new IllegalStateException("private-provider");
                })
            .build();
    DatabaseSessionService clock =
        DatabaseSessionService.builder(source)
            .instantSource(
                () -> {
                  throw new IllegalStateException("private-clock");
                })
            .build();
    for (DatabaseSessionService service : new DatabaseSessionService[] {uuid, clock}) {
      SessionException failure =
          assertThrows(
              SessionException.class, () -> service.createSession("app", "u").blockingGet());
      assertThat(failure).hasCauseThat().isNull();
      assertThat(failure).hasMessageThat().doesNotContain("private");
    }
    verifyNoInteractions(source);
  }

  @Test
  void partialAndInvalidEventsNeverBorrowAConnection() {
    DataSource source = mock(DataSource.class);
    DatabaseSessionService service = new DatabaseSessionService(source);
    Session session = Session.builder("id").appName("app").userId("u").build();
    Event event = Event.builder().id("invalid\0event").timestamp(1).partial(true).build();
    assertThat(service.appendEvent(session, event).blockingGet()).isSameInstanceAs(event);
    assertThrows(
        SessionException.class,
        () -> service.appendEvent(session, event.toBuilder().partial(false).build()).blockingGet());
    verifyNoInteractions(source);
  }

  @Test
  void callerTransactionsAreRejectedWithoutCommittingOrRollingThemBack() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    DatabaseSessionService service =
        DatabaseSessionService.builder(source)
            .dialect(DatabaseSessionService.Dialect.SQLITE)
            .build();
    assertThrows(SessionException.class, () -> service.listSessions("app", "u").blockingGet());
    verify(connection, never()).commit();
    verify(connection, never()).rollback();
    verify(connection, never()).setAutoCommit(true);
    verify(connection).close();
  }

  @Test
  void driverFailuresNeverExposeRawMessagesCausesOrSuppressedData() throws Exception {
    DataSource source = mock(DataSource.class);
    SQLException raw = new SQLException("private-password-and-url");
    raw.addSuppressed(new SQLException("private-suppressed"));
    when(source.getConnection()).thenThrow(raw);
    DatabaseSessionService service =
        DatabaseSessionService.builder(source)
            .dialect(DatabaseSessionService.Dialect.SQLITE)
            .build();
    SessionException failure =
        assertThrows(SessionException.class, () -> service.listSessions("app", "u").blockingGet());
    assertThat(failure).hasCauseThat().isNull();
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(failure).hasMessageThat().doesNotContain("private");
  }
}
