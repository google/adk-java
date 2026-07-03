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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.Predicate;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiTest {

  // Test cases for processRawResponses static method
  @Test
  public void processRawResponses_withTextChunks_emitsPartialResponses() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Hello"), toResponseWithText(" world"));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isPartialTextResponse("Hello"), isPartialTextResponse(" world"));
  }

  @Test
  public void
      processRawResponses_textThenFunctionCall_emitsPartialTextThenFullTextAndFunctionCall() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Thinking..."),
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isFinalTextResponse("Thinking..."),
        isFunctionCallResponse());
  }

  @Test
  public void processRawResponses_textAndStopReason_emitsPartialThenFinalText() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"), toResponseWithText(" world", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponse(" world"),
        isFinalTextResponse("Hello world"));
  }

  @Test
  public void processRawResponses_emptyStream_emitsNothing() {
    Flowable<GenerateContentResponse> rawResponses = Flowable.empty();

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(llmResponses);
  }

  @Test
  public void processRawResponses_singleEmptyResponse_emitsOneEmptyResponse() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(GenerateContentResponse.builder().build());

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(llmResponses, isEmptyResponse());
  }

  @Test
  public void processRawResponses_finishReasonNotStop_doesNotEmitFinalAccumulatedText() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"),
            toResponseWithText(" world", FinishReason.Known.MAX_TOKENS));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isPartialTextResponse("Hello"), isPartialTextResponse(" world"));
  }

  @Test
  public void processRawResponses_textThenEmpty_emitsPartialTextThenFullTextAndEmpty() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Thinking..."), GenerateContentResponse.builder().build());

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isFinalTextResponse("Thinking..."),
        isEmptyResponse());
  }

  @Test
  public void processRawResponses_withTextChunks_partialResponsesIncludeUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello", metadata1), toResponseWithText(" world", metadata2));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponseWithUsageMetadata("Hello", metadata1),
        isPartialTextResponseWithUsageMetadata(" world", metadata2));
  }

  @Test
  public void processRawResponses_textAndStopReason_finalResponseIncludesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata = createUsageMetadata(10, 20, 30);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"),
            toResponseWithText(" world", FinishReason.Known.STOP, metadata));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponseWithUsageMetadata(" world", metadata),
        isFinalTextResponseWithUsageMetadata("Hello world", metadata));
  }

  @Test
  public void processRawResponses_thoughtChunksAndStop_includeUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithThoughtText("Thinking", metadata1),
            toResponseWithThoughtText(" deeply", FinishReason.Known.STOP, metadata2));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isPartialThoughtResponseWithUsageMetadata(" deeply", metadata2),
        isFinalThoughtResponseWithUsageMetadata("Thinking deeply", metadata2));
  }

  @Test
  public void processRawResponses_thoughtAndTextWithStop_onlyFinalTextIncludesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 5, 10);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(10, 20, 30);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithThoughtText("Thinking", metadata1),
            toResponseWithText("Answer", FinishReason.Known.STOP, metadata2));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isPartialTextResponseWithUsageMetadata("Answer", metadata2),
        isFinalThoughtResponseWithNoUsageMetadata("Thinking"),
        isFinalTextResponseWithUsageMetadata("Answer", metadata2));
  }

  // Helper methods for assertions

  private void assertLlmResponses(
      Flowable<LlmResponse> llmResponses, Predicate<LlmResponse>... predicates) {
    TestSubscriber<LlmResponse> testSubscriber = llmResponses.test();
    testSubscriber.assertValueCount(predicates.length);
    for (int i = 0; i < predicates.length; i++) {
      testSubscriber.assertValueAt(i, predicates[i]);
    }
    testSubscriber.assertComplete();
    testSubscriber.assertNoErrors();
  }

  private static Predicate<LlmResponse> isPartialTextResponse(String expectedText) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextResponse(String expectedText) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFunctionCallResponse() {
    return response -> {
      assertThat(response.content().get().parts().get().get(0).functionCall()).isNotNull();
      return true;
    };
  }

  private static Predicate<LlmResponse> isEmptyResponse() {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEmpty();
      return true;
    };
  }

  private static Predicate<LlmResponse> isPartialTextResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isPartialThoughtResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtResponseWithNoUsageMetadata(
      String expectedText) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).isEmpty();
      return true;
    };
  }

  // Helper methods to create responses for testing

  private GenerateContentResponse toResponseWithText(String text) {
    return toResponse(Part.fromText(text));
  }

  private GenerateContentResponse toResponseWithText(String text, FinishReason.Known finishReason) {
    return toResponse(
        Candidate.builder()
            .content(Content.builder().parts(Part.fromText(text)).build())
            .finishReason(new FinishReason(finishReason))
            .build());
  }

  private GenerateContentResponse toResponse(Part part) {
    return toResponse(Candidate.builder().content(Content.builder().parts(part).build()).build());
  }

  private GenerateContentResponse toResponse(Candidate candidate) {
    return GenerateContentResponse.builder().candidates(candidate).build();
  }

  private GenerateContentResponse toResponseWithText(
      String text, GenerateContentResponseUsageMetadata usageMetadata) {
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(Content.builder().parts(Part.fromText(text)).build())
                .build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private GenerateContentResponse toResponseWithText(
      String text,
      FinishReason.Known finishReason,
      GenerateContentResponseUsageMetadata usageMetadata) {
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(Content.builder().parts(Part.fromText(text)).build())
                .finishReason(new FinishReason(finishReason))
                .build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private GenerateContentResponse toResponseWithThoughtText(
      String text, GenerateContentResponseUsageMetadata usageMetadata) {
    Part thoughtPart = Part.fromText(text).toBuilder().thought(true).build();
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder().content(Content.builder().parts(thoughtPart).build()).build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private GenerateContentResponse toResponseWithThoughtText(
      String text,
      FinishReason.Known finishReason,
      GenerateContentResponseUsageMetadata usageMetadata) {
    Part thoughtPart = Part.fromText(text).toBuilder().thought(true).build();
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(Content.builder().parts(thoughtPart).build())
                .finishReason(new FinishReason(finishReason))
                .build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private static GenerateContentResponseUsageMetadata createUsageMetadata(
      int promptTokens, int candidateTokens, int totalTokens) {
    return GenerateContentResponseUsageMetadata.builder()
        .promptTokenCount(promptTokens)
        .candidatesTokenCount(candidateTokens)
        .totalTokenCount(totalTokens)
        .build();
  }
}
