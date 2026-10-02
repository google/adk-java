package com.google.adk.sessions.dialect;

public class MySqlDialect implements SqlDialect {

  @Override
  public String dialectName() {
    return "MySQL";
  }

  @Override
  public String jsonCastSyntax() {
    return "";
  }

  @Override
  public String forUpdateSyntax() {
    return "FOR UPDATE";
  }

  @Override
  public String upsertAppStateSql() {
    return "INSERT INTO app_states (app_name, state, update_time) "
        + "VALUES (:appName, :state, :updateTime) "
        + "ON DUPLICATE KEY UPDATE state = VALUES(state), update_time = VALUES(update_time)";
  }

  @Override
  public String upsertUserStateSql() {
    return "INSERT INTO user_states (app_name, user_id, state, update_time) "
        + "VALUES (:appName, :userId, :state, :updateTime) "
        + "ON DUPLICATE KEY UPDATE state = VALUES(state), update_time = VALUES(update_time)";
  }
}
