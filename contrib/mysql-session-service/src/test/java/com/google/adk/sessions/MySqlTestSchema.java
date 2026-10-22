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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

final class MySqlTestSchema {

  private MySqlTestSchema() {}

  static void reset(DataSource dataSource) throws SQLException, IOException {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS adk_events");
      stmt.execute("DROP TABLE IF EXISTS adk_sessions");
      stmt.execute("DROP TABLE IF EXISTS adk_app_state");
      stmt.execute("DROP TABLE IF EXISTS adk_user_state");

      try (InputStream schemaStream =
          Objects.requireNonNull(
              MySqlTestSchema.class
                  .getClassLoader()
                  .getResourceAsStream("mysql-session-service-schema.sql"))) {
        String schema = new String(schemaStream.readAllBytes(), StandardCharsets.UTF_8);
        if (conn.getMetaData().getDatabaseProductName().equals("H2")) {
          // H2's JSON type JSON-encodes strings passed through PreparedStatement#setString,
          // unlike MySQL. TEXT preserves the JDBC behavior exercised by these DAO tests.
          schema = schema.replace(" JSON NOT NULL", " TEXT NOT NULL");
        }
        for (String sql : schema.split(";")) {
          if (!sql.isBlank()) {
            stmt.execute(sql);
          }
        }
      }
    }
  }
}
