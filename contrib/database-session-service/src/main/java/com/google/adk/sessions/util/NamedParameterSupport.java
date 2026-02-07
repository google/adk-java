package com.google.adk.sessions.util;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NamedParameterSupport {

  private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile("(?<!:):([a-zA-Z0-9_]+)");

  private final String parsedSql;
  private final List<String> parameterNames;

  private NamedParameterSupport(String parsedSql, List<String> parameterNames) {
    this.parsedSql = parsedSql;
    this.parameterNames = parameterNames;
  }

  public static NamedParameterSupport parse(String namedSql) {
    List<String> parameterNames = new ArrayList<>();
    Matcher matcher = NAMED_PARAM_PATTERN.matcher(namedSql);
    StringBuffer parsedSql = new StringBuffer();

    while (matcher.find()) {
      String paramName = matcher.group(1);
      parameterNames.add(paramName);
      matcher.appendReplacement(parsedSql, "?");
    }
    matcher.appendTail(parsedSql);

    return new NamedParameterSupport(parsedSql.toString(), parameterNames);
  }

  public String getParsedSql() {
    return parsedSql;
  }

  public void setParameters(PreparedStatement ps, Map<String, Object> params) throws SQLException {
    for (int i = 0; i < parameterNames.size(); i++) {
      String paramName = parameterNames.get(i);

      if (!params.containsKey(paramName)) {
        throw new IllegalArgumentException("Missing parameter: " + paramName);
      }

      Object value = params.get(paramName);
      ps.setObject(i + 1, value);
    }
  }

  public List<String> getParameterNames() {
    return new ArrayList<>(parameterNames);
  }
}
