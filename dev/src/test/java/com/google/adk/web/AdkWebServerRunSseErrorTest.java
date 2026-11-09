/*
 * Copyright 2026 Google LLC
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

package com.google.adk.web;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.common.base.Splitter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Tests what an HTTP client receives from {@code /run_sse} when the run fails. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    // The error event carries a stack trace only under debug logging, so pin it off.
    properties = "logging.level.com.google.adk.web.controller.ExecutionController=INFO")
@ExtendWith(OutputCaptureExtension.class)
final class AdkWebServerRunSseErrorTest {

  private static final String FAILS_AFTER_EVENT_APP = "fails_after_event_app";
  private static final String FAILS_AT_START_APP = "fails_at_start_app";
  private static final String USER_ID = "user";
  private static final Instant NOW = Instant.ofEpochMilli(1_767_225_600_123L);

  private static final HttpClient httpClient = HttpClient.newHttpClient();
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @TestConfiguration
  static class Apps {
    @Bean
    AppLoader appLoader() {
      return new InMemoryAppLoader(
          App.builder()
              .name(FAILS_AFTER_EVENT_APP)
              .rootAgent(new FailingAgent(/* eventFirst= */ true))
              .build(),
          App.builder()
              .name(FAILS_AT_START_APP)
              .rootAgent(new FailingAgent(/* eventFirst= */ false))
              .build());
    }

    @Bean
    @Primary
    InstantSource fixedInstantSource() {
      return InstantSource.fixed(NOW);
    }
  }

  @LocalServerPort private int port;

  @Test
  void runSse_runFailsAfterAnEvent_endsTheStreamWithAnErrorEvent(CapturedOutput output)
      throws Exception {
    HttpResponse<String> response = runSse(FAILS_AFTER_EVENT_APP);

    assertThat(response.statusCode()).isEqualTo(200);
    List<String> frames = frames(response);
    assertThat(frames).hasSize(2);
    assertThat(frames.get(0)).contains("\"text\":\"working\"");
    JsonNode error = data(frames.get(1));
    assertThat(error.get("error").asText())
        .isEqualTo("IllegalStateException: run failed on purpose");
    JsonNode details = error.get("error_details");
    assertThat(details.get("error_type").asText()).isEqualTo("IllegalStateException");
    assertThat(details.get("error_message").asText()).isEqualTo("run failed on purpose");
    // Seconds since the epoch, as Python's time.time().
    assertThat(details.get("timestamp").asDouble()).isEqualTo(1_767_225_600.123);
    // The server logs the failure by type only: its message can carry user or model content.
    assertThat(output.getAll()).contains("java.lang.IllegalStateException");
    assertThat(output.getAll()).doesNotContain("run failed on purpose");
  }

  @Test
  void runSse_runFailsBeforeAnyEvent_sendsOnlyTheErrorEventWithoutAStackTrace() throws Exception {
    HttpResponse<String> response = runSse(FAILS_AT_START_APP);

    assertThat(response.statusCode()).isEqualTo(200);
    List<String> frames = frames(response);
    assertThat(frames).hasSize(1);
    JsonNode error = data(frames.get(0));
    assertThat(error.get("error").asText())
        .isEqualTo("IllegalStateException: run failed on purpose");
    JsonNode details = error.get("error_details");
    assertThat(details.get("error_type").asText()).isEqualTo("IllegalStateException");
    assertThat(details.get("error_message").asText()).isEqualTo("run failed on purpose");
    assertThat(details.get("timestamp").asDouble()).isEqualTo(1_767_225_600.123);
    // Debug logging is off, so the stack trace stays on the server.
    assertThat(details.has("stacktrace")).isFalse();
  }

  @Test
  void runSse_unknownSession_answers404WithoutAStream() throws Exception {
    HttpResponse<String> response = runSse(FAILS_AT_START_APP, UUID.randomUUID().toString());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).doesNotContain("data:");
  }

  @Test
  void runSse_sessionLookupFails_answers500AndLogsOnlyTheType(CapturedOutput output)
      throws Exception {
    // Without a userId the session lookup throws.
    HttpResponse<String> response =
        post(
            "/run_sse",
            String.format(
                """
                {"appName": "%s", "sessionId": "%s",
                 "newMessage": {"role": "user", "parts": [{"text": "hi"}]}}
                """,
                FAILS_AT_START_APP, UUID.randomUUID()));

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).doesNotContain("data:");
    assertThat(output.getAll()).contains("Session lookup failed: java.lang.NullPointerException");
  }

  /** Creates a session in {@code appName} and runs one turn in it over {@code /run_sse}. */
  private HttpResponse<String> runSse(String appName) throws Exception {
    String sessionId = UUID.randomUUID().toString();
    HttpResponse<String> created =
        post("/apps/" + appName + "/users/" + USER_ID + "/sessions/" + sessionId, "{}");
    assertThat(created.statusCode()).isEqualTo(200);
    return runSse(appName, sessionId);
  }

  private HttpResponse<String> runSse(String appName, String sessionId) throws Exception {
    return post(
        "/run_sse",
        String.format(
            """
            {"appName": "%s", "userId": "%s", "sessionId": "%s",
             "newMessage": {"role": "user", "parts": [{"text": "hi"}]}}
            """,
            appName, USER_ID, sessionId));
  }

  private static List<String> frames(HttpResponse<String> response) {
    return Splitter.on("\n\n").omitEmptyStrings().splitToList(response.body());
  }

  private static JsonNode data(String frame) throws Exception {
    assertThat(frame).startsWith("data:");
    return objectMapper.readTree(frame.substring("data:".length()));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body, UTF_8))
            .build();
    return httpClient.send(request, BodyHandlers.ofString(UTF_8));
  }

  /**
   * Fails its run. With {@code eventFirst} it emits one event first, as a run does when a tool call
   * fails after the model answered; without, it fails before any event.
   */
  private static final class FailingAgent extends BaseAgent {
    private final boolean eventFirst;

    FailingAgent(boolean eventFirst) {
      super("failing_agent", "", null, null, null);
      this.eventFirst = eventFirst;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      Flowable<Event> failure = Flowable.error(new IllegalStateException("run failed on purpose"));
      if (!eventFirst) {
        return failure;
      }
      Event event =
          Event.builder()
              .invocationId(invocationContext.invocationId())
              .author(name())
              .content(Content.builder().role("model").parts(Part.fromText("working")).build())
              .build();
      return Flowable.concat(Flowable.just(event), failure);
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return runAsyncImpl(invocationContext);
    }
  }
}
