/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.models.chat;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
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
public final class ChatCompletionsHttpClientTest {
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final MediaType JSON = MediaType.get("application/json");
  private static final MediaType EVENT_STREAM = MediaType.get("text/event-stream");

  /**
   * Bounded wait for {@link TestSubscriber#await} so a buggy callback wiring cannot hang the test
   * JVM. The mock callbacks fire synchronously in the same thread, so this value is intentionally
   * short -- on a successful run the await returns in microseconds, and on a hung run we fail fast
   * instead of stalling the test suite.
   */
  private static final Duration AWAIT_TIMEOUT = Duration.ofMillis(500);

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private OkHttpClient mockHttpClient;
  @Mock private Call mockCall;

  private ChatCompletionsHttpClient client;

  @Before
  public void setUp() {
    when(mockHttpClient.newCall(any())).thenReturn(mockCall);
    client =
        ChatCompletionsHttpClient.forTesting(
            HttpOptions.builder().baseUrl("https://example.com/").build(), mockHttpClient);
  }

  /**
   * Wires the per-test mock {@link OkHttpClient} into a fresh {@link ChatCompletionsHttpClient}
   * built from the supplied options. Used by tests that need a non-default {@link HttpOptions}
   * (e.g. custom headers, baseUrl variants) but still want callbacks captured by the mock.
   */
  private ChatCompletionsHttpClient newClientWithMock(HttpOptions options) {
    when(mockHttpClient.newCall(any())).thenReturn(mockCall);
    return ChatCompletionsHttpClient.forTesting(options, mockHttpClient);
  }

  private Response createMockResponse(String body, MediaType mediaType) {
    return createMockResponse(body, mediaType, 200, "OK");
  }

  private Response createMockResponse(String body, MediaType mediaType, int code, String message) {
    Response.Builder builder =
        new Response.Builder()
            .request(new Request.Builder().url("https://example.com/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message);
    // OkHttp's Response.Builder rejects a null body via its Kotlin @NotNull contract; omit
    // the body() call entirely to model an empty/null response body.
    if (body != null) {
      builder.body(ResponseBody.create(body, mediaType));
    }
    return builder.build();
  }

  /** Returns a minimal {@link LlmRequest} suitable for tests that don't care about the payload. */
  private static LlmRequest minimalRequest() {
    return LlmRequest.builder()
        .model("gpt-4")
        .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
        .build();
  }

  @Test
  public void complete_nonStreaming_sendsCorrectPayload() throws Exception {
    String responseBody =
        """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": "Hi"
              },
              "finish_reason": "stop"
            }
          ]
        }
        """;

    Response mockResponse = createMockResponse(responseBody, JSON);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    LlmResponse response = testSubscriber.values().get(0);

    ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
    verify(mockHttpClient).newCall(requestCaptor.capture());
    Request capturedRequest = requestCaptor.getValue();
    assertThat(capturedRequest.url().encodedPath()).isEqualTo("/chat/completions");

    Buffer buffer = new Buffer();
    capturedRequest.body().writeTo(buffer);
    JsonNode requestBodyJson = objectMapper.readTree(buffer.readUtf8());
    assertThat(requestBodyJson.get("model").asText()).isEqualTo("gpt-4");
    assertThat(requestBodyJson.get("messages").get(0).get("role").asText()).isEqualTo("user");
    assertThat(requestBodyJson.get("messages").get(0).get("content").asText()).isEqualTo("hello");

    LlmResponse expectedResponse =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.fromText("Hi")))
                    .build())
            .finishReason(new FinishReason(FinishReason.Known.STOP.toString()))
            .customMetadata(ImmutableList.of())
            .build();

    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  public void complete_streaming_parsesChunks() throws Exception {
    String responseBody =
        """
        data: {"choices":[{"delta":{"content":"Chunk"},"index":0}]}

        data: [DONE]
        """;

    Response mockResponse = createMockResponse(responseBody, EVENT_STREAM);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), true).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    LlmResponse response = testSubscriber.values().get(0);

    LlmResponse expectedResponse =
        LlmResponse.builder()
            .content(
                Content.builder().role("").parts(ImmutableList.of(Part.fromText("Chunk"))).build())
            .partial(true)
            .modelVersion("")
            .customMetadata(ImmutableList.of())
            .build();

    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  public void complete_nonStreaming_propagateFailure() throws Exception {
    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onFailure(mockCall, new IOException("Network Error"));
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertError(IOException.class);
  }

  @Test
  public void complete_streaming_propagateFailure() throws Exception {
    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), true).test();

    callbackCaptor.getValue().onFailure(mockCall, new IOException("Network Error"));
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertError(IOException.class);
  }

  // -- Streaming-specific tests. ---------------------------------------------------------

  /**
   * Verifies that an HTTP error status (e.g. 500) on a streaming request propagates as a stream
   * error rather than producing zero values silently. Mirror of the non-streaming variant for
   * symmetric coverage, since the streaming and non-streaming Flowables have separate error-path
   * code.
   */
  @Test
  public void complete_streaming_propagatesHttpErrorStatus() throws Exception {
    Response mockResponse =
        createMockResponse("{\"error\":\"server exploded\"}", JSON, 500, "Internal Server Error");

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), true).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertNoValues();
    testSubscriber.assertError(
        e ->
            e instanceof IOException
                && e.getMessage().contains("HTTP request failed with status:")
                && e.getMessage().contains("server exploded"));
  }

  /**
   * Verifies that a single malformed JSON chunk in the middle of a stream does NOT abort the
   * stream. The good chunks before AND after the malformed chunk must still be emitted.
   */
  @Test
  public void complete_streaming_continuesOnMalformedChunk() throws Exception {
    String responseBody =
        """
        data: {"choices":[{"delta":{"content":"Good1"},"index":0}]}

        data: {this is not valid json}

        data: {"choices":[{"delta":{"content":"Good2"},"index":0}]}

        data: [DONE]
        """;

    Response mockResponse = createMockResponse(responseBody, EVENT_STREAM);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), true).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertNoErrors();
    testSubscriber.assertValueCount(2);
    assertThat(testSubscriber.values().get(0).content().get().parts().get().get(0).text().get())
        .isEqualTo("Good1");
    assertThat(testSubscriber.values().get(1).content().get().parts().get().get(0).text().get())
        .isEqualTo("Good2");
  }

  /**
   * Verifies that SSE chunks which omit the trailing space after {@code data:} are still parsed.
   * Some upstream providers send {@code data:{json}} (no space), and the SSE spec allows it; a
   * strict {@code "data: "} match would silently drop these lines.
   */
  @Test
  public void complete_streaming_acceptsDataPrefixWithoutSpace() throws Exception {
    // Note: NO space after "data:" on the data line.
    String responseBody =
        """
        data:{"choices":[{"delta":{"content":"NoSpace"},"index":0}]}

        data:[DONE]
        """;

    Response mockResponse = createMockResponse(responseBody, EVENT_STREAM);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), true).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertNoErrors();
    testSubscriber.assertValueCount(1);
    assertThat(testSubscriber.values().get(0).content().get().parts().get().get(0).text().get())
        .isEqualTo("NoSpace");
  }

  // -- Header, error-propagation, and timeout coverage. ----------------------------------

  /**
   * Verifies that an HTTP error status (e.g. 500) propagates as a stream error and that the error
   * message includes the response body so callers can debug. Covers the {@code
   * !response.isSuccessful()} branch of the non-streaming path; the streaming counterpart is tested
   * above in the streaming-specific section.
   */
  @Test
  public void complete_nonStreaming_propagatesHttpErrorStatus() throws Exception {
    Response mockResponse =
        createMockResponse("{\"error\":\"server exploded\"}", JSON, 500, "Internal Server Error");

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertError(
        e ->
            e instanceof IOException
                && e.getMessage().contains("HTTP request failed with status:")
                && e.getMessage().contains("server exploded"));
  }

  /**
   * Verifies that an empty response body propagates as a stream error rather than silently emitting
   * an empty value. The exact exception class depends on OkHttp's behavior:
   *
   * <ul>
   *   <li>If OkHttp produces a {@code null} body, our code surfaces an {@link IOException} with the
   *       message {@code "Empty response body"}.
   *   <li>If OkHttp produces an empty (non-null) body, Jackson surfaces a {@link
   *       com.fasterxml.jackson.databind.exc.MismatchedInputException} ("No content to map").
   * </ul>
   *
   * Both outcomes satisfy the contract: empty body must NOT silently produce a successful empty
   * {@link LlmResponse}.
   */
  @Test
  public void complete_nonStreaming_propagatesEmptyBody() throws Exception {
    Response mockResponse = createMockResponse(null, JSON);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber = client.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertNoValues();
    testSubscriber.assertError(Throwable.class);
  }

  /**
   * Verifies that caller-supplied headers reach the wire on the captured {@link Request}. This is
   * the most common production failure mode (missing or wrong Authorization header), so it gets its
   * own test rather than being implicit in other tests.
   */
  @Test
  public void complete_sendsCustomHeaders() throws Exception {
    ChatCompletionsHttpClient clientWithHeaders =
        newClientWithMock(
            HttpOptions.builder()
                .baseUrl("https://example.com/")
                .headers(ImmutableMap.of("Authorization", "Bearer test-token", "X-Custom", "value"))
                .build());

    String responseBody =
        """
        {"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
        """;
    Response mockResponse = createMockResponse(responseBody, JSON);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber =
        clientWithHeaders.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
    verify(mockHttpClient).newCall(requestCaptor.capture());
    Request capturedRequest = requestCaptor.getValue();
    assertThat(capturedRequest.header("Authorization")).isEqualTo("Bearer test-token");
    assertThat(capturedRequest.header("X-Custom")).isEqualTo("value");
    // Content-Type is forced to application/json regardless of caller input.
    assertThat(capturedRequest.header("Content-Type")).contains("application/json");
  }

  /**
   * Verifies that even when a caller passes a conflicting {@code Content-Type} header, the client
   * overrides it with {@code application/json} so the upstream API does not reject the request as a
   * malformed payload.
   */
  @Test
  public void complete_overridesCallerContentType() throws Exception {
    ChatCompletionsHttpClient clientWithBadHeader =
        newClientWithMock(
            HttpOptions.builder()
                .baseUrl("https://example.com/")
                .headers(ImmutableMap.of("Content-Type", "text/plain"))
                .build());

    String responseBody =
        """
        {"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
        """;
    Response mockResponse = createMockResponse(responseBody, JSON);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber =
        clientWithBadHeader.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
    verify(mockHttpClient).newCall(requestCaptor.capture());
    Request capturedRequest = requestCaptor.getValue();
    // Should be exactly one Content-Type header, not two.
    assertThat(capturedRequest.headers("Content-Type")).hasSize(1);
    assertThat(capturedRequest.header("Content-Type")).contains("application/json");
  }

  /**
   * Verifies that a {@code baseUrl} without a trailing slash still produces the correct {@code
   * /chat/completions} path. {@link okhttp3.HttpUrl#newBuilder()} normalizes path segments
   * regardless of the trailing-slash state of the base URL.
   */
  @Test
  public void complete_handlesBaseUrlWithoutTrailingSlash() throws Exception {
    ChatCompletionsHttpClient clientNoSlash =
        newClientWithMock(HttpOptions.builder().baseUrl("https://example.com").build());

    String responseBody =
        """
        {"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
        """;
    Response mockResponse = createMockResponse(responseBody, JSON);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber =
        clientNoSlash.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
    verify(mockHttpClient).newCall(requestCaptor.capture());
    assertThat(requestCaptor.getValue().url().encodedPath()).isEqualTo("/chat/completions");
  }

  /**
   * Verifies that omitting {@code headers} on the supplied {@link HttpOptions} is treated as no
   * extra headers, not as an NPE.
   */
  @Test
  public void constructor_missingHeaders_isTreatedAsEmpty() throws Exception {
    ChatCompletionsHttpClient clientWithoutHeaders =
        newClientWithMock(HttpOptions.builder().baseUrl("https://example.com/").build());

    String responseBody =
        """
        {"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
        """;
    Response mockResponse = createMockResponse(responseBody, JSON);

    ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
    doNothing().when(mockCall).enqueue(callbackCaptor.capture());

    TestSubscriber<LlmResponse> testSubscriber =
        clientWithoutHeaders.complete(minimalRequest(), false).test();

    callbackCaptor.getValue().onResponse(mockCall, mockResponse);
    testSubscriber.await(AWAIT_TIMEOUT.toMillis(), MILLISECONDS);

    testSubscriber.assertNoErrors();
    testSubscriber.assertValueCount(1);
  }

  /** Verifies that a {@code null} {@link HttpOptions} is rejected at construction time. */
  @Test
  public void constructor_nullHttpOptions_throws() {
    assertThrows(NullPointerException.class, () -> new ChatCompletionsHttpClient(null));
  }

  /**
   * Verifies that an {@link HttpOptions} without a {@code baseUrl} is rejected at construction time
   * as bad configuration. {@link IllegalArgumentException} (not NPE) is the conventional signal for
   * missing required configuration.
   */
  @Test
  public void constructor_missingBaseUrl_throws() {
    HttpOptions noBaseUrl = HttpOptions.builder().build();
    assertThrows(IllegalArgumentException.class, () -> new ChatCompletionsHttpClient(noBaseUrl));
  }

  /**
   * Verifies that an {@link HttpOptions} with a malformed (non-HTTP(S)) {@code baseUrl} is rejected
   * at construction time, rather than failing later at the first {@code complete()} call with a
   * confusing NPE from {@link okhttp3.HttpUrl#parse}.
   */
  @Test
  public void constructor_malformedBaseUrl_throws() {
    HttpOptions malformed = HttpOptions.builder().baseUrl("not a url").build();
    assertThrows(IllegalArgumentException.class, () -> new ChatCompletionsHttpClient(malformed));
  }

  // -- Tri-state timeout policy. ----------------------------------------------------------

  /**
   * Verifies that when {@code httpOptions} omits {@code timeout()}, the client applies the 5-minute
   * default call timeout to prevent indefinite hangs in callers that did not explicitly configure a
   * timeout.
   */
  @Test
  public void constructor_missingTimeout_appliesDefaultFiveMinuteTimeout() {
    ChatCompletionsHttpClient defaultClient =
        new ChatCompletionsHttpClient(
            HttpOptions.builder().baseUrl("https://example.com/").build());

    OkHttpClient internal = readInternalClient(defaultClient);
    assertThat(internal.callTimeoutMillis())
        .isEqualTo((int) Duration.ofMinutes(5).toMillis()); // 300_000
  }

  /**
   * Verifies that when the caller explicitly sets {@code httpOptions.timeout() == 0}, the client
   * respects this as the explicit opt-in to infinite hang. This is the migration path for
   * long-running streams or batch jobs that need no timeout.
   */
  @Test
  public void constructor_zeroTimeout_respectsInfiniteHang() {
    HttpOptions zeroTimeout =
        HttpOptions.builder().baseUrl("https://example.com/").timeout(0).build();
    ChatCompletionsHttpClient infiniteClient = new ChatCompletionsHttpClient(zeroTimeout);

    OkHttpClient internal = readInternalClient(infiniteClient);
    assertThat(internal.callTimeoutMillis()).isEqualTo(0); // OkHttp: 0 = no timeout
  }

  /**
   * Verifies that when the caller sets a positive timeout, that value (in milliseconds) is used as
   * the call timeout.
   */
  @Test
  public void constructor_explicitTimeout_appliesIt() {
    HttpOptions tenSeconds =
        HttpOptions.builder().baseUrl("https://example.com/").timeout(10_000).build();
    ChatCompletionsHttpClient timedClient = new ChatCompletionsHttpClient(tenSeconds);

    OkHttpClient internal = readInternalClient(timedClient);
    assertThat(internal.callTimeoutMillis()).isEqualTo(10_000);
  }

  /** Reflectively reads the internal {@link OkHttpClient} to inspect the resolved timeout. */
  private static OkHttpClient readInternalClient(ChatCompletionsHttpClient target) {
    try {
      Field clientField = ChatCompletionsHttpClient.class.getDeclaredField("client");
      clientField.setAccessible(true);
      return (OkHttpClient) clientField.get(target);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Failed to read internal client", e);
    }
  }
}
