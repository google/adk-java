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
 * A fake Gemini API that a real genai {@link Client} reaches over HTTP. Each model call gets the
 * next numbered answer, and each cache create returns a new numbered cache that expires in an hour,
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
  private @Nullable String nextCreateBody = null;
  private boolean omitExpireTime = false;
  private Duration cacheCallDelay = Duration.ZERO;
  private int answers = 0;

  Client client() {
    OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(this).build();
    return Client.builder()
        .apiKey("test-api-key")
        .vertexAI(false)
        .clientOptions(ClientOptions.builder().customHttpClient(httpClient).build())
        .build();
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
    Kind kind;
    int code = 200;
    String responseBody;
    if (path.endsWith(":generateContent")) {
      kind = Kind.GENERATE;
      answers++;
      responseBody = answer("Answer " + answers).toJson();
    } else if (path.endsWith(":streamGenerateContent")) {
      kind = Kind.GENERATE;
      answers++;
      responseBody =
          "data: "
              + partialAnswer("Answer ").toJson()
              + "\n\ndata: "
              + answer(String.valueOf(answers)).toJson()
              + "\n\n";
    } else if (request.method().equals("POST") && path.endsWith("/cachedContents")) {
      kind = Kind.CREATE_CACHE;
      sleep(cacheCallDelay);
      if (!createFailures.isEmpty()) {
        code = createFailures.remove();
        responseBody = error(code);
      } else if (nextCreateBody != null) {
        responseBody = nextCreateBody;
        nextCreateBody = null;
      } else {
        Instant expireTime = Instant.now().plus(Duration.ofHours(1));
        expireTimes.add(expireTime);
        String name = "cachedContents/cache-" + expireTimes.size();
        responseBody =
            omitExpireTime
                ? String.format("{\"name\": \"%s\"}", name)
                : String.format("{\"name\": \"%s\", \"expireTime\": \"%s\"}", name, expireTime);
      }
    } else if (request.method().equals("DELETE") && path.contains("/cachedContents/")) {
      kind = Kind.DELETE_CACHE;
      sleep(cacheCallDelay);
      code = deleteFailures.isEmpty() ? 200 : deleteFailures.remove();
      responseBody = code == 200 ? "{}" : error(code);
    } else {
      throw new IOException("Unexpected request: " + request.method() + " " + path);
    }
    calls.add(new Call(kind, path, body, request.headers()));
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .body(ResponseBody.create(responseBody, MediaType.get("application/json")))
        .build();
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

  private record Call(Kind kind, String path, JsonNode body, Headers headers) {}
}
