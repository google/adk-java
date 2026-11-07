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

import com.google.adk.models.GeminiLlmConnection.PendingText;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Language;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerSetupComplete;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.LiveServerToolCallCancellation;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import com.google.genai.types.UsageMetadata;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.ArrayList;
import java.util.List;
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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);
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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);
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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

    testObserver.assertNoValues();
    testObserver.assertComplete();
  }

  @Test
  public void convertToServerResponse_withUnknownMessage_returnsErrorResponse() {
    LiveServerMessage message = LiveServerMessage.builder().build();

    TestObserver<LlmResponse> testObserver = new TestObserver<>();

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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

    GeminiLlmConnection.convertToServerResponse(message, new PendingText()).subscribe(testObserver);

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
        .containsExactly("partial text:Hel", "text:Hel interrupted", "output:Hel")
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
        .containsExactly("output:Hel interrupted");
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
        GeminiLlmConnection.convertToServerResponse(message, new PendingText())
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
    // The partial text response is not persisted, so the transcription is not put on it.
    assertThat(
            convertAll(
                serverContent(
                    LiveServerContent.builder()
                        .modelTurn(modelContent(Part.fromText("Hel")))
                        .inputTranscription(Transcription.builder().text("Hi").build()))))
        .containsExactly("partial text:Hel", "input:Hi")
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
    GeminiLlmConnection connection = new GeminiLlmConnection(transportFuture);
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
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));

    TestSubscriber<LlmResponse> subscriber = connection.receive().test();
    subscriber.awaitDone(5, TimeUnit.SECONDS);

    subscriber.assertComplete();
    subscriber.assertNoErrors();
  }

  @Test
  public void close_afterTransportStreamEnd_isNoOp() {
    FakeTransport transport = new FakeTransport(/* endStreamOnReceive= */ true);
    GeminiLlmConnection connection =
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));
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
                      artifact)))
          .blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(
              clientContent(
                  ImmutableList.of(
                      userContent(Part.fromText("Look at this."), image),
                      modelContent(Part.fromText("I see it.")),
                      userContent(artifact)),
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
    ImmutableList<Content> textTurns =
        ImmutableList.of(userContent(Part.fromText("hi")), modelContent(Part.fromText("hello")));
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture
          .connection
          .sendHistory(
              ImmutableList.<Content>builder()
                  .addAll(textTurns)
                  .add(userContent(inlineAudio()))
                  .build())
          .blockingAwait();

      assertThat(fixture.transport.clientContents)
          .containsExactly(clientContent(textTurns, /* turnComplete= */ false));
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
  public void sendHistory_emptyHistory_sendsNothing() {
    try (LiveConnectionFixture fixture = new LiveConnectionFixture()) {
      fixture.connection.sendHistory(ImmutableList.of()).blockingAwait();

      assertThat(fixture.transport.clientContents).isEmpty();
    }
  }

  /** Converts {@code messages} in order with one {@link PendingText}, as a connection does. */
  private static ImmutableList<String> convertAll(LiveServerMessage... messages) {
    PendingText pendingText = new PendingText();
    List<LlmResponse> responses = new ArrayList<>();
    for (LiveServerMessage message : messages) {
      responses.addAll(
          GeminiLlmConnection.convertToServerResponse(message, pendingText).toList().blockingGet());
    }
    return describe(responses);
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
        .map(
            response -> {
              List<String> words = new ArrayList<>();
              if (response.partial().orElse(false)) {
                words.add("partial");
              }
              response.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
                  .map(GeminiLlmConnectionTest::describePart)
                  .forEach(words::add);
              response
                  .inputTranscription()
                  .flatMap(Transcription::text)
                  .ifPresent(text -> words.add("input:" + text));
              response
                  .outputTranscription()
                  .flatMap(Transcription::text)
                  .ifPresent(text -> words.add("output:" + text));
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
  private static final class FakeTransport implements GeminiLiveTransport {
    private final boolean endStreamOnReceive;
    private final ImmutableList<LiveServerMessage> messages;
    final AtomicInteger closeCount = new AtomicInteger();
    final List<LiveSendClientContentParameters> clientContents = new CopyOnWriteArrayList<>();
    final List<LiveSendToolResponseParameters> toolResponses = new CopyOnWriteArrayList<>();

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
    final GeminiLlmConnection connection =
        new GeminiLlmConnection(CompletableFuture.completedFuture(transport));

    @Override
    public void close() {
      connection.close();
    }
  }
}
