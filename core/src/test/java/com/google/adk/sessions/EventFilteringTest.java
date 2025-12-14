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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventFilteringTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:filter_test;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "filter-test-app";
  private static final String TEST_USER_ID = "filter-user";

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
  public void testNumRecentEventsFilter() {
    String sessionId = "recent-events-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 10; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(Instant.now().plusSeconds(i).toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(3).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(3, session.events().size());
  }

  @Test
  public void testNumRecentEventsZero() {
    String sessionId = "zero-events-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(0).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(0, session.events().size());
  }

  @Test
  public void testNumRecentEventsExceedsTotal() {
    String sessionId = "exceed-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 3; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(10).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(3, session.events().size());
  }

  @Test
  public void testAfterTimestampFilter() {
    String sessionId = "timestamp-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant baseTime = Instant.now();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(baseTime.plusSeconds(i).toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    Instant filterTime = baseTime.plusSeconds(3);
    GetSessionConfig config = GetSessionConfig.builder().afterTimestamp(filterTime).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(2, session.events().size());
  }

  @Test
  public void testAfterTimestampNoMatches() {
    String sessionId = "no-matches-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant baseTime = Instant.now();

    for (int i = 1; i <= 3; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(baseTime.plusSeconds(i).toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    Instant futureTime = baseTime.plusSeconds(100);
    GetSessionConfig config = GetSessionConfig.builder().afterTimestamp(futureTime).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(0, session.events().size());
  }

  @Test
  public void testCombinedFilters() {
    String sessionId = "combined-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant baseTime = Instant.now();

    for (int i = 1; i <= 10; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(baseTime.plusSeconds(i).toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    Instant filterTime = baseTime.plusSeconds(3);
    GetSessionConfig config =
        GetSessionConfig.builder().afterTimestamp(filterTime).numRecentEvents(3).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(3, session.events().size());
  }

  @Test
  public void testNoFilterConfiguration() {
    String sessionId = "no-filter-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(session);
    assertEquals(5, session.events().size());
  }

  @Test
  public void testFilteringPreservesOrder() {
    String sessionId = "order-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant baseTime = Instant.now();

    for (int i = 1; i <= 5; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(baseTime.plusSeconds(i).toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(3).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(3, session.events().size());

    assertEquals("event-3", session.events().get(0).id());
    assertEquals("event-4", session.events().get(1).id());
    assertEquals("event-5", session.events().get(2).id());
  }

  @Test
  public void testFilterOnEmptyEventList() {
    String sessionId = "empty-filter-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(5).build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertTrue(session.events().isEmpty());
  }

  @Test
  public void testEventPagination() {
    String sessionId = "pagination-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 20; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    ListEventsResponse page1 =
        sessionService.listEvents(TEST_APP_NAME, TEST_USER_ID, sessionId, 5, null).blockingGet();

    assertNotNull(page1);
    assertEquals(5, page1.events().size());
    assertTrue(page1.nextPageToken().isPresent());

    ListEventsResponse page2 =
        sessionService
            .listEvents(TEST_APP_NAME, TEST_USER_ID, sessionId, 5, page1.nextPageToken().get())
            .blockingGet();

    assertNotNull(page2);
    assertEquals(5, page2.events().size());
    assertTrue(page2.nextPageToken().isPresent());
  }

  @Test
  public void testEventPaginationLastPage() {
    String sessionId = "last-page-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 7; i++) {
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Message " + i)))
              .timestamp(Instant.now().toEpochMilli())
              .build();

      sessionService.appendEvent(TEST_APP_NAME, TEST_USER_ID, sessionId, event).blockingGet();
    }

    ListEventsResponse page1 =
        sessionService.listEvents(TEST_APP_NAME, TEST_USER_ID, sessionId, 5, null).blockingGet();

    assertNotNull(page1);
    assertEquals(5, page1.events().size());

    ListEventsResponse page2 =
        sessionService
            .listEvents(TEST_APP_NAME, TEST_USER_ID, sessionId, 5, page1.nextPageToken().get())
            .blockingGet();

    assertNotNull(page2);
    assertEquals(2, page2.events().size());
    assertTrue(page2.nextPageToken().isEmpty());
  }
}
