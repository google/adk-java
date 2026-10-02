/*
 * Copyright 2025 Google LLC
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
package com.google.adk.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Centralized configuration for integration test databases.
 *
 * <p>These connection strings assume test databases are running via
 * scripts/docker-compose.test.yml:
 *
 * <pre>{@code
 * docker-compose -f scripts/docker-compose.test.yml up -d
 * }</pre>
 */
public final class TestDatabaseConfig {

  private TestDatabaseConfig() {}

  // MySQL Test Database Configuration
  public static final String MYSQL_HOST = "localhost";
  public static final int MYSQL_PORT = 3306;
  public static final String MYSQL_DATABASE = "adk_test";
  public static final String MYSQL_USER = "adk_user";
  public static final String MYSQL_PASSWORD = "adk_password";
  public static final String MYSQL_JDBC_URL =
      String.format(
          "jdbc:mysql://%s:%d/%s?user=%s&password=%s&useSSL=false&allowPublicKeyRetrieval=true",
          MYSQL_HOST, MYSQL_PORT, MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD);

  // PostgreSQL Test Database Configuration
  public static final String POSTGRES_HOST = "localhost";
  public static final int POSTGRES_PORT = 5432;
  public static final String POSTGRES_DATABASE = "adk_test";
  public static final String POSTGRES_USER = "adk_user";
  public static final String POSTGRES_PASSWORD = "adk_password";
  public static final String POSTGRES_JDBC_URL =
      String.format(
          "jdbc:postgresql://%s:%d/%s?user=%s&password=%s",
          POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DATABASE, POSTGRES_USER, POSTGRES_PASSWORD);

  // Cloud Spanner Emulator Configuration
  public static final String SPANNER_HOST = "localhost";
  public static final int SPANNER_PORT = 9010;
  public static final String SPANNER_PROJECT = "test-project";
  public static final String SPANNER_INSTANCE = "test-instance";
  public static final String SPANNER_DATABASE = "test-db";
  public static final String SPANNER_JDBC_URL =
      String.format(
          "jdbc:cloudspanner://%s:%d/projects/%s/instances/%s/databases/%s?autoConfigEmulator=true",
          SPANNER_HOST, SPANNER_PORT, SPANNER_PROJECT, SPANNER_INSTANCE, SPANNER_DATABASE);

  /**
   * Checks if MySQL test database is available.
   *
   * @return true if connection succeeds, false otherwise
   */
  public static boolean isMySQLAvailable() {
    try (Connection conn = DriverManager.getConnection(MYSQL_JDBC_URL)) {
      return conn.isValid(2);
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * Checks if PostgreSQL test database is available.
   *
   * @return true if connection succeeds, false otherwise
   */
  public static boolean isPostgreSQLAvailable() {
    try (Connection conn = DriverManager.getConnection(POSTGRES_JDBC_URL)) {
      return conn.isValid(2);
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * Checks if Cloud Spanner emulator is available.
   *
   * @return true if connection succeeds, false otherwise
   */
  public static boolean isSpannerAvailable() {
    try (Connection conn = DriverManager.getConnection(SPANNER_JDBC_URL)) {
      return conn.isValid(2);
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * Returns a helpful message for skipped tests when database is not available.
   *
   * @param databaseName The name of the database (MySQL, PostgreSQL, or Spanner)
   * @return A message explaining how to start the database
   */
  public static String getDatabaseNotAvailableMessage(String databaseName) {
    if ("Spanner".equalsIgnoreCase(databaseName)) {
      return "Cloud Spanner emulator not available. Start it with: "
          + "docker run -d -p 9010:9010 -p 9020:9020 gcr.io/cloud-spanner-emulator/emulator && "
          + "export SPANNER_EMULATOR_HOST=localhost:9010";
    }
    return String.format(
        "%s test database not available. Start it with: "
            + "docker-compose -f docker-compose.test.yml up -d %s-test",
        databaseName, databaseName.toLowerCase());
  }
}
