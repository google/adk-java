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

package com.google.adk.models.sarvamai.stt;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import java.io.IOException;
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
class SarvamSttServiceTest {

  private MockWebServer server;
  private SarvamSttService sttService;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("test-stt-key")
            .sttEndpoint(server.url("/speech-to-text").toString())
            .sttModel("saaras:v3")
            .sttMode("transcribe")
            .sttLanguageCode("hi-IN")
            .build();

    sttService = new SarvamSttService(config, new OkHttpClient());
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void transcribe_success() throws TranscriptionException, InterruptedException {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"request_id\":\"req-123\",\"transcript\":\"नमस्ते\",\"language_code\":\"hi-IN\"}"));

    TranscriptionConfig requestConfig =
        TranscriptionConfig.builder()
            .endpoint(server.url("/speech-to-text").toString())
            .language("hi-IN")
            .build();

    TranscriptionResult result = sttService.transcribe(new byte[] {1, 2, 3}, requestConfig);

    assertThat(result.getText()).isEqualTo("नमस्ते");
    assertThat(result.getLanguage().orElse("")).isEqualTo("hi-IN");

    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded.getHeader("api-subscription-key")).isEqualTo("test-stt-key");
  }

  @Test
  void transcribe_serverError_throwsException() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Server error"));

    TranscriptionConfig requestConfig =
        TranscriptionConfig.builder()
            .endpoint(server.url("/speech-to-text").toString())
            .language("hi-IN")
            .build();

    assertThrows(
        TranscriptionException.class,
        () -> sttService.transcribe(new byte[] {1, 2, 3}, requestConfig));
  }

  @Test
  void isAvailable_returnsTrue() {
    assertThat(sttService.isAvailable()).isTrue();
  }

  @Test
  void getServiceType_returnsSarvam() {
    assertThat(sttService.getServiceType().getValue()).isEqualTo("sarvam");
  }
}
