package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

public class FlywayMigrationTest {

  @Test
  public void testFlywayMigrationsApplied() {
    String dbUrl = "jdbc:h2:mem:flyway_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    assertDoesNotThrow(
        () -> {
          try (DatabaseSessionService service = new DatabaseSessionService(dbUrl)) {
            assertNotNull(service);
          }
        });

    try (Connection conn = DriverManager.getConnection(dbUrl);
        Statement stmt = conn.createStatement()) {

      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\"");
      rs.next();
      int migrationCount = rs.getInt(1);
      assert migrationCount > 0 : "Flyway migrations should be applied";

      rs =
          stmt.executeQuery(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'");
      boolean hasAppStates = false;
      boolean hasUserStates = false;
      boolean hasSessions = false;
      boolean hasEvents = false;

      while (rs.next()) {
        String tableName = rs.getString("table_name");
        if (tableName.equalsIgnoreCase("APP_STATES")) hasAppStates = true;
        if (tableName.equalsIgnoreCase("USER_STATES")) hasUserStates = true;
        if (tableName.equalsIgnoreCase("SESSIONS")) hasSessions = true;
        if (tableName.equalsIgnoreCase("EVENTS")) hasEvents = true;
      }

      assert hasAppStates : "app_states table should exist";
      assert hasUserStates : "user_states table should exist";
      assert hasSessions : "sessions table should exist";
      assert hasEvents : "events table should exist";

    } catch (Exception e) {
      throw new RuntimeException("Failed to verify Flyway migrations", e);
    }
  }

  @Test
  public void testMultipleServiceInstancesShareSchema() {
    String dbUrl = "jdbc:h2:mem:shared_schema_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    try (DatabaseSessionService service1 = new DatabaseSessionService(dbUrl);
        DatabaseSessionService service2 = new DatabaseSessionService(dbUrl)) {

      assertNotNull(service1);
      assertNotNull(service2);
    }
  }
}
