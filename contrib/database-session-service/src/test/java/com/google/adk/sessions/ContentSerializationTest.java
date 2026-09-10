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
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ContentSerializationTest {

  private static final String TEST_DB_URL =
      "jdbc:h2:mem:testdb_content;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
  private static final String TEST_APP_NAME = "content-test-app";
  private static final String TEST_USER_ID = "content-test-user";

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
    sessionService.close();
  }

  @Test
  public void testTextPartRoundTrip() {
    String sessionId = "text-part-test";
    String testText = "Hello, world! This is a test message.";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Event originalEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("test-author")
            .content(Content.fromParts(Part.fromText(testText)))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, originalEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrievedSession);
    assertEquals(1, retrievedSession.events().size());

    Event retrievedEvent = retrievedSession.events().get(0);
    assertNotNull(retrievedEvent.content());
    assertTrue(retrievedEvent.content().isPresent());

    Content content = retrievedEvent.content().get();
    assertNotNull(content.parts());
    assertTrue(content.parts().isPresent());

    List<Part> parts = content.parts().get();
    assertEquals(1, parts.size());

    Part part = parts.get(0);
    assertTrue(part.text().isPresent());
    assertEquals(testText, part.text().get());
  }

  @Test
  public void testFunctionCallPartRoundTrip() {
    String sessionId = "function-call-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    FunctionCall functionCall =
        FunctionCall.builder()
            .name("get_weather")
            .args(Map.of("location", "San Francisco", "unit", "celsius"))
            .id("call-123")
            .build();

    Event originalEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("model")
            .content(Content.fromParts(Part.builder().functionCall(functionCall).build()))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, originalEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertNotNull(retrievedSession);
    assertEquals(1, retrievedSession.events().size());

    Event retrievedEvent = retrievedSession.events().get(0);
    Content content = retrievedEvent.content().get();
    Part part = content.parts().get().get(0);

    assertTrue(part.functionCall().isPresent());
    FunctionCall retrievedCall = part.functionCall().get();

    assertEquals("get_weather", retrievedCall.name().get());
    assertEquals("call-123", retrievedCall.id().get());

    Map<String, Object> retrievedArgs = retrievedCall.args().get();
    assertEquals("San Francisco", retrievedArgs.get("location"));
    assertEquals("celsius", retrievedArgs.get("unit"));
  }

  @Test
  public void testFileDataPartRoundTrip() {
    String sessionId = "file-data-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    FileData fileData =
        FileData.builder()
            .fileUri("gs://bucket/path/to/file.pdf")
            .mimeType("application/pdf")
            .build();

    Event originalEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("user")
            .content(Content.fromParts(Part.builder().fileData(fileData).build()))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, originalEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    Event retrievedEvent = retrievedSession.events().get(0);
    Part part = retrievedEvent.content().get().parts().get().get(0);

    assertTrue(part.fileData().isPresent());
    FileData retrievedFileData = part.fileData().get();

    assertEquals("gs://bucket/path/to/file.pdf", retrievedFileData.fileUri().get());
    assertEquals("application/pdf", retrievedFileData.mimeType().get());
  }

  @Test
  public void testFunctionResponsePartRoundTrip() {
    String sessionId = "function-response-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    FunctionResponse functionResponse =
        FunctionResponse.builder()
            .name("get_weather")
            .response(Map.of("temperature", 72, "conditions", "sunny"))
            .id("call-123")
            .build();

    Event originalEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("tool")
            .content(Content.fromParts(Part.builder().functionResponse(functionResponse).build()))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, originalEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    Event retrievedEvent = retrievedSession.events().get(0);
    Part part = retrievedEvent.content().get().parts().get().get(0);

    assertTrue(part.functionResponse().isPresent());
    FunctionResponse retrievedResponse = part.functionResponse().get();

    assertEquals("get_weather", retrievedResponse.name().get());
    assertEquals("call-123", retrievedResponse.id().get());

    Map<String, Object> responseData = retrievedResponse.response().get();
    assertEquals(72, responseData.get("temperature"));
    assertEquals("sunny", responseData.get("conditions"));
  }

  @Test
  public void testExecutableCodePartRoundTrip() {
    String sessionId = "executable-code-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    ExecutableCode executableCode = ExecutableCode.builder().code("print('Hello, World!')").build();

    Event originalEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("model")
            .content(Content.fromParts(Part.builder().executableCode(executableCode).build()))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, originalEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    Event retrievedEvent = retrievedSession.events().get(0);
    Part part = retrievedEvent.content().get().parts().get().get(0);

    assertTrue(part.executableCode().isPresent());
    ExecutableCode retrievedCode = part.executableCode().get();

    assertEquals("print('Hello, World!')", retrievedCode.code().get());
  }

  @Test
  public void testMixedPartsInSingleEvent() {
    String sessionId = "mixed-parts-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Part textPart = Part.fromText("Let me call a function:");
    Part functionCallPart =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("calculate")
                    .args(Map.of("expression", "2+2"))
                    .id("calc-1")
                    .build())
            .build();
    Part fileDataPart =
        Part.builder()
            .fileData(
                FileData.builder().fileUri("gs://bucket/data.csv").mimeType("text/csv").build())
            .build();

    Event originalEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("model")
            .content(Content.fromParts(textPart, functionCallPart, fileDataPart))
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, originalEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    Event retrievedEvent = retrievedSession.events().get(0);
    List<Part> parts = retrievedEvent.content().get().parts().get();

    assertEquals(3, parts.size());

    assertTrue(parts.get(0).text().isPresent());
    assertEquals("Let me call a function:", parts.get(0).text().get());

    assertTrue(parts.get(1).functionCall().isPresent());
    assertEquals("calculate", parts.get(1).functionCall().get().name().get());

    assertTrue(parts.get(2).fileData().isPresent());
    assertEquals("gs://bucket/data.csv", parts.get(2).fileData().get().fileUri().get());
  }

  /**
   * Tests that a multi-turn conversation with function calls is correctly serialized and
   * deserialized. This verifies the complete workflow: user message -> model function call -> tool
   * response -> model final response.
   *
   * <p>IMPORTANT: Events are created with incrementing timestamps (100ms apart) to simulate
   * realistic timing. In production, events naturally have different timestamps due to processing
   * delays. Without timestamp separation, events with identical timestamps would have undefined
   * ordering since the database only sorts by timestamp. This test previously failed intermittently
   * because it created all events with Instant.now() within the same millisecond, causing
   * non-deterministic ordering.
   */
  @Test
  public void testMultiTurnConversationWithTools() {
    String sessionId = "multi-turn-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    long baseTimestamp = Instant.now().toEpochMilli();

    Event userMessage =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("user")
            .content(Content.fromParts(Part.fromText("What's the weather in Tokyo?")))
            .timestamp(baseTimestamp)
            .build();

    Event modelFunctionCall =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("model")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("get_weather")
                                .args(Map.of("city", "Tokyo"))
                                .id("weather-1")
                                .build())
                        .build()))
            .timestamp(baseTimestamp + 100)
            .build();

    Event toolResponse =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("tool")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .name("get_weather")
                                .response(Map.of("temp", 18, "condition", "cloudy"))
                                .id("weather-1")
                                .build())
                        .build()))
            .timestamp(baseTimestamp + 200)
            .build();

    Event modelFinalResponse =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("model")
            .content(Content.fromParts(Part.fromText("The weather in Tokyo is 18°C and cloudy.")))
            .timestamp(baseTimestamp + 300)
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, userMessage).blockingGet();

    session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, modelFunctionCall).blockingGet();

    session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, toolResponse).blockingGet();

    session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();
    sessionService.appendEvent(session, modelFinalResponse).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    assertEquals(4, retrievedSession.events().size());

    assertEquals(
        "What's the weather in Tokyo?",
        retrievedSession.events().get(0).content().get().parts().get().get(0).text().get());

    FunctionCall retrievedCall =
        retrievedSession.events().get(1).content().get().parts().get().get(0).functionCall().get();
    assertEquals("get_weather", retrievedCall.name().get());
    assertEquals("Tokyo", retrievedCall.args().get().get("city"));

    FunctionResponse retrievedResponse =
        retrievedSession
            .events()
            .get(2)
            .content()
            .get()
            .parts()
            .get()
            .get(0)
            .functionResponse()
            .get();
    assertEquals("get_weather", retrievedResponse.name().get());
    assertEquals(18, retrievedResponse.response().get().get("temp"));

    assertEquals(
        "The weather in Tokyo is 18°C and cloudy.",
        retrievedSession.events().get(3).content().get().parts().get().get(0).text().get());
  }

  @Test
  public void testEmptyAndNullContent() {
    String sessionId = "empty-content-test";

    sessionService
        .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), sessionId)
        .blockingGet();

    Event emptyContentEvent =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("system")
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Session session =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    sessionService.appendEvent(session, emptyContentEvent).blockingGet();

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, sessionId, Optional.empty())
            .blockingGet();

    Event retrievedEvent = retrievedSession.events().get(0);
    assertTrue(
        retrievedEvent.content().isEmpty() || retrievedEvent.content().get().parts().isEmpty());
  }
}
