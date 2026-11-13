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
package consumer;

import com.google.adk.events.Event;
import com.google.adk.sessions.DatabaseSessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.sqlite.SQLiteDataSource;

/** Packaged ADK contrib under Boot 4.1 dependency management, with no Spring runtime required. */
public final class BootBomConsumer {
  public static void main(String[] arguments) throws Exception {
    Properties artifact = new Properties();
    try (var input =
        Flyway.class.getResourceAsStream(
            "/META-INF/maven/org.flywaydb/flyway-core/pom.properties")) {
      artifact.load(input);
    }
    if (!"12.4.0".equals(artifact.getProperty("version"))) {
      throw new IllegalStateException("Boot consumer must actually resolve Flyway 12.4.0.");
    }
    Path file = Path.of(arguments[0]).toAbsolutePath();
    Files.createDirectories(file.getParent());
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + file);
    Flyway application =
        new FluentConfiguration()
            .dataSource(source)
            .locations("classpath:application")
            .loggers(new String[0])
            .load();
    application.migrate();
    DatabaseSessionService service = new DatabaseSessionService(source);
    service.migrate().blockingAwait();
    application.migrate();
    var session = service.createSession("boot", "user").blockingGet();
    service.appendEvent(session, Event.builder().id("event").timestamp(123).build()).blockingGet();
    var restored =
        new DatabaseSessionService(source).getSession(session.sessionKey(), null).blockingGet();
    if (restored == null || restored.immutableEvents().size() != 1) {
      throw new IllegalStateException("Consumer persistence failed.");
    }
    System.out.println("ADK_BOOT_BOM_CONSUMER_OK boot=4.1.2 flyway=12.4.0");
  }
}
