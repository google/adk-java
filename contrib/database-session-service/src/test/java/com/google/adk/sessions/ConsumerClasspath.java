/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.adk.sessions.DatabaseSessionService.Dialect;
import com.google.adk.sessions.consumer.ConsumerProbe;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Real consumer process classpaths, not mocks of optional dependency behavior. */
final class ConsumerClasspath {
  private ConsumerClasspath() {}

  static void verify(String url, String username, String password, Dialect dialect)
      throws Exception {
    for (boolean migrations : new boolean[] {true, false}) {
      String original =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      String classpath =
          Arrays.stream(original.split(java.util.regex.Pattern.quote(File.pathSeparator)))
              .filter(entry -> include(entry, dialect, migrations))
              .collect(Collectors.joining(File.pathSeparator));
      String executable =
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString();
      ProcessBuilder builder =
          new ProcessBuilder(
                  executable,
                  "-cp",
                  classpath,
                  ConsumerProbe.class.getName(),
                  dialect.name(),
                  Boolean.toString(migrations))
              .redirectErrorStream(true);
      builder.environment().put("ADK_TEST_JDBC_URL", url);
      builder.environment().put("ADK_TEST_USERNAME", username);
      builder.environment().put("ADK_TEST_PASSWORD", password);
      // Embedded ADK migrations must not adopt unrelated process-wide Flyway configuration.
      builder.environment().put("FLYWAY_URL", "jdbc:invalid:consumer-isolation-probe");
      builder.environment().put("FLYWAY_LOCATIONS", "classpath:not_an_adk_location");
      builder.environment().put("FLYWAY_CALLBACKS", "missing_callback_for_isolation_probe");
      Process process = builder.start();
      try {
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertWithMessage(output).that(process.exitValue()).isEqualTo(0);
        assertThat(output).contains("ADK_CONSUMER_OK");
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  private static boolean include(String entry, Dialect dialect, boolean migrations) {
    String name = Path.of(entry).getFileName().toString().toLowerCase(Locale.ROOT);
    for (String excluded :
        List.of(
            "mockito",
            "junit",
            "testcontainers",
            "hikaricp",
            "byte-buddy",
            "objenesis",
            "surefire",
            "truth-",
            "slf4j-simple")) {
      if (name.contains(excluded)) return false;
    }
    if (name.endsWith("-tests.jar")) return false;
    if (name.startsWith("sqlite-jdbc") && dialect != Dialect.SQLITE) return false;
    if (name.startsWith("postgresql-") && dialect != Dialect.POSTGRESQL) return false;
    if (name.startsWith("mysql-connector-j-") && dialect != Dialect.MYSQL) return false;
    if (name.startsWith("mariadb-java-client-") && dialect != Dialect.MARIADB) return false;
    if (name.startsWith("flyway-") && !migrations) return false;
    if (name.startsWith("flyway-database-postgresql") && dialect != Dialect.POSTGRESQL)
      return false;
    return !name.startsWith("flyway-mysql")
        || dialect == Dialect.MYSQL
        || dialect == Dialect.MARIADB;
  }
}
