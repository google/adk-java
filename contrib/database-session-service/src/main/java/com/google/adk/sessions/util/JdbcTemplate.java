package com.google.adk.sessions.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

public class JdbcTemplate {

  private final DataSource dataSource;

  public JdbcTemplate(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public <T> T inTransaction(TransactionCallback<T> callback) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      boolean originalAutoCommit = conn.getAutoCommit();
      try {
        conn.setAutoCommit(false);
        T result = callback.doInTransaction(new JdbcOperations(conn));
        conn.commit();
        return result;
      } catch (Exception e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(originalAutoCommit);
      }
    }
  }

  @FunctionalInterface
  public interface TransactionCallback<T> {
    T doInTransaction(JdbcOperations ops) throws SQLException;
  }

  public static class JdbcOperations {
    private final Connection connection;

    JdbcOperations(Connection connection) {
      this.connection = connection;
    }

    public Connection getConnection() {
      return connection;
    }

    public <T> Optional<T> queryForObject(
        String sql, Map<String, Object> params, RowMapper<T> mapper) throws SQLException {
      NamedParameterSupport nps = NamedParameterSupport.parse(sql);

      try (PreparedStatement ps = connection.prepareStatement(nps.getParsedSql())) {
        nps.setParameters(ps, params);

        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return Optional.of(mapper.mapRow(rs));
          }
          return Optional.empty();
        }
      }
    }

    public <T> List<T> query(String sql, Map<String, Object> params, RowMapper<T> mapper)
        throws SQLException {
      NamedParameterSupport nps = NamedParameterSupport.parse(sql);
      List<T> results = new ArrayList<>();

      try (PreparedStatement ps = connection.prepareStatement(nps.getParsedSql())) {
        nps.setParameters(ps, params);

        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            results.add(mapper.mapRow(rs));
          }
        }
      }

      return results;
    }

    public int update(String sql, Map<String, Object> params) throws SQLException {
      NamedParameterSupport nps = NamedParameterSupport.parse(sql);

      try (PreparedStatement ps = connection.prepareStatement(nps.getParsedSql())) {
        nps.setParameters(ps, params);
        return ps.executeUpdate();
      }
    }

    public int execute(String sql, Map<String, Object> params) throws SQLException {
      return update(sql, params);
    }
  }
}
