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
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.ContextCacheConfig;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that {@link Gemini} leaves resuming paused generations to the GenAI SDK. */
@RunWith(JUnit4.class)
public final class GeminiContinuationTest {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final Content QUESTION =
      Content.builder().role("user").parts(Part.fromText("What is the answer?")).build();
  private static final Content EARLIER_ANSWER =
      Content.builder().role("model").parts(Part.fromText("It depends.")).build();
  private static final Content FOLLOW_UP =
      Content.builder().role("user").parts(Part.fromText("On what?")).build();
  private static final String CACHE_NAME = "cachedContents/answers";

  private final ScriptedGeminiApi api = new ScriptedGeminiApi();
  private final Gemini gemini = new Gemini("gemini-test-model", api.client());

  @Test
  public void generateContent_pausedGeneration_isResumedWithSummedUsage() {
    api.respond(
        response("CONTINUATION", "state", usage(10, 5, 15), Part.fromText("The answer is")),
        response("STOP", /* token= */ null, usage(15, 3, 18), Part.fromText(" 42.")));

    LlmResponse response = getOnlyElement(generate(/* stream= */ false));

    assertThat(texts(response)).containsExactly("The answer is", " 42.").inOrder();
    assertThat(finishReason(response)).isEqualTo(FinishReason.Known.STOP);
    assertUsage(response, /* prompt= */ 10 + 15, /* candidates= */ 5 + 3, /* total= */ 15 + 18);
    ImmutableList<JsonNode> requests = api.requests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).has("continuationToken")).isFalse();
    assertThat(requests.get(1).get("continuationToken").asText()).isEqualTo(base64("state"));
    assertThat(requests.get(1).get("contents")).isEqualTo(requests.get(0).get("contents"));
  }

  @Test
  public void generateContent_pausedStream_isResumedWithSummedUsage() {
    api.respondStream(
        response(
            /* finishReason= */ null, /* token= */ null, /* usage= */ null, Part.fromText("The")),
        response("CONTINUATION", "state", usage(10, 5, 15), Part.fromText(" answer is")));
    api.respondStream(response("STOP", /* token= */ null, usage(15, 3, 18), Part.fromText(" 42.")));

    List<LlmResponse> responses = generate(/* stream= */ true);

    LlmResponse last = getLast(responses);
    assertThat(last.partial()).isEmpty();
    assertThat(texts(last)).containsExactly("The answer is 42.");
    assertThat(finishReason(last)).isEqualTo(FinishReason.Known.STOP);
    assertUsage(last, /* prompt= */ 10 + 15, /* candidates= */ 5 + 3, /* total= */ 15 + 18);
    assertThat(responses.stream().map(GeminiContinuationTest::finishReason))
        .doesNotContain(FinishReason.Known.CONTINUATION);
    assertThat(api.requests()).hasSize(2);
  }

  @Test
  public void generateContent_streamPauseWithoutOutput_emitsNoEmptyResponse() {
    api.respondStream(response("CONTINUATION", "state", usage(10, 0, 10)));
    api.respondStream(
        response("STOP", /* token= */ null, usage(15, 3, 18), Part.fromText("Done.")));

    List<LlmResponse> responses = generate(/* stream= */ true);

    assertThat(responses.stream().map(response -> response.partial().orElse(false)))
        .containsExactly(true, false)
        .inOrder();
    assertThat(texts(getLast(responses))).containsExactly("Done.");
    assertThat(api.requests()).hasSize(2);
  }

  @Test
  public void generateContent_streamPauseWithOnlyStreamTerminator_emitsNoEmptyResponse() {
    api.respondStream(response("CONTINUATION", "state", usage(10, 0, 10), Part.fromText("")));
    api.respondStream(
        response("STOP", /* token= */ null, usage(15, 3, 18), Part.fromText("Done.")));

    List<LlmResponse> responses = generate(/* stream= */ true);

    assertThat(responses.stream().map(response -> response.partial().orElse(false)))
        .containsExactly(true, false)
        .inOrder();
    assertThat(texts(getLast(responses))).containsExactly("Done.");
    assertThat(api.requests()).hasSize(2);
  }

  @Test
  public void generateContent_maxTokensWithToken_isNotResumed() {
    api.respond(response("MAX_TOKENS", "state", usage(10, 5, 15), Part.fromText("The answer is")));

    LlmResponse response = getOnlyElement(generate(/* stream= */ false));

    assertThat(finishReason(response)).isEqualTo(FinishReason.Known.MAX_TOKENS);
    assertThat(api.requests()).hasSize(1);
  }

  @Test
  public void generateContent_pausedGenerationWithCache_isResumedWithTheCache() {
    LlmRequest request = requestThatCreatesCache();
    api.respond(
        response("CONTINUATION", "state", usage(10, 5, 15), Part.fromText("The answer is")),
        response("STOP", /* token= */ null, usage(15, 3, 18), Part.fromText(" 42.")));

    LlmResponse response =
        getOnlyElement(gemini.generateContent(request, /* stream= */ false).toList().blockingGet());

    assertResumedWithCache(api.requests());
    assertThat(response.cacheMetadata().flatMap(CacheMetadata::cacheName)).hasValue(CACHE_NAME);
    assertUsage(response, /* prompt= */ 10 + 15, /* candidates= */ 5 + 3, /* total= */ 15 + 18);
  }

  @Test
  public void generateContent_pausedStreamWithCache_isResumedWithTheCache() {
    LlmRequest request = requestThatCreatesCache();
    api.respondStream(
        response(
            /* finishReason= */ null, /* token= */ null, /* usage= */ null, Part.fromText("The")),
        response("CONTINUATION", "state", usage(10, 5, 15), Part.fromText(" answer is")));
    api.respondStream(response("STOP", /* token= */ null, usage(15, 3, 18), Part.fromText(" 42.")));

    List<LlmResponse> responses =
        gemini.generateContent(request, /* stream= */ true).toList().blockingGet();

    assertResumedWithCache(api.requests());
    LlmResponse last = getLast(responses);
    assertThat(responses.stream().filter(response -> response.cacheMetadata().isPresent()))
        .containsExactly(last);
    assertThat(last.cacheMetadata().flatMap(CacheMetadata::cacheName)).hasValue(CACHE_NAME);
    assertUsage(last, /* prompt= */ 10 + 15, /* candidates= */ 5 + 3, /* total= */ 15 + 18);
  }

  private List<LlmResponse> generate(boolean stream) {
    LlmRequest request = LlmRequest.builder().contents(ImmutableList.of(QUESTION)).build();
    return gemini.generateContent(request, stream).toList().blockingGet();
  }

  /**
   * Returns a request whose next call creates a cache: a first call records the fingerprint of the
   * cacheable contents, and the fake answers the cache creation with {@link #CACHE_NAME}.
   */
  private LlmRequest requestThatCreatesCache() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(QUESTION, EARLIER_ANSWER, FOLLOW_UP))
            .cacheConfig(new ContextCacheConfig())
            .build();
    api.respond(response("STOP", /* token= */ null, usage(5000, 1, 5001), Part.fromText("OK")));
    CacheMetadata fingerprint =
        getOnlyElement(gemini.generateContent(request, /* stream= */ false).toList().blockingGet())
            .cacheMetadata()
            .orElseThrow();
    api.respondWithCache(CACHE_NAME);
    return request.toBuilder().cacheMetadata(fingerprint).cacheableContentsTokenCount(5000).build();
  }

  /**
   * Asserts that, after the fingerprint call and the cache creation, the paused generation and its
   * resume both name the cache and send the same contents.
   */
  private static void assertResumedWithCache(ImmutableList<JsonNode> requests) {
    assertThat(requests).hasSize(4);
    JsonNode paused = requests.get(2);
    JsonNode resume = requests.get(3);
    assertThat(paused.path("cachedContent").asText()).isEqualTo(CACHE_NAME);
    assertThat(resume.path("cachedContent").asText()).isEqualTo(CACHE_NAME);
    assertThat(resume.get("contents")).isEqualTo(paused.get("contents"));
    assertThat(resume.path("continuationToken").asText()).isEqualTo(base64("state"));
  }

  private static GenerateContentResponse response(
      @Nullable String finishReason,
      @Nullable String token,
      @Nullable GenerateContentResponseUsageMetadata usage,
      Part... parts) {
    Candidate.Builder candidate =
        Candidate.builder().content(Content.builder().role("model").parts(parts).build());
    if (finishReason != null) {
      candidate.finishReason(finishReason);
    }
    if (token != null) {
      candidate.continuationToken(token.getBytes(UTF_8));
    }
    GenerateContentResponse.Builder response =
        GenerateContentResponse.builder().candidates(ImmutableList.of(candidate.build()));
    if (usage != null) {
      response.usageMetadata(usage);
    }
    return response.build();
  }

  private static GenerateContentResponseUsageMetadata usage(
      int promptTokens, int candidatesTokens, int totalTokens) {
    return GenerateContentResponseUsageMetadata.builder()
        .promptTokenCount(promptTokens)
        .candidatesTokenCount(candidatesTokens)
        .totalTokenCount(totalTokens)
        .build();
  }

  private static void assertUsage(LlmResponse response, int prompt, int candidates, int total) {
    GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElseThrow();
    assertThat(usage.promptTokenCount()).hasValue(prompt);
    assertThat(usage.candidatesTokenCount()).hasValue(candidates);
    assertThat(usage.totalTokenCount()).hasValue(total);
  }

  private static ImmutableList<String> texts(LlmResponse response) {
    return response.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
        .map(part -> part.text().orElse(""))
        .collect(toImmutableList());
  }

  private static FinishReason.@Nullable Known finishReason(LlmResponse response) {
    return response.finishReason().map(FinishReason::knownEnum).orElse(null);
  }

  private static String base64(String token) {
    return Base64.getEncoder().encodeToString(token.getBytes(UTF_8));
  }

  /** A fake Gemini API that records each request and answers it with the next canned response. */
  private static final class ScriptedGeminiApi implements Interceptor {
    private final Deque<String> responses = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();

    Client client() {
      OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(this).build();
      return Client.builder()
          .apiKey("test-api-key")
          .vertexAI(false)
          .clientOptions(ClientOptions.builder().customHttpClient(httpClient).build())
          .build();
    }

    synchronized void respond(GenerateContentResponse... responses) {
      for (GenerateContentResponse response : responses) {
        this.responses.add(response.toJson());
      }
    }

    /** Answers the next request with a server-sent event stream of {@code chunks}. */
    synchronized void respondStream(GenerateContentResponse... chunks) {
      responses.add(
          Arrays.stream(chunks)
              .map(chunk -> "data: " + chunk.toJson() + "\n\n")
              .collect(joining()));
    }

    /** Answers the next request, which creates a cache, with a cache named {@code name}. */
    synchronized void respondWithCache(String name) {
      responses.add("{\"name\":\"" + name + "\",\"expireTime\":\"2099-01-01T00:00:00Z\"}");
    }

    synchronized ImmutableList<JsonNode> requests() {
      return ImmutableList.copyOf(requests);
    }

    @Override
    public synchronized Response intercept(Chain chain) throws IOException {
      Request request = chain.request();
      Buffer body = new Buffer();
      request.body().writeTo(body);
      requests.add(objectMapper.readTree(body.readUtf8()));
      return new Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("fake")
          .body(ResponseBody.create(responses.remove(), MediaType.get("application/json")))
          .build();
    }
  }
}
