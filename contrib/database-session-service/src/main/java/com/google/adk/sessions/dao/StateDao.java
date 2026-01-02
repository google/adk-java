package com.google.adk.sessions.dao;

import com.google.adk.sessions.dialect.SqlDialect;
import com.google.adk.sessions.model.AppStateRow;
import com.google.adk.sessions.model.UserStateRow;
import com.google.adk.sessions.util.JdbcTemplate.JdbcOperations;
import com.google.adk.sessions.util.RowMapper;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StateDao {

  private static final Logger logger = LoggerFactory.getLogger(StateDao.class);
  private final SqlDialect dialect;

  public StateDao(SqlDialect dialect) {
    this.dialect = dialect;
  }

  private static final RowMapper<AppStateRow> APP_STATE_MAPPER =
      rs -> {
        AppStateRow row = new AppStateRow();
        row.setAppName(rs.getString("app_name"));
        row.setState(rs.getString("state"));

        Timestamp updateTs = rs.getTimestamp("update_time");
        row.setUpdateTime(updateTs != null ? updateTs.toInstant() : null);

        return row;
      };

  private static final RowMapper<UserStateRow> USER_STATE_MAPPER =
      rs -> {
        UserStateRow row = new UserStateRow();
        row.setAppName(rs.getString("app_name"));
        row.setUserId(rs.getString("user_id"));
        row.setState(rs.getString("state"));

        Timestamp updateTs = rs.getTimestamp("update_time");
        row.setUpdateTime(updateTs != null ? updateTs.toInstant() : null);

        return row;
      };

  public Optional<AppStateRow> getAppState(JdbcOperations ops, String appName) throws SQLException {
    String sql = "SELECT * FROM app_states WHERE app_name = :appName";

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);

    return ops.queryForObject(sql, params, APP_STATE_MAPPER);
  }

  public Optional<AppStateRow> getAppStateForUpdate(JdbcOperations ops, String appName)
      throws SQLException {
    String sql = "SELECT * FROM app_states WHERE app_name = :appName " + dialect.forUpdateSyntax();

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);

    return ops.queryForObject(sql, params, APP_STATE_MAPPER);
  }

  public void upsertAppState(JdbcOperations ops, AppStateRow appState) throws SQLException {
    logger.debug("Upserting app state for app: {}", appState.getAppName());
    dialect.upsertAppState(ops, appState);
    logger.debug("App state upserted successfully for app: {}", appState.getAppName());
  }

  public Optional<UserStateRow> getUserState(JdbcOperations ops, String appName, String userId)
      throws SQLException {
    String sql = "SELECT * FROM user_states WHERE app_name = :appName AND user_id = :userId";

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);

    return ops.queryForObject(sql, params, USER_STATE_MAPPER);
  }

  public Optional<UserStateRow> getUserStateForUpdate(
      JdbcOperations ops, String appName, String userId) throws SQLException {
    String sql =
        "SELECT * FROM user_states WHERE app_name = :appName AND user_id = :userId "
            + dialect.forUpdateSyntax();

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);

    return ops.queryForObject(sql, params, USER_STATE_MAPPER);
  }

  public void upsertUserState(JdbcOperations ops, UserStateRow userState) throws SQLException {
    logger.debug(
        "Upserting user state for app: {}, user: {}",
        userState.getAppName(),
        userState.getUserId());
    dialect.upsertUserState(ops, userState);
    logger.debug(
        "User state upserted successfully for app: {}, user: {}",
        userState.getAppName(),
        userState.getUserId());
  }
}
