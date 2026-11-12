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

import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Test-only real JDBC delegation, deterministic fault/barrier hooks, and connection leak checks.
 */
final class JdbcProbe implements DataSource {
  @FunctionalInterface
  interface Hook {
    void execute(String sql, boolean after) throws Exception;
  }

  final List<String> sql = new CopyOnWriteArrayList<>();
  final List<Integer> timeouts = new CopyOnWriteArrayList<>();
  final AtomicInteger borrowed = new AtomicInteger();
  final AtomicInteger closed = new AtomicInteger();
  volatile Hook hook = (sql, after) -> {};
  private final DataSource delegate;
  private final Dialect dialect;

  JdbcProbe(DataSource delegate, Dialect dialect) {
    this.delegate = delegate;
    this.dialect = dialect;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return wrap(delegate.getConnection());
  }

  @Override
  public Connection getConnection(String user, String password) throws SQLException {
    return wrap(delegate.getConnection(user, password));
  }

  private Connection wrap(Connection connection) throws SQLException {
    borrowed.incrementAndGet();
    boolean autoCommit = connection.getAutoCommit();
    boolean readOnly = connection.isReadOnly();
    int isolation = connection.getTransactionIsolation();
    int fk = dialect == Dialect.SQLITE ? pragma(connection, "foreign_keys") : -1;
    int busy = dialect == Dialect.SQLITE ? pragma(connection, "busy_timeout") : -1;
    AtomicBoolean returned = new AtomicBoolean();
    AtomicBoolean aborted = new AtomicBoolean();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("abort")) aborted.set(true);
              if (name.equals("close") && returned.compareAndSet(false, true)) {
                try {
                  if (!aborted.get()) {
                    assertThat(connection.getAutoCommit()).isEqualTo(autoCommit);
                    assertThat(connection.isReadOnly()).isEqualTo(readOnly);
                    assertThat(connection.getTransactionIsolation()).isEqualTo(isolation);
                    if (dialect == Dialect.SQLITE) {
                      assertThat(pragma(connection, "foreign_keys")).isEqualTo(fk);
                      assertThat(pragma(connection, "busy_timeout")).isEqualTo(busy);
                      try (Statement statement = connection.createStatement()) {
                        statement.execute("BEGIN");
                        statement.execute("ROLLBACK");
                      }
                    }
                  }
                } finally {
                  closed.incrementAndGet();
                  connection.close();
                }
                hook.execute("CLOSE CONNECTION", true);
                return null;
              }
              if (name.equals("commit") || name.equals("rollback")) {
                String command = name.toUpperCase(java.util.Locale.ROOT);
                sql.add(command);
                hook.execute(command, false);
                Object result = invoke(connection, method, args);
                hook.execute(command, true);
                return result;
              }
              Object result = invoke(connection, method, args);
              if (result instanceof Statement statement
                  && (name.equals("createStatement") || name.equals("prepareStatement"))) {
                String prepared = name.equals("prepareStatement") ? (String) args[0] : null;
                Class<?> type =
                    prepared == null ? Statement.class : java.sql.PreparedStatement.class;
                AtomicBoolean executedCommit = new AtomicBoolean();
                return Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] {type},
                    (statementProxy, operation, parameters) -> {
                      if (operation.getName().startsWith("execute")) {
                        String command = prepared == null ? (String) parameters[0] : prepared;
                        sql.add(command);
                        hook.execute(command, false);
                        Object answer = invoke(statement, operation, parameters);
                        executedCommit.set(command.equals("COMMIT"));
                        hook.execute(command, true);
                        return answer;
                      }
                      if (operation.getName().equals("setQueryTimeout")) {
                        timeouts.add((Integer) parameters[0]);
                      }
                      Object answer = invoke(statement, operation, parameters);
                      if (operation.getName().equals("close") && executedCommit.get()) {
                        hook.execute("CLOSE COMMIT STATEMENT", true);
                      }
                      return answer;
                    });
              }
              return result;
            });
  }

  private static Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(receiver, args);
    } catch (InvocationTargetException wrapped) {
      throw wrapped.getCause();
    }
  }

  private static int pragma(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      if (!result.next()) throw new SQLException();
      return result.getInt(1);
    }
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter writer) throws SQLException {
    delegate.setLogWriter(writer);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public void setLoginTimeout(int timeout) throws SQLException {
    delegate.setLoginTimeout(timeout);
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    return delegate.unwrap(type);
  }

  @Override
  public boolean isWrapperFor(Class<?> type) throws SQLException {
    return delegate.isWrapperFor(type);
  }
}
