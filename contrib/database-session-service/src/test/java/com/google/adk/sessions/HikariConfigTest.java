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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class HikariConfigTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:hikari_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private DatabaseSessionService sessionService;

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testDefaultHikariConfig() throws Exception {
    sessionService = new DatabaseSessionService(TEST_DB_URL);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertNotNull(dataSource);
    assertEquals(10, dataSource.getMaximumPoolSize());
    assertEquals(2, dataSource.getMinimumIdle());
    assertEquals(30000, dataSource.getConnectionTimeout());
    assertEquals(600000, dataSource.getIdleTimeout());
    assertEquals(1800000, dataSource.getMaxLifetime());
  }

  @Test
  public void testCustomMaximumPoolSize() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maximumPoolSize", 20);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(20, dataSource.getMaximumPoolSize());
    assertEquals(2, dataSource.getMinimumIdle());
  }

  @Test
  public void testCustomMinimumIdle() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.minimumIdle", 5);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(10, dataSource.getMaximumPoolSize());
    assertEquals(5, dataSource.getMinimumIdle());
  }

  @Test
  public void testCustomConnectionTimeout() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.connectionTimeout", 60000L);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(60000, dataSource.getConnectionTimeout());
  }

  @Test
  public void testCustomIdleTimeout() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.idleTimeout", 300000L);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(300000, dataSource.getIdleTimeout());
  }

  @Test
  public void testCustomMaxLifetime() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maxLifetime", 900000L);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(900000, dataSource.getMaxLifetime());
  }

  @Test
  public void testAllCustomHikariProperties() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maximumPoolSize", 25);
    properties.put("hikari.minimumIdle", 10);
    properties.put("hikari.connectionTimeout", 45000L);
    properties.put("hikari.idleTimeout", 400000L);
    properties.put("hikari.maxLifetime", 1200000L);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(25, dataSource.getMaximumPoolSize());
    assertEquals(10, dataSource.getMinimumIdle());
    assertEquals(45000, dataSource.getConnectionTimeout());
    assertEquals(400000, dataSource.getIdleTimeout());
    assertEquals(1200000, dataSource.getMaxLifetime());
  }

  @Test
  public void testNonHikariPropertiesArePassedToDataSource() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("cachePrepStmts", "true");
    properties.put("prepStmtCacheSize", 250);
    properties.put("hikari.maximumPoolSize", 15);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(15, dataSource.getMaximumPoolSize());
  }

  @Test
  public void testInvalidIntegerPropertyUsesDefault() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maximumPoolSize", "invalid");

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(10, dataSource.getMaximumPoolSize());
  }

  @Test
  public void testInvalidLongPropertyUsesDefault() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.connectionTimeout", "invalid");

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(30000, dataSource.getConnectionTimeout());
  }

  @Test
  public void testIntegerAsNumberType() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maximumPoolSize", Integer.valueOf(30));

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(30, dataSource.getMaximumPoolSize());
  }

  @Test
  public void testLongAsNumberType() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.connectionTimeout", Long.valueOf(50000L));

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(50000, dataSource.getConnectionTimeout());
  }

  @Test
  public void testStringNumberConversion() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maximumPoolSize", "35");
    properties.put("hikari.connectionTimeout", "40000");

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(35, dataSource.getMaximumPoolSize());
    assertEquals(40000, dataSource.getConnectionTimeout());
  }

  @Test
  public void testNullPropertyUsesDefault() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("hikari.maximumPoolSize", null);

    sessionService = new DatabaseSessionService(TEST_DB_URL, properties);

    HikariDataSource dataSource = getDataSource(sessionService);
    assertEquals(10, dataSource.getMaximumPoolSize());
  }

  private HikariDataSource getDataSource(DatabaseSessionService service) throws Exception {
    Field dataSourceField = DatabaseSessionService.class.getDeclaredField("dataSource");
    dataSourceField.setAccessible(true);
    return (HikariDataSource) dataSourceField.get(service);
  }
}
