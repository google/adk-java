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

package com.google.adk.store;

import com.google.adk.utils.PropertiesHelper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages PostgreSQL connection pool and provides database operations for artifact storage. Uses
 * HikariCP for connection pooling and supports environment variable configuration.
 *
 * @author Yashas S
 * @since 2025-12-08
 */
public class PostgresArtifactStore {

  private static final Logger logger = LoggerFactory.getLogger(PostgresArtifactStore.class);

  // Default table name
  private static final String DEFAULT_TABLE_NAME = "artifacts";

  private static volatile PostgresArtifactStore instance;
  private final HikariDataSource dataSource;
  private final String tableName;

  /**
   * Private constructor for singleton pattern. Initializes HikariCP connection pool from
   * environment variables.
   */
  private PostgresArtifactStore() {
    this(DEFAULT_TABLE_NAME);
  }

  /**
   * Private constructor with custom table name. Initializes HikariCP connection pool from
   * environment variables.
   *
   * @param tableName the table name to use for artifacts
   */
  private PostgresArtifactStore(String tableName) {
    this.tableName = tableName;
    this.dataSource = initializeDataSource();
  }

  /**
   * Constructor with explicit connection parameters.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   * @param tableName the table name to use for artifacts
   */
  private PostgresArtifactStore(String dbUrl, String dbUser, String dbPassword, String tableName) {
    this.tableName = tableName;
    this.dataSource = initializeDataSource(dbUrl, dbUser, dbPassword);
  }

  /**
   * Get singleton instance with default table name. Uses environment variables for database
   * connection.
   *
   * @return the PostgresArtifactStore instance
   */
  public static PostgresArtifactStore getInstance() {
    if (instance == null) {
      synchronized (PostgresArtifactStore.class) {
        if (instance == null) {
          instance = new PostgresArtifactStore();
        }
      }
    }
    return instance;
  }

  /**
   * Get singleton instance with custom table name. Uses environment variables for database
   * connection.
   *
   * @param tableName the table name to use for artifacts
   * @return the PostgresArtifactStore instance
   */
  public static PostgresArtifactStore getInstance(String tableName) {
    if (instance == null) {
      synchronized (PostgresArtifactStore.class) {
        if (instance == null) {
          instance = new PostgresArtifactStore(tableName);
        }
      }
    }
    return instance;
  }

  /**
   * Get instance with explicit connection parameters. Creates a new instance (not singleton) for
   * flexibility.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   * @param tableName the table name to use for artifacts
   * @return a new PostgresArtifactStore instance
   */
  public static PostgresArtifactStore createInstance(
      String dbUrl, String dbUser, String dbPassword, String tableName) {
    return new PostgresArtifactStore(dbUrl, dbUser, dbPassword, tableName);
  }

  /**
   * Initialize HikariCP data source from environment variables.
   *
   * @return the configured HikariDataSource
   */
  private HikariDataSource initializeDataSource() {
    logger.info("Reading database configuration from environment variables...");
    String dbUrl = System.getenv("DBURL");
    String dbUser = System.getenv("DBUSER");
    String dbPassword = System.getenv("DBPASSWORD");

    // Fallback to PropertiesHelper if environment variables are not set
    if (dbUrl == null) {
      logger.debug("DBURL not in environment, checking PropertiesHelper");
      dbUrl = PropertiesHelper.getInstance().getValue("db_url");
    }
    if (dbUser == null) {
      logger.debug("DBUSER not in environment, checking PropertiesHelper");
      dbUser = PropertiesHelper.getInstance().getValue("db_user");
    }
    if (dbPassword == null) {
      logger.debug("DBPASSWORD not in environment, checking PropertiesHelper");
      dbPassword = PropertiesHelper.getInstance().getValue("db_password");
    }
    if (dbUrl == null || dbUrl.isEmpty()) {
      throw new IllegalStateException(
          "Database URL not configured. Set DBURL environment variable or db_url property.");
    }

    logger.info("Database configuration loaded successfully for URL: {}", dbUrl);
    return initializeDataSource(dbUrl, dbUser, dbPassword);
  }

  /**
   * Initialize HikariCP data source with explicit parameters.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   * @return the configured HikariDataSource
   */
  private HikariDataSource initializeDataSource(String dbUrl, String dbUser, String dbPassword) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(dbUrl);
    config.setUsername(dbUser);
    config.setPassword(dbPassword);

    // Connection pool settings
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(10000);
    config.setIdleTimeout(600000);
    config.setMaxLifetime(1800000);
    config.setLeakDetectionThreshold(120000);

    // Performance settings
    config.addDataSourceProperty("cachePrepStmts", "true");
    config.addDataSourceProperty("prepStmtCacheSize", "250");
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

    return new HikariDataSource(config);
  }

  /**
   * Get connection from pool.
   *
   * @return a database connection
   * @throws SQLException if connection fails
   */
  private Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  /**
   * Save artifact to database. Returns the assigned version number.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param data the artifact binary data
   * @param mimeType the MIME type
   * @return the version number assigned to this artifact
   * @throws SQLException if save operation fails
   */
  public int saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      byte[] data,
      String mimeType)
      throws SQLException {
    return saveArtifact(appName, userId, sessionId, filename, data, mimeType, null);
  }

  /**
   * Save artifact to database with metadata. Returns the assigned version number.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param data the artifact binary data
   * @param mimeType the MIME type
   * @param metadata the metadata JSON string (can be null)
   * @return the version number assigned to this artifact
   * @throws SQLException if save operation fails
   */
  public int saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      byte[] data,
      String mimeType,
      String metadata)
      throws SQLException {
    return saveArtifact(appName, userId, sessionId, filename, data, mimeType, metadata, null);
  }

  /**
   * Save artifact to database with metadata and file URI. Returns the assigned version number.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param data the artifact binary data
   * @param mimeType the MIME type
   * @param metadata the metadata JSON string (can be null)
   * @param fileUri the URI pointing to external storage like S3 (can be null)
   * @return the version number assigned to this artifact
   * @throws SQLException if save operation fails
   */
  public int saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      byte[] data,
      String mimeType,
      String metadata,
      String fileUri)
      throws SQLException {
    logger.debug(
        "Saving artifact: app={}, user={}, session={}, file={}, size={}KB, mime={}",
        appName,
        userId,
        sessionId,
        filename,
        data != null ? data.length / 1024 : 0,
        mimeType);

    Connection conn = null;
    try {
      conn = getConnection();
      // Start transaction
      conn.setAutoCommit(false);

      // Get next version with row-level lock (prevents race conditions)
      int nextVersion = getNextVersion(conn, appName, userId, sessionId, filename);

      String sql =
          String.format(
              "INSERT INTO %s (app_name, user_id, session_id, filename, version, mime_type, data, metadata, file_uri) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
              tableName);

      try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
        pstmt.setString(1, appName);
        pstmt.setString(2, userId);
        pstmt.setString(3, sessionId);
        pstmt.setString(4, filename);
        pstmt.setInt(5, nextVersion);
        pstmt.setString(6, mimeType);
        if (data != null) {
          pstmt.setBytes(7, data);
        } else {
          pstmt.setNull(7, java.sql.Types.BINARY);
        }
        if (metadata != null) {
          pstmt.setString(8, metadata);
        } else {
          pstmt.setNull(8, java.sql.Types.OTHER);
        }
        if (fileUri != null) {
          pstmt.setString(9, fileUri);
        } else {
          pstmt.setNull(9, java.sql.Types.VARCHAR);
        }

        int rowsAffected = pstmt.executeUpdate();

        if (rowsAffected > 0) {
          // Commit transaction
          conn.commit();

          logger.info(
              "✅ Artifact saved: app={}, user={}, session={}, file={}, version={}, size={}KB",
              appName,
              userId,
              sessionId,
              filename,
              nextVersion,
              data.length / 1024);
          return nextVersion;
        } else {
          conn.rollback();
          logger.error(
              "❌ Failed to save artifact (no rows affected): app={}, user={}, session={}, file={}",
              appName,
              userId,
              sessionId,
              filename);
          throw new SQLException("Failed to save artifact, no rows affected");
        }
      }
    } catch (SQLException e) {
      if (conn != null) {
        try {
          conn.rollback();
        } catch (SQLException rollbackEx) {
          logger.error("Error rolling back transaction: {}", rollbackEx.getMessage());
        }
      }
      logger.error(
          "❌ Error saving artifact: app={}, user={}, session={}, file={}, error={}",
          appName,
          userId,
          sessionId,
          filename,
          e.getMessage());
      throw e;
    } finally {
      if (conn != null) {
        try {
          conn.setAutoCommit(true); // Restore default
          conn.close();
        } catch (SQLException closeEx) {
          logger.error("Error closing connection: {}", closeEx.getMessage());
        }
      }
    }
  }

  /**
   * Update the file URI for an existing artifact version.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param version the artifact version
   * @param fileUri the URI pointing to external storage (S3)
   * @throws SQLException if update fails
   */
  public void updateFileUri(
      String appName, String userId, String sessionId, String filename, int version, String fileUri)
      throws SQLException {
    String sql =
        String.format(
            "UPDATE %s SET file_uri = ? WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version = ?",
            tableName);
    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, fileUri);
      pstmt.setString(2, appName);
      pstmt.setString(3, userId);
      pstmt.setString(4, sessionId);
      pstmt.setString(5, filename);
      pstmt.setInt(6, version);
      pstmt.executeUpdate();
    } catch (SQLException e) {
      logger.error(
          "❌ Error updating file_uri: app={}, user={}, session={}, file={}, version={}, error={}",
          appName,
          userId,
          sessionId,
          filename,
          version,
          e.getMessage());
      throw e;
    }
  }

  /**
   * Get next version number for an artifact with row-level locking to prevent race conditions.
   *
   * <p>Uses SELECT ... FOR UPDATE to ensure atomic version increment when multiple
   * threads/processes attempt to save the same artifact simultaneously.
   *
   * @param conn the database connection (must be within a transaction)
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @return the next version number
   * @throws SQLException if query fails
   */
  private int getNextVersion(
      Connection conn, String appName, String userId, String sessionId, String filename)
      throws SQLException {
    // Lock all existing rows for this artifact to prevent concurrent version generation
    // We first lock the rows, then compute MAX in a separate query
    // This prevents race conditions without using FOR UPDATE with aggregate functions

    // Step 1: Lock all rows for this artifact (if any exist)
    String lockSql =
        String.format(
            "SELECT version FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? "
                + "FOR UPDATE",
            tableName);

    try (PreparedStatement lockStmt = conn.prepareStatement(lockSql)) {
      lockStmt.setString(1, appName);
      lockStmt.setString(2, userId);
      lockStmt.setString(3, sessionId);
      lockStmt.setString(4, filename);
      lockStmt.executeQuery(); // Lock rows (result not needed, just the lock)
    }

    // Step 2: Now compute the next version (rows are locked)
    String maxSql =
        String.format(
            "SELECT COALESCE(MAX(version), -1) + 1 as next_version FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
            tableName);

    try (PreparedStatement maxStmt = conn.prepareStatement(maxSql)) {
      maxStmt.setString(1, appName);
      maxStmt.setString(2, userId);
      maxStmt.setString(3, sessionId);
      maxStmt.setString(4, filename);

      try (ResultSet rs = maxStmt.executeQuery()) {
        if (rs.next()) {
          return rs.getInt("next_version");
        }
        return 0; // First version
      }
    }
  }

  /**
   * Load artifact by version or latest. Returns ArtifactData object or null if not found.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param version the version number, or null for latest
   * @return ArtifactData object or null if not found
   * @throws SQLException if load operation fails
   */
  public ArtifactData loadArtifact(
      String appName, String userId, String sessionId, String filename, Integer version)
      throws SQLException {
    logger.debug(
        "Loading artifact: app={}, user={}, session={}, file={}, version={}",
        appName,
        userId,
        sessionId,
        filename,
        version != null ? version : "latest");

    String sql;
    if (version != null) {
      // Load specific version
      sql =
          String.format(
              "SELECT data, mime_type, version, created_at, metadata, file_uri FROM %s "
                  + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version = ?",
              tableName);
    } else {
      // Load latest version
      sql =
          String.format(
              "SELECT data, mime_type, version, created_at, metadata, file_uri FROM %s "
                  + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? "
                  + "ORDER BY version DESC LIMIT 1",
              tableName);
    }

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);
      if (version != null) {
        pstmt.setInt(5, version);
      }

      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          byte[] data = rs.getBytes("data");
          String mimeType = rs.getString("mime_type");
          int loadedVersion = rs.getInt("version");
          Timestamp createdAt = rs.getTimestamp("created_at");
          String metadata = rs.getString("metadata");
          String fileUri = rs.getString("file_uri");

          logger.info(
              "✅ Artifact loaded: app={}, user={}, session={}, file={}, version={}, size={}KB",
              appName,
              userId,
              sessionId,
              filename,
              loadedVersion,
              data != null ? data.length / 1024 : 0);

          return new ArtifactData(data, mimeType, loadedVersion, createdAt, metadata, fileUri);
        } else {
          logger.warn(
              "⚠️  Artifact not found: app={}, user={}, session={}, file={}, version={}",
              appName,
              userId,
              sessionId,
              filename,
              version != null ? version : "latest");
        }
      }
    } catch (SQLException e) {
      logger.error(
          "❌ Error loading artifact: app={}, user={}, session={}, file={}, error={}",
          appName,
          userId,
          sessionId,
          filename,
          e.getMessage());
      throw e;
    }

    return null; // Not found
  }

  /**
   * List all filenames for a session.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @return list of artifact filenames
   * @throws SQLException if query fails
   */
  public List<String> listFilenames(String appName, String userId, String sessionId)
      throws SQLException {
    logger.debug("Listing artifacts: app={}, user={}, session={}", appName, userId, sessionId);

    String sql =
        String.format(
            "SELECT DISTINCT filename FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? "
                + "ORDER BY filename",
            tableName);

    List<String> filenames = new ArrayList<>();

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);

      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          filenames.add(rs.getString("filename"));
        }
      }

      logger.info(
          "✅ Listed {} artifacts: app={}, user={}, session={}, files={}",
          filenames.size(),
          appName,
          userId,
          sessionId,
          filenames);

      return filenames;
    } catch (SQLException e) {
      logger.error(
          "❌ Error listing artifacts: app={}, user={}, session={}, error={}",
          appName,
          userId,
          sessionId,
          e.getMessage());
      throw e;
    }
  }

  /**
   * Delete all versions of an artifact.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @throws SQLException if delete operation fails
   */
  public void deleteArtifact(String appName, String userId, String sessionId, String filename)
      throws SQLException {
    logger.debug(
        "Deleting artifact: app={}, user={}, session={}, file={}",
        appName,
        userId,
        sessionId,
        filename);

    String sql =
        String.format(
            "DELETE FROM %s WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
            tableName);

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);

      int rowsDeleted = pstmt.executeUpdate();

      logger.info(
          "✅ Artifact deleted: app={}, user={}, session={}, file={}, versionsDeleted={}",
          appName,
          userId,
          sessionId,
          filename,
          rowsDeleted);
    } catch (SQLException e) {
      logger.error(
          "❌ Error deleting artifact: app={}, user={}, session={}, file={}, error={}",
          appName,
          userId,
          sessionId,
          filename,
          e.getMessage());
      throw e;
    }
  }

  /**
   * List all versions for an artifact.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @return list of version numbers
   * @throws SQLException if query fails
   */
  public List<Integer> listVersions(
      String appName, String userId, String sessionId, String filename) throws SQLException {
    logger.debug(
        "Listing versions: app={}, user={}, session={}, file={}",
        appName,
        userId,
        sessionId,
        filename);

    String sql =
        String.format(
            "SELECT version FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? "
                + "ORDER BY version",
            tableName);

    List<Integer> versions = new ArrayList<>();

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);

      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          versions.add(rs.getInt("version"));
        }
      }

      logger.info(
          "✅ Listed {} versions: app={}, user={}, session={}, file={}, versions={}",
          versions.size(),
          appName,
          userId,
          sessionId,
          filename,
          versions);

      return versions;
    } catch (SQLException e) {
      logger.error(
          "❌ Error listing versions: app={}, user={}, session={}, file={}, error={}",
          appName,
          userId,
          sessionId,
          filename,
          e.getMessage());
      throw e;
    }
  }

  /** Close the connection pool. */
  public void close() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
    }
  }

  /** Data class for artifact results. */
  public static class ArtifactData {
    public final byte[] data;
    public final String mimeType;
    public final int version;
    public final Timestamp createdAt;
    public final String metadata;
    public final String fileUri;

    public ArtifactData(
        byte[] data, String mimeType, int version, Timestamp createdAt, String metadata) {
      this(data, mimeType, version, createdAt, metadata, null);
    }

    public ArtifactData(
        byte[] data,
        String mimeType,
        int version,
        Timestamp createdAt,
        String metadata,
        String fileUri) {
      this.data = data;
      this.mimeType = mimeType;
      this.version = version;
      this.createdAt = createdAt;
      this.metadata = metadata;
      this.fileUri = fileUri;
    }
  }
}
