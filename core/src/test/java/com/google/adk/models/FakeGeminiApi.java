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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.jspecify.annotations.Nullable;

/**
 * A fake Gemini API that a real genai {@link Client} reaches over HTTP.
 *
 * <p>Tests can queue the answers and cache names to return; once a queue is empty, each model call
 * gets the next numbered answer ({@code "Answer 1"}, {@code "Answer 2"}, ...) and each cache create
 * returns a new numbered cache ({@code "cachedContents/cache-1"}, ...) that expires in an hour,
 * unless told to fail or to omit the expiry.
 */
final class FakeGeminiApi implements Interceptor {

  /** The kind of an API call the fake has served. */
  enum Kind {
    GENERATE,
    CREATE_CACHE,
    DELETE_CACHE
  }

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final List<Call> calls = new ArrayList<>();
  private final List<Instant> expireTimes = new ArrayList<>();
  private final Deque<Integer> createFailures = new ArrayDeque<>();
  private final Deque<Integer> deleteFailures = new ArrayDeque<>();
  private final Deque<String> queuedAnswers = new ArrayDeque<>();
  private final Deque<String> queuedCacheNames = new ArrayDeque<>();
  private @Nullable String nextCreateBody = null;
  private boolean omitExpireTime = false;
  private Duration cacheCallDelay = Duration.ZERO;
  private int numberedAnswers = 0;
  private int numberedCaches = 0;

  Client client() {
    OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(this).build();
    return Client.builder()
        .apiKey("test-api-key")
        .vertexAI(false)
        .clientOptions(ClientOptions.builder().customHttpClient(httpClient).build())
        .build();
  }

  /**
   * Queues texts for the next model calls to answer with, in order, before numbered answers resume.
   *
   * <p>A streamed answer comes as two chunks, split after its last space (or in the middle if it
   * has no space).
   */
  synchronized void queueAnswers(String... texts) {
    Collections.addAll(queuedAnswers, texts);
  }

  /**
   * Queues names for the next successfully created caches, in order, before numbered names resume.
   */
  synchronized void queueCacheNames(String... names) {
    Collections.addAll(queuedCacheNames, names);
  }

  synchronized void failNextCreate(int code) {
    createFailures.add(code);
  }

  synchronized void failNextDelete(int code) {
    deleteFailures.add(code);
  }

  synchronized void answerNextCreateWith(String body) {
    nextCreateBody = body;
  }

  synchronized void omitExpireTime() {
    omitExpireTime = true;
  }

  synchronized void delayCacheCalls(Duration delay) {
    cacheCallDelay = delay;
  }

  /** Returns the expiry reported for the {@code n}-th created cache, counting from 1. */
  synchronized Instant expireTime(int n) {
    return expireTimes.get(n - 1);
  }

  synchronized ImmutableList<Kind> kinds() {
    return calls.stream().map(Call::kind).collect(toImmutableList());
  }

  synchronized ImmutableList<String> paths(Kind kind) {
    return calls.stream()
        .filter(call -> call.kind() == kind)
        .map(Call::path)
        .collect(toImmutableList());
  }

  synchronized ImmutableList<JsonNode> bodies(Kind kind) {
    return calls.stream()
        .filter(call -> call.kind() == kind)
        .map(Call::body)
        .collect(toImmutableList());
  }

  synchronized ImmutableList<Headers> headers(Kind kind) {
    return calls.stream()
        .filter(call -> call.kind() == kind)
        .map(Call::headers)
        .collect(toImmutableList());
  }

  @Override
  public synchronized Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    String path = request.url().encodedPath();
    JsonNode body = MissingNode.getInstance();
    if (request.body() != null) {
      Buffer buffer = new Buffer();
      request.body().writeTo(buffer);
      body = objectMapper.readTree(buffer.readUtf8());
    }
    Call call;
    if (path.endsWith(":generateContent")) {
      call = callGenerateContent(path, body, request.headers());
    } else if (path.endsWith(":streamGenerateContent")) {
      call = callStreamGenerateContent(path, body, request.headers());
    } else if (request.method().equals("POST") && path.endsWith("/cachedContents")) {
      call = callCreateCache(path, body, request.headers());
    } else if (request.method().equals("DELETE") && path.contains("/cachedContents/")) {
      call = callDeleteCache(path, body, request.headers());
    } else {
      throw new IOException("Unexpected request: " + request.method() + " " + path);
    }
    calls.add(call);
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(call.code())
        .message("fake")
        .body(ResponseBody.create(call.responseBody(), MediaType.get("application/json")))
        .build();
  }

  private Call callGenerateContent(String path, JsonNode body, Headers headers) {
    return new Call(Kind.GENERATE, path, body, headers, 200, answer(nextAnswer()).toJson());
  }

  private Call callStreamGenerateContent(String path, JsonNode body, Headers headers) {
    String text = nextAnswer();
    int split = text.lastIndexOf(' ') + 1;
    if (split == 0) {
      split = text.length() / 2;
    }
    String responseBody =
        "data: "
            + partialAnswer(text.substring(0, split)).toJson()
            + "\n\ndata: "
            + answer(text.substring(split)).toJson()
            + "\n\n";
    return new Call(Kind.GENERATE, path, body, headers, 200, responseBody);
  }

  private Call callCreateCache(String path, JsonNode body, Headers headers) throws IOException {
    sleep(cacheCallDelay);
    if (!createFailures.isEmpty()) {
      int code = createFailures.remove();
      return new Call(Kind.CREATE_CACHE, path, body, headers, code, error(code));
    }
    if (nextCreateBody != null) {
      String responseBody = nextCreateBody;
      nextCreateBody = null;
      return new Call(Kind.CREATE_CACHE, path, body, headers, 200, responseBody);
    }
    Instant expireTime = Instant.now().plus(Duration.ofHours(1));
    expireTimes.add(expireTime);
    String name =
        queuedCacheNames.isEmpty()
            ? "cachedContents/cache-" + ++numberedCaches
            : queuedCacheNames.remove();
    String responseBody =
        omitExpireTime
            ? String.format("{\"name\": \"%s\"}", name)
            : String.format("{\"name\": \"%s\", \"expireTime\": \"%s\"}", name, expireTime);
    return new Call(Kind.CREATE_CACHE, path, body, headers, 200, responseBody);
  }

  private Call callDeleteCache(String path, JsonNode body, Headers headers) throws IOException {
    sleep(cacheCallDelay);
    int code = deleteFailures.isEmpty() ? 200 : deleteFailures.remove();
    return new Call(Kind.DELETE_CACHE, path, body, headers, code, code == 200 ? "{}" : error(code));
  }

  private String nextAnswer() {
    return queuedAnswers.isEmpty() ? "Answer " + ++numberedAnswers : queuedAnswers.remove();
  }

  private static GenerateContentResponse answer(String text) {
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(modelText(text))
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build())
        .usageMetadata(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(5000)
                .candidatesTokenCount(10)
                .totalTokenCount(5010)
                .build())
        .build();
  }

  private static GenerateContentResponse partialAnswer(String text) {
    return GenerateContentResponse.builder()
        .candidates(Candidate.builder().content(modelText(text)).build())
        .build();
  }

  // `Thread.sleep(Duration)` needs Java 19, and ADK Java still compiles for Java 17.
  @SuppressWarnings("PreferJavaTimeOverload")
  private static void sleep(Duration duration) throws IOException {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private static Content modelText(String text) {
    return Content.builder().role("model").parts(Part.fromText(text)).build();
  }

  private static String error(int code) {
    return "{\"error\": {\"code\": " + code + ", \"message\": \"fake\"}}";
  }

  private record Call(
      Kind kind, String path, JsonNode body, Headers headers, int code, String responseBody) {}
}
