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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTTP client for interacting with OpenAI-compatible chat completions endpoints.
 *
 * <p>Supports both non-streaming responses (single {@link LlmResponse} emission) and streaming
 * Server-Sent Events (SSE) responses (multiple incremental {@link LlmResponse} emissions). See the
 * <a href="https://developers.openai.com/api/reference/resources/chat">OpenAI Chat Completions API
 * reference</a> for the wire protocol.
 */
public class ChatCompletionsHttpClient {
  private static final Logger logger = LoggerFactory.getLogger(ChatCompletionsHttpClient.class);
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final String SSE_DATA_PREFIX = "data:";

  /**
   * Default OkHttp call timeout used when the caller does not supply an {@link HttpOptions}
   * timeout. Five minutes is long enough for most non-streaming completions and short enough to
   * prevent indefinite hangs in the common case where the caller does not configure timeouts.
   * Callers who need infinite (e.g. long batch jobs or open streams) can opt in by passing an
   * {@link HttpOptions} with {@code timeout() == 0}.
   */
  private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofMinutes(5);

  /**
   * Shared OkHttpClient instance whose connection pool and thread dispatcher are reused across all
   * {@link ChatCompletionsHttpClient} instances. Each instance forks this client via {@link
   * OkHttpClient#newBuilder()} to apply per-instance timeouts without leaking pools.
   */
  private static final OkHttpClient SHARED_POOL_CLIENT = new OkHttpClient();

  private final OkHttpClient client;
  private final HttpUrl completionsUrl;
  private final ImmutableMap<String, String> headers;

  /**
   * Constructs a new {@link ChatCompletionsHttpClient} that facilitates API interaction with the
   * standard {@code /chat/completions} REST endpoint.
   *
   * <p>All configuration is sourced from the supplied {@link HttpOptions}:
   *
   * <ul>
   *   <li>{@link HttpOptions#baseUrl()} -- <b>required</b>. The base URL of the chat completions
   *       endpoint. The {@code chat/completions} path segments are appended automatically using
   *       {@link HttpUrl}, which handles trailing slashes and percent-encoding deterministically.
   *       Set via {@code HttpOptions.builder().baseUrl("https://...").build()}.
   *   <li>{@link HttpOptions#headers()} -- optional. Extra HTTP headers to include in outgoing
   *       requests. The {@code Content-Type} header is set automatically and cannot be overridden.
   *       Set via {@code HttpOptions.builder().headers(Map.of("Authorization", "Bearer ...")) }.
   *   <li>{@link HttpOptions#timeout()} -- optional. Per-call timeout in milliseconds. A missing
   *       timeout defaults to 5 minutes ({@link #DEFAULT_CALL_TIMEOUT}). A timeout of {@code 0} is
   *       respected as the explicit caller opt-in to infinite wait. Set via {@code
   *       HttpOptions.builder().timeout(10_000).build()}.
   * </ul>
   *
   * <p>Example:
   *
   * <pre>{@code
   * HttpOptions options =
   *     HttpOptions.builder()
   *         .baseUrl("https://example.com/v1/")
   *         .headers(ImmutableMap.of("Authorization", "Bearer my-token"))
   *         .timeout(30_000)
   *         .build();
   * ChatCompletionsHttpClient client = new ChatCompletionsHttpClient(options);
   * }</pre>
   *
   * @param httpOptions HTTP configuration. Must not be {@code null}, and {@link
   *     HttpOptions#baseUrl()} must be present and parseable as an HTTP(S) URL.
   * @throws IllegalArgumentException if {@code httpOptions.baseUrl()} is missing or is not a valid
   *     HTTP(S) URL.
   */
  public ChatCompletionsHttpClient(HttpOptions httpOptions) {
    this(httpOptions, buildClient(httpOptions));
  }

  private ChatCompletionsHttpClient(HttpOptions httpOptions, OkHttpClient client) {
    Objects.requireNonNull(httpOptions, "httpOptions cannot be null");
    String baseUrl =
        httpOptions
            .baseUrl()
            .orElseThrow(() -> new IllegalArgumentException("httpOptions.baseUrl() must be set"));
    HttpUrl parsedBaseUrl = HttpUrl.parse(baseUrl);
    if (parsedBaseUrl == null) {
      throw new IllegalArgumentException(
          "httpOptions.baseUrl() is not a valid HTTP(S) URL: " + baseUrl);
    }
    // Pre-build the completions URL once. HttpUrl.addPathSegment handles trailing slashes,
    // percent-encoding, and existing path components on baseUrl deterministically.
    this.completionsUrl =
        parsedBaseUrl.newBuilder().addPathSegment("chat").addPathSegment("completions").build();
    // Defensive copy of caller-supplied headers; absent is treated as no extra headers.
    this.headers =
        httpOptions
            .headers()
            .<ImmutableMap<String, String>>map(ImmutableMap::copyOf)
            .orElse(ImmutableMap.of());
    this.client = client;
  }

  /**
   * Test-only factory that injects a custom {@link OkHttpClient} (typically a mock) without
   * touching production wiring. Production callers should use the public constructor.
   */
  @VisibleForTesting
  static ChatCompletionsHttpClient forTesting(HttpOptions httpOptions, OkHttpClient client) {
    return new ChatCompletionsHttpClient(httpOptions, client);
  }

  /**
   * Builds the production OkHttpClient by forking {@link #SHARED_POOL_CLIENT} so the connection
   * pool and dispatcher are reused across instances while applying per-instance timeouts.
   */
  private static OkHttpClient buildClient(HttpOptions httpOptions) {
    Objects.requireNonNull(httpOptions, "httpOptions cannot be null");
    OkHttpClient.Builder builder = SHARED_POOL_CLIENT.newBuilder();
    builder.connectTimeout(Duration.ZERO);
    builder.readTimeout(Duration.ZERO);
    builder.writeTimeout(Duration.ZERO);
    builder.callTimeout(resolveCallTimeout(httpOptions));
    return builder.build();
  }

  /** Resolves the call timeout from HttpOptions. */
  private static Duration resolveCallTimeout(HttpOptions httpOptions) {
    if (httpOptions.timeout().isEmpty()) {
      return DEFAULT_CALL_TIMEOUT;
    }
    long timeoutMs = httpOptions.timeout().get();
    // 0 is treated as no timeout (Duration.ZERO).
    return timeoutMs == 0L ? Duration.ZERO : Duration.ofMillis(timeoutMs);
  }

  /**
   * Generates a conversational response from the chat completions endpoint based on the provided
   * messages. This encapsulates building the HTTP payload, sending the request to the completions
   * endpoint, and initiating the handling of complete calls.
   *
   * @param llmRequest The request containing the model, configuration, and sequence of messages.
   * @param stream Whether to request a streaming response.
   * @return A {@link Flowable} emitting the discrete (or combined) {@link LlmResponse} objects.
   */
  public Flowable<LlmResponse> complete(LlmRequest llmRequest, boolean stream) {
    return Flowable.defer(
        () -> {
          String effectiveModelName = llmRequest.model().orElse("?");
          logger.trace("Chat Completion Request Contents: {}", llmRequest.contents());
          llmRequest.config().ifPresent(c -> logger.trace("Chat Completion Request Config: {}", c));

          ChatCompletionsRequest dtoRequest =
              ChatCompletionsRequest.fromLlmRequest(llmRequest, stream);
          String jsonPayload = objectMapper.writeValueAsString(dtoRequest);
          logger.trace("Chat Completion Request JSON: {}", jsonPayload);

          if (stream) {
            logger.debug(
                "Sending streaming chat-completion request to model {}", effectiveModelName);
          } else {
            logger.debug("Sending chat-completion request to model {}", effectiveModelName);
          }

          Request.Builder requestBuilder =
              new Request.Builder().url(completionsUrl).post(RequestBody.create(jsonPayload, JSON));

          for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
          }
          // Defensively force Content-Type to JSON by replacing instead of appending.
          requestBuilder.header("Content-Type", JSON.toString());

          Request request = requestBuilder.build();
          return stream ? createStreamingFlowable(request) : createNonStreamingFlowable(request);
        });
  }

  private Flowable<LlmResponse> createStreamingFlowable(Request request) {
    return Flowable.create(
        emitter -> {
          Call call = client.newCall(request);
          emitter.setCancellable(call::cancel);
          call.enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                  emitter.tryOnError(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                  try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                      String bodyStr = body != null ? body.string() : "";
                      emitter.tryOnError(
                          new IOException(
                              "HTTP request failed with status: "
                                  + response
                                  + " - body: "
                                  + bodyStr));
                      return;
                    }
                    if (body == null) {
                      emitter.tryOnError(new IOException("Empty response body"));
                      return;
                    }

                    BufferedSource source = body.source();
                    ChatCompletionsResponse.ChatCompletionChunkCollection collection =
                        new ChatCompletionsResponse.ChatCompletionChunkCollection();
                    while (!source.exhausted() && !emitter.isCancelled()) {
                      String line = source.readUtf8Line();
                      if (line == null) {
                        break;
                      }
                      if (line.isEmpty()) {
                        continue;
                      }
                      // TODO: Support SSE "event", "id", and "retry".
                      // See
                      // https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
                      if (!line.startsWith(SSE_DATA_PREFIX)) {
                        logger.debug("Ignoring SSE line without data prefix: {}", line);
                        continue;
                      }
                      // The SSE spec allows whitespace after the prefix,
                      //   eg: "data:foo" vs "data: foo".
                      String data = line.substring(SSE_DATA_PREFIX.length()).stripLeading();
                      if (data.equals("[DONE]")) {
                        break;
                      }
                      // A single malformed chunk must not abort the entire stream. Log a
                      // warning and continue.
                      try {
                        logger.trace("Raw streaming chat-completion chunk: {}", data);
                        ChatCompletionsResponse.ChatCompletionChunk chunk =
                            objectMapper.readValue(
                                data, ChatCompletionsResponse.ChatCompletionChunk.class);
                        ImmutableList<LlmResponse> responses = collection.processChunk(chunk);
                        if (!responses.isEmpty()) {
                          logger.trace("Responses to emit: {}", responses);
                        }
                        for (LlmResponse resp : responses) {
                          emitter.onNext(resp);
                        }
                      } catch (JsonProcessingException e) {
                        logger.warn("Failed to parse JSON chunk: {}", data, e);
                      }
                    }
                    emitter.onComplete();
                  } catch (Exception e) {
                    emitter.tryOnError(e);
                  }
                }
              });
        },
        BackpressureStrategy.BUFFER);
  }

  /**
   * Wraps an OkHttp {@link Callback} in a reactive {@link Flowable} for single-turn, non-streaming
   * responses.
   */
  private Flowable<LlmResponse> createNonStreamingFlowable(Request request) {
    return Flowable.create(
        emitter -> {
          Call call = client.newCall(request);
          emitter.setCancellable(call::cancel);
          call.enqueue(new NonStreamingCallback(emitter));
        },
        BackpressureStrategy.BUFFER);
  }

  /**
   * Handles OkHttp failure and success callbacks, pushing {@link LlmResponse} results to the given
   * emitter.
   */
  private static final class NonStreamingCallback implements Callback {
    private final FlowableEmitter<LlmResponse> emitter;

    NonStreamingCallback(FlowableEmitter<LlmResponse> emitter) {
      this.emitter = emitter;
    }

    @Override
    public void onFailure(Call call, IOException e) {
      emitter.tryOnError(e);
    }

    @Override
    public void onResponse(Call call, Response response) {
      try (ResponseBody body = response.body()) {
        if (!response.isSuccessful()) {
          String bodyStr = body != null ? body.string() : "";
          emitter.tryOnError(
              new IOException(
                  "HTTP request failed with status: " + response + " - body: " + bodyStr));
          return;
        }
        if (body == null) {
          emitter.tryOnError(new IOException("Empty response body"));
          return;
        }

        String jsonResponse = body.string();
        logger.trace("Raw non-streaming chat-completion response: {}", jsonResponse);
        ChatCompletionsResponse.ChatCompletion completion =
            objectMapper.readValue(jsonResponse, ChatCompletionsResponse.ChatCompletion.class);
        LlmResponse llmResponse = completion.toLlmResponse();
        logger.trace("Response to emit: {}", llmResponse);
        emitter.onNext(llmResponse);
        emitter.onComplete();
      } catch (Exception e) {
        emitter.tryOnError(e);
      }
    }
  }
}
