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
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.google.adk.sessions.DatabaseSessionService.Dialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Opt-in real-engine tests. Missing Docker/images fail; nothing is silently skipped. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class DatabaseSessionServiceIT {
  private static final String POSTGRESQL =
      "postgres:17.10-bookworm@sha256:9b18b78397054fce88a9552e9d5a3ad5bb7fd258c5b3cc1c5028e46373d6ea8f";
  private static final String POSTGRESQL18 =
      "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382";
  private static final String MYSQL =
      "mysql:8.4.11@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364";
  private static final String MARIADB =
      "mariadb:11.8.7@sha256:78185355dd49b54dd6909072531ce8d7e06aa0eccd7aa5b23c93ebb7e34c5aaa";
  private static final String MARIADB114 =
      "mariadb:11.4.12@sha256:4f1d8d202fcf7bcb3902f63af09f9c1a050c2922a89652f22abaec0d4f015e83";
  private final List<Fixture> fixtures = new ArrayList<>();

  @TestFactory
  Stream<DynamicContainer> databaseContracts() {
    String configured = System.getProperty("adk.test.backends", "postgresql,mysql,mariadb");
    if (configured.isBlank())
      throw new IllegalArgumentException("At least one test backend is required.");
    return Arrays.stream(configured.split(","))
        .map(String::strip)
        .distinct()
        .map(
            backend -> {
              Fixture fixture = start(backend);
              Stream<DynamicNode> checks =
                  Stream.concat(
                      Stream.of(
                          dynamicTest(
                              "schema migration repeatability and actual driver",
                              () -> {
                                DatabaseSessionService service =
                                    new DatabaseSessionService(fixture.source);
                                assertThat(service.resolveDialect()).isEqualTo(fixture.dialect);
                                service.migrate().blockingAwait();
                                service.migrate().blockingAwait();
                                try (Connection connection = fixture.source.getConnection()) {
                                  var metadata = connection.getMetaData();
                                  String timezone;
                                  try (var query = connection.createStatement();
                                      var row =
                                          query.executeQuery(
                                              fixture.dialect == Dialect.POSTGRESQL
                                                  ? "SHOW TimeZone"
                                                  : "SELECT @@session.time_zone")) {
                                    assertThat(row.next()).isTrue();
                                    timezone = row.getString(1);
                                  }
                                  assertThat(timezone)
                                      .isEqualTo(
                                          fixture.dialect == Dialect.POSTGRESQL
                                              ? "Pacific/Auckland"
                                              : fixture.dialect == Dialect.MYSQL
                                                  ? "+05:30"
                                                  : "-03:00");
                                  System.out.printf(
                                      "ADK_DATABASE_TEST backend=%s engine=%s version=%s driver=%s java=%s timezone=%s%n",
                                      backend,
                                      metadata.getDatabaseProductName(),
                                      metadata.getDatabaseProductVersion(),
                                      metadata.getDriverVersion(),
                                      System.getProperty("java.version"),
                                      timezone);
                                }
                              })),
                      Stream.concat(
                          new DatabaseSessionContract(fixture.source, fixture.dialect).tests(),
                          new DatabaseSessionJdbcContract(fixture.source, fixture.dialect)
                              .tests()));
              return DynamicContainer.dynamicContainer(
                  backend,
                  Stream.concat(
                      checks,
                      Stream.of(
                          dynamicTest(
                              "isolated consumer: selected driver and plugin only",
                              () ->
                                  ConsumerClasspath.verify(
                                      fixture.container.getJdbcUrl(),
                                      fixture.container.getUsername(),
                                      fixture.container.getPassword(),
                                      fixture.dialect)),
                          dynamicTest("release fixture resources", fixture::close))));
            });
  }

  private Fixture start(String backend) {
    Dialect dialect;
    JdbcDatabaseContainer<?> container;
    switch (backend) {
      case "postgresql", "postgresql18" -> {
        dialect = Dialect.POSTGRESQL;
        container =
            new PostgreSQLContainer(
                    image(backend, backend.equals("postgresql") ? POSTGRESQL : POSTGRESQL18))
                .withEnv("TZ", "Pacific/Auckland")
                .withCommand(
                    "postgres",
                    "-c",
                    "timezone=Pacific/Auckland",
                    "-c",
                    "lock_timeout=10s",
                    "-c",
                    "statement_timeout=15s");
      }
      case "mysql" -> {
        dialect = Dialect.MYSQL;
        container =
            new MySQLContainer(image(backend, MYSQL))
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+05:30",
                    "--innodb-lock-wait-timeout=10",
                    "--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");
      }
      case "mariadb", "mariadb114" -> {
        dialect = Dialect.MARIADB;
        container =
            new MariaDBContainer(image(backend, backend.equals("mariadb") ? MARIADB : MARIADB114))
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--default-time-zone=-03:00",
                    "--innodb-lock-wait-timeout=10",
                    "--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");
      }
      default -> throw new IllegalArgumentException("Unknown test backend.");
    }
    container.withDatabaseName("adk_sessions");
    container.withUsername("adk_test");
    container.withPassword(UUID.randomUUID().toString());
    container.withStartupTimeout(Duration.ofMinutes(3));
    container.withLabel("org.google.adk.database-session-service", "contract-tests");
    Fixture fixture = new Fixture(container, dialect);
    fixtures.add(fixture); // Register cleanup before startup, including a failed startup.
    container.start();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(container.getJdbcUrl());
    config.setDriverClassName(container.getDriverClassName());
    config.setUsername(container.getUsername());
    config.setPassword(container.getPassword());
    config.setMaximumPoolSize(4);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(15_000);
    config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    config.setConnectionInitSql(
        dialect == Dialect.POSTGRESQL
            ? "SET TIME ZONE 'Pacific/Auckland'"
            : dialect == Dialect.MYSQL ? "SET time_zone='+05:30'" : "SET time_zone='-03:00'");
    fixture.source = new HikariDataSource(config);
    new DatabaseSessionService(fixture.source).migrate().blockingAwait();
    return fixture;
  }

  private static DockerImageName image(String backend, String fallback) {
    String canonical =
        backend.startsWith("postgresql")
            ? "postgres"
            : backend.startsWith("mariadb") ? "mariadb" : "mysql";
    return DockerImageName.parse(System.getProperty("adk.test.image." + backend, fallback))
        .asCompatibleSubstituteFor(canonical);
  }

  @AfterAll
  void closeFixtures() {
    for (Fixture fixture : fixtures) fixture.close();
  }

  private static final class Fixture implements AutoCloseable {
    final JdbcDatabaseContainer<?> container;
    final Dialect dialect;
    HikariDataSource source;

    Fixture(JdbcDatabaseContainer<?> container, Dialect dialect) {
      this.container = container;
      this.dialect = dialect;
    }

    @Override
    public void close() {
      if (source != null) source.close();
      container.stop();
    }
  }
}
