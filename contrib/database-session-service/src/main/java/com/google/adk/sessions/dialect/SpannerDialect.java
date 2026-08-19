package com.google.adk.sessions.dialect;

import com.google.adk.sessions.util.JdbcTemplate.JdbcOperations;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class SpannerDialect implements SqlDialect {

  @Override
  public String dialectName() {
    return "Cloud Spanner";
  }

  @Override
  public String jsonCastSyntax() {
    return "";
  }

  @Override
  public String jsonValue(String paramName) {
    return "PARSE_JSON(" + paramName + ")";
  }

  @Override
  public String forUpdateSyntax() {
    return "";
  }

  @Override
  public String upsertAppStateSql() {
    return "INSERT OR UPDATE app_states (app_name, state, update_time) "
        + "VALUES (:appName, "
        + jsonValue(":state")
        + ", :updateTime)";
  }

  @Override
  public String upsertUserStateSql() {
    return "INSERT OR UPDATE user_states (app_name, user_id, state, update_time) "
        + "VALUES (:appName, :userId, "
        + jsonValue(":state")
        + ", :updateTime)";
  }

  @Override
  public void deleteSession(JdbcOperations ops, String appName, String userId, String sessionId)
      throws SQLException {
    String deleteEventsSql =
        "DELETE FROM events WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId";
    Map<String, Object> eventsParams = new HashMap<>();
    eventsParams.put("appName", appName);
    eventsParams.put("userId", userId);
    eventsParams.put("sessionId", sessionId);
    ops.update(deleteEventsSql, eventsParams);

    String deleteSessionSql =
        "DELETE FROM sessions WHERE app_name = :appName AND user_id = :userId AND id = :id";
    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("id", sessionId);
    ops.update(deleteSessionSql, params);
  }
}
