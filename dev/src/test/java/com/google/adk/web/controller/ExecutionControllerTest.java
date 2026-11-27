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

package com.google.adk.web.controller;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Tests request validation for {@code POST /run} and {@code POST /run_sse}. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "logging.level.com.google.adk.web.controller.ExecutionController=INFO")
@ExtendWith(OutputCaptureExtension.class)
final class ExecutionControllerTest {

  private static final HttpClient httpClient = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  private static ImmutableList<InvalidRequest> invalidRequests() {
    return ImmutableList.of(
        new InvalidRequest(
            "/run",
            """
            {"sessionId": "secret-run-null-app-session"}
            """,
            "appName",
            "secret-run-null-app-session"),
        new InvalidRequest(
            "/run",
            """
            {"appName": "   ", "sessionId": "secret-run-blank-app-session"}
            """,
            "appName",
            "secret-run-blank-app-session"),
        new InvalidRequest(
            "/run",
            """
            {"appName": "secret-run-null-session-app"}
            """,
            "sessionId",
            "secret-run-null-session-app"),
        new InvalidRequest(
            "/run",
            """
            {"appName": "secret-run-blank-session-app", "sessionId": "   "}
            """,
            "sessionId",
            "secret-run-blank-session-app"),
        new InvalidRequest(
            "/run_sse",
            """
            {"sessionId": "secret-sse-null-app-session"}
            """,
            "appName",
            "secret-sse-null-app-session"),
        new InvalidRequest(
            "/run_sse",
            """
            {"appName": "   ", "sessionId": "secret-sse-blank-app-session"}
            """,
            "appName",
            "secret-sse-blank-app-session"),
        new InvalidRequest(
            "/run_sse",
            """
            {"appName": "secret-sse-null-session-app"}
            """,
            "sessionId",
            "secret-sse-null-session-app"),
        new InvalidRequest(
            "/run_sse",
            """
            {"appName": "secret-sse-blank-session-app", "sessionId": "   "}
            """,
            "sessionId",
            "secret-sse-blank-session-app"));
  }

  @ParameterizedTest
  @MethodSource("invalidRequests")
  void runEndpoints_blankRequiredField_answers400WithoutLoggingCallerValues(
      InvalidRequest invalidRequest, CapturedOutput output) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + invalidRequest.path()))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(invalidRequest.body(), UTF_8))
            .build();

    HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString(UTF_8));

    assertThat(response.statusCode()).isEqualTo(400);
    if (invalidRequest.path().equals("/run_sse")) {
      assertThat(response.body()).doesNotContain("data:");
    }
    assertThat(output.getAll())
        .contains(
            invalidRequest.missingField()
                + " cannot be null or empty in POST "
                + invalidRequest.path()
                + " request.");
    assertThat(output.getAll()).doesNotContain(invalidRequest.callerValue());
  }

  private record InvalidRequest(
      String path, String body, String missingField, String callerValue) {}
}
