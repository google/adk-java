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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests the API version on the path of requests from the genai clients ADK builds. */
@RunWith(JUnit4.class)
public final class GeminiApiVersionTest {

  @Test
  public void apiKeyClient_usesGeminiApiVersion() throws Exception {
    assumeGeminiApiWithoutVersion();

    assertThat(pathRequestedBy(serverUrl -> new Gemini("gemini-test", "test-api-key")))
        .isEqualTo("/v1beta/models/gemini-test:generateContent");
  }

  @Test
  public void builderApiKeyClient_usesGeminiApiVersion() throws Exception {
    assumeGeminiApiWithoutVersion();

    assertThat(
            pathRequestedBy(serverUrl ->
                Gemini.builder().modelName("gemini-test").apiKey("test-api-key").build()))
        .isEqualTo("/v1beta/models/gemini-test:generateContent");
  }

  @Test
  public void defaultClient_usesGeminiApiVersion() throws Exception {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));
    assumeGeminiApiWithoutVersion();

    assertThat(pathRequestedBy(serverUrl -> Gemini.builder().modelName("gemini-test").build()))
        .isEqualTo("/v1beta/models/gemini-test:generateContent");
  }

  @Test
  public void apiKeyClient_apiVersionFromEnvironment_isKept() throws Exception {
    String apiVersion = System.getenv("GOOGLE_GENAI_API_VERSION");
    // The SDK maps "beta" to v1beta, so only a literal version appears in the path.
    assumeTrue(apiVersion != null && apiVersion.startsWith("v"));
    assumeGeminiApi();

    assertThat(pathRequestedBy(serverUrl -> new Gemini("gemini-test", "test-api-key")))
        .isEqualTo("/" + apiVersion + "/models/gemini-test:generateContent");
  }

  @Test
  public void apigeeGeminiModelWithoutVersion_usesGeminiApiVersion() throws Exception {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));
    assumeGeminiApiWithoutVersion();

    assertThat(
            pathRequestedBy(serverUrl ->
                ApigeeLlm.builder()
                    .modelName("apigee/gemini/gemini-test")
                    .proxyUrl(serverUrl)
                    .build()))
        .isEqualTo("/v1beta/models/gemini-test:generateContent");
  }

  @Test
  public void apigeeGeminiModelWithVersion_keepsItsVersion() throws Exception {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));
    assumeGeminiApi();

    assertThat(
            pathRequestedBy(serverUrl ->
                ApigeeLlm.builder()
                    .modelName("apigee/gemini/v1/gemini-test")
                    .proxyUrl(serverUrl)
                    .build()))
        .isEqualTo("/v1/models/gemini-test:generateContent");
  }

  @Test
  public void buildOnGeminiApiVersion_explicitVersion_isKept() throws Exception {
    assumeGeminiApi();

    assertThat(
            pathRequestedBy(serverUrl ->
                new Gemini(
                    "gemini-test",
                    Gemini.buildOnGeminiApiVersion(
                        Client.builder().apiKey("test-api-key"),
                        HttpOptions.builder().apiVersion("v1alpha").build()))))
        .isEqualTo("/v1alpha/models/gemini-test:generateContent");
  }

  @Test
  public void buildOnGeminiApiVersion_vertexClient_keepsSdkDefault() throws Exception {
    assumeApiVersionUnset();

    assertThat(
            pathRequestedBy(serverUrl ->
                new Gemini(
                    "gemini-test",
                    Gemini.buildOnGeminiApiVersion(
                        Client.builder().apiKey("test-api-key").vertexAI(true),
                        HttpOptions.builder().build()))))
        .isEqualTo("/v1beta1/publishers/google/models/gemini-test:generateContent");
  }

  private static void assumeApiVersionUnset() {
    String apiVersion = System.getenv("GOOGLE_GENAI_API_VERSION");
    assumeTrue(apiVersion == null || apiVersion.isEmpty());
  }

  /** Skips unless the environment leaves ADK-built clients on the Gemini API. */
  private static void assumeGeminiApi() {
    assumeFalse("true".equalsIgnoreCase(System.getenv("GOOGLE_GENAI_USE_VERTEXAI")));
    assumeFalse("true".equalsIgnoreCase(System.getenv("GOOGLE_GENAI_USE_ENTERPRISE")));
  }

  private static void assumeGeminiApiWithoutVersion() {
    assumeGeminiApi();
    assumeApiVersionUnset();
  }

  /**
   * Sends one request through the model {@code model} builds and returns the path a local server
   * received. {@code model} gets the server URL; models that build their own client reach the
   * server through {@link Client#setDefaultBaseUrls}.
   */
  private static String pathRequestedBy(Function<String, ? extends BaseLlm> model)
      throws Exception {
    AtomicReference<String> path = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          byte[] body = GENERATE_CONTENT_JSON.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    String serverUrl =
        "http://"
            + InetAddresses.toUriString(server.getAddress().getAddress())
            + ":"
            + server.getAddress().getPort();
    Client.setDefaultBaseUrls(Optional.of(serverUrl), Optional.of(serverUrl));
    try {
      assertThat(
              model
                  .apply(serverUrl)
                  .generateContent(request(), /* stream= */ false)
                  .toList()
                  .blockingGet())
          .isNotEmpty();
      return path.get();
    } finally {
      Client.setDefaultBaseUrls(Optional.empty(), Optional.empty());
      server.stop(0);
    }
  }

  private static LlmRequest request() {
    return LlmRequest.builder()
        .contents(
            ImmutableList.of(
                Content.builder().role("user").parts(Part.fromText("What is the answer?")).build()))
        .build();
  }

  private static final String GENERATE_CONTENT_JSON =
      "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]},"
          + "\"finishReason\":\"STOP\"}]}";
}
