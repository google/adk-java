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
import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolResponse;
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

  // The last partialArgs chunk keeps willContinue=true; completion arrives on a separate empty
  // willContinue=false marker, then trailing text follows. The marker must flush the call so it
  // precedes the text. Without handling the marker, close() flushes the call after the text,
  // reversing their order (a single call alone would be masked by that end-of-stream flush).
  @Test
  public void processRawResponses_streamedCallEndedByEmptyMarker_flushesCallBeforeTrailingText() {
    GenerateContentResponse name =
        toResponse(
            functionCallPart(FunctionCall.builder().name("bookFlight").willContinue(true).build()));
    GenerateContentResponse origin1 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.origin").stringValue("Krak").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse origin2 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.origin").stringValue("ow").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse destination =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder()
                            .jsonPath("$.destination")
                            .stringValue("Warsaw")
                            .build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse endMarker =
        toResponse(functionCallPart(FunctionCall.builder().willContinue(false).build()));
    GenerateContentResponse trailingText = toResponseWithText("Booked.", FinishReason.Known.STOP);

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(
                    Flowable.just(name, origin1, origin2, destination, endMarker, trailingText))
                .blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    FunctionCall finalCall =
        finalResponse.content().get().parts().get().get(0).functionCall().get();
    assertThat(finalCall.name()).hasValue("bookFlight");
    assertThat(finalCall.args().get()).containsExactly("origin", "Krakow", "destination", "Warsaw");
    assertThat(finalResponse.content().get().parts().get().get(1).text()).hasValue("Booked.");
  }

  // Two multi-arg streamed calls each ended by an empty willContinue=false marker must not drop the
  // first call nor bleed its args into the second.
  @Test
  public void processRawResponses_twoStreamedCallsEndedByEmptyMarkers_keepArgsSeparate() {
    GenerateContentResponse call1Name =
        toResponse(
            functionCallPart(
                FunctionCall.builder().name("getTemperature").willContinue(true).build()));
    GenerateContentResponse call1City =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.city").stringValue("Krakow").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse call1Unit =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.unit").stringValue("C").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse marker1 =
        toResponse(functionCallPart(FunctionCall.builder().willContinue(false).build()));
    GenerateContentResponse call2Name =
        toResponse(
            functionCallPart(
                FunctionCall.builder().name("getCondition").willContinue(true).build()));
    GenerateContentResponse call2City =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.city").stringValue("Warsaw").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse call2Unit =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.unit").stringValue("F").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse marker2 =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(functionCallPart(FunctionCall.builder().willContinue(false).build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(
                    Flowable.just(
                        call1Name, call1City, call1Unit, marker1, call2Name, call2City, call2Unit,
                        marker2))
                .blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    FunctionCall first = finalResponse.content().get().parts().get().get(0).functionCall().get();
    FunctionCall second = finalResponse.content().get().parts().get().get(1).functionCall().get();
    assertThat(first.name()).hasValue("getTemperature");
    assertThat(first.args().get()).containsExactly("city", "Krakow", "unit", "C");
    assertThat(second.name()).hasValue("getCondition");
    assertThat(second.args().get()).containsExactly("city", "Warsaw", "unit", "F");
  }

  // Safety guard for non-conforming output: a streamed call still in progress (the model should
  // have terminated it with willContinue=false) is followed by a complete non-streaming call. The
  // in-progress call is flushed before appending, so neither is dropped nor merged.
  @Test
  public void processRawResponses_streamedCallFollowedByCompleteCall_flushesInProgressFirst() {
    GenerateContentResponse streamedName =
        toResponse(
            functionCallPart(
                FunctionCall.builder().name("stream_call").willContinue(true).build()));
    GenerateContentResponse streamedArg =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.a").stringValue("1").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse completeCall =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(
                            functionCallPart(
                                FunctionCall.builder()
                                    .name("plain_call")
                                    .args(ImmutableMap.of("b", "2"))
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(streamedName, streamedArg, completeCall))
                .blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    FunctionCall first = finalResponse.content().get().parts().get().get(0).functionCall().get();
    FunctionCall second = finalResponse.content().get().parts().get().get(1).functionCall().get();
    assertThat(first.name()).hasValue("stream_call");
    assertThat(first.args().get()).containsExactly("a", "1");
    assertThat(second.name()).hasValue("plain_call");
    assertThat(second.args().get()).containsExactly("b", "2");
  }

  // A stray nameless willContinue=false marker with no call in progress must be a safe no-op (the
  // currentFcName != null half of the guard): it must not add a function call nor split the
  // surrounding text. Without that half it would be treated as a streamed part and prematurely
  // flush the text buffer, splitting "Hello world" into two parts.
  @Test
  public void processRawResponses_strayNamelessMarker_isNoOpAndDoesNotSplitText() {
    GenerateContentResponse hello = toResponseWithText("Hello ");
    GenerateContentResponse strayMarker =
        toResponse(functionCallPart(FunctionCall.builder().willContinue(false).build()));
    GenerateContentResponse world = toResponseWithText("world", FinishReason.Known.STOP);

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(hello, strayMarker, world))
                .blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(1);
    assertThat(finalResponse.content().get().parts().get().get(0).text()).hasValue("Hello world");
    assertThat(finalResponse.content().get().parts().get().get(0).functionCall()).isEmpty();
  }

  @Test
  public void processRawResponses_imageOnlyWithStop_emitsFinalImagePart() {
    Part imagePart = Part.fromBytes(new byte[] {1, 2, 3}, "image/png");
    GenerateContentResponse imageWithStop =
        toResponse(
            Candidate.builder()
                .content(Content.builder().role("model").parts(imagePart).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(imageWithStop)).blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(1);
    assertThat(finalResponse.content().get().parts().get().get(0).inlineData()).isPresent();
  }

  // Image-generation models return image bytes as an inline-data part, often alongside text.
  // Regression test: the aggregated response must retain the image, not just the text.
  @Test
  public void processRawResponses_textThenImageWithStop_finalKeepsTextAndImage() {
    Part imagePart = Part.fromBytes(new byte[] {1, 2, 3}, "image/png");
    GenerateContentResponse textChunk = toResponseWithText("Here is your image:");
    GenerateContentResponse imageWithStop =
        toResponse(
            Candidate.builder()
                .content(Content.builder().role("model").parts(imagePart).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    ImmutableList<LlmResponse> responses =
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(textChunk, imageWithStop)).blockingIterable());

    LlmResponse finalResponse = Iterables.getLast(responses);
    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    assertThat(finalResponse.content().get().parts().get().get(0).text())
        .hasValue("Here is your image:");
    assertThat(finalResponse.content().get().parts().get().get(1).inlineData()).isPresent();
  }

  // The aggregator must pass through any non-text, non-function-call part, not just an allowlist.
  // These guard the part types that were being silently dropped: server-side tool calls/responses
  // and function responses. Each uses a text-then-part sequence so the part must survive the final
  // aggregation (a lone part would otherwise slip through via the empty-sequence fallback).
  @Test
  public void processRawResponses_textThenToolCall_finalKeepsBoth() {
    Part toolCallPart =
        Part.builder()
            .toolCall(ToolCall.builder().id("tc-1").args(ImmutableMap.of("q", "weather")).build())
            .build();

    LlmResponse finalResponse = aggregateTextThenPart(toolCallPart);

    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    assertThat(finalResponse.content().get().parts().get().get(1).toolCall()).isPresent();
  }

  @Test
  public void processRawResponses_textThenToolResponse_finalKeepsBoth() {
    Part toolResponsePart =
        Part.builder()
            .toolResponse(
                ToolResponse.builder().id("tc-1").response(ImmutableMap.of("ok", true)).build())
            .build();

    LlmResponse finalResponse = aggregateTextThenPart(toolResponsePart);

    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    assertThat(finalResponse.content().get().parts().get().get(1).toolResponse()).isPresent();
  }

  @Test
  public void processRawResponses_textThenFunctionResponse_finalKeepsBoth() {
    Part functionResponsePart = Part.fromFunctionResponse("my_tool", ImmutableMap.of("result", 42));

    LlmResponse finalResponse = aggregateTextThenPart(functionResponsePart);

    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    assertThat(finalResponse.content().get().parts().get().get(1).functionResponse()).isPresent();
  }

  // Per the Gemini docs, a data part (e.g. inlineData) can carry a thoughtSignature with the
  // thought
  // flag unset (multi-turn image editing). The part must be kept verbatim, and its signature must
  // not leak onto the preceding text part (the docs forbid putting a signature on a part that did
  // not originally carry one).
  @Test
  public void processRawResponses_textThenDataPartWithSignature_keepsSignatureOnDataPartOnly() {
    Part imageWithSignature =
        Part.builder()
            .inlineData(Blob.builder().mimeType("image/png").data(new byte[] {1, 2, 3}).build())
            .thoughtSignature("sig".getBytes(UTF_8))
            .build();

    LlmResponse finalResponse = aggregateTextThenPart(imageWithSignature);

    assertThat(finalResponse.content().get().parts().get()).hasSize(2);
    assertThat(finalResponse.content().get().parts().get().get(0).text())
        .hasValue("Working on it:");
    assertThat(finalResponse.content().get().parts().get().get(0).thoughtSignature()).isEmpty();
    assertThat(finalResponse.content().get().parts().get().get(1).inlineData()).isPresent();
    assertThat(finalResponse.content().get().parts().get().get(1).thoughtSignature()).isPresent();
  }

  private LlmResponse aggregateTextThenPart(Part part) {
    GenerateContentResponse textChunk = toResponseWithText("Working on it:");
    GenerateContentResponse partWithStop =
        toResponse(
            Candidate.builder()
                .content(Content.builder().role("model").parts(part).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());
    return Iterables.getLast(
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.just(textChunk, partWithStop)).blockingIterable()));
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
  public void processRawResponses_thoughtThenSignatureAndStop_keepsSignatureOnItsOwnPart() {
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
        isPartialSignatureResponse("sig"),
        response -> {
          ImmutableList<Part> parts = ImmutableList.copyOf(response.content().get().parts().get());
          assertThat(parts).hasSize(2);
          assertThat(parts.get(0).text()).hasValue("Thinking");
          assertThat(parts.get(0).thoughtSignature()).isEmpty();
          assertThat(parts.get(1).thoughtSignature()).hasValue("sig".getBytes(UTF_8));
          assertThat(response.usageMetadata()).hasValue(metadata2);
          return true;
        });
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
      processRawResponses_thoughtThenFunctionCallThenSignature_keepsSignatureOnItsOwnPart() {
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
        isPartialSignatureResponse("sig"),
        response -> {
          ImmutableList<Part> parts = ImmutableList.copyOf(response.content().get().parts().get());
          assertThat(parts).hasSize(3);
          assertThat(parts.get(0).text()).hasValue("Thinking");
          assertThat(parts.get(1).functionCall().get().name()).hasValue("my_tool");
          assertThat(parts.get(1).thoughtSignature()).isEmpty();
          assertThat(parts.get(2).thoughtSignature()).hasValue("sig".getBytes(UTF_8));
          assertThat(response.usageMetadata()).hasValue(metadata2);
          return true;
        });
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
        isPartialSignatureResponse("sig"),
        isFinalThoughtResponseWithUsageMetadataAndSignature("", metadata, "sig"));
  }

  // Consecutive text chunks are merged into a single part the aggregator builds from scratch, so a
  // thought signature the chunks carried is lost unless it is copied across. The model expects its
  // signature back verbatim; without it, it redoes the reasoning the signature stood for. Mirrors
  // ADK Python's TestStreamingThoughtSignature.
  @Test
  public void processRawResponses_signatureOnMergedText_isPreserved() {
    GenerateContentResponse chunk1 = toResponseWithTextAndSignature("At minute 5 ", "text-sig");
    GenerateContentResponse chunk2 =
        toResponseWithText("the presenter speaks.", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("At minute 5 the presenter speaks.");
    assertThat(parts.get(0).thoughtSignature()).hasValue("text-sig".getBytes(UTF_8));
  }

  // The signature can land on any chunk of the run, not just the first.
  @Test
  public void processRawResponses_signatureOnLaterTextChunk_isPreserved() {
    GenerateContentResponse chunk1 = toResponseWithText("At minute 5 ");
    GenerateContentResponse chunk2 = toResponseWithTextAndSignature("the presenter ", "late-sig");
    GenerateContentResponse chunk3 = toResponseWithText("speaks.", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("At minute 5 the presenter speaks.");
    assertThat(parts.get(0).thoughtSignature()).hasValue("late-sig".getBytes(UTF_8));
  }

  // A merged part carries one signature; the run keeps the first it saw, as ADK Python does.
  @Test
  public void processRawResponses_multipleSignaturesInOneRun_keepsTheFirst() {
    GenerateContentResponse chunk1 = toResponseWithTextAndSignature("At minute 5 ", "first-sig");
    GenerateContentResponse chunk2 = toResponseWithTextAndSignature("the presenter ", "second-sig");
    GenerateContentResponse chunk3 =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(Part.fromFunctionCall("done", ImmutableMap.of()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).hasValue("first-sig".getBytes(UTF_8));
  }

  // A thought run and an answer run flush separately and must not swap signatures: the answer's
  // signature arrives on the chunk that triggers the flush of the thought.
  @Test
  public void processRawResponses_thoughtAndAnswerRuns_keepTheirOwnSignatures() {
    GenerateContentResponse chunk1 =
        toResponse(
            Part.builder()
                .text("Let me check.")
                .thought(true)
                .thoughtSignature("thought-sig".getBytes(UTF_8))
                .build());
    GenerateContentResponse chunk2 =
        toResponseWithTextAndSignature("It is a dog.", "answer-sig", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(parts.get(0).thoughtSignature()).hasValue("thought-sig".getBytes(UTF_8));
    assertThat(parts.get(1).thoughtSignature()).hasValue("answer-sig".getBytes(UTF_8));
  }

  // A signature-only thought part keeps its signature on itself, as ADK Python does, rather than
  // having it relocated onto the text around it.
  @Test
  public void processRawResponses_standaloneSignatureMidTextRun_keepsItsOwnSignature() {
    GenerateContentResponse chunk1 = toResponseWithText("At minute 5 ");
    GenerateContentResponse chunk2 =
        toResponse(
            Part.builder().thought(true).thoughtSignature("carried-sig".getBytes(UTF_8)).build());
    GenerateContentResponse chunk3 =
        toResponseWithText("the presenter speaks.", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).hasValue("At minute 5 ");
    assertThat(parts.get(0).thoughtSignature()).isEmpty();
    assertThat(parts.get(1).thoughtSignature()).hasValue("carried-sig".getBytes(UTF_8));
    assertThat(parts.get(2).text()).hasValue("the presenter speaks.");
    assertThat(parts.get(2).thoughtSignature()).isEmpty();
  }

  // A text chunk arriving mid-stream of a function call must not take the call's signature with it:
  // the two runs flush together and each keeps its own.
  @Test
  public void processRawResponses_textInterleavedWithStreamedCall_keepsBothSignatures() {
    GenerateContentResponse chunk1 =
        toResponse(
            Part.builder()
                .functionCall(
                    FunctionCall.builder()
                        .name("search")
                        .partialArgs(
                            PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
                        .willContinue(true)
                        .build())
                .thoughtSignature("fc-sig".getBytes(UTF_8))
                .build());
    GenerateContentResponse chunk2 = toResponseWithTextAndSignature("Working on it.", "text-sig");
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
                                            .jsonPath("$.q")
                                            .stringValue("lo")
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("Working on it.");
    assertThat(parts.get(0).thoughtSignature()).hasValue("text-sig".getBytes(UTF_8));
    assertThat(parts.get(1).functionCall().get().name()).hasValue("search");
    assertThat(parts.get(1).thoughtSignature()).hasValue("fc-sig".getBytes(UTF_8));
  }

  // A signature-only part with no text run open must not be dropped, and the streamed call that
  // follows must not inherit its signature.
  @Test
  public void processRawResponses_standaloneSignatureThenStreamedCall_keepsItOnItsOwnPart() {
    GenerateContentResponse chunk1 =
        toResponse(Part.builder().thought(true).thoughtSignature("sig-A".getBytes(UTF_8)).build());
    GenerateContentResponse chunk2 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .name("search")
                    .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
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
                                            .jsonPath("$.q")
                                            .stringValue("lo")
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).hasValue("sig-A".getBytes(UTF_8));
    assertThat(parts.get(1).functionCall().get().name()).hasValue("search");
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
  }

  // Two runs inside the final chunk each keep their own signature: the final-chunk re-attach must
  // not stamp the first run's signature over the second's.
  @Test
  public void processRawResponses_thoughtAndAnswerInFinalChunk_keepTheirOwnSignatures() {
    Part thought =
        Part.builder()
            .text("Let me check.")
            .thought(true)
            .thoughtSignature("thought-sig".getBytes(UTF_8))
            .build();
    Part answer =
        Part.builder().text("It is a dog.").thoughtSignature("answer-sig".getBytes(UTF_8)).build();
    GenerateContentResponse chunk =
        toResponse(
            Candidate.builder()
                .content(Content.builder().parts(thought, answer).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).hasValue("thought-sig".getBytes(UTF_8));
    assertThat(parts.get(1).thoughtSignature()).hasValue("answer-sig".getBytes(UTF_8));
  }

  // A carrier between two text runs ends the first and is emitted on its own; neither run's
  // signature moves, so nothing is attributed to a part the model did not sign.
  @Test
  public void processRawResponses_carrierBetweenTwoRuns_isEmittedOnItsOwn() {
    GenerateContentResponse chunk1 = toResponseWithTextAndSignature("Hello", "sig-A");
    GenerateContentResponse chunk2 =
        toResponse(Part.builder().thought(true).thoughtSignature("sig-B".getBytes(UTF_8)).build());
    GenerateContentResponse chunk3 = toResponseWithText(" world", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).hasValue("Hello");
    assertThat(parts.get(0).thoughtSignature()).hasValue("sig-A".getBytes(UTF_8));
    assertThat(parts.get(1).thoughtSignature()).hasValue("sig-B".getBytes(UTF_8));
    assertThat(parts.get(2).text()).hasValue(" world");
    assertThat(parts.get(2).thoughtSignature()).isEmpty();
  }

  // An empty signature must not occupy the run's slot and block the real one behind it.
  @Test
  public void processRawResponses_emptySignatureThenRealOne_keepsTheRealOne() {
    GenerateContentResponse chunk1 = toResponseWithTextAndSignature("Hel", "");
    GenerateContentResponse chunk2 =
        toResponseWithTextAndSignature("lo", "real-sig", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).thoughtSignature()).hasValue("real-sig".getBytes(UTF_8));
  }

  // A signature the aggregator already placed must not be handed out again by the final-chunk
  // re-attach when the last part happens to be unsigned.
  @Test
  public void processRawResponses_signedThenUnsignedRunInFinalChunk_doesNotDuplicate() {
    Part signed = Part.builder().text("A").thoughtSignature("sig-1".getBytes(UTF_8)).build();
    Part unsigned = Part.builder().text("B").thought(true).build();
    GenerateContentResponse chunk =
        toResponse(
            Candidate.builder()
                .content(Content.builder().parts(signed, unsigned).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).hasValue("sig-1".getBytes(UTF_8));
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
  }

  // A carrier's signature stays on the carrier: neither the call after it nor the text after that
  // may end up carrying the same bytes.
  @Test
  public void processRawResponses_carrierThenCallThenText_doesNotDuplicate() {
    GenerateContentResponse chunk1 =
        toResponse(Part.builder().thought(true).thoughtSignature("sig-A".getBytes(UTF_8)).build());
    GenerateContentResponse chunk2 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .name("search")
                    .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse chunk3 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("lo").build())
                    .willContinue(false)
                    .build()));
    GenerateContentResponse chunk4 = toResponseWithText("Done.", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3, chunk4);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thoughtSignature()).hasValue("sig-A".getBytes(UTF_8));
    assertThat(parts.get(1).functionCall()).isPresent();
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
    assertThat(parts.get(2).text()).hasValue("Done.");
    assertThat(parts.get(2).thoughtSignature()).isEmpty();
  }

  // An empty text part that also carries payload must survive: Optional.isEmpty() is false for
  // text="", so such a part misses the catch-all unless the emptiness is tested on the value.
  @Test
  public void processRawResponses_emptyTextPartWithInlineData_isKept() {
    GenerateContentResponse chunk1 = toResponseWithText("Here.");
    GenerateContentResponse chunk2 =
        toResponse(
            Part.builder()
                .text("")
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[] {1, 2}).build())
                .build());
    GenerateContentResponse chunk3 = toResponseWithText("", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(1).inlineData()).isPresent();
  }

  // A signature appears on exactly one part: the one the model put it on. Neither the text run
  // after the carrier nor the call after that may emit the same bytes.
  @Test
  public void processRawResponses_carriedSignature_isNotEmittedOnTwoParts() {
    GenerateContentResponse chunk1 =
        toResponse(Part.builder().thought(true).thoughtSignature("sig-A".getBytes(UTF_8)).build());
    GenerateContentResponse chunk2 = toResponseWithText("Working on it.");
    GenerateContentResponse chunk3 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .name("search")
                    .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse chunk4 =
        toResponse(
            Candidate.builder()
                .content(
                    Content.builder()
                        .parts(
                            functionCallPart(
                                FunctionCall.builder()
                                    .partialArgs(
                                        PartialArg.builder()
                                            .jsonPath("$.q")
                                            .stringValue("lo")
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3, chunk4);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thoughtSignature()).hasValue("sig-A".getBytes(UTF_8));
    assertThat(parts.get(1).text()).hasValue("Working on it.");
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
    assertThat(parts.get(2).functionCall()).isPresent();
    assertThat(parts.get(2).thoughtSignature()).isEmpty();
  }

  // A streamed call that carries its own signature keeps it, and the carrier before it keeps its
  // own: two signatures in, two signatures out, neither displaced.
  @Test
  public void processRawResponses_streamedCallKeepsItsOwnSignatureAfterACarrier() {
    GenerateContentResponse chunk1 =
        toResponse(Part.builder().thought(true).thoughtSignature("sig-A".getBytes(UTF_8)).build());
    GenerateContentResponse chunk2 =
        toResponse(
            Part.builder()
                .functionCall(
                    FunctionCall.builder()
                        .name("search")
                        .partialArgs(
                            PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
                        .willContinue(true)
                        .build())
                .thoughtSignature("fc-B".getBytes(UTF_8))
                .build());
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
                                            .jsonPath("$.q")
                                            .stringValue("lo")
                                            .build())
                                    .willContinue(false)
                                    .build()))
                        .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).hasValue("sig-A".getBytes(UTF_8));
    assertThat(parts.get(1).thoughtSignature()).hasValue("fc-B".getBytes(UTF_8));
  }

  // Server-side media tools return signatures on parts holding nothing else. Such a part must
  // survive as its own part rather than being folded into the surrounding text.
  @Test
  public void processRawResponses_contentFreeSignaturePart_isKept() {
    GenerateContentResponse chunk1 = toResponseWithText("At minute 5 the presenter speaks.");
    GenerateContentResponse chunk2 =
        toResponse(Part.builder().thoughtSignature("call-context".getBytes(UTF_8)).build());
    GenerateContentResponse chunk3 = toResponseWithText("", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).isEmpty();
    assertThat(parts.get(1).thoughtSignature()).hasValue("call-context".getBytes(UTF_8));
  }

  // The other half of the rule above: an empty text part carrying nothing at all only marks the end
  // of a Gemini 3 stream, so it must not reach the caller as a part of its own.
  @Test
  public void processRawResponses_bareEmptyTextPart_isDropped() {
    GenerateContentResponse chunk1 = toResponseWithText("Let me check.");
    GenerateContentResponse chunk2 = toResponseWithText("", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("Let me check.");
  }

  // The wire shape the standard Gemini API actually sends for the same thing: the signature rides
  // on a part whose text is present but empty, which Optional.isEmpty() does not recognise.
  @Test
  public void processRawResponses_emptyTextSignaturePart_isKept() {
    GenerateContentResponse chunk1 = toResponseWithText("The answer is 42.");
    GenerateContentResponse chunk2 =
        toResponse(
            Part.builder().text("").thoughtSignature("trailing-sig".getBytes(UTF_8)).build());
    GenerateContentResponse chunk3 = toResponseWithText("", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature()).isEmpty();
    assertThat(parts.get(1).thoughtSignature()).hasValue("trailing-sig".getBytes(UTF_8));
  }

  // Three parts, two signatures, and no relocation: the carrier keeps its own and the signed text
  // run behind the call keeps its own.
  @Test
  public void processRawResponses_carrierThenCallThenSignedText_keepsEachSignatureInPlace() {
    GenerateContentResponse chunk1 =
        toResponse(Part.builder().thought(true).thoughtSignature("carry".getBytes(UTF_8)).build());
    GenerateContentResponse chunk2 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .name("search")
                    .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
                    .willContinue(true)
                    .build()));
    GenerateContentResponse chunk3 =
        toResponse(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("lo").build())
                    .willContinue(false)
                    .build()));
    GenerateContentResponse chunk4 =
        toResponseWithTextAndSignature("Here you go.", "text-B", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3, chunk4);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thoughtSignature()).hasValue("carry".getBytes(UTF_8));
    assertThat(parts.get(1).functionCall()).isPresent();
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
    assertThat(parts.get(2).thoughtSignature()).hasValue("text-B".getBytes(UTF_8));
  }

  // Same invariant with a complete rather than a streamed call. The model did not sign the call,
  // so the call goes out unsigned rather than inheriting the thought's signature.
  @Test
  public void processRawResponses_carrierThenCompleteCall_leavesTheCallUnsigned() {
    GenerateContentResponse chunk1 =
        toResponse(Part.builder().thought(true).thoughtSignature("carry".getBytes(UTF_8)).build());
    GenerateContentResponse chunk2 =
        toResponse(functionCallPart(FunctionCall.builder().name("search").id("fc-1").build()));
    GenerateContentResponse chunk3 =
        toResponseWithTextAndSignature("Here you go.", "text-B", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thoughtSignature()).hasValue("carry".getBytes(UTF_8));
    assertThat(parts.get(1).functionCall()).isPresent();
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
    assertThat(parts.get(2).thoughtSignature()).hasValue("text-B".getBytes(UTF_8));
  }

  // A thought-marked server-side tool call carries payload the model has to see again, so only the
  // marker and the signature may be folded away - the part itself has to reach the session intact.
  @Test
  public void processRawResponses_thoughtMarkedServerSideToolCall_survivesTheStream() {
    Part toolCallPart =
        Part.builder()
            .thought(true)
            .toolCall(ToolCall.builder().id("tc1").build())
            .thoughtSignature("tool-sig".getBytes(UTF_8))
            .build();
    GenerateContentResponse chunk1 = toResponse(toolCallPart);
    GenerateContentResponse chunk2 = toResponseWithText("Found it.", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).isEqualTo(toolCallPart);
    assertThat(parts.get(1).text()).hasValue("Found it.");
  }

  // A zero-length signature must not occupy the streamed call's own slot and block the real one
  // behind it, the same rule the text run's slot follows.
  @Test
  public void processRawResponses_emptySignatureThenRealOneOnAStreamedCall_keepsTheRealOne() {
    GenerateContentResponse chunk1 =
        toResponse(
            Part.builder()
                .functionCall(
                    FunctionCall.builder()
                        .name("search")
                        .partialArgs(
                            PartialArg.builder().jsonPath("$.q").stringValue("hel").build())
                        .willContinue(true)
                        .build())
                .thoughtSignature(new byte[0])
                .build());
    GenerateContentResponse chunk2 =
        toResponse(
            Part.builder()
                .functionCall(
                    FunctionCall.builder()
                        .partialArgs(PartialArg.builder().jsonPath("$.q").stringValue("lo").build())
                        .willContinue(false)
                        .build())
                .thoughtSignature("real-sig".getBytes(UTF_8))
                .build());
    // The stream ends unsigned, so the final-chunk re-attach cannot supply the signature and the
    // assertion is about the call's own slot rather than a fallback filling the gap.
    GenerateContentResponse chunk3 = toResponseWithText("", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).thoughtSignature()).hasValue("real-sig".getBytes(UTF_8));
  }

  // The stream terminator is recognised by shape, not by identity: one that also carries an
  // explicit thought marker is still nothing to keep.
  @Test
  public void processRawResponses_emptyTextPartWithExplicitThoughtFalse_isDropped() {
    GenerateContentResponse chunk1 = toResponseWithText("Let me check.");
    GenerateContentResponse chunk2 = toResponse(Part.builder().text("").thought(false).build());
    GenerateContentResponse chunk3 = toResponseWithText("", FinishReason.Known.STOP);

    LlmResponse finalResponse = aggregateFinalResponse(chunk1, chunk2, chunk3);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("Let me check.");
  }

  // A multi-part final chunk already carries each call's own signature. The re-attach reads part0
  // only, so it must not stamp the first call's signature onto the last one.
  @Test
  public void processRawResponses_multiCallFinalChunkSignedOnPart0_doesNotStampTheLastCall() {
    Part signedCall =
        Part.builder()
            .functionCall(FunctionCall.builder().name("get_weather").id("fc-0").build())
            .thoughtSignature("call-sig".getBytes(UTF_8))
            .build();
    Part secondCall =
        functionCallPart(FunctionCall.builder().name("get_weather").id("fc-1").build());
    Part thirdCall =
        functionCallPart(FunctionCall.builder().name("get_weather").id("fc-2").build());
    GenerateContentResponse chunk =
        toResponse(
            Candidate.builder()
                .content(Content.builder().parts(signedCall, secondCall, thirdCall).build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build());

    LlmResponse finalResponse = aggregateFinalResponse(chunk);

    ImmutableList<Part> parts = ImmutableList.copyOf(finalResponse.content().get().parts().get());
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thoughtSignature()).hasValue("call-sig".getBytes(UTF_8));
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
    assertThat(parts.get(2).thoughtSignature()).isEmpty();
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

  /** A partial chunk holding nothing but a thought marker and a signature. */
  private static Predicate<LlmResponse> isPartialSignatureResponse(String expectedSignature) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thoughtSignature))
          .hasValue(expectedSignature.getBytes(UTF_8));
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

  private GenerateContentResponse toResponseWithTextAndSignature(String text, String signature) {
    return toResponse(
        Part.builder().text(text).thoughtSignature(signature.getBytes(UTF_8)).build());
  }

  private GenerateContentResponse toResponseWithTextAndSignature(
      String text, String signature, FinishReason.Known finishReason) {
    Part part = Part.builder().text(text).thoughtSignature(signature.getBytes(UTF_8)).build();
    return toResponse(
        Candidate.builder()
            .content(Content.builder().parts(part).build())
            .finishReason(new FinishReason(finishReason))
            .build());
  }

  /** Runs the chunks through the aggregator and returns the final (non-partial) response. */
  private static LlmResponse aggregateFinalResponse(GenerateContentResponse... chunks) {
    return Iterables.getLast(
        ImmutableList.copyOf(
            Gemini.processRawResponses(Flowable.fromArray(chunks)).blockingIterable()));
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
