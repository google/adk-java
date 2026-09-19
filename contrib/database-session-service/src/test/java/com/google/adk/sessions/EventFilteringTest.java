package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventFilteringTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:filter_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private static final String TEST_APP_NAME = "filter-test-app";
  private static final String TEST_USER_ID = "filter-test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testFilterByNumRecentEvents() throws InterruptedException {
    String sessionId = "recent-events-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 10; i++) {
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
      TimeUnit.MILLISECONDS.sleep(10);
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(3).build();
    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(3, session.events().size());
    assertEquals("event-8", session.events().get(0).id());
    assertEquals("event-9", session.events().get(1).id());
    assertEquals("event-10", session.events().get(2).id());
  }

  @Test
  public void testFilterByAfterTimestamp() throws InterruptedException {
    String sessionId = "timestamp-filter-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Instant startTime = Instant.now();

    for (int i = 1; i <= 5; i++) {
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

    GetSessionConfig config =
        GetSessionConfig.builder().afterTimestamp(startTime.plusSeconds(3)).build();
    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(2, session.events().size());
    assertEquals("event-4", session.events().get(0).id());
    assertEquals("event-5", session.events().get(1).id());
  }

  @Test
  public void testFilterByNumRecentEventsZero() {
    String sessionId = "zero-events-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText("Test")))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, event).blockingGet();

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(0).build();
    Session filteredSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(filteredSession);
    assertEquals(0, filteredSession.events().size());
  }

  @Test
  public void testNoFilterReturnsAllEvents() throws InterruptedException {
    String sessionId = "all-events-test";
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
      TimeUnit.MILLISECONDS.sleep(10);
    }

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(session);
    assertEquals(5, session.events().size());
  }

  @Test
  public void testCombinedFilters() throws InterruptedException {
    String sessionId = "combined-filter-test";
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

    GetSessionConfig config =
        GetSessionConfig.builder()
            .afterTimestamp(startTime.plusSeconds(3))
            .numRecentEvents(3)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertTrue(session.events().size() <= 3);
  }

  @Test
  public void testNoFilterReturnsAllEventsLargeDataset() throws InterruptedException {
    String sessionId = "large-dataset-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 50; i++) {
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
      TimeUnit.MILLISECONDS.sleep(10);
    }

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(session);
    assertEquals(50, session.events().size());
    assertEquals("event-1", session.events().get(0).id());
    assertEquals("event-25", session.events().get(24).id());
    assertEquals("event-50", session.events().get(49).id());
  }

  @Test
  public void testLimitedEventsFromLargeDatasetReturnsCorrectOrder() throws InterruptedException {
    String sessionId = "limited-large-dataset-test";
    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    for (int i = 1; i <= 50; i++) {
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
      TimeUnit.MILLISECONDS.sleep(10);
    }

    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(20).build();
    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    assertNotNull(session);
    assertEquals(20, session.events().size());
    assertEquals("event-31", session.events().get(0).id());
    assertEquals("event-40", session.events().get(9).id());
    assertEquals("event-50", session.events().get(19).id());
  }
}
