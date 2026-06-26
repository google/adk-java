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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
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

    // No finish reason: the accumulated text is still emitted as a final aggregated response.
    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponse(" world"),
        isFinalTextResponse("Hello world"));
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
        isPartialFunctionCallResponse("test_function"),
        isFinalTextAndFunctionCallResponseWithNoUsageMetadata("Thinking...", "test_function"));
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
        llmResponses,
        isPartialTextAndFunctionCallResponse("Here is the call:", "my_tool"),
        isFinalTextAndFunctionCallResponseWithNoUsageMetadata("Here is the call:", "my_tool"));
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

  // Mirrors ADK Python's test_streaming_fc_generates_consistent_id_across_chunks: a function call
  // arriving without an ID gets one client-side ID, reused in both the partial and final events so
  // consumers can correlate them (and distinct calls get distinct IDs).
  @Test
  public void
      processRawResponses_streamingFunctionCallsAndStop_partialAndFinalShareFunctionCallId() {
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

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(Gemini.processRawResponses(rawResponses).blockingIterable());

    // 3 responses: partial(tool1), partial(tool2), final(tool1+tool2).
    assertThat(responses).hasSize(3);

    LlmResponse partial1 = responses.get(0);
    LlmResponse partial2 = responses.get(1);
    LlmResponse finalAgg = responses.get(2);

    String partial1Id = functionCallId(partial1, 0);
    String partial2Id = functionCallId(partial2, 0);
    String final1Id = functionCallId(finalAgg, 0);
    String final2Id = functionCallId(finalAgg, 1);

    // Tool1's ID matches between its partial event and its position in the final aggregated event.
    assertThat(partial1Id).isEqualTo(final1Id);
    // Tool2's ID matches between its partial event and its position in the final aggregated event.
    assertThat(partial2Id).isEqualTo(final2Id);
    // The two distinct calls have distinct IDs.
    assertThat(partial1Id).isNotEqualTo(partial2Id);
  }

  // Mirrors ADK Python's test_non_streaming_fc_generates_id_when_empty: a function call without an
  // ID gets a client-side "adk-"-prefixed ID (the prefix lets downstream code strip client IDs
  // before replaying to the model), shared by the partial and final events.
  @Test
  public void processRawResponses_functionCallWithoutId_generatesAdkPrefixedId() {
    GenerateContentResponse fcWithStop =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(Part.fromFunctionCall("my_tool", ImmutableMap.of("x", "1")))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(fcWithStop)).blockingIterable());

    // partial(my_tool) + final(my_tool).
    assertThat(responses).hasSize(2);
    String partialId = functionCallId(responses.get(0), 0);
    String finalId = functionCallId(responses.get(1), 0);
    assertThat(partialId).startsWith("adk-");
    assertThat(finalId).startsWith("adk-");
    assertThat(partialId).isEqualTo(finalId);
    // A complete (non-streaming) call keeps its arguments verbatim in the final event.
    FunctionCall finalCall =
        Iterables.getLast(responses).content().get().parts().get().get(0).functionCall().get();
    assertThat(finalCall.args().get()).containsExactly("x", "1");
  }

  // Mirrors ADK Python's streaming_utils test_non_streaming_fc_preserves_llm_assigned_id: when the
  // model itself supplies a function-call ID, the aggregator must preserve it (rather than
  // overwriting it with a generated "adk-" ID) in both the partial and final events.
  @Test
  public void processRawResponses_functionCallWithModelProvidedId_preservesId() {
    Part fcWithId =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .id("model-assigned-id")
                    .name("my_tool")
                    .args(ImmutableMap.of("x", "1"))
                    .build())
            .build();
    GenerateContentResponse fcWithStop =
        toResponse(
            Candidate.builder()
                .content(Content.builder().parts(fcWithId).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(fcWithStop)).blockingIterable());

    // partial(my_tool) + final(my_tool), both keeping the model-supplied ID.
    assertThat(responses).hasSize(2);
    assertThat(functionCallId(responses.get(0), 0)).isEqualTo("model-assigned-id");
    assertThat(functionCallId(responses.get(1), 0)).isEqualTo("model-assigned-id");
  }

  // Mirrors ADK Python's streaming_utils streamed-function-call handling: when the model streams a
  // single function call across chunks via partialArgs/willContinue, the arguments are accumulated
  // (string chunks concatenated by JSONPath) and emitted as ONE complete call in the final
  // aggregated response, rather than one (incomplete) call per chunk.
  @Test
  public void processRawResponses_streamingFunctionCallArgs_mergesIntoSingleFinalCall() {
    GenerateContentResponse chunk1 =
        toResponse(
            functionCallPart(FunctionCall.builder().name("getWeather").willContinue(true).build()));
    GenerateContentResponse chunk2 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.city").stringValue("Kra").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse chunk3 =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(
                            functionCallPart(
                                FunctionCall.builder()
                                    .partialArgs(
                                        PartialArg.builder()
                                            .jsonPath("$.city")
                                            .stringValue("kow")
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(chunk1, chunk2, chunk3)).blockingIterable());

    // The final aggregated response carries exactly one complete getWeather(city="Krakow") call.
    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.partial().orElse(false)).isFalse();
    assertThat(finalResponse.content().get().parts().get()).hasSize(1);
    FunctionCall finalCall =
        finalResponse.content().get().parts().get().get(0).functionCall().get();
    assertThat(finalCall.name()).hasValue("getWeather");
    assertThat(finalCall.args().get()).containsExactly("city", "Krakow");
    // The call's ID (generated on the first chunk) is reused on the final event.
    assertThat(finalCall.id()).hasValue(functionCallId(responses.get(0), 0));
  }

  // Streamed function-call arguments may target nested JSONPaths and non-string values; the
  // aggregator must build the nested structure, mirroring ADK Python's _set_value_by_json_path.
  @Test
  public void processRawResponses_streamingFunctionCallArgs_buildsNestedArgs() {
    GenerateContentResponse chunk1 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .name("book")
                    .partialArgs(
                        PartialArg.builder()
                            .jsonPath("$.location.city")
                            .stringValue("Paris")
                            .build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse chunk2 =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(
                            functionCallPart(
                                FunctionCall.builder()
                                    .partialArgs(
                                        PartialArg.builder()
                                            .jsonPath("$.guests")
                                            .numberValue(2.0)
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(chunk1, chunk2)).blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    FunctionCall finalCall =
        finalResponse.content().get().parts().get().get(0).functionCall().get();
    assertThat(finalCall.name()).hasValue("book");
    assertThat(finalCall.args().get())
        .containsExactly("location", ImmutableMap.of("city", "Paris"), "guests", 2.0);
  }

  // Two streamed function calls back-to-back must not bleed arguments into each other: a completed
  // call's accumulated-args state is reset before the next one starts.
  @Test
  public void processRawResponses_twoStreamingFunctionCalls_keepArgsSeparate() {
    GenerateContentResponse call1 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .name("first")
                    .partialArgs(PartialArg.builder().jsonPath("$.a").stringValue("1").build())
                    .willContinue(false)
                    .build()));
    GenerateContentResponse call2 =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(
                            functionCallPart(
                                FunctionCall.builder()
                                    .name("second")
                                    .partialArgs(
                                        PartialArg.builder()
                                            .jsonPath("$.b")
                                            .stringValue("2")
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(call1, call2)).blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    FunctionCall first = finalResponse.content().get().parts().get().get(0).functionCall().get();
    FunctionCall second = finalResponse.content().get().parts().get().get(1).functionCall().get();
    assertThat(first.name()).hasValue("first");
    assertThat(first.args().get()).containsExactly("a", "1");
    assertThat(second.name()).hasValue("second");
    assertThat(second.args().get()).containsExactly("b", "2");
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
  public void processRawResponses_finishReasonNotStop_emitsFinalWithErrorCode() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"),
            toResponseWithText(" world", FinishReason.Known.MAX_TOKENS));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    // Mirrors ADK Python: a non-STOP finish still yields the aggregated final response, with the
    // finish reason surfaced as an error code.
    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponse(" world"),
        isFinalTextResponseWithErrorCode("Hello world", FinishReason.Known.MAX_TOKENS));
  }

  @Test
  public void
      processRawResponses_finishReasonNotStopWithMessage_finalResponseIncludesErrorMessage() {
    GenerateContentResponse truncatedResponse =
        GenerateContentResponse.builder()
            .candidates(
                Candidate.builder()
                    .content(Content.builder().parts(Part.fromText(" world")).build())
                    .finishReason(new FinishReason(FinishReason.Known.MAX_TOKENS))
                    .finishMessage("Output truncated due to token limit.")
                    .build())
            .build();
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Hello"), truncatedResponse);

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    // A non-STOP finish surfaces the candidate's finishMessage as the response errorMessage.
    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponse(" world"),
        isFinalTextResponseWithErrorCodeAndMessage(
            "Hello world", FinishReason.Known.MAX_TOKENS, "Output truncated due to token limit."));
  }

  @Test
  public void processRawResponses_textThenEmpty_emitsPartialTextThenFullText() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Thinking..."), GenerateContentResponse.builder().build());

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isPartialTextResponse("Thinking..."), isFinalTextResponse("Thinking..."));
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
        isPartialTextResponseWithUsageMetadata(" world", metadata2),
        isFinalTextResponseWithUsageMetadata("Hello world", metadata2));
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
                                    .thoughtSignature("sig".getBytes(UTF_8))
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
                                    .thoughtSignature("sig".getBytes(UTF_8))
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
                                    .thoughtSignature("sig".getBytes(UTF_8))
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
                                    .thoughtSignature("sig".getBytes(UTF_8))
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

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

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

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

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

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    // The trailing empty-text chunk adds no empty response; the function call is still aggregated
    // into a final response even without a finish reason.
    assertLlmResponses(
        llmResponses,
        isPartialFunctionCallResponse("test_function"),
        isFinalAggregatedFunctionCallResponse("test_function"));
  }

  @Test
  public void textThenFunctionCallThenEmptyTextWithStop_emitsTextThenFunctionCalls() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Thinking..."),
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isPartialFunctionCallResponse("test_function"),
        isFinalTextAndFunctionCallResponseWithNoUsageMetadata("Thinking...", "test_function"));
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

  /** Returns the function-call ID of the part at {@code partIndex} in the response's content. */
  private static String functionCallId(LlmResponse response, int partIndex) {
    return response
        .content()
        .flatMap(Content::parts)
        .map(parts -> parts.get(partIndex))
        .flatMap(Part::functionCall)
        .flatMap(FunctionCall::id)
        .orElseThrow();
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

  private static Predicate<LlmResponse> isFinalTextResponseWithErrorCode(
      String expectedText, FinishReason.Known expectedErrorCode) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.errorCode().map(FinishReason::knownEnum)).hasValue(expectedErrorCode);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextResponseWithErrorCodeAndMessage(
      String expectedText, FinishReason.Known expectedErrorCode, String expectedErrorMessage) {
    return response -> {
      assertThat(response.partial().orElse(false)).isFalse();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.errorCode().map(FinishReason::knownEnum)).hasValue(expectedErrorCode);
      assertThat(response.errorMessage()).hasValue(expectedErrorMessage);
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
          .isEqualTo(expectedSignature.getBytes(UTF_8));

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
          .isEqualTo(expectedSignature.getBytes(UTF_8));
      assertThat(response.content().get().parts().get().get(1).text()).hasValue(expectedText);
      assertThat(response.content().get().parts().get().get(1).thought()).hasValue(false);
      // The signature belongs only to the thought part; it must not leak onto the following text
      // part (the aggregator resets the buffered signature after each flush).
      assertThat(response.content().get().parts().get().get(1).thoughtSignature()).isEmpty();
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
            .isEqualTo(expectedSignature.getBytes(UTF_8));
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

  private static Part functionCallPart(FunctionCall functionCall) {
    return Part.builder().functionCall(functionCall).build();
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
