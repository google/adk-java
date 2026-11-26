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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.models.GeminiLlmConnection.PendingText;
import com.google.adk.models.GeminiLlmConnection.PendingTranscriptions;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.GroundingChunkWeb;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.GroundingSupport;
import com.google.genai.types.Language;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSetupComplete;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.LiveServerToolCallCancellation;
import com.google.genai.types.Part;
import com.google.genai.types.SearchEntryPoint;
import com.google.genai.types.Transcription;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiLlmConnectionTest {

  @Test
  public void sendContent_withoutPartial_completesTurn() {
    Content content = Content.fromParts(Part.fromText("complete input"));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendContent(content)
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete()
          .assertNoErrors();

      LiveSendClientContentParameters sent = getOnlyElement(fixture.transport.clientContents);
      assertThat(sent.turns()).hasValue(ImmutableList.of(content));
      assertThat(sent.turnComplete()).hasValue(true);
    }
  }

  @Test
  public void sendContent_multiplePartialUpdates_onlyFinalContentCompletesTurn() {
    Content first = Content.fromParts(Part.fromText("first context"));
    Content second = Content.fromParts(Part.fromText("second context"));
    Content last = Content.fromParts(Part.fromText("final context"));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendContent(first, /* partial= */ true)
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete()
          .assertNoErrors();
      fixture
          .connection
          .sendContent(second, /* partial= */ true)
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete()
          .assertNoErrors();
      fixture
          .connection
          .sendContent(last, /* partial= */ false)
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete()
          .assertNoErrors();

      List<LiveSendClientContentParameters> sent = fixture.transport.clientContents;
      assertThat(sent).hasSize(3);
      assertThat(sent.get(0).turns()).hasValue(ImmutableList.of(first));
      assertThat(sent.get(1).turns()).hasValue(ImmutableList.of(second));
      assertThat(sent.get(2).turns()).hasValue(ImmutableList.of(last));
      assertThat(sent.stream().map(p -> p.turnComplete().orElseThrow()).toList())
          .containsExactly(false, false, true)
          .inOrder();
    }
  }

  @Test
  public void sendContent_functionResponses_useToolResponseRegardlessOfPartial() {
    Content content =
        Content.fromParts(
            Part.fromFunctionResponse("firstTool", ImmutableMap.of("result", "first")),
            Part.fromFunctionResponse("secondTool", ImmutableMap.of("result", "second")));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendContent(content, /* partial= */ true)
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete()
          .assertNoErrors();
      fixture
          .connection
          .sendContent(content, /* partial= */ false)
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete()
          .assertNoErrors();

      assertThat(fixture.transport.toolResponses).hasSize(2);
      assertThat(fixture.transport.clientContents).isEmpty();
      for (LiveSendToolResponseParameters sent : fixture.transport.toolResponses) {
        assertThat(sent.functionResponses())
            .hasValue(content.parts().get().stream().map(p -> p.functionResponse().get()).toList());
      }
    }
  }

  @Test
  public void convertToServerResponse_withInterruptedTrue_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Model response")))
            .turnComplete(false)
            .interrupted(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();
    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    // The text of the interrupted message is emitted in full and reports the interruption.
    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);

    assertThat(response.content().get().text()).isEqualTo("Model response");
    assertThat(response.partial()).hasValue(false);
    assertThat(response.interrupted()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withInterruptedFalse_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Continuing response")))
            .turnComplete(false)
            .interrupted(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.interrupted()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withoutInterruptedField_mapsEmptyOptional() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Normal response")))
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(2);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(1);
    assertThat(response.interrupted()).isEmpty();
    assertThat(response.turnComplete()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withTurnCompleteTrue_mapsPartialFalse() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Final response")))
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    // The text is emitted in full before the response that completes the turn.
    testObserver.assertValueCount(2);
    testObserver.assertComplete();
    LlmResponse textResponse = testObserver.values().get(0);
    assertThat(textResponse.content().get().text()).isEqualTo("Final response");
    assertThat(textResponse.partial()).hasValue(false);
    LlmResponse response = testObserver.values().get(1);
    assertThat(response.content()).isEmpty();
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withTurnCompleteFalse_mapsPartialTrue() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Partial response")))
            .turnComplete(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.partial()).hasValue(true);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withToolCall_mapsContentWithFunctionCall() {
    FunctionCall functionCall = FunctionCall.builder().name("tool").build();
    LiveServerToolCall toolCall =
        LiveServerToolCall.builder().functionCalls(ImmutableList.of(functionCall)).build();

    LiveServerMessage message = LiveServerMessage.builder().toolCall(toolCall).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(1);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(functionCall);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withMultipleFunctionCalls_preservesAllCallsAsParts() {
    // Regression test for parallel tool calling on the live/BIDI path: a single toolCall message
    // can carry multiple FunctionCalls, and all of them must be preserved (not just the last).
    FunctionCall getWeather =
        FunctionCall.builder().name("getWeather").args(ImmutableMap.of("city", "Paris")).build();
    FunctionCall getTime =
        FunctionCall.builder()
            .name("getTime")
            .args(ImmutableMap.of("timezone", "Europe/London"))
            .build();
    LiveServerToolCall toolCall =
        LiveServerToolCall.builder().functionCalls(ImmutableList.of(getWeather, getTime)).build();

    LiveServerMessage message = LiveServerMessage.builder().toolCall(toolCall).build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).hasSize(2);
    assertThat(response.content().get().parts().get().get(0).functionCall()).hasValue(getWeather);
    assertThat(response.content().get().parts().get().get(1).functionCall()).hasValue(getTime);
    assertThat(response.partial()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withUsageMetadata_mapsGenerateResponseUsageMetadata() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .usageMetadata(
                UsageMetadata.builder()
                    .promptTokenCount(10)
                    .responseTokenCount(20)
                    .totalTokenCount(30)
                    .build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);
    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.usageMetadata()).isPresent();
    GenerateContentResponseUsageMetadata expectedUsageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    assertThat(response.usageMetadata()).hasValue(expectedUsageMetadata);
  }

  @Test
  public void convertToServerResponse_withToolCallCancellation_returnsNoValues() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .toolCallCancellation(LiveServerToolCallCancellation.builder().build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);
    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withSetupComplete_returnsNoValues() {
    LiveServerMessage message =
        LiveServerMessage.builder()
            .setupComplete(LiveServerSetupComplete.builder().build())
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withUnknownMessage_returnsErrorResponse() {
    LiveServerMessage message = LiveServerMessage.builder().build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(1);
    testObserver.assertComplete();
    LlmResponse response = testObserver.values().get(0);
    assertThat(response.errorCode()).isPresent();
    assertThat(response.errorMessage()).hasValue("Received unknown server message.");
  }

  @Test
  public void convertToServerResponse_withContentAndUsageMetadata_emitsMultiple() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(Content.fromParts(Part.fromText("Model response")))
            .turnComplete(true)
            .build();

    UsageMetadata usageMetadata =
        UsageMetadata.builder()
            .promptTokenCount(10)
            .responseTokenCount(20)
            .totalTokenCount(30)
            .build();

    LiveServerMessage message =
        LiveServerMessage.builder()
            .serverContent(serverContent)
            .usageMetadata(usageMetadata)
            .build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(
            message, new PendingText(), new PendingTranscriptions(/* inputArrivesInFull= */ false))
        .subscribe(testObserver);

    testObserver.assertValueCount(3);
    testObserver.assertComplete();

    List<LlmResponse> responses = testObserver.values();

    // Check for ServerContent response: the text in full, then the turn completion
    LlmResponse contentResponse = responses.get(0);
    assertThat(contentResponse.content()).isPresent();
    assertThat(contentResponse.content().get().text()).isEqualTo("Model response");
    assertThat(contentResponse.usageMetadata()).isEmpty();
    assertThat(responses.get(1).turnComplete()).hasValue(true);

    // Check for UsageMetadata response
    LlmResponse usageResponse = responses.get(2);
    assertThat(usageResponse.content()).isEmpty();
    assertThat(usageResponse.usageMetadata()).isPresent();
    GenerateContentResponseUsageMetadata expectedUsageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    assertThat(usageResponse.usageMetadata()).hasValue(expectedUsageMetadata);
  }

  @Test
  public void convertToServerResponse_textArrivingWithTurnComplete_isEmittedOnlyInFull() {
    // Text that arrives with the end of the turn is not streamed on its own.
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromText("lo")))
                        .turnComplete(true))))
        .containsExactly("partial text:Hel", "text:Hello", "turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_interrupted_fullTextReportsIt() {
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(LiveServerContent.builder().interrupted(true))))
        .containsExactly("partial text:Hel", "text:Hel interrupted")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_textArrivingWithInterrupted_isEmittedOnlyInFull() {
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromText("lo")))
                        .interrupted(true))))
        .containsExactly("partial text:Hel", "text:Hello interrupted")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_interruptedWithTurnComplete_bothResponsesReportIt() {
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(LiveServerContent.builder().interrupted(true).turnComplete(true))))
        .containsExactly("partial text:Hel", "text:Hel interrupted", "turnComplete interrupted")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_interruptedWithInlineData_bothResponsesReportIt() {
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromBytes(new byte[] {1, 2}, "audio/pcm")))
                        .interrupted(true))))
        .containsExactly("partial text:Hel", "text:Hel interrupted", "inlineData interrupted")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_interruptedWithTranscription_fullTextReportsIt() {
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(
                    LiveServerContent.builder()
                        .interrupted(true)
                        .outputTranscription(Transcription.builder().text("Hel").build()))))
        .containsExactly(
            "partial text:Hel", "partial output:Hel", "finished output:Hel", "text:Hel interrupted")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_interruptedWithoutText_isReportedOnMessageResponse() {
    assertThat(convertAll(serverContent(LiveServerContent.builder().interrupted(true))))
        .containsExactly("interrupted");
    assertThat(
            convertAll(
                serverContent(
                    LiveServerContent.builder()
                        .interrupted(true)
                        .outputTranscription(Transcription.builder().text("Hel").build()))))
        .containsExactly("partial output:Hel", "finished output:Hel", "interrupted")
        .inOrder();
    assertThat(
            convertAll(
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromBytes(new byte[] {1, 2}, "audio/pcm")))
                        .interrupted(true))))
        .containsExactly("inlineData interrupted");
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().interrupted(true).turnComplete(true))))
        .containsExactly("turnComplete interrupted");
  }

  @Test
  public void convertToServerResponse_textAndOtherPartInOneMessage_keepsOtherPartAfterFullText() {
    Part code =
        Part.builder()
            .executableCode(
                ExecutableCode.builder().code("print(1)").language(Language.Known.PYTHON))
            .build();
    LiveServerMessage message =
        serverContent(
            LiveServerContent.builder()
                .modelTurn(
                    Content.builder().role("model").parts(Part.fromText("Run"), code).build()));

    List<LlmResponse> responses =
        GeminiLlmConnection.convertToServerResponse(
                message,
                new PendingText(),
                new PendingTranscriptions(/* inputArrivesInFull= */ false))
            .toList()
            .blockingGet();

    assertThat(describe(responses)).containsExactly("text:Run", "executableCode").inOrder();
    assertThat(responses.get(1).content().get().role()).hasValue("model");
  }

  @Test
  public void convertToServerResponse_textAndInlineDataWithTurnComplete_keepsInlineData() {
    assertThat(
            convertAll(
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(
                            Content.builder()
                                .role("model")
                                .parts(
                                    Part.fromText("Hello"),
                                    Part.fromBytes(new byte[] {1, 2}, "audio/pcm"))
                                .build())
                        .turnComplete(true))))
        .containsExactly("text:Hello", "inlineData turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_toolCall_emitsPendingTextInFullFirst() {
    LiveServerMessage toolCall =
        LiveServerMessage.builder()
            .toolCall(
                LiveServerToolCall.builder()
                    .functionCalls(ImmutableList.of(FunctionCall.builder().name("tool").build()))
                    .build())
            .build();

    assertThat(convertAll(modelTurn(Part.fromText("Checking")), toolCall))
        .containsExactly("partial text:Checking", "text:Checking", "call:tool")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_thoughtThenText_emitsEachInFull() {
    assertThat(
            convertAll(
                modelTurn(Part.builder().text("Plan").thought(true).build()),
                modelTurn(Part.fromText("Answer")),
                serverContent(LiveServerContent.builder().turnComplete(true))))
        .containsExactly(
            "partial thought:Plan",
            "thought:Plan",
            "partial text:Answer",
            "text:Answer",
            "turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_inlineData_isNotPartialAndDoesNotEndPendingText() {
    // Only text is partial, even in a message that sets turnComplete to false.
    assertThat(
            convertAll(
                modelTurn(Part.fromText("Hel")),
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromBytes(new byte[] {1, 2}, "audio/pcm")))
                        .turnComplete(false)),
                modelTurn(Part.fromText("lo")),
                serverContent(LiveServerContent.builder().turnComplete(true))))
        .containsExactly(
            "partial text:Hel", "inlineData", "partial text:lo", "text:Hello", "turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_textWithTranscription_emitsTranscriptionSeparately() {
    // Each transcription chunk gets a response of its own.
    assertThat(
            convertAll(
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromText("Hel")))
                        .inputTranscription(Transcription.builder().text("Hi").build()))))
        .containsExactly("partial text:Hel", "partial input:Hi")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_emptyText_endsPendingTextLikeOtherParts() {
    // A part with empty text, such as a bare thought signature, is not text.
    assertThat(convertAll(modelTurn(Part.fromText("Hel")), modelTurn(Part.fromText(""))))
        .containsExactly("partial text:Hel", "text:Hel", "text:")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_otherPart_endsPendingText() {
    Part code =
        Part.builder()
            .executableCode(
                ExecutableCode.builder().code("print(1)").language(Language.Known.PYTHON))
            .build();

    assertThat(convertAll(modelTurn(Part.fromText("Running")), modelTurn(code)))
        .containsExactly("partial text:Running", "text:Running", "executableCode")
        .inOrder();
  }

  @Test
  public void receive_textChunks_streamedAsPartialAndEmittedInFullAtTurnComplete() {
    // The Live API may leave turnComplete out of a message that does not end the turn.
    CompletableFuture<GeminiLiveTransport> transportFuture = new CompletableFuture<>();
    GeminiLlmConnection connection = new GeminiLlmConnection("gemini-live", transportFuture);
    TestSubscriber<LlmResponse> subscriber = connection.receive().test();

    transportFuture.complete(
        new FakeTransport(
            /* endStreamOnReceive= */ true,
            ImmutableList.of(
                modelTurn(Part.fromText("Hel")),
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromText("lo")))
                        .turnComplete(false)),
                serverContent(LiveServerContent.builder().turnComplete(true)))));
    subscriber.awaitDone(5, TimeUnit.SECONDS);

    subscriber.assertComplete();
    assertThat(describe(subscriber.values()))
        .containsExactly("partial text:Hel", "partial text:lo", "text:Hello", "turnComplete")
        .inOrder();
  }

  @Test
  public void receive_completesWhenTransportSignalsStreamEnd() {
    // A transport that ends its own receive stream completes the connection's response stream, so a
    // live run with no client close still terminates.
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection("gemini-live", CompletableFuture.completedFuture(transport));

    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);

    subscriber.assertComplete();
    subscriber.assertNoErrors();
  }

  @Test
  public void close_afterTransportStreamEnd_isNoOp() {
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection("gemini-live", CompletableFuture.completedFuture(transport));
    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);
    subscriber.assertComplete();

    connection.close(); // Already completed via stream-end; must not re-terminate or error.

    subscriber.assertComplete();
    subscriber.assertNoErrors();
    // completeReceive leaves the transport to close itself, so no close() is issued on this path.
    assertThat(transport.closeCount.get()).isEqualTo(0);
  }

  @Test
  public void sendRealtime_gemini3xLive_sendsAudioAndVideoInTheirOwnFields() {
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=16000").data(new byte[] {1}).build();
    Blob frame = Blob.builder().mimeType("image/jpeg").data(new byte[] {2}).build();
    try (LiveConnectionFixture fixture = new LiveConnectionFixture("gemini-3.8-live")) {
      fixture.connection.sendRealtime(audio).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
      fixture.connection.sendRealtime(frame).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

      assertThat(fixture.transport.realtimeInputs)
          .containsExactly(
              LiveSendRealtimeInputParameters.builder().audio(audio).build(),
              LiveSendRealtimeInputParameters.builder().video(frame).build())
          .inOrder();
    }
  }

  @Test
  public void sendRealtime_gemini35LiveTranslate_sendsAudioInItsOwnField() {
    Blob audio = Blob.builder().mimeType("audio/pcm;rate=16000").data(new byte[] {1}).build();
    try (LiveConnectionFixture fixture =
        new LiveConnectionFixture(
            "projects/p/locations/l/publishers/google/models/gemini-3.5-live-translate-preview")) {
      fixture.connection.sendRealtime(audio).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

      assertThat(fixture.transport.realtimeInputs)
          .containsExactly(LiveSendRealtimeInputParameters.builder().audio(audio).build());
    }
  }

  @Test
  public void sendRealtime_gemini3xLiveAndNoAudioOrImageMimeType_sendsNothing() {
    try (LiveConnectionFixture fixture = new LiveConnectionFixture("gemini-3.8-live")) {
      fixture
          .connection
          .sendRealtime(Blob.builder().mimeType("text/plain").data(new byte[] {1}).build())
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete();
      fixture
          .connection
          .sendRealtime(Blob.builder().data(new byte[] {1}).build())
          .test()
          .awaitDone(5, TimeUnit.SECONDS)
          .assertComplete();

      assertThat(fixture.transport.realtimeInputs).isEmpty();
    }
  }

  @Test
  public void sendRealtime_olderLiveModel_sendsMedia() {
    Blob frame = Blob.builder().mimeType("image/jpeg").data(new byte[] {2}).build();
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendRealtime(frame).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

      assertThat(fixture.transport.realtimeInputs)
          .containsExactly(LiveSendRealtimeInputParameters.builder().media(frame).build());
    }
  }

  @Test
  public void sendHistory_endsWithUserTurn_completesTurn() {
    ImmutableList<Content> history =
        ImmutableList.of(
            userContent(Part.fromText("My name is Zofia.")),
            modelContent(Part.fromText("Nice to meet you.")),
            userContent(Part.fromText("What is my name?")));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendHistory(history).blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(history, /* turnComplete= */ true));
      assertThat(fixture.transport.realtimeInputs).isEmpty();
    }
  }

  @Test
  public void sendHistory_endsWithModelTurn_leavesTurnOpen() {
    ImmutableList<Content> history =
        ImmutableList.of(
            userContent(Part.fromText("Hello")), modelContent(Part.fromText("Hi there!")));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendHistory(history).blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(history, /* turnComplete= */ false));
    }
  }

  @Test
  public void sendHistory_endsWithTurnWithoutRole_leavesTurnOpen() {
    ImmutableList<Content> history =
        ImmutableList.of(
            userContent(Part.fromText("Hello")),
            Content.builder().parts(Part.fromText("Hi there!")).build());
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendHistory(history).blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(history, /* turnComplete= */ false));
    }
  }

  @Test
  public void sendHistory_endsWithFunctionResponse_completesTurn() {
    ImmutableList<Content> history =
        ImmutableList.of(
            userContent(Part.fromText("What is the weather in Paris?")),
            modelContent(Part.fromFunctionCall("get_weather", ImmutableMap.of("city", "Paris"))),
            userContent(
                Part.fromFunctionResponse("get_weather", ImmutableMap.of("forecast", "sunny"))));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendHistory(history).blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(history, /* turnComplete= */ true));
    }
  }

  @Test
  public void sendHistory_dropsAudioAndSavedLiveBlobs_keepsOtherParts() {
    Part image = Part.fromBytes(new byte[] {1}, "image/png");
    Part artifact = Part.fromUri("artifact://app/user/session/report.pdf#0", "application/pdf");
    Part liveImageOutsideArtifacts = Part.fromUri("gs://bucket/_adk_live/image.png", "image/png");
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendHistory(
              ImmutableList.of(
                  userContent(Part.fromText("Look at this."), inlineAudio(), image),
                  Content.builder()
                      .role("model")
                      .parts(
                          Part.fromText("I see it."),
                          Part.fromUri("gs://bucket/reply.wav", "audio/wav"))
                      .build(),
                  userContent(
                      Part.fromUri(
                          "artifact://app/user/session/_adk_live/media_1.zip#0", "application/zip"),
                      artifact,
                      liveImageOutsideArtifacts)))
          .blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(
              clientContent(
                  ImmutableList.of(
                      userContent(Part.fromText("Look at this."), image),
                      modelContent(Part.fromText("I see it.")),
                      userContent(artifact, liveImageOutsideArtifacts)),
                  /* turnComplete= */ true));
    }
  }

  @Test
  public void sendHistory_trailingModelTurnOfOnlyAudio_completesTheUserTurnBeforeIt() {
    Content question = userContent(Part.fromText("What is my name?"));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendHistory(ImmutableList.of(question, modelContent(inlineAudio())))
          .blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(ImmutableList.of(question), /* turnComplete= */ true));
    }
  }

  @Test
  public void sendHistory_trailingUserTurnOfOnlyAudio_leavesTurnOpen() {
    Content hi = userContent(Part.fromText("hi"));
    Content hello = modelContent(Part.fromText("hello"));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendHistory(ImmutableList.of(hi, hello, userContent(inlineAudio())))
          .blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(ImmutableList.of(hi, hello), /* turnComplete= */ false));
    }
  }

  @Test
  public void sendHistory_everyPartDropped_sendsNothing() {
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendHistory(ImmutableList.of(userContent(inlineAudio()))).blockingAwait();

      assertThat(fixture.transport.clientContents).isEmpty();
    }
  }

  @Test
  public void runLive_transcriptionChunks_savesOneFinishedTranscriptionEach() throws Exception {
    ImmutableList<String> saved =
        savedTranscriptions(
            "gemini-live-2.5-flash-native-audio",
            serverContent(LiveServerContent.builder().inputTranscription(chunk("Where is"))),
            serverContent(
                LiveServerContent.builder()
                    .inputTranscription(
                        Transcription.builder().text(" my order?").finished(true).build())),
            serverContent(LiveServerContent.builder().outputTranscription(chunk("It shipped"))),
            serverContent(LiveServerContent.builder().outputTranscription(chunk(" today."))),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(saved)
        .containsExactly("finished input:Where is my order?", "finished output:It shipped today.")
        .inOrder();
  }

  @Test
  public void runLive_gemini3xLiveInputTranscription_isSavedAsFinishedRightAway() throws Exception {
    // Gemini 3.x Live sends the input transcription once, in full, and may never mark it finished.
    ImmutableList<String> saved =
        savedTranscriptions(
            "gemini-3.8-live",
            serverContent(
                LiveServerContent.builder().inputTranscription(chunk("Tell me the secret"))),
            serverContent(LiveServerContent.builder().outputTranscription(chunk("No."))));

    assertThat(saved).contains("finished input:Tell me the secret");
  }

  @Test
  public void runLive_transferBeforeSpeechIsMarkedFinished_targetAgentHistoryHasTheSpeech()
      throws Exception {
    CompletableFuture<GeminiLiveTransport> rootConnecting = new CompletableFuture<>();
    CompletableFuture<LiveSendClientContentParameters> refundHistory = new CompletableFuture<>();
    FakeTransport refundTransport =
        new FakeTransport(/* endStreamOnReceive= */ false) {
          @Override
          public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params) {
            refundHistory.complete(params);
            return super.sendClientContent(params);
          }
        };
    Iterator<CompletableFuture<GeminiLiveTransport>> connections =
        ImmutableList.of(
                rootConnecting,
                CompletableFuture.<GeminiLiveTransport>completedFuture(refundTransport))
            .iterator();
    Gemini gemini =
        new Gemini("gemini-live-2.5-flash-native-audio", "fake-api-key") {
          @Override
          protected CompletableFuture<GeminiLiveTransport> connectLiveTransport(
              String name, LiveConnectConfig config) {
            return connections.next();
          }
        };
    LlmAgent refund = LlmAgent.builder().name("refund").model(gemini).build();
    InMemoryRunner runner =
        new InMemoryRunner(LlmAgent.builder().name("root").model(gemini).subAgents(refund).build());
    Session session = runner.sessionService().createSession(runner.appName(), "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> events =
        runner
            .runLive(
                session,
                liveRequestQueue,
                RunConfig.builder().setStreamingMode(StreamingMode.BIDI).build())
            .test();
    // The transfer call arrives before the user's speech is marked finished.
    rootConnecting.complete(
        new FakeTransport(
            /* endStreamOnReceive= */ false,
            ImmutableList.of(
                serverContent(
                    LiveServerContent.builder().inputTranscription(chunk("I want a refund."))),
                toolCallMessage(
                    FunctionCall.builder()
                        .id("call-1")
                        .name("transfer_to_agent")
                        .args(ImmutableMap.of("agent_name", "refund"))
                        .build()))) {
          @Override
          public CompletableFuture<Void> receive(
              Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
            // A real transport delivers messages on its own thread after setup.
            return CompletableFuture.runAsync(() -> {
              var unused = super.receive(onMessage, onStreamEnd);
            });
          }
        });
    LiveSendClientContentParameters history = refundHistory.get(5, TimeUnit.SECONDS);
    liveRequestQueue.close();
    events.awaitDone(5, TimeUnit.SECONDS).assertComplete();

    assertThat(texts(history)).contains("I want a refund.");
  }

  @Test
  public void convertToServerResponse_transcriptionChunks_streamedAsPartialAndEmittedInFull() {
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Hel"))),
                serverContent(
                    LiveServerContent.builder()
                        .inputTranscription(
                            Transcription.builder().text("lo").finished(true).build())),
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Again")))))
        .containsExactly(
            "partial input:Hel", "partial input:lo", "finished input:Hello", "partial input:Again")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_transcription_setsPartialAndFinishedExplicitly() {
    PendingTranscriptions pendingTranscriptions =
        new PendingTranscriptions(/* inputArrivesInFull= */ false);
    List<LlmResponse> responses =
        GeminiLlmConnection.convertToServerResponse(
                serverContent(
                    LiveServerContent.builder()
                        .outputTranscription(
                            Transcription.builder().text("Hi").finished(true).build())),
                new PendingText(),
                pendingTranscriptions)
            .toList()
            .blockingGet();

    assertThat(responses).hasSize(2);
    assertThat(responses.get(0).partial()).hasValue(true);
    assertThat(responses.get(0).outputTranscription().get().finished()).hasValue(false);
    assertThat(responses.get(1).partial()).hasValue(false);
    assertThat(responses.get(1).outputTranscription().get().finished()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_turnSignal_flushesPendingTranscriptionsBeforeIt() {
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Hi"))),
                serverContent(LiveServerContent.builder().outputTranscription(chunk("Hel"))),
                serverContent(
                    LiveServerContent.builder()
                        .outputTranscription(chunk("lo"))
                        .turnComplete(true))))
        .containsExactly(
            "partial input:Hi",
            "partial output:Hel",
            "partial output:lo",
            "finished input:Hi",
            "finished output:Hello",
            "turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_turnComplete_emitsTranscriptionsBeforeFullText() {
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Hi"))),
                modelTurn(Part.fromText("Hel")),
                serverContent(LiveServerContent.builder().turnComplete(true))))
        .containsExactly(
            "partial input:Hi", "partial text:Hel", "finished input:Hi", "text:Hel", "turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_toolCall_emitsPendingTranscriptionsBeforeTheCall() {
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Check"))),
                serverContent(LiveServerContent.builder().outputTranscription(chunk("One moment"))),
                toolCallMessage(FunctionCall.builder().name("tool").build()),
                serverContent(LiveServerContent.builder().turnComplete(true))))
        .containsExactly(
            "partial input:Check",
            "partial output:One moment",
            "finished input:Check",
            "finished output:One moment",
            "call:tool",
            "turnComplete")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_finishAfterToolCall_emitsOnlyTextThatFollowedTheCall() {
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Check"))),
                toolCallMessage(FunctionCall.builder().name("tool").build()),
                serverContent(
                    LiveServerContent.builder()
                        .inputTranscription(Transcription.builder().finished(true).build()))))
        .containsExactly("partial input:Check", "finished input:Check", "call:tool")
        .inOrder();
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().outputTranscription(chunk("One"))),
                toolCallMessage(FunctionCall.builder().name("tool").build()),
                serverContent(
                    LiveServerContent.builder()
                        .outputTranscription(
                            Transcription.builder().text(" more").finished(true).build()))))
        .containsExactly(
            "partial output:One",
            "finished output:One",
            "call:tool",
            "partial output: more",
            "finished output: more")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_generationCompleteOrInterrupted_flushesTranscriptions() {
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().outputTranscription(chunk("Hel"))),
                serverContent(
                    LiveServerContent.builder()
                        .outputTranscription(chunk("lo"))
                        .generationComplete(true))))
        .containsExactly("partial output:Hel", "partial output:lo", "finished output:Hello")
        .inOrder();
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().outputTranscription(chunk("Hel"))),
                serverContent(LiveServerContent.builder().generationComplete(true)),
                serverContent(LiveServerContent.builder().turnComplete(true))))
        .containsExactly("partial output:Hel", "finished output:Hel", "turnComplete")
        .inOrder();
    assertThat(
            convertAll(
                serverContent(LiveServerContent.builder().outputTranscription(chunk("Hel"))),
                serverContent(LiveServerContent.builder().interrupted(true))))
        .containsExactly("partial output:Hel", "finished output:Hel", "interrupted")
        .inOrder();
  }

  @Test
  public void convertToServerResponse_finishedWithoutPendingText_emitsEmptyFinishedTranscription() {
    // As in ADK Python, a finished signal gets a full response, even with no text.
    assertThat(
            convertAll(
                serverContent(
                    LiveServerContent.builder()
                        .outputTranscription(Transcription.builder().finished(true).build()))))
        .containsExactly("finished output:");
  }

  @Test
  public void convertToServerResponse_gemini3xLiveInput_isEmittedOnlyInFull() {
    assertThat(
            convertAll(
                new PendingTranscriptions(/* inputArrivesInFull= */ true),
                serverContent(LiveServerContent.builder().inputTranscription(chunk("Hello"))),
                serverContent(LiveServerContent.builder().outputTranscription(chunk("Hi"))),
                serverContent(LiveServerContent.builder().turnComplete(true))))
        .containsExactly(
            "finished input:Hello", "partial output:Hi", "finished output:Hi", "turnComplete")
        .inOrder();
    assertThat(
            convertAll(
                new PendingTranscriptions(/* inputArrivesInFull= */ true),
                serverContent(
                    LiveServerContent.builder()
                        .inputTranscription(Transcription.builder().finished(true).build()))))
        .isEmpty();
  }

  @Test
  public void isGemini3xLive_matchesGemini3LiveModelsExceptLiveTranslate() {
    assertThat(GeminiLlmConnection.isGemini3xLive("gemini-3.8-live")).isTrue();
    assertThat(
            GeminiLlmConnection.isGemini3xLive(
                "projects/p/locations/l/publishers/google/models/gemini-3.1-flash-live-preview"))
        .isTrue();
    assertThat(GeminiLlmConnection.isGemini3xLive("models/gemini-3.8-live")).isTrue();
    assertThat(GeminiLlmConnection.isGemini3xLive("apigee/v1/gemini-3.8-live")).isTrue();
    assertThat(GeminiLlmConnection.isGemini3xLive("gemini-3.5-live-translate")).isFalse();
    assertThat(GeminiLlmConnection.isGemini3xLive("gemini-live-2.5-flash-native-audio")).isFalse();
    assertThat(GeminiLlmConnection.isGemini3xLive("gemini-3.8-flash")).isFalse();
    assertThat(GeminiLlmConnection.isGemini3xLive(null)).isFalse();
  }

  @Test
  public void convertToServerResponse_groundingWithStreamedText_isCarriedByTheFullText() {
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("Sun")))
                    .groundingMetadata(grounding)),
            modelTurn(Part.fromText("ny.")),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(describe(responses))
        .containsExactly("partial text:Sun", "partial text:ny.", "text:Sunny.", "turnComplete")
        .inOrder();
    assertThat(groundings(responses))
        .containsExactly(
            Optional.of(grounding), Optional.empty(), Optional.of(grounding), Optional.empty())
        .inOrder();
  }

  @Test
  public void convertToServerResponse_groundingWithTurnComplete_isOnFullTextAndTurnComplete() {
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("It is sunny.")))
                    .groundingMetadata(grounding)
                    .turnComplete(true)));

    assertThat(describe(responses)).containsExactly("text:It is sunny.", "turnComplete").inOrder();
    assertThat(groundings(responses))
        .containsExactly(Optional.of(grounding), Optional.of(grounding))
        .inOrder();
  }

  @Test
  public void convertToServerResponse_groundingWithoutContent_isEmittedOnItsOwn() {
    GroundingMetadata grounding =
        GroundingMetadata.builder()
            .webSearchQueries("weather")
            .searchEntryPoint(SearchEntryPoint.builder().renderedContent("<p>Weather</p>"))
            .build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(LiveServerContent.builder().groundingMetadata(grounding)));

    LlmResponse response = getOnlyElement(responses);
    assertThat(response.groundingMetadata()).hasValue(grounding);
    assertThat(response.content()).isEmpty();
  }

  @Test
  public void convertToServerResponse_groundingBeforeToolCall_isCarriedByTheToolCall() {
    GroundingMetadata grounding = GroundingMetadata.builder().retrievalQueries("query").build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(LiveServerContent.builder().groundingMetadata(grounding)),
            toolCallMessage(FunctionCall.builder().name("lookup").build()),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(describe(responses)).containsExactly("", "call:lookup", "turnComplete").inOrder();
    assertThat(groundings(responses))
        .containsExactly(Optional.of(grounding), Optional.of(grounding), Optional.empty())
        .inOrder();
  }

  @Test
  public void convertToServerResponse_groundingAcrossMessages_isMergedWithShiftedChunkIndices() {
    GroundingMetadata first =
        GroundingMetadata.builder()
            .retrievalQueries("query1")
            .groundingChunks(chunk(1))
            .groundingSupports(GroundingSupport.builder().groundingChunkIndices(0))
            .build();
    GroundingMetadata second =
        GroundingMetadata.builder()
            .retrievalQueries("query1", "query2")
            .groundingChunks(chunk(2))
            .groundingSupports(GroundingSupport.builder().groundingChunkIndices(0))
            .build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("hello")))
                    .groundingMetadata(first)),
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText(" world")))
                    .groundingMetadata(second)),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    LlmResponse fullText = responses.get(2);
    assertThat(describe(ImmutableList.of(fullText))).containsExactly("text:hello world");
    GroundingMetadata merged = fullText.groundingMetadata().get();
    assertThat(merged.retrievalQueries()).hasValue(ImmutableList.of("query1", "query2"));
    assertThat(merged.groundingChunks()).hasValue(ImmutableList.of(chunk(1), chunk(2)));
    assertThat(
            merged.groundingSupports().get().stream()
                .map(support -> support.groundingChunkIndices().get())
                .collect(toImmutableList()))
        .containsExactly(ImmutableList.of(0), ImmutableList.of(1))
        .inOrder();
  }

  @Test
  public void convertToServerResponse_groundingBeforeThoughtSwitch_staysForTheAnswer() {
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();
    Part thought = Part.builder().text("Searching").thought(true).build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(thought))
                    .groundingMetadata(grounding)),
            modelTurn(Part.fromText("Sunny.")),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(describe(responses))
        .containsExactly(
            "partial thought:Searching",
            "thought:Searching",
            "partial text:Sunny.",
            "text:Sunny.",
            "turnComplete")
        .inOrder();
    assertThat(groundings(responses).get(1)).isEmpty();
    assertThat(groundings(responses).get(3)).hasValue(grounding);
  }

  @Test
  public void convertToServerResponse_interruption_carriesAndClearsTheGrounding() {
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();

    ImmutableList<LlmResponse> withText =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("Sun")))
                    .groundingMetadata(grounding)),
            serverContent(LiveServerContent.builder().interrupted(true)),
            serverContent(LiveServerContent.builder().turnComplete(true)));
    ImmutableList<LlmResponse> withoutText =
        convertAllResponses(
            serverContent(LiveServerContent.builder().groundingMetadata(grounding)),
            serverContent(LiveServerContent.builder().interrupted(true)),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(describe(withText))
        .containsExactly("partial text:Sun", "text:Sun interrupted", "turnComplete")
        .inOrder();
    assertThat(groundings(withText))
        .containsExactly(Optional.of(grounding), Optional.of(grounding), Optional.empty())
        .inOrder();
    assertThat(describe(withoutText)).containsExactly("", "interrupted", "turnComplete").inOrder();
    assertThat(groundings(withoutText))
        .containsExactly(Optional.of(grounding), Optional.of(grounding), Optional.empty())
        .inOrder();
  }

  @Test
  public void convertToServerResponse_groundingMerge_keepsQueriesAndReplacesOtherFields() {
    GroundingMetadata first =
        GroundingMetadata.builder()
            .webSearchQueries("a", "a")
            .searchEntryPoint(SearchEntryPoint.builder().renderedContent("first"))
            .build();
    GroundingMetadata second =
        GroundingMetadata.builder()
            .webSearchQueries("a", "b")
            .searchEntryPoint(SearchEntryPoint.builder().renderedContent("second"))
            .build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("A")))
                    .groundingMetadata(first)),
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("B")))
                    .groundingMetadata(second)),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    GroundingMetadata merged = responses.get(2).groundingMetadata().get();
    assertThat(merged.webSearchQueries()).hasValue(ImmutableList.of("a", "a", "b"));
    assertThat(merged.searchEntryPoint().flatMap(SearchEntryPoint::renderedContent))
        .hasValue("second");
  }

  @Test
  public void convertToServerResponse_groundedTextEndedByNonTextPart_carriesTheGrounding() {
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();
    Part code =
        Part.builder()
            .executableCode(
                ExecutableCode.builder().code("print(1)").language(Language.Known.PYTHON))
            .build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("Sunny.")))
                    .groundingMetadata(grounding)),
            modelTurn(code),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(describe(responses))
        .containsExactly("partial text:Sunny.", "text:Sunny.", "executableCode", "turnComplete")
        .inOrder();
    assertThat(groundings(responses))
        .containsExactly(
            Optional.of(grounding), Optional.of(grounding), Optional.empty(), Optional.empty())
        .inOrder();
  }

  @Test
  public void convertToServerResponse_groundedTextEndedByToolCall_fullTextCarriesTheGrounding() {
    GroundingMetadata grounding = GroundingMetadata.builder().webSearchQueries("weather").build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("Checking.")))
                    .groundingMetadata(grounding)),
            toolCallMessage(FunctionCall.builder().name("lookup").build()),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    assertThat(describe(responses))
        .containsExactly("partial text:Checking.", "text:Checking.", "call:lookup", "turnComplete")
        .inOrder();
    assertThat(groundings(responses))
        .containsExactly(
            Optional.of(grounding), Optional.of(grounding), Optional.empty(), Optional.empty())
        .inOrder();
  }

  @Test
  public void
      convertToServerResponse_groundingMergeAfterNoChunks_keepsChunkIndicesAndImageQueries() {
    GroundingMetadata first = GroundingMetadata.builder().imageSearchQueries("cat").build();
    GroundingMetadata second =
        GroundingMetadata.builder()
            .imageSearchQueries("cat", "dog")
            .groundingChunks(chunk(1))
            .groundingSupports(GroundingSupport.builder().groundingChunkIndices(0))
            .build();

    ImmutableList<LlmResponse> responses =
        convertAllResponses(
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("A")))
                    .groundingMetadata(first)),
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("B")))
                    .groundingMetadata(second)),
            serverContent(LiveServerContent.builder().turnComplete(true)));

    GroundingMetadata merged = responses.get(2).groundingMetadata().get();
    assertThat(merged.imageSearchQueries()).hasValue(ImmutableList.of("cat", "dog"));
    assertThat(getOnlyElement(merged.groundingSupports().get()).groundingChunkIndices())
        .hasValue(ImmutableList.of(0));
  }

  @Test
  public void runLive_groundedAnswer_eventsCarryTheGroundingMetadata() throws Exception {
    GroundingMetadata grounding =
        GroundingMetadata.builder()
            .webSearchQueries("weather today")
            .searchEntryPoint(SearchEntryPoint.builder().renderedContent("<p>Weather</p>"))
            .build();

    ImmutableList<Event> events =
        liveEvents(
            "gemini-3.8-live",
            serverContent(
                LiveServerContent.builder()
                    .modelTurn(modelContent(Part.fromText("It is sunny.")))
                    .groundingMetadata(grounding)
                    .turnComplete(true)));

    assertThat(
            events.stream()
                .filter(event -> event.groundingMetadata().isPresent())
                .map(event -> event.groundingMetadata().get())
                .collect(toImmutableList()))
        .containsExactly(grounding, grounding);
  }

  @Test
  public void isGemini35LiveTranslate_matchesOnlyLiveTranslateModels() {
    assertThat(GeminiLlmConnection.isGemini35LiveTranslate("gemini-3.5-live-translate")).isTrue();
    assertThat(
            GeminiLlmConnection.isGemini35LiveTranslate(
                "projects/p/locations/l/publishers/google/models/gemini-3.5-live-translate-preview"))
        .isTrue();
    assertThat(GeminiLlmConnection.isGemini35LiveTranslate("gemini-3.8-live")).isFalse();
    assertThat(GeminiLlmConnection.isGemini35LiveTranslate(null)).isFalse();
  }

  /**
   * Runs an agent live through {@link InMemoryRunner} on a Gemini named {@code modelName} whose
   * transport delivers {@code messages}, and describes the transcriptions the session saved.
   */
  private static ImmutableList<String> savedTranscriptions(
      String modelName, LiveServerMessage... messages) throws Exception {
    return describe(
        liveEvents(modelName, messages).stream()
            .filter(event ->
                event.inputTranscription().isPresent() || event.outputTranscription().isPresent())
            .map(GeminiLlmConnectionTest::transcriptionsOf)
            .collect(toImmutableList()));
  }

  /**
   * Runs an agent live through {@link InMemoryRunner} on a Gemini named {@code modelName} whose
   * transport delivers {@code messages}, and returns the events the session saved.
   */
  private static ImmutableList<Event> liveEvents(String modelName, LiveServerMessage... messages)
      throws Exception {
    CompletableFuture<GeminiLiveTransport> connecting = new CompletableFuture<>();
    Gemini gemini =
        new Gemini(modelName, "fake-api-key") {
          @Override
          protected CompletableFuture<GeminiLiveTransport> connectLiveTransport(
              String name, LiveConnectConfig config) {
            return connecting;
          }
        };
    InMemoryRunner runner =
        new InMemoryRunner(LlmAgent.builder().name("agent").model(gemini).build());
    Session session = runner.sessionService().createSession(runner.appName(), "user").blockingGet();

    TestSubscriber<Event> events =
        runner
            .runLive(
                session,
                new LiveRequestQueue(),
                RunConfig.builder().setStreamingMode(StreamingMode.BIDI).build())
            .test();
    // runLive subscribes to the connection's responses before the transport connects.
    connecting.complete(
        new FakeTransport(/* endStreamOnReceive= */ true, ImmutableList.copyOf(messages)));
    events.awaitDone(5, TimeUnit.SECONDS).assertComplete();

    Session saved =
        runner
            .sessionService()
            .getSession(runner.appName(), "user", session.id(), Optional.empty())
            .blockingGet();
    return ImmutableList.copyOf(saved.immutableEvents());
  }

  private static LlmResponse transcriptionsOf(Event event) {
    return LlmResponse.builder()
        .inputTranscription(event.inputTranscription().orElse(null))
        .outputTranscription(event.outputTranscription().orElse(null))
        .partial(event.partial().orElse(null))
        .build();
  }

  private static Transcription chunk(String text) {
    return Transcription.builder().text(text).build();
  }

  private static GroundingChunk chunk(int number) {
    return GroundingChunk.builder()
        .web(GroundingChunkWeb.builder().uri("https://example.com/" + number))
        .build();
  }

  private static LiveServerMessage toolCallMessage(FunctionCall functionCall) {
    return LiveServerMessage.builder()
        .toolCall(
            LiveServerToolCall.builder().functionCalls(ImmutableList.of(functionCall)).build())
        .build();
  }

  /** Returns the text of all parts in the turns of {@code clientContent}. */
  private static ImmutableList<String> texts(LiveSendClientContentParameters clientContent) {
    return clientContent.turns().orElse(ImmutableList.of()).stream()
        .flatMap(turn -> turn.parts().orElse(ImmutableList.of()).stream())
        .flatMap(part -> part.text().stream())
        .collect(toImmutableList());
  }

  /** Converts {@code messages} in order with fresh pending state and returns the responses. */
  private static ImmutableList<LlmResponse> convertAllResponses(LiveServerMessage... messages) {
    return convertAllResponses(
        new PendingTranscriptions(/* inputArrivesInFull= */ false), messages);
  }

  /** Converts {@code messages} in order with the given pending state and returns the responses. */
  private static ImmutableList<LlmResponse> convertAllResponses(
      PendingTranscriptions pendingTranscriptions, LiveServerMessage... messages) {
    PendingText pendingText = new PendingText();
    ImmutableList.Builder<LlmResponse> responses = ImmutableList.builder();
    for (LiveServerMessage message : messages) {
      responses.addAll(
          GeminiLlmConnection.convertToServerResponse(message, pendingText, pendingTranscriptions)
              .toList()
              .blockingGet());
    }
    return responses.build();
  }

  private static ImmutableList<Optional<GroundingMetadata>> groundings(
      List<LlmResponse> responses) {
    return responses.stream().map(LlmResponse::groundingMetadata).collect(toImmutableList());
  }

  /** Converts {@code messages} in order with fresh pending state, as a connection does. */
  private static ImmutableList<String> convertAll(LiveServerMessage... messages) {
    return convertAll(new PendingTranscriptions(/* inputArrivesInFull= */ false), messages);
  }

  /** Converts {@code messages} in order with the given pending state, as a connection does. */
  private static ImmutableList<String> convertAll(
      PendingTranscriptions pendingTranscriptions, LiveServerMessage... messages) {
    return describe(convertAllResponses(pendingTranscriptions, messages));
  }

  private static LiveServerMessage modelTurn(Part part) {
    return serverContent(LiveServerContent.builder().modelTurn(modelContent(part)));
  }

  private static LiveServerMessage serverContent(LiveServerContent.Builder serverContent) {
    return LiveServerMessage.builder().serverContent(serverContent.build()).build();
  }

  private static Content modelContent(Part part) {
    return Content.builder().role("model").parts(part).build();
  }

  /**
   * Describes each response by its partial flag, parts and turn signals, such as {@code partial
   * text:Hel} or {@code turnComplete}.
   */
  private static ImmutableList<String> describe(List<LlmResponse> responses) {
    return responses.stream()
        .map(response -> {
          List<String> words = new ArrayList<>();
          if (response.partial().orElse(false)) {
            words.add("partial");
          }
          response.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
              .map(GeminiLlmConnectionTest::describePart)
              .forEach(words::add);
          response
              .inputTranscription()
              .ifPresent(t -> words.add(describeTranscription("input:", t)));
          response
              .outputTranscription()
              .ifPresent(t -> words.add(describeTranscription("output:", t)));
          if (response.turnComplete().orElse(false)) {
            words.add("turnComplete");
          }
          if (response.interrupted().orElse(false)) {
            words.add("interrupted");
          }
          return String.join(" ", words);
        })
        .collect(toImmutableList());
  }

  private static String describeTranscription(String label, Transcription transcription) {
    return (transcription.finished().orElse(false) ? "finished " : "")
        + label
        + transcription.text().orElse("");
  }

  private static String describePart(Part part) {
    if (part.text().isPresent()) {
      return (part.thought().orElse(false) ? "thought:" : "text:") + part.text().get();
    }
    if (part.functionCall().isPresent()) {
      return "call:" + part.functionCall().get().name().orElse("");
    }
    if (part.inlineData().isPresent()) {
      return "inlineData";
    }
    return part.executableCode().isPresent() ? "executableCode" : "other";
  }

  private static Content userContent(Part... parts) {
    return Content.builder().role("user").parts(parts).build();
  }

  private static Part inlineAudio() {
    return Part.fromBytes(new byte[] {0, 0}, "audio/pcm;rate=16000");
  }

  private static LiveSendClientContentParameters clientContent(
      List<Content> turns, boolean turnComplete) {
    return LiveSendClientContentParameters.builder()
        .turns(turns)
        .turnComplete(turnComplete)
        .build();
  }

  /**
   * A minimal in-process {@link GeminiLiveTransport} that delivers the given messages, records
   * sends and closes and can end its stream.
   */
  private static class FakeTransport implements GeminiLiveTransport {
    private final boolean endStreamOnReceive;
    private final ImmutableList<LiveServerMessage> messages;
    final AtomicInteger closeCount = new AtomicInteger();
    final List<LiveSendClientContentParameters> clientContents = new CopyOnWriteArrayList<>();
    final List<LiveSendToolResponseParameters> toolResponses = new CopyOnWriteArrayList<>();
    final List<LiveSendRealtimeInputParameters> realtimeInputs = new CopyOnWriteArrayList<>();

    FakeTransport(boolean endStreamOnReceive) {
      this(endStreamOnReceive, ImmutableList.of());
    }

    FakeTransport(boolean endStreamOnReceive, ImmutableList<LiveServerMessage> messages) {
      this.endStreamOnReceive = endStreamOnReceive;
      this.messages = messages;
    }

    @Override
    public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params) {
      clientContents.add(params);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters params) {
      realtimeInputs.add(params);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters params) {
      toolResponses.add(params);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> receive(
        Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
      messages.forEach(onMessage);
      if (endStreamOnReceive) {
        onStreamEnd.run();
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
      closeCount.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }
  }

  /** Exercises the real connection's send path without opening a WebSocket or using credentials. */
  private static final class LiveConnectionFixture implements AutoCloseable {
    final FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ false);
    final GeminiLlmConnection connection;

    LiveConnectionFixture() {
      this("gemini-live");
    }

    LiveConnectionFixture(String modelName) {
      connection = new GeminiLlmConnection(modelName, CompletableFuture.completedFuture(transport));
    }

    @Override
    public void close() {
      connection.close();
    }
  }
}
