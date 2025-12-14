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
package com.google.adk.sessions.db.util;

/** Utility class for detecting the appropriate Hibernate dialect based on the database URL. */
public class DatabaseDialectDetector {

  private DatabaseDialectDetector() {
    // Utility class, no instantiation
  }

  /**
   * Detects the appropriate Hibernate dialect based on the database URL.
   *
   * @param dbUrl The database URL
   * @return The Hibernate dialect class name
   * @throws IllegalArgumentException If the database type is not supported
   */
  public static String detectDialect(String dbUrl) {
    if (dbUrl == null) {
      throw new IllegalArgumentException("Database URL cannot be null");
    }

    // PostgreSQL
    if (dbUrl.startsWith("jdbc:postgresql:")) {
      return "org.hibernate.dialect.PostgreSQLDialect";
    }

    // MySQL
    if (dbUrl.startsWith("jdbc:mysql:")) {
      return "org.hibernate.dialect.MySQLDialect";
    }

    // SQLite
    if (dbUrl.startsWith("jdbc:sqlite:")) {
      return "org.hibernate.dialect.SQLiteDialect";
    }

    // H2 Database
    if (dbUrl.startsWith("jdbc:h2:")) {
      return "org.hibernate.dialect.H2Dialect";
    }

    // Cloud Spanner
    if (dbUrl.startsWith("jdbc:cloudspanner:")) {
      return "com.google.cloud.spanner.hibernate.SpannerDialect";
    }

    throw new IllegalArgumentException("Unsupported database URL: " + maskConnectionUrl(dbUrl));
  }

  /**
   * Masks sensitive information in the database connection URL for logging purposes.
   *
   * @param url The database URL to mask
   * @return A masked version of the URL
   */
  public static String maskConnectionUrl(String url) {
    if (url == null) {
      return null;
    }

    String result = url;
    result = result.replaceAll(":([^:/@]+)@", "password:****@");
    result = result.replaceAll("password=([^&]*)", "password=****");
    return result;
  }
}
