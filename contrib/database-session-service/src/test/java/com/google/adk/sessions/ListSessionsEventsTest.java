package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ListSessionsEventsTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:list_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
  private static final String TEST_APP_NAME = "list-test-app";
  private static final String TEST_USER_ID = "list-test-user";

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
  public void testListSessionsReturnsAllSessions() {
    String userId = "list-sessions-user";
    for (int i = 1; i <= 50; i++) {
      sessionService
          .createSession(TEST_APP_NAME, userId, new ConcurrentHashMap<>(), "session-" + i)
          .blockingGet();
    }

    ListSessionsResponse response =
        sessionService.listSessions(TEST_APP_NAME, userId).blockingGet();

    assertNotNull(response);
    assertEquals(50, response.sessions().size());
  }

  @Test
  public void testListEventsReturnsAllEvents() throws InterruptedException {
    String userId = "list-events-user";
    String sessionId = "all-events-test";
    sessionService
        .createSession(TEST_APP_NAME, userId, new ConcurrentHashMap<>(), sessionId)
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
              .getSession(TEST_APP_NAME, userId, sessionId, Optional.empty())
              .blockingGet();

      sessionService.appendEvent(session, event).blockingGet();
      TimeUnit.MILLISECONDS.sleep(10);
    }

    ListEventsResponse response =
        sessionService.listEvents(TEST_APP_NAME, userId, sessionId).blockingGet();

    assertNotNull(response);
    assertEquals(50, response.events().size());
    assertEquals("event-1", response.events().get(0).id());
    assertEquals("event-25", response.events().get(24).id());
    assertEquals("event-50", response.events().get(49).id());
  }
}
