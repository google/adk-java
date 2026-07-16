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

package com.google.adk.models.sarvamai.tts;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.models.sarvamai.SarvamAiException;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Sandeep Belgavi
 */
class SarvamTtsServiceTest {

  private MockWebServer server;
  private SarvamTtsService ttsService;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("test-tts-key")
            .ttsEndpoint(server.url("/text-to-speech").toString())
            .ttsModel("bulbul:v3")
            .ttsSpeaker("shubh")
            .build();

    ttsService = new SarvamTtsService(config, new OkHttpClient());
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void synthesize_success() throws InterruptedException {
    byte[] expectedAudio = "fake-audio-data".getBytes();
    String base64Audio = Base64.getEncoder().encodeToString(expectedAudio);
    String responseBody =
        String.format("{\"request_id\":\"req-456\",\"audios\":[\"%s\"]}", base64Audio);

    server.enqueue(new MockResponse().setBody(responseBody));

    byte[] audio = ttsService.synthesize("Hello world", "en-IN");

    assertThat(audio).isEqualTo(expectedAudio);

    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded.getHeader("api-subscription-key")).isEqualTo("test-tts-key");
    String body = recorded.getBody().readUtf8();
    assertThat(body).contains("\"model\":\"bulbul:v3\"");
    assertThat(body).contains("\"speaker\":\"shubh\"");
    assertThat(body).contains("\"target_language_code\":\"en-IN\"");
  }

  @Test
  void synthesize_serverError_throwsException() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Server error"));

    assertThrows(SarvamAiException.class, () -> ttsService.synthesize("Hello", "en-IN"));
  }

  @Test
  void synthesize_emptyAudio_throwsException() {
    server.enqueue(new MockResponse().setBody("{\"request_id\":\"req-789\",\"audios\":[]}"));

    assertThrows(SarvamAiException.class, () -> ttsService.synthesize("Hello", "en-IN"));
  }

  @Test
  void synthesize_nullText_throwsNpe() {
    assertThrows(NullPointerException.class, () -> ttsService.synthesize(null, "en-IN"));
  }

  @Test
  void isAvailable_returnsTrue() {
    assertThat(ttsService.isAvailable()).isTrue();
  }
}
