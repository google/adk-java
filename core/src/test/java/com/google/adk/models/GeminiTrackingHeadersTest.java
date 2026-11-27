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

package com.google.adk.models;

import static com.google.adk.models.LocalModelServer.GENERATE_CONTENT_JSON;
import static com.google.adk.models.LocalModelServer.headersSentToLocalServer;
import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeNotNull;

import com.google.adk.models.FakeGeminiApi.Kind;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.function.Function;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests the tracking headers sent on Gemini requests after the genai SDK merges them. Both {@code
 * user-agent} and {@code x-goog-api-client} carry one ADK tag and the Java version tag, whoever
 * built the client.
 */
@RunWith(JUnit4.class)
public final class GeminiTrackingHeadersTest {

  private final FakeGeminiApi api = new FakeGeminiApi();

  @Test
  public void generateContent_callerClient_sendsTrackingHeaders() {
    generate(new Gemini("gemini-test", api.client()), request(), /* stream= */ false);

    assertTrackingHeaders(onlyGenerateHeaders()::get, TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContentStream_callerClient_sendsTrackingHeaders() {
    generate(new Gemini("gemini-test", api.client()), request(), /* stream= */ true);

    assertTrackingHeaders(onlyGenerateHeaders()::get, TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_adkBuiltApiKeyClient_sendsTrackingHeaders() throws Exception {
    assertTrackingHeaders(
        headersSentToLocalServer(
                url -> new Gemini("gemini-test", "test-api-key"), GENERATE_CONTENT_JSON)
            ::getFirst,
        TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_adkBuiltBuilderClient_sendsTrackingHeaders() throws Exception {
    assertTrackingHeaders(
        headersSentToLocalServer(
                url -> Gemini.builder().modelName("gemini-test").apiKey("test-api-key").build(),
                GENERATE_CONTENT_JSON)
            ::getFirst,
        TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_adkBuiltRegistryClient_sendsTrackingHeaders() throws Exception {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));

    assertTrackingHeaders(
        headersSentToLocalServer(
                url -> LlmRegistry.getLlm("gemini-tracking-headers-test"), GENERATE_CONTENT_JSON)
            ::getFirst,
        TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_adkBuiltVertexCredentialsClient_sendsTrackingHeaders()
      throws Exception {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));

    // Empty credentials let the environment configure the backend, here the Gemini API key.
    assertTrackingHeaders(
        headersSentToLocalServer(
                url -> new Gemini("gemini-test", VertexCredentials.builder().build()),
                GENERATE_CONTENT_JSON)
            ::getFirst,
        TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_callerClientUserAgent_isExtendedWithAdkTag() {
    generate(
        new Gemini("gemini-test", clientWithHeaders(ImmutableMap.of("user-agent", "my-app/1.0"))),
        request(),
        /* stream= */ false);

    Headers headers = onlyGenerateHeaders();
    assertThat(headers.get("user-agent")).endsWith(" my-app/1.0 " + TrackingHeaders.ADK_TAG);
    assertTrackingHeaders(headers::get, TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_callerRequestHeaders_areExtendedWithAdkTag() {
    generate(
        new Gemini("gemini-test", api.client()),
        requestWithHeaders(ImmutableMap.of("User-Agent", "my-app/1.0", "X-Custom", "v")),
        /* stream= */ false);

    Headers headers = onlyGenerateHeaders();
    assertThat(headers.get("user-agent")).endsWith(" my-app/1.0 " + TrackingHeaders.ADK_TAG);
    assertThat(headers.get("X-Custom")).isEqualTo("v");
    assertTrackingHeaders(headers::get, TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_callerClient_keepsOtherRequestOptions() {
    LlmRequest request =
        request().toBuilder()
            .config(
                GenerateContentConfig.builder()
                    .temperature(0.5f)
                    .httpOptions(HttpOptions.builder().timeout(30_000).build())
                    .build())
            .build();

    generate(new Gemini("gemini-test", api.client()), request, /* stream= */ false);

    Headers headers = onlyGenerateHeaders();
    assertThat(headers.get("X-Server-Timeout")).isEqualTo("30");
    assertThat(api.bodies(Kind.GENERATE).get(0).at("/generationConfig/temperature").asDouble())
        .isEqualTo(0.5);
    assertTrackingHeaders(headers::get, TrackingHeaders.ADK_TAG);
  }

  @Test
  public void generateContent_callerRequestHeaderWithAdkTag_isNotExtended() {
    generate(
        new Gemini("gemini-test", api.client()),
        requestWithHeaders(ImmutableMap.of("user-agent", "my-app/1.0 google-adk/0.0.1")),
        /* stream= */ false);

    Headers headers = onlyGenerateHeaders();
    assertThat(headers.get("user-agent")).endsWith(" my-app/1.0 google-adk/0.0.1");
    assertTrackingHeaders(headers::get, "google-adk/0.0.1");
  }

  private Client clientWithHeaders(Map<String, String> headers) {
    return Client.builder()
        .apiKey("test-api-key")
        .vertexAI(false)
        .httpOptions(HttpOptions.builder().headers(headers).build())
        .clientOptions(
            ClientOptions.builder()
                .customHttpClient(new OkHttpClient.Builder().addInterceptor(api).build())
                .build())
        .build();
  }

  private static LlmRequest request() {
    return LlmRequest.builder()
        .contents(
            ImmutableList.of(
                Content.builder().role("user").parts(Part.fromText("What is the answer?")).build()))
        .build();
  }

  private static LlmRequest requestWithHeaders(ImmutableMap<String, String> headers) {
    return request().toBuilder()
        .config(
            GenerateContentConfig.builder()
                .httpOptions(HttpOptions.builder().headers(headers).build())
                .build())
        .build();
  }

  private static void generate(BaseLlm model, LlmRequest request, boolean stream) {
    assertThat(model.generateContent(request, stream).toList().blockingGet()).isNotEmpty();
  }

  private Headers onlyGenerateHeaders() {
    ImmutableList<Headers> headers = api.headers(Kind.GENERATE);
    assertThat(headers).hasSize(1);
    return headers.get(0);
  }

  /**
   * Asserts both tracking headers as sent: each starts with the genai SDK label, includes the Java
   * version tag and one ADK tag, and repeats no tag name. The {@code x-goog-api-client} ADK tag is
   * always {@link TrackingHeaders#ADK_TAG}; the {@code user-agent} tag is {@code userAgentAdkTag}.
   */
  private static void assertTrackingHeaders(
      Function<String, String> headers, String userAgentAdkTag) {
    ImmutableMap<String, String> adkTags =
        ImmutableMap.of(
            "user-agent", userAgentAdkTag, "x-goog-api-client", TrackingHeaders.ADK_TAG);
    adkTags.forEach(
        (name, adkTag) -> {
          ImmutableList<String> tags =
              ImmutableList.copyOf(Splitter.on(' ').split(headers.apply(name)));
          assertThat(tags.get(0)).startsWith("google-genai-sdk/");
          assertThat(tags).containsAtLeast("gl-java/" + JAVA_VERSION.value(), adkTag);
          assertThat(tags.stream().map(tag -> tag.split("/", 2)[0]).collect(toImmutableList()))
              .containsNoDuplicates();
        });
  }
}
