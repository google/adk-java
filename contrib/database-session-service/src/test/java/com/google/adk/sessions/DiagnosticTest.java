package com.google.adk.sessions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.adk.events.Event;
import com.google.adk.testing.TestDatabaseConfig;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
public class DiagnosticTest {
  private static final String TEST_DB_URL = TestDatabaseConfig.MYSQL_JDBC_URL;
  private static final String TEST_APP_NAME = "diagnostic-test";
  private static final String TEST_USER_ID = "diagnostic-user";
  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    assumeTrue(
        TestDatabaseConfig.isMySQLAvailable(),
        TestDatabaseConfig.getDatabaseNotAvailableMessage("MySQL"));
    sessionService = new DatabaseSessionService(TEST_DB_URL, new java.util.HashMap<>());
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void diagnosticAfterTimestampFiltering() {
    String sessionId = "diag-" + System.currentTimeMillis();
    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
            .blockingGet();

    Instant baseTime = Instant.now();
    System.out.println("Base time: " + baseTime);

    // Create 10 events
    for (int i = 1; i <= 10; i++) {
      Instant eventTime = baseTime.plusSeconds(i);
      Event event =
          Event.builder()
              .id("event-" + i)
              .author("test")
              .content(Content.fromParts(Part.fromText("Event " + i)))
              .timestamp(eventTime.toEpochMilli())
              .build();
      session =
          sessionService
              .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
              .blockingGet();
      sessionService.appendEvent(session, event).blockingGet();
      System.out.println(
          "Created event-"
              + i
              + " with timestamp: "
              + eventTime
              + " ("
              + eventTime.toEpochMilli()
              + ")");
      try {
        TimeUnit.MILLISECONDS.sleep(5);
      } catch (InterruptedException e) {
      }
    }

    // Get all events
    Session allEvents =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    System.out.println("\n=== ALL EVENTS ===");
    for (Event e : allEvents.events()) {
      System.out.println(
          e.id() + ": " + e.timestamp() + " (" + Instant.ofEpochMilli(e.timestamp()) + ")");
    }

    // Filter after 5 seconds
    Instant threshold = baseTime.plusSeconds(5);
    System.out.println(
        "\n=== FILTERING AFTER: " + threshold + " (" + threshold.toEpochMilli() + ") ===");
    GetSessionConfig config = GetSessionConfig.builder().afterTimestamp(threshold).build();
    Session filtered =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.of(config))
            .blockingGet();

    System.out.println("Expected ~5 events (event-6 through event-10)");
    System.out.println("Actually got: " + filtered.events().size() + " events");
    for (Event e : filtered.events()) {
      System.out.println(
          "  " + e.id() + ": " + e.timestamp() + " (" + Instant.ofEpochMilli(e.timestamp()) + ")");
    }

    assertEquals(5, filtered.events().size(), "Should get exactly 5 events after threshold");
    assertEquals("event-6", filtered.events().get(0).id());
  }
}
