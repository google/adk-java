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

import com.google.common.collect.ImmutableList;
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
import java.nio.charset.StandardCharsets;
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
        isPartialFunctionCallResponse("test_function"));
    // No final response is emitted, no FinishReason.STOP means the stream is treated as
    // incomplete/aborted.
  }

  @Test
  public void processRawResponses_chunkWithBothTextAndFunctionCall_emitsPartialWithBoth() {
    GenerateContentResponse chunkWithBoth =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(
                        Content.builder()
                            .parts(
                                Part.fromText("Here is the call:"),
                                Part.fromFunctionCall("my_tool", ImmutableMap.of()))
                            .build())
                    .build())
            .build();

    Flowable<GenerateContentResponse> rawResponses = Flowable.just(chunkWithBoth);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isPartialTextAndFunctionCallResponse("Here is the call:", "my_tool"));
    // No final response is emitted, no FinishReason.STOP means the stream is treated as
    // incomplete/aborted.
  }

  @Test
  public void processRawResponses_streamingFunctionCallsAndStop_emitsPartialsThenFinalAggregated() {
    Part fc1 = Part.fromFunctionCall("tool1", ImmutableMap.of("arg1", "val1"));
    Part fc2 = Part.fromFunctionCall("tool2", ImmutableMap.of("arg2", "val2"));
    GenerateContentResponse fc2WithStop =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(Content.builder().parts(fc2).build())
                    .finishReason(new FinishReason(FinishReason.Known.STOP))
                    .build())
            .build();
    Flowable<GenerateContentResponse> rawResponses = Flowable.just(toResponse(fc1), fc2WithStop);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialFunctionCallResponse("tool1"),
        isPartialFunctionCallResponse("tool2"),
        isFinalAggregatedFunctionCallResponse("tool1", "tool2"));
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
    // No final response is emitted, no FinishReason.STOP means the stream is treated as
    // incomplete/aborted.
  }

  @Test
  public void processRawResponses_textThenEmpty_emitsPartialTextThenFullText() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Thinking..."), GenerateContentResponse.builder().build());

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(llmResponses, isPartialTextResponse("Thinking..."));
    // No final response is emitted, no FinishReason.STOP means the stream is treated as
    // incomplete/aborted.
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
    // No final response is emitted, no FinishReason.STOP means the stream is treated as
    // incomplete/aborted.
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
  public void
      processRawResponses_textThenEmptyStopWithUsageMetadata_finalResponseIncludesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata = createUsageMetadata(10, 20, 30);
    GenerateContentResponse stopResponse =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder().finishReason(new FinishReason(FinishReason.Known.STOP)).build())
            .usageMetadata(metadata)
            .build();
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Hello"), stopResponse);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isFinalTextResponseWithUsageMetadata("Hello", metadata));
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
        isFinalThoughtAndTextResponseWithUsageMetadata("Thinking", "Answer", metadata2));
  }

  @Test
  public void
      processRawResponses_interleavedThoughtAndTextWithStop_separatelyAggregatesThoughtAndText() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 5, 10);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata3 = createUsageMetadata(10, 15, 25);
    GenerateContentResponseUsageMetadata metadata4 = createUsageMetadata(10, 20, 30);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithThoughtText("Thinking 1", metadata1),
            toResponseWithText("Answer 1", metadata2),
            toResponseWithThoughtText(" Thinking 2", metadata3),
            toResponseWithText(" Answer 2", FinishReason.Known.STOP, metadata4));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking 1", metadata1),
        isPartialTextResponseWithUsageMetadata("Answer 1", metadata2),
        isPartialThoughtResponseWithUsageMetadata(" Thinking 2", metadata3),
        isPartialTextResponseWithUsageMetadata(" Answer 2", metadata4),
        isFinalInterleavedThoughtAndTextResponseWithUsageMetadata(
            "Thinking 1", "Answer 1", " Thinking 2", " Answer 2", metadata4));
  }

  @Test
  public void
      processRawResponses_textAndFunctionCallWithStop_onlyFinalFunctionCallIncludesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 5, 10);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(10, 20, 30);
    Part fcPart = Part.fromFunctionCall("my_tool", ImmutableMap.of());
    GenerateContentResponse stopResponse =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(Content.builder().parts(fcPart).build())
                    .finishReason(new FinishReason(FinishReason.Known.STOP))
                    .build())
            .usageMetadata(metadata2)
            .build();
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Answer", metadata1), stopResponse);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponseWithUsageMetadata("Answer", metadata1),
        isPartialFunctionCallResponse("my_tool"),
        isFinalTextAndFunctionCallResponseWithUsageMetadata("Answer", metadata2, "my_tool"));
  }

  @Test
  public void
      processRawResponses_thoughtThenEmptyWithSignatureAndStop_flushesThoughtWithSignature() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    GenerateContentResponse chunk1 = toResponseWithThoughtText("Thinking", metadata1);
    GenerateContentResponse chunk2 =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(
                        Content.builder()
                            .parts(
                                Part.builder()
                                    .thought(true)
                                    .thoughtSignature("sig".getBytes(StandardCharsets.UTF_8))
                                    .build())
                            .build())
                    .finishReason(new FinishReason(FinishReason.Known.STOP))
                    .build())
            .usageMetadata(metadata2)
            .build();
    Flowable<GenerateContentResponse> rawResponses = Flowable.just(chunk1, chunk2);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isFinalThoughtResponseWithUsageMetadataAndSignature("Thinking", metadata2, "sig"));
  }

  @Test
  public void
      processRawResponses_thoughtWithSignatureThenTextAndStop_flushesThoughtWithSignature() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    GenerateContentResponse chunk1 =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(
                        Content.builder()
                            .parts(
                                Part.builder()
                                    .text("Thinking")
                                    .thought(true)
                                    .thoughtSignature("sig".getBytes(StandardCharsets.UTF_8))
                                    .build())
                            .build())
                    .build())
            .usageMetadata(metadata1)
            .build();
    GenerateContentResponse chunk2 =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(
                        Content.builder()
                            .parts(Part.builder().text("Hello").thought(false).build())
                            .build())
                    .finishReason(new FinishReason(FinishReason.Known.STOP))
                    .build())
            .usageMetadata(metadata2)
            .build();
    Flowable<GenerateContentResponse> rawResponses = Flowable.just(chunk1, chunk2);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isPartialTextResponseWithUsageMetadata("Hello", metadata2),
        isFinalThoughtAndTextResponseWithUsageMetadataAndSignature(
            "Thinking", "Hello", metadata2, "sig"));
  }

  @Test
  public void
      processRawResponses_thoughtThenFunctionCallWithSignatureAndStop_attachesSignatureToFunctionCall() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    GenerateContentResponse chunk1 = toResponseWithThoughtText("Thinking", metadata1);
    GenerateContentResponse chunk2 =
        toResponse(Part.fromFunctionCall("my_tool", ImmutableMap.of()));
    GenerateContentResponse chunk3 =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(
                        Content.builder()
                            .parts(
                                Part.builder()
                                    .thought(true)
                                    .thoughtSignature("sig".getBytes(StandardCharsets.UTF_8))
                                    .build())
                            .build())
                    .finishReason(new FinishReason(FinishReason.Known.STOP))
                    .build())
            .usageMetadata(metadata2)
            .build();
    Flowable<GenerateContentResponse> rawResponses = Flowable.just(chunk1, chunk2, chunk3);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isPartialFunctionCallResponse("my_tool"),
        isFinalThoughtAndFunctionCallResponseWithUsageMetadataAndSignature(
            "Thinking", metadata2, "sig", "my_tool"));
  }

  @Test
  public void processRawResponses_emptyPartsThenSignature_doesNotThrowException() {
    GenerateContentResponseUsageMetadata metadata = createUsageMetadata(5, 10, 15);
    GenerateContentResponse chunk1 =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(Content.builder().parts(ImmutableList.of()).build())
                    .build())
            .build();
    GenerateContentResponse chunk2 =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(
                        Content.builder()
                            .parts(
                                Part.builder()
                                    .thought(true)
                                    .thoughtSignature("sig".getBytes(StandardCharsets.UTF_8))
                                    .build())
                            .build())
                    .finishReason(new FinishReason(FinishReason.Known.STOP))
                    .build())
            .usageMetadata(metadata)
            .build();
    Flowable<GenerateContentResponse> rawResponses = Flowable.just(chunk1, chunk2);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isEmptyResponse(),
        isFinalThoughtResponseWithUsageMetadataAndSignature("", metadata, "sig"));
  }

  @Test
  public void functionCallThenEmptyTextWithStop_emitsPartialThenFinalAggregatedFunctionCall() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses =
        Gemini.processRawResponses(rawResponses).filter(Gemini::shouldEmit);

    assertLlmResponses(
        llmResponses,
        isPartialFunctionCallResponse("test_function"),
        isFinalAggregatedFunctionCallResponse("test_function"));
  }

  @Test
  public void functionCallThenEmptyTextWithUsageMetadata_emitsFinalAggregatedWithUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata = createUsageMetadata(5, 10, 15);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP, metadata));

    Flowable<LlmResponse> llmResponses =
        Gemini.processRawResponses(rawResponses).filter(Gemini::shouldEmit);

    assertLlmResponses(
        llmResponses,
        isPartialFunctionCallResponse("test_function"),
        isFinalAggregatedFunctionCallResponseWithUsageMetadata(metadata, "test_function"));
  }

  @Test
  public void functionCallThenEmptyText_doesNotEmitExtraEmptyResponse() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText(""));

    Flowable<LlmResponse> llmResponses =
        Gemini.processRawResponses(rawResponses).filter(Gemini::shouldEmit);

    assertLlmResponses(llmResponses, isPartialFunctionCallResponse("test_function"));
    // No final response is emitted, no FinishReason.STOP means the stream is treated as
    // incomplete/aborted.
  }

  @Test
  public void textThenFunctionCallThenEmptyTextWithStop_emitsTextThenFunctionCalls() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Thinking..."),
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses =
        Gemini.processRawResponses(rawResponses).filter(Gemini::shouldEmit);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isPartialFunctionCallResponse("test_function"),
        isFinalTextAndFunctionCallResponseWithNoUsageMetadata("Thinking...", "test_function"));
  }

  // Test cases for the shouldEmit filter applied by generateContent after processRawResponses.
  // shouldEmit drops chunks that are empty-text-only unless they carry final metadata (usage
  // metadata or finish reason); everything else is forwarded.
  // processRawResponses normally already strips empty-text-only chunks, so shouldEmit
  // is defense-in-depth, but it must still behave correctly when fed any LlmResponse directly.

  @Test
  public void shouldEmit_emptyTextOnlyResponseWithNoMetadata_returnsFalse() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isFalse();
  }

  @Test
  public void shouldEmit_emptyTextOnlyResponseWithFinishReason_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .finishReason(new FinishReason(FinishReason.Known.STOP))
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_emptyTextOnlyResponseWithUsageMetadata_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .usageMetadata(createUsageMetadata(5, 10, 15))
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_nonEmptyTextResponse_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("hello")).build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_functionCallResponse_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(Part.fromFunctionCall("test_function", ImmutableMap.of()))
                    .build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_contentlessResponse_returnsTrue() {
    // A response with no content at all is not an empty-text-only response, so it should pass
    // through regardless of metadata. This is the shape emitted by processRawResponses after it
    // strips empty-text content while preserving metadata.
    LlmResponse response = LlmResponse.builder().build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_multiPartResponseWithEmptyTextPart_returnsTrue() {
    // Only single-part empty-text responses are considered "empty-text-only". A multi-part response
    // is treated as carrying semantic content and must always pass through.
    LlmResponse response =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(Part.fromText(""), Part.fromText("hello"))
                    .build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
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
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      return true;
    };
  }

  private static Predicate<LlmResponse> isPartialFunctionCallResponse(String expectedToolName) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(response.content().get().parts().get()).hasSize(1);
      assertThat(response.content().get().parts().get().get(0).functionCall().get().name())
          .hasValue(expectedToolName);
      return true;
    };
  }

  private static Predicate<LlmResponse> isPartialTextAndFunctionCallResponse(
      String expectedText, String expectedToolName) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(response.content().get().parts().get()).hasSize(2);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedText);
      assertThat(response.content().get().parts().get().get(1).functionCall().get().name())
          .hasValue(expectedToolName);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalAggregatedFunctionCallResponse(
      String... expectedToolNames) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(expectedToolNames.length);
      for (int i = 0; i < expectedToolNames.length; i++) {
        assertThat(response.content().get().parts().get().get(i).functionCall().get().name())
            .hasValue(expectedToolNames[i]);
      }
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalAggregatedFunctionCallResponseWithUsageMetadata(
      GenerateContentResponseUsageMetadata expectedMetadata, String... expectedToolNames) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(expectedToolNames.length);
      for (int i = 0; i < expectedToolNames.length; i++) {
        assertThat(response.content().get().parts().get().get(i).functionCall().get().name())
            .hasValue(expectedToolNames[i]);
      }
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isEmptyResponse() {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
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
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtResponseWithUsageMetadataAndSignature(
      String expectedText,
      GenerateContentResponseUsageMetadata expectedMetadata,
      String expectedSignature) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(
              GeminiUtil.getPart0FromLlmResponse(response)
                  .flatMap(Part::thoughtSignature)
                  .orElse(new byte[0]))
          .isEqualTo(expectedSignature.getBytes(StandardCharsets.UTF_8));

      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtAndTextResponseWithUsageMetadata(
      String expectedThought,
      String expectedText,
      GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(2);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedThought);
      assertThat(response.content().get().parts().get().get(0).thought()).hasValue(true);
      assertThat(response.content().get().parts().get().get(1).text()).hasValue(expectedText);
      assertThat(response.content().get().parts().get().get(1).thought()).hasValue(false);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtAndTextResponseWithUsageMetadataAndSignature(
      String expectedThought,
      String expectedText,
      GenerateContentResponseUsageMetadata expectedMetadata,
      String expectedSignature) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(2);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedThought);
      assertThat(response.content().get().parts().get().get(0).thought()).hasValue(true);
      assertThat(
              response.content().get().parts().get().get(0).thoughtSignature().orElse(new byte[0]))
          .isEqualTo(expectedSignature.getBytes(StandardCharsets.UTF_8));
      assertThat(response.content().get().parts().get().get(1).text()).hasValue(expectedText);
      assertThat(response.content().get().parts().get().get(1).thought()).hasValue(false);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalInterleavedThoughtAndTextResponseWithUsageMetadata(
      String expectedThought1,
      String expectedText1,
      String expectedThought2,
      String expectedText2,
      GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(4);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedThought1);
      assertThat(response.content().get().parts().get().get(0).thought()).hasValue(true);
      assertThat(response.content().get().parts().get().get(1).text()).hasValue(expectedText1);
      assertThat(response.content().get().parts().get().get(1).thought()).hasValue(false);
      assertThat(response.content().get().parts().get().get(2).text()).hasValue(expectedThought2);
      assertThat(response.content().get().parts().get().get(2).thought()).hasValue(true);
      assertThat(response.content().get().parts().get().get(3).text()).hasValue(expectedText2);
      assertThat(response.content().get().parts().get().get(3).thought()).hasValue(false);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextAndFunctionCallResponseWithUsageMetadata(
      String expectedText,
      GenerateContentResponseUsageMetadata expectedMetadata,
      String... expectedToolNames) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(expectedToolNames.length + 1);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedText);
      for (int i = 0; i < expectedToolNames.length; i++) {
        assertThat(response.content().get().parts().get().get(i + 1).functionCall().get().name())
            .hasValue(expectedToolNames[i]);
      }
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextAndFunctionCallResponseWithNoUsageMetadata(
      String expectedText, String... expectedToolNames) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(expectedToolNames.length + 1);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedText);
      for (int i = 0; i < expectedToolNames.length; i++) {
        assertThat(response.content().get().parts().get().get(i + 1).functionCall().get().name())
            .hasValue(expectedToolNames[i]);
      }
      assertThat(response.usageMetadata()).isEmpty();
      return true;
    };
  }

  private static Predicate<LlmResponse>
      isFinalThoughtAndFunctionCallResponseWithUsageMetadataAndSignature(
          String expectedThought,
          GenerateContentResponseUsageMetadata expectedMetadata,
          String expectedSignature,
          String... expectedToolNames) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(response.content().get().parts().get()).hasSize(expectedToolNames.length + 1);
      assertThat(response.content().get().parts().get().get(0).text()).hasValue(expectedThought);
      assertThat(response.content().get().parts().get().get(0).thought()).hasValue(true);
      for (int i = 0; i < expectedToolNames.length; i++) {
        Part part = response.content().get().parts().get().get(i + 1);
        assertThat(part.functionCall().get().name()).hasValue(expectedToolNames[i]);
        assertThat(part.thoughtSignature().orElse(new byte[0]))
            .isEqualTo(expectedSignature.getBytes(StandardCharsets.UTF_8));
      }
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
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

  private GenerateContentResponse toResponse(Part part) {
    return toResponse(Candidate.builder().content(Content.builder().parts(part).build()).build());
  }

  private GenerateContentResponse toResponse(Candidate candidate) {
    return GenerateContentResponse.builder().candidates(candidate).build();
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
