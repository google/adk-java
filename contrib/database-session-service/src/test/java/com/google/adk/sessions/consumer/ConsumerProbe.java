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
package com.google.adk.sessions.consumer;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.DatabaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Standalone consumer process, with no JUnit, pool, Mockito, or Testcontainers on its classpath.
 */
public final class ConsumerProbe {
  private ConsumerProbe() {}

  public static void main(String[] arguments) {
    try {
      DatabaseSessionService.Dialect dialect = DatabaseSessionService.Dialect.valueOf(arguments[0]);
      boolean migrations = Boolean.parseBoolean(arguments[1]);
      absent("com.zaxxer.hikari.HikariDataSource");
      absent("org.testcontainers.DockerClientFactory");
      absent("org.mockito.Mockito");
      absent("org.slf4j.simple.SimpleLoggerFactory");
      String selected =
          switch (dialect) {
            case SQLITE -> "org.sqlite.JDBC";
            case POSTGRESQL -> "org.postgresql.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
          };
      for (String driver :
          new String[] {
            "org.sqlite.JDBC",
            "org.postgresql.Driver",
            "com.mysql.cj.jdbc.Driver",
            "org.mariadb.jdbc.Driver"
          }) {
        if (driver.equals(selected)) Class.forName(driver);
        else absent(driver);
      }
      if (!migrations) absent("org.flywaydb.core.Flyway");
      if (!migrations || dialect != DatabaseSessionService.Dialect.POSTGRESQL)
        absent("org.flywaydb.database.postgresql.PostgreSQLDatabaseType");
      if (!migrations
          || dialect == DatabaseSessionService.Dialect.SQLITE
          || dialect == DatabaseSessionService.Dialect.POSTGRESQL)
        absent("org.flywaydb.database.mysql.MySQLDatabaseType");
      DataSource source =
          source(
              System.getenv("ADK_TEST_JDBC_URL"),
              System.getenv("ADK_TEST_USERNAME"),
              System.getenv("ADK_TEST_PASSWORD"));
      DatabaseSessionService service =
          DatabaseSessionService.builder(source).dialect(dialect).build();
      if (migrations) service.migrate().blockingAwait();
      else {
        try {
          service.migrate().blockingAwait();
          throw new IllegalStateException();
        } catch (SessionException expected) {
          if (expected.getCause() != null) throw new IllegalStateException();
        }
      }
      Session session =
          service.createSession("consumer-" + UUID.randomUUID(), "user").blockingGet();
      service
          .appendEvent(
              session,
              Event.builder()
                  .id("event")
                  .author("agent")
                  .timestamp(1)
                  .actions(EventActions.builder().stateDelta(Map.of("retained", true)).build())
                  .build())
          .blockingGet();
      Session restored =
          new DatabaseSessionService(source).getSession(session.sessionKey(), null).blockingGet();
      if (restored == null
          || restored.immutableEvents().size() != 1
          || !Boolean.TRUE.equals(restored.state().get("retained")))
        throw new IllegalStateException();
      System.out.println("ADK_CONSUMER_OK dialect=" + dialect + " migrations=" + migrations);
    } catch (Exception | LinkageError failure) {
      // Never print raw URLs/passwords or nested JDBC exception messages, even on test failure.
      System.err.println("ADK_CONSUMER_FAILURE type=" + failure.getClass().getName());
      System.exit(1);
    }
  }

  private static void absent(String type) {
    try {
      Class.forName(type);
      throw new IllegalStateException("Unexpected consumer dependency.");
    } catch (ClassNotFoundException expected) {
      /* This dependency must genuinely be absent. */
    }
  }

  private static DataSource source(String url, String user, String password) {
    return new DataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
      }

      @Override
      public Connection getConnection(String username, String secret) throws SQLException {
        return DriverManager.getConnection(url, username, secret);
      }

      @Override
      public PrintWriter getLogWriter() {
        return null;
      }

      @Override
      public void setLogWriter(PrintWriter writer) {}

      @Override
      public int getLoginTimeout() {
        return 0;
      }

      @Override
      public void setLoginTimeout(int timeout) {}

      @Override
      public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
      }

      @Override
      public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException();
      }

      @Override
      public boolean isWrapperFor(Class<?> type) {
        return false;
      }
    };
  }
}
