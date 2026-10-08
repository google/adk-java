package com.google.adk.sessions.util;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface RowMapper<T> {
  T mapRow(ResultSet rs) throws SQLException;
}
