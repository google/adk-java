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
package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.sessions.dialect.DialectDetector;
import com.google.adk.sessions.dialect.H2Dialect;
import com.google.adk.sessions.dialect.MySqlDialect;
import com.google.adk.sessions.dialect.PostgresDialect;
import com.google.adk.sessions.dialect.SpannerDialect;
import com.google.adk.sessions.dialect.SqlDialect;
import org.junit.jupiter.api.Test;

public class DialectDetectorTest {

  @Test
  public void testDetectPostgreSQLDialectFromUrl() {
    String url = "jdbc:postgresql://localhost:5432/testdb";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(PostgresDialect.class, dialect.getClass());
    assertEquals("PostgreSQL", dialect.dialectName());
  }

  @Test
  public void testDetectMySQLDialectFromUrl() {
    String url = "jdbc:mysql://localhost:3306/testdb";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(MySqlDialect.class, dialect.getClass());
    assertEquals("MySQL", dialect.dialectName());
  }

  @Test
  public void testDetectH2DialectFromUrl() {
    String url = "jdbc:h2:mem:testdb";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(H2Dialect.class, dialect.getClass());
    assertEquals("H2", dialect.dialectName());
  }

  @Test
  public void testDetectSpannerDialectFromUrl() {
    String url = "jdbc:cloudspanner:/projects/test/instances/test/databases/test";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(SpannerDialect.class, dialect.getClass());
    assertEquals("Cloud Spanner", dialect.dialectName());
  }

  @Test
  public void testDetectDialectWithParametersInUrl() {
    String url = "jdbc:postgresql://localhost:5432/testdb?user=admin&password=secret";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(PostgresDialect.class, dialect.getClass());
  }

  @Test
  public void testDetectDialectUnsupportedDatabase() {
    String url = "jdbc:oracle:thin:@localhost:1521:testdb";
    assertThrows(IllegalArgumentException.class, () -> DialectDetector.detectFromJdbcUrl(url));
  }

  @Test
  public void testDetectH2InMemoryDatabase() {
    String url = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(H2Dialect.class, dialect.getClass());
  }

  @Test
  public void testDetectH2FileDatabase() {
    String url = "jdbc:h2:file:/data/testdb";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(H2Dialect.class, dialect.getClass());
  }

  @Test
  public void testDetectPostgreSQLWithSSL() {
    String url =
        "jdbc:postgresql://localhost:5432/testdb?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(PostgresDialect.class, dialect.getClass());
  }

  @Test
  public void testDetectMySQLWithUTF8() {
    String url = "jdbc:mysql://localhost:3306/testdb?useUnicode=true&characterEncoding=UTF-8";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(MySqlDialect.class, dialect.getClass());
  }

  @Test
  public void testDetectDialectCaseInsensitive() {
    String urlUpper = "JDBC:POSTGRESQL://localhost:5432/testdb";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(urlUpper);
    assertEquals(PostgresDialect.class, dialect.getClass());
  }

  @Test
  public void testDetectSpannerWithComplexUrl() {
    String url =
        "jdbc:cloudspanner:/projects/my-project/instances/my-instance/databases/my-database?credentials=/path/to/credentials.json";
    SqlDialect dialect = DialectDetector.detectFromJdbcUrl(url);
    assertEquals(SpannerDialect.class, dialect.getClass());
  }
}
