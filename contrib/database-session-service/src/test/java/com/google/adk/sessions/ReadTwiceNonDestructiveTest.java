/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ReadTwiceNonDestructiveTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:read_twice_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "read-twice-test-app";
  private static final String TEST_USER_ID = "read-twice-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(TEST_DB_URL, null, null)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testGetSessionTwiceDoesNotDeleteEvents() {
    String sessionId = "non-destructive-read-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    Session firstRead =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(firstRead);
    assertEquals(5, firstRead.events().size(), "First read should return 5 events");

    Session secondRead =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(secondRead);
    assertEquals(5, secondRead.events().size(), "Second read should still return 5 events");

    Session thirdRead =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertNotNull(thirdRead);
    assertEquals(5, thirdRead.events().size(), "Third read should still return 5 events");
  }

  @Test
  public void testGetSessionMultipleTimesWithFiltering() {
    String sessionId = "filter-read-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant startTime = Instant.now();

    for (int i = 1; i <= 10; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(startTime.plusSeconds(i).toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    Session allEvents =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertEquals(10, allEvents.events().size());

    GetSessionConfig recentConfig = GetSessionConfig.builder().numRecentEvents(3).build();
    Session recentEvents =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(recentConfig))
            .blockingGet();
    assertEquals(3, recentEvents.events().size());

    Session allEventsAgain =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertEquals(10, allEventsAgain.events().size(), "All events should still exist in DB");

    GetSessionConfig timestampConfig =
        GetSessionConfig.builder().afterTimestamp(startTime.plusSeconds(5)).build();
    Session filteredByTime =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(timestampConfig))
            .blockingGet();
    assertEquals(5, filteredByTime.events().size());

    Session finalRead =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertEquals(10, finalRead.events().size(), "Events should persist after all filtered reads");
  }

  @Test
  public void testConcurrentReadsDoNotAffectData() throws InterruptedException {
    String sessionId = "concurrent-read-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id(UUID.randomUUID().toString())
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    Thread[] readers = new Thread[10];
    for (int i = 0; i < 10; i++) {
      readers[i] =
          new Thread(
              () -> {
                for (int j = 0; j < 5; j++) {
                  Session session =
                      sessionService
                          .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
                          .blockingGet();
                  assertEquals(
                      5, session.events().size(), "Each concurrent read should return 5 events");
                }
              });
      readers[i].start();
    }

    for (Thread reader : readers) {
      reader.join();
    }

    Session finalCheck =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertEquals(
        5, finalCheck.events().size(), "Events should remain intact after concurrent reads");
  }

  @Test
  public void testReadWithDifferentConfigs() {
    String sessionId = "config-variation-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant startTime = Instant.now();

    for (int i = 1; i <= 10; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test-author")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(startTime.plusSeconds(i).toEpochMilli())
              .build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
    }

    for (int recentCount = 1; recentCount <= 10; recentCount++) {
      GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(recentCount).build();
      Session session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
              .blockingGet();
      assertEquals(
          recentCount, session.events().size(), "Should get " + recentCount + " recent events");
    }

    Session fullRead =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    assertEquals(
        10,
        fullRead.events().size(),
        "All 10 events should still exist after multiple config reads");
  }
}
