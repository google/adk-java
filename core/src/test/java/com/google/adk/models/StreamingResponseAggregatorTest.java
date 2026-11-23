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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.NullValue;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StreamingResponseAggregatorTest {

  private static final byte[] signatureA = {1, 2};
  private static final byte[] signatureB = {3, 4};
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
        chunk(Part.builder().text(" world").thoughtSignature(signatureA).build()),
        chunk(Part.builder().text("!").thoughtSignature(signatureB).build()));

    Part merged = getOnlyElement(aggregatedParts(aggregator));

    assertThat(merged.text()).hasValue("Hello world!");
    assertThat(merged.thoughtSignature().get()).isEqualTo(signatureA);
  }

  @Test
  public void aggregate_thoughtAndAnswerRuns_keepTheirOwnSignatures() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.builder().text("Reasoning.").thought(true).thoughtSignature(signatureA).build()),
        chunk(Part.builder().text("Answer.").thoughtSignature(signatureB).build()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).thoughtSignature().get()).isEqualTo(signatureA);
    assertThat(parts.get(1).thoughtSignature().get()).isEqualTo(signatureB);
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
                        .thoughtSignature(signatureA)
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
                "title", "Printer is broken", "details", ImmutableMap.of("priority", 1)));
    assertThat(callPart.thoughtSignature().get()).isEqualTo(signatureA);
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
  public void aggregate_partialArgWithoutJsonPath_isSkipped() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .name("tool")
                    .partialArgs(
                        PartialArg.builder().stringValue("lost").build(),
                        PartialArg.builder().jsonPath("$.x").stringValue("kept").build())
                    .willContinue(true)
                    .build())),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("x", "kept"));
  }

  @Test
  public void aggregate_streamedBoolAndNullArgs_areKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .name("tool")
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.urgent").boolValue(true).build(),
                        PartialArg.builder()
                            .jsonPath("$.assignee")
                            .nullValue(NullValue.Known.NULL_VALUE)
                            .build())
                    .willContinue(true)
                    .build())),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args().get()).containsExactly("urgent", true, "assignee", null);
  }

  @Test
  public void aggregate_twoStreamedCalls_getDifferentIdsAndOnlyTheirOwnSignature() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            streamedCallStart("first", "$.x", "1").toBuilder()
                .thoughtSignature(signatureA)
                .build()),
        chunk(streamedCallEnd()),
        chunk(streamedCallStart("second", "$.y", "2")),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().id())
        .isNotEqualTo(parts.get(1).functionCall().get().id());
    assertThat(parts.get(0).thoughtSignature().get()).isEqualTo(signatureA);
    assertThat(parts.get(1).thoughtSignature()).isEmpty();
  }

  @Test
  public void aggregate_newNamedStreamedCallWhileOneIsOpen_flushesTheOpenCallFirst() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(
            streamedCallStart("get_time", "$.zone", "CET").toBuilder()
                .thoughtSignature(signatureB)
                .build()),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(0).functionCall().get().args()).hasValue(ImmutableMap.of("city", "Paris"));
    assertThat(parts.get(0).thoughtSignature()).isEmpty();
    assertThat(parts.get(1).functionCall().get().name()).hasValue("get_time");
    assertThat(parts.get(1).functionCall().get().args()).hasValue(ImmutableMap.of("zone", "CET"));
    assertThat(parts.get(1).thoughtSignature().get()).isEqualTo(signatureB);
  }

  @Test
  public void aggregate_sameNameWithoutArgsWhileACallIsOpen_closesThatCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    String openingId =
        onlyFunctionCall(
                aggregator
                    .processResponse(chunk(streamedCallStart("get_weather", "$.city", "Paris")))
                    .get())
            .id()
            .get();
    LlmResponse closing =
        aggregator
            .processResponse(
                chunk(functionCallPart(FunctionCall.builder().name("get_weather").build())))
            .get();

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(onlyFunctionCall(closing).id()).hasValue(openingId);
    assertThat(call.name()).hasValue("get_weather");
    assertThat(call.args()).hasValue(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void aggregate_sameNameWithArgsWhileACallIsOpen_isASeparateCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .name("get_weather")
                    .args(ImmutableMap.of("city", "London"))
                    .build())));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().args()).hasValue(ImmutableMap.of("city", "Paris"));
    assertThat(parts.get(1).functionCall().get().args())
        .hasValue(ImmutableMap.of("city", "London"));
  }

  @Test
  public void aggregate_sameNameWithAnotherModelIdWithoutArgsWhileACallIsOpen_closesThatCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .id("call-1")
                    .name("tool")
                    .partialArgs(PartialArg.builder().jsonPath("$.x").stringValue("1").build())
                    .willContinue(true)
                    .build())));
    LlmResponse closing =
        aggregator
            .processResponse(
                chunk(functionCallPart(FunctionCall.builder().id("call-2").name("tool").build())))
            .get();

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(onlyFunctionCall(closing).id()).hasValue("call-2");
    assertThat(call.id()).hasValue("call-1");
    assertThat(call.args()).hasValue(ImmutableMap.of("x", "1"));
  }

  @Test
  public void aggregate_callWithoutArgsWhileANamelessCallIsOpen_closesItUnderThatName() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.x").stringValue("a").build())
                    .willContinue(true)
                    .build())),
        chunk(functionCallPart(FunctionCall.builder().name("get_time").build())));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.name()).hasValue("get_time");
    assertThat(call.args()).hasValue(ImmutableMap.of("x", "a"));
  }

  @Test
  public void processResponse_streamedCallChunks_carryTheCallId() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    String firstId =
        onlyFunctionCall(
                aggregator.processResponse(chunk(streamedCallStart("tool", "$.x", "a"))).get())
            .id()
            .get();
    LlmResponse continuation =
        aggregator
            .processResponse(
                chunk(
                    functionCallPart(
                        FunctionCall.builder()
                            .partialArgs(
                                PartialArg.builder().jsonPath("$.x").stringValue("b").build())
                            .willContinue(true)
                            .build())))
            .get();
    LlmResponse closing = aggregator.processResponse(chunk(streamedCallEnd())).get();

    assertThat(onlyFunctionCall(continuation).id()).hasValue(firstId);
    assertThat(onlyFunctionCall(closing).id()).hasValue(firstId);
  }

  @Test
  public void aggregate_namelessChunkBeforeStreamedCall_doesNotLeakIntoIt() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.stale").stringValue("x").build())
                    .willContinue(false)
                    .build())),
        chunk(streamedCallStart("tool", "$.y", "1")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("y", "1"));
  }

  @Test
  public void aggregate_streamedCallStartedWithoutName_isClosedByAChunkWithoutWillContinue() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.x").stringValue("a").build())
                    .willContinue(true)
                    .build())),
        chunk(functionCallPart(FunctionCall.builder().build())),
        chunk(streamedCallStart("tool", "$.y", "1")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("y", "1"));
  }

  @Test
  public void processResponse_completeCallWithoutName_getsAnId() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part nameless = functionCallPart(FunctionCall.builder().args(ImmutableMap.of()).build());

    FunctionCall partialCall = onlyFunctionCall(aggregator.processResponse(chunk(nameless)).get());

    assertThat(partialCall.id().orElse("")).isNotEmpty();
    assertThat(partialCall.name()).isEmpty();
  }

  @Test
  public void aggregate_completeCallWithoutName_isNotInTheFinal() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(functionCallPart(FunctionCall.builder().args(ImmutableMap.of()).build())));

    assertThat(aggregator.aggregate().get().content()).isEmpty();
  }

  @Test
  public void aggregate_completeCallWithoutNameWhileACallIsOpen_closesThatCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Par")),
        chunk(functionCallPart(FunctionCall.builder().args(ImmutableMap.of("city", "x")).build())),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.city").stringValue("is").build())
                    .willContinue(false)
                    .build())));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.name()).hasValue("get_weather");
    assertThat(call.args()).hasValue(ImmutableMap.of("city", "Par"));
  }

  @Test
  public void aggregate_callRepeatingAModelId_keepsTheFirst() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part first =
        functionCallPart(
            FunctionCall.builder()
                .id("call-1")
                .name("send_email")
                .args(ImmutableMap.of("to", "ann"))
                .build());
    Part other =
        functionCallPart(
            FunctionCall.builder()
                .id("call-2")
                .name("send_sms")
                .args(ImmutableMap.of("to", "cid"))
                .build());
    Part repeat =
        functionCallPart(
            FunctionCall.builder()
                .id("call-1")
                .name("notify")
                .args(ImmutableMap.of("to", "bob"))
                .build());
    process(aggregator, chunk(first), chunk(other), chunk(repeat), chunk(Part.fromText("Sent.")));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0)).isEqualTo(first);
    assertThat(parts.get(1)).isEqualTo(other);
    assertThat(parts.get(2).text()).hasValue("Sent.");
  }

  @Test
  public void aggregate_streamedCallRepeatingAModelId_keepsTheFirst() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStartWithId("call-1", "get_weather", "$.city", "Paris")),
        chunk(streamedCallEnd()),
        chunk(streamedCallStartWithId("call-1", "get_weather", "$.city", "Rome")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.id()).hasValue("call-1");
    assertThat(call.args()).hasValue(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void aggregate_identicalCallsWithoutModelIds_areKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.fromFunctionCall("roll_dice", ImmutableMap.of())),
        chunk(Part.fromFunctionCall("roll_dice", ImmutableMap.of())));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().id())
        .isNotEqualTo(parts.get(1).functionCall().get().id());
  }

  @Test
  public void aggregate_identicalCallsWithTheSameClientId_areKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    String clientId = FunctionCallIds.generateClientFunctionCallId();
    FunctionCall call =
        FunctionCall.builder().id(clientId).name("roll_dice").args(ImmutableMap.of()).build();
    process(aggregator, chunk(functionCallPart(call)), chunk(functionCallPart(call)));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
  }

  @Test
  public void aggregate_streamedCallsWhoseClosingChunkOmitsWillContinue_areSeparateCalls() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder().name("find_hotels").willContinue(true).build())),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.amenities[0]").stringValue("POOL").build())
                    .build())),
        chunk(
            functionCallPart(
                FunctionCall.builder().name("book_hotels").willContinue(true).build())),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.length_of_stay").numberValue(10.0).build())
                    .willContinue(true)
                    .build())));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("find_hotels");
    assertThat(parts.get(0).functionCall().get().args())
        .hasValue(ImmutableMap.of("amenities", ImmutableList.of("POOL")));
    assertThat(parts.get(1).functionCall().get().name()).hasValue("book_hotels");
    assertThat(parts.get(1).functionCall().get().args())
        .hasValue(ImmutableMap.of("length_of_stay", 10));
  }

  @Test
  public void processResponse_bareEmptyTextPart_returnsEmpty() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();

    assertThat(aggregator.processResponse(chunk(Part.fromText("")))).isEmpty();
  }

  @Test
  public void aggregate_bareEmptyTextPart_isDropped() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.fromText("")),
        chunk(Part.fromText("Done.")),
        chunk(Part.fromText("")).toBuilder().finishReason(STOP).build());

    assertThat(getOnlyElement(aggregatedParts(aggregator)).text()).hasValue("Done.");
  }

  @Test
  public void processResponse_emptyTextPartWithSignature_returnsPartial() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part signed = Part.builder().text("").thoughtSignature(signatureA).build();

    assertThat(aggregator.processResponse(chunk(signed))).isPresent();
  }

  @Test
  public void aggregate_emptyTextPartWithSignature_isKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part signed = Part.builder().text("").thoughtSignature(signatureA).build();
    process(aggregator, chunk(signed));

    assertThat(aggregatedParts(aggregator)).containsExactly(signed);
  }

  @Test
  public void aggregate_final_hasAMutablePartList() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part call =
        functionCallPart(
            FunctionCall.builder().id("call-1").name("tool").args(ImmutableMap.of()).build());
    process(aggregator, chunk(call));
    List<Part> parts = aggregatedParts(aggregator);

    parts.add(Part.fromText("added by a caller"));

    assertThat(parts).hasSize(2);
  }

  @Test
  public void processResponse_partialWithAFunctionCall_hasAMutablePartList() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    List<Part> parts =
        aggregator
            .processResponse(chunk(streamedCallStart("tool", "$.x", "a")))
            .get()
            .content()
            .get()
            .parts()
            .get();

    parts.add(Part.fromText("added by a caller"));

    assertThat(parts).hasSize(2);
  }

  @Test
  public void aggregate_textAroundStreamedCall_keepsTheCallBetweenTheTexts() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.fromText("Checking the weather.")),
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(Part.fromText(" One moment.")),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).hasValue("Checking the weather.");
    assertThat(parts.get(1).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(2).text()).hasValue(" One moment.");
  }

  @Test
  public void aggregate_textWhileACallIsOpenThenANewName_keepsTheTextAfterTheOpenCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(Part.fromText("hello")),
        chunk(streamedCallStart("get_time", "$.zone", "CET")),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(1).text()).hasValue("hello");
    assertThat(parts.get(2).functionCall().get().name()).hasValue("get_time");
  }

  @Test
  public void
      aggregate_textWhileACallIsOpenThenACompleteCallWithoutName_keepsTheTextAfterTheCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(Part.fromText("hello")),
        chunk(functionCallPart(FunctionCall.builder().args(ImmutableMap.of("x", "1")).build())));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(1).text()).hasValue("hello");
  }

  @Test
  public void aggregate_textWhileACallIsOpenThenACompleteCall_keepsTheTextBetweenTheCalls() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(Part.fromText("hello")),
        chunk(Part.fromFunctionCall("get_time", ImmutableMap.of("zone", "CET"))));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(1).text()).hasValue("hello");
    assertThat(parts.get(2).functionCall().get().name()).hasValue("get_time");
  }

  @Test
  public void aggregate_thoughtAndStreamedCallInOneChunk_keepsTheThoughtFirst() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(thought("Thinking..."), streamedCallStart("my_tool", "$.query", "hello ")),
        chunk(thought(" Still thinking.")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder().jsonPath("$.query").stringValue("world").build())
                    .willContinue(false)
                    .build())));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).text()).hasValue("Thinking...");
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(parts.get(1).functionCall().get().args())
        .hasValue(ImmutableMap.of("query", "hello world"));
    assertThat(parts.get(2).text()).hasValue(" Still thinking.");
  }

  @Test
  public void aggregate_streamEndsWithCallOpen_keepsTheCallBeforeLaterText() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(Part.fromText("Done.")));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(1).text()).hasValue("Done.");
  }

  @Test
  public void aggregate_dataPartWhileACallIsOpen_keepsTheDataPartAfterTheCall() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    Part image =
        Part.builder()
            .inlineData(Blob.builder().mimeType("image/png").data(new byte[] {7}).build())
            .build();
    process(
        aggregator,
        chunk(streamedCallStart("get_weather", "$.city", "Paris")),
        chunk(image),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().name()).hasValue("get_weather");
    assertThat(parts.get(1)).isEqualTo(image);
  }

  @Test
  public void aggregate_namelessStreamedCallBetweenTexts_keepsTheTextsApart() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(Part.fromText("Before.")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.x").stringValue("1").build())
                    .willContinue(true)
                    .build())),
        chunk(Part.fromText(" After.")),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("Before.");
    assertThat(parts.get(1).text()).hasValue(" After.");
  }

  @Test
  public void aggregate_scalarAfterStringChunksOnSamePath_replacesThem() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.value", "12")),
        chunk(streamedCallContinuation("$.value", "34")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.value").numberValue(99.0).build())
                    .willContinue(false)
                    .build())));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("value", 99));
  }

  @Test
  public void aggregate_valuelessPartialArgAfterStringChunks_keepsTheString() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.a", "hello")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.a").build())
                    .willContinue(false)
                    .build())));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("a", "hello"));
  }

  @Test
  public void aggregate_stringChunksUnderTwoSpellingsOfOnePath_joinIntoOneValue() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.a", "hello")),
        chunk(streamedCallContinuation("$['a']", " world")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("a", "hello world"));
  }

  @Test
  public void aggregate_nullOnListElementAfterStringChunksUnderIt_keepsTheNull() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.items[0].name", "alice")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder()
                            .jsonPath("$.items[0]")
                            .nullValue(NullValue.Known.NULL_VALUE)
                            .build())
                    .willContinue(false)
                    .build())));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args().get()).containsExactly("items", Collections.singletonList(null));
  }

  @Test
  public void aggregate_interleavedArgs_keepTheirArrivalOrder() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.a", "hello")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.b").numberValue(7.0).build())
                    .willContinue(true)
                    .build())),
        chunk(streamedCallContinuation("$.a", " world")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args().get()).containsExactly("a", "hello world", "b", 7).inOrder();
  }

  @Test
  public void aggregate_rootPathScalarAfterStringChunks_keepsTheStrings() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.a", "hello")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(
                        PartialArg.builder()
                            .jsonPath("$")
                            .nullValue(NullValue.Known.NULL_VALUE)
                            .build())
                    .willContinue(false)
                    .build())));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("a", "hello"));
  }

  @Test
  public void aggregate_stringAfterScalarOnSamePath_replacesTheScalar() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.x", "old")),
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.x").numberValue(42.0).build())
                    .willContinue(true)
                    .build())),
        chunk(streamedCallContinuation("$.x", "new")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("x", "new"));
  }

  @Test
  public void aggregate_stringChunksAtNestedPath_joinIntoNestedValue() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("my_tool", "$.user.name", "Ja")),
        chunk(streamedCallContinuation("$.user.name", "n")),
        chunk(streamedCallEnd()));

    FunctionCall call = getOnlyElement(aggregatedParts(aggregator)).functionCall().get();

    assertThat(call.args()).hasValue(ImmutableMap.of("user", ImmutableMap.of("name", "Jan")));
  }

  @Test
  public void aggregate_twoCallsSamePath_keepTheirOwnValues() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(streamedCallStart("tool_a", "$.x", "first")),
        chunk(streamedCallEnd()),
        chunk(streamedCallStart("tool_b", "$.x", "second")),
        chunk(streamedCallEnd()));

    List<Part> parts = aggregatedParts(aggregator);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall().get().args()).hasValue(ImmutableMap.of("x", "first"));
    assertThat(parts.get(1).functionCall().get().args()).hasValue(ImmutableMap.of("x", "second"));
  }

  @Test
  public void aggregate_noChunks_returnsEmpty() {
    assertThat(new StreamingResponseAggregator().aggregate()).isEmpty();
  }

  @Test
  public void aggregate_noContentAndNoFinishReason_returnsFinalWithoutContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    GenerateContentResponseUsageMetadata usage = usage(5);
    process(aggregator, LlmResponse.builder().usageMetadata(usage).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.content()).isEmpty();
    assertThat(aggregated.usageMetadata()).hasValue(usage);
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
  public void aggregate_onlyStreamTerminator_keepsTheChunkContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    LlmResponse terminatorOnly = chunk(Part.fromText("")).toBuilder().finishReason(STOP).build();
    process(aggregator, terminatorOnly);

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.content()).isEqualTo(terminatorOnly.content());
    assertThat(aggregated.finishReason()).hasValue(STOP);
  }

  @Test
  public void aggregate_streamTerminatorThenUsageOnlyChunk_keepsTheTerminatorContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    LlmResponse terminator = chunk(Part.fromText("")).toBuilder().finishReason(STOP).build();
    process(aggregator, terminator, trailingUsageChunk(usage(9)));

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.content()).isEqualTo(terminator.content());
    assertThat(aggregated.usageMetadata()).hasValue(usage(9));
  }

  @Test
  public void aggregate_namelessStreamedCallChunk_returnsFinalWithoutContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(
            functionCallPart(
                FunctionCall.builder()
                    .partialArgs(PartialArg.builder().jsonPath("$.x").stringValue("1").build())
                    .build())));

    assertThat(aggregator.aggregate().get().content()).isEmpty();
  }

  @Test
  public void aggregate_namelessEndMarkerThenStop_returnsFinalWithoutContent() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    process(
        aggregator,
        chunk(functionCallPart(FunctionCall.builder().willContinue(false).build())),
        LlmResponse.builder().finishReason(STOP).build());

    assertThat(aggregator.aggregate().get().content()).isEmpty();
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

  @Test
  public void aggregate_usageOnEarlierChunk_isKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    GenerateContentResponseUsageMetadata usage = usage(15);
    process(
        aggregator,
        chunk(Part.fromText("Hello ")).toBuilder().usageMetadata(usage).build(),
        chunk(Part.fromText("World!")).toBuilder().finishReason(STOP).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.usageMetadata()).hasValue(usage);
    assertThat(aggregated.finishReason()).hasValue(STOP);
  }

  @Test
  public void aggregate_trailingUsageOnlyChunk_keepsFinishReason() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    GenerateContentResponseUsageMetadata usage = usage(42);
    process(
        aggregator,
        chunk(Part.fromText("Hi")),
        chunk(Part.fromText("!")).toBuilder().finishReason(STOP).build(),
        trailingUsageChunk(usage));

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(getOnlyElement(aggregated.content().get().parts().get()).text()).hasValue("Hi!");
    assertThat(aggregated.finishReason()).hasValue(STOP);
    assertThat(aggregated.usageMetadata()).hasValue(usage);
    assertThat(aggregated.errorCode()).isEmpty();
    assertThat(aggregated.errorMessage()).isEmpty();
  }

  @Test
  public void aggregate_nonStopFinishReasonBeforeTrailingChunk_setsErrorCode() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    FinishReason maxTokens = new FinishReason(FinishReason.Known.MAX_TOKENS);
    process(
        aggregator,
        chunk(Part.fromText("Cut sho")).toBuilder().finishReason(maxTokens).build(),
        trailingUsageChunk(usage(7)));

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.finishReason()).hasValue(maxTokens);
    assertThat(aggregated.errorCode()).hasValue(maxTokens);
    assertThat(aggregated.errorMessage()).isEmpty();
  }

  @Test
  public void aggregate_groundingOnEarlierChunk_isKept() {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();
    process(
        aggregator,
        chunk(Part.fromText("Hello ")).toBuilder().groundingMetadata(grounding).build(),
        chunk(Part.fromText("World!")).toBuilder().finishReason(STOP).build());

    LlmResponse aggregated = aggregator.aggregate().get();

    assertThat(aggregated.groundingMetadata()).hasValue(grounding);
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

  private static Part streamedCallContinuation(String jsonPath, String value) {
    return functionCallPart(
        FunctionCall.builder()
            .partialArgs(PartialArg.builder().jsonPath(jsonPath).stringValue(value).build())
            .willContinue(true)
            .build());
  }

  private static Part streamedCallEnd() {
    return functionCallPart(FunctionCall.builder().willContinue(false).build());
  }

  private static Part streamedCallStartWithId(
      String id, String name, String jsonPath, String value) {
    return functionCallPart(
        streamedCallStart(name, jsonPath, value).functionCall().get().toBuilder().id(id).build());
  }

  private static GenerateContentResponseUsageMetadata usage(int totalTokenCount) {
    return GenerateContentResponseUsageMetadata.builder().totalTokenCount(totalTokenCount).build();
  }

  /** Returns what {@link LlmResponse#create} makes of a usage-only chunk without candidates. */
  private static LlmResponse trailingUsageChunk(GenerateContentResponseUsageMetadata usage) {
    return LlmResponse.create(GenerateContentResponse.builder().usageMetadata(usage).build());
  }
}
