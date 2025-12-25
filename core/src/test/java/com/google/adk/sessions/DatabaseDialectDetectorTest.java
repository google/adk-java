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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.sessions.db.util.DatabaseDialectDetector;
import org.junit.jupiter.api.Test;

public class DatabaseDialectDetectorTest {

  @Test
  public void testDetectPostgreSQLDialect() {
    String url = "jdbc:postgresql://localhost:5432/testdb";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.PostgreSQLDialect", dialect);
  }

  @Test
  public void testDetectMySQLDialect() {
    String url = "jdbc:mysql://localhost:3306/testdb";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.MySQLDialect", dialect);
  }

  @Test
  public void testDetectH2Dialect() {
    String url = "jdbc:h2:mem:testdb";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.H2Dialect", dialect);
  }

  @Test
  public void testDetectSQLiteDialect() {
    String url = "jdbc:sqlite:/path/to/database.db";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.SQLiteDialect", dialect);
  }

  @Test
  public void testDetectSpannerDialect() {
    String url = "jdbc:cloudspanner:/projects/test/instances/test/databases/test";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("com.google.cloud.spanner.hibernate.SpannerDialect", dialect);
  }

  @Test
  public void testDetectDialectWithParameters() {
    String url = "jdbc:postgresql://localhost:5432/testdb?user=admin&password=secret";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.PostgreSQLDialect", dialect);
  }

  @Test
  public void testDetectDialectNullUrl() {
    assertThrows(IllegalArgumentException.class, () -> DatabaseDialectDetector.detectDialect(null));
  }

  @Test
  public void testDetectDialectUnsupportedDatabase() {
    String url = "jdbc:oracle:thin:@localhost:1521:testdb";
    assertThrows(IllegalArgumentException.class, () -> DatabaseDialectDetector.detectDialect(url));
  }

  @Test
  public void testDetectDialectInvalidUrl() {
    String url = "not-a-jdbc-url";
    assertThrows(IllegalArgumentException.class, () -> DatabaseDialectDetector.detectDialect(url));
  }

  @Test
  public void testMaskConnectionUrlWithPassword() {
    String url = "jdbc:postgresql://localhost:5432/testdb?user=admin&password=secret123";
    String masked = DatabaseDialectDetector.maskConnectionUrl(url);
    assertFalse(masked.contains("secret123"));
    assertTrue(masked.contains("password=****"));
  }

  @Test
  public void testMaskConnectionUrlWithColonPassword() {
    String url = "jdbc:mysql://admin:secret456@localhost:3306/testdb";
    String masked = DatabaseDialectDetector.maskConnectionUrl(url);
    assertFalse(masked.contains("secret456"));
    assertTrue(masked.contains("password:****@"));
  }

  @Test
  public void testMaskConnectionUrlMultiplePasswords() {
    String url =
        "jdbc:postgresql://localhost:5432/testdb?password=first&user=admin&password=second";
    String masked = DatabaseDialectDetector.maskConnectionUrl(url);
    assertFalse(masked.contains("first"));
    assertFalse(masked.contains("second"));
    assertTrue(masked.contains("password=****"));
  }

  @Test
  public void testMaskConnectionUrlNoPassword() {
    String url = "jdbc:h2:mem:testdb";
    String masked = DatabaseDialectDetector.maskConnectionUrl(url);
    assertEquals(url, masked);
  }

  @Test
  public void testMaskConnectionUrlNull() {
    String masked = DatabaseDialectDetector.maskConnectionUrl(null);
    assertNull(masked);
  }

  @Test
  public void testMaskConnectionUrlWithUserButNoPassword() {
    String url = "jdbc:postgresql://localhost:5432/testdb?user=admin";
    String masked = DatabaseDialectDetector.maskConnectionUrl(url);
    assertEquals(url, masked);
  }

  @Test
  public void testMaskConnectionUrlComplexCase() {
    String url =
        "jdbc:mysql://user:mypassword123@host:3306/db?password=anotherpass&ssl=true&password=thirdpass";
    String masked = DatabaseDialectDetector.maskConnectionUrl(url);
    assertFalse(masked.contains("mypassword123"));
    assertFalse(masked.contains("anotherpass"));
    assertFalse(masked.contains("thirdpass"));
    assertTrue(masked.contains("password:****@"));
    assertTrue(masked.contains("password=****"));
  }

  @Test
  public void testH2InMemoryDatabase() {
    String url = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.H2Dialect", dialect);
  }

  @Test
  public void testH2FileDatabase() {
    String url = "jdbc:h2:file:/data/testdb";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.H2Dialect", dialect);
  }

  @Test
  public void testPostgreSQLWithSSL() {
    String url =
        "jdbc:postgresql://localhost:5432/testdb?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.PostgreSQLDialect", dialect);
  }

  @Test
  public void testMySQLWithUTF8() {
    String url = "jdbc:mysql://localhost:3306/testdb?useUnicode=true&characterEncoding=UTF-8";
    String dialect = DatabaseDialectDetector.detectDialect(url);
    assertEquals("org.hibernate.dialect.MySQLDialect", dialect);
  }

  @Test
  public void testEmptyStringUrl() {
    assertThrows(IllegalArgumentException.class, () -> DatabaseDialectDetector.detectDialect(""));
  }
}
