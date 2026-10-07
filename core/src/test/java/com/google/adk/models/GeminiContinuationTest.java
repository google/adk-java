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

import static com.google.adk.models.GeminiContinuation.MAX_RESUMES;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import com.google.genai.types.Candidate;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.MediaModality;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

/** Tests for {@link Gemini} resuming generations paused by the model with a continuation token. */
@RunWith(JUnit4.class)
public final class GeminiContinuationTest {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final Content QUESTION =
      Content.builder().role("user").parts(Part.fromText("What is the answer?")).build();
  private static final GenerateContentConfig CONFIG =
      GenerateContentConfig.builder().temperature(0.5f).build();

  private final FakeGeminiApi api = new FakeGeminiApi();
  private final Gemini gemini = new Gemini("gemini-test-model", api.client());

  @Test
  public void generateContent_pausedGeneration_resumesAndReturnsWholeGeneration() {
    api.respond(
        withUsage(
            paused("\0state", Part.fromText("The answer is")),
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(5)
                .totalTokenCount(15)
                .thoughtsTokenCount(2)
                .toolUsePromptTokenCount(1)
                .cachedContentTokenCount(4)
                .promptTokensDetails(
                    ImmutableList.of(
                        tokens(MediaModality.Known.TEXT, 8), tokens(MediaModality.Known.IMAGE, 2)))
                .candidatesTokensDetails(ImmutableList.of(tokens(MediaModality.Known.TEXT, 5)))
                .cacheTokensDetails(ImmutableList.of(tokens(MediaModality.Known.TEXT, 4)))
                .toolUsePromptTokensDetails(ImmutableList.of(tokens(/* modality= */ null, 1)))
                .build()),
        withUsage(
            finished(Part.fromText(" 42.")),
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(15)
                .candidatesTokenCount(3)
                .totalTokenCount(18)
                .thoughtsTokenCount(3)
                .toolUsePromptTokenCount(2)
                .promptTokensDetails(
                    ImmutableList.of(
                        tokens(MediaModality.Known.IMAGE, 1), tokens(MediaModality.Known.TEXT, 14)))
                .candidatesTokensDetails(ImmutableList.of(tokens(MediaModality.Known.TEXT, 3)))
                .toolUsePromptTokensDetails(ImmutableList.of(tokens(/* modality= */ null, 2)))
                .build()));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(response.content()).hasValue(modelText("The answer is 42."));
    assertThat(response.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.STOP);
    assertThat(response.usageMetadata())
        .hasValue(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(25)
                .candidatesTokenCount(8)
                .totalTokenCount(33)
                .thoughtsTokenCount(5)
                .toolUsePromptTokenCount(3)
                .cachedContentTokenCount(4)
                .promptTokensDetails(
                    ImmutableList.of(
                        tokens(MediaModality.Known.TEXT, 22), tokens(MediaModality.Known.IMAGE, 3)))
                .candidatesTokensDetails(ImmutableList.of(tokens(MediaModality.Known.TEXT, 8)))
                .cacheTokensDetails(ImmutableList.of(tokens(MediaModality.Known.TEXT, 4)))
                .toolUsePromptTokensDetails(ImmutableList.of(tokens(/* modality= */ null, 3)))
                .build());
    assertThat(api.requests()).hasSize(2);
    JsonNode resumed = api.requests().get(1);
    assertThat(contents(resumed)).containsExactly(QUESTION, modelText("The answer is")).inOrder();
    assertThat(resumed.get("continuationToken").asText()).isEqualTo(base64("\0state"));
  }

  @Test
  public void generateContent_streamPausedGeneration_resumesInOneAggregatedStream() {
    api.respondStream(
        chunk(Part.fromText("The answer")),
        withUsage(
            paused("state", Part.fromText(" is")),
            GenerateContentResponseUsageMetadata.builder().candidatesTokenCount(5).build()));
    api.respondStream(
        withUsage(
            finished(Part.fromText(" 42.")),
            GenerateContentResponseUsageMetadata.builder().candidatesTokenCount(3).build()));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(responses.stream().map(r -> r.partial().orElse(false)).collect(toImmutableList()))
        .containsExactly(true, true, true, false)
        .inOrder();
    assertThat(responses.stream().filter(r -> r.errorCode().isPresent())).isEmpty();
    // A pause is not the end of the generation, so no response reports it.
    assertThat(responses.stream().map(r -> r.finishReason().map(FinishReason::knownEnum)))
        .doesNotContain(Optional.of(FinishReason.Known.CONTINUATION));
    LlmResponse last = getLast(responses);
    assertThat(firstText(last)).isEqualTo("The answer is 42.");
    assertThat(last.finishReason().map(FinishReason::knownEnum)).hasValue(FinishReason.Known.STOP);
    assertThat(
            last.usageMetadata()
                .flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount))
        .hasValue(8);
    assertThat(api.requests()).hasSize(2);
    JsonNode resumed = api.requests().get(1);
    assertThat(getLast(contents(resumed))).isEqualTo(modelText("The answer is"));
    assertThat(resumed.get("continuationToken").asText()).isEqualTo(base64("state"));
  }

  @Test
  public void generateContent_pausedTwice_resumesUntilComplete() {
    api.respond(
        paused("first", Part.fromText("a")),
        paused("second", Part.fromText("b")),
        finished(Part.fromText("c")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(firstText(response)).isEqualTo("abc");
    assertThat(api.requests()).hasSize(3);
    JsonNode secondResume = api.requests().get(2);
    assertThat(contents(secondResume)).containsExactly(QUESTION, modelText("ab")).inOrder();
    assertThat(secondResume.get("continuationToken").asText()).isEqualTo(base64("second"));
  }

  @Test
  public void generateContent_streamPausedTwice_resumesUntilComplete() {
    api.respondStream(paused("first", Part.fromText("a")));
    api.respondStream(paused("second", Part.fromText("b")));
    api.respondStream(finished(Part.fromText("c")));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(firstText(getLast(responses))).isEqualTo("abc");
    assertThat(api.requests()).hasSize(3);
    JsonNode secondResume = api.requests().get(2);
    assertThat(contents(secondResume)).containsExactly(QUESTION, modelText("ab")).inOrder();
    assertThat(secondResume.get("continuationToken").asText()).isEqualTo(base64("second"));
  }

  @Test
  public void generateContent_pauseWithoutOutput_resumesWithOriginalContents() {
    api.respond(paused("state"), finished(Part.fromText("Done.")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(firstText(response)).isEqualTo("Done.");
    assertThat(api.requests()).hasSize(2);
    assertThat(contents(api.requests().get(1))).containsExactly(QUESTION);
  }

  @Test
  public void generateContent_streamPauseWithoutOutput_emitsNoEmptyResponse() {
    api.respondStream(paused("state"));
    api.respondStream(finished(Part.fromText("Done.")));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(responses.stream().map(r -> r.partial().orElse(false)).collect(toImmutableList()))
        .containsExactly(true, false)
        .inOrder();
    assertThat(firstText(getLast(responses))).isEqualTo("Done.");
    assertThat(api.requests()).hasSize(2);
    assertThat(contents(api.requests().get(1))).containsExactly(QUESTION);
  }

  @Test
  public void generateContent_streamPauseWithOnlyStreamTerminator_emitsNoEmptyResponse() {
    api.respondStream(paused("state", Part.fromText("")));
    api.respondStream(finished(Part.fromText("Done.")));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(responses.stream().map(r -> r.partial().orElse(false)).collect(toImmutableList()))
        .containsExactly(true, false)
        .inOrder();
    assertThat(firstText(getLast(responses))).isEqualTo("Done.");
    assertThat(api.requests()).hasSize(2);
    assertThat(contents(api.requests().get(1))).containsExactly(QUESTION);
  }

  @Test
  public void generateContent_resumed_keepsRequestConfig() {
    GenerateContentConfig config =
        CONFIG.toBuilder()
            .httpOptions(
                HttpOptions.builder().extraBody(ImmutableMap.of("custom", "value")).build())
            .build();
    api.respond(paused("state", Part.fromText("a")), finished(Part.fromText("b")));

    List<LlmResponse> unused = generate(config, /* stream= */ false);

    assertThat(api.requests()).hasSize(2);
    JsonNode first = api.requests().get(0);
    JsonNode resumed = api.requests().get(1);
    assertThat(first.has("continuationToken")).isFalse();
    assertThat(resumed.get("custom").asText()).isEqualTo("value");
    assertThat(resumed.get("continuationToken").asText()).isEqualTo(base64("state"));
    assertThat(resumed.get("generationConfig")).isEqualTo(first.get("generationConfig"));
  }

  @Test
  public void generateContent_configWithToken_sendsItFirstAndReplacesItOnResume() {
    api.respond(paused("next", Part.fromText("a")), finished(Part.fromText("b")));

    List<LlmResponse> unused =
        generate(
            CONFIG.toBuilder().continuationToken("caller".getBytes(UTF_8)).build(),
            /* stream= */ false);

    assertThat(api.requests().stream().map(request -> request.get("continuationToken").asText()))
        .containsExactly(base64("caller"), base64("next"))
        .inOrder();
  }

  @Test
  public void generateContent_resumed_joinsSignedTextKeepingFirstSignature() {
    byte[] first = "first".getBytes(UTF_8);
    byte[] second = "second".getBytes(UTF_8);
    api.respond(
        paused("state", Part.builder().text("The answer is").thoughtSignature(first).build()),
        finished(Part.builder().text(" 42.").thoughtSignature(second).build()));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    Part part = getOnlyElement(response.content().flatMap(Content::parts).get());
    assertThat(part.text()).hasValue("The answer is 42.");
    assertThat(part.thoughtSignature().get()).isEqualTo(first);
  }

  @Test
  public void generateContent_resumed_keepsThoughtApartFromAnswer() {
    Part thought = Part.builder().text("Thinking.").thought(true).build();
    api.respond(paused("state", thought), finished(Part.fromText("The answer is 42.")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(response.content().flatMap(Content::parts).get())
        .containsExactly(thought, Part.fromText("The answer is 42."))
        .inOrder();
    assertThat(api.requests()).hasSize(2);
    assertThat(getLast(contents(api.requests().get(1))))
        .isEqualTo(Content.builder().role("model").parts(thought).build());
  }

  @Test
  public void generateContent_resumed_keepsFunctionCallApartFromText() {
    Part call = Part.fromFunctionCall("lookup", ImmutableMap.of("query", "answer"));
    api.respond(
        paused("state", Part.fromText("Let me check."), call), finished(Part.fromText("42.")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(response.content().flatMap(Content::parts).get())
        .containsExactly(Part.fromText("Let me check."), call, Part.fromText("42."))
        .inOrder();
    assertThat(api.requests()).hasSize(2);
    assertThat(getLast(contents(api.requests().get(1))))
        .isEqualTo(
            Content.builder().role("model").parts(Part.fromText("Let me check."), call).build());
  }

  @Test
  public void generateContent_streamResumed_resendsFunctionCallWithoutClientId() {
    Part call = Part.fromFunctionCall("lookup", ImmutableMap.of("q", "x"));
    api.respondStream(chunk(call), paused("state"));
    api.respondStream(finished(Part.fromText("Done.")));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    FunctionCall finalCall =
        getLast(responses).content().flatMap(Content::parts).get().stream()
            .flatMap(part -> part.functionCall().stream())
            .findFirst()
            .orElseThrow();
    assertThat(finalCall.id().orElse("")).startsWith("adk-");
    assertThat(api.requests()).hasSize(2);
    assertThat(getLast(contents(api.requests().get(1))))
        .isEqualTo(Content.builder().role("model").parts(call).build());
  }

  @Test
  public void generateContent_resumed_joinsThoughtSplitByPause() {
    api.respond(
        paused("state", Part.builder().text("Think").thought(true).build()),
        finished(Part.builder().text("ing.").thought(true).build(), Part.fromText("42.")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(response.content().flatMap(Content::parts).get())
        .containsExactly(
            Part.builder().text("Thinking.").thought(true).build(), Part.fromText("42."))
        .inOrder();
  }

  @Test
  public void generateContent_resumed_joinsTextKeepingResumedSignature() {
    byte[] signature = "sig".getBytes(UTF_8);
    api.respond(
        paused("state", Part.fromText("The answer is")),
        finished(Part.builder().text(" 42.").thoughtSignature(signature).build()));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    Part part = getOnlyElement(response.content().flatMap(Content::parts).get());
    assertThat(part.text()).hasValue("The answer is 42.");
    assertThat(part.thoughtSignature().get()).isEqualTo(signature);
  }

  @Test
  public void generateContent_resumed_keepsEmptyTextApart() {
    api.respond(
        paused("state", Part.fromText("a"), Part.fromText("")), finished(Part.fromText("b")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(response.content().flatMap(Content::parts).get())
        .containsExactly(Part.fromText("a"), Part.fromText(""), Part.fromText("b"))
        .inOrder();
  }

  @Test
  public void generateContent_pauseWithoutToken_returnsOutput() {
    api.respond(response("CONTINUATION", /* token= */ null, Part.fromText("a")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(api.requests()).hasSize(1);
    assertThat(firstText(response)).isEqualTo("a");
    assertThat(response.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.CONTINUATION);
  }

  @Test
  public void generateContent_streamPauseWithoutToken_returnsOutput() {
    api.respondStream(response("CONTINUATION", /* token= */ null, Part.fromText("a")));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(api.requests()).hasSize(1);
    LlmResponse last = getLast(responses);
    assertThat(firstText(last)).isEqualTo("a");
    assertThat(last.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.CONTINUATION);
    assertThat(last.errorCode().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.CONTINUATION);
  }

  @Test
  public void generateContent_emptyToken_returnsOutput() {
    api.respond(paused("", Part.fromText("a")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(api.requests()).hasSize(1);
    assertThat(firstText(response)).isEqualTo("a");
  }

  @Test
  public void generateContent_repeatedToken_stopsResuming() {
    api.respond(paused("state", Part.fromText("a")), paused("state", Part.fromText("b")));

    LlmResponse response = getOnlyElement(generate(CONFIG, /* stream= */ false));

    assertThat(api.requests()).hasSize(2);
    assertThat(firstText(response)).isEqualTo("ab");
  }

  @Test
  public void generateContent_streamRepeatedToken_endsWithContinuation() {
    api.respondStream(paused("state", Part.fromText("a")));
    api.respondStream(paused("state", Part.fromText("b")));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(api.requests()).hasSize(2);
    LlmResponse last = getLast(responses);
    assertThat(firstText(last)).isEqualTo("ab");
    assertThat(last.errorCode().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.CONTINUATION);
  }

  @Test
  public void generateContent_pausedOnEveryRequest_stopsAfterMaxResumesOnSmallStack()
      throws InterruptedException {
    for (int i = 0; i <= MAX_RESUMES; i++) {
      api.respond(paused("token" + i, Part.fromText("a")));
    }

    LlmResponse response = getOnlyElement(generateOnSmallStack(/* stream= */ false));

    assertThat(api.requests()).hasSize(MAX_RESUMES + 1);
    assertThat(firstText(response)).isEqualTo("a".repeat(MAX_RESUMES + 1));
    assertThat(response.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.CONTINUATION);
  }

  @Test
  public void generateContent_streamPausedOnEveryRequest_stopsAfterMaxResumesOnSmallStack()
      throws InterruptedException {
    for (int i = 0; i <= MAX_RESUMES; i++) {
      api.respondStream(paused("token" + i, Part.fromText("a")));
    }

    List<LlmResponse> responses = generateOnSmallStack(/* stream= */ true);

    assertThat(api.requests()).hasSize(MAX_RESUMES + 1);
    LlmResponse last = getLast(responses);
    assertThat(firstText(last)).isEqualTo("a".repeat(MAX_RESUMES + 1));
    assertThat(last.errorCode().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.CONTINUATION);
  }

  @Test
  public void generateContent_cancelledBeforeResume_sendsNoFurtherRequest() {
    api.respond(paused("state", Part.fromText("a")), finished(Part.fromText("b")));
    TestSubscriber<LlmResponse> subscriber = new TestSubscriber<>();
    api.onRequest(subscriber::cancel);

    gemini.generateContent(request(CONFIG), /* stream= */ false).subscribe(subscriber);

    assertThat(api.requests()).hasSize(1);
    subscriber.assertNoValues();
  }

  @Test
  public void generateContent_streamCancelledBeforeResume_sendsNoFurtherRequest() {
    api.respondStream(paused("state", Part.fromText("a")));
    api.respondStream(finished(Part.fromText("b")));

    List<LlmResponse> responses =
        gemini.generateContent(request(CONFIG), /* stream= */ true).take(1).toList().blockingGet();

    assertThat(responses).hasSize(1);
    assertThat(api.requests()).hasSize(1);
  }

  @Test
  public void generateContent_subscribedTwice_resumesEachTime() {
    api.respond(
        paused("state", Part.fromText("a")),
        finished(Part.fromText("b")),
        finished(Part.fromText("b")));
    Flowable<LlmResponse> responses = gemini.generateContent(request(CONFIG), /* stream= */ false);

    LlmResponse first = responses.blockingSingle();
    LlmResponse second = responses.blockingSingle();

    assertThat(firstText(first)).isEqualTo("ab");
    assertThat(firstText(second)).isEqualTo("ab");
  }

  @Test
  public void generateContent_resumeFails_returnsError() {
    api.respond(paused("state", Part.fromText("a")));
    api.respondWithError(400);

    TestSubscriber<LlmResponse> subscriber =
        gemini.generateContent(request(CONFIG), /* stream= */ false).test();

    assertThat(api.requests()).hasSize(2);
    subscriber.assertNoValues();
    subscriber.assertError(error -> error.getCause() instanceof ClientException);
  }

  @Test
  public void generateContent_streamCompleteGeneration_sendsOneRequest() {
    GenerateContentResponseUsageMetadata usage =
        GenerateContentResponseUsageMetadata.builder().totalTokenCount(7).build();
    api.respondStream(
        chunk(Part.fromText("Hello")), withUsage(finished(Part.fromText(" world")), usage));

    List<LlmResponse> responses = generate(CONFIG, /* stream= */ true);

    assertThat(api.requests()).hasSize(1);
    assertThat(api.requests().get(0).has("continuationToken")).isFalse();
    LlmResponse last = getLast(responses);
    assertThat(firstText(last)).isEqualTo("Hello world");
    assertThat(last.usageMetadata()).hasValue(usage);
  }

  private List<LlmResponse> generate(GenerateContentConfig config, boolean stream) {
    return gemini.generateContent(request(config), stream).toList().blockingGet();
  }

  /** Generates on a thread with a 256 KB stack, which overflows if every resume adds frames. */
  private List<LlmResponse> generateOnSmallStack(boolean stream) throws InterruptedException {
    AtomicReference<List<LlmResponse>> responses = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(
            /* group= */ null,
            () -> responses.set(generate(CONFIG, stream)),
            "small-stack",
            /* stackSize= */ 256 * 1024);
    thread.setUncaughtExceptionHandler((unused, error) -> failure.set(error));
    thread.start();
    thread.join();
    assertThat(failure.get()).isNull();
    return responses.get();
  }

  private static LlmRequest request(GenerateContentConfig config) {
    return LlmRequest.builder().contents(ImmutableList.of(QUESTION)).config(config).build();
  }

  private static GenerateContentResponse paused(String token, Part... parts) {
    return response("CONTINUATION", token.getBytes(UTF_8), parts);
  }

  private static GenerateContentResponse finished(Part... parts) {
    return response("STOP", /* token= */ null, parts);
  }

  private static GenerateContentResponse chunk(Part... parts) {
    return response(/* finishReason= */ null, /* token= */ null, parts);
  }

  private static GenerateContentResponse response(
      @Nullable String finishReason, byte @Nullable [] token, Part... parts) {
    Candidate.Builder candidate = Candidate.builder();
    if (parts.length > 0) {
      candidate.content(Content.builder().role("model").parts(parts).build());
    }
    if (finishReason != null) {
      candidate.finishReason(finishReason);
    }
    if (token != null) {
      candidate.continuationToken(token);
    }
    return GenerateContentResponse.builder()
        .candidates(ImmutableList.of(candidate.build()))
        .build();
  }

  private static GenerateContentResponse withUsage(
      GenerateContentResponse response, GenerateContentResponseUsageMetadata usage) {
    return response.toBuilder().usageMetadata(usage).build();
  }

  private static ModalityTokenCount tokens(MediaModality.@Nullable Known modality, int tokenCount) {
    ModalityTokenCount.Builder count = ModalityTokenCount.builder().tokenCount(tokenCount);
    if (modality != null) {
      count.modality(modality);
    }
    return count.build();
  }

  private static Content modelText(String text) {
    return Content.builder().role("model").parts(Part.fromText(text)).build();
  }

  private static String firstText(LlmResponse response) {
    return response.content().flatMap(Content::parts).get().get(0).text().get();
  }

  private static String base64(String token) {
    return Base64.getEncoder().encodeToString(token.getBytes(UTF_8));
  }

  private static ImmutableList<Content> contents(JsonNode request) {
    ImmutableList.Builder<Content> contents = ImmutableList.builder();
    request.get("contents").forEach(content -> contents.add(Content.fromJson(content.toString())));
    return contents.build();
  }

  /** A fake Gemini API that records each request and answers it with the next canned response. */
  private static final class FakeGeminiApi implements Interceptor {
    private final Deque<CannedResponse> responses = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private Runnable onRequest = () -> {};

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
        this.responses.add(new CannedResponse(200, response.toJson()));
      }
    }

    /** Answers the next request with a server-sent event stream of {@code chunks}. */
    synchronized void respondStream(GenerateContentResponse... chunks) {
      responses.add(
          new CannedResponse(
              200,
              Arrays.stream(chunks)
                  .map(chunk -> "data: " + chunk.toJson() + "\n\n")
                  .collect(joining())));
    }

    synchronized void respondWithError(int code) {
      responses.add(
          new CannedResponse(code, "{\"error\": {\"code\": " + code + ", \"message\": \"fake\"}}"));
    }

    /** Runs {@code action} when a request arrives, before answering it. */
    synchronized void onRequest(Runnable action) {
      onRequest = action;
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
      onRequest.run();
      CannedResponse response = responses.remove();
      return new Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(response.code())
          .message("fake")
          .body(ResponseBody.create(response.body(), MediaType.get("application/json")))
          .build();
    }

    private record CannedResponse(int code, String body) {}
  }
}
