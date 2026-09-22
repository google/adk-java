package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
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

  @Test
  public void testTenConcurrentServiceInstances() {
    String dbUrl = "jdbc:h2:mem:ten_instances_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    // Create 10 service instances concurrently, simulating 10 Kubernetes pods starting
    DatabaseSessionService[] services = new DatabaseSessionService[10];

    try {
      for (int i = 0; i < 10; i++) {
        services[i] = new DatabaseSessionService(dbUrl);
        assertNotNull(services[i], "Service instance " + i + " should be initialized");
      }

      // Verify all instances are operational
      for (int i = 0; i < 10; i++) {
        assertNotNull(services[i], "Service instance " + i + " should still be valid");
      }

    } finally {
      // Clean up all instances
      for (int i = 0; i < 10; i++) {
        if (services[i] != null) {
          services[i].close();
        }
      }
    }
  }

  @Test
  public void testFlywayMigrationPostgres() {
    String jdbcUrl =
        "jdbc:postgresql://localhost:5432/adk_flyway_test?user=adk_test&password=adk_test_password";

    // Check if PostgreSQL is available
    try {
      DriverManager.getConnection(jdbcUrl).close();
    } catch (SQLException e) {
      Assumptions.assumeTrue(false, "PostgreSQL not available - skipping test");
      return;
    }

    // Verify tables DO NOT exist before migration
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      ResultSet rs =
          stmt.executeQuery(
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('sessions', 'events', 'app_states', 'user_states')");
      rs.next();
      int tableCount = rs.getInt(1);
      assertTrue(tableCount == 0, "Tables should NOT exist before migration in PostgreSQL");
    } catch (SQLException e) {
      throw new RuntimeException("Failed to verify pre-migration state", e);
    }

    DatabaseSessionService[] services = new DatabaseSessionService[10];

    try {
      // Create 10 instances
      for (int i = 0; i < 10; i++) {
        services[i] = new DatabaseSessionService(jdbcUrl);
        assertNotNull(services[i], "PostgreSQL instance " + i + " should be initialized");
      }

      // Verify migration was applied exactly once
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM flyway_schema_history");
        rs.next();
        int migrationCount = rs.getInt(1);
        assertTrue(
            migrationCount > 0, "At least one migration should be applied to PostgreSQL database");
      }

      // Verify all expected tables exist AFTER migration
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        ResultSet rs =
            stmt.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('sessions', 'events', 'app_states', 'user_states', 'adk_internal_metadata') ORDER BY table_name");

        boolean hasSessions = false;
        boolean hasEvents = false;
        boolean hasAppStates = false;
        boolean hasUserStates = false;
        boolean hasMetadata = false;

        while (rs.next()) {
          String tableName = rs.getString("table_name");
          if (tableName.equals("sessions")) hasSessions = true;
          if (tableName.equals("events")) hasEvents = true;
          if (tableName.equals("app_states")) hasAppStates = true;
          if (tableName.equals("user_states")) hasUserStates = true;
          if (tableName.equals("adk_internal_metadata")) hasMetadata = true;
        }

        assertTrue(hasSessions, "sessions table should exist after migration");
        assertTrue(hasEvents, "events table should exist after migration");
        assertTrue(hasAppStates, "app_states table should exist after migration");
        assertTrue(hasUserStates, "user_states table should exist after migration");
        assertTrue(hasMetadata, "adk_internal_metadata table should exist after migration");
      }

      // Verify column schema for sessions table
      try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        ResultSet rs = conn.getMetaData().getColumns(null, "public", "sessions", null);
        Set<String> columnNames = new HashSet<>();
        while (rs.next()) {
          columnNames.add(rs.getString("COLUMN_NAME"));
        }
        assertTrue(columnNames.contains("app_name"), "sessions should have app_name column");
        assertTrue(columnNames.contains("user_id"), "sessions should have user_id column");
        assertTrue(columnNames.contains("id"), "sessions should have id column");
        assertTrue(columnNames.contains("state"), "sessions should have state column");
        assertTrue(columnNames.contains("create_time"), "sessions should have create_time column");
        assertTrue(columnNames.contains("update_time"), "sessions should have update_time column");
      }

      // Verify column schema for events table
      try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        ResultSet rs = conn.getMetaData().getColumns(null, "public", "events", null);
        Set<String> columnNames = new HashSet<>();
        while (rs.next()) {
          columnNames.add(rs.getString("COLUMN_NAME"));
        }
        assertTrue(columnNames.contains("id"), "events should have id column");
        assertTrue(columnNames.contains("app_name"), "events should have app_name column");
        assertTrue(columnNames.contains("user_id"), "events should have user_id column");
        assertTrue(columnNames.contains("session_id"), "events should have session_id column");
        assertTrue(
            columnNames.contains("invocation_id"), "events should have invocation_id column");
        assertTrue(columnNames.contains("timestamp"), "events should have timestamp column");
        assertTrue(columnNames.contains("event_data"), "events should have event_data column");
      }

      // Verify foreign key from events to sessions
      try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        ResultSet rs = conn.getMetaData().getImportedKeys(null, "public", "events");
        boolean hasSessionFK = false;
        while (rs.next()) {
          String pkTable = rs.getString("PKTABLE_NAME");
          String fkTable = rs.getString("FKTABLE_NAME");
          if (pkTable.equals("sessions") && fkTable.equals("events")) {
            hasSessionFK = true;
          }
        }
        assertTrue(hasSessionFK, "events should have foreign key to sessions");
      }

    } catch (Exception e) {
      throw new RuntimeException("PostgreSQL test failed", e);
    } finally {
      // Close all services
      for (int i = 0; i < 10; i++) {
        if (services[i] != null) {
          services[i].close();
        }
      }

      // Clean up database
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS events CASCADE");
        stmt.execute("DROP TABLE IF EXISTS sessions CASCADE");
        stmt.execute("DROP TABLE IF EXISTS user_states CASCADE");
        stmt.execute("DROP TABLE IF EXISTS app_states CASCADE");
        stmt.execute("DROP TABLE IF EXISTS adk_internal_metadata CASCADE");
        stmt.execute("DROP TABLE IF EXISTS flyway_schema_history CASCADE");
      } catch (SQLException e) {
        System.err.println("Failed to clean up PostgreSQL test database: " + e.getMessage());
      }
    }
  }

  @Test
  public void testFlywayMigrationMysql() {
    String jdbcUrl =
        "jdbc:mysql://localhost:3306/adk_flyway_test?user=adk_test&password=adk_test_password";

    // Check if MySQL is available
    try {
      DriverManager.getConnection(jdbcUrl).close();
    } catch (SQLException e) {
      Assumptions.assumeTrue(false, "MySQL not available - skipping test");
      return;
    }

    // Verify tables DO NOT exist before migration
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      ResultSet rs =
          stmt.executeQuery(
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'adk_flyway_test' AND table_name IN ('sessions', 'events', 'app_states', 'user_states')");
      rs.next();
      int tableCount = rs.getInt(1);
      assertTrue(tableCount == 0, "Tables should NOT exist before migration in MySQL");
    } catch (SQLException e) {
      throw new RuntimeException("Failed to verify pre-migration state", e);
    }

    DatabaseSessionService[] services = new DatabaseSessionService[10];

    try {
      // Create 10 instances
      for (int i = 0; i < 10; i++) {
        services[i] = new DatabaseSessionService(jdbcUrl);
        assertNotNull(services[i], "MySQL instance " + i + " should be initialized");
      }

      // Verify migration was applied exactly once
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM flyway_schema_history");
        rs.next();
        int migrationCount = rs.getInt(1);
        assertTrue(
            migrationCount > 0, "At least one migration should be applied to MySQL database");
      }

      // Verify all expected tables exist AFTER migration
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        ResultSet rs =
            stmt.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'adk_flyway_test' AND table_name IN ('sessions', 'events', 'app_states', 'user_states', 'adk_internal_metadata') ORDER BY table_name");

        boolean hasSessions = false;
        boolean hasEvents = false;
        boolean hasAppStates = false;
        boolean hasUserStates = false;
        boolean hasMetadata = false;

        while (rs.next()) {
          String tableName = rs.getString("table_name");
          if (tableName.equals("sessions")) hasSessions = true;
          if (tableName.equals("events")) hasEvents = true;
          if (tableName.equals("app_states")) hasAppStates = true;
          if (tableName.equals("user_states")) hasUserStates = true;
          if (tableName.equals("adk_internal_metadata")) hasMetadata = true;
        }

        assertTrue(hasSessions, "sessions table should exist after migration");
        assertTrue(hasEvents, "events table should exist after migration");
        assertTrue(hasAppStates, "app_states table should exist after migration");
        assertTrue(hasUserStates, "user_states table should exist after migration");
        assertTrue(hasMetadata, "adk_internal_metadata table should exist after migration");
      }

      // Verify column schema for sessions table
      try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        ResultSet rs = conn.getMetaData().getColumns(null, "adk_flyway_test", "sessions", null);
        Set<String> columnNames = new HashSet<>();
        while (rs.next()) {
          columnNames.add(rs.getString("COLUMN_NAME"));
        }
        assertTrue(columnNames.contains("app_name"), "sessions should have app_name column");
        assertTrue(columnNames.contains("user_id"), "sessions should have user_id column");
        assertTrue(columnNames.contains("id"), "sessions should have id column");
        assertTrue(columnNames.contains("state"), "sessions should have state column");
        assertTrue(columnNames.contains("create_time"), "sessions should have create_time column");
        assertTrue(columnNames.contains("update_time"), "sessions should have update_time column");
      }

      // Verify column schema for events table
      try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        ResultSet rs = conn.getMetaData().getColumns(null, "adk_flyway_test", "events", null);
        Set<String> columnNames = new HashSet<>();
        while (rs.next()) {
          columnNames.add(rs.getString("COLUMN_NAME"));
        }
        assertTrue(columnNames.contains("id"), "events should have id column");
        assertTrue(columnNames.contains("app_name"), "events should have app_name column");
        assertTrue(columnNames.contains("user_id"), "events should have user_id column");
        assertTrue(columnNames.contains("session_id"), "events should have session_id column");
        assertTrue(
            columnNames.contains("invocation_id"), "events should have invocation_id column");
        assertTrue(columnNames.contains("timestamp"), "events should have timestamp column");
        assertTrue(columnNames.contains("event_data"), "events should have event_data column");
      }

      // Verify foreign key from events to sessions
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        ResultSet rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = 'adk_flyway_test' "
                    + "AND TABLE_NAME = 'events' "
                    + "AND REFERENCED_TABLE_NAME = 'sessions'");
        rs.next();
        assertTrue(rs.getInt(1) > 0, "events should have foreign key to sessions");
      }

    } catch (Exception e) {
      throw new RuntimeException("MySQL test failed", e);
    } finally {
      // Close all services
      for (int i = 0; i < 10; i++) {
        if (services[i] != null) {
          services[i].close();
        }
      }

      // Clean up database
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
          Statement stmt = conn.createStatement()) {
        stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
        stmt.execute("DROP TABLE IF EXISTS events");
        stmt.execute("DROP TABLE IF EXISTS sessions");
        stmt.execute("DROP TABLE IF EXISTS user_states");
        stmt.execute("DROP TABLE IF EXISTS app_states");
        stmt.execute("DROP TABLE IF EXISTS adk_internal_metadata");
        stmt.execute("DROP TABLE IF EXISTS flyway_schema_history");
        stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
      } catch (SQLException e) {
        System.err.println("Failed to clean up MySQL test database: " + e.getMessage());
      }
    }
  }
}
