package com.google.adk.sessions.dialect;

public class H2Dialect implements SqlDialect {

  @Override
  public String dialectName() {
    return "H2";
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
    return "MERGE INTO app_states (app_name, state, update_time) "
        + "KEY (app_name) "
        + "VALUES (:appName, :state, :updateTime)";
  }

  @Override
  public String upsertUserStateSql() {
    return "MERGE INTO user_states (app_name, user_id, state, update_time) "
        + "KEY (app_name, user_id) "
        + "VALUES (:appName, :userId, :state, :updateTime)";
  }
}
