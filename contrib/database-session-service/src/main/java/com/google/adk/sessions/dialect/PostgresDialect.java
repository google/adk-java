package com.google.adk.sessions.dialect;

public class PostgresDialect implements SqlDialect {

  @Override
  public String dialectName() {
    return "PostgreSQL";
  }

  @Override
  public String jsonCastSyntax() {
    return "::jsonb";
  }

  @Override
  public String forUpdateSyntax() {
    return "FOR UPDATE";
  }

  @Override
  public String upsertAppStateSql() {
    return "INSERT INTO app_states (app_name, state, update_time) "
        + "VALUES (:appName, :state::jsonb, :updateTime) "
        + "ON CONFLICT (app_name) "
        + "DO UPDATE SET state = EXCLUDED.state, update_time = EXCLUDED.update_time";
  }

  @Override
  public String upsertUserStateSql() {
    return "INSERT INTO user_states (app_name, user_id, state, update_time) "
        + "VALUES (:appName, :userId, :state::jsonb, :updateTime) "
        + "ON CONFLICT (app_name, user_id) "
        + "DO UPDATE SET state = EXCLUDED.state, update_time = EXCLUDED.update_time";
  }
}
