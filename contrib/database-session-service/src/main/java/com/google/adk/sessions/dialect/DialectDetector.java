package com.google.adk.sessions.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

public class DialectDetector {

  public static SqlDialect detect(Connection connection) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    String productName = metaData.getDatabaseProductName().toLowerCase();

    if (productName.contains("postgresql")) {
      return new PostgresDialect();
    } else if (productName.contains("mysql")) {
      return new MySqlDialect();
    } else if (productName.contains("h2")) {
      return new H2Dialect();
    } else if (productName.contains("spanner")) {
      return new SpannerDialect();
    } else {
      throw new IllegalArgumentException(
          "Unsupported database: "
              + productName
              + ". "
              + "Supported databases: PostgreSQL, MySQL, H2, Cloud Spanner");
    }
  }

  public static SqlDialect detectFromJdbcUrl(String jdbcUrl) {
    String url = jdbcUrl.toLowerCase();

    if (url.startsWith("jdbc:postgresql:")) {
      return new PostgresDialect();
    } else if (url.startsWith("jdbc:mysql:")) {
      return new MySqlDialect();
    } else if (url.startsWith("jdbc:h2:")) {
      return new H2Dialect();
    } else if (url.startsWith("jdbc:cloudspanner:")) {
      return new SpannerDialect();
    } else {
      throw new IllegalArgumentException("Cannot detect dialect from JDBC URL: " + jdbcUrl);
    }
  }
}
