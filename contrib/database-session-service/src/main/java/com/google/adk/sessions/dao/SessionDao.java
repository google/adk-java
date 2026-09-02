package com.google.adk.sessions.dao;

import com.google.adk.sessions.dialect.SqlDialect;
import com.google.adk.sessions.model.SessionRow;
import com.google.adk.sessions.util.JdbcTemplate.JdbcOperations;
import com.google.adk.sessions.util.RowMapper;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionDao {

  private static final Logger logger = LoggerFactory.getLogger(SessionDao.class);
  private final SqlDialect dialect;

  public SessionDao(SqlDialect dialect) {
    this.dialect = dialect;
    logger.debug("SessionDao initialized with {} dialect", dialect.dialectName());
  }

  private static final RowMapper<SessionRow> ROW_MAPPER =
      rs -> {
        SessionRow row = new SessionRow();
        row.setAppName(rs.getString("app_name"));
        row.setUserId(rs.getString("user_id"));
        row.setId(rs.getString("id"));
        row.setState(rs.getString("state"));

        Timestamp createTs = rs.getTimestamp("create_time");
        row.setCreateTime(createTs != null ? createTs.toInstant() : null);

        Timestamp updateTs = rs.getTimestamp("update_time");
        row.setUpdateTime(updateTs != null ? updateTs.toInstant() : null);

        return row;
      };

  public Optional<SessionRow> findSession(
      JdbcOperations ops, String appName, String userId, String id) throws SQLException {
    String sql =
        "SELECT * FROM sessions " + "WHERE app_name = :appName AND user_id = :userId AND id = :id";

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("id", id);

    return ops.queryForObject(sql, params, ROW_MAPPER);
  }

  public Optional<SessionRow> findSessionForUpdate(
      JdbcOperations ops, String appName, String userId, String id) throws SQLException {
    String sql =
        "SELECT * FROM sessions "
            + "WHERE app_name = :appName AND user_id = :userId AND id = :id "
            + dialect.forUpdateSyntax();

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("id", id);

    return ops.queryForObject(sql, params, ROW_MAPPER);
  }

  public List<SessionRow> listSessions(JdbcOperations ops, String appName, String userId)
      throws SQLException {
    String sql =
        "SELECT * FROM sessions "
            + "WHERE app_name = :appName AND user_id = :userId "
            + "ORDER BY update_time DESC";

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);

    return ops.query(sql, params, ROW_MAPPER);
  }

  public void insertSession(JdbcOperations ops, SessionRow session) throws SQLException {
    String sql =
        "INSERT INTO sessions (app_name, user_id, id, state, create_time, update_time) "
            + "VALUES (:appName, :userId, :id, "
            + dialect.jsonValue(":state")
            + ", :createTime, :updateTime)";

    Map<String, Object> params = new HashMap<>();
    params.put("appName", session.getAppName());
    params.put("userId", session.getUserId());
    params.put("id", session.getId());
    params.put("state", session.getState());
    params.put("createTime", Timestamp.from(session.getCreateTime()));
    params.put("updateTime", Timestamp.from(session.getUpdateTime()));

    logger.debug(
        "Inserting session: app={}, user={}, sessionId={}",
        session.getAppName(),
        session.getUserId(),
        session.getId());
    ops.update(sql, params);
    logger.debug("Session created successfully: {}", session.getId());
  }

  public void updateSession(JdbcOperations ops, SessionRow session) throws SQLException {
    String sql =
        "UPDATE sessions "
            + "SET state = "
            + dialect.jsonValue(":state")
            + ", update_time = :updateTime "
            + "WHERE app_name = :appName AND user_id = :userId AND id = :id";

    Map<String, Object> params = new HashMap<>();
    params.put("state", session.getState());
    params.put("updateTime", Timestamp.from(session.getUpdateTime()));
    params.put("appName", session.getAppName());
    params.put("userId", session.getUserId());
    params.put("id", session.getId());

    ops.update(sql, params);
  }

  public void deleteSession(JdbcOperations ops, String appName, String userId, String id)
      throws SQLException {
    dialect.deleteSession(ops, appName, userId, id);
  }
}
