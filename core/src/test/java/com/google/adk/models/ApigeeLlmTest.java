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
package com.google.adk.models;

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.models.ApigeeLlm.ApiType;
import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.core.Flowable;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ApigeeLlmTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private Gemini mockGeminiDelegate;
  @Mock private ChatCompletionsClient mockCcClient;

  private static final String PROXY_URL = "https://test.apigee.net";

  @Before
  public void checkApiKey() {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));
  }

  @Test
  public void build_withValidModelStrings_succeeds() {
    String[] validModelStrings = {
      "apigee/whatever-model",
      "apigee/v1/whatever-model",
      "apigee/vertex_ai/whatever-model",
      "apigee/gemini/v1/whatever-model",
      "apigee/vertex_ai/v1beta/whatever-model",
      "apigee/openai/gpt-4"
    };

    for (String modelName : validModelStrings) {
      ApigeeLlm llm = ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL).build();
      assertThat(llm).isNotNull();
    }
  }

  @Test
  public void build_withInvalidModelStrings_throwsException() {
    String[] invalidModelStrings = {
      "apigee/openai/v1/gpt",
      "apigee/",
      "apigee",
      "gemini-pro",
      "apigee/vertex_ai/v1/model/extra",
      "apigee/unknown/model",
      "apigee/gemini//"
    };

    for (String modelName : invalidModelStrings) {
      ApigeeLlm.Builder builder = ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL);
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> builder.build());
      assertThat(e).hasMessageThat().contains("Invalid model string: " + modelName);
    }
  }

  @Test
  public void generateContent_stripsApigeePrefixAndSendsToDelegate() {
    when(mockGeminiDelegate.generateContent(any(), anyBoolean())).thenReturn(Flowable.empty());

    ApigeeLlm llm = new ApigeeLlm("apigee/gemini/v1/whatever-model", mockGeminiDelegate);

    LlmRequest request =
        LlmRequest.builder()
            .model("apigee/gemini/v1/whatever-model")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hi")).build()))
            .build();
    llm.generateContent(request, true).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockGeminiDelegate).generateContent(requestCaptor.capture(), eq(true));
    assertThat(requestCaptor.getValue().model()).hasValue("whatever-model");
  }

  @Test
  public void generateContent_withChatCompletionsApiType_sendsToCcClient() {
    when(mockCcClient.complete(any(), anyBoolean())).thenReturn(Flowable.empty());

    ApigeeLlm llm = new ApigeeLlm("apigee/openai/gpt-4", mockGeminiDelegate, mockCcClient);

    LlmRequest request =
        LlmRequest.builder()
            .model("apigee/openai/gpt-4")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();
    llm.generateContent(request, false).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockCcClient).complete(requestCaptor.capture(), eq(false));
    verify(mockGeminiDelegate, never()).generateContent(any(), anyBoolean());
    assertThat(requestCaptor.getValue().model()).hasValue("gpt-4");
  }

  @Test
  public void connect_withChatCompletionsApiType_throwsUnsupportedOperationException() {
    ApigeeLlm llm = new ApigeeLlm("apigee/openai/gpt-4", mockGeminiDelegate, mockCcClient);
    LlmRequest request = LlmRequest.builder().model("apigee/openai/gpt-4").build();
    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> llm.connect(request));
    assertThat(e)
        .hasMessageThat()
        .contains("Streaming connections are not supported for chat completions.");
  }

  @Test
  public void build_withExplicitChatCompletionsApiType_success() {
    ApigeeLlm llm =
        ApigeeLlm.builder()
            .modelName("apigee/whatever-model")
            .proxyUrl(PROXY_URL)
            .apiType(ApiType.CHAT_COMPLETIONS)
            .build();
    assertThat(llm).isNotNull();
  }

  // Add a test to verify the vertexAI flag is set correctly.
  @Test
  public void generateContent_setsVertexAiFlagCorrectly_withVertexAi() {
    ApigeeLlm llm =
        ApigeeLlm.builder()
            .modelName("apigee/vertex_ai/whatever-model")
            .proxyUrl(PROXY_URL)
            .build();
    assertThat(llm.getApiClient().vertexAI()).isTrue();
  }

  @Test
  public void generateContent_setsVertexAiFlagCorrectly_withOrWithoutVertexAi() {

    ApigeeLlm llm =
        ApigeeLlm.builder().modelName("apigee/whatever-model").proxyUrl(PROXY_URL).build();
    String useVertexAi = System.getenv("GOOGLE_GENAI_USE_VERTEXAI");
    // Unless the variable is true or 1, ApigeeLlm leaves the backend to the genai client.
    boolean expectVertexAi =
        Boolean.parseBoolean(useVertexAi)
            || Objects.equals(useVertexAi, "1")
            || Client.builder().build().vertexAI();

    assertThat(llm.getApiClient().vertexAI()).isEqualTo(expectVertexAi);
  }

  @Test
  public void generateContent_setsVertexAiFlagCorrectly_withGemini() {
    ApigeeLlm llm =
        ApigeeLlm.builder().modelName("apigee/gemini/whatever-model").proxyUrl(PROXY_URL).build();
    assertThat(llm.getApiClient().vertexAI()).isFalse();
  }

  // Add a test to verify the api version is set correctly.
  @Test
  public void generateContent_setsApiVersionCorrectly() {
    ImmutableMap<String, String> modelToApiVersion =
        ImmutableMap.of(
            "apigee/whatever-model", "",
            "apigee/v1/whatever-model", "v1",
            "apigee/vertex_ai/whatever-model", "",
            "apigee/gemini/v1/whatever-model", "v1",
            "apigee/vertex_ai/v1beta/whatever-model", "v1beta");

    for (Map.Entry<String, String> entry : modelToApiVersion.entrySet()) {
      String modelName = entry.getKey();
      String expectedApiVersion = entry.getValue();
      ApigeeLlm llm = ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL).build();
      if (expectedApiVersion.isEmpty()) {
        assertThat(llm.getHttpOptions().apiVersion()).isEmpty();
      } else {
        assertThat(llm.getHttpOptions().apiVersion()).hasValue(expectedApiVersion);
      }
    }
  }

  @Test
  public void build_withCustomHeaders_setsHeadersInHttpOptions() {
    ImmutableMap<String, String> customHeaders = ImmutableMap.of("X-Test-Header", "TestValue");
    ApigeeLlm llm =
        ApigeeLlm.builder()
            .modelName("apigee/whatever-model")
            .proxyUrl(PROXY_URL)
            .customHeaders(customHeaders)
            .build();
    assertThat(llm.getHttpOptions().headers().get()).containsKey("X-Test-Header");
    assertThat(llm.getHttpOptions().headers().get()).containsEntry("X-Test-Header", "TestValue");
    // Also check for tracking headers
    assertThat(llm.getHttpOptions().headers().get()).containsKey("x-goog-api-client");
    assertThat(llm.getHttpOptions().headers().get()).containsKey("user-agent");
  }

  @Test
  public void build_withTrailingSlashInModel_parsesVersionAndModelId() {
    when(mockGeminiDelegate.generateContent(any(), anyBoolean())).thenReturn(Flowable.empty());
    ApigeeLlm llm = new ApigeeLlm("apigee/gemini/v1/", mockGeminiDelegate);
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hi")).build()))
            .build();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> llm.generateContent(request, false));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Invalid model string, expected apigee/[<provider>/][<version>/]<model_id>: "
                + "apigee/gemini/v1/");
    verify(mockGeminiDelegate, never()).generateContent(any(), anyBoolean());
  }

  @Test
  public void build_withoutProxyUrlAndEnvVarSet_readsFromEnvironment() {
    assumeNotNull(System.getenv("APIGEE_PROXY_URL"));
    String envProxyUrl = System.getenv("APIGEE_PROXY_URL");
    ApigeeLlm llm = ApigeeLlm.builder().modelName("apigee/whatever-model").build();
    assertThat(llm.getHttpOptions().baseUrl()).hasValue(envProxyUrl);
  }

  @Test
  public void build_withoutProxyUrlAndEnvVarNotSet_throwsException() {
    assumeTrue(System.getenv("APIGEE_PROXY_URL") == null);
    ApigeeLlm.Builder builder = ApigeeLlm.builder().modelName("apigee/whatever-model");
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> builder.build());
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Apigee proxy URL is not set and not found in the environment variable"
                + " APIGEE_PROXY_URL.");
  }

  @Test
  public void build_withProxyUrl_usesProvidedUrl() {
    ApigeeLlm llm =
        ApigeeLlm.builder().proxyUrl(PROXY_URL).modelName("apigee/whatever-model").build();
    assertThat(llm.getHttpOptions().baseUrl()).hasValue(PROXY_URL);
  }

  @Test
  public void generateContent_withChatCompletionsApiType_sendsToCcClient_streaming() {
    when(mockCcClient.complete(any(), anyBoolean())).thenReturn(Flowable.empty());

    ApigeeLlm llm = new ApigeeLlm("apigee/openai/gpt-4o", mockGeminiDelegate, mockCcClient);
    LlmRequest request =
        LlmRequest.builder()
            .model("apigee/openai/gpt-4o")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();
    llm.generateContent(request, true).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockCcClient).complete(requestCaptor.capture(), eq(true));
    verify(mockGeminiDelegate, never()).generateContent(any(), anyBoolean());
    assertThat(requestCaptor.getValue().model()).hasValue("gpt-4o");
  }

  @Test
  public void generateContent_requestLevelModelOverride_extractedCorrectly() {
    when(mockCcClient.complete(any(), anyBoolean())).thenReturn(Flowable.empty());

    ApigeeLlm llm = new ApigeeLlm("apigee/openai/gpt-4o", mockGeminiDelegate, mockCcClient);
    LlmRequest request =
        LlmRequest.builder()
            .model("apigee/openai/gpt-3.5-turbo")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();

    llm.generateContent(request, false).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockCcClient).complete(requestCaptor.capture(), eq(false));
    assertThat(requestCaptor.getValue().model()).hasValue("gpt-3.5-turbo");
  }

  @Test
  public void validateModelString_rejectsOpenAiWithVersion() {
    // 3-component model string (e.g. apigee/openai/v1/gpt-4o) fails because "openai" != "vertex_ai"
    // and != "gemini"
    ApigeeLlm.Builder builder =
        ApigeeLlm.builder().modelName("apigee/openai/v1/gpt-4o").proxyUrl(PROXY_URL);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(e).hasMessageThat().contains("Invalid model string: apigee/openai/v1/gpt-4o");
  }

  @Test
  public void build_chatCompletions_addsTheTrackingTagsCustomHeadersLack() {
    ImmutableMap<String, String> overlappingHeaders =
        ImmutableMap.of(
            "User-Agent", "custom-agent", "x-goog-api-client", "lib/1 google-adk/0.0.1");
    ApigeeLlm llm =
        ApigeeLlm.builder()
            .modelName("apigee/openai/gpt-4o")
            .proxyUrl(PROXY_URL)
            .customHeaders(overlappingHeaders)
            .build();

    assertThat(llm.getHttpOptions().headers().get())
        .containsExactly(
            "user-agent",
            "custom-agent " + TrackingHeaders.VALUE,
            "x-goog-api-client",
            "lib/1 google-adk/0.0.1 gl-java/" + JAVA_VERSION.value());
  }

  @Test
  public void build_withNullApiType_throwsNullPointerException() {
    ApigeeLlm.Builder builder =
        ApigeeLlm.builder().modelName("apigee/whatever-model").proxyUrl(PROXY_URL);
    assertThrows(NullPointerException.class, () -> builder.apiType(null));
  }

  @Test
  public void generateContent_crossApiTypeRequestOverride_routesBasedOnOriginalApiType() {
    when(mockGeminiDelegate.generateContent(any(), anyBoolean())).thenReturn(Flowable.empty());

    // Original ApiType is GENAI implicitly
    ApigeeLlm llm = new ApigeeLlm("apigee/gemini/gemini-pro", mockGeminiDelegate);

    // Override specifies openai models
    LlmRequest request =
        LlmRequest.builder()
            .model("apigee/openai/gpt-4o")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();

    llm.generateContent(request, false).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockGeminiDelegate).generateContent(requestCaptor.capture(), eq(false));
    // It still goes to gemini delegate, but model is stripped
    assertThat(requestCaptor.getValue().model()).hasValue("gpt-4o");
  }

  @Test
  public void generateContent_invalidRequestLevelOverride_throwsException() {
    ApigeeLlm llm = new ApigeeLlm("apigee/openai/gpt-4o", mockGeminiDelegate, mockCcClient);

    LlmRequest request =
        LlmRequest.builder()
            .model("invalid-no-apigee-prefix")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> llm.generateContent(request, false));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Invalid model string, expected apigee/[<provider>/][<version>/]<model_id>: "
                + "invalid-no-apigee-prefix");
  }

  @Test
  public void build_nullModelName_throwsNullPointerException() {
    ApigeeLlm.Builder builder = ApigeeLlm.builder().modelName(null).proxyUrl(PROXY_URL);
    assertThrows(NullPointerException.class, builder::build);
  }

  @Test
  public void build_malformedModelsWithTrailingSlashes_throwsException() {
    String[] malformedModels = {"apigee/openai/", "apigee/openai/gpt-4o/"};
    for (String modelName : malformedModels) {
      ApigeeLlm.Builder builder = ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL);
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
      assertThat(e).hasMessageThat().contains("Invalid model string: " + modelName);
    }
  }

  @Test
  public void generateContent_genAi_sendsTrackingHeadersOnce() throws Exception {
    Headers headers =
        headersSentToProxy(
            "apigee/gemini/v1beta/gemini-test",
            /* customHeaders= */ ImmutableMap.of(),
            GENERATE_CONTENT_JSON);

    assertGenAiTrackingHeaders(headers);
  }

  @Test
  public void generateContent_chatCompletions_sendsTrackingHeaders() throws Exception {
    Headers headers =
        headersSentToProxy(
            "apigee/openai/gpt-4o", /* customHeaders= */ ImmutableMap.of(), CHAT_COMPLETION_JSON);

    for (String name : TrackingHeaders.HEADERS.keySet()) {
      assertThat(headers.get(name)).containsExactly(TrackingHeaders.VALUE);
    }
  }

  @Test
  public void generateContent_genAi_extendsACustomUserAgentWithTheAdkTag() throws Exception {
    Headers headers =
        headersSentToProxy(
            "apigee/gemini/v1beta/gemini-test",
            ImmutableMap.of("User-Agent", "custom-agent"),
            GENERATE_CONTENT_JSON);

    assertThat(getOnlyElement(headers.get("user-agent")))
        .endsWith(" custom-agent " + TrackingHeaders.ADK_TAG);
    assertGenAiTrackingHeaders(headers);
  }

  @Test
  public void generateContent_chatCompletions_extendsACustomUserAgent() throws Exception {
    Headers headers =
        headersSentToProxy(
            "apigee/openai/gpt-4o",
            ImmutableMap.of("User-Agent", "custom-agent"),
            CHAT_COMPLETION_JSON);

    assertThat(headers.get("user-agent")).containsExactly("custom-agent " + TrackingHeaders.VALUE);
    assertThat(headers.get("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE);
  }

  /**
   * Sends one request through an {@link ApigeeLlm} for {@code modelName} to a local proxy that
   * answers with {@code responseJson}, and returns the request headers the proxy received.
   */
  private static Headers headersSentToProxy(
      String modelName, Map<String, String> customHeaders, String responseJson) throws Exception {
    AtomicReference<Headers> requestHeaders = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestHeaders.set(exchange.getRequestHeaders());
          byte[] body = responseJson.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      ApigeeLlm.Builder builder =
          ApigeeLlm.builder()
              .modelName(modelName)
              .proxyUrl(
                  "http://"
                      + InetAddresses.toUriString(server.getAddress().getAddress())
                      + ":"
                      + server.getAddress().getPort());
      builder.customHeaders(customHeaders);
      LlmRequest request =
          LlmRequest.builder()
              .contents(
                  ImmutableList.of(
                      Content.builder().role("user").parts(Part.fromText("hello")).build()))
              .build();

      builder.build().generateContent(request, /* stream= */ false).blockingSubscribe();

      return requestHeaders.get();
    } finally {
      server.stop(0);
    }
  }

  /**
   * Asserts both tracking headers as sent by the genai SDK: each starts with the SDK label,
   * includes the Java version tag and ADK's tag, and repeats no tag name.
   */
  private static void assertGenAiTrackingHeaders(Headers headers) {
    for (String name : TrackingHeaders.HEADERS.keySet()) {
      ImmutableList<String> tags =
          ImmutableList.copyOf(Splitter.on(' ').split(getOnlyElement(headers.get(name))));
      assertThat(tags.get(0)).startsWith("google-genai-sdk/");
      assertThat(tags).containsAtLeast("gl-java/" + JAVA_VERSION.value(), TrackingHeaders.ADK_TAG);
      assertThat(tags.stream().map(tag -> tag.split("/", 2)[0]).collect(toImmutableList()))
          .containsNoDuplicates();
    }
  }

  private static final String GENERATE_CONTENT_JSON =
      "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]},"
          + "\"finishReason\":\"STOP\"}]}";

  private static final String CHAT_COMPLETION_JSON =
      "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
          + "\"finish_reason\":\"stop\"}]}";
}
