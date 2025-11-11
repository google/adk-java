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

    System.out.println("rawResponses: " + rawResponses);
    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isFinalTextResponse("Thinking..."),
        isEmptyResponse());
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
      assertThat(GeminiUtil.getTextFromLlmResponse(response)).isEqualTo(expectedText);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextResponse(String expectedText) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getTextFromLlmResponse(response)).isEqualTo(expectedText);
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
      assertThat(GeminiUtil.getTextFromLlmResponse(response)).isEmpty();
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
}
