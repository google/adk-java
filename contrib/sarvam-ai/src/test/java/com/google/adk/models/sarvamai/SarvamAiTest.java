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

package com.google.adk.models.sarvamai;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import java.util.Collections;
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
class SarvamAiTest {

  private MockWebServer server;
  private SarvamAi sarvamAi;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("test-api-key")
            .chatEndpoint(server.url("/v1/chat/completions").toString())
            .build();

    sarvamAi =
        SarvamAi.builder()
            .modelName("sarvam-m")
            .config(config)
            .httpClient(new OkHttpClient())
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void generateContent_nonStreaming_returnsContent() {
    String jsonResponse =
        "{\"id\":\"chatcmpl-abc\",\"object\":\"chat.completion\",\"created\":1699000000,"
            + "\"model\":\"sarvam-m\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    server.enqueue(new MockResponse().setBody(jsonResponse));

    LlmRequest request = buildUserRequest("Hi");
    TestSubscriber<LlmResponse> subscriber = sarvamAi.generateContent(request, false).test();

    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertNoErrors();
    subscriber.assertValueCount(1);

    LlmResponse response = subscriber.values().get(0);
    assertThat(response.content().flatMap(Content::parts).get().get(0).text().get())
        .isEqualTo("Hello world");
  }

  @Test
  void generateContent_streaming_returnsChunks() {
    String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n";
    String chunk2 = "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n";
    String done = "data: [DONE]\n\n";

    server.enqueue(new MockResponse().setBody(chunk1 + chunk2 + done));

    LlmRequest request = buildUserRequest("Hi");
    TestSubscriber<LlmResponse> subscriber = sarvamAi.generateContent(request, true).test();

    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertNoErrors();
    subscriber.assertValueCount(2);

    assertThat(
            subscriber.values().get(0).content().flatMap(Content::parts).get().get(0).text().get())
        .isEqualTo("Hello");
    assertThat(
            subscriber.values().get(1).content().flatMap(Content::parts).get().get(0).text().get())
        .isEqualTo(" world");
  }

  @Test
  void generateContent_streamingChunksAreMarkedPartial() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"test\"}}]}\n\ndata: [DONE]\n\n"));

    LlmRequest request = buildUserRequest("Hi");
    TestSubscriber<LlmResponse> subscriber = sarvamAi.generateContent(request, true).test();

    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertNoErrors();
    LlmResponse response = subscriber.values().get(0);
    assertThat(response.partial().orElse(false)).isTrue();
  }

  @Test
  void generateContent_serverError_propagatesException() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(500)
            .setBody(
                "{\"error\":{\"message\":\"Internal error\",\"code\":\"internal_server_error\"}}"));

    LlmRequest request = buildUserRequest("Hi");
    TestSubscriber<LlmResponse> subscriber = sarvamAi.generateContent(request, false).test();

    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertError(SarvamAiException.class);
  }

  @Test
  void generateContent_usesCorrectAuthHeader() throws InterruptedException {
    server.enqueue(
        new MockResponse()
            .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"));

    sarvamAi.generateContent(buildUserRequest("Hi"), false).blockingSubscribe();

    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded).isNotNull();
    assertThat(recorded.getHeader("api-subscription-key")).isEqualTo("test-api-key");
  }

  @Test
  void generateContent_setsStreamFlagInBody() throws InterruptedException {
    String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n";
    server.enqueue(new MockResponse().setBody(chunk));

    sarvamAi.generateContent(buildUserRequest("Hello"), true).blockingSubscribe();

    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded).isNotNull();
    String body = recorded.getBody().readUtf8();
    assertThat(body).contains("\"stream\":true");
  }

  @Test
  void generateContent_mapsModelRoleToAssistant() throws InterruptedException {
    server.enqueue(
        new MockResponse()
            .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"));

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                java.util.List.of(
                    Content.builder().role("user").parts(Part.fromText("Hi")).build(),
                    Content.builder().role("model").parts(Part.fromText("Hello")).build(),
                    Content.builder().role("user").parts(Part.fromText("How?")).build()))
            .build();

    sarvamAi.generateContent(request, false).blockingSubscribe();

    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    String body = recorded.getBody().readUtf8();
    assertThat(body).contains("\"role\":\"assistant\"");
    assertThat(body).doesNotContain("\"role\":\"model\"");
  }

  @Test
  void builder_requiresModelName() {
    assertThrows(
        NullPointerException.class,
        () -> SarvamAi.builder().config(SarvamAiConfig.builder().apiKey("key").build()).build());
  }

  @Test
  void builder_requiresConfig() {
    assertThrows(
        NullPointerException.class, () -> SarvamAi.builder().modelName("sarvam-m").build());
  }

  private LlmRequest buildUserRequest(String text) {
    return LlmRequest.builder()
        .contents(
            Collections.singletonList(
                Content.builder().role("user").parts(Part.fromText(text)).build()))
        .build();
  }
}
