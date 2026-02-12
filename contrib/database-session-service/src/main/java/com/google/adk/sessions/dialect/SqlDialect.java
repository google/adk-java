package com.google.adk.sessions.dialect;

import com.google.adk.sessions.model.AppStateRow;
import com.google.adk.sessions.model.UserStateRow;
import com.google.adk.sessions.util.JdbcTemplate.JdbcOperations;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

public interface SqlDialect {

  String dialectName();

  String jsonCastSyntax();

  String forUpdateSyntax();

  String upsertAppStateSql();

  String upsertUserStateSql();

  default String jsonValue(String paramName) {
    return paramName + jsonCastSyntax();
  }

  default void upsertAppState(JdbcOperations ops, AppStateRow appState) throws SQLException {
    String sql = upsertAppStateSql();
    Map<String, Object> params = new HashMap<>();
    params.put("appName", appState.getAppName());
    params.put("state", appState.getState());
    params.put("updateTime", Timestamp.from(appState.getUpdateTime()));
    ops.update(sql, params);
  }

  default void upsertUserState(JdbcOperations ops, UserStateRow userState) throws SQLException {
    String sql = upsertUserStateSql();
    Map<String, Object> params = new HashMap<>();
    params.put("appName", userState.getAppName());
    params.put("userId", userState.getUserId());
    params.put("state", userState.getState());
    params.put("updateTime", Timestamp.from(userState.getUpdateTime()));
    ops.update(sql, params);
  }

  default void deleteSession(JdbcOperations ops, String appName, String userId, String sessionId)
      throws SQLException {
    String sql =
        "DELETE FROM sessions WHERE app_name = :appName AND user_id = :userId AND id = :id";
    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("id", sessionId);
    ops.update(sql, params);
  }
}
