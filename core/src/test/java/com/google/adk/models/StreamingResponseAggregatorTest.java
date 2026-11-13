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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StreamingResponseAggregatorTest {

  private static final byte[] SIGNATURE_A = {1, 2};
  private static final byte[] SIGNATURE_B = {3, 4};
  private static final FinishReason STOP = new FinishReason(FinishReason.Known.STOP);

  @Test
  public void processResponse_textChunk_returnsChunkAsPartial() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    GenerateContentResponseUsageMetadata usage = usage(5);
    LlmResponse chunk =
        chunk(Part.fromText("Hello")).toBuilder().usageMetadata(usage).modelVersion("m").build();

    LlmResponse partial = aggregator.processResponse(chunk).get();

    assertThat(partial.partial()).hasValue(true);
    assertThat(partial.content().get().role()).hasValue("model");
    assertThat(partial.content().get().parts().get()).containsExactly(Part.fromText("Hello"));
    assertThat(partial.usageMetadata()).hasValue(usage);
    assertThat(partial.modelVersion()).hasValue("m");
  }

  @Test
  public void processResponse_chunkWithoutContent_returnsEmpty() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();

    assertThat(aggregator.processResponse(LlmResponse.builder().usageMetadata(usage(5)).build()))
        .isEmpty();
    assertThat(aggregator.hasAccumulatedContent()).isFalse();
  }

  @Test
  public void aggregate_textChunks_mergesIntoOneNonPartialPart() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(aggregator, chunk(Part.fromText("Hello")), chunk(Part.fromText(" world")));

    LlmResponse aggregated = aggregator.aggregate().get();

    Part merged = getOnlyElement(aggregated.content().get().parts().get());
    assertThat(merged.text()).hasValue("Hello world");
    assertThat(merged.thought()).hasValue(false);
    assertThat(aggregated.content().get().role()).hasValue("model");
    assertThat(aggregated.partial()).isEmpty();
  }

  @Test
  public void aggregate_thoughtThenText_keepsSeparateParts() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(thought("Let me ")),
        chunk(thought("think.")),
        chunk(Part.fromText("Answer.")));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("Let me think.");
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(parts.get(1).text()).hasValue("Answer.");
    assertThat(parts.get(1).thought()).hasValue(false);
  }

  @Test
  public void aggregate_textAroundOtherPart_keepsOrderWithoutMerging() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part image =
        Part.builder()
            .inlineData(Blob.builder().mimeType("image/png").data(new byte[] {7}).build())
            .build();
    process(
        aggregator,
        chunk(Part.fromText("Chart:")),
        chunk(image),
        chunk(Part.fromText("It goes up.")));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).hasValue("Chart:");
    assertThat(parts.get(1)).isEqualTo(image);
    assertThat(parts.get(2).text()).hasValue("It goes up.");
  }

  @Test
  public void aggregate_signaturesInOneTextRun_keepsTheFirst() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.fromText("Hello")),
        chunk(Part.builder().text(" world").thoughtSignature(SIGNATURE_A).build()),
        chunk(Part.builder().text("!").thoughtSignature(SIGNATURE_B).build()));

    Part merged = getOnlyElement(aggregatedParts(aggregator));

    assertThat(merged.text()).hasValue("Hello world!");
    assertThat(merged.thoughtSignature().get()).isEqualTo(SIGNATURE_A);
  }

  @Test
  public void aggregate_thoughtAndAnswerRuns_keepTheirOwnSignatures() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            Part.builder().text("Reasoning.").thought(true).thoughtSignature(SIGNATURE_A).build()),
        chunk(Part.builder().text("Answer.").thoughtSignature(SIGNATURE_B).build()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature().get()).isEqualTo(SIGNATURE_A);
    assertThat(parts.get(1).thoughtSignature().get()).isEqualTo(SIGNATURE_B);
  }

  @Test
  public void processResponse_functionCallWithoutId_sharesGeneratedIdWithAggregatedResponse() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();

    LlmResponse partial =
        aggregator
            .processResponse(
                chunk(Part.fromFunctionCall("get_weather", ImmutableMap.of("city", "Paris"))))
            .get();

    String partialId = onlyFunctionCall(partial).id().get();
    FunctionCall aggregated = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();
    assertThat(FunctionCallIds.isClientGeneratedFunctionCallId(partialId)).isTrue();
    assertThat(aggregated.id()).hasValue(partialId);
    assertThat(aggregated.name()).hasValue("get_weather");
    assertThat(aggregated.args()).hasValue(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void aggregate_functionCallWithModelId_keepsTheId() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder().id("call-1").name("tool").args(ImmutableMap.of()).build())));

    FunctionCall aggregated = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(aggregated.id()).hasValue("call-1");
  }

  @Test
  public void aggregate_textThenCompleteFunctionCall_keepsOrder() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.fromText("Calling the tool.")),
        chunk(Part.fromFunctionCall("tool", ImmutableMap.of())));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("Calling the tool.");
    assertThat(parts.get(1).functionCall().get().name()).hasValue("tool");
  }

  @Test
  public void aggregate_streamedFunctionCall_assemblesArgsFromPartialArgs() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    LlmResponse firstPartial =
        aggregator
            .processResponse(
                chunk(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder().name("create_ticket").willContinue(true).build())
                        .thoughtSignature(SIGNATURE_A)
                        .build()))
            .get();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder()
                            .jsonPath("$.title")
                            .stringValue("Printer is bro")
                            .build(),
                        PartialArg.builder()
                            .jsonPath("$.details.priority")
                            .numberValue(1.0)
                            .build())
                    .willContinue(true)
                    .build())),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.title").stringValue("ken").build())
                    .willContinue(true)
                    .build())),
        chunk(functionCallPart(FunctionCall.builder().willContinue(false).build())));

    Part callPart = getOnlyElement(aggregatedParts(aggregator));

    FunctionCall call = callPart.functionCall().get();
    assertThat(call.name()).hasValue("create_ticket");
    assertThat(call.id()).hasValue(onlyFunctionCall(firstPartial).id().get());
    assertThat(call.args())
        .hasValue(
            ImmutableMap.of(
                "title", "Printer is broken", "details", ImmutableMap.of("priority", 1.0)));
    assertThat(callPart.thoughtSignature().get()).isEqualTo(SIGNATURE_A);
  }

  @Test
  public void aggregate_streamedCallThenCompleteCall_flushesTheStreamedCallFirst() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("first", "$.x", "1")),
        chunk(Part.fromFunctionCall("second", ImmutableMap.of("y", 2))));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("first");
    assertThat(parts.get(0).functionCall().get().args()).hasValue(ImmutableMap.of("x", "1"));
    assertThat(parts.get(1).functionCall().get().name()).hasValue("second");
  }

  @Test
  public void aggregate_streamedCallEndedByNamelessMarker_flushesCallBeforeTrailingText() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("tool", "$.x", "1")),
        chunk(functionCallPart(FunctionCall.builder().willContinue(false).build())),
        chunk(Part.fromText("Done.")));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("tool");
    assertThat(parts.get(1).text()).hasValue("Done.");
  }

  @Test
  public void aggregate_streamEndingMidCall_flushesTheIncompleteCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(aggregator, chunk(streamedCallStart("tool", "$.x", "partial")));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.name()).hasValue("tool");
    assertThat(call.args()).hasValue(ImmutableMap.of("x", "partial"));
  }

  @Test
  public void processResponse_bareEmptyTextPart_isDropped() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();

    assertThat(aggregator.processResponse(chunk(Part.fromText("")))).isEmpty();
    process(
        aggregator,
        chunk(Part.fromText("Done.")),
        chunk(Part.fromText("")).toBuilder().finishReason(STOP).build());

    assertThat(getOnlyElement(aggregatedParts(aggregator)).text()).hasValue("Done.");
  }

  @Test
  public void aggregate_emptyTextPartWithSignature_isKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part signed = Part.builder().text("").thoughtSignature(SIGNATURE_A).build();

    assertThat(aggregator.processResponse(chunk(signed))).isPresent();

    assertThat(aggregatedParts(aggregator)).containsExactly(signed);
  }

  @Test
  public void hasAccumulatedContent_countsTextAndCompletedCallsOnly() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    assertThat(aggregator.hasAccumulatedContent()).isFalse();

    process(aggregator, chunk(streamedCallStart("tool", "$.x", "1")));
    assertThat(aggregator.hasAccumulatedContent()).isFalse();

    process(
        aggregator, chunk(functionCallPart(FunctionCall.builder().willContinue(false).build())));
    assertThat(aggregator.hasAccumulatedContent()).isTrue();
  }

  @Test
  public void aggregate_noChunks_returnsEmpty() {
    assertThat(new StreamingResponseAggregator().aggregate()).isEmpty();
  }

  @Test
  public void aggregate_noContentAndNoFinishReason_returnsEmpty() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(aggregator, LlmResponse.builder().usageMetadata(usage(5)).build());

    assertThat(aggregator.aggregate()).isEmpty();
  }

  @Test
  public void aggregate_finishReasonWithoutContent_returnsResponseWithoutContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(aggregator, LlmResponse.builder().finishReason(STOP).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.content()).isEmpty();
    assertThat(aggregated.finishReason()).hasValue(STOP);
    assertThat(aggregated.errorCode()).isEmpty();
  }

  @Test
  public void aggregate_buildsOnLastChunkMetadata() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    GenerateContentResponseUsageMetadata lastUsage = usage(9);
    process(
        aggregator,
        chunk(Part.fromText("Hi")).toBuilder().usageMetadata(usage(5)).modelVersion("m").build(),
        chunk(Part.fromText("!")).toBuilder().usageMetadata(lastUsage).finishReason(STOP).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.usageMetadata()).hasValue(lastUsage);
    assertThat(aggregated.finishReason()).hasValue(STOP);
    assertThat(aggregated.modelVersion()).isEmpty();
    assertThat(aggregated.errorCode()).isEmpty();
  }

  @Test
  public void aggregate_nonStopFinishReason_setsErrorCode() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    FinishReason maxTokens = new FinishReason(FinishReason.Known.MAX_TOKENS);
    process(
        aggregator,
        chunk(Part.fromText("Cut sho")),
        LlmResponse.builder().finishReason(maxTokens).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(getOnlyElement(aggregated.content().get().parts().get()).text()).hasValue("Cut sho");
    assertThat(aggregated.finishReason()).hasValue(maxTokens);
    assertThat(aggregated.errorCode()).hasValue(maxTokens);
  }

  @Test
  public void aggregate_nonStopFinishReasonWithoutContent_setsErrorCodeWithoutContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    FinishReason safety = new FinishReason(FinishReason.Known.SAFETY);
    process(aggregator, LlmResponse.builder().finishReason(safety).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.content()).isEmpty();
    assertThat(aggregated.finishReason()).hasValue(safety);
    assertThat(aggregated.errorCode()).hasValue(safety);
  }

  private static void process(StreamingResponseAggregator aggregator, LlmResponse... chunks) {
    for (LlmResponse chunk : chunks) {
      var unused = aggregator.processResponse(chunk);
    }
  }

  private static List<Part> aggregatedParts(StreamingResponseAggregator aggregator) {
    return aggregator.aggregate().get().content().get().parts().get();
  }

  private static FunctionCall onlyFunctionCall(LlmResponse response) {
    return getOnlyElement(response.content().get().parts().get()).functionCall().get();
  }

  private static LlmResponse chunk(Part... parts) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(parts).build())
        .build();
  }

  private static Part thought(String text) {
    return Part.builder().text(text).thought(true).build();
  }

  private static Part functionCallPart(FunctionCall functionCall) {
    return Part.builder().functionCall(functionCall).build();
  }

  private static Part streamedCallStart(String name, String jsonPath, String value) {
    return functionCallPart(
        FunctionCall.builder()
            .name(name)
            .partialArgs(PartialArg.builder().jsonPath(jsonPath).stringValue(value).build())
            .willContinue(true)
            .build());
  }

  private static GenerateContentResponseUsageMetadata usage(int totalTokenCount) {
    return GenerateContentResponseUsageMetadata.builder().totalTokenCount(totalTokenCount).build();
  }
}
