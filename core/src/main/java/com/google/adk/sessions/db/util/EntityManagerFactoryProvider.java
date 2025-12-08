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

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider for creating and managing an EntityManagerFactory for the DatabaseSessionService. Uses
 * the singleton pattern to ensure only one EntityManagerFactory exists per persistence unit.
 */
public class EntityManagerFactoryProvider {

  private static final String PERSISTENCE_UNIT_NAME = "adk-sessions";

  /**
   * Creates an EntityManagerFactory with the specified database URL and configuration.
   *
   * @param dbUrl The database URL to connect to
   * @param properties Additional properties for the EntityManagerFactory
   * @return A configured EntityManagerFactory
   */
  public static EntityManagerFactory createEntityManagerFactory(
      String dbUrl, Map<String, Object> properties) {

    Map<String, Object> config = new HashMap<>(properties);

    // Set required properties if not already provided
    if (!config.containsKey("jakarta.persistence.jdbc.url")) {
      config.put("jakarta.persistence.jdbc.url", dbUrl);
    }

    if (!config.containsKey("hibernate.dialect")) {
      config.put("hibernate.dialect", DatabaseDialectDetector.detectDialect(dbUrl));
    }

    // Set default credentials for H2 if not provided
    String dialect = (String) config.get("hibernate.dialect");
    if ("org.hibernate.dialect.H2Dialect".equals(dialect)) {
      if (!config.containsKey("jakarta.persistence.jdbc.user")) {
        config.put("jakarta.persistence.jdbc.user", "sa");
      }
      if (!config.containsKey("jakarta.persistence.jdbc.password")) {
        config.put("jakarta.persistence.jdbc.password", "");
      }
    }

    // Set default schema generation mode if not specified
    // Use validate mode by default to work with Flyway-managed schema
    if (!config.containsKey("hibernate.hbm2ddl.auto")) {
      config.put("hibernate.hbm2ddl.auto", "validate");
    }

    // Set default connection pooling properties
    if (!config.containsKey("hibernate.connection.provider_class")) {
      config.put(
          "hibernate.connection.provider_class",
          "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
      // Hibernate expects String values in configuration map; provide numbers as strings
      config.put("hibernate.hikari.minimumIdle", "5");
      config.put("hibernate.hikari.maximumPoolSize", "20");
      config.put("hibernate.hikari.idleTimeout", "30000");
    }

    // Create the EntityManagerFactory
    return Persistence.createEntityManagerFactory(PERSISTENCE_UNIT_NAME, config);
  }
}
